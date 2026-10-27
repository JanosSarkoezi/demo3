package de.fmc.editor.core;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.GridSpec;
import de.fmc.editor.core.service.DiagramService;
import de.fmc.editor.core.service.SelectionService;
import de.fmc.editor.core.state.CameraInfo;
import de.fmc.editor.core.state.impl.IdleToolState;
import de.fmc.editor.core.state.impl.PendingMoveState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifiziert die entschiedene Selektions-Semantik (doc/spec.md, demo2):
 * Selektion bei Maus-Druck — einfacher Klick = exklusiv, Ctrl+Klick =
 * Toggle, Leere-Klick / ESC = Auswahl leeren. DEL löscht die Auswahl über
 * den Bus; der SelectionService staubt tote IDs automatisch ab.
 */
class ClickSelectionTest {

    private InteractionEventBus eventBus;
    private DiagramService diagramService;
    private final List<DiagramEvent.SelectionChanged> selectionFacts = new ArrayList<>();
    private final List<ActionEvent.DeleteObjects> deleteRequests = new ArrayList<>();
    private UUID defaultLayerId;

    @BeforeEach
    void setUp() {
        selectionFacts.clear();
        deleteRequests.clear();
        defaultLayerId = UUID.randomUUID();

        // Vollständige zweiphasige Initialisierung: Beide Services hängen am Bus.
        SelectionService selectionService = new SelectionService();
        diagramService = new DiagramService();
        eventBus = new InteractionEventBus(diagramService,
                new CameraInfo(1.0, 0.0, 0.0, defaultLayerId),
                new GridSpec(10.0), selectionService);
        diagramService.attachTo(eventBus);
        selectionService.attachTo(eventBus);

        eventBus.subscribe(DiagramEvent.SelectionChanged.class, selectionFacts::add);
        eventBus.subscribe(ActionEvent.DeleteObjects.class, deleteRequests::add);
    }

    private UUID addCircleAt(double x, double y) {
        Circle circle = new Circle(UUID.randomUUID(), x, y, 20.0, defaultLayerId);
        eventBus.publish(new ActionEvent.AddElement(circle));
        return circle.id();
    }

    @Test
    @DisplayName("Einfacher Klick selektiert das Objekt exklusiv")
    void clickShouldSelectObject() {
        UUID a = addCircleAt(100.0, 100.0);
        eventBus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.PRIMARY, 1));

        assertThat(eventBus.getToolState()).isInstanceOf(PendingMoveState.class);
        assertThat(selectionFacts).hasSize(1);
        assertThat(selectionFacts.getFirst().selectedIds()).containsExactly(a);
    }

    @Test
    @DisplayName("Klick auf ein anderes Objekt ersetzt die Auswahl")
    void clickOnOtherObjectReplacesSelection() {
        addCircleAt(100.0, 100.0);
        UUID b = addCircleAt(300.0, 100.0);

        eventBus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.PRIMARY, 1));
        eventBus.publish(new InputEvent.MouseReleased(100.0, 100.0, CustomMouseButton.PRIMARY));
        eventBus.publish(new InputEvent.MousePressed(300.0, 100.0, CustomMouseButton.PRIMARY, 1));

        assertThat(selectionFacts).hasSize(2);
        assertThat(selectionFacts.get(1).selectedIds()).containsExactly(b);
    }

    @Test
    @DisplayName("Ctrl+Klick nimmt ein weiteres Objekt zusätzlich in die Auswahl auf")
    void ctrlClickAddsToSelection() {
        UUID a = addCircleAt(100.0, 100.0);
        UUID b = addCircleAt(300.0, 100.0);

        eventBus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.PRIMARY, 1));
        eventBus.publish(new InputEvent.MouseReleased(100.0, 100.0, CustomMouseButton.PRIMARY));
        eventBus.publish(new InputEvent.MousePressed(300.0, 100.0, CustomMouseButton.PRIMARY, 1, true));

        assertThat(selectionFacts.getLast().selectedIds()).containsExactlyInAnyOrder(a, b);
    }

    @Test
    @DisplayName("Ctrl+Klick auf ein bereits selektiertes Objekt entfernt es wieder")
    void ctrlClickOnSelectedTogglesOff() {
        UUID a = addCircleAt(100.0, 100.0);
        UUID b = addCircleAt(300.0, 100.0);

        eventBus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.PRIMARY, 1));
        eventBus.publish(new InputEvent.MouseReleased(100.0, 100.0, CustomMouseButton.PRIMARY));
        eventBus.publish(new InputEvent.MousePressed(300.0, 100.0, CustomMouseButton.PRIMARY, 1, true));
        eventBus.publish(new InputEvent.MouseReleased(300.0, 100.0, CustomMouseButton.PRIMARY));
        // Erneuter Ctrl+Klick auf b -> b fliegt wieder raus, nur a bleibt
        eventBus.publish(new InputEvent.MousePressed(300.0, 100.0, CustomMouseButton.PRIMARY, 1, true));

        assertThat(selectionFacts.getLast().selectedIds()).containsExactly(a);
    }

    @Test
    @DisplayName("Klick auf die leere Fläche hebt die Auswahl auf")
    void clickOnEmptySpaceClearsSelection() {
        addCircleAt(100.0, 100.0);
        eventBus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.PRIMARY, 1));
        eventBus.publish(new InputEvent.MouseReleased(100.0, 100.0, CustomMouseButton.PRIMARY));

        eventBus.publish(new InputEvent.MousePressed(500.0, 500.0, CustomMouseButton.PRIMARY, 1));

        assertThat(selectionFacts.getLast().selectedIds()).isEmpty();
    }

    @Test
    @DisplayName("ESC hebt die Auswahl auf, ohne den Zustand zu wechseln")
    void escapeClearsSelection() {
        UUID a = addCircleAt(100.0, 100.0);
        eventBus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.PRIMARY, 1));
        eventBus.publish(new InputEvent.MouseReleased(100.0, 100.0, CustomMouseButton.PRIMARY));
        assertThat(selectionFacts.getFirst().selectedIds()).containsExactly(a);

        eventBus.publish(new InputEvent.KeyPressed(CustomKeyCode.ESCAPE));

        assertThat(eventBus.getToolState()).isInstanceOf(IdleToolState.class);
        assertThat(selectionFacts.getLast().selectedIds()).isEmpty();
    }

    @Test
    @DisplayName("DEL löscht die Auswahl über den Bus; die Auswahl wird danach automatisch geleert")
    void delDeletesSelectionAndPrunesIt() {
        UUID a = addCircleAt(100.0, 100.0);
        eventBus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.PRIMARY, 1));
        eventBus.publish(new InputEvent.MouseReleased(100.0, 100.0, CustomMouseButton.PRIMARY));

        eventBus.publish(new InputEvent.KeyPressed(CustomKeyCode.DELETE));

        assertThat(deleteRequests).hasSize(1);
        assertThat(deleteRequests.getFirst().ids()).containsExactly(a);
        // Service hat das Objekt entfernt -> SelectionService staubt die tote ID ab:
        assertThat(selectionFacts.getLast().selectedIds()).isEmpty();
        assertThat(diagramService.findById(a)).isEmpty();
    }

    @Test
    @DisplayName("DEL ohne Auswahl feuert keinen Lösch-Request")
    void delWithoutSelectionFiresNothing() {
        addCircleAt(100.0, 100.0);

        eventBus.publish(new InputEvent.KeyPressed(CustomKeyCode.DELETE));

        assertThat(deleteRequests).isEmpty();
    }
}
