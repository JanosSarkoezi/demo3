package de.fmc.editor.core;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.model.DiagramQuery;
import de.fmc.editor.core.model.GridSpec;
import de.fmc.editor.core.service.SelectionService;
import de.fmc.editor.core.state.CameraInfo;
import de.fmc.editor.core.state.impl.CameraIdleState;
import de.fmc.editor.core.state.impl.CameraPanningState;
import de.fmc.editor.core.state.impl.PendingMoveState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifiziert, dass der Bus seinen Kamera-Snapshot bei PanView/ZoomView
 * aktualisiert — Hit-Tests und Koordinaten-Umrechnungen rechnen auch nach
 * Panning/Zooming mit der aktuellen Kamera, nicht mit dem Start-Snapshot.
 */
class CameraConsistencyTest {

    private InteractionEventBus eventBus;
    private final List<DiagramObject> diagramObjects = new ArrayList<>();
    private UUID defaultLayerId;

    @BeforeEach
    void setUp() {
        defaultLayerId = UUID.randomUUID();
        diagramObjects.clear();

        // Dynamic Stub: Findet Objekte in der Liste 'diagramObjects'
        DiagramQuery dynamicDiagram = new DiagramQuery() {
            @Override
            public Optional<DiagramObject> findTopmostAt(double x, double y) {
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

        eventBus = new InteractionEventBus(dynamicDiagram,
                new CameraInfo(1.0, 0.0, 0.0, defaultLayerId),
                new GridSpec(10.0), new SelectionService());
    }

    @Test
    @DisplayName("PanView aktualisiert den Kamera-Snapshot im Bus")
    void panShouldUpdateCameraSnapshot() {
        eventBus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.MIDDLE, 1));
        eventBus.publish(new InputEvent.MouseDragged(130.0, 140.0, CustomMouseButton.MIDDLE));

        CameraInfo camera = eventBus.getCameraInfo();
        assertThat(camera.offsetX()).isEqualTo(30.0);
        assertThat(camera.offsetY()).isEqualTo(40.0);
        // Umrechnung rechnet mit dem neuen Offset:
        assertThat(camera.screenToWorldX(0.0)).isEqualTo(-30.0);
    }

    @Test
    @DisplayName("ZoomView hält den Welt-Punkt unter dem Pivot fixiert")
    void zoomShouldKeepWorldPointUnderPivot() {
        eventBus.publish(new ActionEvent.ZoomView(2.0, 100.0, 50.0));

        CameraInfo camera = eventBus.getCameraInfo();
        assertThat(camera.zoom()).isEqualTo(2.0);
        assertThat(camera.offsetX()).isEqualTo(-100.0);
        assertThat(camera.offsetY()).isEqualTo(-50.0);
        // Der Welt-Punkt unter dem Pivot (100, 50) bleibt dort:
        assertThat(camera.screenToWorldX(100.0)).isEqualTo(100.0);
        assertThat(camera.screenToWorldY(50.0)).isEqualTo(50.0);
    }

    @Test
    @DisplayName("Hit-Testing nach dem Panning rechnet mit der aktualisierten Kamera")
    void hitTestShouldUseCurrentCameraAfterPan() {
        // Objekt liegt in der Welt auf (200, 200)
        diagramObjects.add(new Circle(UUID.randomUUID(), 200.0, 200.0, 20.0, defaultLayerId));

        // Ansicht um (50, 0) verschieben: Das Objekt erscheint nun bei Screen (250, 200)
        eventBus.publish(new InputEvent.MousePressed(0.0, 0.0, CustomMouseButton.MIDDLE, 1));
        eventBus.publish(new InputEvent.MouseDragged(50.0, 0.0, CustomMouseButton.MIDDLE));
        eventBus.publish(new InputEvent.MouseReleased(50.0, 0.0, CustomMouseButton.MIDDLE));

        // Klick auf die NEUE Bildschirm-Position muss das Objekt treffen —
        // mit einem veralteten Snapshot (Offset 0) würde der Klick ins Leere gehen.
        eventBus.publish(new InputEvent.MousePressed(250.0, 200.0, CustomMouseButton.PRIMARY, 1));
        assertThat(eventBus.getToolState()).isInstanceOf(PendingMoveState.class);
    }

    @Test
    @DisplayName("Panning endet nur beim Loslassen der auslösenden Maustaste")
    void panShouldEndOnlyOnInitiatingButton() {
        eventBus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.MIDDLE, 1));
        eventBus.publish(new InputEvent.MouseDragged(130.0, 100.0, CustomMouseButton.MIDDLE));

        // Loslassen einer ANDEREN Taste beendet das Panning nicht
        eventBus.publish(new InputEvent.MouseReleased(130.0, 100.0, CustomMouseButton.SECONDARY));
        assertThat(eventBus.getCameraState()).isInstanceOf(CameraPanningState.class);

        // Loslassen der Panning-Taste beendet das Panning
        eventBus.publish(new InputEvent.MouseReleased(130.0, 100.0, CustomMouseButton.MIDDLE));
        assertThat(eventBus.getCameraState()).isInstanceOf(CameraIdleState.class);
    }

    @Test
    @DisplayName("Tool-initiiertes Panning (Leerraum-Drag) endet beim Loslassen der linken Taste")
    void toolInitiatedPanShouldEndOnPrimaryRelease() {
        // Linksklick auf freie Fläche -> PendingCreateOrPanState
        eventBus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.PRIMARY, 1));

        // Über Schwellenwert ziehen -> Kamera übernimmt das Panning
        eventBus.publish(new InputEvent.MouseDragged(150.0, 100.0, CustomMouseButton.PRIMARY));
        assertThat(eventBus.getCameraState()).isInstanceOf(CameraPanningState.class);

        // Weiterziehen -> PanView fließt in den Kamera-Snapshot
        eventBus.publish(new InputEvent.MouseDragged(170.0, 100.0, CustomMouseButton.PRIMARY));
        assertThat(eventBus.getCameraInfo().offsetX()).isEqualTo(70.0);

        // Loslassen der linken Taste beendet das Panning
        eventBus.publish(new InputEvent.MouseReleased(170.0, 100.0, CustomMouseButton.PRIMARY));
        assertThat(eventBus.getCameraState()).isInstanceOf(CameraIdleState.class);
    }
}
