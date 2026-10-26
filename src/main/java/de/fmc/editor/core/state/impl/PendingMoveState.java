package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.state.rule.BaseState;

public final class PendingMoveState extends BaseState {

    private static final double DRAG_THRESHOLD_PIXELS = 4.0;

    private final DiagramObject target;
    private final double startScreenX;
    private final double startScreenY;

    public PendingMoveState(DiagramObject target, double startScreenX, double startScreenY) {
        this.target = target;
        this.startScreenX = startScreenX;
        this.startScreenY = startScreenY;

        // 1. Dragging über Schwellenwert -> In aktiven MovingState wechseln
        addRule(InputEvent.MouseDragged.class,
                (e, c) -> distance(startScreenX, startScreenY, e.x(), e.y()) > DRAG_THRESHOLD_PIXELS,
                (e, c) -> new MovingState(target, startScreenX, startScreenY));

        // 2. Loslassen ohne Drag -> Reiner Klick (z. B. Objekt selektieren)
        addRule(InputEvent.MouseReleased.class,
                e -> e.button() == CustomMouseButton.PRIMARY,
                (e, c) -> {
                    // Hier könnte optional ein Select-Event gefeuert werden:
                    // c.publisher().publish(new ActionEvent.ObjectSelected(target.id()));
                    return new IdleToolState();
                });
    }

    private double distance(double x1, double y1, double x2, double y2) {
        return Math.hypot(x2 - x1, y2 - y1);
    }
}