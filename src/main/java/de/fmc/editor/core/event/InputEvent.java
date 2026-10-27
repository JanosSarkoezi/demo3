package de.fmc.editor.core.event;

public sealed interface InputEvent extends DomainEvent {
    // clickCount hinzugefügt (1 = Einzelklick, 2 = Doppelklick).
    // controlDown: Modifier-Info für den Selektions-Toggle (Ctrl+Klick) —
    // dient später auch dem Gummiband (Ctrl+Drag). Der Kompatibilitäts-
    // konstruktor hält Bestands-Call-Sites grün.
    record MousePressed(double x, double y, CustomMouseButton button, int clickCount, boolean controlDown) implements InputEvent {
        public MousePressed(double x, double y, CustomMouseButton button, int clickCount) {
            this(x, y, button, clickCount, false);
        }
    }

    record MouseDragged(double x, double y, CustomMouseButton button) implements InputEvent {}
    record MouseReleased(double x, double y, CustomMouseButton button) implements InputEvent {}
    record MouseScrolled(double x, double y, double deltaY) implements InputEvent {}
    record KeyPressed(CustomKeyCode code) implements InputEvent {}
    record KeyReleased(CustomKeyCode code) implements InputEvent {}
}