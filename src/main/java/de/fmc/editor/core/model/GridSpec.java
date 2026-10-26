package de.fmc.editor.core.model;

/**
 * Das Editor-Grid in Welt-Koordinaten (objektunabhängig von Zoom/Pan).
 * Dient heute als Referenz für Minimalgrößen (kleinstes Objekt = 1 Zelle);
 * Zentren- und Move-Snapping kommen später und nutzen snapToGrid(...).
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
