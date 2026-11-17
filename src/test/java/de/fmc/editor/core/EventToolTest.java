package de.fmc.editor.core;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Event;
import de.fmc.editor.core.model.EventDirection;
import de.fmc.editor.core.model.EventSide;
import de.fmc.editor.core.model.GridSpec;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.Text;
import de.fmc.editor.core.model.ToolType;
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
 * Event-Werkzeug auf Gesten-Ebene (17.11., Kardinalität/Seitenlogik 23.11.,
 * Vier-Quadranten-Modell 26.11.): Die Press-Regel verzweigt in der Aktion —
 * Formgriff-Greifen (achsen-gebundener Drag), EditEventLabel für
 * bestehende Events und ihre Label-Textbox, CreateEventFor mit RICHTUNG aus
 * der vertikalen Klickhälfte (oben = hereinkommend, unten = rausgehend) und
 * SEITE aus der horizontalen (Erstellen nur ohne bestehendes Event der
 * Richtung — 0..1 je Richtung) sowie SetEventSide + Label-Spiegelung beim
 * UMZIEHEN in das horizontal andere Quadrant. Der Form-Drag committet genau
 * EIN ResizeEvent beim Loslassen; KEY_E-Wechsel; ESC verlässt das Werkzeug.
 */
class EventToolTest {

    private InteractionEventBus eventBus;
    private DiagramService diagram;
    private UUID defaultLayerId;
    private Rectangle owner;

    private final List<ActionEvent.CreateEventFor> createEventIntents = new ArrayList<>();
    private final List<ActionEvent.EditEventLabel> editLabelIntents = new ArrayList<>();
    private final List<ActionEvent.SetEventSide> sideRequests = new ArrayList<>();
    private final List<ActionEvent.ResizeEvent> resizeRequests = new ArrayList<>();
    private final List<ActionEvent.ResizePreviewChanged> previews = new ArrayList<>();
    private final List<DiagramEvent.ObjectAdded> addedFacts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        createEventIntents.clear();
        editLabelIntents.clear();
        sideRequests.clear();
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
        eventBus.subscribe(ActionEvent.SetEventSide.class, sideRequests::add);
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
        eventBus.publish(new ActionEvent.SelectTool(ToolType.EVENT));
    }

    private void press(double x, double y) {
        eventBus.publish(new InputEvent.MousePressed(x, y, CustomMouseButton.PRIMARY, 1, false));
    }

    /** Shift-Press: wird seit dem Vier-Quadranten-Modell (26.11.) NICHT
     *  mehr ausgewertet — gehalten für die Modifier-Regression. */
    private void shiftPress(double x, double y) {
        eventBus.publish(new InputEvent.MousePressed(x, y, CustomMouseButton.PRIMARY, 1, false, true));
    }

    private void drag(double x, double y) {
        eventBus.publish(new InputEvent.MouseDragged(x, y, CustomMouseButton.PRIMARY));
    }

    private void release() {
        eventBus.publish(new InputEvent.MouseReleased(0.0, 0.0, CustomMouseButton.PRIMARY));
    }

    /** Legt ein Event im Modell an (Service-Pfad) und liefert das Record. */
    private Event addEvent(EventSide side) {
        return addEvent(side, EventDirection.INCOMING);
    }

    private Event addEvent(EventSide side, EventDirection direction) {
        eventBus.publish(new ActionEvent.CreateEvent(owner.id(), side, direction, "E", 140.0, 60.0));
        release();
        return addedFacts.stream()
                .map(DiagramEvent.ObjectAdded::object)
                .filter(obj -> obj instanceof Event e && e.direction() == direction)
                .map(obj -> (Event) obj)
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    private Text labelOf(Event event) {
        List<UUID> owned = diagram.findTextIdsOwnedBy(event.id());
        return (Text) diagram.findById(owned.getFirst()).orElseThrow();
    }

    // ---------- Erstellung: nur ohne bestehendes Event ----------

    @Test
    @DisplayName("Klick ins obere rechte Quadrant (ohne IE) -> CreateEventFor(owner, RIGHT, INCOMING, Anker)")
    void pressOnRightHalfCreatesRightIntent() {
        selectEventTool();
        press(110.0, 95.0);

        assertThat(createEventIntents).hasSize(1);
        ActionEvent.CreateEventFor intent = createEventIntents.get(0);
        assertThat(intent.ownerId()).isEqualTo(owner.id());
        assertThat(intent.side()).isEqualTo(EventSide.RIGHT);
        assertThat(intent.direction()).isEqualTo(EventDirection.INCOMING);
        // Label-Anker über dem Startpunkt: Start = (120 + 40, 85 - 40) = (160, 45)
        assertThat(intent.worldX()).isCloseTo(160.0, within(1e-9));
        assertThat(intent.worldY()).isCloseTo(35.0, within(1e-9));
    }

    @Test
    @DisplayName("Klick ins obere linke Quadrant (ohne IE) -> CreateEventFor(owner, LEFT, INCOMING, ...)")
    void pressOnLeftHalfCreatesLeftIntent() {
        selectEventTool();
        press(90.0, 95.0);

        assertThat(createEventIntents).hasSize(1);
        assertThat(createEventIntents.get(0).side()).isEqualTo(EventSide.LEFT);
        assertThat(createEventIntents.get(0).direction()).isEqualTo(EventDirection.INCOMING);
        assertThat(createEventIntents.get(0).worldX()).isCloseTo(40.0, within(1e-9));
    }

    // ---------- Richtung: Vier-Quadranten (26.11.) — oben = IE, unten = OE ----------

    @Test
    @DisplayName("Klick ins untere rechte Quadrant (ohne OE) -> CreateEventFor(owner, RIGHT, OUTGOING, Arm-Mitten-Anker)")
    void lowerRightQuadrantCreatesOutgoingIntent() {
        selectEventTool();
        press(110.0, 100.0); // Mittellinie gehört zur unteren Hälfte

        assertThat(createEventIntents).hasSize(1);
        ActionEvent.CreateEventFor intent = createEventIntents.get(0);
        assertThat(intent.ownerId()).isEqualTo(owner.id());
        assertThat(intent.side()).isEqualTo(EventSide.RIGHT);
        assertThat(intent.direction()).isEqualTo(EventDirection.OUTGOING);
        // Label-Anker: Mitte des äußeren Segments (Knick 110 → Ende 160 ->
        // 135), UNTERHALB des Arms (Knick-Höhe 155 + 10).
        assertThat(intent.worldX()).isCloseTo(135.0, within(1e-9));
        assertThat(intent.worldY()).isCloseTo(165.0, within(1e-9));
    }

    @Test
    @DisplayName("Klick ins untere linke Quadrant -> OE links; die Seitenwahl gilt für beide Richtungen")
    void lowerLeftQuadrantCreatesLeftOutgoingIntent() {
        selectEventTool();
        press(90.0, 110.0);

        assertThat(createEventIntents).hasSize(1);
        assertThat(createEventIntents.get(0).side()).isEqualTo(EventSide.LEFT);
        assertThat(createEventIntents.get(0).direction()).isEqualTo(EventDirection.OUTGOING);
        // Arm-Mitte links: Knick 90 -> Ende 40 -> 65
        assertThat(createEventIntents.get(0).worldX()).isCloseTo(65.0, within(1e-9));
    }

    @Test
    @DisplayName("Koexistenz: Klick unten trotz bestehendem IE -> OUTGOING-Intent")
    void lowerClickWithIncomingCreatesOutgoingIntent() {
        addEvent(EventSide.RIGHT); // hereinkommend, dockt oben an
        selectEventTool();
        press(110.0, 100.0);

        // 0..1 gilt JE RICHTUNG: Das Incoming stört das Outgoing nicht —
        // es entsteht ein NEUES Event, kein EditEventLabel des Incoming.
        assertThat(createEventIntents).hasSize(1);
        assertThat(createEventIntents.get(0).direction()).isEqualTo(EventDirection.OUTGOING);
        assertThat(editLabelIntents).isEmpty();
    }

    @Test
    @DisplayName("Unteres Eigen-Quadrant des OE -> EditEventLabel; horizontal anderes -> Flip (mit Label-Spiegel)")
    void lowerQuadrantEditsOrFlipsOutgoing() {
        Event outgoing = addEvent(EventSide.RIGHT, EventDirection.OUTGOING);
        selectEventTool();

        // Eigenes Quadrant (unten rechts): Label-Inhalt bearbeiten.
        press(110.0, 100.0);
        assertThat(editLabelIntents).extracting(ActionEvent.EditEventLabel::eventId)
                .containsExactly(outgoing.id());
        assertThat(sideRequests).isEmpty();

        // Horizontal anderes Quadrant (unten links): UMZIEHEN — SetEventSide
        // + Label an der R-Achse gespiegelt (y unverändert, auch unterhalb
        // des R).
        Text label = labelOf(outgoing);
        editLabelIntents.clear();
        press(90.0, 100.0);
        assertThat(sideRequests).hasSize(1);
        assertThat(sideRequests.get(0).id()).isEqualTo(outgoing.id());
        assertThat(sideRequests.get(0).side()).isEqualTo(EventSide.LEFT);
        assertThat(editLabelIntents).isEmpty();
        Text mirrored = labelOf(outgoing);
        assertThat(mirrored.x()).isCloseTo(2.0 * 100.0 - label.x(), within(1e-9));
        assertThat(mirrored.y()).isCloseTo(label.y(), within(1e-9));
    }

    @Test
    @DisplayName("Oberes Quadrant adressiert das IE und ignoriert ein bestehendes OE")
    void upperQuadrantIgnoresOutgoing() {
        addEvent(EventSide.RIGHT, EventDirection.OUTGOING);
        selectEventTool();

        // Oben rechts existiert kein IE: Erstellungs-Intent für das
        // Incoming — das bestehende Outgoing löst KEIN EditEventLabel aus.
        press(110.0, 95.0);
        assertThat(editLabelIntents).isEmpty();
        assertThat(createEventIntents).hasSize(1);
        assertThat(createEventIntents.get(0).direction()).isEqualTo(EventDirection.INCOMING);
    }

    @Test
    @DisplayName("Shift wird ignoriert: Richtung kommt allein aus der vertikalen Klickhälfte")
    void shiftNoLongerSelectsDirection() {
        selectEventTool();

        // Shift im OBEREN Quadrant: weiterhin INCOMING — der Modifier ist
        // seit dem Vier-Quadranten-Modell bewusst tot.
        shiftPress(110.0, 95.0);
        assertThat(createEventIntents).hasSize(1);
        assertThat(createEventIntents.get(0).direction()).isEqualTo(EventDirection.INCOMING);

        // Shift im UNTEREN Quadrant: weiterhin OUTGOING.
        shiftPress(90.0, 105.0);
        assertThat(createEventIntents).hasSize(2);
        assertThat(createEventIntents.get(1).direction()).isEqualTo(EventDirection.OUTGOING);
    }

    // ---------- 0..1: eigenes Quadrant = bearbeiten, horizontal anderes = umziehen ----------

    @Test
    @DisplayName("Klick ins eigene Quadrant (oben rechts) -> EditEventLabel (0..1)")
    void pressOnSameHalfEditsLabel() {
        Event event = addEvent(EventSide.RIGHT);
        selectEventTool();

        press(110.0, 95.0);
        assertThat(editLabelIntents).extracting(ActionEvent.EditEventLabel::eventId)
                .containsExactly(event.id());
        assertThat(createEventIntents).isEmpty(); // kein zweites Event
        assertThat(sideRequests).isEmpty();
    }

    @Test
    @DisplayName("Klick ins horizontal andere Quadrant -> SetEventSide + Label an der R-Achse gespiegelt")
    void pressOnOtherHalfFlipsSideAndMirrorsLabel() {
        Event event = addEvent(EventSide.RIGHT);
        Text label = labelOf(event);
        selectEventTool();

        press(90.0, 95.0); // oberes linkes Quadrant, Event sitzt rechts
        assertThat(sideRequests).hasSize(1);
        assertThat(sideRequests.get(0).id()).isEqualTo(event.id());
        assertThat(sideRequests.get(0).side()).isEqualTo(EventSide.LEFT);
        assertThat(editLabelIntents).isEmpty(); // reine Positions-Geste
        assertThat(createEventIntents).isEmpty();

        // Der Service hat umgedockt, die Geste hat das Label GESPIEGELT:
        // Pfeil-Spitze 110 -> 90; Label-Anker 160 -> 2*100 - 160 = 40 —
        // exakt der linke Default-Anker (Spiegel-Symmetrie-Konsistenz),
        // y unverändert (Spiegelung an der VERTIKALEN Achse).
        assertThat(((Event) diagram.findById(event.id()).orElseThrow()).x())
                .isCloseTo(90.0, within(1e-9));
        assertThat(((Event) diagram.findById(event.id()).orElseThrow()).side())
                .isEqualTo(EventSide.LEFT);
        Text movedLabel = labelOf(event);
        assertThat(movedLabel.x()).isCloseTo(40.0, within(1e-9));
        assertThat(movedLabel.y()).isCloseTo(label.y(), within(1e-9));
    }

    @Test
    @DisplayName("Umziehen mit WEIT gezogenem Label: Spiegelung erhält die relative Lage")
    void flipMirrorsDistantLabel() {
        Event event = addEvent(EventSide.RIGHT);
        Text label = labelOf(event);
        // Label weit nach rechts gezogen — Delta-Wanderung ließe es dort
        // zurück; die Spiegelung bringt es symmetrisch nach links.
        eventBus.publish(new ActionEvent.MoveObjectTo(
                diagram.findTextIdsOwnedBy(event.id()).getFirst(), 300.0, label.y()));
        release();
        Text distant = labelOf(event);
        assertThat(distant.x()).isCloseTo(300.0, within(1e-9));

        selectEventTool();
        press(90.0, 95.0); // oberes linkes Quadrant: Flip nach links

        Text mirrored = labelOf(event);
        assertThat(mirrored.x()).isCloseTo(2.0 * 100.0 - 300.0, within(1e-9)); // = -100
        assertThat(mirrored.y()).isCloseTo(label.y(), within(1e-9));
    }

    @Test
    @DisplayName("Klick auf die Label-TEXTBOX eines Events -> EditEventLabel")
    void pressOnLabelTextboxEditsLabel() {
        Event event = addEvent(EventSide.RIGHT);
        Text label = labelOf(event);
        selectEventTool();

        press(label.x(), label.y());
        assertThat(editLabelIntents).extracting(ActionEvent.EditEventLabel::eventId)
                .containsExactly(event.id());
        assertThat(createEventIntents).isEmpty();
        assertThat(sideRequests).isEmpty();
    }

    @Test
    @DisplayName("Klick auf ein bestehendes Event (Pfad) -> EditEventLabel")
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
        addEvent(EventSide.RIGHT);
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
