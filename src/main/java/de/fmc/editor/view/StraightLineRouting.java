package de.fmc.editor.view;

import de.fmc.editor.core.model.DiagramObject;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;

import java.util.List;

/**
 * Gerade Linien durch alle Kettenpunkte — einfachste Routing-Strategie
 * (1:1 aus demo2 portiert, Typen an das sealed demo3-Modell angepasst).
 */
public class StraightLineRouting implements RoutingStrategy {

    @Override
    public Path calculatePath(DiagramObject source, DiagramObject target,
                              List<DiagramObject> waypoints) {
        Path path = new Path();
        path.getElements().add(new MoveTo(source.x(), source.y()));

        if (waypoints != null) {
            for (DiagramObject wp : waypoints) {
                path.getElements().add(new LineTo(wp.x(), wp.y()));
            }
        }

        path.getElements().add(new LineTo(target.x(), target.y()));
        return path;
    }
}
