package de.fmc.editor.core.persistence;

import java.util.List;
import java.util.UUID;

/**
 * DTO einer Verbindung — Feldnamen wie demo2s Connection (sourceId/targetId/
 * waypointIds); layerId ist demo3-Erweiterung (fehlendes Feld alter Dateien
 * → Standard-Ebene im {@link DiagramCodec}). Die EIGENE ID steht nicht im
 * Record: Sie ist der Schlüssel der connections-Map des Formats.
 *
 * Null-tolerant bewusst ohne requireNonNull: Der Codec überspringt defekte
 * Einträge still, statt den ganzen Ladevorgang crashen zu lassen.
 *
 * @author saj
 */
public record PersistedConnection(
        UUID sourceId,
        UUID targetId,
        List<UUID> waypointIds,
        UUID layerId
) {
    public PersistedConnection {
        waypointIds = waypointIds == null ? List.of() : List.copyOf(waypointIds);
    }
}
