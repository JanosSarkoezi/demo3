package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.ToolType;
import de.fmc.editor.core.state.rule.BaseState;

public final class CircleToolState extends BaseState {

    public CircleToolState() {
        // 1. Linksklick -> In die Gesten-Prüfung (Klick = Erzeugen, Drag = Panning)
        addRule(InputEvent.MousePressed.class,
                e -> e.button() == CustomMouseButton.PRIMARY,
                (e, c) -> new PendingCreateOrPanState(e.x(), e.y(), ToolType.CIRCLE)
        );

        // 2. ESC-Taste -> Werkzeug verlassen und zurück zum Standard-Auswahlwerkzeug
        addRule(InputEvent.KeyPressed.class,
                e -> e.code() == CustomKeyCode.ESCAPE,
                (e, c) -> new IdleToolState()
        );

        // 3. Werkzeug-Wechsel über Event (z. B. Toolbar-Klick)
        addRule(ActionEvent.SelectTool.class, (e, c) -> switch (e.tool()) {
            case CIRCLE -> this; // Bleibt im Kreis-Werkzeug
            case RECTANGLE -> new RectangleToolState();
            case CONNECT -> new ConnectToolState();
            case SELECT, NONE -> new IdleToolState();
        });

        // 4. Werkzeug-Wechsel über Keyboard-Shortcuts
        addRule(InputEvent.KeyPressed.class, (e, c) -> switch (e.code()) {
            case KEY_R -> new RectangleToolState();
            case KEY_C -> new ConnectToolState();
            default -> this;
        });
    }
}