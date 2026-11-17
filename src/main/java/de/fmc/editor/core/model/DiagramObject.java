package de.fmc.editor.core.model;

import java.util.UUID;

public sealed interface DiagramObject permits Circle, Rectangle, Waypoint, Text, Event {
    UUID id();
    double x();
    double y();
    UUID layerId();
}