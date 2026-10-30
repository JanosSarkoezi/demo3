package de.fmc.editor.ui;

/**
 * Sitzungszustand der UI — Hover- und Preview-Zeichnung; die Welt-Umrechnung
 * macht die View über CameraInfo.
 *
 * @author saj
 */
public class UiMouseState {

    private double screenX;
    private double screenY;
    private boolean inside;

    public double getScreenX() {
        return screenX;
    }

    public void setScreenX(double screenX) {
        this.screenX = screenX;
    }

    public double getScreenY() {
        return screenY;
    }

    public void setScreenY(double screenY) {
        this.screenY = screenY;
    }

    /** Maus innerhalb der Zeichenfläche. */
    public boolean isInside() {
        return inside;
    }

    public void setInside(boolean inside) {
        this.inside = inside;
    }
}
