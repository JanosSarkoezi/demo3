package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.Text;
import de.fmc.editor.core.model.ToolType;
import de.fmc.editor.core.model.Waypoint;
import de.fmc.editor.core.state.InteractionContext;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class IdleToolState extends ToolState {

    public IdleToolState() {
        super(ToolType.SELECT);
        // 0. Doppelklick auf ein Objekt -> Auswahl + Resize-Modus (Handles).
        //    Guard-disjunkt zur einfachen Klick-Regel: clickCount 2 vs. 1.
        addRule(InputEvent.MousePressed.class,
                e -> e.button() == CustomMouseButton.PRIMARY && e.clickCount() == 2,
                (e, c) -> {
                    double worldX = c.camera().screenToWorldX(e.x());
                    double worldY = c.camera().screenToWorldY(e.y());
                    var target = visibleTargetAt(c, worldX, worldY);
                    if (target.isPresent()) {
                        c.publisher().publish(new ActionEvent.SetSelection(List.of(target.get().id())));
                        // Resize-Modus für K/R und Text-Boxen (gemeinsame
                        // ofBox-Griffe) — Waypoints sind Routenpunkte ohne
                        // Griffe: Der Doppelklick selektiert sie nur.
                        if (target.get() instanceof Circle
                                || target.get() instanceof Rectangle
                                || target.get() instanceof Text) {
                            c.publisher().publish(new ActionEvent.ResizeModeChanged(target.get()));
                            return new ResizeModeState(target.get());
                        }
                        return this;
                    }
                    // Doppelklick auf die Verbindungskette aus dem Idle (03.11.
                    // weiter in EINER Geste): Modus betreten + Kettenpunkt an
                    // der Klick-Stelle einfügen — Press(1) derselben Geste hat
                    // die Verbindung bereits selektiert (Plain-Klick-Semantik).
                    // Trifft der Doppelklick ein BESTEHENDES W (im Idle
                    // unsichtbar, aber hier sichtbar gemeint), wird es wie im
                    // Modus nur selektiert — kein drittes W an derselben Stelle.
                    double toleranceWorld =
                            CONNECTION_CLICK_TOLERANCE_PIXELS / c.camera().zoom();
                    Optional<UUID> near =
                            c.diagram().findConnectionNear(worldX, worldY, toleranceWorld);
                    if (near.isPresent()) {
                        c.publisher().publish(
                                new ActionEvent.WaypointFocusChanged(near.get()));
                        Optional<DiagramObject> topmost =
                                c.diagram().findTopmostAt(worldX, worldY);
                        if (topmost.isPresent() && topmost.get() instanceof Waypoint) {
                            c.publisher().publish(
                                    new ActionEvent.SetSelection(List.of(topmost.get().id())));
                        } else {
                            c.publisher().publish(new ActionEvent.AddWaypoint(near.get(),
                                    new Waypoint(UUID.randomUUID(), worldX, worldY,
                                            c.camera().defaultLayerId())));
                        }
                        return new RouteEditModeState(near.get());
                    }
                    return this;
                });

        registerDefaultRules();

        // 1. Einfacher Klick -> Selektion bei Maus-Druck + Geste vorbereiten.
        //    clickCount == 1 hält die Regel Guard-disjunkt zur Doppelklick-Regel;
        //    Dreifach-Klicks treffen bewusst KEINE Regel => Zustand bleibt.
        addRule(InputEvent.MousePressed.class,
                e -> e.button() == CustomMouseButton.PRIMARY && e.clickCount() == 1,
                (e, c) -> {
                    double worldX = c.camera().screenToWorldX(e.x());
                    double worldY = c.camera().screenToWorldY(e.y());

                    var target = visibleTargetAt(c, worldX, worldY);
                    if (target.isEmpty()) {
                        double toleranceWorld =
                                CONNECTION_CLICK_TOLERANCE_PIXELS / c.camera().zoom();
                        Optional<UUID> near =
                                c.diagram().findConnectionNear(worldX, worldY, toleranceWorld);
                        if (near.isPresent() && e.controlDown()) {
                            // CTRL+Klick auf die Verbindungskette -> RouteEditMode:
                            // Die Ws werden sichtbar (Hinweis) und damit greifbar.
                            // Die Auswahl bleibt unverändert — der Klick zielt auf
                            // die Route, nicht auf eine Auswahl-Änderung.
                            c.publisher().publish(
                                    new ActionEvent.WaypointFocusChanged(near.get()));
                            return new RouteEditModeState(near.get());
                        }
                        if (near.isPresent()) {
                            // Plain-Klick -> reine Selektion der Verbindung
                            // (03.11.): blauer Pfad in der View, DEL löscht sie
                            // samt Kaskade (Ws + verankerte Labels, Endpunkte
                            // bleiben). KEIN Modus-Eintritt, KEIN Move-Vorbereiten
                            // — Drag an der Verbindung ist bewusst ein No-Op;
                            // Route-Editing läuft über CTRL+Klick in den Modus.
                            c.publisher().publish(
                                    new ActionEvent.SetSelection(List.of(near.get())));
                            return this;
                        }

                        if (e.controlDown()) {
                            // Ctrl+Klick ins Leere -> Gummiband vorbereiten
                            // (OHNE Waypoints: Sie sind hier unsichtbar und
                            // dürften keine heimlichen Band-Treffer liefern —
                            // vollselektierte Verbindungen wandern mit, ohne
                            // dass ihre Ws in der Auswahl stehen müssen).
                            // Kein Event: Die Auswahl bleibt bis zum Band-
                            // Ergebnis unverändert; Ctrl-Klick ohne Drag ist
                            // ein No-Op.
                            return new PendingBoxSelectState(e.x(), e.y());
                        }
                        // Klick ins Leere -> Auswahl aufheben; kann Drag/Pan werden
                        c.publisher().publish(new ActionEvent.SetSelection(List.of()));
                        return new PendingCreateOrPanState(e.x(), e.y(), ToolType.SELECT, e.button());
                    }

                    // Selektion bei Maus-Druck + Verschieben vorbereiten
                    // (einfach = exklusiv, Ctrl = Toggle, Plain-Press auf
                    // Selektiertem = Gruppen-Move) — gemeinsame Semantik mit
                    // dem RouteEditMode.
                    return PressSelections.selectAndPrepareMove(
                            c, e, target.get().id(), IdleToolState::new);
                });
    }

    /**
     * Oberstes Klick-Ziel an der Welt-Position — Waypoints werden wie im
     * {@link ConnectingState} herausgefiltert: Außerhalb des RouteEditMode
     * sind sie unsichtbar (Sitzungszustand der UI) und dürfen keine
     * heimlichen Klick-Ziele sein. Ein Klick auf ein W landet stattdessen
     * über die Verbindungsnähe im RouteEditMode.
     */
    private static Optional<DiagramObject> visibleTargetAt(
            InteractionContext c, double worldX, double worldY) {
        return c.diagram().findTopmostAt(worldX, worldY)
                .filter(obj -> !(obj instanceof Waypoint));
    }

    private void registerDefaultRules() {
        // Werkzeug-Wechsel (Toolbar + Shortcuts) — zentral in ToolState
        registerToolSwitching();

        // ESC -> Auswahl aufheben (Zustand bleibt). Gefahrlos, da seit dem
        // 27.10. alle KeyPressed-Regeln Guard-disjunkt sind.
        addRule(InputEvent.KeyPressed.class,
                e -> e.code() == CustomKeyCode.ESCAPE,
                (e, c) -> {
                    c.publisher().publish(new ActionEvent.SetSelection(List.of()));
                    return this;
                });

        // DEL -> aktuelle Auswahl löschen. Guard: Auswahl nicht leer —
        // sonst kein Lösch-Request und damit auch keine Fakten.
        addRule(InputEvent.KeyPressed.class,
                (e, c) -> e.code() == CustomKeyCode.DELETE
                        && !c.selection().selectedIds().isEmpty(),
                (e, c) -> {
                    c.publisher().publish(
                            new ActionEvent.DeleteObjects(c.selection().selectedIds()));
                    return this;
                });
    }
}
