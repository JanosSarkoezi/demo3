package de.fmc.editor.core.state;

import de.fmc.editor.core.bus.EventBusPublisher;
import de.fmc.editor.core.model.DiagramQuery;
import de.fmc.editor.core.model.GridSpec;
import de.fmc.editor.core.model.SelectionQuery;

public record InteractionContext(
        EventBusPublisher publisher,
        DiagramQuery diagram,
        CameraInfo camera,
        GridSpec grid,
        SelectionQuery selection
) {}
