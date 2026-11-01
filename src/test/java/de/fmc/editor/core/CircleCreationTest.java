package de.fmc.editor.core;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.model.DiagramQuery;
import de.fmc.editor.core.model.GridSpec;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.ToolType;
import de.fmc.editor.core.model.Waypoint;
import de.fmc.editor.core.service.SelectionService;
import de.fmc.editor.core.state.CameraInfo;
import de.fmc.editor.core.state.impl.CircleToolState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CircleCreationTest {

    private InteractionEventBus eventBus;
    private List<ActionEvent.AddElement> createdEvents;
    private UUID defaultLayerId;

    @BeforeEach
    void setUp() {
        createdEvents = new ArrayList<>();
        defaultLayerId = UUID.randomUUID();

        // Kamera-Konfiguration ohne Zoom (1.0) und ohne Offset (0,0)
        CameraInfo cameraInfo = new CameraInfo(1.0, 0.0, 0.0, defaultLayerId);

        eventBus = new InteractionEventBus(emptyDiagram(), cameraInfo,
                new GridSpec(10.0), new SelectionService());

        // Event-Listener zur Aufzeichnung gefeuerter AddElement-Events
        eventBus.subscribe(ActionEvent.AddElement.class, createdEvents::add);
    }

    /** Stub für DiagramQuery: Simulation einer leeren Zeichenfläche. */
    private static DiagramQuery emptyDiagram() {
        return new DiagramQuery() {
            @Override
            public Optional<DiagramObject> findTopmostAt(double x, double y) {
                return Optional.empty();
            }

            @Override
            public Optional<DiagramObject> findById(UUID id) {
                return Optional.empty();
            }
        };
    }

    /** Stub, der GENAU an (occupiedX, occupiedY) ein Objekt meldet. */
    private static DiagramQuery diagramWith(DiagramObject occupant) {
        return new DiagramQuery() {
            @Override
            public Optional<DiagramObject> findTopmostAt(double x, double y) {
                // Hit-Match: dieselbe Stelle wie die spätere Klick-Position
                return Math.hypot(x - occupant.x(), y - occupant.y()) < 1.0
                        ? Optional.of(occupant)
                        : Optional.empty();
            }

            @Override
            public Optional<DiagramObject> findById(UUID id) {
                return id.equals(occupant.id()) ? Optional.of(occupant) : Optional.empty();
            }
        };
    }

    /** Klick-Geste im angegebenen Werkzeug an (clickX, clickY). */
    private void clickInTool(ToolType tool, double clickX, double clickY) {
        eventBus.publish(new ActionEvent.SelectTool(tool));
        eventBus.publish(new InputEvent.MousePressed(clickX, clickY, CustomMouseButton.PRIMARY, 1));
        eventBus.publish(new InputEvent.MouseReleased(clickX, clickY, CustomMouseButton.PRIMARY));
    }

    @Test
    @DisplayName("Sollte einen Kreis erzeugen, wenn im Kreis-Werkzeug ins Leere geklickt wird")
    void shouldCreateCircleOnCanvasClick() {
        // 1. Werkzeug per Event auf KREIS stellen
        eventBus.publish(new ActionEvent.SelectTool(ToolType.CIRCLE));
        assertThat(eventBus.getToolState()).isInstanceOf(CircleToolState.class);

        // 2. Klick-Geste simulieren (Drücken + Loslassen an Position 150/200)
        double clickX = 150.0;
        double clickY = 200.0;

        eventBus.publish(new InputEvent.MousePressed(clickX, clickY, CustomMouseButton.PRIMARY, 1));
        eventBus.publish(new InputEvent.MouseReleased(clickX, clickY, CustomMouseButton.PRIMARY));

        // 3. Überprüfung mit AssertionJ
        assertThat(createdEvents)
                .hasSize(1)
                .first()
                .extracting(ActionEvent.AddElement::object)
                .isInstanceOf(Circle.class)
                .satisfies(object -> {
                    Circle circle = (Circle) object;
                    assertThat(circle.x()).isEqualTo(150.0);
                    assertThat(circle.y()).isEqualTo(200.0);
                    assertThat(circle.radius()).isEqualTo(20.0);
                    assertThat(circle.layerId()).isEqualTo(defaultLayerId);
                    assertThat(circle.id()).isNotNull();
                });

        // 4. Prüfen, dass das System weiterhin im CircleToolState ist (für fortlaufendes Zeichnen)
        assertThat(eventBus.getToolState()).isInstanceOf(CircleToolState.class);
    }

    @Test
    @DisplayName("Klick auf ein bestehendes Objekt erzeugt KEINEN Kreis (stiller No-Op)")
    void clickOnExistingObjectDoesNotCreateCircle() {
        Circle occupied = new Circle(UUID.randomUUID(), 150.0, 200.0, 20.0, defaultLayerId);
        eventBus = new InteractionEventBus(diagramWith(occupied),
                new CameraInfo(1.0, 0.0, 0.0, defaultLayerId),
                new GridSpec(10.0), new SelectionService());
        eventBus.subscribe(ActionEvent.AddElement.class, createdEvents::add);

        clickInTool(ToolType.CIRCLE, 150.0, 200.0);

        // Kein deckungsgleicher Stapel — nur ein stiller No-Op:
        assertThat(createdEvents).isEmpty();
        assertThat(eventBus.getToolState()).isInstanceOf(CircleToolState.class);
    }

    @Test
    @DisplayName("Klick auf ein bestehendes Objekt erzeugt KEIN Rechteck (geteilter State)")
    void clickOnExistingObjectDoesNotCreateRectangle() {
        Rectangle occupied = new Rectangle(UUID.randomUUID(), 150.0, 200.0, 40.0, 30.0, defaultLayerId);
        eventBus = new InteractionEventBus(diagramWith(occupied),
                new CameraInfo(1.0, 0.0, 0.0, defaultLayerId),
                new GridSpec(10.0), new SelectionService());
        eventBus.subscribe(ActionEvent.AddElement.class, createdEvents::add);

        clickInTool(ToolType.RECTANGLE, 150.0, 200.0);

        assertThat(createdEvents).isEmpty();
        assertThat(eventBus.getToolState())
                .isInstanceOf(de.fmc.editor.core.state.impl.RectangleToolState.class);
    }

    @Test
    @DisplayName("Klick auf ein (unsichtbares) W blockiert die Erzeugung nicht")
    void clickOnWaypointStillCreatesCircle() {
        Waypoint occupied = new Waypoint(UUID.randomUUID(), 150.0, 200.0, defaultLayerId);
        eventBus = new InteractionEventBus(diagramWith(occupied),
                new CameraInfo(1.0, 0.0, 0.0, defaultLayerId),
                new GridSpec(10.0), new SelectionService());
        eventBus.subscribe(ActionEvent.AddElement.class, createdEvents::add);

        clickInTool(ToolType.CIRCLE, 150.0, 200.0);

        // Ws sind außerhalb des RouteEditMode unsichtbar und dürfen Klicks
        // nicht verschlucken — die Erzeugung passiert:
        assertThat(createdEvents).hasSize(1);
    }
}
