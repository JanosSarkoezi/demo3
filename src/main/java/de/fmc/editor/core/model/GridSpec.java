package de.fmc.editor.core.model;

/**
 * Das Editor-Grid in Welt-Koordinaten (objektunabhängig von Zoom/Pan).
 * Referenz für Minimalgrößen (kleinstes Objekt = 1 Zelle) und seit Block 2
 * für das MOVE-Snapping: Die Zielposition des primär gezogenen Objekts
 * rundet snapToGrid(...) auf den nächsten Schnittpunkt (spec.md — „kleben,
 * bis die Maus dem nächsten Kreuzungspunkt näher liegt"); Erzeugung und
 * Resize-Klemmung nutzen dieselbe Instanz.
 */
public record GridSpec(double gridSize) {

    public GridSpec {
        if (gridSize <= 0) {
            throw new IllegalArgumentException("gridSize muss positiv sein: " + gridSize);
        }
    }

    /** Rundet eine Welt-Koordinate auf den nächsten Grid-Schnittpunkt. */
    public double snapToGrid(double coordinate) {
        return Math.round(coordinate / gridSize) * gridSize;
    }
}
