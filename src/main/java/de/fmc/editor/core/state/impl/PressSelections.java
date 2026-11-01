package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.state.InteractionContext;
import de.fmc.editor.core.state.InteractionState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Die gemeinsame Press-Semantik von {@link IdleToolState} und
 * {@link RouteEditModeState}: Selektion bei Maus-Druck (einfach = exklusiv,
 * Ctrl = Toggle, Plain-Press auf bereits Selektiertem kollabiert die Menge
 * nicht — Gruppen-Move) plus Move-Vorbereitung über die effektive Move-Menge
 * ({@code findMoveSet}: Basis + externe Label + Ws vollselektierter
 * Verbindungen). EINE Semantik, zwei Zustände — sie darf nicht auseinander
 * drifteten (demo2-Lektion: parallele Wahrheiten verkrauten).
 */
final class PressSelections {

    private PressSelections() {
    }

    /**
     * Publiziert das SetSelection für den Press auf targetId und liefert den
     * vorbereiteten {@link PendingMoveState} mit dem Rückkehr-Ziel des
     * aufrufenden Zustands. targetId ist zugleich das PRIMÄR gezogene
     * Objekt — es wird an die Move-Geste gereicht und ist das
     * Snapping-Bezugssystem (Ziel snapt, Gruppe folgt dem Delta).
     */
    static PendingMoveState selectAndPrepareMove(
            InteractionContext context, InputEvent.MousePressed event,
            UUID targetId, Supplier<InteractionState> returnState) {

        List<UUID> current = context.selection().selectedIds();
        List<UUID> newSelection;
        if (event.controlDown()) {
            newSelection = new ArrayList<>(current);
            newSelection.remove(targetId); // No-Op, falls nicht enthalten
            if (!current.contains(targetId)) {
                newSelection.add(targetId);
            }
        } else if (current.contains(targetId)) {
            newSelection = current;
        } else {
            newSelection = List.of(targetId);
        }
        context.publisher().publish(new ActionEvent.SetSelection(newSelection));

        // Verschiebe-Menge: die Auswahl, sofern sie das Ziel enthält
        // (Ctrl-Toggle-out zieht nur das Ziel selbst).
        List<UUID> base = newSelection.contains(targetId)
                ? newSelection
                : List.of(targetId);
        List<UUID> movingIds = context.diagram().findMoveSet(base);

        return new PendingMoveState(movingIds, targetId, event.x(), event.y(), returnState);
    }
}
