package de.fmc.editor.core.model;

/**
 * Die zwei Formgriffe eines Events (abgeleitete Punkte aus
 * {@link EventGeometry}, KEINE Waypoint-Objekte): START = äußeres Pfadende,
 * zieht nur HORIZONTAL (ändert horizontalExtent); KINK = Knickpunkt, zieht
 * nur VERTIKAL (ändert verticalRise). Die Achs-Bindung lebt in der
 * Drag-Geste — das Modell kennt nur die zwei Längen.
 *
 * @author saj
 */
public enum EventHandle {
    START,
    KINK
}
