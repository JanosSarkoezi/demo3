package de.fmc.editor.core;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.service.DiagramService;
import de.fmc.editor.core.state.impl.ResizeModeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifiziert die Resize-Geste Ende-zu-Ende (Entscheidungen 30.10.):
 * - Option B: Nach dem Commit bleibt der Resize-Modus — mit FRISCHEM Record
 *   (Regression für den User-Fund „Griffe nach dem Resize nicht greifbar":
 *   das beim Eintritt geschnappte target trug die Alt-Geometrie).
 * - Live-Vorschau: Pro Drag-Segment ein ResizePreviewChanged-Hinweis mit
 *   der exakten Commit-Mathematik (symmetrisch, Grid-Klemmung); Vorschau-
 *   Ende als Null-Hinweis bei Commit UND Abbruch.
 * - Die Hinweise sind Sitzungszustand: KEINE Modell-Fakten, kein Undo-Punkt.
 */
class ResizeFlowTest {

    private BusTestSupport support;
    private InteractionEventBus bus;
    private DiagramService diagram;
    private final List<ActionEvent.ResizePreviewChanged> previewHints = new ArrayList<>();
    private final List<DiagramEvent.ObjectResized> resizeFacts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        support = new BusTestSupport();
        bus = support.bus;
        diagram = support.diagram;
        bus.subscribe(ActionEvent.ResizePreviewChanged.class, previewHints::add);
        bus.subscribe(DiagramEvent.ObjectResized.class, resizeFacts::add);
    }

    private Circle circleWithRadius20() {
        Circle circle = support.addCircle(100.0, 100.0);
        support.closeGesture();
        return circle;
    }

    /** Doppelklick → Resize-Modus; Griff an (x, y) greifen und nach (endX, endY) ziehen. */
    private void dragHandle(double gripX, double gripY, double endX, double endY) {
        bus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.PRIMARY, 2));
        bus.publish(new InputEvent.MousePressed(gripX, gripY, CustomMouseButton.PRIMARY, 1));
        bus.publish(new InputEvent.MouseDragged(endX, endY, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseReleased(endX, endY, CustomMouseButton.PRIMARY));
    }

    @Test
    @DisplayName("Option B: Modus bleibt nach dem Commit — Griffe am FRISCHEN Record greifbar")
    void handlesStayGrabbableAfterCommit() {
        Circle circle = circleWithRadius20();

        // Erstresize: Ost-Griff (120,100) nach (140,100) ziehen -> r=40
        dragHandle(120.0, 100.0, 140.0, 100.0);
        assertThat(bus.getToolState()).isInstanceOf(ResizeModeState.class);
        Circle afterFirst = (Circle) diagram.findById(circle.id()).orElseThrow();
        assertThat(afterFirst.radius()).isEqualTo(40.0);

        // ZWEITER Griff am NEUEN Ost-Griff (140,100) -> (150,100) -> r=50.
        // Ohne Fresh-Read prüfte der Modus gegen die Alt-Position (120,100)
        // — der Griff-Klick gälte als „Klick daneben" und verließe den Modus.
        bus.publish(new InputEvent.MousePressed(140.0, 100.0, CustomMouseButton.PRIMARY, 1));
        bus.publish(new InputEvent.MouseDragged(150.0, 100.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseReleased(150.0, 100.0, CustomMouseButton.PRIMARY));

        assertThat(bus.getToolState()).isInstanceOf(ResizeModeState.class);
        Circle afterSecond = (Circle) diagram.findById(circle.id()).orElseThrow();
        assertThat(afterSecond.radius()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("Live-Vorschau: pro Drag-Segment ein Hinweis mit der Commit-Mathematik")
    void dragPublishesLivePreviewWithCommitMath() {
        Circle circle = circleWithRadius20();

        bus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.PRIMARY, 2));
        bus.publish(new InputEvent.MousePressed(120.0, 100.0, CustomMouseButton.PRIMARY, 1));
        bus.publish(new InputEvent.MouseDragged(130.0, 100.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseDragged(140.0, 100.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseReleased(140.0, 100.0, CustomMouseButton.PRIMARY));

        // Vorschau-Hinweise: r=30 nach dem ersten, r=40 nach dem zweiten Segment …
        assertThat(previewHints).hasSize(3); // 2× Vorschau + 1× Ende (null)
        Circle preview1 = (Circle) previewHints.get(0).preview();
        Circle preview2 = (Circle) previewHints.get(1).preview();
        assertThat(preview1.radius()).isEqualTo(30.0);
        assertThat(preview2.radius()).isEqualTo(30.0 + 10.0);
        // … und der letzte Hinweis beendet die Vorschau:
        assertThat(previewHints.getLast().preview()).isNull();

        // Der Commit ist trotzdem genau EIN Fakt mit demselben Ergebnis:
        assertThat(resizeFacts).hasSize(1);
        Circle committed = (Circle) resizeFacts.getFirst().resized();
        assertThat(committed.radius()).isEqualTo(preview2.radius());
        assertThat(diagram.findById(circle.id()).orElseThrow()).isEqualTo(committed);
    }

    @Test
    @DisplayName("Vorschau klemmt symmetrisch: Radius nie kleiner als eine Grid-Zelle (10)")
    void previewRespectsGridClamp() {
        circleWithRadius20();

        bus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.PRIMARY, 2));
        bus.publish(new InputEvent.MousePressed(120.0, 100.0, CustomMouseButton.PRIMARY, 1));
        // Mitten aufs Zentrum ziehen — Radius klemmt auf gridSize (10):
        bus.publish(new InputEvent.MouseDragged(100.0, 100.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseReleased(100.0, 100.0, CustomMouseButton.PRIMARY));

        Circle preview = (Circle) previewHints.getFirst().preview();
        assertThat(preview.radius()).isEqualTo(10.0);
    }

    @Test
    @DisplayName("ESC mitten im Drag: Vorschau-Ende-Hinweis, KEIN Commit, Modell unverändert")
    void escapeClearsPreviewWithoutCommit() {
        Circle circle = circleWithRadius20();

        bus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.PRIMARY, 2));
        bus.publish(new InputEvent.MousePressed(120.0, 100.0, CustomMouseButton.PRIMARY, 1));
        bus.publish(new InputEvent.MouseDragged(140.0, 100.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.KeyPressed(CustomKeyCode.ESCAPE));

        assertThat(previewHints.getLast().preview()).isNull();
        assertThat(resizeFacts).isEmpty();
        assertThat(diagram.findById(circle.id()).orElseThrow()).isEqualTo(circle);
        // Abbruch bleibt im Modus (Option B), target war noch aktuell:
        assertThat(bus.getToolState()).isInstanceOf(ResizeModeState.class);
    }

    @Test
    @DisplayName("Vorschau-Hinweise sind KEINE Undo-Punkte — EIN Undo reicht nach der Geste")
    void previewHintsAreNoUndoPoints() {
        Circle circle = circleWithRadius20();

        dragHandle(120.0, 100.0, 140.0, 100.0);
        assertThat(previewHints).isNotEmpty();

        bus.publish(new ActionEvent.RequestUndo());
        Circle undone = (Circle) diagram.findById(circle.id()).orElseThrow();
        assertThat(undone.radius()).isEqualTo(20.0);
    }
}
