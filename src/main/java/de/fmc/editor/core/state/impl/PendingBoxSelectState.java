package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.model.Waypoint;
import de.fmc.editor.core.state.InteractionState;
import de.fmc.editor.core.state.rule.BaseState;

import java.util.List;
import java.util.function.Supplier;

/**
 * Gummiband-Auswahl (Ctrl+Klick+Drag auf leerer Fläche).
 *
 * Commit-on-Release wie bei Resize/Move: Während des Ziehens läuft KEIN
 * Modell-Event über den Bus — nur der {@link ActionEvent.BoxSelectPreviewChanged}-Hinweis
 * mit dem aktuellen Bildschirm-Rechteck, den die UI direkt zeichnet. Erst
 * beim Loslassen übersetzt der Zustand Start/Ende über die Kamera in
 * Welt-Koordinaten, fragt die Treffer per DiagramQuery ab und publiziert
 * EIN deklaratives SetSelection — das Band ersetzt die bisherige Auswahl
 * (demo2-Semantik).
 *
 * Zwei Kontext-Parameter: includeWaypoints entscheidet, ob Ws Band-Ziele
 * sind (nur der RouteEditMode lässt sie zu — im Idle sind sie unsichtbar
 * und dürften keine heimlichen Treffer liefern); returnState bestimmt den
 * Zustand nach der Geste (Idle bzw. zurück in den RouteEditMode).
 */
public final class PendingBoxSelectState extends BaseState {

    private final double startScreenX;
    private final double startScreenY;
    private final boolean includeWaypoints;
    private final Supplier<InteractionState> returnState;
    private boolean bandActive = false;

    /** Kompat-Konstruktor (Idle-Semantik): Band ohne Waypoints, Rückkehr zum Auswahl-Werkzeug. */
    public PendingBoxSelectState(double startScreenX, double startScreenY) {
        this(startScreenX, startScreenY, false, IdleToolState::new);
    }

    public PendingBoxSelectState(double startScreenX, double startScreenY,
                                 boolean includeWaypoints,
                                 Supplier<InteractionState> returnState) {
        this.startScreenX = startScreenX;
        this.startScreenY = startScreenY;
        this.includeWaypoints = includeWaypoints;
        this.returnState = returnState;

        // 1. Drag über Schwellenwert -> Gummiband aktiviert: erster Hinweis
        //    mit dem aktuellen Rechteck. Guard-disjunkt zur Folge-Regel über
        //    bandActive.
        addRule(InputEvent.MouseDragged.class,
                (e, c) -> !bandActive
                        && distance(startScreenX, startScreenY, e.x(), e.y()) > DRAG_THRESHOLD_PIXELS,
                (e, c) -> {
                    bandActive = true;
                    c.publisher().publish(new ActionEvent.BoxSelectPreviewChanged(
                            startScreenX, startScreenY, e.x(), e.y()));
                    return this;
                });

        // 1b. Weitere Drags: Live-Rechteck nachführen (eine Geste, viele Hinweise).
        addRule(InputEvent.MouseDragged.class,
                (e, c) -> bandActive,
                (e, c) -> {
                    c.publisher().publish(new ActionEvent.BoxSelectPreviewChanged(
                            startScreenX, startScreenY, e.x(), e.y()));
                    return this;
                });

        // 2. Loslassen OHNE Band -> reiner Ctrl-Klick ins Leere:
        //    kein Event, Auswahl bleibt unverändert. Guard-disjunkt zur
        //    Band-Regel über bandActive.
        addRule(InputEvent.MouseReleased.class,
                e -> e.button() == CustomMouseButton.PRIMARY && !bandActive,
                (e, c) -> returnState.get());

        // 3. Loslassen MIT Band -> Treffer im Welt-Rechteck als neue Auswahl.
        addRule(InputEvent.MouseReleased.class,
                e -> e.button() == CustomMouseButton.PRIMARY && bandActive,
                (e, c) -> {
                    double startWorldX = c.camera().screenToWorldX(startScreenX);
                    double startWorldY = c.camera().screenToWorldY(startScreenY);
                    double endWorldX = c.camera().screenToWorldX(e.x());
                    double endWorldY = c.camera().screenToWorldY(e.y());

                    List<DiagramObject> hits = c.diagram().findObjectsInBounds(
                            Math.min(startWorldX, endWorldX),
                            Math.min(startWorldY, endWorldY),
                            Math.max(startWorldX, endWorldX),
                            Math.max(startWorldY, endWorldY));

                    // Im Idle sind Ws unsichtbar -> keine heimlichen Band-Treffer.
                    // (Der RouteEditMode startet das Band mit includeWaypoints.)
                    if (!includeWaypoints) {
                        hits = hits.stream()
                                .filter(obj -> !(obj instanceof Waypoint))
                                .toList();
                    }

                    c.publisher().publish(new ActionEvent.BoxSelectPreviewChanged(
                            null, null, null, null));
                    c.publisher().publish(new ActionEvent.SetSelection(
                            hits.stream().map(DiagramObject::id).toList()));
                    return returnState.get();
                });

        // 4. ESC -> Abbruch: Band-Hinweis aufräumen (falls aktiv), Auswahl bleibt.
        addRule(InputEvent.KeyPressed.class,
                e -> e.code() == CustomKeyCode.ESCAPE,
                (e, c) -> {
                    if (bandActive) {
                        c.publisher().publish(new ActionEvent.BoxSelectPreviewChanged(
                                null, null, null, null));
                    }
                    return returnState.get();
                });
    }

    private double distance(double x1, double y1, double x2, double y2) {
        return Math.hypot(x2 - x1, y2 - y1);
    }
}
