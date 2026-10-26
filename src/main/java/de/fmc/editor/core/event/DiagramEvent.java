package de.fmc.editor.core.event;

import de.fmc.editor.core.model.Connection;
import de.fmc.editor.core.model.DiagramObject;

import java.util.UUID;

/**
 * Fakten-Events des DiagramService: Antwort auf ActionEvents (Requests).
 * Erfolgreich angewandte Änderungen sowie Ablehnungen mit Begründung.
 * Daran dockt die spätere UI-Schicht (ViewMapper) an.
 */
public sealed interface DiagramEvent extends DomainEvent {

    record ObjectAdded(DiagramObject object) implements DiagramEvent {}

    record ObjectMovedApplied(UUID id, double newX, double newY) implements DiagramEvent {}

    record ObjectResizedApplied(UUID id, double newWidth, double newHeight) implements DiagramEvent {}

    record ConnectionEstablished(Connection connection) implements DiagramEvent {}

    record ConnectionRejected(UUID sourceId, UUID targetId, String reason) implements DiagramEvent {}
}
