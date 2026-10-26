package de.fmc.editor.core.state;

import de.fmc.editor.core.event.DomainEvent;

public interface CameraState {
    /** Returns true, wenn die Kamera gerade aktiv panned (konsumiert dann Events) */
    boolean isActive();

    CameraState handleInput(DomainEvent event, InteractionContext context);
}