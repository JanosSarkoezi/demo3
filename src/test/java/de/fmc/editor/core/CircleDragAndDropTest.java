package de.fmc.editor.core;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.model.DiagramQuery;
import de.fmc.editor.core.model.GridSpec;
import de.fmc.editor.core.model.ToolType;
import de.fmc.editor.core.service.SelectionService;
import de.fmc.editor.core.state.CameraInfo;
import de.fmc.editor.core.state.impl.IdleToolState;
import de.fmc.editor.core.state.impl.MovingState;
import de.fmc.editor.core.state.impl.PendingMoveState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CircleDragAndDropTest {

    private InteractionEventBus eventBus;
    private List<ActionEvent.MoveObjects> movedEvents;
    private final List<DiagramObject> diagramObjects = new ArrayList<>();
    private UUID defaultLayerId;

    @BeforeEach
    void setUp() {
        movedEvents = new ArrayList<>();
        defaultLayerId = UUID.randomUUID();
        diagramObjects.clear();

        // Dynamic Stub: Findet Objekte in der Liste 'diagramObjects'
        DiagramQuery dynamicDiagram = new DiagramQuery() {
            @Override
            public Optional<DiagramObject> findTopmostAt(double x, double y) {
                // Einfaches Hit-Testing: Liefert das erste Objekt in der Nähe der Koordinaten
                return diagramObjects.stream()
                        .filter(obj -> Math.hypot(obj.x() - x, obj.y() - y) <= 30.0)
                        .findFirst();
            }

            @Override
            public Optional<DiagramObject> findById(UUID id) {
                return diagramObjects.stream()
                        .filter(obj -> obj.id().equals(id))
                        .findFirst();
            }
        };

        CameraInfo cameraInfo = new CameraInfo(1.0, 0.0, 0.0, defaultLayerId);
        eventBus = new InteractionEventBus(dynamicDiagram, cameraInfo,
                new GridSpec(10.0), new SelectionService());

        // Aufzeichnen aller Objekt-Verschiebungs-Events (die Geste spricht
        // seit dem Gruppen-Move immer den pluralen Request; n=1 ist der Spezialfall)
        eventBus.subscribe(ActionEvent.MoveObjects.class, movedEvents::add);

        // Sobald ein Element erzeugt wird, fügen wir es direkt unserem Diagramm-Speicher hinzu
        eventBus.subscribe(ActionEvent.AddElement.class, e -> diagramObjects.add(e.object()));
    }

    @Test
    @DisplayName("Sollte ein erzeugtes Objekt per Drag-and-Drop verschieben")
    void shouldMoveCircleWithDragAndDrop() {
        // --- 1. Vorbereitung: Kreis erzeugen (bei 150, 200) ---
        eventBus.publish(new ActionEvent.SelectTool(ToolType.CIRCLE));
        eventBus.publish(new InputEvent.MousePressed(150.0, 200.0, CustomMouseButton.PRIMARY, 1));
        eventBus.publish(new InputEvent.MouseReleased(150.0, 200.0, CustomMouseButton.PRIMARY));

        assertThat(diagramObjects).hasSize(1);
        Circle createdCircle = (Circle) diagramObjects.get(0);

        // Zurück zum Auswahl-Werkzeug wechseln
        eventBus.publish(new ActionEvent.SelectTool(ToolType.SELECT));
        assertThat(eventBus.getToolState()).isInstanceOf(IdleToolState.class);

        // --- 2. Drag-and-Drop starten ---

        // A) Maustaste auf dem Kreis drücken -> System wechselt in PendingMoveState
        eventBus.publish(new InputEvent.MousePressed(150.0, 200.0, CustomMouseButton.PRIMARY, 1));
        assertThat(eventBus.getToolState()).isInstanceOf(PendingMoveState.class);

        // B) Maus über Schwellenwert ziehen (von 150/200 nach 160/200, Delta > 4px Threshold)
        // System wechselt von PendingMoveState in den aktiven MovingState
        eventBus.publish(new InputEvent.MouseDragged(160.0, 200.0, CustomMouseButton.PRIMARY));
        assertThat(eventBus.getToolState()).isInstanceOf(MovingState.class);

        // C) Weiterziehen der Maus (von 160/200 nach 180/220) -> Feuert MoveObject-Event
        eventBus.publish(new InputEvent.MouseDragged(180.0, 220.0, CustomMouseButton.PRIMARY));

        // D) Maustaste loslassen -> Beendet Dragging und kehrt zu IdleToolState zurück
        eventBus.publish(new InputEvent.MouseReleased(180.0, 220.0, CustomMouseButton.PRIMARY));
        assertThat(eventBus.getToolState()).isInstanceOf(IdleToolState.class);

        // --- 3. Zusicherungen mit AssertionJ ---
        assertThat(movedEvents)
                .isNotEmpty()
                .hasSize(1)
                .first()
                .satisfies(event -> {
                    assertThat(event.ids()).containsExactly(createdCircle.id());
                    // Der Versatz berechnet sich aus der Start-Position (150, 200) bis zur End-Position (180, 220)
                    assertThat(event.deltaX()).isEqualTo(30.0);
                    assertThat(event.deltaY()).isEqualTo(20.0);
                });
    }
}