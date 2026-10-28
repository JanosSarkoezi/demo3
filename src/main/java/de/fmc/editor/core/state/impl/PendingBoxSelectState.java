package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.state.rule.BaseState;

import java.util.List;

/**
 * Gummiband-Auswahl (Ctrl+Klick+Drag auf leerer Fläche).
 *
 * Commit-on-Release wie bei Resize/Move: Während des Ziehens läuft NICHTS
 * über den Bus — das sichtbare Rechteck ist reine Bildschirm-Präsentation
 * und wird von der späteren UI lokal gezeichnet (sie ist der Ursprung der
 * Maus-Events). Erst beim Loslassen übersetzt der Zustand Start/Ende über
 * die Kamera in Welt-Koordinaten, fragt die Treffer per DiagramQuery ab
 * und publiziert EIN deklaratives SetSelection — das Band ersetzt die
 * bisherige Auswahl (demo2-Semantik).
 */
public final class PendingBoxSelectState extends BaseState {

    private final double startScreenX;
    private final double startScreenY;
    private boolean bandActive = false;

    public PendingBoxSelectState(double startScreenX, double startScreenY) {
        this.startScreenX = startScreenX;
        this.startScreenY = startScreenY;

        // 1. Drag über Schwellenwert -> Gummiband ist aktiv (stumm: Das
        //    sichtbare Rechteck zeichnet die UI lokal, der Bus bleibt ruhig).
        //    Weitere Drags treffen danach bewusst KEINE Regel => Zustand bleibt.
        addRule(InputEvent.MouseDragged.class,
                (e, c) -> !bandActive
                        && distance(startScreenX, startScreenY, e.x(), e.y()) > DRAG_THRESHOLD_PIXELS,
                (e, c) -> {
                    bandActive = true;
                    return this;
                });

        // 2. Loslassen OHNE Band -> reiner Ctrl-Klick ins Leere:
        //    kein Event, Auswahl bleibt unverändert. Guard-disjunkt zur
        //    Band-Regel über bandActive.
        addRule(InputEvent.MouseReleased.class,
                e -> e.button() == CustomMouseButton.PRIMARY && !bandActive,
                (e, c) -> new IdleToolState());

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

                    c.publisher().publish(new ActionEvent.SetSelection(
                            hits.stream().map(DiagramObject::id).toList()));
                    return new IdleToolState();
                });

        // 4. ESC -> Abbruch ohne Event (Auswahl bleibt).
        addRule(InputEvent.KeyPressed.class,
                e -> e.code() == CustomKeyCode.ESCAPE,
                (e, c) -> new IdleToolState());
    }

    private double distance(double x1, double y1, double x2, double y2) {
        return Math.hypot(x2 - x1, y2 - y1);
    }
}
