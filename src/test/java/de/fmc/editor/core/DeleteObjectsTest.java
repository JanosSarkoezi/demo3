package de.fmc.editor.core;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.Connection;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.model.GridSpec;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.Waypoint;
import de.fmc.editor.core.service.DiagramService;
import de.fmc.editor.core.service.SelectionService;
import de.fmc.editor.core.state.CameraInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifiziert die Lösch-Kaskade als sichtbare Fakten: K/R-Löschen entfernt
 * ihre Connections mitsamt Waypoints; JEDES entfernte Objekt wird aus allen
 * Waypoint-Listen gestrichen — geteilte Waypoints räumen sich in fremden
 * Connections automatisch mit ab (die Ecke, in der demo2s getrennte Logik
 * kippte).
 */
class DeleteObjectsTest {

    private InteractionEventBus bus;
    private DiagramService diagram;
    private final List<DiagramEvent.ObjectsRemoved> objectsRemovedFacts = new ArrayList<>();
    private final List<DiagramEvent.ConnectionsRemoved> connectionsRemovedFacts = new ArrayList<>();
    private final List<DiagramEvent.ConnectionsUpdated> connectionsUpdatedFacts = new ArrayList<>();
    private final List<DiagramEvent.ConnectionEstablished> established = new ArrayList<>();
    private UUID defaultLayerId;

    @BeforeEach
    void setUp() {
        objectsRemovedFacts.clear();
        connectionsRemovedFacts.clear();
        connectionsUpdatedFacts.clear();
        established.clear();
        defaultLayerId = UUID.randomUUID();

        diagram = new DiagramService();
        bus = new InteractionEventBus(diagram,
                new CameraInfo(1.0, 0.0, 0.0, defaultLayerId),
                new GridSpec(10.0), new SelectionService());
        diagram.attachTo(bus);

        bus.subscribe(DiagramEvent.ObjectsRemoved.class, objectsRemovedFacts::add);
        bus.subscribe(DiagramEvent.ConnectionsRemoved.class, connectionsRemovedFacts::add);
        bus.subscribe(DiagramEvent.ConnectionsUpdated.class, connectionsUpdatedFacts::add);
        bus.subscribe(DiagramEvent.ConnectionEstablished.class, established::add);
    }

    private Circle addCircle(double x, double y) {
        Circle circle = new Circle(UUID.randomUUID(), x, y, 20.0, defaultLayerId);
        bus.publish(new ActionEvent.AddElement(circle));
        return circle;
    }

    private Rectangle addRectangle(double x, double y) {
        Rectangle rect = new Rectangle(UUID.randomUUID(), x, y, 40.0, 30.0, defaultLayerId);
        bus.publish(new ActionEvent.AddElement(rect));
        return rect;
    }

    @Test
    @DisplayName("Kreis-Löschen kaskadiert: Connections und deren Waypoints verschwinden mit")
    void deleteShouldCascadeConnectionsAndWaypoints() {
        Circle circle = addCircle(100.0, 100.0);
        Circle other = addCircle(300.0, 100.0);
        Rectangle rect = addRectangle(500.0, 100.0);

        Waypoint w1 = new Waypoint(UUID.randomUUID(), 150.0, 150.0, defaultLayerId);
        Waypoint w2 = new Waypoint(UUID.randomUUID(), 400.0, 150.0, defaultLayerId);
        bus.publish(new ActionEvent.CreateConnection(circle.id(), rect.id(), List.of(w1)));
        bus.publish(new ActionEvent.CreateConnection(other.id(), rect.id(), List.of(w2)));

        bus.publish(new ActionEvent.DeleteObjects(List.of(circle.id())));

        // Objekte weg: Kreis + sein Waypoint w1
        assertThat(objectsRemovedFacts).hasSize(1);
        assertThat(objectsRemovedFacts.getFirst().objects())
                .extracting(DiagramObject::id)
                .containsExactlyInAnyOrder(circle.id(), w1.id());
        // Nur die Connection des gelöschten Kreises ist weg:
        assertThat(connectionsRemovedFacts).hasSize(1);
        assertThat(connectionsRemovedFacts.getFirst().connections())
                .extracting(Connection::id)
                .containsExactly(established.getFirst().connection().id());
        // Die andere Connection (other -> rect mit w2) bleibt unberührt:
        assertThat(connectionsUpdatedFacts).isEmpty();
        assertThat(diagram.findConnectionById(established.get(1).connection().id())).isPresent();
        assertThat(diagram.findById(w2.id())).isPresent();
        assertThat(diagram.findById(circle.id())).isEmpty();
        assertThat(diagram.findById(w1.id())).isEmpty();
    }

    @Test
    @DisplayName("Geteilter Waypoint: überlebende Connection verliert nur die ID aus ihrer Liste")
    void deleteShouldPruneSharedWaypointFromSurvivingConnection() {
        Circle a = addCircle(100.0, 100.0);
        Circle b = addCircle(300.0, 100.0);
        Rectangle rect = addRectangle(500.0, 100.0);

        Waypoint shared = new Waypoint(UUID.randomUUID(), 300.0, 200.0, defaultLayerId);
        bus.publish(new ActionEvent.CreateConnection(a.id(), rect.id(), List.of(shared)));
        bus.publish(new ActionEvent.CreateConnection(b.id(), rect.id(), List.of(shared)));

        bus.publish(new ActionEvent.DeleteObjects(List.of(a.id())));

        // Connection a->rect ist samt shared weg; b->rect bleibt, aber ohne die ID:
        assertThat(connectionsRemovedFacts).hasSize(1);
        Connection surviving = diagram
                .findConnectionById(established.get(1).connection().id())
                .orElseThrow();
        assertThat(surviving.waypointIds()).doesNotContain(shared.id());
        // Der geteilte Waypoint selbst ist aus dem Modell entfernt:
        assertThat(diagram.findById(shared.id())).isEmpty();
        // Und als Update-Fakt gemeldet, damit die spätere UI neu zeichnet:
        assertThat(connectionsUpdatedFacts).hasSize(1);
        assertThat(connectionsUpdatedFacts.getFirst().updated())
                .extracting(Connection::id)
                .containsExactly(surviving.id());
        // Vor-Zustand macht den Fakt invertierbar (Undo-Regel):
        assertThat(connectionsUpdatedFacts.getFirst().previous().getFirst().waypointIds())
                .contains(shared.id());
        // Die zweite Connection hat überlebt:
        assertThat(diagram.findConnectionById(surviving.id())).isPresent();
    }

    @Test
    @DisplayName("Einzeln gelöschter Waypoint wird nur aus den Listen gestrichen")
    void deleteSingleWaypointOnlyPrunesLists() {
        Circle a = addCircle(100.0, 100.0);
        Rectangle rect = addRectangle(500.0, 100.0);
        Waypoint w = new Waypoint(UUID.randomUUID(), 300.0, 200.0, defaultLayerId);
        bus.publish(new ActionEvent.CreateConnection(a.id(), rect.id(), List.of(w)));

        bus.publish(new ActionEvent.DeleteObjects(List.of(w.id())));

        assertThat(objectsRemovedFacts).hasSize(1);
        assertThat(objectsRemovedFacts.getFirst().objects())
                .extracting(DiagramObject::id)
                .containsExactly(w.id());
        // Die Connection bleibt bestehen — nur mit leerer Waypoint-Liste:
        assertThat(connectionsRemovedFacts).isEmpty();
        assertThat(connectionsUpdatedFacts).hasSize(1);
        Connection surviving = diagram
                .findConnectionById(established.getFirst().connection().id())
                .orElseThrow();
        assertThat(surviving.waypointIds()).isEmpty();
        assertThat(diagram.findById(a.id())).isPresent();
    }

    @Test
    @DisplayName("Unbekannte IDs werden still ignoriert — keine Fakten")
    void deleteUnknownIdIsSilentlyIgnored() {
        bus.publish(new ActionEvent.DeleteObjects(List.of(UUID.randomUUID())));

        assertThat(objectsRemovedFacts).isEmpty();
        assertThat(connectionsRemovedFacts).isEmpty();
        assertThat(connectionsUpdatedFacts).isEmpty();
    }
}
