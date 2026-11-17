package de.fmc.editor.view;

import de.fmc.editor.core.model.Event;
import de.fmc.editor.core.model.EventGeometry;
import de.fmc.editor.core.model.Rectangle;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.Polygon;

import java.util.List;

/**
 * Stummer Renderer des Event-Symbols (gebogener Pfeil, 17.11.): Node-Aufbau
 * und In-place-Update des von der {@link GraphView} verwalteten Groups. Die
 * Geometrie kommt vollständig aus {@link EventGeometry} (Kern = die einzige
 * Rechen-Wahrheit) — die View rendert nur. Wie alle Modell-Nodes ist der
 * Group mouseTransparent; Hit-Tests macht allein der Kern.
 *
 * Das Label ist seit 23.11. KEIN Kind mehr des Event-Nodes: Es ist ein
 * eigenes Text-Objekt (Kette R → E → Text) und rendert als eigener Node mit
 * voller Text-Semantik (mehrzeilig, bewegbar, selektierbar). Kinder-Vertrag:
 * Kind 0 = Path (Haupt-Shape für Stroke/Selektions-/Hover-Glow), Kind 1 =
 * Pfeilspitzen-Polygon (geteilt mit ConnectionViewMapper über
 * {@link ArrowShape}).
 *
 * @author saj
 */
public final class EventRenderer {

    private EventRenderer() {
    }

    /** Legt die typisierten Kind-Nodes einmalig an. */
    public static void buildNode(Group node) {
        Path path = new Path();
        path.setStroke(Color.BLACK);
        path.setStrokeWidth(1.5);
        path.setFill(null);

        Polygon arrowHead = ArrowShape.create();
        arrowHead.setFill(Color.BLACK);
        arrowHead.setStroke(Color.BLACK);
        arrowHead.setStrokeWidth(1.0);

        node.getChildren().addAll(path, arrowHead);
    }

    /**
     * Aktualisiert die Geometrie in-place. Ohne Owner (Kaskaden-Fenster)
     * bleibt der letzte Stand stehen — der Node verschwindet mit dem
     * ohnehin folgenden Remove-Fakt. Die Pfeilspitze orientiert sich am
     * LETZTEN Segment: bei INCOMING zeigt sie senkrecht nach unten in R,
     * bei OUTGOING waagrecht nach außen (26.11.) — deshalb braucht diese
     * Methode für beide Richtungen nur dieselbe Punktliste.
     */
    public static void updateNode(Group node, Event event, Rectangle owner) {
        List<double[]> points = EventGeometry.pathPoints(owner, event.side(), event.direction(),
                event.horizontalExtent(), event.verticalRise());

        Path path = (Path) node.getChildren().get(0);
        path.getElements().clear();
        path.getElements().add(new MoveTo(points.get(0)[0], points.get(0)[1]));
        for (int i = 1; i < points.size() - 1; i++) {
            path.getElements().add(new LineTo(points.get(i)[0], points.get(i)[1]));
        }

        // Letztes Segment bis zur Pfeil-Einkerbung zurückziehen (demo2-
        // Algorithmus, wie beim Verbindungspfeil); die Richtung folgt dem
        // letzten Segment der Punkteliste — ohne Renderer-Wissen über die
        // Event-Richtung.
        double[] tip = points.get(points.size() - 1);
        double[] beforeTip = points.get(points.size() - 2);
        double angle = Math.atan2(tip[1] - beforeTip[1], tip[0] - beforeTip[0]);
        double indent = ArrowShape.indent();
        path.getElements().add(new LineTo(
                tip[0] - indent * Math.cos(angle),
                tip[1] - indent * Math.sin(angle)));

        Polygon arrowHead = (Polygon) node.getChildren().get(1);
        ArrowShape.orient(arrowHead, tip[0], tip[1], Math.toDegrees(angle));
    }
}
