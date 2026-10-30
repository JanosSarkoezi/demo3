package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.ToolType;

public final class RectangleToolState extends ToolState {

    public RectangleToolState() {
        super(ToolType.RECTANGLE);

        // 1. Linksklick -> In die Gesten-Prüfung für Rechtecke
        addRule(InputEvent.MousePressed.class,
                e -> e.button() == CustomMouseButton.PRIMARY,
                (e, c) -> new PendingCreateOrPanState(e.x(), e.y(), ToolType.RECTANGLE, e.button()));

        // 2. ESC-Taste -> Abbrechen und zurück zu Select
        addRule(InputEvent.KeyPressed.class,
                e -> e.code() == CustomKeyCode.ESCAPE,
                (e, c) -> new IdleToolState());

        // 3. Werkzeug-Wechsel (Toolbar + Shortcuts) — zentral in ToolState
        registerToolSwitching();
    }
}
