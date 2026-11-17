package de.fmc.editor.view;

import de.fmc.editor.core.model.Event;
import de.fmc.editor.core.model.EventGeometry;
import de.fmc.editor.core.model.Rectangle;
import javafx.geometry.VPos;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.Polygon;
import javafx.scene.text.Font;
import javafx.scene.text.Text;

import java.util.List;

/**
 * Stummer Renderer des Event-Symbols (gebogener Pfeil mit Label am
 * Pfadanfang, 17.11.): Node-Aufbau und In-place-Update des von der
 * {@link GraphView} verwalteten Groups. Die Geometrie kommt vollständig aus
 * {@link EventGeometry} (Kern = die einzige Rechen-Wahrheit) — die View
 * rendert nur. Wie alle Modell-Nodes ist der Group mouseTransparent;
 * Hit-Tests macht allein der Kern.
 *
 * Kinder-Vertrag (an GraphView.updateObjectNode gebunden):
 * Kind 0 = Path (Haupt-Shape für Stroke/Selektions-/Hover-Glow),
 * Kind 1 = Pfeilspitzen-Polygon (geteilt mit ConnectionViewMapper über
 * {@link ArrowShape}), Kind 2 = Label-Text.
 *
 * @author saj
 */
public final class EventRenderer {

    private static final Font LABEL_FONT = Font.font(14);
    /** Kleiner Absatz zwischen horizontalem Segment und Label-Basislinie —
     *  derselbe Wert wie in der Hit-Box des Kerns (EventGeometry). */
    private static final double LABEL_BASELINE_GAP = 2.0;

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

        Text label = new Text();
        label.setFont(LABEL_FONT);
        label.setFill(Color.BLACK);
        // Label sitzt ÜBER dem horizontalen Segment: Baseline-Absatz nach
        // unten (VPos.BOTTOM), horizontal am Startpunkt zentriert.
        label.setTextOrigin(VPos.BOTTOM);

        node.getChildren().addAll(path, arrowHead, label);
    }

    /**
     * Aktualisiert Geometrie und Label in-place. Ohne Owner (Kaskaden-
     * Fenster) bleibt der letzte Stand stehen — der Node verschwindet mit
     * dem ohnehin folgenden Remove-Fakt.
     */
    public static void updateNode(Group node, Event event, Rectangle owner) {
        List<double[]> points = EventGeometry.pathPoints(owner, event.side(),
                event.horizontalExtent(), event.verticalRise());

        Path path = (Path) node.getChildren().get(0);
        path.getElements().clear();
        path.getElements().add(new MoveTo(points.get(0)[0], points.get(0)[1]));
        for (int i = 1; i < points.size() - 1; i++) {
            path.getElements().add(new LineTo(points.get(i)[0], points.get(i)[1]));
        }

        // Letztes Segment bis zur Pfeil-Einkerbung zurückziehen (demo2-
        // Algorithmus, wie beim Verbindungspfeil) und Spitze an der R-Kante
        // ausrichten: Richtung Knick → Spitze ist immer senkrecht nach unten.
        double[] tip = points.get(points.size() - 1);
        double[] beforeTip = points.get(points.size() - 2);
        double angle = Math.atan2(tip[1] - beforeTip[1], tip[0] - beforeTip[0]);
        double indent = ArrowShape.indent();
        path.getElements().add(new LineTo(
                tip[0] - indent * Math.cos(angle),
                tip[1] - indent * Math.sin(angle)));

        Polygon arrowHead = (Polygon) node.getChildren().get(1);
        ArrowShape.orient(arrowHead, tip[0], tip[1], Math.toDegrees(angle));

        Text label = (Text) node.getChildren().get(2);
        label.setText(event.label());
        label.setX(points.get(0)[0] - label.getBoundsInLocal().getWidth() / 2);
        label.setY(points.get(0)[1] - LABEL_BASELINE_GAP);
    }
}
