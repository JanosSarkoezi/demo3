package de.fmc.editor.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Reine Geometrie des Event-Symbols (gebogener Pfeil am R) — EINE
 * Rechen-Wahrheit im Kern: DiagramService (Hit-Test, Andock-Ableitung),
 * EventToolState (Editor- und Griff-Hit) und View (Rendering) konsumieren
 * sie nur, rechnen nie selbst.
 *
 * Rückwärts-Konstruktion (User-Entscheidung 17.11., Richtung 26.11.):
 * INCOMING — Pfeilspitze auf der oberen R-Kante bei ¼ der R-Breite vom
 * Seitenrand, senkrecht nach oben (verticalRise), am Knick ein 90°-Bogen
 * (Radius 20 wie bei den Verbindungsrouten), horizontal hinaus über den
 * Seitenrand (horizontalExtent) — dort, am Pfadanfang, sitzt das Label.
 * OUTGOING — derselbe Aufbau an der UNTEREN Kante gespiegelt und in der
 * Zeichenrichtung umgedreht: Fuß an der unteren Kante, senkrecht nach
 * unten, Bogen, horizontal hinaus — die Pfeilspitze sitzt am äußeren
 * Ende. Standardmaß 40/40; Klemm-Untergrenze je Segment = Bogen-Radius,
 * damit der Viertelkreis immer ohne Verzerrung trägt.
 *
 * @author saj
 */
public final class EventGeometry {

    public static final double DEFAULT_EXTENT = 40.0;
    public static final double DEFAULT_RISE = 40.0;
    /** Klemm-Untergrenze je Segment = Bogen-Radius (User-Entscheidung). */
    public static final double MIN_SEGMENT = 20.0;
    public static final double CORNER_RADIUS = 20.0;
    /** Punkt-zu-Kette-Toleranz des Hit-Tests (Welt-Einheiten). */
    public static final double HIT_TOLERANCE = 5.0;
    /** Label-Box-Default (Welt-Einheiten) für Erstellung und Migration —
     *  live liefert die Editor-Session die Maße, gemessen folgt über
     *  UpdateText. Nur die Höhe geht zusätzlich in den labelAnchor ein. */
    public static final double LABEL_DEFAULT_BOX_WIDTH = 140.0;
    public static final double LABEL_DEFAULT_BOX_HEIGHT = 60.0;
    /** Höhe, die das Label über dem horizontalen Segment sitzt — gemeinsam
     *  für Anker (Kern) und Baseline-Absatz (View), damit „wo der Editor
     *  öffnet" und „wo der Text sitzt" übereinstimmen. */
    public static final double LABEL_HEIGHT = 16.0;
    private static final int ARC_SEGMENTS = 20;

    private EventGeometry() {
    }

    /** Andockpunkt auf der R-Kante, ¼ der Breite vom Seitenrand: bei
     *  INCOMING die Pfeilspitze auf der OBEREN Kante, bei OUTGOING der
     *  Pfadfuß auf der UNTEREN. */
    public static double[] dock(Rectangle owner, EventSide side, EventDirection direction) {
        return new double[]{
                owner.x() + side.direction() * owner.width() / 4.0,
                owner.y() + direction.verticalSign() * owner.height() / 2.0};
    }

    /** Knickpunkt senkrecht neben dem Andockpunkt, vom R weg
     *  (Formgriff: nur vertikal ziehbar). */
    public static double[] kink(Rectangle owner, EventSide side,
                                EventDirection direction, double rise) {
        double[] dock = dock(owner, side, direction);
        return new double[]{dock[0], dock[1] + direction.verticalSign() * rise};
    }

    /** Äußeres Pfadende (Formgriff: nur horizontal ziehbar): horizontal
     *  hinaus über den Seitenrand, auf Knick-Höhe. Bei INCOMING sitzt
     *  hier auch der Label-Anker; bei OUTGOING die Pfeilspitze. */
    public static double[] end(Rectangle owner, EventSide side,
                               EventDirection direction,
                               double extent, double rise) {
        double[] kink = kink(owner, side, direction, rise);
        double sideEdgeX = owner.x() + side.direction() * owner.width() / 2.0;
        return new double[]{sideEdgeX + side.direction() * extent, kink[1]};
    }

    /**
     * Polyline in Zeichenrichtung. INCOMING: Start → Knick(als 90°-Bogen)
     * → Spitze an der R-Kante. OUTGOING: Fuß an der R-Kante → Bogen →
     * äußeres Ende — dieselbe Punktfolge, nur gespiegelt und UMGEKEHRT,
     * denn die Pfeilspitze orientiert sich am LETZTEN Segment: bei
     * hereinkommenden Events zeigt sie in R, bei rausgehenden waagrecht
     * nach außen. Der Knick wird durch seinen Bogen ersetzt: Tangenten-
     * abstand d = min(r, horizontale Länge, vertikale Länge) — bei den
     * Mindestlängen (20) trägt der volle Viertelkreis. Hit-Test und
     * Rendering nutzen dieselben Punkte, damit „wo klickbar" und
     * „wo sichtbar" nie auseinanderlaufen.
     */
    public static List<double[]> pathPoints(Rectangle owner, EventSide side,
                                            EventDirection direction,
                                            double extent, double rise) {
        double[] end = end(owner, side, direction, extent, rise);
        double[] kink = kink(owner, side, direction, rise);
        double v = direction.verticalSign();

        double outX = Math.signum(end[0] - kink[0]); // Richtung Knick → Start
        double horizontalLength = Math.abs(end[0] - kink[0]);
        double d = Math.min(CORNER_RADIUS, Math.min(horizontalLength, rise));

        // Bogenzentrum diagonal zum Knick auf der konkaven Seite — immer
        // zur Andockkante hin; beide Tangentialpunkte liegen im Abstand d.
        double centerX = kink[0] + outX * d;
        double centerY = kink[1] - v * d;
        double startAngle = v * Math.PI / 2.0; // Tangentialpunkt am horizontalen Ast
        double sweepSign = outX * v;           // Drehsinn: Knick-Seite bleibt konkav

        List<double[]> points = new ArrayList<>();
        points.add(end);
        points.add(new double[]{kink[0] + outX * d, kink[1]});
        for (int s = 1; s <= ARC_SEGMENTS; s++) {
            double theta = startAngle + sweepSign * (Math.PI / 2.0) * s / ARC_SEGMENTS;
            points.add(new double[]{
                    centerX + d * Math.cos(theta),
                    centerY + d * Math.sin(theta)});
        }
        points.add(new double[]{kink[0], kink[1] - v * d});
        points.add(dock(owner, side, direction));
        if (direction == EventDirection.OUTGOING) {
            java.util.Collections.reverse(points);
        }
        return points;
    }

    /**
     * Hit-Test (Welt-Koordinaten): Punkt in Ketten-Nähe (inkl. Bogen). Das
     * Label ist seit 23.11. ein eigenes Text-Objekt mit eigener Hit-Box —
     * die Event-Kette selbst bleibt das Klick-Ziel des Symbols. Der
     * Parameter-Satz statt des Event-Records hält die Geometrie auch ohne
     * Modellbestand testbar.
     */
    public static boolean hitPoint(Rectangle owner, EventSide side,
                                   EventDirection direction,
                                   double extent, double rise,
                                   double worldX, double worldY) {
        List<double[]> points = pathPoints(owner, side, direction, extent, rise);
        for (int i = 0; i + 1 < points.size(); i++) {
            if (pointSegmentDistance(worldX, worldY,
                    points.get(i), points.get(i + 1)) <= HIT_TOLERANCE) {
                return true;
            }
        }
        return false;
    }

    /** Convenience-Überladung für den Service-Hit-Test gegen das Record. */
    public static boolean hit(Event event, Rectangle owner, double worldX, double worldY) {
        return hitPoint(owner, event.side(), event.direction(),
                event.horizontalExtent(), event.verticalRise(), worldX, worldY);
    }

    /** Anker des Labels — Erstellungs-Default (Service/Editor) und
     *  Baseline-Absatz (View) teilen denselben Punkt. Nach der Erstellung
     *  ist die Position Benutzerbesitz (freies Text-Objekt).
     *  INCOMING: ZENTRUM der Label-Box über dem äußeren Pfadende.
     *  OUTGOING: Mitte des äußeren Segments, UNTERHALB — am Ende sitzt
     *  hier die Pfeilspitze, die Mitte bleibt frei von ihr (User-
     *  Entscheidung 26.11., symmetrisch zum Incoming-Anker). */
    public static double[] labelAnchor(Rectangle owner, EventSide side,
                                       EventDirection direction,
                                       double extent, double rise) {
        if (direction == EventDirection.OUTGOING) {
            // Mitte des äußeren SEGMENTS (Knick → Ende — der Arm beginnt am
            // Knick INNERHALB des R), UNTERHALB des Arms: Am Ende sitzt die
            // Pfeilspitze, die Mitte bleibt frei von ihr.
            double[] kink = kink(owner, side, direction, rise);
            double[] end = end(owner, side, direction, extent, rise);
            return new double[]{
                    (kink[0] + end[0]) / 2.0,
                    kink[1] + LABEL_HEIGHT / 2.0 + 2.0};
        }
        double[] end = end(owner, side, direction, extent, rise);
        return new double[]{end[0], end[1] - LABEL_HEIGHT / 2.0 - 2.0};
    }

    /** Bounding-Box {minX, minY, maxX, maxY} der Kette (Gummiband-Auswahl);
     *  das Label bringt als eigenes Objekt seine eigene Box mit. */
    public static double[] bounds(Rectangle owner, EventSide side,
                                  EventDirection direction,
                                  double extent, double rise) {
        List<double[]> points = pathPoints(owner, side, direction, extent, rise);
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = Double.MIN_VALUE;
        double maxY = Double.MIN_VALUE;
        for (double[] p : points) {
            minX = Math.min(minX, p[0]);
            minY = Math.min(minY, p[1]);
            maxX = Math.max(maxX, p[0]);
            maxY = Math.max(maxY, p[1]);
        }
        return new double[]{minX, minY, maxX, maxY};
    }

    /** Abstand Punkt → Strecke a-b. */
    private static double pointSegmentDistance(double px, double py,
                                               double[] a, double[] b) {
        double abx = b[0] - a[0];
        double aby = b[1] - a[1];
        double lengthSquared = abx * abx + aby * aby;
        double t = lengthSquared == 0.0
                ? 0.0
                : Math.clamp(((px - a[0]) * abx + (py - a[1]) * aby) / lengthSquared, 0.0, 1.0);
        return Math.hypot(px - (a[0] + t * abx), py - (a[1] + t * aby));
    }
}
