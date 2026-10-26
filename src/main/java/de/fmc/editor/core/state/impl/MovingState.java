package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.state.rule.BaseState;

public final class MovingState extends BaseState {

    private double lastScreenX;
    private double lastScreenY;

    public MovingState(DiagramObject target, double startScreenX, double startScreenY) {
        this.lastScreenX = startScreenX;
        this.lastScreenY = startScreenY;

        // Verschieben: Delta berechnen, Zoom berücksichtigen & Event feuern
        addRule(InputEvent.MouseDragged.class, (e, c) -> {
            // Delta in Bildschirm-Pixeln
            double deltaScreenX = e.x() - lastScreenX;
            double deltaScreenY = e.y() - lastScreenY;

            // Umrechnung des Versatzes in Welt-Koordinaten (geteilt durch Zoom)
            double deltaWorldX = deltaScreenX / c.camera().zoom();
            double deltaWorldY = deltaScreenY / c.camera().zoom();

            c.publisher().publish(new ActionEvent.MoveObject(target.id(), deltaWorldX, deltaWorldY));

            this.lastScreenX = e.x();
            this.lastScreenY = e.y();
            return this;
        });

        // Loslassen beendet das Verschieben
        addRule(InputEvent.MouseReleased.class, (e, c) -> new IdleToolState());
    }
}