package de.fmc.editor.core.model;

/**
 * Seitenlage eines Events am R: RIGHT = Pfeil kommt von rechts (Standard,
 * sofern die Erstellung nichts anderes sagt), LEFT = gespiegelt von links.
 * Die Klickhälfte auf dem R bestimmt die Seite bei der Erstellung
 * (User-Entscheidung 17.11.).
 *
 * @author saj
 */
public enum EventSide {
    RIGHT,
    LEFT;

    /** Vorzeichen der Horizontalen: +1 = rechts vom R-Zentrum, −1 = links. */
    public double direction() {
        return this == RIGHT ? 1.0 : -1.0;
    }
}
