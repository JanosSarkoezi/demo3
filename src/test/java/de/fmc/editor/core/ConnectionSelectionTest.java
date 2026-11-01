package de.fmc.editor.core;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.Connection;
import de.fmc.editor.core.model.GridSpec;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.Text;
import de.fmc.editor.core.model.TextAnchor;
import de.fmc.editor.core.model.Waypoint;
import de.fmc.editor.core.service.DiagramService;
import de.fmc.editor.core.service.SelectionService;
import de.fmc.editor.core.service.UndoService;
import de.fmc.editor.core.state.CameraInfo;
import de.fmc.editor.core.state.impl.IdleToolState;
import de.fmc.editor.core.state.impl.RouteEditModeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verbindungs-Selektion (03.11.): Uniforme Klick-Selektion — Plain-Klick
 * nahe einer Verbindung selektiert NUR die Verbindung (blaue Markierung;
 * ihre Ws zeigt die View abgeleitet mit, sie stehen nicht in der Auswahl);
 * CTRL+Klick betritt den RouteEditMode (Ws gelb + greifbar); DEL löscht die
 * Verbindung samt Kaskade (Ws + verankerte Labels), die Endpunkte bleiben;
 * Drag an der Verbindung ist ein No-Op (Verbindungen sind nicht bewegbar).
 *
 * Zwei kritische Nebenaspekte: Die Auswahl-Invariante muss auch Connection-
 * Löschungen stauben (auch die Endpunkt-Kaskade), und eine Connection-ID in
 * der Auswahl darf die effektive Move-Menge nicht verunreinigen — das
 * Verbindungs-Label GLEITET bei Endpunkt-Moves, es darf nie Move-Basis
 * werden (ein MoveObjects würde es sonst RE-ANKERN).
 */
class ConnectionSelectionTest {

    private InteractionEventBus bus;
    private DiagramService diagram;
    private SelectionService selection;
    private UUID defaultLayerId;

    private final List<DiagramEvent.SelectionChanged> selectionFacts = new ArrayList<>();
    private final List<ActionEvent.WaypointFocusChanged> focusHints = new ArrayList<>();
    private final List<DiagramEvent.ObjectsMoved> movedBatchFacts = new ArrayList<>();
    private final List<DiagramEvent.ObjectTextChanged> textChangedFacts = new ArrayList<>();
    private final List<DiagramEvent.ObjectsRemoved> removedFacts = new ArrayList<>();
    private final List<DiagramEvent.ConnectionsRemoved> connectionsRemovedFacts = new ArrayList<>();
    private final List<DiagramEvent.ConnectionEstablished> established = new ArrayList<>();

    // Kette: K(100,100) -- W1(250,100) -- W2(350,100) -- R(500,100).
    private Circle k;
    private Rectangle r;
    private Waypoint w1;
    private Waypoint w2;
    private UUID connId;

    @BeforeEach
    void setUp() {
        selectionFacts.clear();
        focusHints.clear();
        movedBatchFacts.clear();
        textChangedFacts.clear();
        removedFacts.clear();
        connectionsRemovedFacts.clear();
        established.clear();
        defaultLayerId = UUID.randomUUID();

        selection = new SelectionService();
        diagram = new DiagramService();
        bus = new InteractionEventBus(diagram,
                new CameraInfo(1.0, 0.0, 0.0, defaultLayerId),
                new GridSpec(10.0), selection);
        diagram.attachTo(bus);
        selection.attachTo(bus);
        new UndoService().attachTo(bus);

        bus.subscribe(DiagramEvent.SelectionChanged.class, selectionFacts::add);
        bus.subscribe(ActionEvent.WaypointFocusChanged.class, focusHints::add);
        bus.subscribe(DiagramEvent.ObjectsMoved.class, movedBatchFacts::add);
        bus.subscribe(DiagramEvent.ObjectTextChanged.class, textChangedFacts::add);
        bus.subscribe(DiagramEvent.ObjectsRemoved.class, removedFacts::add);
        bus.subscribe(DiagramEvent.ConnectionsRemoved.class, connectionsRemovedFacts::add);
        bus.subscribe(DiagramEvent.ConnectionEstablished.class, established::add);

        k = addCircle(100.0, 100.0);
        r = addRectangle(500.0, 100.0);
        w1 = new Waypoint(UUID.randomUUID(), 250.0, 100.0, defaultLayerId);
        w2 = new Waypoint(UUID.randomUUID(), 350.0, 100.0, defaultLayerId);
        bus.publish(new ActionEvent.CreateConnection(k.id(), r.id(), List.of(w1, w2)));
        connId = established.getLast().connection().id();
    }

    // ---------- Helfer ----------

    private Circle addCircle(double x, double y) {
        Circle circle = new Circle(UUID.randomUUID(), x, y, 20.0, defaultLayerId);
        bus.publish(new ActionEvent.AddElement(circle));
        return circle;
    }

    private Rectangle addRectangle(double x, double y) {
        Rectangle rect = new Rectangle(UUID.randomUUID(), x, y, 40.0, 30.0, defaultLayerId);
        bus.publish(new ActionEvent.AddElement(rect));
        return rect;
    }

    /** Verankertes Label auf Abschnitt 1 (W1->W2), Mitte plus 15 darunter:
     *  Position (300,115). */
    private Text addConnectionLabel() {
        bus.publish(new ActionEvent.AddAnchoredText(connId, "liefert",
                new TextAnchor(1, 0.5, 0.0, 15.0), 40.0, 20.0, defaultLayerId));
        Text label = (Text) diagram.allObjectsInZOrder().stream()
                .filter(obj -> obj instanceof Text)
                .reduce((a, b) -> b).orElseThrow();
        assertThat(label.x()).isEqualTo(300.0);
        assertThat(label.y()).isEqualTo(115.0);
        return label;
    }

    private void press(double x, double y, boolean ctrl) {
        bus.publish(new InputEvent.MousePressed(x, y, CustomMouseButton.PRIMARY, 1, ctrl));
    }

    private void release(double x, double y) {
        bus.publish(new InputEvent.MouseReleased(x, y, CustomMouseButton.PRIMARY));
    }

    private void click(double x, double y, boolean ctrl) {
        press(x, y, ctrl);
        release(x, y);
    }

    private Text textById(UUID id) {
        return (Text) diagram.findById(id).orElseThrow();
    }

    // ---------- Selektion & Modus-Eintritt ----------

    @Test
    @DisplayName("Klick nahe der Verbindung selektiert NUR die Verbindung — exklusiv, ohne Modus")
    void plainClickNearConnectionSelectsConnectionOnly() {
        click(250.0, 105.0, false);

        // Nur die Connection-ID — die Ws stehen bewusst NICHT in der Auswahl:
        // Sie sterben beim Löschen als Service-Kaskade, die View markiert
        // sie abgeleitet mit. Ws in der Auswahl wären Move-Basis-Mitglieder.
        assertThat(selectionFacts.getLast().selectedIds())
                .containsExactly(connId);
        assertThat(selection.selectedIds())
                .containsExactly(connId);
        // KEIN Modus-Eintritt: kein Fokus-Hinweis, Rückkehr ins Idle.
        assertThat(focusHints).isEmpty();
        assertThat(bus.getToolState()).isInstanceOf(IdleToolState.class);
    }

    @Test
    @DisplayName("CTRL+Klick nahe der Verbindung betritt den RouteEditMode")
    void ctrlClickNearConnectionEntersRouteEditMode() {
        int factsBefore = selectionFacts.size();

        click(250.0, 105.0, true);

        assertThat(focusHints.getLast().connectionId()).isEqualTo(connId);
        assertThat(selectionFacts).hasSize(factsBefore); // Modus ändert keine Auswahl
        assertThat(bus.getToolState()).isInstanceOf(RouteEditModeState.class);
    }

    @Test
    @DisplayName("Press auf der (selektierten) Verbindung bleibt Idle — kein Move wird vorbereitet")
    void pressOnConnectionPreparesNoMove() {
        click(250.0, 105.0, false);
        assertThat(selection.selectedIds()).containsExactly(connId);

        // Erneuter Press auf der Verbindung: reine Selektion (identische Menge,
        // kein Fakt), KEIN PendingMoveState — Drag = No-Op.
        press(250.0, 105.0, false);
        assertThat(bus.getToolState()).isInstanceOf(IdleToolState.class);
        release(250.0, 105.0);
        assertThat(bus.getToolState()).isInstanceOf(IdleToolState.class);
    }

    // ---------- Drag: No-Op — Route-Editing läuft über den RouteEditMode ----------

    @Test
    @DisplayName("Drag an der selektierten Verbindung ist ein No-Op — Label wird weder bewegt noch re-ankert")
    void draggingSelectedConnectionMovesNothing() {
        Text label = addConnectionLabel();
        closeGesture();

        movedBatchFacts.clear();
        textChangedFacts.clear();
        press(250.0, 105.0, false);
        bus.publish(new InputEvent.MouseDragged(250.0, 110.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseDragged(250.0, 115.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseReleased(250.0, 115.0, CustomMouseButton.PRIMARY));

        // Nichts bewegt sich: Ws, Endpunkte und Label stehen exakt wie zuvor.
        assertThat(diagram.findById(w1.id()).orElseThrow().y()).isEqualTo(100.0);
        assertThat(diagram.findById(w2.id()).orElseThrow().y()).isEqualTo(100.0);
        assertThat(diagram.findById(k.id()).orElseThrow().y()).isEqualTo(100.0);
        assertThat(diagram.findById(r.id()).orElseThrow().y()).isEqualTo(100.0);
        Text untouched = textById(label.id());
        assertThat(untouched.y()).isEqualTo(115.0);
        assertThat(untouched.anchor()).isEqualTo(new TextAnchor(1, 0.5, 0.0, 15.0));

        // Weder Move- noch TextChanged-Fakten: Das eliminiert die ganze
        // Problemklasse „Label als Move-Basis wird re-ankert statt gleitet".
        assertThat(movedBatchFacts).isEmpty();
        assertThat(textChangedFacts).isEmpty();
        assertThat(bus.getToolState()).isInstanceOf(IdleToolState.class);
    }

    // ---------- Move-Mengen-Reinheit: Connection-ID ist keine Move-Basis ----------

    @Test
    @DisplayName("Connection-ID in der Auswahl verunreinigt die Move-Menge nicht — Label gleitet, wird nie re-ankert")
    void connectionIdInSelectionDoesNotPolluteMoveSet() {
        Text label = addConnectionLabel();
        closeGesture();

        // Verbindung selektieren, dann K per Ctrl-Toggle mit aufnehmen:
        // Auswahl = {connId, k} — gemischte Auswahl, K ist das Primär-Ziel.
        // KEIN Release: Die Drag-Geste läuft aus demselben Press heraus.
        click(250.0, 105.0, false);
        press(100.0, 100.0, true);
        assertThat(selection.selectedIds()).containsExactly(connId, k.id());

        movedBatchFacts.clear();
        textChangedFacts.clear();
        bus.publish(new InputEvent.MouseDragged(105.0, 100.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseDragged(110.0, 100.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseReleased(110.0, 100.0, CustomMouseButton.PRIMARY));

        // K ist tatsächlich gewandert (Snap: 10er-Raster, +10 world).
        assertThat(diagram.findById(k.id()).orElseThrow().x()).isEqualTo(110.0);

        // Das Label ist NICHT Move-Basis geworden: Abschnitt 1 (W1->W2) ist
        // unverändert, also steht es exakt wie zuvor — KEIN ObjectTextChanged.
        // (Ohne den findMoveSet-Schutz hätte der Drag es auf (310,115)
        // projiziert und RE-ANKERT: t 0.5 -> 0.6.)
        Text untouched = textById(label.id());
        assertThat(untouched.x()).isEqualTo(300.0);
        assertThat(untouched.y()).isEqualTo(115.0);
        assertThat(untouched.anchor()).isEqualTo(new TextAnchor(1, 0.5, 0.0, 15.0));
        assertThat(textChangedFacts).isEmpty();

        List<UUID> movedIds = movedBatchFacts.getLast().movedObjects().stream()
                .map(DiagramEvent.ObjectMoved::id).toList();
        assertThat(movedIds).containsExactly(k.id());
    }

    // ---------- DEL: Verbindung + Ws + Label, Endpunkte bleiben ----------

    @Test
    @DisplayName("DEL auf die ausgewählte Verbindung löscht Ws UND Label mit — Endpunkte bleiben; Undo stellt her")
    void deleteKeyRemovesConnectionWithCascadeButKeepsEndpoints() {
        Text label = addConnectionLabel();
        click(250.0, 105.0, false);
        closeGesture();

        removedFacts.clear();
        connectionsRemovedFacts.clear();
        bus.publish(new InputEvent.KeyPressed(CustomKeyCode.DELETE));

        var removedIds = removedFacts.get(0).objects().stream()
                .map(obj -> obj.id()).toList();
        assertThat(removedIds).containsExactlyInAnyOrder(w1.id(), w2.id(), label.id());
        assertThat(connectionsRemovedFacts).hasSize(1);
        assertThat(connectionsRemovedFacts.get(0).connections())
                .extracting(Connection::id).containsExactly(connId);
        // Endpunkte überleben; die Auswahl ist komplett gestaubt.
        assertThat(diagram.findById(k.id())).isPresent();
        assertThat(diagram.findById(r.id())).isPresent();
        assertThat(selection.selectedIds()).isEmpty();

        // Undo: Verbindung (mit waypointIds), Ws und Label kommen zurück —
        // die Inversen existierten bereits (RemovedObjects/RemovedConnections).
        bus.publish(new ActionEvent.RequestUndo());
        Connection restored = diagram.allConnections().stream()
                .filter(c -> c.id().equals(connId)).findFirst().orElseThrow();
        assertThat(restored.waypointIds()).containsExactly(w1.id(), w2.id());
        assertThat(diagram.findById(w1.id())).isPresent();
        assertThat(diagram.findById(w2.id())).isPresent();
        Text restoredLabel = textById(label.id());
        assertThat(restoredLabel.content()).isEqualTo("liefert");
        assertThat(restoredLabel.anchor()).isEqualTo(new TextAnchor(1, 0.5, 0.0, 15.0));
        assertThat(restoredLabel.y()).isEqualTo(115.0);
    }

    // ---------- Invariante: keine toten IDs in der Auswahl ----------

    @Test
    @DisplayName("Kaskadiert gelöschte Verbindung verlässt die Auswahl (Invariante: keine toten IDs)")
    void cascadedConnectionLeavesSelection() {
        click(250.0, 105.0, false);
        assertThat(selection.selectedIds()).contains(connId);

        // Endpunkt löschen -> Verbindung kaskadiert mit -> SelectionService
        // staubt auch über ConnectionsRemoved (neu), nicht nur ObjectsRemoved.
        bus.publish(new ActionEvent.DeleteObjects(List.of(k.id())));

        assertThat(selection.selectedIds()).isEmpty();
    }

    // ---------- Direktverbindung ohne Ws: Drag wirkungslos ----------

    @Test
    @DisplayName("Drag an einer selektierten Direktverbindung (ohne Ws) bewegt nichts")
    void draggingConnectionWithoutWaypointsDoesNothing() {
        Circle k2 = addCircle(500.0, 100.0);
        Rectangle r2 = addRectangle(700.0, 100.0);
        established.clear();
        bus.publish(new ActionEvent.CreateConnection(k2.id(), r2.id(), List.of()));

        click(600.0, 100.0, false);
        assertThat(selectionFacts.getLast().selectedIds()).hasSize(1);

        movedBatchFacts.clear();
        press(600.0, 100.0, false);
        bus.publish(new InputEvent.MouseDragged(600.0, 110.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseReleased(600.0, 110.0, CustomMouseButton.PRIMARY));

        assertThat(diagram.findById(k2.id()).orElseThrow().y()).isEqualTo(100.0);
        assertThat(diagram.findById(r2.id()).orElseThrow().y()).isEqualTo(100.0);
        assertThat(movedBatchFacts).isEmpty();
    }

    private void closeGesture() {
        bus.publish(new InputEvent.MouseReleased(0.0, 0.0, CustomMouseButton.PRIMARY));
    }
}
