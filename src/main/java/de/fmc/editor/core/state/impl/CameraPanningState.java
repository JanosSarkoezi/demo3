package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.DomainEvent;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.state.CameraState;
import de.fmc.editor.core.state.InteractionContext;

public final class CameraPanningState implements CameraState {

    private double lastScreenX;
    private double lastScreenY;

    public CameraPanningState(double startScreenX, double startScreenY) {
        this.lastScreenX = startScreenX;
        this.lastScreenY = startScreenY;
    }

    @Override
    public boolean isActive() {
        return true; // Signalisiert dem Bus: Kamera ist aktiv -> Tool-Machine pausiert!
    }

    @Override
    public CameraState handleInput(DomainEvent event, InteractionContext context) {
        // 1. Maus bewegen -> Pan-Offset berechnen & Event feuern
        if (event instanceof InputEvent.MouseDragged md) {
            double deltaX = md.x() - lastScreenX;
            double deltaY = md.y() - lastScreenY;

            context.publisher().publish(new ActionEvent.PanView(deltaX, deltaY));

            this.lastScreenX = md.x();
            this.lastScreenY = md.y();
            return this;
        }

        // 2. Maustaste loslassen -> Zurück in Kamera-Ruhezustand (Tool taut wieder auf)
        if (event instanceof InputEvent.MouseReleased) {
            return new CameraIdleState();
        }

        return this;
    }
}