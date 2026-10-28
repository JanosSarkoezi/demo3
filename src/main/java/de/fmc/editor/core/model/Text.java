package de.fmc.editor.core.model;

import java.util.UUID;

/**
 * Ein Text mit eigener rechteckiger Bounding-Box. x/y ist der MITTELPUNKT
 * der Box (wie bei K und R — damit snappt, hit-testet und verschiebt sich
 * alles nach derselben Konvention).
 *
 * ownerId referenziert ein Objekt (externes Label an K/R), eine Connection
 * (Verbindungs-Label, maximal eins) oder nichts (freier Text).
 *
 * anchor: null = absolut positioniert (freie Texte und externe K/R-Labels);
 * gesetzt = an einem Verbindungsabschnitt verankert — dann ist x/y die vom
 * DiagramService abgeleitete Position (bei Erzeugung/Re-Ankerung berechnet;
 * das Gleiten mit Waypoint-Verschiebungen folgt mit der W-Sichtbarkeit).
 */
public record Text(
        UUID id,
        String content,
        double x,
        double y,
        double width,
        double height,
        TextAnchor anchor,
        UUID ownerId,
        UUID layerId
) implements DiagramObject {
    public Text {
        content = content == null ? "" : content;
    }
}
