package de.fmc.editor.core.model;

import java.util.UUID;

public record Waypoint(
        UUID id,
        double x,
        double y,
        UUID layerId
) implements DiagramObject {}