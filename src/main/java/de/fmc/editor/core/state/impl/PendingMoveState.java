package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.state.InteractionContext;
import de.fmc.editor.core.state.InteractionState;
import de.fmc.editor.core.state.rule.BaseState;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public final class PendingMoveState extends BaseState {

    private final List<UUID> movingIds;
    private final UUID primaryId;
    private final double startScreenX;
    private final double startScreenY;
    private final Supplier<InteractionState> returnState;

    /** Kompat-Konstruktor: Rückkehr zum Auswahl-Werkzeug, ohne Snapping. */
    public PendingMoveState(List<UUID> movingIds, double startScreenX, double startScreenY) {
        this(movingIds, null, startScreenX, startScreenY, IdleToolState::new);
    }

    /** Kompat-Konstruktor: ohne Primary (kein Snapping). */
    public PendingMoveState(List<UUID> movingIds, double startScreenX, double startScreenY,
                            Supplier<InteractionState> returnState) {
        this(movingIds, null, startScreenX, startScreenY, returnState);
    }

    /** primaryId: das primär gezogene Objekt (Press-Ziel). Dessen Welt-
     *  Position wird beim Drag-Start frisch gelesen (Modell-Position ist
     *  da noch die Gesten-Start-Position) und als unveränderlicher
     *  {@link MovingState.MoveAnchor} an die Move-Geste gereicht — das
     *  Snapping-Bezugssystem. */
    public PendingMoveState(List<UUID> movingIds, UUID primaryId,
                            double startScreenX, double startScreenY,
                            Supplier<InteractionState> returnState) {
        this.movingIds = movingIds;
        this.primaryId = primaryId;
        this.startScreenX = startScreenX;
        this.startScreenY = startScreenY;
        this.returnState = returnState;

        // 1. Dragging über Schwellenwert -> In aktiven MovingState wechseln
        addRule(InputEvent.MouseDragged.class,
                (e, c) -> distance(startScreenX, startScreenY, e.x(), e.y()) > DRAG_THRESHOLD_PIXELS,
                (e, c) -> new MovingState(movingIds, moveAnchor(c), startScreenX, startScreenY, returnState));

        // 2. Loslassen ohne Drag -> Reiner Klick: Die Selektion passierte
        //    bereits bei Press; hier gibt es nichts zu tun.
        addRule(InputEvent.MouseReleased.class,
                e -> e.button() == CustomMouseButton.PRIMARY,
                (e, c) -> returnState.get());
    }

    /** Frischer Positions-Snapshot des Primär-Objekts — oder null, wenn
     *  es (noch) nicht existiert (Stub-Kontext): Dann bleibt das Verschieben
     *  ungesnappt (Kompat-Verhalten). */
    private MovingState.MoveAnchor moveAnchor(de.fmc.editor.core.state.InteractionContext c) {
        if (primaryId == null) {
            return null;
        }
        return c.diagram().findById(primaryId)
                .map(obj -> new MovingState.MoveAnchor(primaryId, obj.x(), obj.y()))
                .orElse(null);
    }

    private double distance(double x1, double y1, double x2, double y2) {
        return Math.hypot(x2 - x1, y2 - y1);
    }
}
