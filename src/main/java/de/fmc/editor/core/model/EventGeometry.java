package de.fmc.editor.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Reine Geometrie des Event-Symbols (gebogener Pfeil am R) — EINE
 * Rechen-Wahrheit im Kern: DiagramService (Hit-Test, Andock-Ableitung),
 * EventToolState (Editor- und Griff-Hit) und View (Rendering) konsumieren
 * sie nur, rechnen nie selbst.
 *
 * Rückwärts-Konstruktion (User-Entscheidung 17.11.): Pfeilspitze auf der
 * oberen R-Kante bei ¼ der R-Breite vom Seitenrand, senkrecht nach oben
 * (verticalRise), am Knick ein 90°-Bogen (Radius 20 wie bei den
 * Verbindungsrouten), horizontal hinaus über den Seitenrand
 * (horizontalExtent) — dort, am Pfadanfang, sitzt das Label. Standardmaß
 * 40/40; Klemm-Untergrenze je Segment = Bogen-Radius, damit der
 * Viertelkreis immer ohne Verzerrung trägt.
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
    /** Grobe Label-Maße für den Hit-Test (Welt-Einheiten) — die exakte
     *  Messung macht die View; für Klick-Zwecke genügt die Schätzung. */
    public static final double LABEL_CHAR_WIDTH = 7.0;
    public static final double LABEL_HEIGHT = 16.0;
    private static final int ARC_SEGMENTS = 20;

    private EventGeometry() {
    }

    /** Pfeilspitze auf der oberen R-Kante, ¼ der Breite vom Seitenrand. */
    public static double[] tip(Rectangle owner, EventSide side) {
        return new double[]{
                owner.x() + side.direction() * owner.width() / 4.0,
                owner.y() - owner.height() / 2.0};
    }

    /** Knickpunkt senkrecht über der Spitze (Formgriff: nur vertikal ziehbar). */
    public static double[] kink(Rectangle owner, EventSide side, double rise) {
        double[] tip = tip(owner, side);
        return new double[]{tip[0], tip[1] - rise};
    }

    /** Äußeres Pfadende = Start + Label-Anker (Formgriff: nur horizontal
     *  ziehbar): horizontal hinaus über den Seitenrand, auf Knick-Höhe. */
    public static double[] end(Rectangle owner, EventSide side, double extent, double rise) {
        double[] kink = kink(owner, side, rise);
        double sideEdgeX = owner.x() + side.direction() * owner.width() / 2.0;
        return new double[]{sideEdgeX + side.direction() * extent, kink[1]};
    }

    /**
     * Polyline in Zeichenrichtung Start → Knick(als 90°-Bogen) → Spitze.
     * Der Knick wird durch seinen Bogen ersetzt: Tangentenabstand
     * d = min(r, horizontale Länge, vertikale Länge) — bei den
     * Mindestlängen (20) trägt der volle Viertelkreis. Hit-Test und
     * Rendering nutzen dieselben Punkte, damit „wo klickbar" und
     * „wo sichtbar" nie auseinanderlaufen.
     */
    public static List<double[]> pathPoints(Rectangle owner, EventSide side,
                                            double extent, double rise) {
        double[] end = end(owner, side, extent, rise);
        double[] kink = kink(owner, side, rise);

        double outX = Math.signum(end[0] - kink[0]); // Richtung Knick → Start
        double horizontalLength = Math.abs(end[0] - kink[0]);
        double d = Math.min(CORNER_RADIUS, Math.min(horizontalLength, rise));

        // Bogenzentrum diagonal zum Knick auf der konkaven Seite; beide
        // Tangentialpunkte liegen im Abstand d.
        double centerX = kink[0] + outX * d;
        double centerY = kink[1] + d;
        double sign = -outX; // rechts: -90° → -180°, links: -90° → 0°

        List<double[]> points = new ArrayList<>();
        points.add(end);
        points.add(new double[]{kink[0] + outX * d, kink[1]});
        for (int s = 1; s <= ARC_SEGMENTS; s++) {
            double theta = -Math.PI / 2.0 + sign * (Math.PI / 2.0) * s / ARC_SEGMENTS;
            points.add(new double[]{
                    centerX + d * Math.cos(theta),
                    centerY + d * Math.sin(theta)});
        }
        points.add(new double[]{kink[0], kink[1] + d});
        points.add(tip(owner, side));
        return points;
    }

    /**
     * Hit-Test (Welt-Koordinaten): Punkt in Ketten-Nähe (inkl. Bogen) oder
     * in der Label-Box über dem Startpunkt. Der Parameter-Satz statt des
     * Event-Records hält die Geometrie auch ohne Modellbestand testbar.
     */
    public static boolean hitPoint(Rectangle owner, EventSide side, String label,
                                   double extent, double rise,
                                   double worldX, double worldY) {
        // Label-Box über dem Startpunkt: Text sitzt über dem horizontalen
        // Segment, ragt um die halbe geschätzte Breite über den Start hinaus.
        double[] end = end(owner, side, extent, rise);
        double labelWidth = Math.max(24.0, label.length() * LABEL_CHAR_WIDTH);
        if (Math.abs(worldX - end[0]) <= labelWidth / 2.0
                && worldY <= end[1] + 2.0
                && worldY >= end[1] - LABEL_HEIGHT - 2.0) {
            return true;
        }
        List<double[]> points = pathPoints(owner, side, extent, rise);
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
        return hitPoint(owner, event.side(), event.label(),
                event.horizontalExtent(), event.verticalRise(), worldX, worldY);
    }

    /** Anker des Labels: ZENTRUM der (geschätzten) Label-Box über dem
     *  Startpunkt — Inline-Editor und Hit-Box teilen denselben Punkt. */
    public static double[] labelAnchor(Rectangle owner, EventSide side,
                                       double extent, double rise) {
        double[] end = end(owner, side, extent, rise);
        return new double[]{end[0], end[1] - LABEL_HEIGHT / 2.0 - 2.0};
    }

    /** Bounding-Box {minX, minY, maxX, maxY} aus Kette + Label-Box
     *  (Gummiband-Auswahl). */
    public static double[] bounds(Rectangle owner, EventSide side, String label,
                                  double extent, double rise) {
        List<double[]> points = pathPoints(owner, side, extent, rise);
        double[] end = points.get(0);
        double labelWidth = Math.max(24.0, label.length() * LABEL_CHAR_WIDTH);
        double minX = Math.min(end[0] - labelWidth / 2.0, end[0]);
        double minY = Math.min(end[1] - LABEL_HEIGHT - 2.0, end[1]);
        double maxX = Math.max(end[0] + labelWidth / 2.0, end[0]);
        double maxY = end[1] + 2.0;
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
