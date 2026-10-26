package de.fmc.editor.core.service;

import de.fmc.editor.core.model.DiagramObject;

import java.util.Optional;

/**
 * Validierungsregel für Verbindungen.
 * Leer = erlaubt, gefüllt = Ablehnungsgrund.
 */
@FunctionalInterface
public interface ConnectionRule {
    Optional<String> violation(DiagramObject source, DiagramObject target);
}
