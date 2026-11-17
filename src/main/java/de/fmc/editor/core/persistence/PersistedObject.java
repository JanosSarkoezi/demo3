package de.fmc.editor.core.persistence;

import java.util.UUID;

/**
 * DTO eines Diagramm-Objekts im demo2-Format: EINE uniforme Bounding-Box für
 * alle Typen (Gson-Export der Altvorlage — das JSON-Format ist der De-facto-
 * Kompatibilitätskontrakt, siehe doc/spec.md).
 *
 * x/y ist der MITTELPUNKT, genau wie im demo3-Modell: demo2s ShapeViewMapper
 * zeichnete `Circle(obj.x(), obj.y(), w/2)` bzw. das Rechteck bei
 * `x − w/2` — deshalb ist KEINE Koordinaten-Konvertierung nötig (löst die
 * offene Frage aus AGENTS.md „Geplant/Persistenz"). Beim Kreis ist
 * width/height der DURCHMESSER (Radius × 2); Waypoints tragen die demo2-
 * Konvention 10×10 (Maße, die das Modell nicht kennt).
 *
 * Demo3-Erweiterungen gegenüber demo2 (spec.md Zeile 57): label beim Kreis,
 * content/centered beim Rechteck. Gson lässt fehlende Felder alter Dateien
 * weg (null bzw. fehlendes Boolean) — die Abbildung im {@link DiagramCodec}
 * liefert dafür die demo3-Defaults (label/content "", centered true).
 * centered ist bewusst der Wrapper: null = „Feld fehlt in alter Datei" ist
 * unterscheidbar von false.
 *
 * Event-Felder (demo3, 17.11.): ownerId/side/horizontalExtent/verticalRise
 * — dieselbe Toleranz-Logik: null (fehlendes Feld) bzw. ungültige side
 * fallen im Codec auf die Defaults (Seite RIGHT, Maße 40/40); fehlende
 * ownerId macht den Eintrag defekt (still übersprungen). Die Andock-
 * Position x/y läuft als abgeleiteter Wert mit und wird beim Laden aus
 * Owner + Seite + Richtung neu abgeleitet (selbstheilend).
 * direction (26.11.): INCOMING (Default für Dateien vor dem 26.11.) oder
 * OUTGOING — ungültige Werte lesen sich als INCOMING.
 *
 * @author saj
 */
public record PersistedObject(
        UUID id,
        PersistedObjectType type,
        double x,
        double y,
        double width,
        double height,
        UUID layerId,
        String label,
        String content,
        Boolean centered,
        UUID ownerId,
        String side,
        Double horizontalExtent,
        Double verticalRise,
        String direction
) {
}
