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

    /**
     * IDs aller Text-Objekte mit diesem Owner (K/R-Objekt oder Connection),
     * in z-Ordnung. Default: leer — Stubs bleiben stumm.
     */
    default List<UUID> findTextIdsOwnedBy(UUID ownerId) {
        return List.of();
    }

    /**
     * Nächstgelegene Connection, deren Verbindungskette den Punkt höchstens
     * `tolerance` Welt-Einheiten entfernt passiert (für Text-Klicks auf
     * Verbindungen). Default: leer — Stubs bleiben stumm.
     */
    default Optional<UUID> findConnectionNear(double worldX, double worldY, double tolerance) {
        return Optional.empty();
    }

    /**
     * Projiziert einen Welt-Punkt auf die Verbindungskette: liefert den
     * Anker (Abschnitt, t) plus Offset vom Linienpunkt zum Klick — die
     * Platzierungs-Absicht für ein Verbindungs-Label. Default: leer.
     */
    default Optional<TextAnchor> projectOntoConnection(UUID connectionId, double worldX, double worldY) {
        return Optional.empty();
    }

    /**
     * Effektive Move-Menge einer Verschiebe-Geste zu einer Auswahl-Basis:
     * die Basis selbst, plus ihre externen Label (wandern mit ihrem Owner),
     * plus die Waypoints vollselektierter Verbindungen (beide Endpunkte in
     * der Basis — die Route gehört zur Verbindung und wandert als Ganzes
     * mit; teilselektierte bleiben verankert). Default: die Basis
     * unverändert — Stubs bleiben stumm.
     */
    default List<UUID> findMoveSet(List<UUID> basisIds) {
        return List.copyOf(basisIds);
    }
}