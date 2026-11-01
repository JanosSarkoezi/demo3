package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.Waypoint;
import de.fmc.editor.core.state.InteractionContext;
import de.fmc.editor.core.state.rule.BaseState;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * RouteEditMode für genau EINE Verbindung (Einstieg per Klick auf die
 * Verbindungskette aus dem {@link IdleToolState} — der graue Hover-Glow
 * zeigt den Hotspot an). Die W-Kreise der Verbindung werden über den
 * {@link ActionEvent.WaypointFocusChanged}-Hinweis sichtbar und sind damit
 * greifbar: einzeln per Klick, mehrfach per Ctrl+Klick oder Gummiband
 * (das Band aus diesem Modus schließt Ws ein).
 *
 * Der Zustand IST das Flag: {@link IdleToolState} filtert Ws aus seinen
 * Hit-Ergebnissen heraus (unsichtbare Objekte sind keine Klick-Ziele),
 * dieser Zustand eben nicht — Sichtbarkeit (UI) und Klick-Semantik (Kern)
 * werden von demselben Hinweis getragen, ohne gemeinsamen Sitzungsspeicher.
 *
 * Verlassen wird der Modus per Klick daneben (löscht die Auswahl), ESC
 * (behält die nicht-W-Auswahl) oder Klick auf ein Objekt (Idle-Semantik).
 * Klick auf eine ANDERE Verbindung wechselt den Fokus, der Modus bleibt.
 */
public final class RouteEditModeState extends BaseState {

    private final UUID connectionId;

    public RouteEditModeState(UUID connectionId) {
        this.connectionId = connectionId;

        // 0. Doppelklick: W selektiert nur (keine Griffe — Ws haben keine
        //    Größe); K/R betritt den Resize-Modus (der Modus wird sauber
        //    verlassen); FREIES SEGMENT fügt dort einen neuen Kettenpunkt
        //    ein (demo2-Semantik — der Modus ist ihr natürlicher Anker:
        //    Die Ws sind sichtbar, das neue sofort greifbar). Guard-disjunkt
        //    zur einfachen Klick-Regel: clickCount 2 vs. 1.
        addRule(InputEvent.MousePressed.class,
                e -> e.button() == CustomMouseButton.PRIMARY && e.clickCount() == 2,
                (e, c) -> {
                    double worldX = c.camera().screenToWorldX(e.x());
                    double worldY = c.camera().screenToWorldY(e.y());
                    var target = c.diagram().findTopmostAt(worldX, worldY);

                    if (target.isEmpty()) {
                        // Freies Segment? -> Kettenpunkt an der Klick-Stelle.
                        // Einfügen in die NÄCHSTE Verbindung: Der vorangegangene
                        // Press(1) derselben Doppelklick-Geste hat den Fokus
                        // bereits dorthin gelegt (bzw. den Modus betreten).
                        double toleranceWorld =
                                CONNECTION_CLICK_TOLERANCE_PIXELS / c.camera().zoom();
                        var near = c.diagram().findConnectionNear(worldX, worldY, toleranceWorld);
                        if (near.isPresent()) {
                            var waypoint = new Waypoint(UUID.randomUUID(),
                                    worldX, worldY, c.camera().defaultLayerId());
                            c.publisher().publish(
                                    new ActionEvent.AddWaypoint(near.get(), waypoint));
                        }
                        return this; // Modus bleibt — direkt weiter formen
                    }
                    if (target.get() instanceof Waypoint) {
                        c.publisher().publish(
                                new ActionEvent.SetSelection(List.of(target.get().id())));
                        return this;
                    }
                    // K/R/Text: Modus verlassen (Ws verschwinden), dann exakt
                    // die Idle-Doppelklick-Semantik.
                    c.publisher().publish(new ActionEvent.WaypointFocusChanged(null));
                    c.publisher().publish(
                            new ActionEvent.SetSelection(List.of(target.get().id())));
                    if (target.get() instanceof Circle || target.get() instanceof Rectangle) {
                        c.publisher().publish(new ActionEvent.ResizeModeChanged(target.get()));
                        return new ResizeModeState(target.get());
                    }
                    return new IdleToolState();
                });

        // 1. Einfacher Press: W greifen, Objekt = Idle-Semantik, Leerraum =
        //    Fokus-Wechsel oder Modus-Ende.
        addRule(InputEvent.MousePressed.class,
                e -> e.button() == CustomMouseButton.PRIMARY && e.clickCount() == 1,
                (e, c) -> {
                    double worldX = c.camera().screenToWorldX(e.x());
                    double worldY = c.camera().screenToWorldY(e.y());
                    var hit = c.diagram().findTopmostAt(worldX, worldY);

                    if (hit.isPresent() && hit.get() instanceof Waypoint) {
                        // W: volle Klick-Semantik, Modus bleibt erhalten.
                        return PressSelections.selectAndPrepareMove(
                                c, e, hit.get().id(),
                                () -> new RouteEditModeState(connectionId));
                    }

                    if (hit.isPresent()) {
                        // K/R/Text: Modus verlassen, dann exakt die Idle-
                        // Klick-Semantik (Selektion bei Press, Move vorbereiten).
                        c.publisher().publish(new ActionEvent.WaypointFocusChanged(null));
                        return PressSelections.selectAndPrepareMove(
                                c, e, hit.get().id(), IdleToolState::new);
                    }

                    // Leerraum: Ctrl -> Gummiband MIT Waypoints (Modus bleibt).
                    if (e.controlDown()) {
                        return new PendingBoxSelectState(
                                e.x(), e.y(), true,
                                () -> new RouteEditModeState(connectionId));
                    }

                    // Leerraum ohne Ctrl: andere Verbindung -> Fokus wechseln,
                    // dieselbe -> bleiben, sonst -> Modus beenden.
                    double toleranceWorld =
                            CONNECTION_CLICK_TOLERANCE_PIXELS / c.camera().zoom();
                    Optional<UUID> near = c.diagram().findConnectionNear(
                            worldX, worldY, toleranceWorld);
                    if (near.isPresent()) {
                        if (near.get().equals(connectionId)) {
                            return this;
                        }
                        c.publisher().publish(
                                new ActionEvent.WaypointFocusChanged(near.get()));
                        return new RouteEditModeState(near.get());
                    }

                    c.publisher().publish(new ActionEvent.WaypointFocusChanged(null));
                    c.publisher().publish(new ActionEvent.SetSelection(List.of()));
                    return new IdleToolState();
                });

        // 2. ESC -> Modus beenden; die nicht-W-Auswahl bleibt bestehen (die
        //    Ws selbst werden herausgefiltert: Unsichtbar Selektierte wären
        //    heimliche Move-Mitglieder).
        addRule(InputEvent.KeyPressed.class,
                e -> e.code() == CustomKeyCode.ESCAPE,
                (e, c) -> {
                    c.publisher().publish(new ActionEvent.WaypointFocusChanged(null));
                    stripWaypointsFromSelection(c);
                    return new IdleToolState();
                });

        // 3. DEL -> Auswahl löschen (wie im Idle). Lebt die fokussierte
        //    Verbindung nach der Kaskade nicht mehr, endet der Modus mit ihr.
        addRule(InputEvent.KeyPressed.class,
                (e, c) -> e.code() == CustomKeyCode.DELETE
                        && !c.selection().selectedIds().isEmpty(),
                (e, c) -> {
                    c.publisher().publish(
                            new ActionEvent.DeleteObjects(c.selection().selectedIds()));

                    boolean connectionAlive = c.diagram().allConnections().stream()
                            .anyMatch(conn -> conn.id().equals(connectionId));
                    if (!connectionAlive) {
                        c.publisher().publish(new ActionEvent.WaypointFocusChanged(null));
                        return new IdleToolState();
                    }
                    return this;
                });

        // 4. Werkzeug-Wechsel (Toolbar): Modus sauber verlassen — ohne diese
        //    Regel blieben Modus (und die sichtbaren Ws) hängen.
        addRule(ActionEvent.SelectTool.class, (e, c) -> {
            c.publisher().publish(new ActionEvent.WaypointFocusChanged(null));
            return switch (e.tool()) {
                case CIRCLE -> new CircleToolState();
                case RECTANGLE -> new RectangleToolState();
                case CONNECT -> new ConnectToolState();
                case TEXT -> new TextToolState();
                case SELECT -> new IdleToolState();
            };
        });
    }

    /** Entfernt Waypoints aus der aktuellen Auswahl — nur falls tatsächlich
     *  welche selektiert sind (identische Mengen feuern KEINEN Fakt). */
    private void stripWaypointsFromSelection(InteractionContext c) {
        List<UUID> current = c.selection().selectedIds();
        List<UUID> withoutWaypoints = current.stream()
                .filter(id -> c.diagram().findById(id)
                        .map(obj -> !(obj instanceof Waypoint))
                        .orElse(false))
                .toList();
        if (withoutWaypoints.size() != current.size()) {
            c.publisher().publish(
                    new ActionEvent.SetSelection(withoutWaypoints));
        }
    }
}
