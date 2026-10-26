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
}