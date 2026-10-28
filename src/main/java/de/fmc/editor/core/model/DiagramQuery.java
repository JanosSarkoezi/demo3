package de.fmc.editor.core.model;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DiagramQuery {
    /** Findet das oberste Objekt an der angegebenen Welt-Koordinate (z.B. für Klicks) */
    Optional<DiagramObject> findTopmostAt(double worldX, double worldY);

    /** Findet ein Objekt anhand seiner ID */
    Optional<DiagramObject> findById(UUID id);

    /**
     * Findet alle Objekte, deren Bounding-Box das Welt-Rechteck schneidet
     * (für die Gummiband-Auswahl). Default: keine Treffer — Test-Stubs
     * bleiben dadurch stumm; der DiagramService überschreibt.
     */
    default List<DiagramObject> findObjectsInBounds(double minX, double minY, double maxX, double maxY) {
        return List.of();
    }
}