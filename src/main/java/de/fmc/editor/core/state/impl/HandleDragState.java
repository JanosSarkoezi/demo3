package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.model.HandleType;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.Text;
import de.fmc.editor.core.state.InteractionContext;
import de.fmc.editor.core.state.rule.BaseState;

/**
 * Aktives Ziehen an einem Resize-Handle.
 *
 * Während des Drags wird die Geometrie pro Drag-Segment als
 * {@link ActionEvent.ResizePreviewChanged}-HINWEIS publiziert (Live-Vorschau:
 * der Kern rechnet — symmetrisch ums Zentrum, Grid-Klemmung —, die View
 * rendert nur). Der MODELL-Zustand ändert sich weiterhin erst beim Commit:
 * Beim Loslassen genau EIN Resize-Request, die Vorschau wird mit einem
 * Null-Hinweis beendet. Ein Abbruch (ESC) endet ebenfalls mit dem
 * Null-Hinweis — die View stellt dann aus dem Modell her, da kein Fakt kommt.
 *
 * Nach dem Commit kehrt die Geste in den {@link ResizeModeState} zurück
 * (Option B, 30.10.: Modus bleibt für weitere Griffe) — mit dem FRISCHEN
 * Record aus dem Modell: das beim Eintritt geschnappte target trägt die
 * Alt-Geometrie, die Griff-Hit-Tests würden sonst gegen veraltete
 * Positionen laufen (User-Fund „Griffe nicht mehr greifbar").
 */
public final class HandleDragState extends BaseState {

    /** Kleinste zulässige Größe pro Dimension, in Grid-Zellen. */
    private static final double MIN_SIZE_CELLS = 1.0;

    private final DiagramObject target;
    private final HandleType handle;

    /** Letzte Maus-Position in Welt-Koordinaten; null = noch nicht gezogen. */
    private Double lastWorldX;
    private Double lastWorldY;

    public HandleDragState(DiagramObject target, HandleType handle) {
        this.target = target;
        this.handle = handle;

        // 1. Ziehen: Geometrie live als Hinweis publizieren — der Kern ist
        //    die einzige Rechen-Wahrheit (symmetrisch, geklemmt), die View
        //    rendert nur. Kein Modell-Fakt, kein Undo-Eintrag.
        addRule(InputEvent.MouseDragged.class, (e, c) -> {
            this.lastWorldX = c.camera().screenToWorldX(e.x());
            this.lastWorldY = c.camera().screenToWorldY(e.y());
            c.publisher().publish(new ActionEvent.ResizePreviewChanged(computeGeometry(c)));
            return this;
        });

        // 2. Loslassen: finale Größe committen — EIN Request pro Geste.
        //    Danach Vorschau beenden und mit dem FRISCHEN Record in den
        //    Resize-Modus zurückkehren (der Bus ist synchron: Nach dem
        //    Commit-Request hält das Modell bereits die neue Geometrie.)
        addRule(InputEvent.MouseReleased.class, (e, c) -> {
            commitResize(c);
            c.publisher().publish(new ActionEvent.ResizePreviewChanged(null));
            DiagramObject fresh = c.diagram().findById(target.id()).orElse(target);
            return new ResizeModeState(fresh);
        });

        // 3. ESC: Abbruch ohne Commit — Vorschau beenden (die View stellt
        //    aus dem Modell her). Kein Commit passiert: Das beim Eintritt
        //    geschnappte target ist noch aktuell.
        addRule(InputEvent.KeyPressed.class,
                e -> e.code() == CustomKeyCode.ESCAPE,
                (e, c) -> {
                    c.publisher().publish(new ActionEvent.ResizePreviewChanged(null));
                    return new ResizeModeState(target);
                });
    }

    /** Die Geometrie des aktuellen Drag-Zustands — dieselbe Mathematik für
     *  Live-Vorschau UND Commit, damit beide nie auseinanderlaufen können.
     *  Ohne Drag-Bewegung: das unveränderte target. */
    private DiagramObject computeGeometry(InteractionContext context) {
        if (lastWorldX == null || lastWorldY == null) {
            return target;
        }
        double minSize = context.grid().gridSize() * MIN_SIZE_CELLS;

        // Jeder Kreis-Griff: Radius = Abstand Maus <-> Zentrum (symmetrisch).
        // Voller Konstruktor: label mitführen (Kompat-Konstruktor würde es
        // still zurücksetzen — dieselbe Falle wie moved()).
        if (target instanceof Circle circle) {
            double radius = Math.max(
                    Math.hypot(lastWorldX - circle.x(), lastWorldY - circle.y()),
                    minSize);
            return new Circle(circle.id(), circle.x(), circle.y(),
                    radius, circle.label(), circle.layerId());
        }

        if (target instanceof Rectangle rectangle) {
            double[] box = boxGeometry(rectangle.x(), rectangle.y(),
                    rectangle.width(), rectangle.height(), minSize);
            return new Rectangle(rectangle.id(), rectangle.x(), rectangle.y(),
                    box[0], box[1], rectangle.content(), rectangle.centered(),
                    rectangle.layerId());
        }

        // Text-Box: dieselbe symmetrische Box-Mathematik wie das R (beide
        // nutzen die gemeinsamen ofBox-Griffe) — nur Maße ändern, Anker/
        // Owner/Position bleiben (verankerte Texte resizen mit, ihre
        // Position leitet weiterhin der Service ab).
        if (target instanceof Text text) {
            double[] box = boxGeometry(text.x(), text.y(),
                    text.width(), text.height(), minSize);
            return new Text(text.id(), text.content(), text.x(), text.y(),
                    box[0], box[1], text.anchor(), text.ownerId(), text.layerId());
        }

        return target;
    }

    /** Gemeinsame Box-Resize-Mathematik für Rectangle und Text: symmetrisch
     *  ums Zentrum, geklemmt auf mindestens eine Grid-Zelle; Ecken-Griffe
     *  ändern beide Dimensionen, Seiten-Griffe nur ihre Richtung. */
    private double[] boxGeometry(double centerX, double centerY,
                                 double width, double height, double minSize) {
        double newWidth = width;
        double newHeight = height;

        boolean affectsWidth = handle == HandleType.W || handle == HandleType.E
                || handle == HandleType.NW || handle == HandleType.NE
                || handle == HandleType.SW || handle == HandleType.SE;
        boolean affectsHeight = handle == HandleType.N || handle == HandleType.S
                || handle == HandleType.NW || handle == HandleType.NE
                || handle == HandleType.SW || handle == HandleType.SE;

        if (affectsWidth) {
            newWidth = Math.max(2 * Math.abs(lastWorldX - centerX), minSize);
        }
        if (affectsHeight) {
            newHeight = Math.max(2 * Math.abs(lastWorldY - centerY), minSize);
        }
        return new double[]{newWidth, newHeight};
    }

    private void commitResize(InteractionContext context) {
        if (lastWorldX == null || lastWorldY == null) {
            return; // Kein Drag passiert -> nichts zu committen
        }
        DiagramObject result = computeGeometry(context);
        if (result instanceof Circle circle) {
            context.publisher().publish(new ActionEvent.ResizeCircle(circle.id(), circle.radius()));
        } else if (result instanceof Rectangle rectangle) {
            context.publisher().publish(
                    new ActionEvent.ResizeBox(rectangle.id(), rectangle.width(), rectangle.height()));
        } else if (result instanceof Text text) {
            context.publisher().publish(
                    new ActionEvent.ResizeBox(text.id(), text.width(), text.height()));
        }
    }
}
