package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.ToolType;
import de.fmc.editor.core.state.rule.BaseState;

public final class RectangleToolState extends BaseState {

    public RectangleToolState() {
        // 1. Linksklick -> In die Gesten-Prüfung für Rechtecke
        addRule(InputEvent.MousePressed.class,
                e -> e.button() == CustomMouseButton.PRIMARY,
                (e, c) -> new PendingCreateOrPanState(e.x(), e.y(), ToolType.RECTANGLE)
        );

        // 2. ESC-Taste -> Abbrechen und zurück zu Select
        addRule(InputEvent.KeyPressed.class,
                e -> e.code() == CustomKeyCode.ESCAPE,
                (e, c) -> new IdleToolState()
        );

        // 3. Werkzeug-Wechsel über Event
        addRule(ActionEvent.SelectTool.class, (e, c) -> switch (e.tool()) {
            case RECTANGLE -> this;
            case CIRCLE -> new CircleToolState();
            case CONNECT -> new ConnectToolState();
            case SELECT, NONE -> new IdleToolState();
        });

        // 4. Werkzeug-Wechsel über Shortcuts
        addRule(InputEvent.KeyPressed.class, (e, c) -> switch (e.code()) {
            case KEY_K -> new CircleToolState();
            case KEY_C -> new ConnectToolState();
            default -> this;
        });
    }
}