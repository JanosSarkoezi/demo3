package de.fmc.editor.core;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.model.DiagramQuery;
import de.fmc.editor.core.model.GridSpec;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.service.SelectionService;
import de.fmc.editor.core.state.CameraInfo;
import de.fmc.editor.core.state.impl.HandleDragState;
import de.fmc.editor.core.state.impl.IdleToolState;
import de.fmc.editor.core.state.impl.ResizeModeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testet die komplette Resize-Geste als reinen Event-Flow:
 * Doppelklick -> Auswahl + Resize-Modus -> Griff ziehen -> Commit beim Loslassen.
 * Während des Ziehens darf KEIN Resize-Request das Backend erreichen.
 */
class ResizeModeStateTest {

    private InteractionEventBus bus;
    private SelectionService selection;
    private final List<DiagramObject> diagramObjects = new ArrayList<>();

    private final List<ActionEvent.SetSelection> setSelectionRequests = new ArrayList<>();
    private final List<ActionEvent.ResizeCircle> resizeCircleRequests = new ArrayList<>();
    private final List<ActionEvent.ResizeObject> resizeObjectRequests = new ArrayList<>();
    private final List<DiagramEvent.SelectionChanged> selectionFacts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        diagramObjects.clear();
        setSelectionRequests.clear();
        resizeCircleRequests.clear();
        resizeObjectRequests.clear();
        selectionFacts.clear();

        // Dynamischer Stub: findet Objekte im Umkreis von 30 um ihr Zentrum
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

        selection = new SelectionService();
        bus = new InteractionEventBus(dynamicDiagram,
                new CameraInfo(1.0, 0.0, 0.0, UUID.randomUUID()),
                new GridSpec(10.0), selection);
        selection.attachTo(bus);

        bus.subscribe(ActionEvent.SetSelection.class, setSelectionRequests::add);
        bus.subscribe(ActionEvent.ResizeCircle.class, resizeCircleRequests::add);
        bus.subscribe(ActionEvent.ResizeObject.class, resizeObjectRequests::add);
        bus.subscribe(DiagramEvent.SelectionChanged.class, selectionFacts::add);
    }

    private Circle circleAt(double x, double y) {
        Circle circle = new Circle(UUID.randomUUID(), x, y, 20.0, UUID.randomUUID());
        diagramObjects.add(circle);
        return circle;
    }

    private Rectangle rectangleAt(double x, double y) {
        Rectangle rectangle = new Rectangle(UUID.randomUUID(), x, y, 40.0, 30.0, UUID.randomUUID());
        diagramObjects.add(rectangle);
        return rectangle;
    }

    private void enterResizeModeAt(double x, double y) {
        bus.publish(new InputEvent.MousePressed(x, y, CustomMouseButton.PRIMARY, 2));
        assertThat(bus.getToolState()).isInstanceOf(ResizeModeState.class);
    }

    @Test
    @DisplayName("Doppelklick wählt das Objekt aus und öffnet den Resize-Modus")
    void doubleClickShouldSelectAndEnterResizeMode() {
        Circle circle = circleAt(150.0, 200.0);

        enterResizeModeAt(150.0, 200.0);

        assertThat(setSelectionRequests).hasSize(1);
        assertThat(setSelectionRequests.getFirst().selectedIds()).containsExactly(circle.id());
        assertThat(selectionFacts).hasSize(1);
        assertThat(selectionFacts.getFirst().selectedIds()).containsExactly(circle.id());
        assertThat(selection.selectedIds()).containsExactly(circle.id());
    }

    @Test
    @DisplayName("Kreis-Resize committet erst beim Loslassen — während des Drags kein Request")
    void circleResizeShouldCommitOnlyOnRelease() {
        Circle circle = circleAt(150.0, 200.0); // E-Griff bei (170, 200)
        enterResizeModeAt(150.0, 200.0);

        bus.publish(new InputEvent.MousePressed(170.0, 200.0, CustomMouseButton.PRIMARY, 1));
        assertThat(bus.getToolState()).isInstanceOf(HandleDragState.class);

        bus.publish(new InputEvent.MouseDragged(185.0, 200.0, CustomMouseButton.PRIMARY));
        assertThat(resizeCircleRequests).isEmpty(); // Backend bleibt während des Drags stumm

        bus.publish(new InputEvent.MouseReleased(185.0, 200.0, CustomMouseButton.PRIMARY));
        assertThat(resizeCircleRequests).hasSize(1); // Genau EIN Commit pro Geste
        assertThat(resizeCircleRequests.getFirst().id()).isEqualTo(circle.id());
        assertThat(resizeCircleRequests.getFirst().newRadius()).isEqualTo(35.0);
        // Weiter im Resize-Modus: nächster Griff kann sofort gezogen werden
        assertThat(bus.getToolState()).isInstanceOf(ResizeModeState.class);
    }

    @Test
    @DisplayName("Rechteck-Eckengriff ändert Breite und Höhe symmetrisch ums Zentrum")
    void rectangleCornerHandleShouldChangeBothDimensions() {
        Rectangle rect = rectangleAt(150.0, 200.0); // SE-Griff bei (170, 215)
        enterResizeModeAt(150.0, 200.0);

        bus.publish(new InputEvent.MousePressed(170.0, 215.0, CustomMouseButton.PRIMARY, 1));
        assertThat(bus.getToolState()).isInstanceOf(HandleDragState.class);

        bus.publish(new InputEvent.MouseDragged(190.0, 235.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseReleased(190.0, 235.0, CustomMouseButton.PRIMARY));

        assertThat(resizeObjectRequests).hasSize(1);
        assertThat(resizeObjectRequests.getFirst().id()).isEqualTo(rect.id());
        assertThat(resizeObjectRequests.getFirst().newWidth()).isEqualTo(80.0);  // 2 * |190 - 150|
        assertThat(resizeObjectRequests.getFirst().newHeight()).isEqualTo(70.0); // 2 * |235 - 200|
    }

    @Test
    @DisplayName("Rechteck-Seitengriff ändert nur die eine Dimension")
    void rectangleEdgeHandleShouldChangeOnlyOneDimension() {
        rectangleAt(150.0, 200.0); // E-Griff bei (170, 200)
        enterResizeModeAt(150.0, 200.0);

        bus.publish(new InputEvent.MousePressed(170.0, 200.0, CustomMouseButton.PRIMARY, 1));
        bus.publish(new InputEvent.MouseDragged(190.0, 300.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseReleased(190.0, 300.0, CustomMouseButton.PRIMARY));

        assertThat(resizeObjectRequests).hasSize(1);
        assertThat(resizeObjectRequests.getFirst().newWidth()).isEqualTo(80.0);
        assertThat(resizeObjectRequests.getFirst().newHeight()).isEqualTo(30.0); // unverändert
    }

    @Test
    @DisplayName("Die Größe wird auf mindestens eine Grid-Zelle geklemmt")
    void sizeShouldBeClampedToMinimumGridCell() {
        circleAt(150.0, 200.0); // E-Griff bei (170, 200)
        enterResizeModeAt(150.0, 200.0);

        bus.publish(new InputEvent.MousePressed(170.0, 200.0, CustomMouseButton.PRIMARY, 1));
        // Nur 3 Welt-Einheiten vom Zentrum entfernt gezogen -> unter dem Minimum
        bus.publish(new InputEvent.MouseDragged(153.0, 200.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseReleased(153.0, 200.0, CustomMouseButton.PRIMARY));

        assertThat(resizeCircleRequests).hasSize(1);
        assertThat(resizeCircleRequests.getFirst().newRadius()).isEqualTo(10.0); // Grid-Minimum
    }

    @Test
    @DisplayName("ESC bricht die Resize-Geste ohne Commit ab")
    void escapeShouldAbortWithoutCommit() {
        circleAt(150.0, 200.0);
        enterResizeModeAt(150.0, 200.0);

        bus.publish(new InputEvent.MousePressed(170.0, 200.0, CustomMouseButton.PRIMARY, 1));
        bus.publish(new InputEvent.MouseDragged(185.0, 200.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.KeyPressed(CustomKeyCode.ESCAPE));

        assertThat(resizeCircleRequests).isEmpty();
        assertThat(bus.getToolState()).isInstanceOf(ResizeModeState.class);
    }

    @Test
    @DisplayName("Klick neben dem Griff wählt ab und kehrt zum Standard-Werkzeug zurück")
    void clickBesideHandlesShouldDeselect() {
        circleAt(150.0, 200.0);
        enterResizeModeAt(150.0, 200.0);

        bus.publish(new InputEvent.MousePressed(400.0, 400.0, CustomMouseButton.PRIMARY, 1));

        assertThat(setSelectionRequests).hasSize(2);
        assertThat(setSelectionRequests.getLast().selectedIds()).isEmpty();
        assertThat(selectionFacts).hasSize(2);
        assertThat(bus.getToolState()).isInstanceOf(IdleToolState.class);
    }
}
