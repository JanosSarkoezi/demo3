package de.fmc.editor.core.model;

import java.util.UUID;

/**
 * Event (FMC: Ereignis, das am besitzenden R verarbeitet wird) — gebogener
 * Pfeil mit Label am Pfadanfang.
 *
 * ownerId referenziert das besitzende Rectangle. horizontalExtent = wie weit
 * das horizontale Segment über den Seitenrand hinausreicht, verticalRise =
 * Höhe des senkrechten Segments über der oberen Kante — beide RELATIV zur
 * R-Geometrie (User-Idee „zwei Ws", umgesetzt als zwei Längen statt zwei
 * absoluter Punkte): Move des R nimmt die Form komplett mit (Delta auf den
 * Andockpunkt, Längen unverändert), Resize dockt nur die Spitze neu ab —
 * die gezogene Form bleibt exakt erhalten.
 *
 * x/y ist die Pfeilspitze auf der oberen R-Kante (¼ der Breite vom
 * Seitenrand) — vom DiagramService abgeleitet und gepflegt wie bei
 * verankerten Texten: sichtbare Fakten auf Gesten-Pfaden, stiller Skip auf
 * dem mechanischen Restore-Pfad. Die Form-Griffe (Start horizontal, Knick
 * vertikal) sind abgeleitete Punkte aus {@link EventGeometry} — bewusst
 * KEINE Waypoint-Objekte (absolute Punkte würden beim Move/Resize des R
 * zurückbleiben und die ganze W-Maschinerie mitschleppen).
 *
 * label = Text am Pfadanfang (Record-Feld wie Circle.label — der Text
 * gehört zum Pfeil, kein separates Text-Objekt). Kardinalität 0..n pro R.
 *
 * @author saj
 */
public record Event(
        UUID id,
        UUID ownerId,
        EventSide side,
        String label,
        double horizontalExtent,
        double verticalRise,
        double x,
        double y,
        UUID layerId
) implements DiagramObject {
    public Event {
        label = label == null ? "" : label;
    }
}
