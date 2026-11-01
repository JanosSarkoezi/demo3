package de.fmc.editor.core;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.Waypoint;
import de.fmc.editor.core.service.DiagramService;
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
 * Verifiziert den RouteEditMode (Block 2): Klick auf die Verbindungskette
 * blendet die Ws ein (Hinweis) und macht sie greifbar — der Zustand IST das
 * Flag: Der Idle filtert Ws aus seinen Hit-Ergebnissen, der Modus nicht.
 * - Einstieg per Klick auf die Kette (Toleranz in Bildschirm-Pixeln / Zoom),
 * - Ws einzeln (Klick) oder mehrfach (Ctrl+Klick, Gummiband MIT Ws) greifbar,
 * - Move-Gruppe über dieselbe findMoveSet-Maschinerie,
 * - Austritt per Klick daneben (Auswahl leer), ESC (nicht-W-Auswahl bleibt),
 *   Klick auf Objekt (Idle-Semantik) oder Toolbar-Wechsel,
 * - DEL löscht die Auswahl; lebt die Verbindung danach nicht mehr, endet der
 *   Modus mit ihr,
 * - Fokus-Wechsel per Klick auf eine andere Verbindung,
 * - Band-Vorschau-Hinweise (Aktivierung, Nachführen, Aufräumen) — Sitzung.
 */
class RouteEditModeTest {

    private BusTestSupport support;
    private InteractionEventBus bus;
    private DiagramService diagram;
    private final List<ActionEvent.WaypointFocusChanged> focusHints = new ArrayList<>();
    private final List<ActionEvent.BoxSelectPreviewChanged> bandHints = new ArrayList<>();
    private final List<DiagramEvent.SelectionChanged> selectionFacts = new ArrayList<>();
    private final List<DiagramEvent.ConnectionEstablished> established = new ArrayList<>();

    /** Szenario: K(100,100) — W1(100,60) — W2(300,60) — R(300,100).
     *  Freie Kettenmitte: (200, 60) liegt auf dem Segment W1→W2, kein Objekt. */
    private Circle k;
    private Rectangle r;
    private Waypoint w1;
    private Waypoint w2;
    private UUID connId;

    @BeforeEach
    void setUp() {
        support = new BusTestSupport();
        bus = support.bus;
        diagram = support.diagram;
        bus.subscribe(ActionEvent.WaypointFocusChanged.class, focusHints::add);
        bus.subscribe(ActionEvent.BoxSelectPreviewChanged.class, bandHints::add);
        bus.subscribe(DiagramEvent.SelectionChanged.class, selectionFacts::add);
        bus.subscribe(DiagramEvent.ConnectionEstablished.class, established::add);

        k = support.addCircle(100.0, 100.0);
        r = support.addRectangle(300.0, 100.0);
        w1 = new Waypoint(UUID.randomUUID(), 100.0, 60.0, support.defaultLayerId);
        w2 = new Waypoint(UUID.randomUUID(), 300.0, 60.0, support.defaultLayerId);
        bus.publish(new ActionEvent.CreateConnection(k.id(), r.id(), List.of(w1, w2)));
        connId = established.getLast().connection().id();
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

    /** Modus-Eintritt: CTRL+Klick auf die freie Kettenmitte (200, 60)
     *  (seit 03.11.: Plain-Klick selektiert nur, CTRL+Klick betritt). */
    private void enterMode() {
        click(200.0, 60.0, true);
        assertThat(bus.getToolState()).isInstanceOf(RouteEditModeState.class);
    }

    @Test
    @DisplayName("CTRL+Klick auf die Verbindungskette betritt den Modus — ohne Auswahl-Änderung")
    void ctrlClickEntersMode() {
        int factsBefore = selectionFacts.size();

        click(200.0, 60.0, true);

        assertThat(focusHints.getLast().connectionId()).isEqualTo(connId);
        assertThat(selectionFacts).hasSize(factsBefore);
        assertThat(bus.getToolState()).isInstanceOf(RouteEditModeState.class);
    }

    @Test
    @DisplayName("Klick-Toleranz: CTRL+Klick 6 px neben der Kette reicht, 20 px nicht")
    void clickToleranceInScreenPixels() {
        // 6 px unter dem Segment W1→W2 (y = 60): innerhalb der 8-px-Toleranz
        click(200.0, 66.0, true);
        assertThat(bus.getToolState()).isInstanceOf(RouteEditModeState.class);

        // Modus verlassen und 20 px daneben klicken: keinerlei Modus-Eintritt
        bus.publish(new InputEvent.KeyPressed(CustomKeyCode.ESCAPE));
        click(200.0, 80.0, true);
        assertThat(focusHints.getLast().connectionId()).isNull();
        assertThat(bus.getToolState()).isInstanceOf(IdleToolState.class);
    }

    @Test
    @DisplayName("Klick auf ein W im Modus selektiert es exklusiv — Modus bleibt")
    void waypointClickSelectsExclusively() {
        enterMode();

        click(100.0, 60.0, false);

        assertThat(selectionFacts.getLast().selectedIds()).containsExactly(w1.id());
        assertThat(bus.getToolState()).isInstanceOf(RouteEditModeState.class);
    }

    @Test
    @DisplayName("Ctrl+Klick nimmt ein zweites W in die Auswahl auf (Multi-Selektion)")
    void ctrlClickTogglesSecondWaypoint() {
        enterMode();

        click(100.0, 60.0, false);
        click(300.0, 60.0, true);

        assertThat(selectionFacts.getLast().selectedIds())
                .containsExactly(w1.id(), w2.id());
        assertThat(bus.getToolState()).isInstanceOf(RouteEditModeState.class);
    }

    @Test
    @DisplayName("Multi-Move zweier Ws im Modus — dieselbe MoveObjects-Maschinerie")
    void multiMoveOfTwoWaypoints() {
        enterMode();
        click(100.0, 60.0, false);
        click(300.0, 60.0, true);

        // Drag am ersten W: die ganze Auswahl (beide Ws) wandert
        bus.publish(new InputEvent.MousePressed(100.0, 60.0, CustomMouseButton.PRIMARY, 1));
        bus.publish(new InputEvent.MouseDragged(120.0, 60.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseDragged(140.0, 60.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseReleased(140.0, 60.0, CustomMouseButton.PRIMARY));

        assertThat(diagram.findById(w1.id()).orElseThrow().x()).isEqualTo(140.0);
        assertThat(diagram.findById(w2.id()).orElseThrow().x()).isEqualTo(340.0);
        assertThat(diagram.findById(k.id()).orElseThrow().x()).isEqualTo(100.0);
        assertThat(diagram.findById(r.id()).orElseThrow().x()).isEqualTo(300.0);
        assertThat(bus.getToolState()).isInstanceOf(RouteEditModeState.class);
    }

    @Test
    @DisplayName("Klick auf K/R verlässt den Modus und wählt exklusiv (Idle-Semantik)")
    void objectClickLeavesMode() {
        enterMode();

        click(100.0, 100.0, false);

        assertThat(focusHints.getLast().connectionId()).isNull();
        assertThat(selectionFacts.getLast().selectedIds()).containsExactly(k.id());
        assertThat(bus.getToolState()).isInstanceOf(IdleToolState.class);
    }

    @Test
    @DisplayName("Klick daneben verlässt den Modus und leert die Auswahl")
    void emptyClickLeavesModeAndClearsSelection() {
        enterMode();
        click(100.0, 60.0, false);   // W1 selektiert

        click(500.0, 400.0, false);  // weit weg von jeder Kette

        assertThat(focusHints.getLast().connectionId()).isNull();
        assertThat(selectionFacts.getLast().selectedIds()).isEmpty();
        assertThat(bus.getToolState()).isInstanceOf(IdleToolState.class);
    }

    @Test
    @DisplayName("ESC beendet den Modus, behält aber die nicht-W-Auswahl")
    void escapeKeepsNonWaypointSelection() {
        enterMode();

        // Band aus dem Modus: gemischte Auswahl [K, R, W1, W2] — Modus bleibt
        bus.publish(new InputEvent.MousePressed(50.0, 30.0, CustomMouseButton.PRIMARY, 1, true));
        bus.publish(new InputEvent.MouseDragged(350.0, 130.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseReleased(350.0, 130.0, CustomMouseButton.PRIMARY));
        assertThat(selectionFacts.getLast().selectedIds())
                .containsExactly(k.id(), r.id(), w1.id(), w2.id());

        bus.publish(new InputEvent.KeyPressed(CustomKeyCode.ESCAPE));

        // Ws fliegen aus der Auswahl (unsichtbar Selektierte wären heimliche
        // Move-Mitglieder), K und R bleiben:
        assertThat(focusHints.getLast().connectionId()).isNull();
        assertThat(selectionFacts.getLast().selectedIds())
                .containsExactly(k.id(), r.id());
        assertThat(bus.getToolState()).isInstanceOf(IdleToolState.class);
    }

    @Test
    @DisplayName("DEL im Modus löscht das selektierte W — Verbindung überlebt (Prune), Modus bleibt")
    void deleteWaypointPrunesConnectionAndKeepsMode() {
        enterMode();
        click(100.0, 60.0, false);   // W1 selektiert

        int hintsBefore = focusHints.size();
        bus.publish(new InputEvent.KeyPressed(CustomKeyCode.DELETE));

        assertThat(diagram.findById(w1.id())).isEmpty();
        assertThat(diagram.allConnections().getFirst().waypointIds())
                .doesNotContain(w1.id());
        // Die Verbindung lebt -> kein Aufräum-Hinweis, Modus bleibt:
        assertThat(focusHints).hasSize(hintsBefore);
        assertThat(bus.getToolState()).isInstanceOf(RouteEditModeState.class);
    }

    @Test
    @DisplayName("Undo des W-Löschens stellt W UND seinen Eintrag in der Verbindung her")
    void undoRestoresDeletedWaypointIntoConnection() {
        enterMode();
        click(100.0, 60.0, false);   // W1 selektiert
        bus.publish(new InputEvent.KeyPressed(CustomKeyCode.DELETE));
        assertThat(diagram.findById(w1.id())).isEmpty();

        bus.publish(new ActionEvent.RequestUndo());

        // Kern-Vertrag (die View leitet ihren Pfad hieraus ab): Das W ist
        // zurück UND die Verbindung referenziert es wieder. Der UndoService
        // spielt dabei AddConnection(previous) VOR RestoreObjects — die View
        // muss deshalb beim Erscheinen des W ihre Connections neu ableiten
        // (erster Refresh lief mit fehlendem W, siehe GraphView.addObjectNode).
        assertThat(diagram.findById(w1.id())).isPresent();
        assertThat(diagram.allConnections().getFirst().waypointIds())
                .contains(w1.id());

        // Und Redo bringt den Prune zurück:
        bus.publish(new ActionEvent.RequestRedo());
        assertThat(diagram.findById(w1.id())).isEmpty();
        assertThat(diagram.allConnections().getFirst().waypointIds())
                .doesNotContain(w1.id());
    }

    @Test
    @DisplayName("Klick auf eine andere Verbindung wechselt den Fokus — Modus bleibt")
    void clickOnOtherConnectionSwitchesFocus() {
        Circle k2 = support.addCircle(500.0, 100.0);
        Rectangle r2 = support.addRectangle(700.0, 100.0);
        bus.publish(new ActionEvent.CreateConnection(k2.id(), r2.id(), List.of()));
        UUID conn2Id = established.getLast().connection().id();

        enterMode();

        click(600.0, 100.0, false);  // freie Mitte der zweiten Kette

        assertThat(focusHints.getLast().connectionId()).isEqualTo(conn2Id);
        assertThat(bus.getToolState()).isInstanceOf(RouteEditModeState.class);
    }

    @Test
    @DisplayName("Gummiband aus dem Modus schließt die Ws ein — Modus bleibt")
    void bandFromModeIncludesWaypoints() {
        enterMode();

        // Ctrl+Drag über die ganze Route
        bus.publish(new InputEvent.MousePressed(50.0, 30.0, CustomMouseButton.PRIMARY, 1, true));
        bus.publish(new InputEvent.MouseDragged(350.0, 130.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseReleased(350.0, 130.0, CustomMouseButton.PRIMARY));

        // z-Ordnung: K, R, W1, W2 (Ws wurden mit der Verbindung zuletzt angelegt)
        assertThat(selectionFacts.getLast().selectedIds())
                .containsExactly(k.id(), r.id(), w1.id(), w2.id());
        assertThat(bus.getToolState()).isInstanceOf(RouteEditModeState.class);
    }

    @Test
    @DisplayName("Band-Vorschau: Aktivierung, Nachführen, Aufräumen beim Release")
    void bandPublishesPreviewHints() {
        enterMode();

        bus.publish(new InputEvent.MousePressed(50.0, 30.0, CustomMouseButton.PRIMARY, 1, true));
        bus.publish(new InputEvent.MouseDragged(200.0, 50.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseDragged(350.0, 130.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseReleased(350.0, 130.0, CustomMouseButton.PRIMARY));

        assertThat(bandHints).hasSize(3);
        assertThat(bandHints.get(0))
                .isEqualTo(new ActionEvent.BoxSelectPreviewChanged(50.0, 30.0, 200.0, 50.0));
        assertThat(bandHints.get(1))
                .isEqualTo(new ActionEvent.BoxSelectPreviewChanged(50.0, 30.0, 350.0, 130.0));
        assertThat(bandHints.get(2).startX()).isNull();
    }

    @Test
    @DisplayName("ESC während des Bands räumt die Vorschau auf, Auswahl bleibt")
    void escapeDuringBandCleansPreview() {
        enterMode();
        click(100.0, 60.0, false);   // W1 selektiert
        int selectionFactsBefore = selectionFacts.size();

        bus.publish(new InputEvent.MousePressed(500.0, 400.0, CustomMouseButton.PRIMARY, 1, true));
        bus.publish(new InputEvent.MouseDragged(600.0, 450.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.KeyPressed(CustomKeyCode.ESCAPE));

        assertThat(bandHints.getLast().startX()).isNull();
        assertThat(selectionFacts).hasSize(selectionFactsBefore); // Abbruch ohne Event
        assertThat(bus.getToolState()).isInstanceOf(RouteEditModeState.class);
    }

    @Test
    @DisplayName("Toolbar-Wechsel beendet den Modus sauber (kein hängender Fokus)")
    void toolSwitchEndsMode() {
        enterMode();

        bus.publish(new ActionEvent.SelectTool(de.fmc.editor.core.model.ToolType.CIRCLE));

        assertThat(focusHints.getLast().connectionId()).isNull();
        assertThat(bus.getToolState())
                .isInstanceOf(de.fmc.editor.core.state.impl.CircleToolState.class);
    }

    @Test
    @DisplayName("W-Move im Modus ist EIN Undo-Punkt — Fokus-Hinweise zählen nicht")
    void waypointMoveIsSingleUndoPoint() {
        enterMode();
        support.closeGesture();

        click(100.0, 60.0, false);
        bus.publish(new InputEvent.MousePressed(100.0, 60.0, CustomMouseButton.PRIMARY, 1));
        bus.publish(new InputEvent.MouseDragged(120.0, 60.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseDragged(140.0, 60.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseReleased(140.0, 60.0, CustomMouseButton.PRIMARY));
        assertThat(diagram.findById(w1.id()).orElseThrow().x()).isEqualTo(140.0);

        // EIN Undo kehrt den Move um — Modus-Eintritt und Hinweise haben die
        // History nicht verdreckt:
        bus.publish(new ActionEvent.RequestUndo());
        assertThat(diagram.findById(w1.id()).orElseThrow().x()).isEqualTo(100.0);
    }

    // -----------------------------------------------------------------
    // Doppelklick auf ein Segment -> Kettenpunkt einfügen (Posten 7)
    // -----------------------------------------------------------------

    /** Doppelklick-Geste: Press(1) selektiert die Verbindung (Plain-Klick),
     *  Press(2) betritt den Modus und führt die Einfüge-Semantik aus
     *  (bzw. selektiert ein bestehendes W nur). */
    private void doubleClick(double x, double y) {
        press(x, y, false);
        release(x, y);
        bus.publish(new InputEvent.MousePressed(x, y, CustomMouseButton.PRIMARY, 2));
        bus.publish(new InputEvent.MouseReleased(x, y, CustomMouseButton.PRIMARY));
    }

    private List<UUID> waypointIdsOfFocusedConnection() {
        return diagram.allConnections().stream()
                .filter(c -> c.id().equals(connId))
                .findFirst().orElseThrow()
                .waypointIds();
    }

    @Test
    @DisplayName("Doppelklick auf Segment K→W1 fügt VOR dem ersten W ein (Index 0)")
    void doubleClickOnFirstSegmentInsertsBeforeFirstWaypoint() {
        doubleClick(100.0, 72.0);   // freies Stück des Segments K(100,100)→W1(100,60)

        List<UUID> ids = waypointIdsOfFocusedConnection();
        assertThat(ids).hasSize(3);
        UUID inserted = ids.get(0);
        assertThat(inserted).isNotEqualTo(w1.id()).isNotEqualTo(w2.id());
        var newW = (de.fmc.editor.core.model.Waypoint)
                diagram.findById(inserted).orElseThrow();
        assertThat(newW.x()).isEqualTo(100.0);
        assertThat(newW.y()).isEqualTo(72.0);
        // Ketten-Reihenfolge: [neu, W1, W2]
        assertThat(ids.get(1)).isEqualTo(w1.id());
        assertThat(ids.get(2)).isEqualTo(w2.id());
        assertThat(bus.getToolState()).isInstanceOf(RouteEditModeState.class);
    }

    @Test
    @DisplayName("Doppelklick auf Segment W1→W2 fügt ZWISCHEN den Ws ein (Index 1)")
    void doubleClickOnMiddleSegmentInsertsBetweenWaypoints() {
        doubleClick(200.0, 60.0);

        List<UUID> ids = waypointIdsOfFocusedConnection();
        assertThat(ids).containsExactly(w1.id(), ids.get(1), w2.id());
        assertThat(ids.get(1)).isNotEqualTo(w1.id()).isNotEqualTo(w2.id());
        var newW = (de.fmc.editor.core.model.Waypoint)
                diagram.findById(ids.get(1)).orElseThrow();
        assertThat(newW.x()).isEqualTo(200.0);
        assertThat(newW.y()).isEqualTo(60.0);
    }

    @Test
    @DisplayName("Doppelklick auf Segment W2→R hängt den Kettenpunkt AN (letzter Index)")
    void doubleClickOnLastSegmentAppendsWaypoint() {
        doubleClick(300.0, 72.0);   // freies Stück des Segments W2(300,60)→R(300,100)

        List<UUID> ids = waypointIdsOfFocusedConnection();
        assertThat(ids).containsExactly(w1.id(), w2.id(), ids.get(2));
        assertThat(ids.get(2)).isNotEqualTo(w1.id()).isNotEqualTo(w2.id());
        var newW = (de.fmc.editor.core.model.Waypoint)
                diagram.findById(ids.get(2)).orElseThrow();
        assertThat(newW.x()).isEqualTo(300.0);
        assertThat(newW.y()).isEqualTo(72.0);
    }

    @Test
    @DisplayName("Doppelklick auf DIREKTE Verbindung K↔R (ohne Ws) fügt das erste W ein")
    void doubleClickOnDirectConnectionInsertsFirstWaypoint() {
        Circle k2 = support.addCircle(500.0, 100.0);
        Rectangle r2 = support.addRectangle(700.0, 100.0);
        bus.publish(new ActionEvent.CreateConnection(k2.id(), r2.id(), List.of()));
        UUID conn2Id = established.getLast().connection().id();

        // Doppelklick auf die freie Mitte der Direktverbindung — aus dem
        // Idle heraus in EINER Geste: Press(1) selektiert, Press(2) betritt
        // den Modus und fügt ein.
        doubleClick(600.0, 100.0);

        var conn2 = diagram.allConnections().stream()
                .filter(c -> c.id().equals(conn2Id))
                .findFirst().orElseThrow();
        assertThat(conn2.waypointIds()).hasSize(1);
        var newW = (de.fmc.editor.core.model.Waypoint)
                diagram.findById(conn2.waypointIds().getFirst()).orElseThrow();
        assertThat(newW.x()).isEqualTo(600.0);
        assertThat(newW.y()).isEqualTo(100.0);
        // Eingefügt wurde in die nächste Verbindung (conn2), nicht in conn:
        assertThat(waypointIdsOfFocusedConnection()).hasSize(2);
    }

    @Test
    @DisplayName("Doppelklick auf ein BESTEHENDES W selektiert nur — kein Einfügen")
    void doubleClickOnExistingWaypointStillSelectsOnly() {
        doubleClick(100.0, 60.0);   // auf W1

        assertThat(selectionFacts.getLast().selectedIds()).containsExactly(w1.id());
        assertThat(waypointIdsOfFocusedConnection())
                .containsExactly(w1.id(), w2.id());
        // Kein drittes W entstanden:
        assertThat(diagram.allObjectsInZOrder())
                .filteredOn(obj -> obj instanceof de.fmc.editor.core.model.Waypoint)
                .hasSize(2);
    }

    @Test
    @DisplayName("Undo des Einfügens entfernt W und stellt die Connection her; Redo kehrt zurück")
    void undoRemovesInsertedWaypointAndRestoresConnection() {
        doubleClick(200.0, 60.0);   // Index 1: [W1, neu, W2]
        UUID inserted = waypointIdsOfFocusedConnection().get(1);
        assertThat(diagram.findById(inserted)).isPresent();

        bus.publish(new ActionEvent.RequestUndo());

        assertThat(diagram.findById(inserted)).isEmpty();
        assertThat(waypointIdsOfFocusedConnection())
                .containsExactly(w1.id(), w2.id());

        bus.publish(new ActionEvent.RequestRedo());

        // Redo replayt dasselbe Record — gleiche UUID, gleiche Stelle:
        assertThat(waypointIdsOfFocusedConnection())
                .containsExactly(w1.id(), inserted, w2.id());
        assertThat(diagram.findById(inserted)).isPresent();
    }

    @Test
    @DisplayName("Einfügen selektiert nie das neue W — Press(1) trägt nur die Verbindung")
    void insertionNeverSelectsTheNewWaypoint() {
        int factsBefore = selectionFacts.size();

        doubleClick(200.0, 60.0);

        // Genau EIN SelectionChanged: Der Press(1) der Doppelklick-Geste hat
        // die Verbindung selektiert (Plain-Klick-Semantik seit 03.11.) — das
        // NEUE W wird bewusst NICHT auto-selektiert: Sichtbar = greifbar,
        // der nächste Press erledigt die Selektion.
        assertThat(selectionFacts).hasSize(factsBefore + 1);
        assertThat(selectionFacts.getLast().selectedIds()).containsExactly(connId);
    }

    @Test
    @DisplayName("AddWaypoint für unbekannte Verbindung ist ein stiller No-Op")
    void addWaypointForUnknownConnectionIsSilentNoOp() {
        int objectsBefore = diagram.allObjectsInZOrder().size();

        bus.publish(new ActionEvent.AddWaypoint(UUID.randomUUID(),
                new de.fmc.editor.core.model.Waypoint(
                        UUID.randomUUID(), 200.0, 60.0, support.defaultLayerId)));

        assertThat(diagram.allObjectsInZOrder()).hasSize(objectsBefore);
    }
}
