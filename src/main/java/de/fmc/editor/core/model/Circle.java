package de.fmc.editor.core.model;

import java.util.UUID;

public record Circle(
        UUID id,
        double x,
        double y,
        double radius,
        UUID layerId
) implements DiagramObject {}