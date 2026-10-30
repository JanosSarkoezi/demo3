package de.fmc.editor.view;

import de.fmc.editor.core.model.DiagramObject;
import javafx.geometry.Point2D;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;

import java.util.ArrayList;
import java.util.List;

/**
 * Routing-Strategie mit abgerundeten Ecken an jedem Waypoint
 * (1:1 aus demo2 portiert, nur Typen angepasst).
 *
 * Mathematik: Abstand vom Scheitelpunkt zu den Tangentialpunkten ist
 * d = r · cot(θ/2); wird d größer als die halbe kürzere Nachbarschaft,
 * verkleinert der Radius passend (Klemmung), damit der Bogen nie über
 * die angrenzenden Segmente hinausragt. Der Bogen selbst wird als
 * ~20 LineTo-Segmente approximiert.
 */
public class RoundedCornerRouting implements RoutingStrategy {

    private final double radius;
    private static final int DEFAULT_SEGMENTS = 20;

    public RoundedCornerRouting() {
        this(20.0);
    }

    public RoundedCornerRouting(double radius) {
        this.radius = radius;
    }

    @Override
    public Path calculatePath(DiagramObject source, DiagramObject target,
                              List<DiagramObject> waypoints) {
        // Alle Punkte in Reihenfolge: Quelle → Waypoints → Ziel (jeweils Zentren)
        List<Point2D> points = new ArrayList<>();
        points.add(new Point2D(source.x(), source.y()));
        if (waypoints != null) {
            for (DiagramObject wp : waypoints) {
                points.add(new Point2D(wp.x(), wp.y()));
            }
        }
        points.add(new Point2D(target.x(), target.y()));

        Path path = new Path();
        path.getElements().add(new MoveTo(points.get(0).getX(), points.get(0).getY()));
        Point2D currentPos = points.get(0);

        // Für jede Ecke (innere Punkte) den Bogen einfügen
        for (int i = 1; i < points.size() - 1; i++) {
            Point2D prev = points.get(i - 1);
            Point2D curr = points.get(i);
            Point2D next = points.get(i + 1);

            // Vektoren von curr zu prev und curr zu next
            Point2D v1 = prev.subtract(curr);
            Point2D v2 = next.subtract(curr);
            double len1 = v1.distance(0, 0);
            double len2 = v2.distance(0, 0);

            // Sicherheitscheck (kollineare oder identische Punkte)
            if (len1 < 1e-9 || len2 < 1e-9) {
                path.getElements().add(new LineTo(curr.getX(), curr.getY()));
                currentPos = curr;
                continue;
            }

            // Innenwinkel der Ecke
            double dot = v1.getX() * v2.getX() + v1.getY() * v2.getY();
            double cosAngle = Math.max(-1.0, Math.min(1.0, dot / (len1 * len2)));
            double angle = Math.acos(cosAngle);

            // (Fast) kollinear: keine Ecke, einfach gerade weiter
            if (angle < 1e-4 || angle > (Math.PI - 1e-4)) {
                path.getElements().add(new LineTo(curr.getX(), curr.getY()));
                currentPos = curr;
                continue;
            }

            // Abstand vom Scheitelpunkt bis zu den Tangentialpunkten: d = r·cot(θ/2)
            double d = radius * Math.cos(angle / 2.0) / Math.sin(angle / 2.0);

            // Klemmung: Bogen darf maximal bis zur Mitte der kürzeren Nachbarschaft reichen
            double maxD = Math.min(len1, len2) * 0.5;
            double effectiveRadius = radius;
            if (d > maxD) {
                effectiveRadius = maxD * Math.tan(angle / 2.0);
                d = maxD;
            }

            // Einheitsvektoren
            Point2D dir1 = v1.multiply(1.0 / len1);
            Point2D dir2 = v2.multiply(1.0 / len2);

            // Tangentialpunkte
            Point2D pStart = curr.add(dir1.multiply(d));
            Point2D pEnd = curr.add(dir2.multiply(d));

            // Gerade Linie bis zum Bogenstart
            if (!currentPos.equals(pStart)) {
                path.getElements().add(new LineTo(pStart.getX(), pStart.getY()));
            }

            // Bogen approximieren: Mittelpunkt über die Winkelhalbierende
            Point2D midDir = dir1.add(dir2).normalize();
            double distCenter = effectiveRadius / Math.sin(angle / 2.0);
            Point2D center = curr.add(midDir.multiply(distCenter));

            double startAngle = Math.atan2(pStart.getY() - center.getY(), pStart.getX() - center.getX());
            double endAngle = Math.atan2(pEnd.getY() - center.getY(), pEnd.getX() - center.getX());

            // Drehrichtung über das Kreuzprodukt
            double cross = (pStart.getX() - center.getX()) * (pEnd.getY() - center.getY())
                    - (pStart.getY() - center.getY()) * (pEnd.getX() - center.getX());

            // Zentralwinkel des Bogens ist π − Innenwinkel
            double beta = Math.PI - angle;
            double sign = (cross > 0) ? 1 : -1;

            for (int s = 1; s <= DEFAULT_SEGMENTS; s++) {
                double t = (double) s / DEFAULT_SEGMENTS * beta;
                double theta = startAngle + sign * t;
                double px = center.getX() + effectiveRadius * Math.cos(theta);
                double py = center.getY() + effectiveRadius * Math.sin(theta);
                path.getElements().add(new LineTo(px, py));
            }

            currentPos = pEnd;
        }

        // Letztes Teilstück zum Ziel
        Point2D last = points.get(points.size() - 1);
        if (!currentPos.equals(last)) {
            path.getElements().add(new LineTo(last.getX(), last.getY()));
        }

        return path;
    }
}
