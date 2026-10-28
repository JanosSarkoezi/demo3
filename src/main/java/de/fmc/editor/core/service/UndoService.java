package de.fmc.editor.core.service;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.Connection;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.Waypoint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Sitzungs-Undo/Redo über den Bus — Undo ist nur ein weiterer Schreiber.
 *
 * Design-Regel (die "Meditations-Regel"): Ein Modell-Fakt ist genau dann
 * undo-fähig, wenn er den vollständigen VOR-Zustand trägt. Dieser Service
 * leitet aus den Fakten mechanisch inverse Requests ab — er besitzt kein
 * eigenes Kaskade-Wissen (demo2-Lektion: Dort drifteten Kaskade und
 * Undo-Command auseinander; Undo verlor dauerhaft mitgelöschte Texte).
 *
 * Wie SelectionService ist die History Sitzungszustand, bewusst NICHT im
 * DiagramService — sie wird niemals Teil des Modellinhalts (Persistenz).
 *
 * Funktionsweise:
 * - Modell-ändernde Fakten werden außerhalb des Replay-Guards als Undo-Ops
 *   im laufenden Gesten-Eintrag gesammelt; SelectionChanged und Kamera-
 *   Fakten bleiben bewusst draußen (Sitzungszustand).
 * - Gesten-Grenzen: MousePressed/MouseReleased/KeyPressed schließen den
 *   laufenden Eintrag. Alle Fakten einer Geste fallen synchron innerhalb
 *   eines Requests an — MouseDragged ist KEINE Grenze, damit Move-Segmente
 *   zu EINEM Eintrag verschmelzen (pro Geste genau ein Undo-Punkt).
 * - RequestUndo: inversen Requests in umgekehrter Ops-Reihenfolge publizieren
 *   (MoveObjectTo absolut — kein Float-Drift). RequestRedo: Original-
 *   Requests vorwärts. Der Replay-Guard verhindert, dass die dabei
 *   entstehenden Fakten wieder in die History laufen (Undo frisst sich
 *   sonst selbst) und dämpft zugleich doppelte Kaskaden (RemoveConnections
 *   auf bereits entfernte Connections ist ein stiller No-Op).
 * - Neue Aktion leert den Redo-Stack (klassische Semantik).
 */
public class UndoService {

    /** Eine undo-fähige Operation, abgeleitet aus einem Fakt (oder gemergten Fakten). */
    private sealed interface UndoOp permits AddedObject, AddedConnection, MovedObject,
            MovedObjects, ResizedObject, RemovedObjects, RemovedConnections, UpdatedConnections {}

    private record AddedObject(DiagramObject object) implements UndoOp {}
    private record AddedConnection(Connection connection) implements UndoOp {}
    private record MovedObject(UUID id, double oldX, double oldY, double newX, double newY) implements UndoOp {}
    private record MovedObjects(List<DiagramEvent.ObjectMoved> moves) implements UndoOp {}
    private record ResizedObject(DiagramObject previous, DiagramObject resized) implements UndoOp {}
    private record RemovedObjects(List<DiagramObject> objects, List<Integer> zIndices) implements UndoOp {}
    private record RemovedConnections(List<Connection> connections) implements UndoOp {}
    private record UpdatedConnections(List<Connection> previous, List<Connection> updated) implements UndoOp {}

    private final Deque<List<UndoOp>> undoStack = new ArrayDeque<>();
    private final Deque<List<UndoOp>> redoStack = new ArrayDeque<>();
    private List<UndoOp> pending = new ArrayList<>();

    /** true während Undo/Redo: Entstehende Fakten werden NICHT aufgezeichnet. */
    private boolean replaying = false;

    private InteractionEventBus bus;

    /** Zweite Aufbau-Phase — analog zu DiagramService.attachTo(bus). */
    public void attachTo(InteractionEventBus bus) {
        this.bus = bus;

        bus.subscribe(ActionEvent.RequestUndo.class, e -> onUndo());
        bus.subscribe(ActionEvent.RequestRedo.class, e -> onRedo());

        // Modell-ändernde Fakten → Ops
        bus.subscribe(DiagramEvent.ObjectAdded.class, fact -> record(new AddedObject(fact.object())));
        bus.subscribe(DiagramEvent.ObjectMoved.class, this::onMoved);
        bus.subscribe(DiagramEvent.ObjectsMoved.class, this::onMovedBatch);
        bus.subscribe(DiagramEvent.ObjectResized.class,
                fact -> record(new ResizedObject(fact.previous(), fact.resized())));
        bus.subscribe(DiagramEvent.ConnectionEstablished.class,
                fact -> record(new AddedConnection(fact.connection())));
        bus.subscribe(DiagramEvent.ObjectsRemoved.class,
                fact -> record(new RemovedObjects(fact.objects(), fact.zIndices())));
        bus.subscribe(DiagramEvent.ConnectionsRemoved.class,
                fact -> record(new RemovedConnections(fact.connections())));
        bus.subscribe(DiagramEvent.ConnectionsUpdated.class,
                fact -> record(new UpdatedConnections(fact.previous(), fact.updated())));

        // Eingaben als Gesten-Grenzen: schließen den laufenden Eintrag.
        bus.subscribe(InputEvent.class, this::onBoundary);
    }

    // ---------- Gesten-Grenzen ----------

    private void onBoundary(InputEvent event) {
        if (replaying) {
            return;
        }
        // MouseDragged ist bewusst KEINE Grenze: Move-Segmente verschmelzen.
        if (event instanceof InputEvent.MousePressed
                || event instanceof InputEvent.MouseReleased
                || event instanceof InputEvent.KeyPressed) {
            closePending();
        }
    }

    private void closePending() {
        if (!pending.isEmpty()) {
            undoStack.push(pending);
            pending = new ArrayList<>();
        }
    }

    // ---------- Fakten → Ops ----------

    private void record(UndoOp op) {
        if (replaying) {
            return;
        }
        redoStack.clear(); // Neue Aktion macht Redo-Altbestand ungültig.
        pending.add(op);
    }

    private void onMoved(DiagramEvent.ObjectMoved fact) {
        if (replaying) {
            return;
        }
        // Merge: aufeinanderfolgende Move-Fakten desselben Objekts sind EINE Geste.
        if (!pending.isEmpty() && pending.getLast() instanceof MovedObject merge
                && merge.id().equals(fact.id())) {
            redoStack.clear();
            pending.set(pending.size() - 1,
                    new MovedObject(merge.id(), merge.oldX(), merge.oldY(), fact.newX(), fact.newY()));
            return;
        }
        record(new MovedObject(fact.id(), fact.oldX(), fact.oldY(), fact.newX(), fact.newY()));
    }

    private void onMovedBatch(DiagramEvent.ObjectsMoved fact) {
        if (replaying) {
            return;
        }
        // Merge: aufeinanderfolgende Batch-Moves derselben ID-Menge sind
        // EINE Geste (je ID: Vor-Position aus dem ersten, Nach-Position aus
        // dem letzten Segment).
        if (!pending.isEmpty() && pending.getLast() instanceof MovedObjects last
                && sameIds(last.moves(), fact.movedObjects())) {
            redoStack.clear();
            pending.set(pending.size() - 1,
                    new MovedObjects(mergeMoves(last.moves(), fact.movedObjects())));
            return;
        }
        record(new MovedObjects(fact.movedObjects()));
    }

    private boolean sameIds(List<DiagramEvent.ObjectMoved> a,
                            List<DiagramEvent.ObjectMoved> b) {
        return idsOf(a).equals(idsOf(b));
    }

    private Set<UUID> idsOf(List<DiagramEvent.ObjectMoved> moves) {
        return moves.stream()
                .map(DiagramEvent.ObjectMoved::id)
                .collect(Collectors.toSet());
    }

    private List<DiagramEvent.ObjectMoved> mergeMoves(List<DiagramEvent.ObjectMoved> first,
                                                             List<DiagramEvent.ObjectMoved> next) {
        Map<UUID, DiagramEvent.ObjectMoved> origins = new LinkedHashMap<>();
        for (DiagramEvent.ObjectMoved move : first) {
            origins.putIfAbsent(move.id(), move);
        }
        List<DiagramEvent.ObjectMoved> merged = new ArrayList<>();
        for (DiagramEvent.ObjectMoved move : next) {
            DiagramEvent.ObjectMoved origin = origins.get(move.id());
            merged.add(new DiagramEvent.ObjectMoved(
                    move.id(), origin.oldX(), origin.oldY(), move.newX(), move.newY()));
        }
        return merged;
    }

    // ---------- Undo / Redo ----------

    private void onUndo() {
        closePending();
        if (undoStack.isEmpty()) {
            return;
        }
        List<UndoOp> entry = undoStack.pop();
        replaying = true;
        try {
            for (int i = entry.size() - 1; i >= 0; i--) {
                publishInverse(entry.get(i));
            }
        } finally {
            replaying = false;
        }
        redoStack.push(entry);
    }

    private void onRedo() {
        if (redoStack.isEmpty()) {
            return;
        }
        List<UndoOp> entry = redoStack.pop();
        replaying = true;
        try {
            for (UndoOp op : entry) {
                publishReplay(op);
            }
        } finally {
            replaying = false;
        }
        undoStack.push(entry);
    }

    /** Inverse Requests — rückwärts durch den Eintrag (zuletzt Gewordenes zuerst zurück). */
    private void publishInverse(UndoOp op) {
        switch (op) {
            case AddedObject added -> bus.publish(new ActionEvent.DeleteObjects(List.of(added.object().id())));
            case AddedConnection added -> bus.publish(
                    new ActionEvent.RemoveConnections(List.of(added.connection().id())));
            case MovedObject move -> bus.publish(new ActionEvent.MoveObjectTo(move.id(), move.oldX(), move.oldY()));
            case MovedObjects moves -> {
                for (int i = moves.moves().size() - 1; i >= 0; i--) {
                    DiagramEvent.ObjectMoved move = moves.moves().get(i);
                    bus.publish(new ActionEvent.MoveObjectTo(move.id(), move.oldX(), move.oldY()));
                }
            }
            case ResizedObject resize -> bus.publish(resizeRequest(resize.previous()));
            case RemovedObjects removed -> bus.publish(
                    new ActionEvent.RestoreObjects(removed.objects(), removed.zIndices()));
            case RemovedConnections removed -> removed.connections().forEach(
                    conn -> bus.publish(new ActionEvent.AddConnection(conn)));
            case UpdatedConnections updated -> updated.previous().forEach(
                    conn -> bus.publish(new ActionEvent.AddConnection(conn)));
        }
    }

    /** Original-Requests vorwärts — Redo ist schlicht Replay (kein "recreate" nötig). */
    private void publishReplay(UndoOp op) {
        switch (op) {
            case AddedObject added -> bus.publish(new ActionEvent.AddElement(added.object()));
            case AddedConnection added -> bus.publish(new ActionEvent.AddConnection(added.connection()));
            case MovedObject move -> bus.publish(new ActionEvent.MoveObjectTo(move.id(), move.newX(), move.newY()));
            case MovedObjects moves -> {
                for (DiagramEvent.ObjectMoved move : moves.moves()) {
                    bus.publish(new ActionEvent.MoveObjectTo(move.id(), move.newX(), move.newY()));
                }
            }
            case ResizedObject resize -> bus.publish(resizeRequest(resize.resized()));
            case RemovedObjects removed -> bus.publish(new ActionEvent.DeleteObjects(
                    removed.objects().stream().map(DiagramObject::id).toList()));
            case RemovedConnections removed -> bus.publish(new ActionEvent.RemoveConnections(
                    removed.connections().stream().map(Connection::id).toList()));
            case UpdatedConnections updated -> updated.updated().forEach(
                    conn -> bus.publish(new ActionEvent.AddConnection(conn)));
        }
    }

    private ActionEvent resizeRequest(DiagramObject target) {
        return switch (target) {
            case Circle c -> new ActionEvent.ResizeCircle(c.id(), c.radius());
            case Rectangle r -> new ActionEvent.ResizeObject(r.id(), r.width(), r.height());
            // Waypoints haben keine Handles — der Service ignoriert das still.
            case Waypoint w -> new ActionEvent.ResizeObject(w.id(), 0.0, 0.0);
        };
    }
}
