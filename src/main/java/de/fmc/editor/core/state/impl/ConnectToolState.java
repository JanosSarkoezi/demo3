package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.ToolType;

public final class ConnectToolState extends ToolState {

    public ConnectToolState() {
        super(ToolType.CONNECT);

        // 1. Linksklick -> Prüfen, ob ein Objekt getroffen wurde
        addRule(InputEvent.MousePressed.class,
                e -> e.button() == CustomMouseButton.PRIMARY,
                (e, c) -> {
                    double worldX = c.camera().screenToWorldX(e.x());
                    double worldY = c.camera().screenToWorldY(e.y());

                    var target = c.diagram().findTopmostAt(worldX, worldY);

                    if (target.isPresent()) {
                        // Objekt geklickt -> Starte Verbindungsmodus
                        return new ConnectingState(target.get());
                    }

                    return this;
                });

        // 2. ESC -> Werkzeug verlassen
        addRule(InputEvent.KeyPressed.class,
                e -> e.code() == CustomKeyCode.ESCAPE,
                (e, c) -> new IdleToolState());

        // 3. Werkzeug-Wechsel (Toolbar + Shortcuts) — zentral in ToolState
        registerToolSwitching();
    }
}
