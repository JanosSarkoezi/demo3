package de.fmc.editor.core;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.GridSpec;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.Waypoint;
import de.fmc.editor.core.service.DiagramService;
import de.fmc.editor.core.service.SelectionService;
import de.fmc.editor.core.service.UndoService;
import de.fmc.editor.core.state.CameraInfo;
import de.fmc.editor.core.state.impl.IdleToolState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifiziert die Gummiband-Auswahl (Ctrl+Klick+Drag auf leerer Fläche):
 * Stumm während des Ziehens, beim Loslassen EIN deklaratives SetSelection
 * mit den Welt-Rechteck-Treffern (ersetzt die bisherige Auswahl).
 * Normales Leerraum-Drag bleibt Panning (Regressionsschutz).
 */
class BoxSelectionTest {

    private InteractionEventBus eventBus;
    private DiagramService diagramService;
    private final List<DiagramEvent.SelectionChanged> selectionFacts = new ArrayList<>();
    private UUID defaultLayerId;

    @BeforeEach
    void setUp() {
        selectionFacts.clear();
        defaultLayerId = UUID.randomUUID();

        SelectionService selectionService = new SelectionService();
        diagramService = new DiagramService();
        eventBus = new InteractionEventBus(diagramService,
                new CameraInfo(1.0, 0.0, 0.0, defaultLayerId),
                new GridSpec(10.0), selectionService);
        diagramService.attachTo(eventBus);
        selectionService.attachTo(eventBus);
        new UndoService().attachTo(eventBus);

        eventBus.subscribe(DiagramEvent.SelectionChanged.class, selectionFacts::add);
    }

    private Circle addCircle(double x, double y) {
        Circle circle = new Circle(UUID.randomUUID(), x, y, 20.0, defaultLayerId);
        eventBus.publish(new ActionEvent.AddElement(circle));
        return circle;
    }

    private Rectangle addRectangle(double x, double y) {
        Rectangle rect = new Rectangle(UUID.randomUUID(), x, y, 40.0, 30.0, defaultLayerId);
        eventBus.publish(new ActionEvent.AddElement(rect));
        return rect;
    }

    /** Gummiband-Geste: Ctrl-Press, Drag über die Schwelle, Release. */
    private void performBand(double startX, double startY, double endX, double endY) {
        eventBus.publish(new InputEvent.MousePressed(startX, startY, CustomMouseButton.PRIMARY, 1, true));
        eventBus.publish(new InputEvent.MouseDragged(endX, endY, CustomMouseButton.PRIMARY));
        eventBus.publish(new InputEvent.MouseReleased(endX, endY, CustomMouseButton.PRIMARY));
    }

    @Test
    @DisplayName("Gummiband selektiert alle Objekte im Rechteck — in z-Ordnung")
    void bandSelectsOverlappingObjects() {
        Circle a = addCircle(100.0, 100.0);
        Circle b = addCircle(300.0, 100.0);
        addRectangle(200.0, 400.0); // unterhalb des Bandes

        performBand(50.0, 50.0, 350.0, 150.0);

        assertThat(selectionFacts.getLast().selectedIds())
                .containsExactly(a.id(), b.id());
    }

    @Test
    @DisplayName("Gummiband ohne Treffer ersetzt die Auswahl durch die leere Menge")
    void bandWithoutHitsClearsSelection() {
        Circle a = addCircle(100.0, 100.0);
        eventBus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.PRIMARY, 1));
        eventBus.publish(new InputEvent.MouseReleased(100.0, 100.0, CustomMouseButton.PRIMARY));
        assertThat(selectionFacts.getLast().selectedIds()).containsExactly(a.id());

        performBand(500.0, 500.0, 700.0, 600.0);

        assertThat(selectionFacts.getLast().selectedIds()).isEmpty();
    }

    @Test
    @DisplayName("Ctrl+Klick ins Leere ohne Drag ist ein No-Op (kein Event)")
    void ctrlClickOnEmptyWithoutDragChangesNothing() {
        Circle a = addCircle(100.0, 100.0);
        eventBus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.PRIMARY, 1));
        eventBus.publish(new InputEvent.MouseReleased(100.0, 100.0, CustomMouseButton.PRIMARY));
        int factsBefore = selectionFacts.size();

        eventBus.publish(new InputEvent.MousePressed(500.0, 500.0, CustomMouseButton.PRIMARY, 1, true));
        eventBus.publish(new InputEvent.MouseReleased(500.0, 500.0, CustomMouseButton.PRIMARY));

        assertThat(selectionFacts).hasSize(factsBefore);
        assertThat(selectionFacts.getLast().selectedIds()).containsExactly(a.id());
    }

    @Test
    @DisplayName("ESC während des Gummibands bricht ohne Event ab")
    void escapeDuringBandKeepsSelection() {
        Circle a = addCircle(100.0, 100.0);
        eventBus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.PRIMARY, 1));
        eventBus.publish(new InputEvent.MouseReleased(100.0, 100.0, CustomMouseButton.PRIMARY));
        int factsBefore = selectionFacts.size();

        eventBus.publish(new InputEvent.MousePressed(500.0, 500.0, CustomMouseButton.PRIMARY, 1, true));
        eventBus.publish(new InputEvent.MouseDragged(600.0, 600.0, CustomMouseButton.PRIMARY));
        eventBus.publish(new InputEvent.KeyPressed(CustomKeyCode.ESCAPE));

        assertThat(eventBus.getToolState()).isInstanceOf(IdleToolState.class);
        assertThat(selectionFacts).hasSize(factsBefore);
        assertThat(selectionFacts.getLast().selectedIds()).containsExactly(a.id());
    }

    @Test
    @DisplayName("Normales Leerraum-Drag (ohne Ctrl) bleibt Panning")
    void plainDragOnEmptyStillPans() {
        Circle a = addCircle(100.0, 100.0);
        // Erst etwas selektieren, damit das Leeren bei Press sichtbar wird:
        eventBus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.PRIMARY, 1));
        eventBus.publish(new InputEvent.MouseReleased(100.0, 100.0, CustomMouseButton.PRIMARY));
        assertThat(selectionFacts.getLast().selectedIds()).containsExactly(a.id());

        // Normales (ohne Ctrl) Leerraum-Drag -> Auswahl leeren + Panning
        eventBus.publish(new InputEvent.MousePressed(500.0, 500.0, CustomMouseButton.PRIMARY, 1));
        assertThat(selectionFacts.getLast().selectedIds()).isEmpty();

        eventBus.publish(new InputEvent.MouseDragged(600.0, 500.0, CustomMouseButton.PRIMARY));
        eventBus.publish(new InputEvent.MouseDragged(700.0, 500.0, CustomMouseButton.PRIMARY));
        eventBus.publish(new InputEvent.MouseReleased(700.0, 500.0, CustomMouseButton.PRIMARY));

        assertThat(eventBus.getCameraInfo().offsetX()).isEqualTo(200.0);
    }

    @Test
    @DisplayName("Gummiband rechnet über die Kamera in Welt-Koordinaten (nach Panning)")
    void bandUsesWorldCoordinatesAfterPan() {
        Circle a = addCircle(200.0, 200.0);

        // Ansicht um (100, 0) verschieben: Objekt erscheint bei Screen (300, 200)
        eventBus.publish(new InputEvent.MousePressed(0.0, 0.0, CustomMouseButton.MIDDLE, 1));
        eventBus.publish(new InputEvent.MouseDragged(100.0, 0.0, CustomMouseButton.MIDDLE));
        eventBus.publish(new InputEvent.MouseReleased(100.0, 0.0, CustomMouseButton.MIDDLE));

        // Band über Screen (280, 180)-(320, 220) entspricht Welt (180, 180)-(220, 220)
        performBand(280.0, 180.0, 320.0, 220.0);

        assertThat(selectionFacts.getLast().selectedIds()).containsExactly(a.id());
    }

    @Test
    @DisplayName("Gummiband + DEL löscht die gesamte Auswahl (Sammellöschen)")
    void bandPlusDelDeletesAll() {
        Circle a = addCircle(100.0, 100.0);
        Circle b = addCircle(300.0, 100.0);

        performBand(50.0, 50.0, 350.0, 150.0);
        eventBus.publish(new InputEvent.KeyPressed(CustomKeyCode.DELETE));

        assertThat(diagramService.findById(a.id())).isEmpty();
        assertThat(diagramService.findById(b.id())).isEmpty();
    }

    @Test
    @DisplayName("Waypoints sind keine Gummiband-Ziele")
    void waypointsAreNotBandTargets() {
        Circle a = addCircle(100.0, 100.0);
        Rectangle rect = addRectangle(300.0, 100.0);
        Waypoint waypoint = new Waypoint(UUID.randomUUID(), 200.0, 100.0, defaultLayerId);
        eventBus.publish(new ActionEvent.CreateConnection(a.id(), rect.id(), List.of(waypoint)));

        // Band überspannt ALLES — auch den Waypoint auf (200, 100)
        performBand(0.0, 0.0, 400.0, 200.0);

        assertThat(selectionFacts.getLast().selectedIds())
                .containsExactly(a.id(), rect.id());
    }
}
