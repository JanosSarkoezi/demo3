package de.fmc.editor.core;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Event;
import de.fmc.editor.core.model.EventSide;
import de.fmc.editor.core.model.GridSpec;
import de.fmc.editor.core.model.Rectangle;
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
import static org.assertj.core.api.Assertions.within;

/**
 * Event-Werkzeug auf Gesten-Ebene (17.11.): Die Press-Regel verzweigt in der
 * Aktion — Formgriff-Greifen (achsen-gebundener Drag), EditEventLabel für
 * bestehende Events, CreateEventFor mit Seitenlage aus der Klickhälfte.
 * Der Form-Drag committet genau EIN ResizeEvent beim Loslassen (Preview-
 * Hinweise zwischendurch, kein Modell-Fakt im Drag); KEY_E-Wechsel; ESC
 * verlässt das Werkzeug.
 */
class EventToolTest {

    private InteractionEventBus eventBus;
    private DiagramService diagram;
    private UUID defaultLayerId;
    private Rectangle owner;

    private final List<ActionEvent.CreateEventFor> createEventIntents = new ArrayList<>();
    private final List<ActionEvent.EditEventLabel> editLabelIntents = new ArrayList<>();
    private final List<ActionEvent.ResizeEvent> resizeRequests = new ArrayList<>();
    private final List<ActionEvent.ResizePreviewChanged> previews = new ArrayList<>();
    private final List<DiagramEvent.ObjectAdded> addedFacts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        createEventIntents.clear();
        editLabelIntents.clear();
        resizeRequests.clear();
        previews.clear();
        addedFacts.clear();
        defaultLayerId = UUID.randomUUID();

        SelectionService selectionService = new SelectionService();
        diagram = new DiagramService();
        eventBus = new InteractionEventBus(diagram,
                new CameraInfo(1.0, 0.0, 0.0, defaultLayerId),
                new GridSpec(10.0), selectionService);
        diagram.attachTo(eventBus);
        selectionService.attachTo(eventBus);
        new UndoService().attachTo(eventBus);

        eventBus.subscribe(ActionEvent.CreateEventFor.class, createEventIntents::add);
        eventBus.subscribe(ActionEvent.EditEventLabel.class, editLabelIntents::add);
        eventBus.subscribe(ActionEvent.ResizeEvent.class, resizeRequests::add);
        eventBus.subscribe(ActionEvent.ResizePreviewChanged.class, previews::add);
        eventBus.subscribe(DiagramEvent.ObjectAdded.class, addedFacts::add);

        owner = new Rectangle(UUID.randomUUID(), 100.0, 100.0, 40.0, 30.0, defaultLayerId);
        eventBus.publish(new ActionEvent.AddElement(owner));
        // R: Zentrum 100/100, 40x30 -> Seitenränder x=80/120, Oberkante y=85.
        eventBus.publish(new InputEvent.MouseReleased(0.0, 0.0, CustomMouseButton.PRIMARY));
    }

    // ---------- Helfer ----------

    private void selectEventTool() {
        eventBus.publish(new ActionEvent.SelectTool(de.fmc.editor.core.model.ToolType.EVENT));
    }

    private void press(double x, double y) {
        eventBus.publish(new InputEvent.MousePressed(x, y, CustomMouseButton.PRIMARY, 1, false));
    }

    private void drag(double x, double y) {
        eventBus.publish(new InputEvent.MouseDragged(x, y, CustomMouseButton.PRIMARY));
    }

    private void release() {
        eventBus.publish(new InputEvent.MouseReleased(0.0, 0.0, CustomMouseButton.PRIMARY));
    }

    /** Legt ein Event im Modell an (Service-Pfad) und liefert das Record. */
    private Event addEvent(EventSide side) {
        eventBus.publish(new ActionEvent.CreateEvent(owner.id(), side, "E"));
        release();
        return (Event) addedFacts.get(addedFacts.size() - 1).object();
    }

    // ---------- Erstellung: Klickhälfte bestimmt die Seite ----------

    @Test
    @DisplayName("Klick auf die rechte R-Hälfte -> CreateEventFor(owner, RIGHT, Anker)")
    void pressOnRightHalfCreatesRightIntent() {
        selectEventTool();
        press(110.0, 100.0);

        assertThat(createEventIntents).hasSize(1);
        ActionEvent.CreateEventFor intent = createEventIntents.get(0);
        assertThat(intent.ownerId()).isEqualTo(owner.id());
        assertThat(intent.side()).isEqualTo(EventSide.RIGHT);
        // Label-Anker über dem Startpunkt: Start = (120 + 40, 85 - 40) = (160, 45)
        assertThat(intent.worldX()).isCloseTo(160.0, within(1e-9));
        assertThat(intent.worldY()).isCloseTo(35.0, within(1e-9));
    }

    @Test
    @DisplayName("Klick auf die linke R-Hälfte -> CreateEventFor(owner, LEFT, ...)")
    void pressOnLeftHalfCreatesLeftIntent() {
        selectEventTool();
        press(90.0, 100.0);

        assertThat(createEventIntents).hasSize(1);
        assertThat(createEventIntents.get(0).side()).isEqualTo(EventSide.LEFT);
        assertThat(createEventIntents.get(0).worldX()).isCloseTo(40.0, within(1e-9));
    }

    @Test
    @DisplayName("Klick auf ein bestehendes Event -> EditEventLabel")
    void pressOnEventEditsLabel() {
        Event event = addEvent(EventSide.RIGHT);
        selectEventTool();

        // Auf dem senkrechten Stück (6 px von beiden Griffen entfernt)
        press(event.x(), 60.0);
        assertThat(editLabelIntents).extracting(ActionEvent.EditEventLabel::eventId)
                .containsExactly(event.id());
        assertThat(createEventIntents).isEmpty();
    }

    @Test
    @DisplayName("Klick auf leere Fläche / K -> stiller No-Op")
    void pressOnEmptyOrCircleIsSilent() {
        selectEventTool();
        press(400.0, 400.0);
        assertThat(createEventIntents).isEmpty();
        assertThat(editLabelIntents).isEmpty();

        // Kreis unter dem Cursor: kein Event möglich, kein Intent
        eventBus.publish(new ActionEvent.AddElement(
                new de.fmc.editor.core.model.Circle(UUID.randomUUID(), 300.0, 300.0, 20.0, defaultLayerId)));
        release();
        press(300.0, 300.0);
        assertThat(createEventIntents).isEmpty();
    }

    // ---------- Formgriff-Drags (achsen-gebunden) ----------

    @Test
    @DisplayName("Start-Griff zieht NUR horizontal: ein ResizeEvent(80, 40) beim Loslassen")
    void startHandleDragsOnlyHorizontal() {
        Event event = addEvent(EventSide.RIGHT); // Start (160/45), Knick (110/45)
        selectEventTool();

        press(160.0, 45.0);
        assertThat(resizeRequests).isEmpty(); // Press allein committet nichts

        drag(200.0, 25.0); // y-Änderung muss IGNORIERT werden
        assertThat(previews).isNotEmpty();
        assertThat(resizeRequests).isEmpty(); // Im Drag KEIN Modell-Fakt

        release();
        assertThat(resizeRequests).hasSize(1);
        assertThat(resizeRequests.get(0).horizontalExtent()).isCloseTo(80.0, within(1e-9));
        assertThat(resizeRequests.get(0).verticalRise()).isCloseTo(40.0, within(1e-9));

        Event updated = (Event) diagram.findById(event.id()).orElseThrow();
        assertThat(updated.horizontalExtent()).isCloseTo(80.0, within(1e-9));
        assertThat(updated.verticalRise()).isCloseTo(40.0, within(1e-9));
    }

    @Test
    @DisplayName("Knick-Griff zieht NUR vertikal: ein ResizeEvent(40, 60), x ignoriert")
    void kinkHandleDragsOnlyVertical() {
        Event event = addEvent(EventSide.RIGHT);
        selectEventTool();

        press(110.0, 45.0);
        drag(500.0, 25.0); // x-Änderung muss IGNORIERT werden
        release();

        assertThat(resizeRequests).hasSize(1);
        assertThat(resizeRequests.get(0).horizontalExtent()).isCloseTo(40.0, within(1e-9));
        assertThat(resizeRequests.get(0).verticalRise()).isCloseTo(60.0, within(1e-9));
    }

    @Test
    @DisplayName("Unterschreiten klemmt auf 20 — und die ganze Geste ist EIN Undo-Punkt")
    void dragClampsAndIsOneUndoPoint() {
        Event event = addEvent(EventSide.RIGHT);
        selectEventTool();

        press(110.0, 45.0);
        drag(110.0, 200.0); // unter das R gezogen -> Klemmung
        drag(110.0, 180.0);
        release();

        assertThat(resizeRequests).hasSize(1);
        assertThat(resizeRequests.get(0).verticalRise()).isCloseTo(20.0, within(1e-9));

        eventBus.publish(new ActionEvent.RequestUndo());
        Event restored = (Event) diagram.findById(event.id()).orElseThrow();
        assertThat(restored.verticalRise()).isCloseTo(40.0, within(1e-9));
    }

    @Test
    @DisplayName("ESC im Drag bricht ohne Commit ab — kein Request, kein Fakt")
    void escAbortsDragWithoutCommit() {
        addEvent(EventSide.RIGHT);
        selectEventTool();

        press(160.0, 45.0);
        drag(200.0, 25.0);
        eventBus.publish(new InputEvent.KeyPressed(CustomKeyCode.ESCAPE));

        assertThat(resizeRequests).isEmpty();
        assertThat(previews).isNotEmpty();
    }

    // ---------- Werkzeug-Wechsel ----------

    @Test
    @DisplayName("KEY_E wechselt ins Event-Werkzeug; KEY_K zurück zum Kreis-Werkzeug")
    void keyEAndKeyKSwitchTools() {
        eventBus.publish(new InputEvent.KeyPressed(CustomKeyCode.KEY_E));
        press(110.0, 100.0);
        assertThat(createEventIntents).hasSize(1); // Event-Tool aktiv

        eventBus.publish(new InputEvent.KeyPressed(CustomKeyCode.KEY_K));
        press(90.0, 100.0);
        assertThat(createEventIntents).hasSize(1); // KEIN neuer Intent: Kreis-Tool
    }

    @Test
    @DisplayName("ESC verlässt das Event-Werkzeug zurück zum Auswahl-Werkzeug")
    void escLeavesEventTool() {
        selectEventTool();
        eventBus.publish(new InputEvent.KeyPressed(CustomKeyCode.ESCAPE));

        press(110.0, 100.0);
        assertThat(createEventIntents).isEmpty(); // Idle: kein Erstell-Intent
    }
}
