package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.ToolType;

import java.util.ArrayList;
import java.util.List;
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
                    var target = c.diagram().findTopmostAt(worldX, worldY);
                    if (target.isPresent()) {
                        c.publisher().publish(new ActionEvent.SetSelection(List.of(target.get().id())));
                        // Resize-Modus nur für K/R — Waypoints sind Routenpunkte
                        // ohne Griffe: Der Doppelklick selektiert sie nur.
                        if (target.get() instanceof Circle || target.get() instanceof Rectangle) {
                            c.publisher().publish(new ActionEvent.ResizeModeChanged(target.get()));
                            return new ResizeModeState(target.get());
                        }
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

                    var target = c.diagram().findTopmostAt(worldX, worldY);
                    if (target.isEmpty()) {
                        if (e.controlDown()) {
                            // Ctrl+Klick ins Leere -> Gummiband vorbereiten.
                            // Kein Event: Die Auswahl bleibt bis zum Band-Ergebnis
                            // unverändert; ein Ctrl-Klick ohne Drag ist ein No-Op.
                            return new PendingBoxSelectState(e.x(), e.y());
                        }
                        // Klick ins Leere -> Auswahl aufheben; kann Drag/Pan werden
                        c.publisher().publish(new ActionEvent.SetSelection(List.of()));
                        return new PendingCreateOrPanState(e.x(), e.y(), ToolType.SELECT, e.button());
                    }

                    // Selektion bei Maus-Druck: einfacher Klick = exklusiv,
                    // Ctrl+Klick = Toggle. Plain-Press auf bereits Selektiertes
                    // kollabiert die Auswahl NICHT — die Geste verschiebt die
                    // ganze Menge (Gruppen-Move).
                    UUID targetId = target.get().id();
                    List<UUID> current = c.selection().selectedIds();
                    List<UUID> newSelection;
                    if (e.controlDown()) {
                        newSelection = new ArrayList<>(current);
                        newSelection.remove(targetId); // No-Op, falls nicht enthalten
                        if (!current.contains(targetId)) {
                            newSelection.add(targetId);
                        }
                    } else if (current.contains(targetId)) {
                        newSelection = current;
                    } else {
                        newSelection = List.of(targetId);
                    }
                    c.publisher().publish(new ActionEvent.SetSelection(newSelection));

                    // Verschiebe-Menge: die Auswahl, sofern sie das Ziel enthält
                    // (Ctrl-Toggle-out zieht nur das Ziel selbst). Die effektive
                    // Menge berechnet der DiagramService (findMoveSet): Basis
                    // plus externe Label plus Waypoints vollselektierter
                    // Verbindungen — die Route gehört zur Verbindung und
                    // wandert als Ganzes mit. Verankerte Texte filtert der
                    // DiagramService beim Move still heraus (Position abgeleitet).
                    List<UUID> base = newSelection.contains(targetId)
                            ? newSelection
                            : List.of(targetId);
                    List<UUID> movingIds = c.diagram().findMoveSet(base);

                    // Klick auf Objekt -> Verschieben vorbereiten
                    return new PendingMoveState(movingIds, e.x(), e.y());
                });
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