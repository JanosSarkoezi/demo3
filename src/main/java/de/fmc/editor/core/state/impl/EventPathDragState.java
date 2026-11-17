package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Event;
import de.fmc.editor.core.model.EventGeometry;
import de.fmc.editor.core.model.EventHandle;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.state.InteractionContext;
import de.fmc.editor.core.state.rule.BaseState;

/**
 * Aktives, achs-gebundenes Ziehen an einem Event-Formgriff (17.11.):
 * START zieht nur HORIZONTAL (ändert horizontalExtent — wie weit der
 * Ausleger über den Seitenrand hinausreicht), KINK zieht nur VERTIKAL
 * (ändert verticalRise — die Höhe über der oberen Kante). Die andere Achse
 * wird still verworfen, nicht etwa auf den Startwert geklemmt: Nur die
 * gewollte Länge ändert sich, der Rest des Symbols bleibt, wo es ist.
 *
 * Dasselbe Muster wie {@link HandleDragState}: Während des Drags wird die
 * Form pro Segment als {@link ActionEvent.ResizePreviewChanged}-HINWEIS
 * publiziert (der Kern rechnet — geklemmt auf die Mindestlänge = Bogen-
 * Radius —, die View rendert nur). Der MODELL-Zustand ändert sich erst
 * beim Loslassen: genau EIN ResizeEvent-Request; die Vorschau endet mit
 * dem Null-Hinweis. ESC bricht ohne Commit ab — die View stellt aus dem
 * Modell her, es kam kein Fakt.
 *
 * Die Spitze bleibt während der ganzen Geste ans R gedockt: Sie ist eine
 * reine Funktion von (Owner-Geometrie, Seite) und ändert sich durch die
 * Längen nicht.
 *
 * @author saj
 */
public final class EventPathDragState extends BaseState {

    private final Event target;
    private final EventHandle handle;

    /** Letzte Maus-Position in Welt-Koordinaten; null = noch nicht gezogen. */
    private Double lastWorldX;
    private Double lastWorldY;

    public EventPathDragState(Event target, EventHandle handle) {
        this.target = target;
        this.handle = handle;

        // 1. Ziehen: Form live als Hinweis publizieren — der Kern ist die
        //    einzige Rechen-Wahrheit (achs-gebunden, geklemmt), die View
        //    rendert nur. Kein Modell-Fakt, kein Undo-Eintrag.
        addRule(InputEvent.MouseDragged.class, (e, c) -> {
            this.lastWorldX = c.camera().screenToWorldX(e.x());
            this.lastWorldY = c.camera().screenToWorldY(e.y());
            c.publisher().publish(new ActionEvent.ResizePreviewChanged(computePreview(c)));
            return this;
        });

        // 2. Loslassen: finale Form committen — EIN Request pro Geste —
        //    und mit frischem Blick in das Werkzeug zurückkehren.
        addRule(InputEvent.MouseReleased.class, (e, c) -> {
            Event preview = computePreview(c);
            if (lastWorldX != null && shapeChanged(preview)) {
                c.publisher().publish(new ActionEvent.ResizeEvent(
                        target.id(), preview.horizontalExtent(), preview.verticalRise()));
            }
            c.publisher().publish(new ActionEvent.ResizePreviewChanged(null));
            return new EventToolState();
        });

        // 3. ESC: Abbruch ohne Commit — Vorschau beenden (die View stellt
        //    aus dem Modell her; kein Fakt kam, nichts ist zu restaurieren).
        addRule(InputEvent.KeyPressed.class,
                e -> e.code() == CustomKeyCode.ESCAPE,
                (e, c) -> {
                    c.publisher().publish(new ActionEvent.ResizePreviewChanged(null));
                    return new EventToolState();
                });
    }

    /** Die Form des aktuellen Drag-Zustands — dieselbe Mathematik für
     *  Live-Vorschau UND Commit, damit beide nie auseinanderlaufen können.
     *  Ohne Drag-Bewegung: das unveränderte target. */
    private Event computePreview(InteractionContext context) {
        if (lastWorldX == null || lastWorldY == null) {
            return target;
        }
        if (!(context.diagram().findById(target.ownerId()).orElse(null) instanceof Rectangle owner)) {
            return target; // Kaskaden-Fenster: ohne Owner keine Form-Änderung
        }

        double extent = target.horizontalExtent();
        double rise = target.verticalRise();
        if (handle == EventHandle.START) {
            // Nur horizontal: Abstand des Ziehens vom Seitenrand, in
            // Seitenrichtung gemessen, geklemmt auf die Mindestlänge.
            double sideEdgeX = owner.x() + target.side().direction() * owner.width() / 2.0;
            extent = Math.max(EventGeometry.MIN_SEGMENT,
                    target.side().direction() * (lastWorldX - sideEdgeX));
        } else {
            // Nur vertikal: Abstand des Ziehens von der Oberkante.
            double topEdgeY = owner.y() - owner.height() / 2.0;
            rise = Math.max(EventGeometry.MIN_SEGMENT, topEdgeY - lastWorldY);
        }
        return new Event(target.id(), target.ownerId(), target.side(), target.label(),
                extent, rise, target.x(), target.y(), target.layerId());
    }

    /** True, wenn die Preview-Form vom Press-Zustand abweicht — nur dann
     *  committet das Loslassen (Klick-ohne-Drag bleibt still). */
    private boolean shapeChanged(Event preview) {
        return preview.horizontalExtent() != target.horizontalExtent()
                || preview.verticalRise() != target.verticalRise();
    }
}
