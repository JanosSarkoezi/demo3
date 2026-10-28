package de.fmc.editor.core.model;

import java.util.UUID;

/**
 * Kreis (FMC-Akteur). label = innerer Text: 0..1, per Konvention genau ein
 * Buchstabe (die Eingabemaske der UI erzwingt das; das Modell bleibt
 * permissiv). x/y ist das Zentrum.
 */
public record Circle(
        UUID id,
        double x,
        double y,
        double radius,
        String label,
        UUID layerId
) implements DiagramObject {
    public Circle {
        label = label == null ? "" : label;
    }

    /** Kompatibilitäts-Konstruktor ohne inneren Text (Bestands-Fixtures). */
    public Circle(UUID id, double x, double y, double radius, UUID layerId) {
        this(id, x, y, radius, "", layerId);
    }
}
