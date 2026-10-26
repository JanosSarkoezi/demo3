package de.fmc.editor.core.model;

import java.util.List;
import java.util.UUID;

/**
 * Read-only Sicht auf die aktuelle Auswahl (Sitzungszustand, kein Modellinhalt).
 * Implementiert vom SelectionService; den States über den InteractionContext
 * injiziert — analog zu DiagramQuery.
 */
public interface SelectionQuery {

    /** Geordnete, duplikatfreie Menge der ausgewählten Objekt-IDs. */
    List<UUID> selectedIds();

    default boolean isEmpty() {
        return selectedIds().isEmpty();
    }

    default boolean contains(UUID id) {
        return selectedIds().contains(id);
    }
}
