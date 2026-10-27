package de.fmc.editor.core.event;

import de.fmc.editor.core.model.Connection;
import de.fmc.editor.core.model.DiagramObject;

import java.util.List;
import java.util.UUID;

/**
 * Fakten-Events (DiagramService & SelectionService): Antwort auf ActionEvents
 * (Requests). Erfolgreich angewandte Änderungen, Ablehnungen mit Begründung
 * und Auswahl-Änderungen. Daran dockt die spätere UI-Schicht (ViewMapper) an.
 */
public sealed interface DiagramEvent extends DomainEvent {

    record ObjectAdded(DiagramObject object) implements DiagramEvent {}

    record ObjectMovedApplied(UUID id, double newX, double newY) implements DiagramEvent {}

    /** Trägt das geänderte Objekt (analog zu ObjectAdded) — Kreis & Rechteck teilen diesen Fakt. */
    record ObjectResizedApplied(DiagramObject resizedObject) implements DiagramEvent {}

    record ConnectionEstablished(Connection connection) implements DiagramEvent {}

    record ConnectionRejected(UUID sourceId, UUID targetId, String reason) implements DiagramEvent {}

    record SelectionChanged(List<UUID> selectedIds) implements DiagramEvent {}

    // --- Batch-Fakten einer Lösch-Geste (vollständiger Zustand für späteres Undo) ---

    /** Alle entfernten Objekte (inkl. mitkaskadierter Waypoints). */
    record ObjectsRemoved(List<DiagramObject> objects) implements DiagramEvent {}

    /** Alle entfernten Connections mit vollständigem Zustand (Waypoint-IDs inklusive). */
    record ConnectionsRemoved(List<Connection> connections) implements DiagramEvent {}

    /** Connections, deren Waypoint-Listen gestrichen wurden, die aber bestehen bleiben. */
    record ConnectionsUpdated(List<Connection> connections) implements DiagramEvent {}
}
