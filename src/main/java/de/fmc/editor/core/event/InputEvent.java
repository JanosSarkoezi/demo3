package de.fmc.editor.core.event;

public sealed interface InputEvent extends DomainEvent {
    // clickCount hinzugefügt (1 = Einzelklick, 2 = Doppelklick)
    record MousePressed(double x, double y, CustomMouseButton button, int clickCount) implements InputEvent {}

    record MouseDragged(double x, double y, CustomMouseButton button) implements InputEvent {}
    record MouseReleased(double x, double y, CustomMouseButton button) implements InputEvent {}
    record MouseScrolled(double x, double y, double deltaY) implements InputEvent {}
    record KeyPressed(CustomKeyCode code) implements InputEvent {}
    record KeyReleased(CustomKeyCode code) implements InputEvent {}
}