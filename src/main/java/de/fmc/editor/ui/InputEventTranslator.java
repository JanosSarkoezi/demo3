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

    public InputEventTranslator(Node eventRoot, EventBusPublisher publisher, UiMouseState mouseState) {
        this.publisher = publisher;
        this.mouseState = mouseState;

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
        publisher.publish(new InputEvent.MousePressed(
                event.getX(), event.getY(),
                mapButton(event.getButton()), event.getClickCount(), event.isControlDown()));
    }

    private void onMouseDragged(MouseEvent event) {
        publisher.publish(new InputEvent.MouseDragged(
                event.getX(), event.getY(), deriveButton(event)));
    }

    private void onMouseReleased(MouseEvent event) {
        publisher.publish(new InputEvent.MouseReleased(
                event.getX(), event.getY(), mapButton(event.getButton())));
    }

    private void onMouseMoved(MouseEvent event) {
        mouseState.setScreenX(event.getX());
        mouseState.setScreenY(event.getY());
        mouseState.setInside(true);
    }

    private void onMouseExited(MouseEvent event) {
        mouseState.setInside(false);
    }

    private void onScroll(ScrollEvent event) {
        publisher.publish(new InputEvent.MouseScrolled(
                event.getX(), event.getY(), event.getDeltaY()));
    }

    private void onKeyPressed(javafx.scene.input.KeyEvent event) {
        KeyCode code = event.getCode();
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
