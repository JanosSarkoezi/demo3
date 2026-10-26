package de.fmc.editor.core;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.Connection;
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
 * Testet den DiagramService als reinen Event-Flow:
 * ActionEvents (Requests) rein — DiagramEvents (Fakten) + Zustand raus.
 */
class DiagramServiceTest {

    private DiagramService diagram;
    private InteractionEventBus bus;
    private UUID defaultLayerId;

    private final List<DiagramEvent.ConnectionRejected> rejections = new ArrayList<>();
    private final List<DiagramEvent.ConnectionEstablished> established = new ArrayList<>();
    private final List<DiagramEvent.ObjectMovedApplied> movedFacts = new ArrayList<>();
    private final List<DiagramEvent.ObjectResizedApplied> resizedFacts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        rejections.clear();
        established.clear();
        movedFacts.clear();
        resizedFacts.clear();
        defaultLayerId = UUID.randomUUID();

        diagram = new DiagramService();
        bus = new InteractionEventBus(diagram, new CameraInfo(1.0, 0.0, 0.0, defaultLayerId),
                new GridSpec(10.0), new SelectionService());
        diagram.attachTo(bus);

        bus.subscribe(DiagramEvent.ConnectionRejected.class, rejections::add);
        bus.subscribe(DiagramEvent.ConnectionEstablished.class, established::add);
        bus.subscribe(DiagramEvent.ObjectMovedApplied.class, movedFacts::add);
        bus.subscribe(DiagramEvent.ObjectResizedApplied.class, resizedFacts::add);
    }

    private Circle newCircle(double x, double y) {
        return new Circle(UUID.randomUUID(), x, y, 20.0, defaultLayerId);
    }

    @Test
    @DisplayName("AddElement legt ein Objekt an, das über findById und findTopmostAt auffindbar ist")
    void shouldStoreCreatedElement() {
        Circle circle = newCircle(100.0, 100.0);
        bus.publish(new ActionEvent.AddElement(circle));

        assertThat(diagram.findById(circle.id())).contains(circle);
        assertThat(diagram.findTopmostAt(105.0, 105.0)).contains(circle);
        assertThat(diagram.findTopmostAt(500.0, 500.0)).isEmpty();
    }

    @Test
    @DisplayName("MoveObject verschiebt das Objekt und feuert den ObjectMovedApplied-Fakt")
    void shouldMoveObject() {
        Circle circle = newCircle(150.0, 200.0);
        bus.publish(new ActionEvent.AddElement(circle));

        bus.publish(new ActionEvent.MoveObject(circle.id(), 30.0, 20.0));

        assertThat(diagram.findById(circle.id()))
                .hasValueSatisfying(obj -> {
                    assertThat(obj.x()).isEqualTo(180.0);
                    assertThat(obj.y()).isEqualTo(220.0);
                });
        assertThat(movedFacts).hasSize(1);
        assertThat(movedFacts.getFirst().id()).isEqualTo(circle.id());
    }

    @Test
    @DisplayName("MoveObject für eine unbekannte ID wird still ignoriert")
    void shouldIgnoreMoveForUnknownId() {
        bus.publish(new ActionEvent.MoveObject(UUID.randomUUID(), 10.0, 10.0));

        assertThat(movedFacts).isEmpty();
    }

    @Test
    @DisplayName("ResizeObject ändert die Maße und feuert den ObjectResizedApplied-Fakt")
    void shouldResizeRectangle() {
        Rectangle rect = new Rectangle(UUID.randomUUID(), 50.0, 60.0, 40.0, 30.0, defaultLayerId);
        bus.publish(new ActionEvent.AddElement(rect));

        bus.publish(new ActionEvent.ResizeObject(rect.id(), 80.0, 50.0));

        assertThat(diagram.findById(rect.id()))
                .hasValueSatisfying(obj -> {
                    Rectangle resized = (Rectangle) obj;
                    assertThat(resized.width()).isEqualTo(80.0);
                    assertThat(resized.height()).isEqualTo(50.0);
                    assertThat(resized.x()).isEqualTo(50.0);
                    assertThat(resized.id()).isEqualTo(rect.id());
                });
        assertThat(resizedFacts).hasSize(1);
        assertThat(resizedFacts.getFirst().resizedObject())
                .isInstanceOf(Rectangle.class)
                .satisfies(obj -> {
                    Rectangle resized = (Rectangle) obj;
                    assertThat(resized.width()).isEqualTo(80.0);
                    assertThat(resized.height()).isEqualTo(50.0);
                    assertThat(resized.id()).isEqualTo(rect.id());
                });
    }

    @Test
    @DisplayName("ResizeCircle ändert den Radius, hält das Zentrum exakt und feuert den Fakt")
    void shouldResizeCircle() {
        Circle circle = newCircle(150.0, 200.0);
        bus.publish(new ActionEvent.AddElement(circle));

        bus.publish(new ActionEvent.ResizeCircle(circle.id(), 35.0));

        assertThat(diagram.findById(circle.id()))
                .hasValueSatisfying(obj -> {
                    Circle resized = (Circle) obj;
                    assertThat(resized.radius()).isEqualTo(35.0);
                    // Das Zentrum bleibt exakt erhalten (symmetrisches Resize)
                    assertThat(resized.x()).isEqualTo(150.0);
                    assertThat(resized.y()).isEqualTo(200.0);
                });
        assertThat(resizedFacts).hasSize(1);
        assertThat(resizedFacts.getFirst().resizedObject())
                .isInstanceOf(Circle.class)
                .satisfies(obj -> assertThat(((Circle) obj).radius()).isEqualTo(35.0));
    }

    @Test
    @DisplayName("ResizeCircle auf ein Rectangle bleibt ohne Fakt")
    void shouldNotResizeRectangleWithCircleRequest() {
        Rectangle rect = new Rectangle(UUID.randomUUID(), 50.0, 60.0, 40.0, 30.0, defaultLayerId);
        bus.publish(new ActionEvent.AddElement(rect));

        bus.publish(new ActionEvent.ResizeCircle(rect.id(), 50.0));

        assertThat(resizedFacts).isEmpty();
    }

    @Test
    @DisplayName("ResizeObject für ein Circle oder eine unbekannte ID bleibt ohne Fakt")
    void shouldNotResizeCircleOrUnknownId() {
        Circle circle = newCircle(0.0, 0.0);
        bus.publish(new ActionEvent.AddElement(circle));

        // Resize auf einen Circle: nur Rectangles sind resizbar => kein Fakt
        bus.publish(new ActionEvent.ResizeObject(circle.id(), 80.0, 50.0));
        // Resize auf eine unbekannte ID => kein Fakt
        bus.publish(new ActionEvent.ResizeObject(UUID.randomUUID(), 80.0, 50.0));

        assertThat(resizedFacts).isEmpty();
    }

    @Test
    @DisplayName("Kreis-zu-Kreis wird zentral abgelehnt: ConnectionRejected, nichts gespeichert")
    void shouldRejectCircleToCircleConnection() {
        Circle c1 = newCircle(0.0, 0.0);
        Circle c2 = newCircle(100.0, 0.0);
        bus.publish(new ActionEvent.AddElement(c1));
        bus.publish(new ActionEvent.AddElement(c2));

        bus.publish(new ActionEvent.CreateConnection(c1.id(), c2.id(), List.of()));

        assertThat(rejections).hasSize(1);
        assertThat(rejections.getFirst().sourceId()).isEqualTo(c1.id());
        assertThat(rejections.getFirst().targetId()).isEqualTo(c2.id());
        assertThat(rejections.getFirst().reason()).isNotBlank();
        assertThat(diagram.connections()).isEmpty();
        assertThat(established).isEmpty();
    }

    @Test
    @DisplayName("Kreis-zu-Rectangle ist erlaubt: ConnectionEstablished mit erhaltenen Waypoints")
    void shouldEstablishValidConnection() {
        Circle circle = newCircle(0.0, 0.0);
        Rectangle rect = new Rectangle(UUID.randomUUID(), 100.0, 0.0, 40.0, 30.0, defaultLayerId);
        bus.publish(new ActionEvent.AddElement(circle));
        bus.publish(new ActionEvent.AddElement(rect));

        Waypoint waypoint = new Waypoint(UUID.randomUUID(), 50.0, 0.0, defaultLayerId);
        bus.publish(new ActionEvent.CreateConnection(circle.id(), rect.id(), List.of(waypoint)));

        assertThat(rejections).isEmpty();
        assertThat(established).hasSize(1);

        Connection connection = established.getFirst().connection();
        assertThat(connection.sourceId()).isEqualTo(circle.id());
        assertThat(connection.targetId()).isEqualTo(rect.id());
        assertThat(connection.waypoints()).containsExactly(waypoint);
        assertThat(diagram.findConnectionById(connection.id())).isPresent();
    }
}
