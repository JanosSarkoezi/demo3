package de.fmc.editor.core.model;

/**
 * Verankerung eines Textes an einem Verbindungsabschnitt (Quelle -> W1 ->
 * ... -> Wn -> Ziel). segmentIndex 0 = Abschnitt von der Quelle zum ersten
 * Waypoint (bzw. direkt zum Ziel ohne Waypoints); t = Position entlang des
 * Abschnitts [0..1]; offsetX/offsetY = Abstand vom Linienpunkt (die
 * Feinjustierung des Benutzers).
 *
 * Der Anker ist die Platzierungs-Absicht: Verschiebt der Benutzer den Text,
 * wird der Anker neu projiziert. Fällt ein Abschnitt weg (W wird gelöscht),
 * klemmt der Index auf den letzten Abschnitt; t und Abstand bleiben.
 */
public record TextAnchor(
        int segmentIndex,
        double t,
        double offsetX,
        double offsetY
) {
    public TextAnchor {
        t = Math.clamp(t, 0.0, 1.0);
    }
}
