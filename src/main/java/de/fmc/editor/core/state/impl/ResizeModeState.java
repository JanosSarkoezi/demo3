package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.CustomMouseButton;
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
                    Optional<HandleType> handle = handleAt(e.x(), e.y(), c);
                    if (handle.isPresent()) {
                        return new HandleDragState(target, handle.get());
                    }
                    // Klick daneben (auch auf den Objekt-Körper) -> abwählen
                    c.publisher().publish(new ActionEvent.SetSelection(List.of()));
                    return new IdleToolState();
                });

        // 2. ESC -> abwählen und zurück zum Standard-Werkzeug
        addRule(InputEvent.KeyPressed.class,
                e -> e.code() == CustomKeyCode.ESCAPE,
                (e, c) -> {
                    c.publisher().publish(new ActionEvent.SetSelection(List.of()));
                    return new IdleToolState();
                });
    }

    /** Hit-Test: Welcher Griff (falls einer) liegt in Griff-Nähe der Bildschirm-Position? */
    private Optional<HandleType> handleAt(double screenX, double screenY, InteractionContext context) {
        double worldX = context.camera().screenToWorldX(screenX);
        double worldY = context.camera().screenToWorldY(screenY);
        double grabRadiusWorld = HANDLE_GRAB_RADIUS_PIXELS / context.camera().zoom();

        return HandlePositions.of(target).entrySet().stream()
                .filter(entry -> Math.hypot(
                        entry.getValue().x() - worldX,
                        entry.getValue().y() - worldY) <= grabRadiusWorld)
                .map(Map.Entry::getKey)
                .findFirst();
    }
}
