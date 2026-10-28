package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.state.rule.BaseState;

import java.util.List;
import java.util.UUID;

public final class MovingState extends BaseState {

    private final List<UUID> movingIds;
    private double lastScreenX;
    private double lastScreenY;

    public MovingState(List<UUID> movingIds, double startScreenX, double startScreenY) {
        this.movingIds = List.copyOf(movingIds);
        this.lastScreenX = startScreenX;
        this.lastScreenY = startScreenY;

        // Verschieben: Delta berechnen, Zoom berücksichtigen & Event feuern.
        // Immer der PLURALE Request — ein einzelnes Objekt ist der Spezialfall
        // n=1; der Service veröffentlicht EINEN Batch-Fakt pro Segment.
        addRule(InputEvent.MouseDragged.class, (e, c) -> {
            // Delta in Bildschirm-Pixeln
            double deltaScreenX = e.x() - lastScreenX;
            double deltaScreenY = e.y() - lastScreenY;

            // Umrechnung des Versatzes in Welt-Koordinaten (geteilt durch Zoom)
            double deltaWorldX = deltaScreenX / c.camera().zoom();
            double deltaWorldY = deltaScreenY / c.camera().zoom();

            c.publisher().publish(new ActionEvent.MoveObjects(movingIds, deltaWorldX, deltaWorldY));

            this.lastScreenX = e.x();
            this.lastScreenY = e.y();
            return this;
        });

        // Loslassen beendet das Verschieben
        addRule(InputEvent.MouseReleased.class, (e, c) -> new IdleToolState());
    }
}
