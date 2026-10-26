package de.fmc.editor.core;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.model.DiagramQuery;
import de.fmc.editor.core.model.ToolType;
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

        // Stub für DiagramQuery: Simulation einer leeren Zeichenfläche
        DiagramQuery emptyDiagram = new DiagramQuery() {
            @Override
            public Optional<DiagramObject> findTopmostAt(double x, double y) {
                return Optional.empty(); // Keine Objekte vorhanden
            }

            @Override
            public Optional<DiagramObject> findById(UUID id) {
                return Optional.empty();
            }
        };

        // Kamera-Konfiguration ohne Zoom (1.0) und ohne Offset (0,0)
        CameraInfo cameraInfo = new CameraInfo(1.0, 0.0, 0.0, defaultLayerId);

        eventBus = new InteractionEventBus(emptyDiagram, cameraInfo);

        // Event-Listener zur Aufzeichnung gefeuerter AddElement-Events
        eventBus.subscribe(ActionEvent.AddElement.class, createdEvents::add);
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
}
