package de.fmc.editor.core.model;

import java.util.UUID;

public record Rectangle(
        UUID id,
        double x,
        double y,
        double width,
        double height,
        UUID layerId
) implements DiagramObject {}