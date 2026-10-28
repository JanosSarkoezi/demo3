package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.state.rule.BaseState;

import java.util.List;
import java.util.UUID;

public final class PendingMoveState extends BaseState {

    private final List<UUID> movingIds;
    private final double startScreenX;
    private final double startScreenY;

    public PendingMoveState(List<UUID> movingIds, double startScreenX, double startScreenY) {
        this.movingIds = movingIds;
        this.startScreenX = startScreenX;
        this.startScreenY = startScreenY;

        // 1. Dragging über Schwellenwert -> In aktiven MovingState wechseln
        addRule(InputEvent.MouseDragged.class,
                (e, c) -> distance(startScreenX, startScreenY, e.x(), e.y()) > DRAG_THRESHOLD_PIXELS,
                (e, c) -> new MovingState(movingIds, startScreenX, startScreenY));

        // 2. Loslassen ohne Drag -> Reiner Klick: Die Selektion passierte
        //    bereits bei Press (IdleToolState); hier gibt es nichts zu tun.
        addRule(InputEvent.MouseReleased.class,
                e -> e.button() == CustomMouseButton.PRIMARY,
                (e, c) -> new IdleToolState());
    }

    private double distance(double x1, double y1, double x2, double y2) {
        return Math.hypot(x2 - x1, y2 - y1);
    }
}
