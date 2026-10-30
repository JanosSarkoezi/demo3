package de.fmc.editor.view;

import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.Connection;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.Text;
import de.fmc.editor.core.model.Waypoint;
import de.fmc.editor.core.model.DiagramQuery;
import javafx.geometry.Point2D;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.Polygon;
import javafx.scene.transform.Rotate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Visuelle Repräsentation der Verbindungen (1:1 aus demo2 portiert):
 * Group aus Path (Routing-Strategie) + Pfeilspitzen-Polygon am Ziel-Rand.
 *
 * demo3-Abweichungen: Endpunkte/Waypoints werden über die {@link DiagramQuery}
 * per ID aufgelöst (der Kern ist die einzige Datenhaltung — die View hält
 * KEIN zweites Modell, sondern nur Nodes); fehlende Objekte (Kaskaden-Fenster)
 * werden still übersprungen. Alle Nodes sind mouseTransparent — Hit-Tests
 * macht allein der Kern.
 */
public class ConnectionViewMapper {

    private static final double ARROW_SIZE = 12.0;    // Länge der Pfeilspitze
    private static final double INDENT_RATIO = 0.65;  // Einkerbungstiefe hinten

    private final DiagramQuery diagramQuery;
    private final Group connectionLayer;
    private final Map<UUID, Group> visualConnections = new HashMap<>();
    private RoutingStrategy routingStrategy = new RoundedCornerRouting(20.0);

    public ConnectionViewMapper(DiagramQuery diagramQuery, Group connectionLayer) {
        this.diagramQuery = diagramQuery;
        this.connectionLayer = connectionLayer;
    }

    public void remove(UUID connectionId) {
        Group group = visualConnections.remove(connectionId);
        if (group != null) {
            connectionLayer.getChildren().remove(group);
        }
    }

    public void clear() {
        connectionLayer.getChildren().removeAll(visualConnections.values());
        visualConnections.clear();
    }

    /**
     * Aktualisiert eine Verbindung: existiert noch kein Node, wird er erzeugt;
     * sonst werden Pfad und Pfeil IN-PLACE überschrieben (kein Node-Re-Layout
     * im Szenengraphen — die demo2-Optimierung).
     */
    public void refresh(Connection conn) {
        var source = diagramQuery.findById(conn.sourceId());
        var target = diagramQuery.findById(conn.targetId());

        // Kaskaden-Fenster: Endpunkt schon/noch weg → nichts zeichnen.
        if (source.isEmpty() || target.isEmpty()) {
            remove(conn.id());
            return;
        }

        List<DiagramObject> waypoints = new ArrayList<>();
        for (UUID wpId : conn.waypointIds()) {
            diagramQuery.findById(wpId)
                    .filter(Waypoint.class::isInstance)
                    .ifPresent(waypoints::add);
        }

        Group existingGroup = visualConnections.get(conn.id());
        if (existingGroup == null) {
            // Fall 1: erste Zeichnung
            Path path = routingStrategy.calculatePath(source.get(), target.get(), waypoints);
            Group group = createConnectionNode(path, source.get(), target.get(), waypoints);
            visualConnections.put(conn.id(), group);
            connectionLayer.getChildren().add(group);
        } else {
            // Fall 2 (Optimierung): bestehenden Node in-place updaten
            Path existingPath = (Path) existingGroup.getChildren().get(0);
            Polygon existingArrow = (Polygon) existingGroup.getChildren().get(1);

            Path calculatedPath = routingStrategy.calculatePath(source.get(), target.get(), waypoints);
            existingPath.getElements().setAll(calculatedPath.getElements());
            updateArrowHead(existingPath, existingArrow, source.get(), target.get(), waypoints);
        }
    }

    /** Baut den Group einmalig: gestylter Pfad + transformierbare Pfeilspitze. */
    private Group createConnectionNode(Path path, DiagramObject source,
                                       DiagramObject target, List<DiagramObject> waypoints) {
        path.setStroke(Color.BLACK);
        path.setStrokeWidth(1.0);

        // Pfeilspitze an (0,0) erzeugt, später per Layout + Rotate an die Zielkante transformiert
        Polygon arrowHead = new Polygon();
        double indentSize = ARROW_SIZE * INDENT_RATIO;
        arrowHead.getPoints().addAll(
                0.0, 0.0,                          // Spitze
                -ARROW_SIZE, -ARROW_SIZE * 0.55,    // äußere Ecke links
                -indentSize, 0.0,                   // Einkerbung (Mitte hinten)
                -ARROW_SIZE, ARROW_SIZE * 0.55      // äußere Ecke rechts
        );
        arrowHead.setFill(Color.BLACK);
        arrowHead.setStroke(Color.BLACK);
        arrowHead.setStrokeWidth(1.0);

        Group group = new Group(path, arrowHead);
        // Hit-Tests macht allein der Kern (findConnectionNear) — die View ist stumm.
        group.setMouseTransparent(true);

        updateArrowHead(path, arrowHead, source, target, waypoints);
        return group;
    }

    /**
     * Richtet die Pfeilspitze am Zielrand aus und zieht das letzte
     * Liniensegment bis zur Einkerbung zurück, damit die Linie nicht durch
     * den Pfeil durchschaut (demo2-Algorithmus).
     */
    private void updateArrowHead(Path path, Polygon arrowHead, DiagramObject source,
                                 DiagramObject target, List<DiagramObject> waypoints) {
        // Letzter Punkt vor dem Ziel: Quelle oder letzter Waypoint
        double fromX = source.x();
        double fromY = source.y();
        if (waypoints != null && !waypoints.isEmpty()) {
            DiagramObject lastWp = waypoints.get(waypoints.size() - 1);
            fromX = lastWp.x();
            fromY = lastWp.y();
        }

        // Schnittpunkt mit dem Rand des Zielobjekts
        Point2D intersection = getBoundaryIntersection(target, fromX, fromY);

        // Winkel des letzten Segments
        double angle = Math.atan2(intersection.getY() - fromY, intersection.getX() - fromX);

        double indentSize = ARROW_SIZE * INDENT_RATIO;

        // Linienende an die Einkerbung des Pfeils zurückziehen
        double lineEndX = intersection.getX() - indentSize * Math.cos(angle);
        double lineEndY = intersection.getY() - indentSize * Math.sin(angle);

        if (!path.getElements().isEmpty()) {
            var lastElement = path.getElements().get(path.getElements().size() - 1);
            if (lastElement instanceof LineTo lineTo) {
                lineTo.setX(lineEndX);
                lineTo.setY(lineEndY);
            }
        }

        // Pfeil an den Rand schieben und in Segmentrichtung rotieren
        arrowHead.setLayoutX(intersection.getX());
        arrowHead.setLayoutY(intersection.getY());
        if (!arrowHead.getTransforms().isEmpty()
                && arrowHead.getTransforms().get(0) instanceof Rotate rotate) {
            rotate.setAngle(Math.toDegrees(angle));
        } else {
            arrowHead.getTransforms().setAll(new Rotate(Math.toDegrees(angle), 0, 0));
        }
    }

    /**
     * Schnittpunkt des Strahls (from → Zentrum) mit dem Objektrand.
     * Kreis: Strahl vom Zentrum auf den Umfang; Box (R/Text): AABB-Schnitt
     * per Minimierung der parametrischen Eintrittstiefe; Waypoint: Punkt.
     */
    private Point2D getBoundaryIntersection(DiagramObject obj, double fromX, double fromY) {
        double cx = obj.x();
        double cy = obj.y();

        if (obj instanceof Circle circle) {
            double r = circle.radius();
            double dx = fromX - cx;
            double dy = fromY - cy;
            double dist = Math.hypot(dx, dy);
            if (dist < 0.001) {
                return new Point2D(cx, cy);
            }
            return new Point2D(cx + (dx / dist) * r, cy + (dy / dist) * r);
        }

        double halfWidth = halfWidth(obj);
        double halfHeight = halfHeight(obj);
        double dx = fromX - cx;
        double dy = fromY - cy;

        if (Math.abs(dx) < 0.001 && Math.abs(dy) < 0.001) {
            return new Point2D(cx, cy);
        }

        double tX = (Math.abs(dx) < 0.001) ? Double.POSITIVE_INFINITY : halfWidth / Math.abs(dx);
        double tY = (Math.abs(dy) < 0.001) ? Double.POSITIVE_INFINITY : halfHeight / Math.abs(dy);

        double t = Math.min(tX, tY);
        return new Point2D(cx + dx * t, cy + dy * t);
    }

    private double halfWidth(DiagramObject obj) {
        return switch (obj) {
            case Rectangle r -> r.width() / 2.0;
            case Text t -> t.width() / 2.0;
            case Circle c -> c.radius();
            // Waypoints sind keine Verbindungsziele — als Quelle/Notfall: Punktnähe
            case Waypoint w -> 0.0;
        };
    }

    private double halfHeight(DiagramObject obj) {
        return switch (obj) {
            case Rectangle r -> r.height() / 2.0;
            case Text t -> t.height() / 2.0;
            case Circle c -> c.radius();
            case Waypoint w -> 0.0;
        };
    }
}
