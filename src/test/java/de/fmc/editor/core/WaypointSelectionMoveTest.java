package de.fmc.editor.core;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.Connection;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.ToolType;
import de.fmc.editor.core.model.Waypoint;
import de.fmc.editor.core.service.DiagramService;
import de.fmc.editor.core.state.impl.IdleToolState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifiziert die Waypoint-Semantik (Entscheidung 30.10.): Ws sind
 * Routenpunkte ihrer Verbindung, keine eigenständigen Objekte. Daraus:
 * - Klick/Gummiband erfassen Ws (Umformen der Route durch direkten Griff),
 * - Ws VOLLSELEKTierter Verbindungen wandern mit der Auswahl mit
 *   (beide Endpunkte selektiert — die Route gehört zur Verbindung),
 * - teilselektierte Verbindungen bleiben verankert,
 * - der Verbindungsaufbau behandelt Ws wie Leerraum (neuer Kettenpunkt),
 * - Doppelklick auf ein W selektiert nur (kein Resize-Modus).
 */
class WaypointSelectionMoveTest {

    private BusTestSupport support;
    private InteractionEventBus bus;
    private DiagramService diagram;
    private final List<DiagramEvent.SelectionChanged> selectionFacts = new ArrayList<>();
    private final List<DiagramEvent.ConnectionEstablished> established = new ArrayList<>();
    private UUID defaultLayerId;

    /** Szenario: K und R, verbunden über zwei Ws (W1 über K, W2 über R). */
    private Circle k;
    private Rectangle r;
    private Waypoint w1;
    private Waypoint w2;

    @BeforeEach
    void setUp() {
        support = new BusTestSupport();
        bus = support.bus;
        diagram = support.diagram;
        defaultLayerId = support.defaultLayerId;
        bus.subscribe(DiagramEvent.SelectionChanged.class, selectionFacts::add);
        bus.subscribe(DiagramEvent.ConnectionEstablished.class, established::add);

        k = support.addCircle(100.0, 100.0);          // r=20 -> y in [80,120]
        r = support.addRectangle(300.0, 100.0);       // 40x30 -> y in [85,115]
        w1 = new Waypoint(UUID.randomUUID(), 100.0, 60.0, defaultLayerId);
        w2 = new Waypoint(UUID.randomUUID(), 300.0, 60.0, defaultLayerId);
        bus.publish(new ActionEvent.CreateConnection(k.id(), r.id(), List.of(w1, w2)));
    }

    private void click(double x, double y, boolean ctrl) {
        bus.publish(new InputEvent.MousePressed(x, y, CustomMouseButton.PRIMARY, 1, ctrl));
        bus.publish(new InputEvent.MouseReleased(x, y, CustomMouseButton.PRIMARY));
    }

    /** Move-Geste: Plain-Press auf (x, y) und Drag um insgesamt 40 nach rechts. */
    private void dragRightBy40(double x, double y) {
        bus.publish(new InputEvent.MousePressed(x, y, CustomMouseButton.PRIMARY, 1));
        bus.publish(new InputEvent.MouseDragged(x + 20.0, y, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseDragged(x + 40.0, y, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseReleased(x + 40.0, y, CustomMouseButton.PRIMARY));
    }

    @Test
    @DisplayName("Klick auf ein Waypoint selektiert es — volle Klick-Ziele")
    void clickOnWaypointSelectsIt() {
        click(100.0, 60.0, false);

        assertThat(selectionFacts.getLast().selectedIds()).containsExactly(w1.id());
    }

    @Test
    @DisplayName("Gummiband erfasst Objekte UND Waypoints — in z-Ordnung")
    void bandCatchesWaypointsAndObjects() {
        // Ctrl-Press auf leerer Fläche, Drag über die Schwelle, Release
        bus.publish(new InputEvent.MousePressed(50.0, 30.0, CustomMouseButton.PRIMARY, 1, true));
        bus.publish(new InputEvent.MouseDragged(350.0, 130.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseReleased(350.0, 130.0, CustomMouseButton.PRIMARY));

        assertThat(selectionFacts.getLast().selectedIds())
                .containsExactly(k.id(), r.id(), w1.id(), w2.id());
    }

    @Test
    @DisplayName("Beide Endpunkte selektiert: die Ws wandern mit der Auswahl mit")
    void fullySelectedConnectionMovesItsWaypoints() {
        click(100.0, 100.0, true);   // Ctrl: K rein
        click(300.0, 100.0, true);   // Ctrl: R dazu -> [K, R]

        dragRightBy40(100.0, 100.0); // Plain-Press auf K (selektiert) -> Gruppen-Move

        assertThat(diagram.findById(k.id()).orElseThrow().x()).isEqualTo(140.0);
        assertThat(diagram.findById(r.id()).orElseThrow().x()).isEqualTo(340.0);
        assertThat(diagram.findById(w1.id()).orElseThrow().x()).isEqualTo(140.0);
        assertThat(diagram.findById(w2.id()).orElseThrow().x()).isEqualTo(340.0);
    }

    @Test
    @DisplayName("Nur ein Endpunkt selektiert: die Ws bleiben verankert")
    void partiallySelectedConnectionLeavesWaypointsAnchored() {
        click(100.0, 100.0, true);   // Ctrl: nur K

        dragRightBy40(100.0, 100.0);

        assertThat(diagram.findById(k.id()).orElseThrow().x()).isEqualTo(140.0);
        assertThat(diagram.findById(r.id()).orElseThrow().x()).isEqualTo(300.0);
        assertThat(diagram.findById(w1.id()).orElseThrow().x()).isEqualTo(100.0);
        assertThat(diagram.findById(w2.id()).orElseThrow().x()).isEqualTo(300.0);
    }

    @Test
    @DisplayName("Direkt selektiertes W bewegt sich allein (Umformen der Route)")
    void explicitlySelectedWaypointMovesAlone() {
        click(100.0, 60.0, false);   // W1 exklusiv selektiert

        dragRightBy40(100.0, 60.0);  // Plain-Press auf W1

        assertThat(diagram.findById(w1.id()).orElseThrow().x()).isEqualTo(140.0);
        assertThat(diagram.findById(k.id()).orElseThrow().x()).isEqualTo(100.0);
        assertThat(diagram.findById(r.id()).orElseThrow().x()).isEqualTo(300.0);
        assertThat(diagram.findById(w2.id()).orElseThrow().x()).isEqualTo(300.0);
    }

    @Test
    @DisplayName("Klick auf ein vorhandenes W im Verbindungsaufbau setzt einen NEUEN Kettenpunkt")
    void connectingClickOnExistingWaypointExtendsChain() {
        bus.publish(new ActionEvent.SelectTool(ToolType.CONNECT));
        bus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.PRIMARY, 1)); // Start an K
        bus.publish(new InputEvent.MousePressed(100.0, 60.0, CustomMouseButton.PRIMARY, 1));  // auf W1
        bus.publish(new InputEvent.MousePressed(300.0, 100.0, CustomMouseButton.PRIMARY, 1)); // Ziel R

        // KEINE Verbindung endet an einem Waypoint:
        assertThat(established).noneMatch(e -> e.connection().targetId().equals(w1.id()));

        Connection created = established.getLast().connection();
        assertThat(created.sourceId()).isEqualTo(k.id());
        assertThat(created.targetId()).isEqualTo(r.id());
        assertThat(created.waypointIds()).hasSize(1);

        // Der Kettenpunkt ist ein NEUES W an der Klick-Position, nicht W1:
        UUID chainWaypointId = created.waypointIds().getFirst();
        assertThat(chainWaypointId).isNotEqualTo(w1.id());
        Waypoint chainWaypoint = (Waypoint) diagram.findById(chainWaypointId).orElseThrow();
        assertThat(chainWaypoint.x()).isEqualTo(100.0);
        assertThat(chainWaypoint.y()).isEqualTo(60.0);
    }

    @Test
    @DisplayName("Undo stellt die implizit mitgewanderten Ws wieder her")
    void undoRestoresImplicitlyMovedWaypoints() {
        click(100.0, 100.0, true);
        click(300.0, 100.0, true);
        dragRightBy40(100.0, 100.0);

        bus.publish(new ActionEvent.RequestUndo());

        assertThat(diagram.findById(k.id()).orElseThrow().x()).isEqualTo(100.0);
        assertThat(diagram.findById(r.id()).orElseThrow().x()).isEqualTo(300.0);
        assertThat(diagram.findById(w1.id()).orElseThrow().x()).isEqualTo(100.0);
        assertThat(diagram.findById(w2.id()).orElseThrow().x()).isEqualTo(300.0);
    }

    @Test
    @DisplayName("Doppelklick auf ein W selektiert nur — kein Resize-Modus")
    void doubleClickOnWaypointSelectsWithoutResizeMode() {
        bus.publish(new InputEvent.MousePressed(100.0, 60.0, CustomMouseButton.PRIMARY, 2));
        bus.publish(new InputEvent.MouseReleased(100.0, 60.0, CustomMouseButton.PRIMARY));

        // Kein ResizeModeState: Der folgende Drag bewegt das W (im Resize-Modus
        // würde der Press abwählen und der Drag ins Leere laufen).
        assertThat(bus.getToolState()).isInstanceOf(IdleToolState.class);
        assertThat(selectionFacts.getLast().selectedIds()).containsExactly(w1.id());

        dragRightBy40(100.0, 60.0);
        assertThat(diagram.findById(w1.id()).orElseThrow().x()).isEqualTo(140.0);
    }
}
