package de.fmc.editor.core.state;

import de.fmc.editor.core.bus.EventBusPublisher;
import de.fmc.editor.core.model.DiagramQuery;

public record InteractionContext(
        EventBusPublisher publisher,
        DiagramQuery diagram,
        CameraInfo camera
) {}