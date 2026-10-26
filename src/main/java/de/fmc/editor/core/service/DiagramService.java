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
        bus.subscribe(ActionEvent.CreateConnection.class, this::onCreateConnection);
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
        DiagramObject moved = moved(current, event.deltaX(), event.deltaY());
        objects.put(moved.id(), moved);
        publishFact(new DiagramEvent.ObjectMovedApplied(moved.id(), moved.x(), moved.y()));
    }

    private void onResizeObject(ActionEvent.ResizeObject event) {
        if (objects.get(event.id()) instanceof Rectangle rectangle) {
            Rectangle resized = new Rectangle(rectangle.id(), rectangle.x(), rectangle.y(),
                    event.newWidth(), event.newHeight(), rectangle.layerId());
            objects.put(resized.id(), resized);
            publishFact(new DiagramEvent.ObjectResizedApplied(
                    resized.id(), resized.width(), resized.height()));
        }
        // Kein Rectangle (oder unbekannte ID) => kein Fakt, nichts passiert.
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

        Connection connection = new Connection(UUID.randomUUID(), event.sourceId(),
                event.targetId(), event.waypoints(), target.layerId());
        connections.put(connection.id(), connection);
        publishFact(new DiagramEvent.ConnectionEstablished(connection));
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
