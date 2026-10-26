package de.fmc.editor.core.model;

import java.util.List;
import java.util.UUID;

/**
 * Eine Kante im Diagramm (Verbindung zwischen zwei DiagramObjects).
 * Bewusst KEIN DiagramObject: Kanten sind keine Klick-Ziele für findTopmostAt.
 */
public record Connection(
        UUID id,
        UUID sourceId,
        UUID targetId,
        List<Waypoint> waypoints,
        UUID layerId
) {
    public Connection {
        waypoints = List.copyOf(waypoints);
    }
}
