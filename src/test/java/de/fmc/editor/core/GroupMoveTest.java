package de.fmc.editor.core;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.GridSpec;
import de.fmc.editor.core.service.DiagramService;
import de.fmc.editor.core.service.SelectionService;
import de.fmc.editor.core.service.UndoService;
import de.fmc.editor.core.state.CameraInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifiziert den Gruppen-Move: Plain-Press auf ein bereits selektiertes
 * Objekt verschiebt die GANZE Auswahl (die Auswahl kollabiert nicht);
 * Ctrl-Toggle-out zieht nur das Ziel selbst. Ein Undo-Punkt pro Geste,
 * egal wie viele Objekte und Drag-Segmente.
 */
class GroupMoveTest {

    private InteractionEventBus eventBus;
    private DiagramService diagram;
    private final List<DiagramEvent.SelectionChanged> selectionFacts = new ArrayList<>();
    private UUID defaultLayerId;

    @BeforeEach
    void setUp() {
        selectionFacts.clear();
        defaultLayerId = UUID.randomUUID();

        SelectionService selectionService = new SelectionService();
        diagram = new DiagramService();
        eventBus = new InteractionEventBus(diagram,
                new CameraInfo(1.0, 0.0, 0.0, defaultLayerId),
                new GridSpec(10.0), selectionService);
        diagram.attachTo(eventBus);
        selectionService.attachTo(eventBus);
        new UndoService().attachTo(eventBus);

        eventBus.subscribe(DiagramEvent.SelectionChanged.class, selectionFacts::add);
    }

    private Circle addCircle(double x, double y) {
        Circle circle = new Circle(UUID.randomUUID(), x, y, 20.0, defaultLayerId);
        eventBus.publish(new ActionEvent.AddElement(circle));
        return circle;
    }

    private void click(double x, double y, boolean ctrl) {
        eventBus.publish(new InputEvent.MousePressed(x, y, CustomMouseButton.PRIMARY, 1, ctrl));
        eventBus.publish(new InputEvent.MouseReleased(x, y, CustomMouseButton.PRIMARY));
    }

    @Test
    @DisplayName("Plain-Press auf ein selektiertes Objekt verschiebt die GANZE Auswahl")
    void groupMoveMovesWholeSelection() {
        Circle a = addCircle(100.0, 100.0);
        Circle b = addCircle(300.0, 100.0);
        click(100.0, 100.0, true);   // Ctrl: a rein
        click(300.0, 100.0, true);   // Ctrl: b dazu -> [a, b]

        // Plain-Press auf b (bereits selektiert) + Drag um insgesamt 40
        eventBus.publish(new InputEvent.MousePressed(300.0, 100.0, CustomMouseButton.PRIMARY, 1));
        eventBus.publish(new InputEvent.MouseDragged(310.0, 100.0, CustomMouseButton.PRIMARY));
        eventBus.publish(new InputEvent.MouseDragged(320.0, 100.0, CustomMouseButton.PRIMARY));
        eventBus.publish(new InputEvent.MouseDragged(340.0, 100.0, CustomMouseButton.PRIMARY));
        eventBus.publish(new InputEvent.MouseReleased(340.0, 100.0, CustomMouseButton.PRIMARY));

        assertThat(diagram.findById(a.id()).orElseThrow().x()).isEqualTo(140.0);
        assertThat(diagram.findById(b.id()).orElseThrow().x()).isEqualTo(340.0);
    }

    @Test
    @DisplayName("Plain-Press auf ein NICHT selektiertes Objekt kollabiert auf Einzelzug")
    void plainPressOnUnselectedCollapsesToSingle() {
        Circle a = addCircle(100.0, 100.0);
        Circle b = addCircle(300.0, 100.0);
        Circle c = addCircle(500.0, 100.0);
        click(100.0, 100.0, true);
        click(300.0, 100.0, true);   // Auswahl: [a, b]

        // Plain-Press auf c (unselektiert) + Drag um 40
        eventBus.publish(new InputEvent.MousePressed(500.0, 100.0, CustomMouseButton.PRIMARY, 1));
        eventBus.publish(new InputEvent.MouseDragged(510.0, 100.0, CustomMouseButton.PRIMARY));
        eventBus.publish(new InputEvent.MouseDragged(540.0, 100.0, CustomMouseButton.PRIMARY));
        eventBus.publish(new InputEvent.MouseReleased(540.0, 100.0, CustomMouseButton.PRIMARY));

        // Nur c ist bewegt und selektiert; a und b bleiben, wo sie waren
        assertThat(diagram.findById(a.id()).orElseThrow().x()).isEqualTo(100.0);
        assertThat(diagram.findById(b.id()).orElseThrow().x()).isEqualTo(300.0);
        assertThat(diagram.findById(c.id()).orElseThrow().x()).isEqualTo(540.0);
        assertThat(selectionFacts.getLast().selectedIds()).containsExactly(c.id());
    }

    @Test
    @DisplayName("Plain-Press auf Selektiertes feuert KEINEN SelectionChanged (kein Kollaps)")
    void plainPressOnSelectedDoesNotCollapseOrFireFact() {
        Circle a = addCircle(100.0, 100.0);
        Circle b = addCircle(300.0, 100.0);
        click(100.0, 100.0, true);
        click(300.0, 100.0, true);
        int factsBefore = selectionFacts.size();

        // Plain-Press auf b, dann ohne Drag loslassen (reiner Klick)
        eventBus.publish(new InputEvent.MousePressed(300.0, 100.0, CustomMouseButton.PRIMARY, 1));
        eventBus.publish(new InputEvent.MouseReleased(300.0, 100.0, CustomMouseButton.PRIMARY));

        assertThat(selectionFacts).hasSize(factsBefore);
        assertThat(selectionFacts.getLast().selectedIds())
                .containsExactlyInAnyOrder(a.id(), b.id());
    }

    @Test
    @DisplayName("Ctrl-Toggle-out + Drag bewegt nur das herausgetoggelte Ziel")
    void ctrlToggleOutThenDragMovesOnlyTarget() {
        Circle a = addCircle(100.0, 100.0);
        Circle b = addCircle(300.0, 100.0);
        click(100.0, 100.0, true);
        click(300.0, 100.0, true);   // [a, b]

        // Ctrl-Press auf b toggelt es RAUS; Drag bewegt nur b
        eventBus.publish(new InputEvent.MousePressed(300.0, 100.0, CustomMouseButton.PRIMARY, 1, true));
        eventBus.publish(new InputEvent.MouseDragged(310.0, 100.0, CustomMouseButton.PRIMARY));
        eventBus.publish(new InputEvent.MouseDragged(330.0, 100.0, CustomMouseButton.PRIMARY));
        eventBus.publish(new InputEvent.MouseReleased(330.0, 100.0, CustomMouseButton.PRIMARY));

        assertThat(diagram.findById(a.id()).orElseThrow().x()).isEqualTo(100.0);
        assertThat(diagram.findById(b.id()).orElseThrow().x()).isEqualTo(330.0);
    }

    @Test
    @DisplayName("EIN Undo-Punkt für die ganze Gruppen-Geste über mehrere Drag-Segmente")
    void undoGroupMoveRestoresBothInOneStep() {
        Circle a = addCircle(100.0, 100.0);
        Circle b = addCircle(300.0, 100.0);
        click(100.0, 100.0, true);
        click(300.0, 100.0, true);

        // Geste mit ZWEI echten Move-Segmenten (erstes Drag = Übergang):
        eventBus.publish(new InputEvent.MousePressed(300.0, 100.0, CustomMouseButton.PRIMARY, 1));
        eventBus.publish(new InputEvent.MouseDragged(310.0, 100.0, CustomMouseButton.PRIMARY));
        eventBus.publish(new InputEvent.MouseDragged(320.0, 100.0, CustomMouseButton.PRIMARY));
        eventBus.publish(new InputEvent.MouseDragged(340.0, 100.0, CustomMouseButton.PRIMARY));
        eventBus.publish(new InputEvent.MouseReleased(340.0, 100.0, CustomMouseButton.PRIMARY));
        assertThat(diagram.findById(a.id()).orElseThrow().x()).isEqualTo(140.0);
        assertThat(diagram.findById(b.id()).orElseThrow().x()).isEqualTo(340.0);

        // EIN Undo bringt BEIDE exakt zurück (Segment-Merge über die ID-Menge):
        eventBus.publish(new ActionEvent.RequestUndo());
        assertThat(diagram.findById(a.id()).orElseThrow().x()).isEqualTo(100.0);
        assertThat(diagram.findById(b.id()).orElseThrow().x()).isEqualTo(300.0);

        // Redo: beide wieder am Ende
        eventBus.publish(new ActionEvent.RequestRedo());
        assertThat(diagram.findById(a.id()).orElseThrow().x()).isEqualTo(140.0);
        assertThat(diagram.findById(b.id()).orElseThrow().x()).isEqualTo(340.0);
    }

    @Test
    @DisplayName("Service-Ebene: unbekannte IDs im MoveObjects werden still ignoriert")
    void moveObjectsIgnoresUnknownIds() {
        Circle a = addCircle(100.0, 100.0);

        eventBus.publish(new ActionEvent.MoveObjects(List.of(a.id(), UUID.randomUUID()), 10.0, 5.0));

        assertThat(diagram.findById(a.id()).orElseThrow().x()).isEqualTo(110.0);
        assertThat(diagram.findById(a.id()).orElseThrow().y()).isEqualTo(105.0);
    }
}
