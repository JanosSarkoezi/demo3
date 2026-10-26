package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.ToolType;
import de.fmc.editor.core.state.rule.BaseState;

public final class IdleToolState extends BaseState {

    public IdleToolState() {
        registerDefaultRules();

        // 1. Klick auf leere Fläche -> Vorbereitung für Panning (ohne Objekt-Erzeugung)
        addRule(InputEvent.MousePressed.class,
                e -> e.button() == CustomMouseButton.PRIMARY,
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