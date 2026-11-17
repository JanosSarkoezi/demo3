package de.fmc.editor.view;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Event;
import de.fmc.editor.core.model.EventDirection;
import de.fmc.editor.core.model.EventSide;
import de.fmc.editor.core.model.GridSpec;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.Text;
import de.fmc.editor.core.model.Text;
import de.fmc.editor.core.service.DiagramService;
import de.fmc.editor.core.service.SelectionService;
import de.fmc.editor.core.service.UndoService;
import de.fmc.editor.core.state.CameraInfo;
import javafx.scene.Group;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Regressionstest des owner-derivierten View-Refresh (23.11., User-Fund
 * „Pfeil klebt nach Undo des R-Moves"): Erstelle R, erstelle E, bewege das
 * R per Drag, Undo — der Pfeil muss WIEDER an der alten Position zeichnen.
 *
 * Ursache: EventRenderer leitet die Pfeilgeometrie aus dem OWNER ab, aber
 * onObjectsMoved aktualisierte Event-Nodes nur über deren EIGENE Move-
 * Einträge. Beim Undo läuft die inverse Wiedergabe rückwärts — der eigene
 * Eintrag des Events zeichnet gegen den noch NEUEN Owner-Stand, und der
 * anschließende Owner-Restore frischte die gedockten Events nicht nach.
 * Das Modell war die ganze Zeit korrekt (EventModelTest beweist die
 * Restore-Position); nur die View blieb stehen. Headless nach dem Muster
 * von ConnectionViewMapperTest — JavaFX-Nodes funktionieren ohne
 * gestartetes Toolkit.
 */
class EventOwnerRefreshTest {

    private DiagramService diagram;
    private InteractionEventBus bus;
    private GraphView view;
    private Rectangle owner;
    private UUID eventNodeId;

    @BeforeEach
    void setUp() {
        UUID layerId = UUID.randomUUID();
        diagram = new DiagramService();
        bus = new InteractionEventBus(diagram,
                new CameraInfo(1.0, 0.0, 0.0, layerId),
                new GridSpec(10.0), new SelectionService());
        diagram.attachTo(bus);
        new UndoService().attachTo(bus);
        view = new GraphView(bus, diagram);

        owner = new Rectangle(UUID.randomUUID(), 100.0, 100.0, 40.0, 30.0, layerId);
        bus.publish(new ActionEvent.AddElement(owner));
        bus.publish(new InputEvent.MouseReleased(0.0, 0.0, CustomMouseButton.PRIMARY));
        bus.publish(new ActionEvent.CreateEvent(owner.id(), EventSide.RIGHT,
                EventDirection.INCOMING, "L", 140.0, 60.0));
        bus.publish(new InputEvent.MouseReleased(0.0, 0.0, CustomMouseButton.PRIMARY));

        Event event = diagram.allObjectsInZOrder().stream()
                .filter(obj -> obj instanceof Event).map(obj -> (Event) obj)
                .findFirst().orElseThrow();
        eventNodeId = event.id();
    }

    /** Startpunkt des Event-Pfads (Kind 0, erstes Element) — die Zeichnung
     *  läuft Start → Bogen → Spitze, der Start ist die sichtbarste Position. */
    private double[] pathStart() {
        Group node = view.objectNode(eventNodeId);
        assertThat(node).as("Event-Node muss existieren").isNotNull();
        Path path = (Path) node.getChildren().get(0);
        MoveTo start = (MoveTo) path.getElements().get(0);
        return new double[]{start.getX(), start.getY()};
    }

    private UUID labelId() {
        return diagram.findTextIdsOwnedBy(eventNodeId).getFirst();
    }

    @Test
    @DisplayName("R-Move + Undo: Der Pfeil zeichnet wieder an der ALTEN Position (User-Repro)")
    void undoOfOwnerMoveRedrawsArrowAtOldPosition() {
        moveUndoRedoArrowFollows();
    }

    @Test
    @DisplayName("Negative probe: Ohne owner-derivierten Refresh klebt der Pfeil (Bug ist real)")
    void withoutHookArrowStaysStale() {
        GraphView.REFRESH_OWNER_DERIVED_NODES = false;
        try {
            moveAndUndoArrowStaysStale();
        } finally {
            GraphView.REFRESH_OWNER_DERIVED_NODES = true;
        }
    }

    /** Ohne owner-derivierten Refresh bleibt der Pfeil nach dem Undo auf
     *  der neuen Position stehen (Modell korrekt, View veraltet). */
    private void moveAndUndoArrowStaysStale() {
        assertThat(pathStart()).containsExactly(new double[]{160.0, 45.0}, within(1e-9));
        bus.publish(new ActionEvent.MoveObjects(
                diagram.findMoveSet(java.util.List.of(owner.id())), 30.0, 10.0));
        bus.publish(new InputEvent.MouseReleased(0.0, 0.0, CustomMouseButton.PRIMARY));
        assertThat(pathStart()).containsExactly(new double[]{190.0, 55.0}, within(1e-9));

        bus.publish(new ActionEvent.RequestUndo());
        // VOR dem Fix blieb der Pfeil hier kleben:
        assertThat(pathStart()).containsExactly(new double[]{190.0, 55.0}, within(1e-9));
    }

    /** Mit owner-deriviertem Refresh folgt der Pfeil Undo UND Redo. */
    private void moveUndoRedoArrowFollows() {
        // Ausgangslage: R bei 100/100 -> Start des rechten Pfeils = (160, 45)
        assertThat(pathStart()).containsExactly(new double[]{160.0, 45.0}, within(1e-9));

        // Drag: R um (30, 10) bewegt (Move-Menge über die Query wie im State)
        java.util.List<DiagramEvent.ObjectMoved> replayFacts = new java.util.ArrayList<>();
        bus.subscribe(DiagramEvent.ObjectMoved.class, replayFacts::add);
        bus.publish(new ActionEvent.MoveObjects(
                diagram.findMoveSet(java.util.List.of(owner.id())), 30.0, 10.0));
        bus.publish(new InputEvent.MouseReleased(0.0, 0.0, CustomMouseButton.PRIMARY));
        replayFacts.clear(); // nur die REPLAY-Fakten interessieren
        // R jetzt bei 130/110 -> Seitenrand 150 -> Start = (190, 55)
        assertThat(pathStart()).containsExactly(new double[]{190.0, 55.0}, within(1e-9));

        // UNDO — vor dem Fix blieb der Pfeil hier auf (190, 55) kleben
        bus.publish(new ActionEvent.RequestUndo());
        Event modelEvent = (Event) diagram.findById(eventNodeId).orElseThrow();
        Rectangle modelOwner = (Rectangle) diagram.findById(owner.id()).orElseThrow();
        assertThat(modelOwner.x()).as("R-Modellposition nach Undo")
                .isCloseTo(100.0, within(1e-9));
        assertThat(modelEvent.x()).as("Event-Spitze (Modell) nach Undo")
                .isCloseTo(110.0, within(1e-9));
        assertThat(replayFacts).as("Replay muss EINZEL-Move-Fakten für R, E und Label "
                + "publizieren (Reihenfolge label->E->R)")
                .extracting(DiagramEvent.ObjectMoved::id)
                .containsExactly(labelId(), eventNodeId, owner.id());
        assertThat(pathStart()).as("View-Pfad nach Undo")
                .containsExactly(new double[]{160.0, 45.0}, within(1e-9));

        // REDO — und wieder vor: Der Pfeil folgt auch vorwärts.
        bus.publish(new ActionEvent.RequestRedo());
        assertThat(pathStart()).containsExactly(new double[]{190.0, 55.0}, within(1e-9));
    }

    @Test
    @DisplayName("Undo mit weit gezogenem Label: Auch das Label-Node folgt seinem eigenen Eintrag")
    void undoRestoresLabelNodePosition() {
        UUID labelId = diagram.findTextIdsOwnedBy(eventNodeId).getFirst();
        Text label = (Text) diagram.findById(labelId).orElseThrow();

        // Label weit wegziehen, dann R bewegen — zwei Gesten.
        bus.publish(new ActionEvent.MoveObjectTo(labelId, 400.0, label.y()));
        bus.publish(new InputEvent.MouseReleased(0.0, 0.0, CustomMouseButton.PRIMARY));
        bus.publish(new ActionEvent.MoveObjects(
                diagram.findMoveSet(java.util.List.of(owner.id())), 30.0, 0.0));
        bus.publish(new InputEvent.MouseReleased(0.0, 0.0, CustomMouseButton.PRIMARY));

        // Undo des R-Moves: Das Label ist MITgenommen worden (sein eigener
        // Batch-Eintrag) und muss auf der Modell-Position stehen — der
        // Node-Refresh über den Owner-Hook darf ihn nicht verschieben.
        bus.publish(new ActionEvent.RequestUndo());
        Text restored = (Text) diagram.findById(labelId).orElseThrow();
        assertThat(restored.x()).isCloseTo(400.0, within(1e-9));
        // Der Pfeil wieder an der alten Stelle.
        assertThat(pathStart()).containsExactly(new double[]{160.0, 45.0}, within(1e-9));
    }
}
