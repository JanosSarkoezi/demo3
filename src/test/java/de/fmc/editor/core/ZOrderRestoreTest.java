package de.fmc.editor.core;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifiziert die z-Ordnungs-Wiederherstellung (User-Idee: HashMap für
 * Lookup + explizite zOrder-Liste): Undo fügt gelöschte Objekte an ihrer
 * ORIGINAL-Position wieder ein statt oben aufzulegen. Die z-Indizes werden
 * vor der ersten Entfernung einer Geste gesnapshottet; das aufsteigende
 * Wiedereinfügen stellt die exakte Reihenfolge her.
 */
class ZOrderRestoreTest {

    private InteractionEventBus bus;
    private DiagramService diagram;
    private final List<DiagramEvent.ConnectionEstablished> established = new ArrayList<>();
    private UUID defaultLayerId;

    @BeforeEach
    void setUp() {
        established.clear();
        defaultLayerId = UUID.randomUUID();

        diagram = new DiagramService();
        SelectionService selectionService = new SelectionService();
        UndoService undoService = new UndoService();
        bus = new InteractionEventBus(diagram,
                new CameraInfo(1.0, 0.0, 0.0, defaultLayerId),
                new GridSpec(10.0), selectionService);
        diagram.attachTo(bus);
        selectionService.attachTo(bus);
        undoService.attachTo(bus);

        bus.subscribe(DiagramEvent.ConnectionEstablished.class, established::add);
    }

    private Circle addCircle(double x, double y) {
        Circle circle = new Circle(UUID.randomUUID(), x, y, 20.0, defaultLayerId);
        bus.publish(new ActionEvent.AddElement(circle));
        closeGesture();
        return circle;
    }

    private Rectangle addRectangle(double x, double y) {
        Rectangle rect = new Rectangle(UUID.randomUUID(), x, y, 40.0, 30.0, defaultLayerId);
        bus.publish(new ActionEvent.AddElement(rect));
        closeGesture();
        return rect;
    }

    /** Neutrale Gesten-Grenze: IdleToolState ignoriert das Release, der
     *  UndoService schließt den laufenden Eintrag. */
    private void closeGesture() {
        bus.publish(new InputEvent.MouseReleased(0.0, 0.0, CustomMouseButton.PRIMARY));
    }

    @Test
    @DisplayName("Undo fügt ein mittig gelöschtes Objekt an seiner Original-Position wieder ein")
    void undoRestoresOriginalZPosition() {
        Circle bottom = addCircle(100.0, 100.0);
        Rectangle middle = addRectangle(100.0, 100.0); // überlappt den Kreis
        Circle top = addCircle(100.0, 100.0);          // überlappt beides
        assertThat(diagram.zOrderIds()).containsExactly(bottom.id(), middle.id(), top.id());

        // Mittleres Objekt löschen -> Reihenfolge [bottom, top]
        bus.publish(new ActionEvent.DeleteObjects(List.of(middle.id())));
        assertThat(diagram.zOrderIds()).containsExactly(bottom.id(), top.id());

        // Undo: middle kehrt ZWISCHEN bottom und top zurück, nicht oben
        bus.publish(new ActionEvent.RequestUndo());
        assertThat(diagram.zOrderIds()).containsExactly(bottom.id(), middle.id(), top.id());

        // Hit-Test bestätigt die Ordnung: top liegt oben, nicht das restaurierte middle
        assertThat(diagram.findTopmostAt(100.0, 100.0)).hasValue(top);

        // Redo entfernt middle wieder
        bus.publish(new ActionEvent.RequestRedo());
        assertThat(diagram.zOrderIds()).containsExactly(bottom.id(), top.id());
    }

    @Test
    @DisplayName("Kaskaden-Restore: Objekt UND Waypoint kehren an ihren Original-Positionen zurück")
    void cascadeRestorePreservesZOrder() {
        Circle circle = addCircle(100.0, 100.0);
        Rectangle rect = addRectangle(300.0, 100.0);
        Waypoint waypoint = new Waypoint(UUID.randomUUID(), 200.0, 150.0, defaultLayerId);
        bus.publish(new ActionEvent.CreateConnection(circle.id(), rect.id(), List.of(waypoint)));
        closeGesture();
        // Waypoints werden beim Verbindungsaufbau oben angelegt:
        assertThat(diagram.zOrderIds())
                .containsExactly(circle.id(), rect.id(), waypoint.id());

        // Kreis löschen kaskadiert Kreis + Waypoint weg
        bus.publish(new ActionEvent.DeleteObjects(List.of(circle.id())));
        assertThat(diagram.zOrderIds()).containsExactly(rect.id());

        // Undo: Kreis bei Index 0, Waypoint bei Index 2 — exakt zurück
        bus.publish(new ActionEvent.RequestUndo());
        assertThat(diagram.zOrderIds())
                .containsExactly(circle.id(), rect.id(), waypoint.id());
        assertThat(diagram.findConnectionById(established.getFirst().connection().id())).isPresent();
    }

    @Test
    @DisplayName("Mehrfach-Lösch-Geste: z-Indizes werden vor der ersten Entfernung gesnapshottet")
    void multiDeleteRestoreKeepsExactOrder() {
        Circle a = addCircle(100.0, 100.0);
        Rectangle b = addRectangle(300.0, 100.0);
        Circle c = addCircle(500.0, 100.0);
        Circle d = addCircle(700.0, 100.0);
        assertThat(diagram.zOrderIds()).containsExactly(a.id(), b.id(), c.id(), d.id());

        // a und c in EINER Geste löschen (Indices 0 und 2)
        bus.publish(new ActionEvent.DeleteObjects(List.of(a.id(), c.id())));
        assertThat(diagram.zOrderIds()).containsExactly(b.id(), d.id());

        // Undo: a bei 0, c bei 2 — ohne Snapshot wären die Indizes verschoben
        bus.publish(new ActionEvent.RequestUndo());
        assertThat(diagram.zOrderIds()).containsExactly(a.id(), b.id(), c.id(), d.id());
    }
}
