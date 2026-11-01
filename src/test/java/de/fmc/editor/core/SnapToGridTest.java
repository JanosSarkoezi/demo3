package de.fmc.editor.core;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.ToolType;
import de.fmc.editor.core.model.Waypoint;
import de.fmc.editor.core.service.DiagramService;
import de.fmc.editor.core.state.impl.RouteEditModeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifiziert das Move-Snapping (Block 2, Posten 6): Beim Verschieben
 * kleben Objekte an Grid-Kreuzungspunkten, bis die Maus dem NÄCHSTEN
 * Kreuzungspunkt näher liegt (spec.md). Demo2-Semantik: Die Zielposition
 * des PRIMÄR gezogenen Objekts snapt (zentral über GridSpec.snapToGrid),
 * die Gruppe folgt dem resultierenden effektiven Delta. Erzeugung bleibt
 * frei (spec: nur das Verschieben snappt). Geste-relevant:
 * - Das erste Drag-Event konsumiert der PendingMoveState-Übergang — die
 *   Snap-Rechnung läuft deshalb über die MAUS-SUMME seit Gesten-Start,
 *   nicht über Einzelsegmente (Segmentsicht klebte für immer fest).
 * - Null-Delta-Segmente (Wiggle innerhalb der Zelle) publizieren NICHTS:
 *   kein Fakt, kein Undo-Rauschen.
 */
class SnapToGridTest {

    private BusTestSupport support;
    private InteractionEventBus bus;
    private DiagramService diagram;
    private final List<ActionEvent.MoveObjects> moveRequests = new ArrayList<>();

    @BeforeEach
    void setUp() {
        support = new BusTestSupport(); // GridSpec(10.0), Zoom 1.0
        bus = support.bus;
        diagram = support.diagram;
        bus.subscribe(ActionEvent.MoveObjects.class, moveRequests::add);
    }

    /** Drag-Geste: Press auf (x, y), ein konsumierendes Transition-Event,
     *  dann die Maus bis zur End-Position. */
    private void dragTo(double x, double y, double endX, double endY) {
        bus.publish(new InputEvent.MousePressed(x, y, CustomMouseButton.PRIMARY, 1));
        bus.publish(new InputEvent.MouseDragged(x + 10.0, y, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseDragged(endX, endY, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseReleased(endX, endY, CustomMouseButton.PRIMARY));
    }

    @Test
    @DisplayName("Maus-Delta 25 px: Objekt springt auf den NÄCHSTEN Schnittpunkt (130)")
    void dragBeyondMidpointSnapsToNextCrossing() {
        Circle k = support.addCircle(100.0, 100.0);
        support.closeGesture();

        // Maus insgesamt +25 (bei Grid 10): 125 ist näher an 130 als an 100
        dragTo(100.0, 100.0, 125.0, 100.0);

        assertThat(diagram.findById(k.id()).orElseThrow().x()).isEqualTo(130.0);
    }

    @Test
    @DisplayName("Wiggle innerhalb der Zelle: Objekt klebt, kein MoveObjects")
    void wiggleWithinCellPublishesNothing() {
        Circle k = support.addCircle(100.0, 100.0);
        support.closeGesture();

        bus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.PRIMARY, 1));
        // Transition-Event (konsumiert von PendingMoveState) + erster Snap-Schritt
        bus.publish(new InputEvent.MouseDragged(105.0, 100.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseDragged(113.0, 100.0, CustomMouseButton.PRIMARY));
        assertThat(diagram.findById(k.id()).orElseThrow().x()).isEqualTo(110.0);
        int requestsAfterJump = moveRequests.size();

        // Maus zurück in Richtung Zellmitte (+8 gesamt): Ziel bleibt 110
        bus.publish(new InputEvent.MouseDragged(108.0, 100.0, CustomMouseButton.PRIMARY));
        assertThat(diagram.findById(k.id()).orElseThrow().x()).isEqualTo(110.0);
        assertThat(moveRequests).hasSize(requestsAfterJump); // geklebt, kein Rauschen

        // Maus unter die alte Mittellinie (+4 gesamt): Ziel fällt auf 100 zurück
        bus.publish(new InputEvent.MouseDragged(104.0, 100.0, CustomMouseButton.PRIMARY));
        assertThat(diagram.findById(k.id()).orElseThrow().x()).isEqualTo(100.0);
        assertThat(moveRequests).hasSize(requestsAfterJump + 1);
    }

    @Test
    @DisplayName("Gruppen-Move: Primär-Objekt (K) snappt, R folgt dem effektiven Delta")
    void groupFollowsPrimarysSnappedDelta() {
        Circle k = support.addCircle(100.0, 100.0);
        Rectangle r = support.addRectangle(300.0, 100.0);
        support.closeGesture();

        // Auswahl [K, R], Press auf K -> K ist das Snapping-Bezugsobjekt
        bus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.PRIMARY, 1, true));
        bus.publish(new InputEvent.MouseReleased(100.0, 100.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MousePressed(300.0, 100.0, CustomMouseButton.PRIMARY, 1, true));
        bus.publish(new InputEvent.MouseReleased(300.0, 100.0, CustomMouseButton.PRIMARY));

        dragTo(100.0, 100.0, 125.0, 100.0);

        // Beide um dasselbe (gesnappte) +30: K auf 130, R folgt auf 330
        assertThat(diagram.findById(k.id()).orElseThrow().x()).isEqualTo(130.0);
        assertThat(diagram.findById(r.id()).orElseThrow().x()).isEqualTo(330.0);
    }

    @Test
    @DisplayName("Waypoints snappen im RouteEditMode ebenfalls (uniform)")
    void waypointSnapsInRouteEditMode() {
        Circle k = support.addCircle(100.0, 100.0);
        Rectangle r = support.addRectangle(300.0, 100.0);
        Waypoint w1 = new Waypoint(UUID.randomUUID(), 100.0, 60.0, support.defaultLayerId);
        bus.publish(new ActionEvent.CreateConnection(
                k.id(), r.id(), List.of(w1)));

        // Modus betreten: CTRL+Klick auf (200, 80) liegt AUF dem schrägen
        // Kettenabschnitt W1(100,60) -> R(300,100) (kein Objekt dort).
        bus.publish(new InputEvent.MousePressed(200.0, 80.0, CustomMouseButton.PRIMARY, 1, true));
        bus.publish(new InputEvent.MouseReleased(200.0, 80.0, CustomMouseButton.PRIMARY));
        assertThat(bus.getToolState()).isInstanceOf(RouteEditModeState.class);

        dragTo(100.0, 60.0, 125.0, 60.0);

        assertThat(diagram.findById(w1.id()).orElseThrow().x()).isEqualTo(130.0);
    }

    @Test
    @DisplayName("Gesnappte Move-Geste bleibt EIN Undo-Punkt")
    void snappedMoveIsSingleUndoPoint() {
        Circle k = support.addCircle(100.0, 100.0);
        support.closeGesture();

        bus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.PRIMARY, 1));
        bus.publish(new InputEvent.MouseDragged(125.0, 100.0, CustomMouseButton.PRIMARY)); // -> 130
        bus.publish(new InputEvent.MouseDragged(145.0, 100.0, CustomMouseButton.PRIMARY)); // -> 150
        bus.publish(new InputEvent.MouseReleased(145.0, 100.0, CustomMouseButton.PRIMARY));
        assertThat(diagram.findById(k.id()).orElseThrow().x()).isEqualTo(150.0);

        bus.publish(new ActionEvent.RequestUndo());

        assertThat(diagram.findById(k.id()).orElseThrow().x()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("Erzeugung bleibt frei: Klick auf (155, 203) snappt NICHT")
    void creationStaysFreeOfSnapping() {
        bus.publish(new ActionEvent.SelectTool(ToolType.CIRCLE));
        bus.publish(new InputEvent.MousePressed(155.0, 203.0, CustomMouseButton.PRIMARY, 1));
        bus.publish(new InputEvent.MouseReleased(155.0, 203.0, CustomMouseButton.PRIMARY));

        Circle created = diagram.allObjectsInZOrder().stream()
                .filter(Circle.class::isInstance)
                .map(Circle.class::cast)
                .findFirst().orElseThrow();
        assertThat(created.x()).isEqualTo(155.0);
        assertThat(created.y()).isEqualTo(203.0);
    }

    // -----------------------------------------------------------------
    // Wählbarer Raster-Abstand (Posten 8: SetGridSize, Toolbar-Eingabe)
    // -----------------------------------------------------------------

    @Test
    @DisplayName("SetGridSize(25) wirkt sofort: Drag +30 landet auf 125, nicht 130")
    void setGridSizeChangesSnapRasterImmediately() {
        bus.publish(new ActionEvent.SetGridSize(25.0));
        Circle k = support.addCircle(100.0, 100.0);
        support.closeGesture();

        // Ziel 130 ist bei Raster 25 näher an 125 (5. Zelle) als an 150
        dragTo(100.0, 100.0, 130.0, 100.0);

        assertThat(diagram.findById(k.id()).orElseThrow().x()).isEqualTo(125.0);
    }

    @Test
    @DisplayName("Ungültige Rasterwerte (0, −5, NaN) werden still ignoriert")
    void invalidGridSizeKeepsCurrentRaster() {
        bus.publish(new ActionEvent.SetGridSize(0.0));
        bus.publish(new ActionEvent.SetGridSize(-5.0));
        bus.publish(new ActionEvent.SetGridSize(Double.NaN));

        Circle k = support.addCircle(100.0, 100.0);
        support.closeGesture();

        // Fixture-Raster bleibt 10: Ziel 125 -> snap -> 130
        dragTo(100.0, 100.0, 125.0, 100.0);
        assertThat(diagram.findById(k.id()).orElseThrow().x()).isEqualTo(130.0);
    }

    @Test
    @DisplayName("Neue Zelle = neue Mindestgröße: Raster 50 klemmt Resize auf Radius 50")
    void gridSizeDefinesMinimumObjectSize() {
        bus.publish(new ActionEvent.SetGridSize(50.0));
        Circle k = support.addCircle(100.0, 100.0);   // r = 20
        support.closeGesture();

        // Doppelklick -> Resize-Modus; Ost-Griff greifen und weit nach INNEN
        // ziehen (Maus nur 10 vom Zentrum): Die Klemmung „1 Zelle" hält.
        bus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.PRIMARY, 2));
        bus.publish(new InputEvent.MousePressed(120.0, 100.0, CustomMouseButton.PRIMARY, 1));
        bus.publish(new InputEvent.MouseDragged(110.0, 100.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseReleased(110.0, 100.0, CustomMouseButton.PRIMARY));

        Circle resized = (Circle) diagram.findById(k.id()).orElseThrow();
        assertThat(resized.radius()).isEqualTo(50.0);
    }
}
