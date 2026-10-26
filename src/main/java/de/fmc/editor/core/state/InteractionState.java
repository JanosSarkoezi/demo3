package de.fmc.editor.core.state;

import de.fmc.editor.core.event.DomainEvent;

public interface InteractionState {
    /**
     * Verarbeitet ein DomainEvent (Input oder Action) unter Verwendung
     * des aktuellen InteractionContexts und gibt den Folge-Zustand zurück.
     */
    InteractionState handleInput(DomainEvent event, InteractionContext context);
}