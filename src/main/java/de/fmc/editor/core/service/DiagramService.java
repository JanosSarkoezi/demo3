package de.fmc.editor.core.service;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.Connection;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.model.DiagramQuery;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.Waypoint;

import java.util.ArrayList;
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

    /** Einfüge-Reihenfolge des LinkedHashMap = z-Ordnung (zuletzt eingefügt = oben). */
    private final LinkedHashMap<UUID, DiagramObject> objects = new LinkedHashMap<>();
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
        bus.subscribe(ActionEvent.MoveObjectTo.class, this::onMoveObjectTo);
        bus.subscribe(ActionEvent.AddConnection.class, this::onAddConnection);
        bus.subscribe(ActionEvent.RemoveConnections.class, this::onRemoveConnections);
    }

    // ---------- Schreibpfad: ActionEvents (Requests) konsumieren ----------

    private void onAddElement(ActionEvent.AddElement event) {
        objects.put(event.object().id(), event.object());
        publishFact(new DiagramEvent.ObjectAdded(event.object()));
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

    private void applyMove(DiagramObject current, double targetX, double targetY) {
        double oldX = current.x();
        double oldY = current.y();
        DiagramObject moved = moved(current, targetX - oldX, targetY - oldY);
        objects.put(moved.id(), moved);
        publishFact(new DiagramEvent.ObjectMovedApplied(moved.id(), oldX, oldY, moved.x(), moved.y()));
    }

    private void onResizeObject(ActionEvent.ResizeObject event) {
        if (objects.get(event.id()) instanceof Rectangle rectangle) {
            Rectangle resized = new Rectangle(rectangle.id(), rectangle.x(), rectangle.y(),
                    event.newWidth(), event.newHeight(), rectangle.layerId());
            objects.put(resized.id(), resized);
            publishFact(new DiagramEvent.ObjectResizedApplied(rectangle, resized));
        }
        // Kein Rectangle (oder unbekannte ID) => kein Fakt, nichts passiert.
    }

    private void onResizeCircle(ActionEvent.ResizeCircle event) {
        if (objects.get(event.id()) instanceof Circle circle) {
            Circle resized = new Circle(circle.id(), circle.x(), circle.y(),
                    event.newRadius(), circle.layerId());
            objects.put(resized.id(), resized);
            publishFact(new DiagramEvent.ObjectResizedApplied(circle, resized));
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
            objects.put(waypoint.id(), waypoint);
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
        List<DiagramObject> removedObjects = new ArrayList<>();
        List<Connection> removedConnections = new ArrayList<>();
        LinkedHashMap<UUID, Connection> updatedPrevious = new LinkedHashMap<>();
        LinkedHashMap<UUID, Connection> updatedAfter = new LinkedHashMap<>();

        for (UUID id : event.ids()) {
            DiagramObject obj = objects.get(id);
            if (obj == null) {
                continue; // Unbekannte ID still ignorieren — kein Crash
            }

            // K/R: angehängte Connections fallen mit in die Kaskade.
            if (!(obj instanceof Waypoint)) {
                List<Connection> attached = connections.values().stream()
                        .filter(conn -> id.equals(conn.sourceId()) || id.equals(conn.targetId()))
                        .toList();
                for (Connection conn : attached) {
                    removeConnectionCascade(conn, removedObjects, removedConnections,
                            updatedPrevious, updatedAfter);
                }
            }
            removeObjectCascade(obj, removedObjects, updatedPrevious, updatedAfter);
        }

        if (!removedObjects.isEmpty()) {
            publishFact(new DiagramEvent.ObjectsRemoved(List.copyOf(removedObjects)));
        }
        if (!updatedAfter.isEmpty()) {
            publishFact(new DiagramEvent.ConnectionsUpdated(
                    List.copyOf(updatedPrevious.values()), List.copyOf(updatedAfter.values())));
        }
        if (!removedConnections.isEmpty()) {
            publishFact(new DiagramEvent.ConnectionsRemoved(List.copyOf(removedConnections)));
        }
    }

    /** Entfernt eine Connection samt ihrer Waypoints und merkt alles für die Fakten vor. */
    private void removeConnectionCascade(Connection conn, List<DiagramObject> removedObjects,
                                         List<Connection> removedConnections,
                                         LinkedHashMap<UUID, Connection> updatedPrevious,
                                         LinkedHashMap<UUID, Connection> updatedAfter) {
        if (connections.remove(conn.id()) == null) {
            return; // Bereits in dieser Kaskade entfernt
        }
        removedConnections.add(conn);
        for (UUID waypointId : conn.waypointIds()) {
            DiagramObject waypoint = objects.get(waypointId);
            if (waypoint != null) {
                removeObjectCascade(waypoint, removedObjects, updatedPrevious, updatedAfter);
            }
        }
    }

    /** Entfernt das Objekt und streicht seine ID aus allen Waypoint-Listen. */
    private void removeObjectCascade(DiagramObject obj, List<DiagramObject> removedObjects,
                                     LinkedHashMap<UUID, Connection> updatedPrevious,
                                     LinkedHashMap<UUID, Connection> updatedAfter) {
        if (objects.remove(obj.id()) == null) {
            return; // Bereits in dieser Kaskade entfernt
        }
        removedObjects.add(obj);

        for (Map.Entry<UUID, Connection> entry : connections.entrySet()) {
            Connection conn = entry.getValue();
            if (conn.waypointIds().contains(obj.id())) {
                List<UUID> prunedIds = new ArrayList<>(conn.waypointIds());
                prunedIds.remove(obj.id());
                Connection pruned = new Connection(conn.id(), conn.sourceId(),
                        conn.targetId(), prunedIds, conn.layerId());
                entry.setValue(pruned);
                // Nur beim ersten Strich den Original-Vor-Zustand sichern (Undo-Regel).
                updatedPrevious.putIfAbsent(conn.id(), conn);
                updatedAfter.put(pruned.id(), pruned);
            }
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

    // ---------- Lesepfad: DiagramQuery implementieren ----------

    @Override
    public Optional<DiagramObject> findTopmostAt(double worldX, double worldY) {
        // Rückwärts iterieren = oberstes Objekt zuerst.
        // Waypoints sind keine Klick-Ziele (sonst trafe ein Klick beim
        // Verbindungsaufbau versehentlich einen vorhandenen Waypoint).
        return objects.sequencedValues().reversed().stream()
                .filter(obj -> !(obj instanceof Waypoint))
                .filter(obj -> hit(obj, worldX, worldY))
                .findFirst();
    }

    @Override
    public Optional<DiagramObject> findById(UUID id) {
        return Optional.ofNullable(objects.get(id));
    }

    public Optional<Connection> findConnectionById(UUID id) {
        return Optional.ofNullable(connections.get(id));
    }

    /** Unveränderliche Sicht auf alle Verbindungen (für UI und Tests). */
    public List<Connection> connections() {
        return List.copyOf(connections.values());
    }

    // ---------- interne Helfer ----------

    /** Kopie mit verschobenen Koordinaten — Pattern Matching statt Factory-Klasse. */
    private DiagramObject moved(DiagramObject obj, double dx, double dy) {
        return switch (obj) {
            case Circle c    -> new Circle(c.id(), c.x() + dx, c.y() + dy, c.radius(), c.layerId());
            case Rectangle r -> new Rectangle(r.id(), r.x() + dx, r.y() + dy, r.width(), r.height(), r.layerId());
            case Waypoint w  -> new Waypoint(w.id(), w.x() + dx, w.y() + dy, w.layerId());
        };
    }

    /** Hit-Test pro Geometrie-Typ. x/y ist jeweils das Zentrum. */
    private boolean hit(DiagramObject obj, double worldX, double worldY) {
        return switch (obj) {
            case Circle c    -> Math.hypot(worldX - c.x(), worldY - c.y()) <= c.radius();
            case Rectangle r -> Math.abs(worldX - r.x()) <= r.width() / 2
                             && Math.abs(worldY - r.y()) <= r.height() / 2;
            case Waypoint w  -> Math.hypot(worldX - w.x(), worldY - w.y()) <= 5.0;
        };
    }

    private void publishFact(DiagramEvent fact) {
        if (bus != null) {
            bus.publish(fact);
        }
    }
}
