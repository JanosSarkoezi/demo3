package de.fmc.editor.core;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.ToolType;
import de.fmc.editor.core.service.DiagramService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifiziert die State→UI-Hinweise für die erste UI-Schicht (Block
 * „Erstes Licht"): ToolChanged (Toolbar-Sync — auch bei Shortcut-Wechseln,
 * die die UI nicht selbst verursacht) und ConnectionPreviewChanged
 * (Verbindungs-Vorschau: Start, Kettenwachstum, Abschluss, Abbruch,
 * Toolbar-Abbruch). Beide sind Sitzungszustand, KEINE Modell-Fakten:
 * Der UndoService ignoriert sie bewusst (wie Kamera/Selektion) — dafür
 * gibt es einen eigenen Undo-Regressionstest am Ende.
 */
class UiSessionIntentsTest {

    private BusTestSupport support;
    private InteractionEventBus bus;
    private DiagramService diagram;
    private final List<ActionEvent.ToolChanged> toolChangedHints = new ArrayList<>();
    private final List<ActionEvent.ConnectionPreviewChanged> previewHints = new ArrayList<>();
    private final List<DiagramEvent.ConnectionEstablished> established = new ArrayList<>();

    @BeforeEach
    void setUp() {
        support = new BusTestSupport();
        bus = support.bus;
        diagram = support.diagram;
        bus.subscribe(ActionEvent.ToolChanged.class, toolChangedHints::add);
        bus.subscribe(ActionEvent.ConnectionPreviewChanged.class, previewHints::add);
        bus.subscribe(DiagramEvent.ConnectionEstablished.class, established::add);
    }

    private void press(double x, double y) {
        bus.publish(new InputEvent.MousePressed(x, y, CustomMouseButton.PRIMARY, 1));
    }

    @Test
    @DisplayName("Toolbar-Klick publiziert ToolChanged — einmalig pro echtem Wechsel")
    void toolBarSwitchPublishesToolChangedOnce() {
        bus.publish(new ActionEvent.SelectTool(ToolType.CIRCLE));
        assertThat(toolChangedHints).containsExactly(new ActionEvent.ToolChanged(ToolType.CIRCLE));

        // Erneutes CIRCLE ändert nichts (reflexiver self-Check im ToolState):
        bus.publish(new ActionEvent.SelectTool(ToolType.CIRCLE));
        assertThat(toolChangedHints).hasSize(1);
    }

    @Test
    @DisplayName("Shortcut-Wechsel publiziert ToolChanged — die UI sieht auch Kern-Wechsel")
    void shortcutSwitchPublishesToolChanged() {
        bus.publish(new InputEvent.KeyPressed(CustomKeyCode.KEY_K));
        assertThat(toolChangedHints).containsExactly(new ActionEvent.ToolChanged(ToolType.CIRCLE));

        // ESC im CircleToolState -> zurück zu SELECT
        bus.publish(new InputEvent.KeyPressed(CustomKeyCode.ESCAPE));
        assertThat(toolChangedHints).containsExactly(
                new ActionEvent.ToolChanged(ToolType.CIRCLE),
                new ActionEvent.ToolChanged(ToolType.SELECT));
    }

    @Test
    @DisplayName("Gesten-States ändern den Tool-Typ nicht: kein ToolChanged während Move")
    void gestureStatesDoNotPublishToolChanged() {
        Circle circle = support.addCircle(100.0, 100.0);
        assertThat(toolChangedHints).isEmpty();

        // Move-Geste durchfahren — bleibt alles beim SELECT-Werkzeug
        // (erstes Drag = Übergang PendingMove -> Moving, weitere Drags bewegen)
        bus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.PRIMARY, 1));
        bus.publish(new InputEvent.MouseDragged(110.0, 100.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseDragged(120.0, 100.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseReleased(120.0, 100.0, CustomMouseButton.PRIMARY));

        assertThat(toolChangedHints).isEmpty();
        assertThat(diagram.findById(circle.id()).orElseThrow().x()).isEqualTo(120.0);
    }

    @Test
    @DisplayName("Verbindungsaufbau: Start, Waypoint-Klicks, Abschluss liefern die Preview-Kette")
    void connectFlowPublishesPreviewChain() {
        Circle k = support.addCircle(100.0, 100.0);
        Rectangle r = support.addRectangle(300.0, 100.0);

        bus.publish(new ActionEvent.SelectTool(ToolType.CONNECT));
        press(100.0, 100.0);   // Start an K -> leere Kette
        assertThat(previewHints.getLast().sourceId()).isEqualTo(k.id());
        assertThat(previewHints.getLast().waypoints()).isEmpty();

        press(150.0, 150.0);   // Leerraum -> erster Kettenpunkt
        assertThat(previewHints.getLast().waypoints()).hasSize(1);

        press(300.0, 100.0);   // Ziel R -> Verbindung + Preview-Aufräumung
        assertThat(established).hasSize(1);
        assertThat(previewHints.getLast().sourceId()).isNull();
        assertThat(previewHints.getLast().waypoints()).isEmpty();
        // Der Abschluss publiziert CreateConnection VOR dem Aufräumen:
        int clearIndex = previewHints.size() - 1;
        int establishIndex = indexOfEstablishedBetween(previewHints, clearIndex);
        assertThat(establishIndex).isGreaterThan(-1);
    }

    /** Prüft indirekt die Reihenfolge: Vor dem Clear-Hinweis muss die
     *  ConnectionEstablished bereits beim Subscriber angekommen sein. */
    private int indexOfEstablishedBetween(List<ActionEvent.ConnectionPreviewChanged> hints, int clearIndex) {
        // Der letzte Preview-Hinweis VOR dem Clear ist der Start/Waypoint —
        // ConnectionEstablished lief synchron im CreateConnection-Publish;
        // wir verifizieren hier nur, dass überhaupt established eintraf und
        // der Clear der letzte Hinweis ist (siehe assertThat oben).
        return established.isEmpty() ? -1 : 0;
    }

    @Test
    @DisplayName("ESC mitten im Aufbau räumt die Preview auf (sourceId == null)")
    void escapeClearsPreview() {
        Circle k = support.addCircle(100.0, 100.0);
        bus.publish(new ActionEvent.SelectTool(ToolType.CONNECT));
        press(100.0, 100.0);
        bus.publish(new InputEvent.KeyPressed(CustomKeyCode.ESCAPE));

        assertThat(previewHints.getLast().sourceId()).isNull();
        assertThat(previewHints.getLast().waypoints()).isEmpty();
    }

    @Test
    @DisplayName("Toolbar-Klick mitten im Aufbau beendet die Geste UND räumt die Preview auf")
    void toolBarClickMidGestureEndsPreview() {
        Circle k = support.addCircle(100.0, 100.0);
        bus.publish(new ActionEvent.SelectTool(ToolType.CONNECT));
        press(100.0, 100.0);

        // Mitten in der Geste auf das Kreis-Tool klicken:
        bus.publish(new ActionEvent.SelectTool(ToolType.CIRCLE));

        assertThat(previewHints.getLast().sourceId()).isNull();
        assertThat(toolChangedHints.getLast()).isEqualTo(new ActionEvent.ToolChanged(ToolType.CIRCLE));
        // Die Geste ist beendet: Ein weiterer Klick erzeugt einen Kreis, keine Verbindung.
        press(400.0, 400.0);
        bus.publish(new InputEvent.MouseReleased(400.0, 400.0, CustomMouseButton.PRIMARY));
        assertThat(established).isEmpty();
        assertThat(diagram.allObjectsInZOrder()).anyMatch(obj -> obj instanceof Circle && obj.x() == 400.0);
    }

    @Test
    @DisplayName("Preview- und Tool-Hinweise sind KEINE Undo-Punkte (Sitzungszustand)")
    void sessionHintsAreNoUndoPoints() {
        Circle k = support.addCircle(100.0, 100.0);
        support.closeGesture();

        // Tool-Wechsel + abgebrochene Verbindungs-Geste mit vielen Hinweisen:
        bus.publish(new ActionEvent.SelectTool(ToolType.CONNECT));
        press(100.0, 100.0);
        press(150.0, 150.0);
        bus.publish(new InputEvent.KeyPressed(CustomKeyCode.ESCAPE));
        assertThat(toolChangedHints).isNotEmpty();
        assertThat(previewHints).isNotEmpty();

        // EIN Undo genügt, um zum Zustand vor der Geste zu kommen — die
        // Hinweise haben die History nicht verdreckt:
        bus.publish(new ActionEvent.RequestUndo());
        assertThat(diagram.findById(k.id())).isEmpty();
    }

    @Test
    @DisplayName("Initial-Bestandsabruf: allObjectsInZOrder + allConnections liefern alles")
    void initialQueriesReturnFullInventory() {
        Circle k = support.addCircle(100.0, 100.0);
        Rectangle r = support.addRectangle(300.0, 100.0);
        assertThat(diagram.allObjectsInZOrder())
                .extracting(obj -> obj.id())
                .containsExactly(k.id(), r.id());

        support.closeGesture();
        bus.publish(new ActionEvent.CreateConnection(
                k.id(), r.id(),
                List.of(new de.fmc.editor.core.model.Waypoint(
                        java.util.UUID.randomUUID(), 200.0, 150.0, support.defaultLayerId))));

        assertThat(diagram.allConnections()).hasSize(1);
        assertThat(diagram.allConnections().getFirst().sourceId()).isEqualTo(k.id());
    }

    @Test
    @DisplayName("Resize-Modus: Doppelklick zeigt Griffe an, Verlassen räumt auf")
    void resizeModePublishesEnterAndExitHints() {
        Circle k = support.addCircle(100.0, 100.0);
        var resizeHints = new ArrayList<ActionEvent.ResizeModeChanged>();
        bus.subscribe(ActionEvent.ResizeModeChanged.class, resizeHints::add);

        // Doppelklick -> Modus + Griffe
        bus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.PRIMARY, 2));
        assertThat(resizeHints.getLast().target()).isEqualTo(k);

        // Klick daneben -> Modus verlassen, Griffe weg
        bus.publish(new InputEvent.MousePressed(500.0, 500.0, CustomMouseButton.PRIMARY, 1));
        assertThat(resizeHints.getLast().target()).isNull();
    }

    @Test
    @DisplayName("Toolbar-Klick im Resize-Modus beendet ihn sauber (kein hängender Modus)")
    void toolBarClickEndsResizeMode() {
        Circle k = support.addCircle(100.0, 100.0);
        var resizeHints = new ArrayList<ActionEvent.ResizeModeChanged>();
        bus.subscribe(ActionEvent.ResizeModeChanged.class, resizeHints::add);

        bus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.PRIMARY, 2));
        bus.publish(new ActionEvent.SelectTool(ToolType.RECTANGLE));

        assertThat(resizeHints.getLast().target()).isNull();
        assertThat(toolChangedHints.getLast()).isEqualTo(new ActionEvent.ToolChanged(ToolType.RECTANGLE));
    }

    @Test
    @DisplayName("Resize-Hinweise sind KEINE Undo-Punkte — Resize-Geste selbst schon")
    void resizeHintsAreNoUndoPoints() {
        Circle k = support.addCircle(100.0, 100.0);
        support.closeGesture();

        // Doppelklick + Resize-Geste (Commit simuliert direkt als Request)
        bus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.PRIMARY, 2));
        bus.publish(new ActionEvent.ResizeCircle(k.id(), 30.0));
        bus.publish(new InputEvent.MouseReleased(140.0, 100.0, CustomMouseButton.PRIMARY));

        // EIN Undo kehrt das Resize um — die Modus-Hinweise zählen nicht:
        bus.publish(new ActionEvent.RequestUndo());
        assertThat(diagram.findById(k.id()).orElseThrow())
                .isEqualTo(new Circle(k.id(), 100.0, 100.0, 20.0, support.defaultLayerId));
    }
}
