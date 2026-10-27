package de.fmc.editor.core.state;

import java.util.UUID;

public record CameraInfo(
        double zoom,
        double offsetX,
        double offsetY,
        UUID defaultLayerId
) {
    /** Rechnet Bildschirm-X (Canvas Pixel) in Welt-X um */
    public double screenToWorldX(double screenX) {
        return (screenX - offsetX) / zoom;
    }

    /** Rechnet Bildschirm-Y (Canvas Pixel) in Welt-Y um */
    public double screenToWorldY(double screenY) {
        return (screenY - offsetY) / zoom;
    }

    /** Liefert eine um (deltaX, deltaY) verschobene Kamera (Bildschirm-Pixel). */
    public CameraInfo panBy(double deltaX, double deltaY) {
        return new CameraInfo(zoom, offsetX + deltaX, offsetY + deltaY, defaultLayerId);
    }

    /**
     * Liefert eine um den Bildschirm-Punkt (pivotX, pivotY) gezoomte Kamera.
     * Der Welt-Punkt unter dem Pivot bleibt garantiert am Pivot fixiert.
     */
    public CameraInfo zoomAt(double zoomFactor, double pivotX, double pivotY) {
        double newZoom = zoom * zoomFactor;
        if (newZoom <= 0) {
            return this; // Ungültiger Faktor: Kamera unverändert lassen
        }
        double newOffsetX = pivotX - (pivotX - offsetX) * (newZoom / zoom);
        double newOffsetY = pivotY - (pivotY - offsetY) * (newZoom / zoom);
        return new CameraInfo(newZoom, newOffsetX, newOffsetY, defaultLayerId);
    }
}