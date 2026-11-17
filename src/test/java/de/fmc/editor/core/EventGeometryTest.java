package de.fmc.editor.core;

import de.fmc.editor.core.model.EventSide;
import de.fmc.editor.core.model.EventGeometry;
import de.fmc.editor.core.model.Rectangle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Reine Event-Geometrie: Spitze bei 1/4 der R-Breite vom Seitenrand,
 * senkrechtes Stück über der oberen Kante, 90°-Bogen am Knick,
 * horizontales Stück über den Seitenrand hinaus (dort das Label).
 * Spiegelung links/rechts über EventSide — eine Rechen-Wahrheit für
 * Hit-Test (Service), Editor-Anker (Werkzeug) und Rendering (View).
 */
class EventGeometryTest {

    private final Rectangle owner = new Rectangle(UUID.randomUUID(),
            100.0, 100.0, 80.0, 60.0, UUID.randomUUID());

    // R: x 100, y 100, w 80, h 60 -> Seitenränder bei x=60/140, Oberkante y=70.

    @Test
    @DisplayName("Spitze liegt auf der Oberkante, 1/4 der Breite vom Seitenrand")
    void tipQuarterFromSideEdge() {
        double[] right = EventGeometry.tip(owner, EventSide.RIGHT);
        assertThat(right[0]).isCloseTo(120.0, within(1e-9)); // 140 - 80/4
        assertThat(right[1]).isCloseTo(70.0, within(1e-9));

        double[] left = EventGeometry.tip(owner, EventSide.LEFT);
        assertThat(left[0]).isCloseTo(80.0, within(1e-9));   // 60 + 80/4
        assertThat(left[1]).isCloseTo(70.0, within(1e-9));
    }

    @Test
    @DisplayName("Knick liegt senkrecht über der Spitze; Start außen auf Knick-Höhe")
    void kinkAndEndPositions() {
        double[] kink = EventGeometry.kink(owner, EventSide.RIGHT, 40.0);
        assertThat(kink[0]).isCloseTo(120.0, within(1e-9));
        assertThat(kink[1]).isCloseTo(30.0, within(1e-9));   // 70 - 40

        double[] end = EventGeometry.end(owner, EventSide.RIGHT, 40.0, 40.0);
        assertThat(end[0]).isCloseTo(180.0, within(1e-9));   // Seitenrand 140 + 40
        assertThat(end[1]).isCloseTo(30.0, within(1e-9));

        double[] leftEnd = EventGeometry.end(owner, EventSide.LEFT, 40.0, 40.0);
        assertThat(leftEnd[0]).isCloseTo(20.0, within(1e-9)); // Seitenrand 60 - 40
        assertThat(leftEnd[1]).isCloseTo(30.0, within(1e-9));
    }

    @Test
    @DisplayName("Polyline: Start → Bogen (90°) → Spitze; Bogen schmiegt sich an den Knick")
    void pathPolylineRunsStartArcTip() {
        List<double[]> points = EventGeometry.pathPoints(owner, EventSide.RIGHT, 40.0, 40.0);

        assertThat(points.get(0)[0]).isCloseTo(180.0, within(1e-9)); // Start
        assertThat(points.get(0)[1]).isCloseTo(30.0, within(1e-9));
        double[] tip = points.get(points.size() - 1);
        assertThat(tip[0]).isCloseTo(120.0, within(1e-9));
        assertThat(tip[1]).isCloseTo(70.0, within(1e-9));

        // Tangentenabstand am Knick: d = min(20, horizontale/vertikale Länge)
        // Horizontal = 180-120 = 60, vertikal = 40 -> d = 20 (voller Radius).
        double[] firstTangent = points.get(1);
        assertThat(firstTangent[0]).isCloseTo(140.0, within(1e-9)); // 120 + 20
        assertThat(firstTangent[1]).isCloseTo(30.0, within(1e-9));
        double[] lastTangent = points.get(points.size() - 2);
        assertThat(lastTangent[0]).isCloseTo(120.0, within(1e-9));
        assertThat(lastTangent[1]).isCloseTo(50.0, within(1e-9));    // 30 + 20

        // Alle Bogenpunkte liegen auf dem Viertelkreis um (140/50) — der
        // Bogen ersetzt den Knick, statt ihn abzuschneiden oder zu übertreffen.
        for (int i = 1; i < points.size() - 1; i++) {
            double[] p = points.get(i);
            double distance = Math.hypot(p[0] - 140.0, p[1] - 50.0);
            assertThat(distance).isCloseTo(20.0, within(0.5)); // Kreis-Treue
        }
    }

    @Test
    @DisplayName("Linke Seite ist exakt gespiegelt (Spiegelachse = R-Zentrum)")
    void leftSideMirrorsRight() {
        List<double[]> right = EventGeometry.pathPoints(owner, EventSide.RIGHT, 40.0, 40.0);
        List<double[]> left = EventGeometry.pathPoints(owner, EventSide.LEFT, 40.0, 40.0);

        assertThat(left.size()).isEqualTo(right.size());
        for (int i = 0; i < right.size(); i++) {
            assertThat(left.get(i)[0]).isCloseTo(200.0 - right.get(i)[0], within(1e-9));
            assertThat(left.get(i)[1]).isCloseTo(right.get(i)[1], within(1e-9));
        }
    }

    @Test
    @DisplayName("Kurze horizontale Segmente klemmen den Bogen in das Segment")
    void shortSegmentsClampArc() {
        // Extrem schmales R (w=10): Knick bei 102.5, Start bei 125 ->
        // horizontale Länge 22.5, d = min(20, 22.5, 40) = 20 passt gerade.
        Rectangle slim = new Rectangle(UUID.randomUUID(), 100.0, 100.0, 10.0, 60.0, UUID.randomUUID());
        List<double[]> points = EventGeometry.pathPoints(slim, EventSide.RIGHT, 20.0, 40.0);
        for (double[] p : points) {
            assertThat(p[0]).isGreaterThanOrEqualTo(102.4);  // Knick nicht underschritten
            assertThat(p[0]).isLessThanOrEqualTo(125.0);     // Start nicht überschritten
        }

        // Extrem flaches R (h=6): rise = 20, vertikale Länge = 20 = Minimum,
        // d = 20 trägt den vollen Viertelkreis (Oberkante 97 - rise 77 + d 20).
        Rectangle flat = new Rectangle(UUID.randomUUID(), 100.0, 100.0, 80.0, 6.0, UUID.randomUUID());
        List<double[]> flatPoints = EventGeometry.pathPoints(flat, EventSide.RIGHT, 40.0, 20.0);
        double[] lastTangent = flatPoints.get(flatPoints.size() - 2);
        assertThat(lastTangent[1]).isCloseTo(97.0, within(1e-9));
    }

    @Test
    @DisplayName("Hit: Kette (inkl. Bogen) und Label-Box über dem Start treffen")
    void hitTestChainAndLabel() {
        // Klick auf die Spitze / den senkrechten Abschnitt
        assertThat(EventGeometry.hitPoint(owner, EventSide.RIGHT, "Bestellung",
                40.0, 40.0, 120.0, 70.0)).isTrue();
        assertThat(EventGeometry.hitPoint(owner, EventSide.RIGHT, "Bestellung",
                40.0, 40.0, 120.0, 48.0)).isTrue();
        // Bogen-Mitte (Zentrum 140/50, Radius 20): ~ (125.9, 35.9)
        assertThat(EventGeometry.hitPoint(owner, EventSide.RIGHT, "",
                40.0, 40.0, 125.9, 35.9)).isTrue();
        // Horizontaler Abschnitt
        assertThat(EventGeometry.hitPoint(owner, EventSide.RIGHT, "",
                40.0, 40.0, 160.0, 30.0)).isTrue();
        // Label-Box: Text sitzt ÜBER dem Startpunkt
        assertThat(EventGeometry.hitPoint(owner, EventSide.RIGHT, "Bestellung",
                40.0, 40.0, 180.0, 20.0)).isTrue();

        // Daneben: R-Körper weit unter der Kante, freie Fläche rechts,
        // deutlich über dem Bogen.
        assertThat(EventGeometry.hitPoint(owner, EventSide.RIGHT, "",
                40.0, 40.0, 100.0, 100.0)).isFalse();
        assertThat(EventGeometry.hitPoint(owner, EventSide.RIGHT, "",
                40.0, 40.0, 250.0, 30.0)).isFalse();
        assertThat(EventGeometry.hitPoint(owner, EventSide.RIGHT, "Bestellung",
                40.0, 40.0, 140.0, 8.0)).isFalse();
    }

    @Test
    @DisplayName("Bounding-Box umschließt Kette und Label")
    void boundsCoverChainAndLabel() {
        double[] bounds = EventGeometry.bounds(owner, EventSide.RIGHT, "Bestellung", 40.0, 40.0);
        // Kette: x 120..180, y 30..70; Label-Box (10 Zeichen à 7) ragt bis
        // x = 180 + 35 = 215 und y = 30 - 16 - 2 = 12.
        assertThat(bounds[0]).isCloseTo(120.0, within(0.01));
        assertThat(bounds[1]).isCloseTo(12.0, within(0.01));
        assertThat(bounds[2]).isCloseTo(215.0, within(0.01));
        assertThat(bounds[3]).isCloseTo(70.0, within(0.01));
    }
}
