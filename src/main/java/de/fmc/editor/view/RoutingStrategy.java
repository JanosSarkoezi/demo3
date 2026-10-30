package de.fmc.editor.view;

import de.fmc.editor.core.model.DiagramObject;
import javafx.scene.shape.Path;

import java.util.List;

/**
 * Routing-Strategie für Verbindungslinien (reine View-Sache, 1:1 aus demo2
 * portiert): berechnet den Pfad von der Quelle über die Waypoints zum Ziel.
 * Der Kern kennt keine Geometrie-Strategien — hier darf die UI frei wählen.
 */
public interface RoutingStrategy {

    /**
     * Berechnet den Linienpfad (Welt-Koordinaten).
     *
     * @param source    Start-Objekt (Zentrum)
     * @param target    Ziel-Objekt (Zentrum)
     * @param waypoints Kettenpunkte in Reihenfolge (leer erlaubt)
     */
    Path calculatePath(DiagramObject source, DiagramObject target,
                       List<DiagramObject> waypoints);
}
