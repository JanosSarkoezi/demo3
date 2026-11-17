package de.fmc.editor.core;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.Event;
import de.fmc.editor.core.model.EventDirection;
import de.fmc.editor.core.model.EventSide;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.Text;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Event-Objekt auf Service-Ebene (17.11., Kardinalität/Label-Kette 23.11.):
 * Create mit zentraler Owner- und 0..1-Validierung, Event UND Label-Textbox
 * in einem Request (Kette R → E → Text), Seitentausch als reine Positions-
 * geste (Spitze dockt neu ab — das Label bewegt die GESTE), Form-Resize mit
 * Klemmung, transitive Move-Expansion (R → E → Label), Re-Dock bei
 * R-Resize, Lösch-Kaskade als Fixpoint inkl. Undo, Hit-Test, Laden als
 * Restore mit frischem Re-Dock.
 */
class EventModelTest {

    private BusTestSupport bus;
    private Rectangle owner;

    private final List<DiagramEvent.ObjectAdded> addedFacts = new ArrayList<>();
    private final List<DiagramEvent.ObjectResized> resizedFacts = new ArrayList<>();
    private final List<DiagramEvent.ObjectsMoved> movedBatchFacts = new ArrayList<>();
    private final List<DiagramEvent.ObjectsRemoved> removedFacts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        addedFacts.clear();
        resizedFacts.clear();
        movedBatchFacts.clear();
        removedFacts.clear();

        bus = new BusTestSupport();
        bus.bus.subscribe(DiagramEvent.ObjectAdded.class, addedFacts::add);
        bus.bus.subscribe(DiagramEvent.ObjectResized.class, resizedFacts::add);
        bus.bus.subscribe(DiagramEvent.ObjectsMoved.class, movedBatchFacts::add);
        bus.bus.subscribe(DiagramEvent.ObjectsRemoved.class, removedFacts::add);

        owner = bus.addRectangle(100.0, 100.0); // 40x30 -> Oberkante y=85
        bus.closeGesture();
    }

    // ---------- Helfer ----------

    private Event createEvent(EventSide side, String label) {
        return createEvent(side, EventDirection.INCOMING, label);
    }

    private Event createEvent(EventSide side, EventDirection direction, String label) {
        bus.bus.publish(new ActionEvent.CreateEvent(owner.id(), side, direction, label, 140.0, 60.0));
        bus.closeGesture();
        return bus.diagram.allObjectsInZOrder().stream()
                .filter(obj -> obj instanceof Event e && e.direction() == direction)
                .map(obj -> (Event) obj)
                .findFirst()
                .orElseThrow();
    }

    private Event byId(UUID id) {
        return (Event) bus.diagram.findById(id).orElseThrow();
    }

    private Text labelOf(Event event) {
        List<UUID> owned = bus.diagram.findTextIdsOwnedBy(event.id());
        assertThat(owned).as("Event braucht eine Label-Textbox").hasSize(1);
        return (Text) bus.diagram.findById(owned.getFirst()).orElseThrow();
    }

    // ---------- Erzeugung, Validierung, Kardinalität ----------

    @Test
    @DisplayName("CreateEvent erzeugt Event UND Label-Textbox (R → E → Text); Spitze abgeleitet, 40/40")
    void createDerivesTipAndCreatesLabel() {
        Event event = createEvent(EventSide.RIGHT, "Bestellung");
        assertThat(event.ownerId()).isEqualTo(owner.id());
        assertThat(event.horizontalExtent()).isCloseTo(40.0, within(1e-9));
        assertThat(event.verticalRise()).isCloseTo(40.0, within(1e-9));
        // Spitze: x = 100 + 40/4 = 110, y = Oberkante 85
        assertThat(event.x()).isCloseTo(110.0, within(1e-9));
        assertThat(event.y()).isCloseTo(85.0, within(1e-9));
        assertThat(event.layerId()).isEqualTo(owner.layerId());

        // Label-Textbox: owned Text am Event, positioniert am abgeleiteten
        // Anker (Start 160/45 -> Anker 160/35), absolute Position.
        Text label = labelOf(event);
        assertThat(label.ownerId()).isEqualTo(event.id());
        assertThat(label.content()).isEqualTo("Bestellung");
        assertThat(label.x()).isCloseTo(160.0, within(1e-9));
        assertThat(label.y()).isCloseTo(35.0, within(1e-9));
        assertThat(label.width()).isCloseTo(140.0, within(1e-9));
        assertThat(label.height()).isCloseTo(60.0, within(1e-9));
        assertThat(label.anchor()).isNull();

        // Beide ObjectAdded-Fakten im SELBEN Gesten-Eintrag.
        assertThat(addedFacts).hasSize(3); // R + Event + Label
    }

    @Test
    @DisplayName("0..1 JE RICHTUNG: Ein zweites CreateEvent gleicher Richtung bleibt still")
    void cardinalityDefendsSecondCreate() {
        Event first = createEvent(EventSide.RIGHT, "Eins");
        int factsBefore = addedFacts.size();

        bus.bus.publish(new ActionEvent.CreateEvent(owner.id(), EventSide.LEFT,
                EventDirection.INCOMING, "Zwei", 140.0, 60.0));
        bus.closeGesture();

        assertThat(addedFacts).hasSize(factsBefore);
        assertThat(bus.diagram.findEventIdsOwnedBy(owner.id())).containsExactly(first.id());
        assertThat(bus.diagram.findTextIdsOwnedBy(first.id())).hasSize(1);
    }

    @Test
    @DisplayName("Ohne gültiges R bleibt CreateEvent still: kein Fakt")
    void createWithoutRectangleIsSilent() {
        Circle circle = bus.addCircle(300.0, 300.0);
        bus.closeGesture();
        int before = addedFacts.size();

        bus.bus.publish(new ActionEvent.CreateEvent(circle.id(), EventSide.RIGHT,
                EventDirection.INCOMING, "x", 140.0, 60.0));
        bus.bus.publish(new ActionEvent.CreateEvent(UUID.randomUUID(), EventSide.RIGHT,
                EventDirection.INCOMING, "x", 140.0, 60.0));
        bus.closeGesture();

        assertThat(addedFacts).hasSize(before);
    }

    @Test
    @DisplayName("OUTGOING: Fuß dockt an der UNTEREN Kante ab, Label an der Arm-Mitte unterhalb")
    void outgoingDocksBottomLabelBelowArmMiddle() {
        Event event = createEvent(EventSide.RIGHT, EventDirection.OUTGOING, "Versand");
        assertThat(event.direction()).isEqualTo(EventDirection.OUTGOING);
        // Fuß: x = 100 + 40/4 = 110, y = Unterkante 115
        assertThat(event.x()).isCloseTo(110.0, within(1e-9));
        assertThat(event.y()).isCloseTo(115.0, within(1e-9));
        assertThat(event.horizontalExtent()).isCloseTo(40.0, within(1e-9));
        assertThat(event.verticalRise()).isCloseTo(40.0, within(1e-9));

        // Label: Mitte des äußeren Segments (Knick 110 → Ende 160 -> 135),
        // UNTERHALB des Arms (Knick-Höhe 155 + 8 + 2 = 165) — das Ende
        // gehört der Pfeilspitze.
        Text label = labelOf(event);
        assertThat(label.x()).isCloseTo(135.0, within(1e-9));
        assertThat(label.y()).isCloseTo(165.0, within(1e-9));
    }

    @Test
    @DisplayName("Koexistenz: INCOMING und OUTGOING am selben R leben nebeneinander")
    void incomingAndOutgoingCoexist() {
        Event incoming = createEvent(EventSide.RIGHT, EventDirection.INCOMING, "Rein");
        Event outgoing = createEvent(EventSide.RIGHT, EventDirection.OUTGOING, "Raus");

        assertThat(bus.diagram.findEventIdsOwnedBy(owner.id()))
                .containsExactlyInAnyOrder(incoming.id(), outgoing.id());
        // Jedes Event trägt seine EIGENE Label-Textbox (Kette R → E → Text).
        assertThat(bus.diagram.findTextIdsOwnedBy(incoming.id())).hasSize(1);
        assertThat(bus.diagram.findTextIdsOwnedBy(outgoing.id())).hasSize(1);
        assertThat(labelOf(incoming).content()).isEqualTo("Rein");
        assertThat(labelOf(outgoing).content()).isEqualTo("Raus");

        // 0..1 bleibt JE RICHTUNG: ein drittes CreateEvent OUTGOING bleibt still.
        int factsBefore = addedFacts.size();
        bus.bus.publish(new ActionEvent.CreateEvent(owner.id(), EventSide.LEFT,
                EventDirection.OUTGOING, "Dritt", 140.0, 60.0));
        bus.closeGesture();
        assertThat(addedFacts).hasSize(factsBefore);
    }

    // ---------- Seitentausch (Umziehen) ----------

    @Test
    @DisplayName("SetEventSide dockt die Spitze gespiegelt ab; gleiche Seite bleibt still")
    void setEventSideMirrorsTip() {
        Event event = createEvent(EventSide.RIGHT, "E");
        resizedFacts.clear();

        // Gleiche Seite: still (kein Fakten-Rauschen)
        bus.bus.publish(new ActionEvent.SetEventSide(event.id(), EventSide.RIGHT));
        bus.closeGesture();
        assertThat(resizedFacts).isEmpty();

        // Andere Seite: ObjectResized mit vollem Vor-Zustand, gespiegelte Spitze
        bus.bus.publish(new ActionEvent.SetEventSide(event.id(), EventSide.LEFT));
        bus.closeGesture();
        assertThat(resizedFacts).hasSize(1);
        Event flipped = byId(event.id());
        assertThat(flipped.side()).isEqualTo(EventSide.LEFT);
        assertThat(flipped.x()).isCloseTo(90.0, within(1e-9));
        assertThat(flipped.y()).isCloseTo(85.0, within(1e-9));
        // Die gezogene Form bleibt unverändert
        assertThat(flipped.horizontalExtent()).isCloseTo(40.0, within(1e-9));
        assertThat(flipped.verticalRise()).isCloseTo(40.0, within(1e-9));

        // Undo stellt SEITE und Position wieder her (SetEventSide-Inverse)
        bus.bus.publish(new ActionEvent.RequestUndo());
        assertThat(byId(event.id()).side()).isEqualTo(EventSide.RIGHT);
        assertThat(byId(event.id()).x()).isCloseTo(110.0, within(1e-9));

        bus.bus.publish(new ActionEvent.RequestRedo());
        assertThat(byId(event.id()).side()).isEqualTo(EventSide.LEFT);
    }

    @Test
    @DisplayName("Undo eines Flip wendet die Label-Spiegelung nicht doppelt an")
    void flipUndoDoesNotDoubleMoveLabel() {
        Event event = createEvent(EventSide.RIGHT, "E");
        Text label = labelOf(event);
        UUID labelId = bus.diagram.findTextIdsOwnedBy(event.id()).getFirst();
        // Geste wie im EventToolState: SetEventSide + Label an der R-Achse
        // gespiegelt (MoveObjectTo, absolut).
        bus.bus.publish(new ActionEvent.SetEventSide(event.id(), EventSide.LEFT));
        bus.bus.publish(new ActionEvent.MoveObjectTo(
                labelId, 2.0 * owner.x() - label.x(), label.y()));
        bus.closeGesture();
        Text afterFlip = labelOf(event);
        assertThat(afterFlip.x()).isCloseTo(2.0 * owner.x() - label.x(), within(1e-9));

        bus.bus.publish(new ActionEvent.RequestUndo());
        Text afterUndo = labelOf(event);
        assertThat(afterUndo.x()).isCloseTo(label.x(), within(1e-9));
        assertThat(afterUndo.y()).isCloseTo(label.y(), within(1e-9));
        assertThat(byId(event.id()).side()).isEqualTo(EventSide.RIGHT);
    }

    // ---------- Form (Resize) ----------

    @Test
    @DisplayName("ResizeEvent ändert NUR die Längen; Spitze bleibt gedockt; Undo/Redo mechanisch")
    void resizeEventChangesOnlyLengths() {
        Event event = createEvent(EventSide.RIGHT, "E");
        resizedFacts.clear();

        bus.bus.publish(new ActionEvent.ResizeEvent(event.id(), 80.0, 60.0));
        bus.closeGesture();
        assertThat(resizedFacts).hasSize(1);
        Event updated = byId(event.id());
        assertThat(updated.horizontalExtent()).isCloseTo(80.0, within(1e-9));
        assertThat(updated.verticalRise()).isCloseTo(60.0, within(1e-9));
        assertThat(updated.x()).isCloseTo(110.0, within(1e-9));
        assertThat(updated.y()).isCloseTo(85.0, within(1e-9));

        bus.bus.publish(new ActionEvent.RequestUndo());
        assertThat(byId(event.id()).horizontalExtent()).isCloseTo(40.0, within(1e-9));

        bus.bus.publish(new ActionEvent.RequestRedo());
        assertThat(byId(event.id()).horizontalExtent()).isCloseTo(80.0, within(1e-9));
    }

    @Test
    @DisplayName("Unterschreiten der Mindestlänge klemmt auf den Bogen-Radius (20)")
    void resizeClampsToMinimum() {
        Event event = createEvent(EventSide.RIGHT, "E");
        bus.bus.publish(new ActionEvent.ResizeEvent(event.id(), 5.0, 200.0));
        bus.closeGesture();
        assertThat(byId(event.id()).horizontalExtent()).isCloseTo(20.0, within(1e-9));
        assertThat(byId(event.id()).verticalRise()).isCloseTo(200.0, within(1e-9));
    }

    // ---------- Move: transitive Expansion, nie eigene Basis ----------

    @Test
    @DisplayName("findMoveSet: Event-ID wird gefiltert; Basis R nimmt Event UND Label mit")
    void moveSetFiltersEventsAndExpandsTransitively() {
        Event event = createEvent(EventSide.RIGHT, "E");
        UUID labelId = bus.diagram.findTextIdsOwnedBy(event.id()).getFirst();

        // Event allein: leere Move-Menge (Drag = bewusster No-Op)
        assertThat(bus.diagram.findMoveSet(List.of(event.id()))).isEmpty();

        // R als Basis: R + owned Event + (transitiv) dessen Label
        List<UUID> moveSet = bus.diagram.findMoveSet(List.of(owner.id()));
        assertThat(moveSet).containsExactlyInAnyOrder(owner.id(), event.id(), labelId);
    }

    @Test
    @DisplayName("R-Move über MoveObjects zieht Spitze UND Label per Delta mit")
    void moveWithOwnerDragsTipAndLabel() {
        Event event = createEvent(EventSide.RIGHT, "E");
        Text label = labelOf(event);
        List<UUID> moveSet = bus.diagram.findMoveSet(List.of(owner.id()));
        movedBatchFacts.clear();

        bus.bus.publish(new ActionEvent.MoveObjects(moveSet, 10.0, -5.0));
        bus.closeGesture();

        Event moved = byId(event.id());
        assertThat(moved.x()).isCloseTo(120.0, within(1e-9));
        assertThat(moved.y()).isCloseTo(80.0, within(1e-9));
        Text movedLabel = labelOf(event);
        assertThat(movedLabel.x()).isCloseTo(label.x() + 10.0, within(1e-9));
        assertThat(movedLabel.y()).isCloseTo(label.y() - 5.0, within(1e-9));

        // Ein Batch-Fakt mit allen drei Einträgen (sichtbares Mitwandern)
        assertThat(movedBatchFacts).hasSize(1);
        assertThat(movedBatchFacts.get(0).movedObjects())
                .extracting(DiagramEvent.ObjectMoved::id)
                .containsExactlyInAnyOrder(owner.id(), event.id(), movedLabel.id());

        // Undo: mechanisch absolute Rückstellung aller drei
        bus.bus.publish(new ActionEvent.RequestUndo());
        assertThat(byId(event.id()).x()).isCloseTo(110.0, within(1e-9));
        assertThat(labelOf(event).x()).isCloseTo(label.x(), within(1e-9));
    }

    @Test
    @DisplayName("R-Move erhält die RICHTUNG: OUTGOING kippt nicht auf INCOMING (Regression)")
    void moveWithOwnerPreservesDirection() {
        // User-Befund 26.11.: Ausgehendes Event am R erzeugen, R bewegen —
        // der Pfeil sprang nach oben und wurde eingehend, weil moved() das
        // Event über den Kompatibilitäts-Konstruktor ohne direction neu
        // baute (Default INCOMING). Der Renderer leitet die Form aus
        // Owner + Seite + RICHTUNG ab — das Modell kippte also sichtbar um.
        Event event = createEvent(EventSide.RIGHT, EventDirection.OUTGOING, "Versand");
        Text label = labelOf(event);
        List<UUID> moveSet = bus.diagram.findMoveSet(List.of(owner.id()));

        bus.bus.publish(new ActionEvent.MoveObjects(moveSet, 10.0, -5.0));
        bus.closeGesture();

        Event moved = byId(event.id());
        assertThat(moved.direction()).isEqualTo(EventDirection.OUTGOING);
        // Fuß bleibt an der (mitgewanderten) UNTERKANTE: 115 - 5
        assertThat(moved.x()).isCloseTo(120.0, within(1e-9));
        assertThat(moved.y()).isCloseTo(110.0, within(1e-9));
        // Das Label wandert mit dem Delta — an seiner relativen Lage zum Arm
        Text movedLabel = labelOf(event);
        assertThat(movedLabel.x()).isCloseTo(label.x() + 10.0, within(1e-9));
        assertThat(movedLabel.y()).isCloseTo(label.y() - 5.0, within(1e-9));

        // Undo/Redo läuft über den mechanischen Restore-Pfad (MoveObjectTo)
        // durch dieselbe Kopier-Hilfe — die Richtung muss auch dort überleben.
        bus.bus.publish(new ActionEvent.RequestUndo());
        assertThat(byId(event.id()).direction()).isEqualTo(EventDirection.OUTGOING);
        assertThat(byId(event.id()).y()).isCloseTo(115.0, within(1e-9));

        bus.bus.publish(new ActionEvent.RequestRedo());
        assertThat(byId(event.id()).direction()).isEqualTo(EventDirection.OUTGOING);
        assertThat(byId(event.id()).y()).isCloseTo(110.0, within(1e-9));
    }

    // ---------- R-Resize = Re-Dock ----------

    @Test
    @DisplayName("R-Resize dockt die Event-Spitze neu ab (¼ der NEUEN Breite), gezogene Form bleibt")
    void resizeOwnerRedocksTip() {
        Event event = createEvent(EventSide.RIGHT, "E");
        bus.bus.publish(new ActionEvent.ResizeEvent(event.id(), 80.0, 60.0));
        bus.closeGesture();
        movedBatchFacts.clear();

        bus.bus.publish(new ActionEvent.ResizeBox(owner.id(), 80.0, 30.0));
        bus.closeGesture();

        Event redocked = byId(event.id());
        // Neue Spitze: 100 + 80/4 = 120, Oberkante unverändert 85
        assertThat(redocked.x()).isCloseTo(120.0, within(1e-9));
        assertThat(redocked.y()).isCloseTo(85.0, within(1e-9));
        // Die gezogene Form bleibt exakt erhalten (Resize-Problem gelöst)
        assertThat(redocked.horizontalExtent()).isCloseTo(80.0, within(1e-9));
        assertThat(redocked.verticalRise()).isCloseTo(60.0, within(1e-9));
        // SICHTBARES Re-Dock: Eintrag im ObjectsMoved-Batch
        assertThat(movedBatchFacts).hasSize(1);
        assertThat(movedBatchFacts.get(0).movedObjects())
                .extracting(DiagramEvent.ObjectMoved::id)
                .contains(event.id());

        bus.bus.publish(new ActionEvent.RequestUndo());
        assertThat(byId(event.id()).x()).isCloseTo(110.0, within(1e-9));
    }

    // ---------- Lösch-Kaskade (Fixpoint) & Undo ----------

    @Test
    @DisplayName("R löschen kaskadiert Event UND Label mit (Fixpoint, sichtbar); Undo stellt alles her")
    void deleteOwnerCascadesWholeChain() {
        Event event = createEvent(EventSide.RIGHT, "E");
        UUID labelId = bus.diagram.findTextIdsOwnedBy(event.id()).getFirst();
        removedFacts.clear();

        bus.bus.publish(new ActionEvent.DeleteObjects(List.of(owner.id())));
        bus.closeGesture();

        assertThat(bus.diagram.findById(event.id())).isEmpty();
        assertThat(bus.diagram.findById(labelId)).isEmpty();
        assertThat(removedFacts).hasSize(1);
        assertThat(removedFacts.get(0).objects())
                .extracting(obj -> obj.id())
                .containsExactlyInAnyOrder(owner.id(), event.id(), labelId);

        bus.bus.publish(new ActionEvent.RequestUndo());
        assertThat(bus.diagram.findById(owner.id())).isPresent();
        assertThat(bus.diagram.findById(event.id())).isPresent();
        assertThat(bus.diagram.findById(labelId)).isPresent();
        assertThat(byId(event.id()).x()).isCloseTo(110.0, within(1e-9));
        assertThat(((Text) bus.diagram.findById(labelId).orElseThrow()).content()).isEqualTo("E");
    }

    @Test
    @DisplayName("Event allein löschen kaskadiert nur sein Label; das R lebt")
    void deleteEventCascadesOnlyItsLabel() {
        Event event = createEvent(EventSide.RIGHT, "E");
        UUID labelId = bus.diagram.findTextIdsOwnedBy(event.id()).getFirst();

        bus.bus.publish(new ActionEvent.DeleteObjects(List.of(event.id())));
        bus.closeGesture();

        assertThat(bus.diagram.findById(event.id())).isEmpty();
        assertThat(bus.diagram.findById(labelId)).isEmpty();
        assertThat(bus.diagram.findById(owner.id())).isPresent();
    }

    // ---------- Hit-Test ----------

    @Test
    @DisplayName("findTopmostAt trifft das Event auf der Kette, das Label als Text-Objekt, das R im Körper")
    void hitTestPrefersEventChain() {
        Event event = createEvent(EventSide.RIGHT, "Bestellung");
        Text label = labelOf(event);

        // Auf der Spitze (unterhalb der Label-Box): Event
        assertThat(bus.diagram.findTopmostAt(110.0, 85.0)).contains(event);
        // Senkrechter Abschnitt UNTERHALB der Label-Box: Event
        assertThat(bus.diagram.findTopmostAt(110.0, 75.0)).contains(event);
        // Senkrechter Abschnitt INNERHALB der Label-Box: das TEXT-Objekt
        // (z-oben — dokumentierte Überlappung: Beide Ziele enden im Event-
        // Werkzeug im Label-Editor; im Auswahl-Werkzeug selektiert der
        // Klick den bewegbaren Text).
        assertThat(bus.diagram.findTopmostAt(110.0, 55.0)).contains(label);
        // Auf der Label-Box-Mitte: das TEXT-Objekt (eigenständig, bewegbar)
        assertThat(bus.diagram.findTopmostAt(160.0, 35.0)).contains(label);
        // Im R-Körper: das R
        assertThat(bus.diagram.findTopmostAt(100.0, 100.0)).contains(owner);
        // Deutlich daneben: nichts
        assertThat(bus.diagram.findTopmostAt(250.0, 90.0)).isEmpty();
    }

    // ---------- Undo der Erzeugung ----------

    @Test
    @DisplayName("Undo der Erzeugung entfernt Event UND Label, Redo bringt beide zurück")
    void undoCreateRemovesEventAndLabel() {
        Event event = createEvent(EventSide.RIGHT, "E");
        UUID labelId = bus.diagram.findTextIdsOwnedBy(event.id()).getFirst();

        bus.bus.publish(new ActionEvent.RequestUndo());
        assertThat(bus.diagram.findById(event.id())).isEmpty();
        assertThat(bus.diagram.findById(labelId)).isEmpty();

        bus.bus.publish(new ActionEvent.RequestRedo());
        assertThat(bus.diagram.findById(event.id())).isPresent();
        assertThat(bus.diagram.findById(labelId)).isPresent();
        assertThat(byId(event.id()).x()).isCloseTo(110.0, within(1e-9));
    }

    // ---------- Verbindungs-Validierung ----------

    @Test
    @DisplayName("Events sind keine Verbindungsendpunkte: CreateConnection wird abgelehnt")
    void eventsAreNotConnectable() {
        Event event = createEvent(EventSide.RIGHT, "E");
        Circle circle = bus.addCircle(300.0, 300.0);
        bus.closeGesture();

        List<DiagramEvent.ConnectionRejected> rejections = new ArrayList<>();
        bus.bus.subscribe(DiagramEvent.ConnectionRejected.class, rejections::add);

        bus.bus.publish(new ActionEvent.CreateConnection(event.id(), circle.id(), List.of()));
        bus.bus.publish(new ActionEvent.CreateConnection(circle.id(), event.id(), List.of()));
        bus.closeGesture();

        assertThat(rejections).hasSize(2);
    }

    // ---------- Laden (Restore mit frischem Re-Dock) ----------

    @Test
    @DisplayName("LoadDiagram dockt Event-Spitzen frisch ab — randeditierte Positionen self-healen")
    void loadRedocksEventTips() {
        Event event = createEvent(EventSide.RIGHT, "Bestellung");

        // Event mit verbogener gespeicherter Spitze laden (simuliert eine
        // hand-editierte Datei): x/y wird ignoriert, aus Owner + Seite neu
        // abgeleitet; das Label reist als normaler Text mit.
        Event doctored = new Event(event.id(), event.ownerId(), event.side(),
                66.0, 77.0, 999.0, 999.0, event.layerId());
        Text label = labelOf(event);
        Text savedLabel = new Text(label.id(), label.content(), label.x(), label.y(),
                label.width(), label.height(), null, label.ownerId(), label.layerId());
        Rectangle freshOwner = new Rectangle(owner.id(), 100.0, 100.0, 40.0, 30.0, owner.layerId());
        bus.bus.publish(new ActionEvent.LoadDiagram(
                List.of(freshOwner, doctored, savedLabel), List.of()));

        Event loaded = byId(event.id());
        assertThat(loaded.x()).isCloseTo(110.0, within(1e-9));
        assertThat(loaded.y()).isCloseTo(85.0, within(1e-9));
        assertThat(loaded.horizontalExtent()).isCloseTo(66.0, within(1e-9));
        assertThat(labelOf(event).content()).isEqualTo("Bestellung");
    }

    // ---------- Band-Auswahl ----------

    @Test
    @DisplayName("Gummiband trifft Event über seine Ketten-Bounds, das Label über seine Box")
    void bandSelectHitsEventAndLabel() {
        Event event = createEvent(EventSide.RIGHT, "Bestellung");
        Text label = labelOf(event);
        // Kette: x 110..160, y 45..85; Label-Box um 160/35 (140x60)
        assertThat(bus.diagram.findObjectsInBounds(100.0, 40.0, 170.0, 90.0))
                .extracting(obj -> obj.id())
                .contains(owner.id(), event.id(), label.id());
        // Weit abseits kein Treffer
        assertThat(bus.diagram.findObjectsInBounds(500.0, 500.0, 600.0, 600.0)).isEmpty();
    }
}
