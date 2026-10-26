package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.state.rule.BaseState;

public final class ConnectToolState extends BaseState {

    public ConnectToolState() {
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
                }
        );

        // 2. ESC -> Werkzeug verlassen
        addRule(InputEvent.KeyPressed.class,
                e -> e.code() == CustomKeyCode.ESCAPE,
                (e, c) -> new IdleToolState()
        );

        // 3. Werkzeug-Wechsel über Event
        addRule(ActionEvent.SelectTool.class, (e, c) -> switch (e.tool()) {
            case CONNECT -> this;
            case CIRCLE -> new CircleToolState();
            case RECTANGLE -> new RectangleToolState();
            case SELECT, NONE -> new IdleToolState();
        });

        // 4. Werkzeug-Wechsel über Shortcuts — eine Guard-disjunkte Regel pro
        //    Key (kein default-Switch, der ESC/Vorhandenes überlappen könnte)
        addRule(InputEvent.KeyPressed.class,
                e -> e.code() == CustomKeyCode.KEY_K,
                (e, c) -> new CircleToolState());
        addRule(InputEvent.KeyPressed.class,
                e -> e.code() == CustomKeyCode.KEY_R,
                (e, c) -> new RectangleToolState());
    }
}