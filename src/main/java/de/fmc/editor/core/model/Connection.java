package de.fmc.editor.core.model;

import java.util.List;
import java.util.UUID;

/**
 * Eine Kante im Diagramm (Verbindung zwischen zwei DiagramObjects).
 * Bewusst KEIN DiagramObject: Kanten sind keine Klick-Ziele für findTopmostAt.
 * Waypoints werden per ID referenziert (spezifikationskonform): Sie sind
 * eigenständige, gespeicherte DiagramObjects — die Connection hält keine
 * zweite Kopie.
 */
public record Connection(
        UUID id,
        UUID sourceId,
        UUID targetId,
        List<UUID> waypointIds,
        UUID layerId
) {
    public Connection {
        waypointIds = List.copyOf(waypointIds);
    }
}
