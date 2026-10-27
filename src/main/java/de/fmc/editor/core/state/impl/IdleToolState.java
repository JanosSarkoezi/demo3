package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.ToolType;
import de.fmc.editor.core.state.rule.BaseState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class IdleToolState extends BaseState {

    public IdleToolState() {
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
                        return new ResizeModeState(target.get());
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
                        // Klick ins Leere -> Auswahl aufheben; kann Drag/Pan werden
                        c.publisher().publish(new ActionEvent.SetSelection(List.of()));
                        return new PendingCreateOrPanState(e.x(), e.y(), ToolType.SELECT);
                    }

                    // Selektion bei Maus-Druck: einfacher Klick = exklusiv,
                    // Ctrl+Klick = Toggle (bereits Selektiertes fliegt raus).
                    UUID targetId = target.get().id();
                    List<UUID> current = c.selection().selectedIds();
                    List<UUID> newSelection;
                    if (e.controlDown()) {
                        newSelection = new ArrayList<>(current);
                        newSelection.remove(targetId); // No-Op, falls nicht enthalten
                        if (!current.contains(targetId)) {
                            newSelection.add(targetId);
                        }
                    } else {
                        newSelection = List.of(targetId);
                    }
                    c.publisher().publish(new ActionEvent.SetSelection(newSelection));

                    // Klick auf Objekt -> Verschieben vorbereiten
                    return new PendingMoveState(target.get(), e.x(), e.y());
                });
    }

    private void registerDefaultRules() {
        // Zoom-Regel (funktioniert immer)
        addRule(InputEvent.MouseScrolled.class, (e, c) -> {
            double factor = e.deltaY() > 0 ? 1.1 : 0.9;
            c.publisher().publish(new ActionEvent.ZoomView(factor, e.x(), e.y()));
            return this;
        });

        // Tool-Wechsel per Event (von UI Toolbar)
        addRule(ActionEvent.SelectTool.class, (e, c) -> switch (e.tool()) {
            case CIRCLE -> new CircleToolState();
            case RECTANGLE -> new RectangleToolState();
            case CONNECT -> new ConnectToolState();
            case SELECT, NONE -> this;
        });

        // Shortcuts für Werkzeuge — eine Guard-disjunkte Regel pro Key
        // (kein default-Switch: der würde auf JEDES KeyPressed matchen und
        // jede künftige Regel — z. B. ESC — zur Laufzeit ambig machen)
        addRule(InputEvent.KeyPressed.class,
                e -> e.code() == CustomKeyCode.KEY_K,
                (e, c) -> new CircleToolState());
        addRule(InputEvent.KeyPressed.class,
                e -> e.code() == CustomKeyCode.KEY_R,
                (e, c) -> new RectangleToolState());
        addRule(InputEvent.KeyPressed.class,
                e -> e.code() == CustomKeyCode.KEY_C,
                (e, c) -> new ConnectToolState());

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