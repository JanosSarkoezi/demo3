package de.fmc.editor.core.event;

public sealed interface InputEvent extends DomainEvent {
    // clickCount hinzugefügt (1 = Einzelklick, 2 = Doppelklick).
    // controlDown: Modifier-Info für den Selektions-Toggle (Ctrl+Klick) —
    // dient später auch dem Gummiband (Ctrl+Drag). shiftDown: Modifier-Info
    // der Event-Richtung (Shift+Klick = rausgehendes Event, 26.11.).
    // Die Kompatibilitäts-Konstruktoren halten Bestands-Call-Sites grün.
    record MousePressed(double x, double y, CustomMouseButton button, int clickCount,
                        boolean controlDown, boolean shiftDown) implements InputEvent {
        public MousePressed(double x, double y, CustomMouseButton button, int clickCount, boolean controlDown) {
            this(x, y, button, clickCount, controlDown, false);
        }

        public MousePressed(double x, double y, CustomMouseButton button, int clickCount) {
            this(x, y, button, clickCount, false, false);
        }
    }

    record MouseDragged(double x, double y, CustomMouseButton button) implements InputEvent {}
    record MouseReleased(double x, double y, CustomMouseButton button) implements InputEvent {}
    record MouseScrolled(double x, double y, double deltaY) implements InputEvent {}
    record KeyPressed(CustomKeyCode code) implements InputEvent {}
    record KeyReleased(CustomKeyCode code) implements InputEvent {}
}