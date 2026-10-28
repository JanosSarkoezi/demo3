package de.fmc.editor.core.service;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.Connection;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.model.DiagramQuery;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.Text;
import de.fmc.editor.core.model.TextAnchor;
import de.fmc.editor.core.model.Waypoint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Zentrale Datenhalterung des Diagramms.
 *
 * Der einzige Schreibpfad ist der Event-Bus: Der Service besitzt keine
 * öffentlichen Mutatoren, damit States und UI niemals direkt schreiben können
 * (Goldene Regel: Einbahnstraßen-Kommunikation). Er konsumiert ActionEvents
 * (Requests) und veröffentlicht DiagramEvents (Fakten).
 *
 * Nicht threadsicher — Aufrufe erfolgen über den Bus, also aus einem Thread.
 */
public class DiagramService implements DiagramQuery {

    /**
     * Objekt-Store als Zwei-Struktur-Kombination:
     * - HashMap für O(1)-Lookup per UUID,
     * - zOrder (UUIDs, Index 0 = ganz unten) als EXPLIZITE z-Ordnung.
     * Die Liste trägt nur IDs: Move/Resize berühren allein die Map; nur
     * Hinzufügen/Entfernen synchronisieren beide Strukturen. Undo kann
     * Objekte damit an ihrer ORIGINAL-Position wieder einfügen
     * (ObjectsRemoved trägt die z-Indizes) statt oben aufzulegen.
     */
    private final Map<UUID, DiagramObject> objects = new HashMap<>();
    private final List<UUID> zOrder = new ArrayList<>();
    private final Map<UUID, Connection> connections = new LinkedHashMap<>();
    private final List<ConnectionRule> connectionRules = new ArrayList<>();

    /** Erst nach attachTo(bus) gesetzt — zweiphasige Initialisierung. */
    private InteractionEventBus bus;

    public DiagramService() {
        // FMC-Regel: Objekte des gleichen Typs können nicht verbunden werden
        // (z. B. Kreis zu Kreis).
        connectionRules.add((source, target) ->
                source.getClass().equals(target.getClass())
                        ? Optional.of("Objekte des gleichen Typs können nicht verbunden werden")
                        : Optional.empty());
    }

    /**
     * Zweite Aufbau-Phase. Löst die Zirkularität Bus <-> Service auf:
     * Der Bus wird mit dem Service als DiagramQuery-Lieferant konstruiert,
     * danach abonniert der Service die ActionEvents am Bus.
     */
    public void attachTo(InteractionEventBus bus) {
        this.bus = bus;
        bus.subscribe(ActionEvent.AddElement.class, this::onAddElement);
        bus.subscribe(ActionEvent.MoveObject.class, this::onMoveObject);
        bus.subscribe(ActionEvent.ResizeObject.class, this::onResizeObject);
        bus.subscribe(ActionEvent.ResizeCircle.class, this::onResizeCircle);
        bus.subscribe(ActionEvent.CreateConnection.class, this::onCreateConnection);
        bus.subscribe(ActionEvent.DeleteObjects.class, this::onDeleteObjects);
        bus.subscribe(ActionEvent.RestoreObjects.class, this::onRestoreObjects);
        bus.subscribe(ActionEvent.MoveObjectTo.class, this::onMoveObjectTo);
        bus.subscribe(ActionEvent.MoveObjects.class, this::onMoveObjects);
        bus.subscribe(ActionEvent.AddConnection.class, this::onAddConnection);
        bus.subscribe(ActionEvent.RemoveConnections.class, this::onRemoveConnections);
        bus.subscribe(ActionEvent.SetCircleLabel.class, this::onSetCircleLabel);
        bus.subscribe(ActionEvent.SetRectangleContent.class, this::onSetRectangleContent);
        bus.subscribe(ActionEvent.UpdateText.class, this::onUpdateText);
        bus.subscribe(ActionEvent.SetAnchor.class, this::onSetAnchor);
        bus.subscribe(ActionEvent.AddAnchoredText.class, this::onAddAnchoredText);
    }

    // ---------- Schreibpfad: ActionEvents (Requests) konsumieren ----------

    private void onAddElement(ActionEvent.AddElement event) {
        addToModel(event.object());
        publishFact(new DiagramEvent.ObjectAdded(event.object()));
    }

    /** Fügt oben in die z-Ordnung ein; bestehende IDs behalten ihre Position. */
    private void addToModel(DiagramObject obj) {
        objects.put(obj.id(), obj);
        if (!zOrder.contains(obj.id())) {
            zOrder.add(obj.id());
        }
    }

    private void onMoveObject(ActionEvent.MoveObject event) {
        DiagramObject current = objects.get(event.id());
        if (current == null) {
            return; // Unbekannte ID still ignorieren — kein Crash
        }
        applyMove(current, current.x() + event.deltaX(), current.y() + event.deltaY());
    }

    /** Absolute Zielposition — das präzise Inverse für Undo (kein Float-Drift). */
    private void onMoveObjectTo(ActionEvent.MoveObjectTo event) {
        DiagramObject current = objects.get(event.id());
        if (current == null) {
            return;
        }
        applyMove(current, event.x(), event.y());
    }

    /** Gruppenverschiebung: EIN Batch-Fakt pro Segment, egal wie viele Objekte. */
    private void onMoveObjects(ActionEvent.MoveObjects event) {
        List<DiagramEvent.ObjectMoved> applied = new ArrayList<>();
        for (UUID id : event.ids()) {
            DiagramObject current = objects.get(id);
            if (current == null || isAnchored(current)) {
                continue; // Unbekannte ID still ignorieren; verankerte Texte
                          // bewegen sich NUR durch Re-Ankerung (SetAnchor),
                          // ihre Position ist abgeleitet.
            }
            double oldX = current.x();
            double oldY = current.y();
            DiagramObject moved = moved(current, event.deltaX(), event.deltaY());
            objects.put(id, moved);
            applied.add(new DiagramEvent.ObjectMoved(id, oldX, oldY, moved.x(), moved.y()));
        }
        if (!applied.isEmpty()) {
            publishFact(new DiagramEvent.ObjectsMoved(List.copyOf(applied)));
        }
    }

    private void applyMove(DiagramObject current, double targetX, double targetY) {
        if (isAnchored(current)) {
            return; // Verankerte Texte sind nicht delta-verschiebbar.
        }
        double oldX = current.x();
        double oldY = current.y();
        DiagramObject moved = moved(current, targetX - oldX, targetY - oldY);
        objects.put(moved.id(), moved);
        publishFact(new DiagramEvent.ObjectMoved(moved.id(), oldX, oldY, moved.x(), moved.y()));
    }

    /** Verankerte Texte sind abgeleitete Positionen — nie direkte Move-Ziele. */
    private boolean isAnchored(DiagramObject obj) {
        return obj instanceof Text text && text.anchor() != null;
    }

    private void onResizeObject(ActionEvent.ResizeObject event) {
        DiagramObject current = objects.get(event.id());
        DiagramObject resized = null;
        if (current instanceof Rectangle rectangle) {
            resized = new Rectangle(rectangle.id(), rectangle.x(), rectangle.y(),
                    event.newWidth(), event.newHeight(), rectangle.content(),
                    rectangle.centered(), rectangle.layerId());
        } else if (current instanceof Text text) {
            // Text-Box: nur Maße ändern — Anker/Owner/Position bleiben.
            resized = new Text(text.id(), text.content(), text.x(), text.y(),
                    event.newWidth(), event.newHeight(), text.anchor(), text.ownerId(), text.layerId());
        }
        if (resized != null) {
            objects.put(resized.id(), resized);
            publishFact(new DiagramEvent.ObjectResized(current, resized));
        }
        // Keine Box (oder unbekannte ID) => kein Fakt, nichts passiert.
    }

    private void onResizeCircle(ActionEvent.ResizeCircle event) {
        if (objects.get(event.id()) instanceof Circle circle) {
            Circle resized = new Circle(circle.id(), circle.x(), circle.y(),
                    event.newRadius(), circle.label(), circle.layerId());
            objects.put(resized.id(), resized);
            publishFact(new DiagramEvent.ObjectResized(circle, resized));
        }
        // Kein Circle (oder unbekannte ID) => kein Fakt, nichts passiert.
    }

    private void onCreateConnection(ActionEvent.CreateConnection event) {
        DiagramObject source = objects.get(event.sourceId());
        DiagramObject target = objects.get(event.targetId());
        if (source == null || target == null) {
            return; // Ohne gültige Endpunkte keine Verbindung
        }

        // Validierung zentral hier, nie in den States:
        Optional<String> violation = connectionRules.stream()
                .flatMap(rule -> rule.violation(source, target).stream())
                .findFirst();
        if (violation.isPresent()) {
            publishFact(new DiagramEvent.ConnectionRejected(
                    event.sourceId(), event.targetId(), violation.get()));
            return;
        }

        // Waypoints werden zu echten, gespeicherten DiagramObjects
        // (spezifikationskonform): Die Connection referenziert sie nur per
        // ID und hält keine zweite Kopie. Pro Waypoint ein ObjectAdded-Fakt,
        // damit die spätere UI sie über denselben Pfad rendert.
        List<UUID> waypointIds = new ArrayList<>();
        for (Waypoint waypoint : event.waypoints()) {
            addToModel(waypoint);
            waypointIds.add(waypoint.id());
            publishFact(new DiagramEvent.ObjectAdded(waypoint));
        }

        Connection connection = new Connection(UUID.randomUUID(), event.sourceId(),
                event.targetId(), waypointIds, target.layerId());
        connections.put(connection.id(), connection);
        publishFact(new DiagramEvent.ConnectionEstablished(connection));
    }

    /**
     * Zentrales Löschen mit sichtbarer Kaskade: K/R-Löschen entfernt ihre
     * Connections mitsamt deren Waypoints; JEDES entfernte Objekt wird
     * zusätzlich aus allen Waypoint-Listen gestrichen — auch aus Connections,
     * die bestehen bleiben (geteilte Waypoints räumen sich so automatisch
     * mit ab, genau die Ecke, in der demo2s geteilte Logik kippte). Alle
     * Entfernungen erscheinen als Batch-Fakten mit vollständigem Zustand:
     * Das künftige Undo baut darauf auf und braucht kein eigenes
     * Kaskade-Wissen (demo2-Lektion).
     */
    private void onDeleteObjects(ActionEvent.DeleteObjects event) {
        // Phase 1 — Sammeln OHNE Mutation: alle betroffenen Objekte
        // (Primär-IDs + kaskadierte Waypoints) und Connections bestimmen.
        // putIfAbsent hält Geteilte (Waypoints mehrerer Connections) genau einmal.
        LinkedHashMap<UUID, DiagramObject> doomedObjects = new LinkedHashMap<>();
        LinkedHashMap<UUID, Connection> doomedConnections = new LinkedHashMap<>();
        for (UUID id : event.ids()) {
            DiagramObject obj = objects.get(id);
            if (obj == null) {
                continue; // Unbekannte ID still ignorieren — kein Crash
            }
            if (!(obj instanceof Waypoint)) {
                connections.values().stream()
                        .filter(conn -> id.equals(conn.sourceId()) || id.equals(conn.targetId()))
                        .forEach(conn -> collectConnectionCascade(conn, doomedObjects, doomedConnections));
            }
            doomedObjects.putIfAbsent(obj.id(), obj);
        }

        // Phase 1b — Text-Kaskade: Texte, deren Owner (Objekt ODER Connection)
        // selbst doomed ist, gehören mit zur Lösch-Geste — als sichtbarer
        // Fakt, nicht versteckt (demo2-Lektion: Kaskade und Undo dürfen nie
        // auseinanderdriften, also lebt sie ausschließlich hier).
        for (DiagramObject obj : objects.values()) {
            if (obj instanceof Text text && text.ownerId() != null
                    && (doomedObjects.containsKey(text.ownerId())
                        || doomedConnections.containsKey(text.ownerId()))) {
                doomedObjects.putIfAbsent(text.id(), text);
            }
        }
        if (doomedObjects.isEmpty()) {
            return;
        }

        // Phase 2 — z-Indizes EINMAL vor der ersten Entfernung snapshoten:
        // Frühere Entfernungen derselben Geste würden sonst die Indizes
        // späterer Objekte verschieben. Aufsteigendes Wiedereinfügen nach
        // Original-Index stellt die exakte Reihenfolge wieder her.
        List<DiagramObject> removedObjects = new ArrayList<>(doomedObjects.values());
        List<Integer> zIndices = removedObjects.stream()
                .map(obj -> zOrder.indexOf(obj.id()))
                .toList();

        // Phase 3 — aus beiden Strukturen entfernen.
        for (DiagramObject obj : removedObjects) {
            objects.remove(obj.id());
            zOrder.remove(obj.id());
        }
        for (Connection conn : doomedConnections.values()) {
            connections.remove(conn.id());
        }

        // Phase 4 — IDs aus Waypoint-Listen überlebender Connections streichen.
        LinkedHashMap<UUID, Connection> updatedPrevious = new LinkedHashMap<>();
        LinkedHashMap<UUID, Connection> updatedAfter = new LinkedHashMap<>();
        for (Map.Entry<UUID, Connection> entry : connections.entrySet()) {
            Connection conn = entry.getValue();
            if (doomedObjects.keySet().stream().anyMatch(conn.waypointIds()::contains)) {
                List<UUID> prunedIds = new ArrayList<>(conn.waypointIds());
                prunedIds.removeAll(doomedObjects.keySet());
                Connection pruned = new Connection(conn.id(), conn.sourceId(),
                        conn.targetId(), prunedIds, conn.layerId());
                entry.setValue(pruned);
                // Nur beim ersten Strich den Original-Vor-Zustand sichern (Undo-Regel).
                updatedPrevious.putIfAbsent(conn.id(), conn);
                updatedAfter.put(pruned.id(), pruned);
            }
        }

        // Phase 5 — Fakten (vollständiger Zustand inkl. z-Indizes = Undo-Grundlage).
        publishFact(new DiagramEvent.ObjectsRemoved(removedObjects, zIndices));
        if (!updatedAfter.isEmpty()) {
            publishFact(new DiagramEvent.ConnectionsUpdated(
                    List.copyOf(updatedPrevious.values()), List.copyOf(updatedAfter.values())));
        }
        if (!doomedConnections.isEmpty()) {
            publishFact(new DiagramEvent.ConnectionsRemoved(List.copyOf(doomedConnections.values())));
        }
    }

    /** Sammelt eine Connection samt ihrer Waypoints für die Kaskade (ohne Mutation). */
    private void collectConnectionCascade(Connection conn,
                                          LinkedHashMap<UUID, DiagramObject> doomedObjects,
                                          LinkedHashMap<UUID, Connection> doomedConnections) {
        if (doomedConnections.putIfAbsent(conn.id(), conn) != null) {
            return; // Bereits gesammelt
        }
        for (UUID waypointId : conn.waypointIds()) {
            DiagramObject waypoint = objects.get(waypointId);
            if (waypoint != null) {
                doomedObjects.putIfAbsent(waypoint.id(), waypoint);
            }
        }
    }

    /**
     * Undo-Inverse von ObjectsRemoved: fügt die Objekte an ihren originalen
     * z-Positionen wieder ein — aufsteigend nach Original-Index, denn kleinere
     * Indizes müssen zuerst wieder besetzt sein. Die LIFO-Disziplin des Undo
     * macht die Indizes selbstheilend: jede spätere z-ändernde Geste ist
     * bereits rückgängig, wenn dieser Restore läuft.
     */
    private void onRestoreObjects(ActionEvent.RestoreObjects event) {
        int n = event.objects().size();
        Integer[] ascending = new Integer[n];
        for (int i = 0; i < n; i++) {
            ascending[i] = i;
        }
        Arrays.sort(ascending, (a, b) -> Integer.compare(event.zIndices().get(a), event.zIndices().get(b)));

        for (int pos : ascending) {
            DiagramObject obj = event.objects().get(pos);
            int insertAt = Math.min(event.zIndices().get(pos), zOrder.size());
            zOrder.remove(obj.id()); // defensiv: falls vorhanden, Position erneuern
            zOrder.add(insertAt, obj.id());
            objects.put(obj.id(), obj);
            publishFact(new DiagramEvent.ObjectAdded(obj));
        }
    }

    /** Stellt eine Connection mit ihrer ORIGINAL-ID wieder her (Undo des Löschens). */
    private void onAddConnection(ActionEvent.AddConnection event) {
        connections.put(event.connection().id(), event.connection());
        publishFact(new DiagramEvent.ConnectionEstablished(event.connection()));
    }

    /** Entfernt NUR die genannten Connections — ohne Waypoint-Kaskade (präzises Inverse). */
    private void onRemoveConnections(ActionEvent.RemoveConnections event) {
        List<Connection> removed = new ArrayList<>();
        for (UUID id : event.ids()) {
            Connection conn = connections.remove(id);
            if (conn != null) {
                removed.add(conn);
            }
        }
        if (!removed.isEmpty()) {
            publishFact(new DiagramEvent.ConnectionsRemoved(List.copyOf(removed)));
        }
    }

    // ---------- Text-Schreibpfad ----------

    private void onSetCircleLabel(ActionEvent.SetCircleLabel event) {
        if (objects.get(event.id()) instanceof Circle circle) {
            Circle updated = new Circle(circle.id(), circle.x(), circle.y(),
                    circle.radius(), event.label(), circle.layerId());
            objects.put(updated.id(), updated);
            publishFact(new DiagramEvent.ObjectTextChanged(circle, updated));
        }
        // Kein Circle (oder unbekannte ID) => kein Fakt, nichts passiert.
    }

    private void onSetRectangleContent(ActionEvent.SetRectangleContent event) {
        if (objects.get(event.id()) instanceof Rectangle rectangle) {
            Rectangle updated = new Rectangle(rectangle.id(), rectangle.x(), rectangle.y(),
                    rectangle.width(), rectangle.height(),
                    event.content(), event.centered(), rectangle.layerId());
            objects.put(updated.id(), updated);
            publishFact(new DiagramEvent.ObjectTextChanged(rectangle, updated));
        }
    }

    private void onUpdateText(ActionEvent.UpdateText event) {
        if (objects.get(event.id()) instanceof Text text) {
            Text updated = new Text(text.id(), event.content(), text.x(), text.y(),
                    event.width(), event.height(), text.anchor(), text.ownerId(), text.layerId());
            objects.put(updated.id(), updated);
            publishFact(new DiagramEvent.ObjectTextChanged(text, updated));
        }
    }

    /**
     * Re-Ankerung eines Textes: Der Service leitet x/y aus der AKTUELLEN
     * Verbindungskette ab (Geometrie-Wissen liegt zentral hier, nie in den
     * States). anchor = null löst den Anker — die Position bleibt, wo sie
     * ist (absolut ab jetzt); das ist bewusst anders als beim Setzen eines
     * Ankers, weil das Lösen ein reines Undo-Inverse ist.
     */
    private void onSetAnchor(ActionEvent.SetAnchor event) {
        if (!(objects.get(event.id()) instanceof Text text)) {
            return;
        }
        Text updated;
        if (event.anchor() == null) {
            updated = new Text(text.id(), text.content(), text.x(), text.y(),
                    text.width(), text.height(), null, text.ownerId(), text.layerId());
        } else {
            double[] center = anchoredCenter(event.anchor(), text.ownerId());
            updated = new Text(text.id(), text.content(), center[0], center[1],
                    text.width(), text.height(), event.anchor(), text.ownerId(), text.layerId());
        }
        objects.put(updated.id(), updated);
        publishFact(new DiagramEvent.ObjectTextChanged(text, updated));
    }

    private void onAddAnchoredText(ActionEvent.AddAnchoredText event) {
        if (!connections.containsKey(event.connectionId())) {
            return; // Ohne Connection kein Anker
        }
        double[] center = anchoredCenter(event.anchor(), event.connectionId());
        Text text = new Text(UUID.randomUUID(), event.content(), center[0], center[1],
                event.width(), event.height(), event.anchor(), event.connectionId(), event.layerId());
        addToModel(text);
        publishFact(new DiagramEvent.ObjectAdded(text));
    }

    // ---------- Lesepfad: DiagramQuery implementieren ----------

    @Override
    public Optional<DiagramObject> findTopmostAt(double worldX, double worldY) {
        // zOrder rückwärts = oberstes Objekt zuerst.
        // Waypoints sind keine Klick-Ziele (sonst trafe ein Klick beim
        // Verbindungsaufbau versehentlich einen vorhandenen Waypoint).
        for (int i = zOrder.size() - 1; i >= 0; i--) {
            DiagramObject obj = objects.get(zOrder.get(i));
            if (obj == null || obj instanceof Waypoint) {
                continue;
            }
            if (hit(obj, worldX, worldY)) {
                return Optional.of(obj);
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<DiagramObject> findById(UUID id) {
        return Optional.ofNullable(objects.get(id));
    }

    /**
     * Liefert alle Objekte, deren Bounding-Box das Welt-Rechteck schneidet —
     * in z-Ordnung (unten -> oben, deterministisch). Waypoints sind keine
     * Band-Ziele, solange sie keine Klick-Ziele sind (Layer-Sichtbarkeit fehlt).
     */
    @Override
    public List<DiagramObject> findObjectsInBounds(double minX, double minY, double maxX, double maxY) {
        List<DiagramObject> result = new ArrayList<>();
        for (UUID id : zOrder) {
            DiagramObject obj = objects.get(id);
            if (obj == null || obj instanceof Waypoint) {
                continue;
            }
            if (boundsIntersect(obj, minX, minY, maxX, maxY)) {
                result.add(obj);
            }
        }
        return result;
    }

    public Optional<Connection> findConnectionById(UUID id) {
        return Optional.ofNullable(connections.get(id));
    }

    /** Unveränderliche Sicht auf alle Verbindungen (für UI und Tests). */
    public List<Connection> connections() {
        return List.copyOf(connections.values());
    }

    /** Unveränderliche Sicht auf die z-Ordnung (unten → oben) — für UI und Tests. */
    public List<UUID> zOrderIds() {
        return List.copyOf(zOrder);
    }

    // ---------- Text-Lesepfad ----------

    @Override
    public List<UUID> findTextIdsOwnedBy(UUID ownerId) {
        List<UUID> result = new ArrayList<>();
        for (UUID id : zOrder) {
            if (objects.get(id) instanceof Text text && ownerId.equals(text.ownerId())) {
                result.add(text.id());
            }
        }
        return result;
    }

    @Override
    public Optional<UUID> findConnectionNear(double worldX, double worldY, double tolerance) {
        UUID nearest = null;
        double bestDistance = tolerance;
        for (Connection conn : connections.values()) {
            List<double[]> points = polyPoints(conn);
            for (int i = 0; i + 1 < points.size(); i++) {
                double distance = pointSegmentDistance(worldX, worldY,
                        points.get(i), points.get(i + 1));
                if (distance < bestDistance) {
                    bestDistance = distance;
                    nearest = conn.id();
                }
            }
        }
        return Optional.ofNullable(nearest);
    }

    @Override
    public Optional<TextAnchor> projectOntoConnection(UUID connectionId, double worldX, double worldY) {
        Connection conn = connections.get(connectionId);
        if (conn == null) {
            return Optional.empty();
        }
        List<double[]> points = polyPoints(conn);
        if (points.size() < 2) {
            return Optional.empty();
        }
        int bestSegment = 0;
        double bestT = 0.0;
        double bestDistance = Double.MAX_VALUE;
        double[] bestPoint = null;
        for (int i = 0; i + 1 < points.size(); i++) {
            double[] projected = projectPointOnSegment(worldX, worldY,
                    points.get(i), points.get(i + 1));
            double distance = Math.hypot(worldX - projected[0], worldY - projected[1]);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestSegment = i;
                bestT = projected[2];
                bestPoint = projected;
            }
        }
        // Offset = Klick − Ankerpunkt: Das Label soll dort sitzen, wo der
        // Benutzer geklickt hat (Kreation frei — Snapping greift erst beim
        // Verschieben).
        return Optional.of(new TextAnchor(bestSegment, bestT,
                worldX - bestPoint[0], worldY - bestPoint[1]));
    }

    // ---------- interne Helfer ----------

    /** Kopie mit verschobenen Koordinaten — Pattern Matching statt Factory-Klasse.
     *  Trägt ALLE Felder mit (auch label/content) — die Kompatibilitäts-
     *  Konstruktoren würden sie sonst still zurücksetzen. */
    private DiagramObject moved(DiagramObject obj, double dx, double dy) {
        return switch (obj) {
            case Circle c    -> new Circle(c.id(), c.x() + dx, c.y() + dy, c.radius(), c.label(), c.layerId());
            case Rectangle r -> new Rectangle(r.id(), r.x() + dx, r.y() + dy, r.width(), r.height(),
                    r.content(), r.centered(), r.layerId());
            case Text t      -> new Text(t.id(), t.content(), t.x() + dx, t.y() + dy,
                    t.width(), t.height(), t.anchor(), t.ownerId(), t.layerId());
            case Waypoint w  -> new Waypoint(w.id(), w.x() + dx, w.y() + dy, w.layerId());
        };
    }

    /** Hit-Test pro Geometrie-Typ. x/y ist jeweils das Zentrum. */
    private boolean hit(DiagramObject obj, double worldX, double worldY) {
        return switch (obj) {
            case Circle c    -> Math.hypot(worldX - c.x(), worldY - c.y()) <= c.radius();
            case Rectangle r -> Math.abs(worldX - r.x()) <= r.width() / 2
                             && Math.abs(worldY - r.y()) <= r.height() / 2;
            case Text t      -> Math.abs(worldX - t.x()) <= t.width() / 2
                             && Math.abs(worldY - t.y()) <= t.height() / 2;
            case Waypoint w  -> Math.hypot(worldX - w.x(), worldY - w.y()) <= 5.0;
        };
    }

    /** Bounding-Box-Schnitt für die Gummiband-Auswahl (x/y ist das Zentrum). */
    private boolean boundsIntersect(DiagramObject obj, double minX, double minY, double maxX, double maxY) {
        return switch (obj) {
            case Circle c    -> c.x() + c.radius() >= minX && c.x() - c.radius() <= maxX
                             && c.y() + c.radius() >= minY && c.y() - c.radius() <= maxY;
            case Rectangle r -> r.x() + r.width() / 2 >= minX && r.x() - r.width() / 2 <= maxX
                             && r.y() + r.height() / 2 >= minY && r.y() - r.height() / 2 <= maxY;
            case Text t      -> t.x() + t.width() / 2 >= minX && t.x() - t.width() / 2 <= maxX
                             && t.y() + t.height() / 2 >= minY && t.y() - t.height() / 2 <= maxY;
            case Waypoint w  -> false; // oben gefiltert — der Vollständigkeit halber
        };
    }

    /** Punkte der Verbindungskette: Quelle, Waypoints, Ziel (Welt-Koordinaten). */
    private List<double[]> polyPoints(Connection conn) {
        List<double[]> points = new ArrayList<>();
        DiagramObject source = objects.get(conn.sourceId());
        if (source != null) {
            points.add(new double[]{source.x(), source.y()});
        }
        for (UUID waypointId : conn.waypointIds()) {
            DiagramObject waypoint = objects.get(waypointId);
            if (waypoint != null) {
                points.add(new double[]{waypoint.x(), waypoint.y()});
            }
        }
        DiagramObject target = objects.get(conn.targetId());
        if (target != null) {
            points.add(new double[]{target.x(), target.y()});
        }
        return points;
    }

    /** Position eines verankerten Textes: Ankerpunkt auf der Kette plus
     *  Feinjustierung. Fällt ein Abschnitt weg (W gelöscht), klemmt der
     *  Index auf den letzten Abschnitt — t und Offset bleiben als Absicht. */
    private double[] anchoredCenter(TextAnchor anchor, UUID connectionId) {
        Connection conn = connections.get(connectionId);
        if (conn == null) {
            return new double[]{0.0, 0.0}; // Ohne Verbindung kein Anker — defensiv
        }
        List<double[]> points = polyPoints(conn);
        if (points.size() < 2) {
            return points.isEmpty()
                    ? new double[]{0.0, 0.0}
                    : new double[]{points.get(0)[0], points.get(0)[1]};
        }
        int segment = Math.clamp(anchor.segmentIndex(), 0, points.size() - 2);
        double[] a = points.get(segment);
        double[] b = points.get(segment + 1);
        return new double[]{
                a[0] + anchor.t() * (b[0] - a[0]) + anchor.offsetX(),
                a[1] + anchor.t() * (b[1] - a[1]) + anchor.offsetY()
        };
    }

    /** Abstand Punkt -> Strecke. */
    private double pointSegmentDistance(double px, double py, double[] a, double[] b) {
        double[] projected = projectPointOnSegment(px, py, a, b);
        return Math.hypot(px - projected[0], py - projected[1]);
    }

    /** Projektion auf die Strecke a->b; [2] = Parameter t [0..1]. */
    private double[] projectPointOnSegment(double px, double py, double[] a, double[] b) {
        double abx = b[0] - a[0];
        double aby = b[1] - a[1];
        double lengthSquared = abx * abx + aby * aby;
        double t = lengthSquared == 0.0
                ? 0.0
                : Math.clamp(((px - a[0]) * abx + (py - a[1]) * aby) / lengthSquared, 0.0, 1.0);
        return new double[]{a[0] + t * abx, a[1] + t * aby, t};
    }

    private void publishFact(DiagramEvent fact) {
        if (bus != null) {
            bus.publish(fact);
        }
    }
}
