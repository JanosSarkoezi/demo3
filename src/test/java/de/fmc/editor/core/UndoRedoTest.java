package de.fmc.editor.core;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.Connection;
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
 * Verifiziert die Undo/Redo-Architektur: Undo ist nur ein weiterer Schreiber
 * über den Bus. Die Fakten tragen den Vor-Zustand; der UndoService leitet
 * daraus mechanisch inverse Requests ab — pro Geste genau ein Undo-Punkt.
 */
class UndoRedoTest {

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
        return circle;
    }

    @Test
    @DisplayName("Minibeispiel: Erstellen → CTRL+Z (weg) → SHIFT+CTRL+Z (wieder da, gleiche ID)")
    void createUndoRedoCycle() {
        Circle circle = addCircle(100.0, 100.0);

        bus.publish(new ActionEvent.RequestUndo());
        assertThat(diagram.findById(circle.id())).isEmpty();

        bus.publish(new ActionEvent.RequestRedo());
        assertThat(diagram.findById(circle.id())).isPresent();
        assertThat(diagram.findById(circle.id()).orElseThrow())
                .isEqualTo(circle); // Dasselbe Record — gleiche ID, gleiche Werte
    }

    @Test
    @DisplayName("Move-Geste: mehrere Drag-Segmente sind EIN Undo-Punkt zurück zum Start")
    void moveGestureIsOneUndoStep() {
        Circle circle = addCircle(100.0, 100.0);

        // Geste simulieren: Press, Drag (Übergang), Drag (MoveObject), Release
        bus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.PRIMARY, 1));
        bus.publish(new InputEvent.MouseDragged(120.0, 100.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseDragged(150.0, 100.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseReleased(150.0, 100.0, CustomMouseButton.PRIMARY));
        assertThat(diagram.findById(circle.id()).orElseThrow().x()).isEqualTo(150.0);

        // EIN Undo genügt für die ganze Geste:
        bus.publish(new ActionEvent.RequestUndo());
        var position = diagram.findById(circle.id()).orElseThrow();
        assertThat(position.x()).isEqualTo(100.0);
        assertThat(position.y()).isEqualTo(100.0);

        bus.publish(new ActionEvent.RequestRedo());
        assertThat(diagram.findById(circle.id()).orElseThrow().x()).isEqualTo(150.0);
    }

    /** Neutrale Gesten-Grenze für synthetische Abläufe: IdleToolState ignoriert
     *  ein Release ohne vorangegangenen Press, der UndoService schließt den Eintrag. */
    private void closeGesture() {
        bus.publish(new InputEvent.MouseReleased(0.0, 0.0, CustomMouseButton.PRIMARY));
    }

    @Test
    @DisplayName("Zwei separate Move-Gesten sind zwei Undo-Punkte")
    void twoGesturesAreTwoUndoSteps() {
        Circle circle = addCircle(100.0, 100.0);
        closeGesture();

        // Geste 1: 100 -> 130 (erster Drag = Übergang, zweiter Drag = MoveObject)
        bus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.PRIMARY, 1));
        bus.publish(new InputEvent.MouseDragged(110.0, 100.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseDragged(130.0, 100.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseReleased(130.0, 100.0, CustomMouseButton.PRIMARY));
        assertThat(diagram.findById(circle.id()).orElseThrow().x()).isEqualTo(130.0);

        // Geste 2: 130 -> 160
        bus.publish(new InputEvent.MousePressed(130.0, 100.0, CustomMouseButton.PRIMARY, 1));
        bus.publish(new InputEvent.MouseDragged(140.0, 100.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseDragged(160.0, 100.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseReleased(160.0, 100.0, CustomMouseButton.PRIMARY));
        assertThat(diagram.findById(circle.id()).orElseThrow().x()).isEqualTo(160.0);

        bus.publish(new ActionEvent.RequestUndo());
        assertThat(diagram.findById(circle.id()).orElseThrow().x()).isEqualTo(130.0);
        bus.publish(new ActionEvent.RequestUndo());
        assertThat(diagram.findById(circle.id()).orElseThrow().x()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("Lösch-Kaskade: Undo stellt Objekt, Waypoints UND Connection mit Original-IDs her")
    void undoRestoresDeleteCascade() {
        Circle circle = addCircle(100.0, 100.0);
        closeGesture();
        Rectangle rect = new Rectangle(UUID.randomUUID(), 300.0, 100.0, 40.0, 30.0, defaultLayerId);
        bus.publish(new ActionEvent.AddElement(rect));
        closeGesture();
        Waypoint waypoint = new Waypoint(UUID.randomUUID(), 200.0, 150.0, defaultLayerId);
        bus.publish(new ActionEvent.CreateConnection(circle.id(), rect.id(), List.of(waypoint)));
        closeGesture();
        Connection connection = established.getFirst().connection();

        // Löschen kaskadiert alles weg
        bus.publish(new ActionEvent.DeleteObjects(List.of(circle.id())));
        assertThat(diagram.findById(circle.id())).isEmpty();
        assertThat(diagram.findById(waypoint.id())).isEmpty();
        assertThat(diagram.findConnectionById(connection.id())).isEmpty();

        // Undo stellt ALLES mit Original-IDs wieder her
        bus.publish(new ActionEvent.RequestUndo());
        assertThat(diagram.findById(circle.id())).isPresent();
        assertThat(diagram.findById(rect.id())).isPresent();
        assertThat(diagram.findById(waypoint.id())).isPresent();
        Connection restored = diagram.findConnectionById(connection.id()).orElseThrow();
        assertThat(restored.waypointIds()).containsExactly(waypoint.id());

        // Redo entfernt die Kaskade erneut
        bus.publish(new ActionEvent.RequestRedo());
        assertThat(diagram.findById(circle.id())).isEmpty();
        assertThat(diagram.findById(waypoint.id())).isEmpty();
        assertThat(diagram.findConnectionById(connection.id())).isEmpty();
    }

    @Test
    @DisplayName("Verbindungserstellung: Undo entfernt Connection UND ihre Waypoints")
    void undoRemovesConnectionAndWaypoints() {
        Circle circle = addCircle(100.0, 100.0);
        closeGesture();
        Rectangle rect = new Rectangle(UUID.randomUUID(), 300.0, 100.0, 40.0, 30.0, defaultLayerId);
        bus.publish(new ActionEvent.AddElement(rect));
        closeGesture();
        Waypoint w1 = new Waypoint(UUID.randomUUID(), 150.0, 150.0, defaultLayerId);
        Waypoint w2 = new Waypoint(UUID.randomUUID(), 250.0, 150.0, defaultLayerId);
        bus.publish(new ActionEvent.CreateConnection(circle.id(), rect.id(), List.of(w1, w2)));
        Connection connection = established.getFirst().connection();

        bus.publish(new ActionEvent.RequestUndo());
        assertThat(diagram.findConnectionById(connection.id())).isEmpty();
        assertThat(diagram.findById(w1.id())).isEmpty();
        assertThat(diagram.findById(w2.id())).isEmpty();
        // Die Endpunkte bleiben:
        assertThat(diagram.findById(circle.id())).isPresent();
        assertThat(diagram.findById(rect.id())).isPresent();

        bus.publish(new ActionEvent.RequestRedo());
        Connection restored = diagram.findConnectionById(connection.id()).orElseThrow();
        assertThat(restored.waypointIds()).containsExactly(w1.id(), w2.id());
        assertThat(diagram.findById(w1.id())).isPresent();
        assertThat(diagram.findById(w2.id())).isPresent();
    }

    @Test
    @DisplayName("Resize: Undo stellt die alten Maße exakt wieder her")
    void undoRestoresOldSize() {
        Rectangle rect = new Rectangle(UUID.randomUUID(), 100.0, 100.0, 40.0, 30.0, defaultLayerId);
        bus.publish(new ActionEvent.AddElement(rect));
        closeGesture(); // Erstellen ist ein eigener Undo-Punkt, Resize ein weiterer

        bus.publish(new ActionEvent.ResizeObject(rect.id(), 80.0, 50.0));
        bus.publish(new ActionEvent.RequestUndo());
        Rectangle undone = (Rectangle) diagram.findById(rect.id()).orElseThrow();
        assertThat(undone.width()).isEqualTo(40.0);
        assertThat(undone.height()).isEqualTo(30.0);

        bus.publish(new ActionEvent.RequestRedo());
        Rectangle redone = (Rectangle) diagram.findById(rect.id()).orElseThrow();
        assertThat(redone.width()).isEqualTo(80.0);
        assertThat(redone.height()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("Neue Aktion nach Undo leert den Redo-Stack")
    void newActionInvalidatesRedoStack() {
        Circle a = addCircle(100.0, 100.0);
        bus.publish(new ActionEvent.RequestUndo());
        assertThat(diagram.findById(a.id())).isEmpty();

        // Neue Aktion (Kollege ruft, neuer Klick) — Redo-Altbestand ist futsch:
        Circle b = addCircle(300.0, 100.0);
        bus.publish(new ActionEvent.RequestRedo());

        // A bleibt weg, B bleibt da:
        assertThat(diagram.findById(a.id())).isEmpty();
        assertThat(diagram.findById(b.id())).isPresent();
    }

    @Test
    @DisplayName("Undo mit leerer History ist ein stiller No-Op")
    void undoWithEmptyHistoryIsSilentNoOp() {
        Circle circle = addCircle(100.0, 100.0);

        bus.publish(new ActionEvent.RequestUndo());
        bus.publish(new ActionEvent.RequestUndo()); // zweite Ebene gibt es nicht

        // Das erste Undo hat den Kreis entfernt; das zweite tat still nichts:
        assertThat(diagram.findById(circle.id())).isEmpty();
    }
}
