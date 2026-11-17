package de.fmc.editor.core.model;

/**
 * Richtung eines Events am R (26.11.): INCOMING = hereinkommend — die
 * Pfeilspitze dockt an der OBEREN R-Kante an und zeigt senkrecht nach
 * unten in R (Verhalten vor dem 26.11.). OUTGOING = rausgehend — der
 * Fuß startet an der UNTEREN R-Kante (¼ der Breite vom Seitenrand),
 * der Pfad läuft senkrecht hinab, biegt horizontal ab und die Pfeil-
 * spitze sitzt am äußeren Ende (waagrecht nach außen).
 *
 * FMC-Lesart: rein oben, raus unten. Kardinalität 0..1 JE RICHTUNG
 * pro R (User-Entscheidung 26.11.) — ein R darf je ein hereinkommendes
 * UND ein rausgehendes Event besitzen.
 *
 * Die Outgoing-Polyline ist die an der Horizontalen gespiegelte und in
 * der Reihenfolge umgedrehte Incoming-Polyline — dadurch bleibt der
 * Renderer unverändert: Er orientiert die Pfeilspitze am LETZTEN
 * Segment (bei Incoming an der R-Kante, bei Outgoing am äußeren Ende).
 *
 * @author saj
 */
public enum EventDirection {
    INCOMING,
    OUTGOING;

    /** Vorzeichen der Vertikalen, von der R-Mitte aus gerechnet:
     *  INCOMING dockt an der OBEREN Kante an (−1), OUTGOING an der
     *  UNTEREN (+1) — Andockpunkt, Knick und Bogenseite folgen allein
     *  daraus. */
    public double verticalSign() {
        return this == OUTGOING ? 1.0 : -1.0;
    }
}
