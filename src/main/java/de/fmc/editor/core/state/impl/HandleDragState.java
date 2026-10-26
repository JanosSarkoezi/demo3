package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.model.HandleType;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.state.InteractionContext;
import de.fmc.editor.core.state.rule.BaseState;

/**
 * Aktives Ziehen an einem Resize-Handle.
 *
 * Während des Drags wird NICHTS publiziert — die Live-Vorschau ist reine
 * UI-Sache, der Backend-Zustand ändert sich erst beim Commit. Beim Loslassen
 * wird genau EIN Resize-Request gefeuert. Die neue Größe ist symmetrisch ums
 * Zentrum berechnet (der Mittelpunkt bleibt damit an seinem Platz bzw. am
 * Gridpunkt) und auf mindestens eine Grid-Zelle geklemmt.
 */
public final class HandleDragState extends BaseState {

    /** Kleinste zulässige Größe pro Dimension, in Grid-Zellen. */
    private static final double MIN_SIZE_CELLS = 1.0;

    private final DiagramObject target;
    private final HandleType handle;

    /** Letzte Maus-Position in Welt-Koordinaten; null = noch nicht gezogen. */
    private Double lastWorldX;
    private Double lastWorldY;

    public HandleDragState(DiagramObject target, HandleType handle) {
        this.target = target;
        this.handle = handle;

        // 1. Ziehen: Position nur intern merken — kein Backend-Update
        addRule(InputEvent.MouseDragged.class, (e, c) -> {
            this.lastWorldX = c.camera().screenToWorldX(e.x());
            this.lastWorldY = c.camera().screenToWorldY(e.y());
            return this;
        });

        // 2. Loslassen: finale Größe committen — EIN Request pro Geste
        addRule(InputEvent.MouseReleased.class, (e, c) -> {
            commitResize(c);
            return new ResizeModeState(target);
        });

        // 3. ESC: Abbruch ohne Commit
        addRule(InputEvent.KeyPressed.class,
                e -> e.code() == CustomKeyCode.ESCAPE,
                (e, c) -> new ResizeModeState(target));
    }

    private void commitResize(InteractionContext context) {
        if (lastWorldX == null || lastWorldY == null) {
            return; // Kein Drag passiert -> nichts zu committen
        }
        double minSize = context.grid().gridSize() * MIN_SIZE_CELLS;

        // Jeder Kreis-Griff: Radius = Abstand Maus <-> Zentrum (symmetrisch)
        if (target instanceof Circle circle) {
            double radius = Math.max(
                    Math.hypot(lastWorldX - circle.x(), lastWorldY - circle.y()),
                    minSize);
            context.publisher().publish(new ActionEvent.ResizeCircle(circle.id(), radius));
            return;
        }

        if (target instanceof Rectangle rectangle) {
            double newWidth = rectangle.width();
            double newHeight = rectangle.height();

            // Ecken-Griffe: beide Dimensionen; Seiten-Griffe: nur die eine Richtung
            boolean affectsWidth = handle == HandleType.W || handle == HandleType.E
                    || handle == HandleType.NW || handle == HandleType.NE
                    || handle == HandleType.SW || handle == HandleType.SE;
            boolean affectsHeight = handle == HandleType.N || handle == HandleType.S
                    || handle == HandleType.NW || handle == HandleType.NE
                    || handle == HandleType.SW || handle == HandleType.SE;

            if (affectsWidth) {
                newWidth = Math.max(2 * Math.abs(lastWorldX - rectangle.x()), minSize);
            }
            if (affectsHeight) {
                newHeight = Math.max(2 * Math.abs(lastWorldY - rectangle.y()), minSize);
            }
            context.publisher().publish(
                    new ActionEvent.ResizeObject(rectangle.id(), newWidth, newHeight));
        }
    }
}
