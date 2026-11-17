package de.fmc.editor.core.model;

import java.util.UUID;

/**
 * Event (FMC: Ereignis, das am besitzenden R verarbeitet wird) — gebogener
 * Pfeil mit Label am Pfadanfang.
 *
 * ownerId referenziert das besitzende Rectangle (Kardinalität 0..1 pro R,
 * User-Entscheidung 23.11.; das Werkzeug erzwingt sie, der Service
 * verteidigt sie still, das Laden bleibt tolerant). horizontalExtent = wie
 * weit das horizontale Segment über den Seitenrand hinausreicht,
 * verticalRise = Höhe des senkrechten Segments über der oberen Kante —
 * beide RELATIV zur R-Geometrie (User-Idee „zwei Ws", umgesetzt als zwei
 * Längen statt zwei absoluter Punkte): Move des R nimmt die Form komplett
 * mit (Delta auf den Andockpunkt, Längen unverändert), Resize dockt nur
 * die Spitze neu ab — die gezogene Form bleibt exakt erhalten.
 *
 * x/y ist der ANDOCKPUNKT auf der R-Kante (¼ der Breite vom Seitenrand):
 * bei INCOMING die Pfeilspitze auf der OBEREN Kante, bei OUTGOING der
 * Pfadfuß auf der UNTEREN — vom DiagramService abgeleitet und gepflegt
 * wie bei verankerten Texten: sichtbare Fakten auf Gesten-Pfaden,
 * stiller Skip auf dem mechanischen Restore-Pfad. Die Form-Griffe
 * (Start horizontal, Knick vertikal) sind abgeleitete Punkte aus {@link EventGeometry} — bewusst
 * KEINE Waypoint-Objekte (absolute Punkte würden beim Move/Resize des R
 * zurückbleiben und die ganze W-Maschinerie mitschleppen).
 *
 * Das Label ist seit 23.11. KEIN Record-Feld mehr, sondern ein eigenes
 * {@link Text}-Objekt mit ownerId = Event-ID (Kette R → E → Text, analog
 * zu den Außentexten am R): mehrzeilig, frei bewegbar, selektier- und
 * löschbar — und es wandert mit seinem Owner mit (transitive Move-
 * Expansion). Nur die ERSTELLUNG leitet den Service ab
 * (EventGeometry.labelAnchor); danach ist die Position Benutzerbesitz.
 *
 * @author saj
 */
public record Event(
        UUID id,
        UUID ownerId,
        EventSide side,
        EventDirection direction,
        double horizontalExtent,
        double verticalRise,
        double x,
        double y,
        UUID layerId
) implements DiagramObject {

    /** Kompatibilitäts-Konstruktor ohne Richtung (Bestands-Fixtures und
     *  Restore-Pfade): Events vor dem 26.11. waren per Definition
     *  hereinkommend — der Default ändert kein bestehendes Verhalten. */
    public Event(UUID id, UUID ownerId, EventSide side,
                 double horizontalExtent, double verticalRise,
                 double x, double y, UUID layerId) {
        this(id, ownerId, side, EventDirection.INCOMING,
                horizontalExtent, verticalRise, x, y, layerId);
    }

    /** Toleranter Lade-Pfad: eine fehlende Richtung (alte Dateien) liest
     *  sich als hereinkommend — dieselbe Default-Logik wie der Codec. */
    public Event {
        if (direction == null) {
            direction = EventDirection.INCOMING;
        }
    }
}
