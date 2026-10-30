package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.DomainEvent;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.state.CameraState;
import de.fmc.editor.core.state.InteractionContext;

public final class CameraIdleState implements CameraState {

    @Override
    public boolean isActive() {
        return false; // Kamera ist inaktiv -> Tool-Machine darf Events verarbeiten
    }

    @Override
    public CameraState handleInput(DomainEvent event, InteractionContext context) {
        // 1. Ein Tool fordert Panning an (z.B. Ziehen auf freier Fläche im CircleTool)
        if (event instanceof ActionEvent.RequestCameraPan request) {
            return new CameraPanningState(request.startX(), request.startY(), request.button());
        }

        // 2. Direkter Panning-Start via mittlerer Maustaste
        if (event instanceof InputEvent.MousePressed mp && mp.button() == CustomMouseButton.MIDDLE) {
            return new CameraPanningState(mp.x(), mp.y(), CustomMouseButton.MIDDLE);
        }

        // 3. Zoom via Mausrad. delta==0 wird still ignoriert (demo2-Wächter,
        //    Zeile 68 des Vorgängers): VNC- und hochauflösende Treiber liefern
        //    pro Raste oft ein Null-Delta-Begleit-Event — ohne den Wächter
        //    würde jeder Null-Delta wie «nach unten» wirken und der Zoom
        //    rein/raus-zappeln. Die Klemmung (0.2–10) macht CameraInfo.zoomAt.
        if (event instanceof InputEvent.MouseScrolled ms && ms.deltaY() != 0) {
            double factor = ms.deltaY() > 0 ? 1.1 : 0.9;
            context.publisher().publish(new ActionEvent.ZoomView(factor, ms.x(), ms.y()));
        }

        return this;
    }
}