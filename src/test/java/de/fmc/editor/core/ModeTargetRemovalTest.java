package de.fmc.editor.core;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.Waypoint;
import de.fmc.editor.core.service.DiagramService;
import de.fmc.editor.core.state.impl.HandleDragState;
import de.fmc.editor.core.state.impl.IdleToolState;
import de.fmc.editor.core.state.impl.ResizeModeState;
import de.fmc.editor.core.state.impl.RouteEditModeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sichert die Invariante „Ein Modus endet, wenn sein Ziel aus dem Modell
 * entfernt wird": Der Resize-Modus und der RouteEditMode halten ein Ziel-
 * Record, das Modell kann ihnen dieses Ziel unter den Füßen wegziehen —
 * der klassische Repro ist CTRL+Z (Undo der Erstellung) während der Modus
 * offen ist. Vor dem Fix sah die View ihre Griffe/W-Kreise weiter, weil der
 * modus-eigene null-Hinweis nur auf Interaktions-Pfaden (Klick daneben,
 * ESC, SelectTool) publiziert wurde — Modell-Fakten erreichten die Tool-
 * Machine gar nicht.
 *
 * Mechanismus: Der Bus dispatcht ObjectsRemoved/ConnectionsRemoved an die
 * Tool-Machine; die Modus-States beenden sich selbst über ihren null-Hinweis
 * (der Zustand IST das Flag — die View braucht keine eigene Pfad-Prüfung).
 */
class ModeTargetRemovalTest {

    private BusTestSupport support;
    private InteractionEventBus bus;
    private DiagramService diagram;
    private final List<ActionEvent.ResizeModeChanged> resizeHints = new ArrayList<>();
    private final List<ActionEvent.WaypointFocusChanged> focusHints = new ArrayList<>();

    @BeforeEach
    void setUp() {
        support = new BusTestSupport();
        bus = support.bus;
        diagram = support.diagram;
        bus.subscribe(ActionEvent.ResizeModeChanged.class, resizeHints::add);
        bus.subscribe(ActionEvent.WaypointFocusChanged.class, focusHints::add);
    }

    /** Doppelklick auf das Objekt-Zentrum betritt den Resize-Modus. */
    private void enterResizeMode(double x, double y) {
        bus.publish(new InputEvent.MousePressed(x, y, CustomMouseButton.PRIMARY, 2));
        assertThat(bus.getToolState()).isInstanceOf(ResizeModeState.class);
    }

    @Test
    @DisplayName("Undo der Erstellung beendet den Resize-Modus: null-Hinweis + Idle")
    void undoOfCreationExitsResizeMode() {
        Circle circle = support.addCircle(100.0, 100.0);
        enterResizeMode(100.0, 100.0);
        assertThat(resizeHints.getLast().target().id()).isEqualTo(circle.id());

        bus.publish(new ActionEvent.RequestUndo());

        assertThat(diagram.findById(circle.id())).isEmpty();
        assertThat(resizeHints.getLast().target()).isNull();
        assertThat(bus.getToolState()).isInstanceOf(IdleToolState.class);
    }

    @Test
    @DisplayName("Undo einer UNBEZOGENEN Erstellung lässt den Resize-Modus bestehen")
    void resizeModeSurvivesUnrelatedRemoval() {
        Circle target = support.addCircle(100.0, 100.0);
        support.closeGesture(); // Erstellungs-Eintrag abschließen
        Circle other = support.addCircle(300.0, 300.0);

        enterResizeMode(100.0, 100.0); // Grenze schließt den zweiten Eintrag
        int hintsBefore = resizeHints.size();

        bus.publish(new ActionEvent.RequestUndo());

        assertThat(diagram.findById(other.id())).isEmpty();
        assertThat(diagram.findById(target.id())).isPresent();
        assertThat(bus.getToolState()).isInstanceOf(ResizeModeState.class);
        assertThat(resizeHints).hasSize(hintsBefore); // kein Exit-Hinweis
    }

    @Test
    @DisplayName("Undo eines Moves hält den Resize-Modus — Griffe greifen an der wiederhergestellten Position")
    void resizeModeHandlesFollowUndoOfMove() {
        Circle circle = support.addCircle(100.0, 100.0);
        support.closeGesture(); // Erstellungs-Eintrag abschließen

        // Objekt bewegen (eigener Undo-Eintrag), dann den Modus am NEUEN Ort
        // betreten — das Entry-Record trägt fortan die Move-Geometrie.
        bus.publish(new ActionEvent.MoveObjects(List.of(circle.id()), 100.0, 0.0));
        enterResizeMode(200.0, 100.0);

        // Undo des Moves: Objekt zurück bei (100, 100), E-Griff bei (120, 100).
        // Der Modus überlebt (kein Entfernen), aber das Entry-Record ist altern.
        bus.publish(new ActionEvent.RequestUndo());
        assertThat(bus.getToolState()).isInstanceOf(ResizeModeState.class);

        // Der Griff muss an der wiederhergestellten Position GREIFEN — vor
        // dem Fix hitestete der Kern gegen die Alt-Geometrie (E-Griff bei
        // 220), der Press galt als „Klick daneben" und verließ still den
        // Modus: Griffe sichtbar (View liest frisch), aber nicht nutzbar.
        bus.publish(new InputEvent.MousePressed(120.0, 100.0, CustomMouseButton.PRIMARY, 1));
        assertThat(bus.getToolState()).isInstanceOf(HandleDragState.class);
    }

    @Test
    @DisplayName("Undo eines Resizes hält den Resize-Modus — Griffe greifen an der alten Größe")
    void resizeModeHandlesFollowUndoOfResize() {
        Circle circle = support.addCircle(100.0, 100.0);
        support.closeGesture();
        enterResizeMode(100.0, 100.0);

        // Eigener Resize-Commit (E-Griff 120 -> 135 => Radius 35); der
        // Rückkehr-State liest frisch, trägt also die r=35-Geometrie.
        bus.publish(new InputEvent.MousePressed(120.0, 100.0, CustomMouseButton.PRIMARY, 1));
        bus.publish(new InputEvent.MouseDragged(135.0, 100.0, CustomMouseButton.PRIMARY));
        bus.publish(new InputEvent.MouseReleased(135.0, 100.0, CustomMouseButton.PRIMARY));

        // Undo des Resizes: Radius zurück auf 20, E-Griff wieder bei (120, 100).
        bus.publish(new ActionEvent.RequestUndo());
        assertThat(bus.getToolState()).isInstanceOf(ResizeModeState.class);

        bus.publish(new InputEvent.MousePressed(120.0, 100.0, CustomMouseButton.PRIMARY, 1));
        assertThat(bus.getToolState()).isInstanceOf(HandleDragState.class);
    }

    @Test
    @DisplayName("Undo der Verbindungserstellung beendet den RouteEditMode: null-Hinweis + Idle")
    void undoOfConnectionExitsRouteEditMode() {
        Circle k = support.addCircle(100.0, 100.0);
        Rectangle r = support.addRectangle(300.0, 100.0);
        support.closeGesture(); // Erstellungs-Eintrag abschließen

        // Kette K(100,100) — W(100,60) — R(300,100); (200, 80) liegt auf dem
        // freien Segment W→R. CTRL+Klick dort betritt den Modus (und schließt
        // zugleich den Verbindungs-Eintrag als Gesten-Grenze).
        Waypoint w = new Waypoint(UUID.randomUUID(), 100.0, 60.0, support.defaultLayerId);
        bus.publish(new ActionEvent.CreateConnection(k.id(), r.id(), List.of(w)));
        bus.publish(new InputEvent.MousePressed(200.0, 80.0, CustomMouseButton.PRIMARY, 1, true));
        bus.publish(new InputEvent.MouseReleased(200.0, 80.0, CustomMouseButton.PRIMARY));
        assertThat(bus.getToolState()).isInstanceOf(RouteEditModeState.class);

        bus.publish(new ActionEvent.RequestUndo());

        assertThat(diagram.allConnections()).isEmpty();
        assertThat(focusHints.getLast().connectionId()).isNull();
        assertThat(bus.getToolState()).isInstanceOf(IdleToolState.class);
        // Nur die Verbindung ist rückgängig: Die Endpunkte überleben.
        assertThat(diagram.findById(k.id())).isPresent();
        assertThat(diagram.findById(r.id())).isPresent();
    }
}
