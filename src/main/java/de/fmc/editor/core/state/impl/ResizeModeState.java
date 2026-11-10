package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.model.HandlePositions;
import de.fmc.editor.core.model.HandleType;
import de.fmc.editor.core.state.InteractionContext;
import de.fmc.editor.core.state.rule.BaseState;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resize-Modus für genau EIN ausgewähltes Objekt (Einstieg per Doppelklick
 * aus dem IdleToolState). Bietet die Griff-Geometrie des Ziels an und startet
 * bei Griff-Kontakt den {@link HandleDragState}.
 *
 * Die Auswahl-Menge selbst verwaltet der SelectionService — dieser State
 * publiziert nur die entsprechenden SetSelection-Requests.
 */
public final class ResizeModeState extends BaseState {

    /** Griff-Radius in Bildschirm-Pixeln (wird über den Zoom in Weltkoordinaten umgerechnet). */
    private static final double HANDLE_GRAB_RADIUS_PIXELS = 6.0;

    private final DiagramObject target;

    public ResizeModeState(DiagramObject target) {
        this.target = target;

        // 1. Griff getroffen -> Resize-Geste starten
        addRule(InputEvent.MousePressed.class,
                e -> e.button() == CustomMouseButton.PRIMARY,
                (e, c) -> {
                    // Ziel FRISCH lesen statt das Entry-Record zu vertrauen:
                    // Das Modell kann die Geometrie zwischen Modus-Eintritt
                    // und Press verändert haben (Undo/Redo eines Moves oder
                    // Resizes) — dieselbe Regel wie beim Rückkehr-Fresh-Read
                    // nach dem Griff-Commit („Griffe nicht mehr greifbar").
                    // Das frische Record wandert weiter in den HandleDragState,
                    // sonst rechnete dessen Vorschau gegen die Alt-Geometrie.
                    Optional<DiagramObject> current = c.diagram().findById(target.id());
                    Optional<HandleType> handle = current.flatMap(
                            obj -> handleAt(obj, e.x(), e.y(), c));
                    if (handle.isPresent()) {
                        return new HandleDragState(current.get(), handle.get());
                    }
                    // Klick daneben (auch auf den Objekt-Körper) -> abwählen
                    c.publisher().publish(new ActionEvent.SetSelection(List.of()));
                    c.publisher().publish(new ActionEvent.ResizeModeChanged(null));
                    return new IdleToolState();
                });

        // 2. ESC -> abwählen und zurück zum Standard-Werkzeug
        addRule(InputEvent.KeyPressed.class,
                e -> e.code() == CustomKeyCode.ESCAPE,
                (e, c) -> {
                    c.publisher().publish(new ActionEvent.SetSelection(List.of()));
                    c.publisher().publish(new ActionEvent.ResizeModeChanged(null));
                    return new IdleToolState();
                });

        // 3. Werkzeug-Wechsel (Toolbar-Klick) im Resize-Modus: Modus sauber
        // verlassen und in das angeforderte Werkzeug wechseln — ohne diese
        // Regel bliebe der Modus (und die UI-Griffe) hängen.
        addRule(ActionEvent.SelectTool.class, (e, c) -> {
            c.publisher().publish(new ActionEvent.ResizeModeChanged(null));
            return switch (e.tool()) {
                case CIRCLE -> new CircleToolState();
                case RECTANGLE -> new RectangleToolState();
                case CONNECT -> new ConnectToolState();
                case TEXT -> new TextToolState();
                case SELECT -> new IdleToolState();
            };
        });

        // 4. Ziel-Sterben (z. B. Undo der Erstellung): Der Modus endet mit
        //    seinem Ziel — auch über Modell-Pfade, nicht nur Interaktions-
        //    pfade. Der Bus reicht ObjectsRemoved an die Tool-Machine; ohne
        //    diese Regel hingen Modus UND View-Griffe an einem toten Record
        //    (User-Fund: Undo nach Doppelklick ließ die Griffe kleben).
        addRule(DiagramEvent.ObjectsRemoved.class,
                (e, c) -> e.objects().stream()
                        .anyMatch(obj -> obj.id().equals(target.id())),
                (e, c) -> {
                    c.publisher().publish(new ActionEvent.ResizeModeChanged(null));
                    return new IdleToolState();
                });
    }

    /** Hit-Test: Welcher Griff (falls einer) liegt in Griff-Nähe der Bildschirm-Position?
     *  Rechnet gegen das übergebene (frisch gelesene) Objekt — nicht gegen das
     *  Entry-Record des Modus. */
    private Optional<HandleType> handleAt(DiagramObject current, double screenX, double screenY,
                                          InteractionContext context) {
        double worldX = context.camera().screenToWorldX(screenX);
        double worldY = context.camera().screenToWorldY(screenY);
        double grabRadiusWorld = HANDLE_GRAB_RADIUS_PIXELS / context.camera().zoom();

        return HandlePositions.of(current).entrySet().stream()
                .filter(entry -> Math.hypot(
                        entry.getValue().x() - worldX,
                        entry.getValue().y() - worldY) <= grabRadiusWorld)
                .map(Map.Entry::getKey)
                .findFirst();
    }
}
