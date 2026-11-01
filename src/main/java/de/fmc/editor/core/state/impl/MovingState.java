package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.state.InteractionState;
import de.fmc.editor.core.state.rule.BaseState;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

public final class MovingState extends BaseState {

    /**
     * Bezugsobjekt des Snapping: das primär gezogen Objekt (unter dem
     * Cursor bei Press) mit seiner Welt-Position zum GESTEN-START — ein
     * unveränderlicher Positions-Snapshot, bewusst KEIN frischer Read:
     * Die Position bei Press ist die Referenz, gegen die das Ziel snapt.
     */
    record MoveAnchor(UUID objectId, double worldX, double worldY) {
    }

    private final List<UUID> movingIds;
    private final MoveAnchor anchor;
    private final Supplier<InteractionState> returnState;

    // Maus-Tracking: Segment für Segment, aufsummiert seit Gesten-Start.
    // (Das erste Drag-Event wurde vom PendingMoveState-Übergang konsumiert
    // und fließt hier über die Distanz zur Press-Position mit ein.)
    private double lastScreenX;
    private double lastScreenY;
    private double totalScreenX;
    private double totalScreenY;

    /** Bereits publizierte effektive Welt-Deltas des Primär-Objekts —
     *  Buchhaltung über die eigenen Requests statt Modell-Neulesens pro
     *  Segment. Hält das Snapping immun gegen Service-seitige
     *  Modellveränderungen zwischen den Segmenten (z. B. die Gleit-Ableitung
     *  verankerter Texte im selben Batch). */
    private double appliedWorldX;
    private double appliedWorldY;

    /** Kompat-Konstruktor: Rückkehr zum Auswahl-Werkzeug, ohne Snapping. */
    public MovingState(List<UUID> movingIds, double startScreenX, double startScreenY) {
        this(movingIds, null, startScreenX, startScreenY, IdleToolState::new);
    }

    /** Kompat-Konstruktor: ohne Snapping (Primary unbekannt). */
    public MovingState(List<UUID> movingIds, double startScreenX, double startScreenY,
                       Supplier<InteractionState> returnState) {
        this(movingIds, null, startScreenX, startScreenY, returnState);
    }

    /** anchor == null: ungesnapptes, inkrementelles Verschieben (Kompat-
     *  Pfad für Stubs). Mit Anchor: Das Ziel des Primär-Objekts snapt auf
     *  den nächsten Grid-Schnittpunkt (spec.md — „kleben, bis die Maus dem
     *  nächsten Kreuzungspunkt näher liegt"), die Gruppe folgt dem
     *  resultierenden effektiven Delta (demo2-Semantik). */
    public MovingState(List<UUID> movingIds, MoveAnchor anchor,
                       double startScreenX, double startScreenY,
                       Supplier<InteractionState> returnState) {
        this.movingIds = List.copyOf(movingIds);
        this.anchor = anchor;
        this.lastScreenX = startScreenX;
        this.lastScreenY = startScreenY;
        this.returnState = returnState;

        // Verschieben: Zoom-berücksichtigtes Delta feuern. Immer der
        // PLURALE Request — ein einzelnes Objekt ist der Spezialfall n=1;
        // der Service veröffentlicht EINEN Batch-Fakt pro Segment.
        addRule(InputEvent.MouseDragged.class, (e, c) -> {
            // Maus-Segment greifen, BEVOR die Tracking-Positionen wandern
            double segmentScreenX = e.x() - lastScreenX;
            double segmentScreenY = e.y() - lastScreenY;
            totalScreenX += segmentScreenX;
            totalScreenY += segmentScreenY;
            lastScreenX = e.x();
            lastScreenY = e.y();

            double zoom = c.camera().zoom();
            if (anchor == null) {
                // Ohne Bezugsobjekt: klassisch inkrementell (unverändertes
                // Kompat-Verhalten — Segment-Delta direkt, kein Snapping).
                c.publisher().publish(new ActionEvent.MoveObjects(
                        movingIds, segmentScreenX / zoom, segmentScreenY / zoom));
                return this;
            }

            // Gesnapptes Ziel des Primär-Objekts aus der MAUS-SUMME
            // (Segmentsicht würde am Schnittpunkt festkleben: Jedes Segment
            // sähe nur wenige Pixel über der alten Position).
            double targetX = c.grid().snapToGrid(anchor.worldX() + totalScreenX / zoom);
            double targetY = c.grid().snapToGrid(anchor.worldY() + totalScreenY / zoom);

            // Effektives Delta = Ziel minus bereits publizierter Versatz.
            // Null-Delta (Wiggle innerhalb der Zelle) publiziert NICHTS:
            // kein Fakt, kein Undo-Rauschen — das Objekt klebt sichtbar.
            double effectiveX = targetX - (anchor.worldX() + appliedWorldX);
            double effectiveY = targetY - (anchor.worldY() + appliedWorldY);
            if (effectiveX != 0.0 || effectiveY != 0.0) {
                appliedWorldX += effectiveX;
                appliedWorldY += effectiveY;
                c.publisher().publish(new ActionEvent.MoveObjects(
                        movingIds, effectiveX, effectiveY));
            }
            return this;
        });

        // Loslassen beendet das Verschieben
        addRule(InputEvent.MouseReleased.class, (e, c) -> returnState.get());
    }
}
