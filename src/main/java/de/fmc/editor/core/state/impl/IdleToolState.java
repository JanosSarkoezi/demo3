package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.ToolType;
import de.fmc.editor.core.state.rule.BaseState;

import java.util.List;

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

        // 1. Einfacher Klick -> Vorbereitung für Panning (ohne Objekt-Erzeugung).
        //    clickCount == 1 hält die Regel Guard-disjunkt zur Doppelklick-Regel;
        //    Dreifach-Klicks treffen bewusst KEINE Regel => Zustand bleibt.
        addRule(InputEvent.MousePressed.class,
                e -> e.button() == CustomMouseButton.PRIMARY && e.clickCount() == 1,
                (e, c) -> {
                    double worldX = c.camera().screenToWorldX(e.x());
                    double worldY = c.camera().screenToWorldY(e.y());

                    var target = c.diagram().findTopmostAt(worldX, worldY);
                    if (target.isEmpty()) {
                        // Klick ins Leere -> Kann Drag/Pan werden (mit ToolType.SELECT)
                        return new PendingCreateOrPanState(e.x(), e.y(), ToolType.SELECT);
                    } else {
                        // Klick auf Objekt -> Verschieben vorbereiten
                        return new PendingMoveState(target.get(), e.x(), e.y());
                    }
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

        // Shortcuts für Werkzeuge
        addRule(InputEvent.KeyPressed.class, (e, c) -> switch (e.code()) {
            case KEY_K -> new CircleToolState();
            case KEY_R -> new RectangleToolState();
            case KEY_C -> new ConnectToolState();
            default -> this;
        });
    }
}