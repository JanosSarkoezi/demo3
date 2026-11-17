package de.fmc.editor.core;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.Connection;
import de.fmc.editor.core.model.Event;
import de.fmc.editor.core.model.EventGeometry;
import de.fmc.editor.core.model.EventSide;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.Waypoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Event-Objekt auf Service-Ebene (17.11.): Create mit zentraler Owner-
 * Validierung und abgeleiteter Spitzen-Position, Label-Änderung, Form-
 * Resize mit Klemmung, Move-mit über die Move-Expansion (Events sind nie
 * selbst Basis), Re-Dock bei R-Resize, Lösch-Kaskade inkl. Undo, Hit-Test
 * über die Ketten-Nähe und Laden als Restore mit frischem Re-Dock.
 */
class EventModelTest {

    private BusTestSupport bus;
    private Rectangle owner;

    private final List<DiagramEvent.ObjectAdded> addedFacts = new ArrayList<>();
    private final List<DiagramEvent.ObjectTextChanged> textChangedFacts = new ArrayList<>();
    private final List<DiagramEvent.ObjectResized> resizedFacts = new ArrayList<>();
    private final List<DiagramEvent.ObjectsMoved> movedBatchFacts = new ArrayList<>();
    private final List<DiagramEvent.ObjectsRemoved> removedFacts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        addedFacts.clear();
        textChangedFacts.clear();
        resizedFacts.clear();
        movedBatchFacts.clear();
        removedFacts.clear();

        bus = new BusTestSupport();
        bus.bus.subscribe(DiagramEvent.ObjectAdded.class, addedFacts::add);
        bus.bus.subscribe(DiagramEvent.ObjectTextChanged.class, textChangedFacts::add);
        bus.bus.subscribe(DiagramEvent.ObjectResized.class, resizedFacts::add);
        bus.bus.subscribe(DiagramEvent.ObjectsMoved.class, movedBatchFacts::add);
        bus.bus.subscribe(DiagramEvent.ObjectsRemoved.class, removedFacts::add);

        owner = bus.addRectangle(100.0, 100.0); // 40x30 -> Oberkante y=85
        bus.closeGesture();
    }

    // ---------- Helfer ----------

    private Event createEvent(EventSide side, String label) {
        bus.bus.publish(new ActionEvent.CreateEvent(owner.id(), side, label));
        bus.closeGesture();
        assertThat(addedFacts).isNotEmpty();
        return (Event) addedFacts.get(addedFacts.size() - 1).object();
    }

    private Event created() {
        return (Event) addedFacts.get(addedFacts.size() - 1).object();
    }

    private Event byId(UUID id) {
        return (Event) bus.diagram.findById(id).orElseThrow();
    }

    // ---------- Erzeugung & Validierung ----------

    @Test
    @DisplayName("CreateEvent an R: Spitze abgeleitet (¼ vom Seitenrand), Standardmaß 40/40, Owner-Ebene")
    void createDerivesTipAndDefaults() {
        Event event = createEvent(EventSide.RIGHT, "Bestellung");
        assertThat(event.ownerId()).isEqualTo(owner.id());
        assertThat(event.label()).isEqualTo("Bestellung");
        assertThat(event.horizontalExtent()).isCloseTo(40.0, within(1e-9));
        assertThat(event.verticalRise()).isCloseTo(40.0, within(1e-9));
        // Spitze: x = 100 + 40/4 = 110, y = Oberkante 85
        assertThat(event.x()).isCloseTo(110.0, within(1e-9));
        assertThat(event.y()).isCloseTo(85.0, within(1e-9));
        assertThat(event.layerId()).isEqualTo(owner.layerId());
    }

    @Test
    @DisplayName("Linke Seite: Spitze bei x - width/4 — exakt gespiegelt")
    void leftSideMirrorsTip() {
        Event event = createEvent(EventSide.LEFT, "");
        assertThat(event.x()).isCloseTo(90.0, within(1e-9));
        assertThat(event.y()).isCloseTo(85.0, within(1e-9));
    }

    @Test
    @DisplayName("Ohne gültiges R bleibt CreateEvent still: kein Fakt")
    void createWithoutRectangleIsSilent() {
        Circle circle = bus.addCircle(300.0, 300.0);
        bus.closeGesture();
        int before = addedFacts.size();

        bus.bus.publish(new ActionEvent.CreateEvent(circle.id(), EventSide.RIGHT, "x"));
        bus.bus.publish(new ActionEvent.CreateEvent(UUID.randomUUID(), EventSide.RIGHT, "x"));
        bus.closeGesture();

        assertThat(addedFacts).hasSize(before);
    }

    // ---------- Label ----------

    @Test
    @DisplayName("SetEventLabel -> ObjectTextChanged mit Vor/Nach; Undo stellt her")
    void labelChangeWithUndo() {
        Event event = createEvent(EventSide.RIGHT, "Alt");
        textChangedFacts.clear();

        bus.bus.publish(new ActionEvent.SetEventLabel(event.id(), "Neu"));
        bus.closeGesture();
        assertThat(textChangedFacts).hasSize(1);
        assertThat(byId(event.id()).label()).isEqualTo("Neu");

        bus.bus.publish(new ActionEvent.RequestUndo());
        assertThat(byId(event.id()).label()).isEqualTo("Alt");

        bus.bus.publish(new ActionEvent.RequestRedo());
        assertThat(byId(event.id()).label()).isEqualTo("Neu");
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
        assertThat(byId(event.id()).verticalRise()).isCloseTo(40.0, within(1e-9));

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

    // ---------- Move: Expansion, nie eigene Basis ----------

    @Test
    @DisplayName("findMoveSet: Event-ID wird gefiltert; Basis R nimmt das Event mit")
    void moveSetFiltersEventsAndExpandsOwned() {
        Event event = createEvent(EventSide.RIGHT, "E");

        // Event allein: leere Move-Menge (Drag = bewusster No-Op)
        assertThat(bus.diagram.findMoveSet(List.of(event.id()))).isEmpty();

        // R als Basis: R + owned Event
        List<UUID> moveSet = bus.diagram.findMoveSet(List.of(owner.id()));
        assertThat(moveSet).containsExactlyInAnyOrder(owner.id(), event.id());
    }

    @Test
    @DisplayName("R-Move über MoveObjects nimmt die Event-Spitze per Delta mit")
    void moveWithOwnerDragsTip() {
        Event event = createEvent(EventSide.RIGHT, "E");
        List<UUID> moveSet = bus.diagram.findMoveSet(List.of(owner.id()));
        movedBatchFacts.clear();

        bus.bus.publish(new ActionEvent.MoveObjects(moveSet, 10.0, -5.0));
        bus.closeGesture();

        Event moved = byId(event.id());
        assertThat(moved.x()).isCloseTo(120.0, within(1e-9));
        assertThat(moved.y()).isCloseTo(80.0, within(1e-9));
        // Ein Batch-Fakt mit beiden Einträgen (sichtbares Mitwandern)
        assertThat(movedBatchFacts).hasSize(1);
        assertThat(movedBatchFacts.get(0).movedObjects())
                .extracting(DiagramEvent.ObjectMoved::id)
                .containsExactlyInAnyOrder(owner.id(), event.id());

        // Undo: mechanisch absolute Rückstellung, Spitze wieder an alter Kante
        bus.bus.publish(new ActionEvent.RequestUndo());
        assertThat(byId(event.id()).x()).isCloseTo(110.0, within(1e-9));
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

        // Undo des R-Resize stellt die alte Spitze wieder her
        bus.bus.publish(new ActionEvent.RequestUndo());
        assertThat(byId(event.id()).x()).isCloseTo(110.0, within(1e-9));
    }

    // ---------- Lösch-Kaskade & Undo ----------

    @Test
    @DisplayName("R löschen kaskadiert das Event mit (sichtbar); Undo stellt beides her")
    void deleteOwnerCascadesEvent() {
        Event event = createEvent(EventSide.RIGHT, "E");
        removedFacts.clear();

        bus.bus.publish(new ActionEvent.DeleteObjects(List.of(owner.id())));
        bus.closeGesture();

        assertThat(bus.diagram.findById(event.id())).isEmpty();
        assertThat(removedFacts).hasSize(1);
        assertThat(removedFacts.get(0).objects())
                .extracting(obj -> obj.id())
                .containsExactlyInAnyOrder(owner.id(), event.id());

        bus.bus.publish(new ActionEvent.RequestUndo());
        assertThat(bus.diagram.findById(owner.id())).isPresent();
        Event restored = byId(event.id());
        assertThat(restored.label()).isEqualTo("E");
        assertThat(restored.x()).isCloseTo(110.0, within(1e-9));
    }

    @Test
    @DisplayName("Event allein löschen lässt das R leben")
    void deleteEventAlone() {
        Event event = createEvent(EventSide.RIGHT, "E");
        bus.bus.publish(new ActionEvent.DeleteObjects(List.of(event.id())));
        bus.closeGesture();

        assertThat(bus.diagram.findById(event.id())).isEmpty();
        assertThat(bus.diagram.findById(owner.id())).isPresent();
    }

    // ---------- Hit-Test ----------

    @Test
    @DisplayName("findTopmostAt trifft das Event auf Kette und Label, das R im Körper")
    void hitTestPrefersEventChain() {
        Event event = createEvent(EventSide.RIGHT, "Bestellung");

        // Auf der Spitze / dem senkrechten Stück: Event
        assertThat(bus.diagram.findTopmostAt(110.0, 85.0)).contains(event);
        assertThat(bus.diagram.findTopmostAt(110.0, 60.0)).contains(event);
        // Label-Box über dem Startpunkt (Start: 160/45, Text darüber)
        assertThat(bus.diagram.findTopmostAt(160.0, 33.0)).contains(event);
        // Im R-Körper: das R
        assertThat(bus.diagram.findTopmostAt(100.0, 100.0)).contains(owner);
        // Deutlich daneben: nichts
        assertThat(bus.diagram.findTopmostAt(250.0, 45.0)).isEmpty();
    }

    // ---------- Undo der Erzeugung ----------

    @Test
    @DisplayName("Undo der Erzeugung entfernt das Event, Redo bringt es zurück")
    void undoCreateRemovesEvent() {
        Event event = createEvent(EventSide.RIGHT, "E");

        bus.bus.publish(new ActionEvent.RequestUndo());
        assertThat(bus.diagram.findById(event.id())).isEmpty();

        bus.bus.publish(new ActionEvent.RequestRedo());
        assertThat(bus.diagram.findById(event.id())).isPresent();
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
        // abgeleitet.
        Event doctored = new Event(event.id(), event.ownerId(), event.side(), event.label(),
                66.0, 77.0, 999.0, 999.0, event.layerId());
        Rectangle freshOwner = new Rectangle(owner.id(), 100.0, 100.0, 40.0, 30.0, owner.layerId());
        bus.bus.publish(new ActionEvent.LoadDiagram(
                List.of(freshOwner, doctored), List.of()));

        Event loaded = byId(event.id());
        assertThat(loaded.x()).isCloseTo(110.0, within(1e-9));
        assertThat(loaded.y()).isCloseTo(85.0, within(1e-9));
        assertThat(loaded.horizontalExtent()).isCloseTo(66.0, within(1e-9));
    }

    // ---------- Band-Auswahl ----------

    @Test
    @DisplayName("Gummiband trifft das Event über seine Kette+Label-Bounds")
    void bandSelectHitsEvent() {
        Event event = createEvent(EventSide.RIGHT, "Bestellung");
        // Kette: x 110..160 (Start = Seitenrand 120 + 40), y 45..85
        assertThat(bus.diagram.findObjectsInBounds(100.0, 40.0, 170.0, 90.0))
                .extracting(obj -> obj.id())
                .contains(owner.id(), event.id());
        // Weit abseits kein Treffer
        assertThat(bus.diagram.findObjectsInBounds(500.0, 500.0, 600.0, 600.0)).isEmpty();
    }
}
