package de.fmc.editor.core.state;

import java.util.UUID;

public record CameraInfo(
        double zoom,
        double offsetX,
        double offsetY,
        UUID defaultLayerId
) {

    /** Zoom-Grenzen (demo2-Konvention): 0.2-fach bis 10-fach. */
    public static final double MIN_ZOOM = 0.2;
    public static final double MAX_ZOOM = 10.0;
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
     * Der Zoom klemmt auf [MIN_ZOOM, MAX_ZOOM] (demo2-Konvention) — an den
     * Grenzen ist die Kamera unverändert.
     */
    public CameraInfo zoomAt(double zoomFactor, double pivotX, double pivotY) {
        double newZoom = Math.clamp(zoom * zoomFactor, MIN_ZOOM, MAX_ZOOM);
        if (newZoom <= 0 || newZoom == zoom) {
            return this; // Ungültiger Faktor oder Grenzfall: Kamera unverändert
        }
        double newOffsetX = pivotX - (pivotX - offsetX) * (newZoom / zoom);
        double newOffsetY = pivotY - (pivotY - offsetY) * (newZoom / zoom);
        return new CameraInfo(newZoom, newOffsetX, newOffsetY, defaultLayerId);
    }
}