package de.fmc.editor.core;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomCursor;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifiziert die GESTEN-Cursor-Hinweise (CursorChanged): Kamera-Panning
 * und Verschiebe-Geste greifen mit CLOSED_HAND zu und geben den Override
 * beim Loslassen frei (null). Bewusst sparsam: Klick-ohne-Drag-Gesten
 * publizieren NICHTS — der Cursor soll nicht bei jedem Klick flackern.
 * Wie alle State→UI-Hinweise ist CursorChanged Sitzungszustand: Der
 * UndoService ignoriert ihn (Regressionstest am Ende — pro Geste genau
 * ein Undo-Punkt, trotz der Zwischen-Hinweise).
 */
class CursorHintTest {

    private BusTestSupport support;
    private InteractionEventBus bus;
    private final List<ActionEvent.CursorChanged> cursorHints = new ArrayList<>();

    @BeforeEach
    void setUp() {
        support = new BusTestSupport();
        bus = support.bus;
        bus.subscribe(ActionEvent.CursorChanged.class, cursorHints::add);
    }

    private void press(double x, double y, CustomMouseButton button) {
        bus.publish(new InputEvent.MousePressed(x, y, button, 1));
    }

    private void drag(double x, double y) {
        bus.publish(new InputEvent.MouseDragged(x, y, CustomMouseButton.MIDDLE));
    }

    private void dragPrimary(double x, double y) {
        bus.publish(new InputEvent.MouseDragged(x, y, CustomMouseButton.PRIMARY));
    }

    private void release(double x, double y, CustomMouseButton button) {
        bus.publish(new InputEvent.MouseReleased(x, y, button));
    }

    @Test
    @DisplayName("Kamera-Panning: erster Drag greift zu, Segmente rauschen nicht, Release gibt frei")
    void panGestureGrabsCursorOnceAndReleases() {
        press(300.0, 200.0, CustomMouseButton.MIDDLE);
        assertThat(cursorHints).isEmpty();

        drag(310.0, 200.0);   // erster Drag: Pan beginnt -> CLOSED_HAND
        assertThat(cursorHints).containsExactly(new ActionEvent.CursorChanged(CustomCursor.CLOSED_HAND));

        drag(320.0, 200.0);   // Folge-Segmente: kein zweiter Hinweis
        drag(320.0, 205.0);
        assertThat(cursorHints).hasSize(1);

        release(320.0, 205.0, CustomMouseButton.MIDDLE);
        assertThat(cursorHints).containsExactly(
                new ActionEvent.CursorChanged(CustomCursor.CLOSED_HAND),
                new ActionEvent.CursorChanged(null));
    }

    @Test
    @DisplayName("Mittelklick ohne Drag (Null-Delta-Pan) publiziert keinen Cursor-Hinweis")
    void middleClickWithoutDragStaysSilent() {
        press(300.0, 200.0, CustomMouseButton.MIDDLE);
        release(300.0, 200.0, CustomMouseButton.MIDDLE);
        assertThat(cursorHints).isEmpty();
    }

    @Test
    @DisplayName("Verschiebe-Geste: nach Schwellenwert CLOSED_HAND, Release gibt frei")
    void moveGestureGrabsCursorOnceAndReleases() {
        var circle = support.addCircle(100.0, 100.0);

        press(100.0, 100.0, CustomMouseButton.PRIMARY);
        dragPrimary(110.0, 100.0);   // Schwellenwert-Übergang: konsumiert, bewegt noch nicht
        assertThat(cursorHints).isEmpty();

        dragPrimary(120.0, 100.0);   // erstes echtes Move-Segment -> CLOSED_HAND
        assertThat(cursorHints).containsExactly(new ActionEvent.CursorChanged(CustomCursor.CLOSED_HAND));

        dragPrimary(130.0, 100.0);   // Folge-Segmente rauschen nicht
        assertThat(cursorHints).hasSize(1);

        release(130.0, 100.0, CustomMouseButton.PRIMARY);
        assertThat(cursorHints).containsExactly(
                new ActionEvent.CursorChanged(CustomCursor.CLOSED_HAND),
                new ActionEvent.CursorChanged(null));
        assertThat(support.diagram.findById(circle.id()).orElseThrow().x()).isEqualTo(130.0);
    }

    @Test
    @DisplayName("Einfacher Klick auf ein Objekt (ohne Drag) publiziert keinen Cursor-Hinweis")
    void plainClickOnObjectStaysSilent() {
        support.addCircle(100.0, 100.0);
        press(100.0, 100.0, CustomMouseButton.PRIMARY);
        release(100.0, 100.0, CustomMouseButton.PRIMARY);
        assertThat(cursorHints).isEmpty();
    }

    @Test
    @DisplayName("Cursor-Hinweise sind kein Undo-Punkt: Undo stellt die Geste komplett zurück")
    void cursorHintsDoNotPolluteUndo() {
        var circle = support.addCircle(100.0, 100.0);

        press(100.0, 100.0, CustomMouseButton.PRIMARY);
        dragPrimary(110.0, 100.0);
        dragPrimary(120.0, 100.0);
        dragPrimary(130.0, 100.0);
        release(130.0, 100.0, CustomMouseButton.PRIMARY);
        assertThat(cursorHints).hasSize(2); // CLOSED_HAND + null — beide kein Undo-Punkt

        bus.publish(new ActionEvent.RequestUndo());
        assertThat(support.diagram.findById(circle.id()).orElseThrow().x())
                .as("Undo der Move-Geste kehrt auf die Gesten-Start-Position zurück")
                .isEqualTo(100.0);

        // Redo-Stack blieb von den Hinweisen unberührt: Redo wiederholt die Geste.
        bus.publish(new ActionEvent.RequestRedo());
        assertThat(support.diagram.findById(circle.id()).orElseThrow().x()).isEqualTo(130.0);
    }
}
