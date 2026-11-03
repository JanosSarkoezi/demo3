package de.fmc.editor.ui;

import de.fmc.editor.core.bus.EventBusPublisher;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import javafx.scene.Node;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;

/**
 * Übersetzt JavaFX-Eingaben in Core-InputEvents. Der Kern ist JavaFX-frei;
 * das hier ist die einzige Übersetzungsstelle. x/y sind immer Koordinaten
 * LOKAL zur Zeichenfläche (event.getX()/getY() des eventRoot) — NICHT
 * szene-global (getSceneX() würde die Toolbar-Breite miteinbeziehen und
 * jeden Klick versetzt landen lassen). Die Welt-Umrechnung macht der Kern
 * selbst über CameraInfo; dieselbe Basis nutzt GraphView für Hover.
 *
 * @author saj
 */
public class InputEventTranslator {

    private final EventBusPublisher publisher;
    private final UiMouseState mouseState;
    private final Node eventRoot;
    /** Datei-Aktionen (CTRL+S/CTRL+O) — reine UI-Flows (FileChooser), im Main
     *  an den PersistenceController verdrahtet. */
    private final Runnable onSaveShortcut;
    private final Runnable onLoadShortcut;

    public InputEventTranslator(Node eventRoot, EventBusPublisher publisher, UiMouseState mouseState,
                                Runnable onSaveShortcut, Runnable onLoadShortcut) {
        this.eventRoot = eventRoot;
        this.publisher = publisher;
        this.mouseState = mouseState;
        this.onSaveShortcut = onSaveShortcut;
        this.onLoadShortcut = onLoadShortcut;

        eventRoot.setFocusTraversable(true);

        eventRoot.addEventFilter(MouseEvent.MOUSE_PRESSED, this::onMousePressed);
        eventRoot.addEventFilter(MouseEvent.MOUSE_DRAGGED, this::onMouseDragged);
        eventRoot.addEventFilter(MouseEvent.MOUSE_RELEASED, this::onMouseReleased);
        eventRoot.addEventFilter(MouseEvent.MOUSE_MOVED, this::onMouseMoved);
        eventRoot.addEventFilter(MouseEvent.MOUSE_EXITED, this::onMouseExited);
        eventRoot.addEventFilter(ScrollEvent.SCROLL, this::onScroll);
        eventRoot.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, this::onKeyPressed);
        eventRoot.addEventFilter(javafx.scene.input.KeyEvent.KEY_RELEASED, this::onKeyReleased);
    }

    private CustomMouseButton mapButton(MouseButton button) {
        return switch (button) {
            case PRIMARY -> CustomMouseButton.PRIMARY;
            case SECONDARY -> CustomMouseButton.SECONDARY;
            case MIDDLE -> CustomMouseButton.MIDDLE;
            default -> CustomMouseButton.NONE;
        };
    }

    /**
     * Ziel-Greife: Nur Events der Zeichenfläche SELBST übersetzen. Der
     * Inline-Editor ist ein KIND des Canvas-Root — seine Tasten und Klicks
     * laufen in der Capture-Phase durch die Root-Filter und würden sonst in
     * den Kern leaken: getippte Werkzeug-Buchstaben (K/R/C/T) wechselten das
     * Tool, CTRL+Z löste Core-Undo statt Text-Undo aus, ESC erreichte den
     * Kern (User-Fund im Stufe-A-Smoke-Test). Echte Canvas-Events haben
     * immer den Root als Ziel — alle Modell-Nodes sind mouseTransparent,
     * das Picking endet am Root. Overlay-Controls sind wie die Toolbar
     * (BorderPane-Sibling, kommt nie hier an) none of the translator's
     * business.
     */
    private boolean isFromCanvas(javafx.event.Event event) {
        return event.getTarget() == eventRoot;
    }

    private CustomMouseButton deriveButton(MouseEvent event) {
        MouseButton button = event.getButton();
        if (button == null || button == MouseButton.NONE) {
            if (event.isPrimaryButtonDown()) {
                return CustomMouseButton.PRIMARY;
            }
            if (event.isMiddleButtonDown()) {
                return CustomMouseButton.MIDDLE;
            }
            return CustomMouseButton.SECONDARY;
        }
        return mapButton(button);
    }

    private void onMousePressed(MouseEvent event) {
        if (!isFromCanvas(event)) {
            return; // Klick in ein Overlay (Editor): weder Fokus noch Kern.
        }
        // Jeder Klick auf die Zeichenfläche holt den Tastatur-Fokus zurück:
        // JavaFX-Regionen übernehmen den Fokus beim Klick NICHT von selbst —
        // ein Textfeld (Snap-to-Grid-Eingabe, Inline-Editor) behielte ihn
        // sonst dauerhaft und ESC/DEL/Shortcuts erreichten den Übersetzer
        // nie mehr (User-Fund im visuellen Test 1.11.).
        eventRoot.requestFocus();

        // Press-Position für den Inline-Editor festhalten (er öffnet an der
        // Klick-Stelle, nicht an der letzten Move-Position — Press ohne
        // vorheriges Move wäre sonst ein Blindflug).
        mouseState.setScreenX(event.getX());
        mouseState.setScreenY(event.getY());
        mouseState.setInside(true);

        publisher.publish(new InputEvent.MousePressed(
                event.getX(), event.getY(),
                mapButton(event.getButton()), event.getClickCount(), event.isControlDown()));
    }

    private void onMouseDragged(MouseEvent event) {
        if (!isFromCanvas(event)) {
            return; // Text-Selektieren im Editor ist keine Canvas-Geste.
        }
        publisher.publish(new InputEvent.MouseDragged(
                event.getX(), event.getY(), deriveButton(event)));
    }

    private void onMouseReleased(MouseEvent event) {
        if (!isFromCanvas(event)) {
            return;
        }
        publisher.publish(new InputEvent.MouseReleased(
                event.getX(), event.getY(), mapButton(event.getButton())));
    }

    private void onMouseMoved(MouseEvent event) {
        if (!isFromCanvas(event)) {
            return; // Maus über dem Editor: Position nicht verfälschen.
        }
        mouseState.setScreenX(event.getX());
        mouseState.setScreenY(event.getY());
        mouseState.setInside(true);
    }

    private void onMouseExited(MouseEvent event) {
        mouseState.setInside(false);
    }

    private void onScroll(ScrollEvent event) {
        if (!isFromCanvas(event)) {
            return;
        }
        publisher.publish(new InputEvent.MouseScrolled(
                event.getX(), event.getY(), event.getDeltaY()));
    }

    private void onKeyPressed(javafx.scene.input.KeyEvent event) {
        if (!isFromCanvas(event)) {
            return; // Getippte Zeichen gehören dem Editor, nie dem Kern.
        }
        KeyCode code = event.getCode();
        // Speichern/Laden (CTRL+S/CTRL+O) sind reine UI-Flows (FileChooser):
        // Dafür gibt es bewusst keine Core-Events — der Übersetzer greift sie
        // am Systemrand ab wie CTRL+Z/Y und reicht die Aktion in die UI-Schicht.
        if (event.isControlDown() && !event.isShiftDown()) {
            if (code == KeyCode.S) {
                onSaveShortcut.run();
                event.consume();
                return;
            }
            if (code == KeyCode.O) {
                onLoadShortcut.run();
                event.consume();
                return;
            }
        }
        if (code == KeyCode.Z || code == KeyCode.Y) {
            if (event.isControlDown() && event.isShiftDown()) {
                publisher.publish(new ActionEvent.RequestRedo());
                event.consume();
                return;
            }
            if (event.isControlDown()) {
                // CTRL+Z = Undo; CTRL+Y = Redo (Windows-Konvention)
                publisher.publish(code == KeyCode.Z
                        ? new ActionEvent.RequestUndo()
                        : new ActionEvent.RequestRedo());
                event.consume();
                return;
            }
            // Ohne CTRL: keine Core-Events (Z/Y existieren bewusst nicht in CustomKeyCode).
            return;
        }
        CustomKeyCode mapped = mapKey(code);
        if (mapped != null) {
            publisher.publish(new InputEvent.KeyPressed(mapped));
        }
    }

    private void onKeyReleased(javafx.scene.input.KeyEvent event) {
        if (!isFromCanvas(event)) {
            return;
        }
        CustomKeyCode mapped = mapKey(event.getCode());
        if (mapped != null) {
            publisher.publish(new InputEvent.KeyReleased(mapped));
        }
    }

    private CustomKeyCode mapKey(KeyCode code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case K -> CustomKeyCode.KEY_K;
            case R -> CustomKeyCode.KEY_R;
            case C -> CustomKeyCode.KEY_C;
            case T -> CustomKeyCode.KEY_T;
            case ESCAPE -> CustomKeyCode.ESCAPE;
            case DELETE -> CustomKeyCode.DELETE;
            case SPACE -> CustomKeyCode.SPACE;
            default -> null; // Kein UNKNOWN-Spam an den Kern.
        };
    }
}
