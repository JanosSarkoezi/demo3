package de.fmc.editor.view;

import javafx.scene.shape.Polygon;
import javafx.scene.transform.Rotate;

/**
 * Pfeilspitzen-Geometrie (demo2-Algorithmus, aus dem ConnectionViewMapper
 * extrahiert): Das Polygon entsteht mit der Spitze in (0,0) und wird per
 * LayoutX/Y + Rotate an der Zielkante ausgerichtet. Verbindungs-Pfeile
 * ({@link ConnectionViewMapper}) und Event-Pfeile ({@link EventRenderer})
 * teilen sich dieselbe Form — EINE Wahrheit für die Pfeil-Optik.
 *
 * @author saj
 */
public final class ArrowShape {

    public static final double ARROW_SIZE = 12.0;    // Länge der Pfeilspitze
    public static final double INDENT_RATIO = 0.65;  // Einkerbungstiefe hinten

    private ArrowShape() {
    }

    /** Einkerbungstiefe hinten — das Liniensende wird um dieses Maß vor der
     *  Spitze zurückgezogen, damit die Linie nicht durch den Pfeil schaut
     *  (demo2-Algorithmus). */
    public static double indent() {
        return ARROW_SIZE * INDENT_RATIO;
    }

    /** Frisches Pfeilspitzen-Polygon: Spitze in (0,0), Einkerbung hinten. */
    public static Polygon create() {
        Polygon arrowHead = new Polygon();
        double indentSize = indent();
        arrowHead.getPoints().addAll(
                0.0, 0.0,                          // Spitze
                -ARROW_SIZE, -ARROW_SIZE * 0.55,    // äußere Ecke links
                -indentSize, 0.0,                   // Einkerbung (Mitte hinten)
                -ARROW_SIZE, ARROW_SIZE * 0.55      // äußere Ecke rechts
        );
        return arrowHead;
    }

    /** Richtet eine (per {@link #create()} erzeugte) Spitze an der Zielkante
     *  aus: Position plus Drehwinkel (Grad) in Segmentrichtung. */
    public static void orient(Polygon arrowHead, double x, double y, double angleDegrees) {
        arrowHead.setLayoutX(x);
        arrowHead.setLayoutY(y);
        if (!arrowHead.getTransforms().isEmpty()
                && arrowHead.getTransforms().get(0) instanceof Rotate rotate) {
            rotate.setAngle(angleDegrees);
        } else {
            arrowHead.getTransforms().setAll(new Rotate(angleDegrees, 0, 0));
        }
    }
}
