package de.fmc.editor.core.model;

import java.util.Optional;
import java.util.UUID;

public interface DiagramQuery {
    /** Findet das oberste Objekt an der angegebenen Welt-Koordinate (z.B. für Klicks) */
    Optional<DiagramObject> findTopmostAt(double worldX, double worldY);

    /** Findet ein Objekt anhand seiner ID */
    Optional<DiagramObject> findById(UUID id);
}