package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.ToolType;
import de.fmc.editor.core.state.rule.BaseState;
/**
 * Gemeinsame Basis aller Werkzeug-States: zentralisiert den Werkzeug-Wechsel
 * (SelectTool-Event von der Toolbar) und die Shortcuts K/R/C — zuvor in jedem
 * State dupliziert (größte Demo2-Baustelle). Der self-Check hält jeden Wechsel
 * reflexiv (im eigenen Werkzeug bleibt der State). Guard-disjunkt: eine Regel
 * pro Key, kein default-Switch.
 */
public abstract class ToolState extends BaseState {

    private final ToolType self;

    protected ToolState(ToolType self) {
        this.self = self;
        // Mausrad-Zoom gehört der KAMERA-Maschine (CameraIdleState, Regel 3):
        // Sie läuft vor jedem Tool-Dispatch und zoomt damit in JEDEM Werkzeug
        // — das demo3-Äquivalent zu demo2s controller-seitigem handleZoom.
        // Eine ZWEITE Regel hier hätte den Zoom gedoppelt (1.1² pro Raste).
    }

    /** Tool-Typ dieses States — read-only für den Bus (Toolbar-Sync via
     *  ActionEvent.ToolChanged). Nicht-ToolState-Gesten-States (Connecting,
     *  Moving, ...) erben den zuletzt aktiven Tool-Typ implizit. */
    public ToolType toolType() {
        return self;
    }

    /** Werkzeug-Wechsel per Toolbar-Event und per Shortcut — einmalig zentral. */
    protected final void registerToolSwitching() {
        // Werkzeug-Wechsel über Event (z. B. Toolbar-Klick)
        addRule(ActionEvent.SelectTool.class, (e, c) -> switch (e.tool()) {
            case CIRCLE -> self == ToolType.CIRCLE ? this : new CircleToolState();
            case RECTANGLE -> self == ToolType.RECTANGLE ? this : new RectangleToolState();
            case CONNECT -> self == ToolType.CONNECT ? this : new ConnectToolState();
            case TEXT -> self == ToolType.TEXT ? this : new TextToolState();
            case SELECT -> self == ToolType.SELECT ? this : new IdleToolState();
        });

        // Shortcuts für Werkzeug-Wechsel — eine Guard-disjunkte Regel pro Key
        // (kein default-Switch, der ESC/Vorhandenes überlappen könnte)
        addRule(InputEvent.KeyPressed.class,
                e -> e.code() == CustomKeyCode.KEY_K,
                (e, c) -> self == ToolType.CIRCLE ? this : new CircleToolState());
        addRule(InputEvent.KeyPressed.class,
                e -> e.code() == CustomKeyCode.KEY_R,
                (e, c) -> self == ToolType.RECTANGLE ? this : new RectangleToolState());
        addRule(InputEvent.KeyPressed.class,
                e -> e.code() == CustomKeyCode.KEY_C,
                (e, c) -> self == ToolType.CONNECT ? this : new ConnectToolState());
        addRule(InputEvent.KeyPressed.class,
                e -> e.code() == CustomKeyCode.KEY_T,
                (e, c) -> self == ToolType.TEXT ? this : new TextToolState());
    }
}
