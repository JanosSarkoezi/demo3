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

    /** Trägt Vor- UND Nach-Position — der Vor-Zustand macht den Fakt invertierbar (Undo-Regel). */
    record ObjectMoved(UUID id, double oldX, double oldY, double newX, double newY) implements DiagramEvent {}

    /** Batch-Fakt einer Gruppenverschiebung: ein Eintrag je Objekt mit
     *  Vor- und Nach-Position (index-parallel zur Request-ID-Liste). */
    record ObjectsMoved(List<ObjectMoved> movedObjects) implements DiagramEvent {}

    /** Trägt Vor- UND Nach-Zustand — der Vor-Zustand macht den Fakt invertierbar (Undo-Regel). */
    record ObjectResized(DiagramObject previous, DiagramObject resized) implements DiagramEvent {}

    record ConnectionEstablished(Connection connection) implements DiagramEvent {}

    record ConnectionRejected(UUID sourceId, UUID targetId, String reason) implements DiagramEvent {}

    record SelectionChanged(List<UUID> selectedIds) implements DiagramEvent {}

    // --- Batch-Fakten einer Lösch-Geste (vollständiger Zustand für späteres Undo) ---

    /** Alle entfernten Objekte (inkl. mitkaskadierter Waypoints) mit ihren
     *  z-Indizes zum Lösch-Zeitpunkt (objects und zIndices index-parallel) —
     *  der vollständige Zustand macht den Fakt invertierbar (Undo-Regel). */
    record ObjectsRemoved(List<DiagramObject> objects, List<Integer> zIndices) implements DiagramEvent {}

    /** Alle entfernten Connections mit vollständigem Zustand (Waypoint-IDs inklusive). */
    record ConnectionsRemoved(List<Connection> connections) implements DiagramEvent {}

    /** Bestehen gebliebene Connections, deren Waypoint-Listen gestrichen wurden —
     *  previous und updated laufen index-parallel; previous macht den Fakt invertierbar. */
    record ConnectionsUpdated(List<Connection> previous, List<Connection> updated) implements DiagramEvent {}
}
