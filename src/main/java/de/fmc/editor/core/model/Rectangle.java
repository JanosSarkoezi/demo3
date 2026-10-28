package de.fmc.editor.core.model;

import java.util.UUID;

/**
 * Rechteck (FMC-Speicher/Ort). content = innerer mehrzeiliger Text (0..1);
 * centered = Ausrichtungs-Schalter. Der innere Text nutzt die Box des R
 * abzüglich eines kleinen Innenrands — wächst und schrumpft automatisch mit
 * dem R (abgeleitete Geometrie, kein eigener Zustand). x/y ist das Zentrum.
 */
public record Rectangle(
        UUID id,
        double x,
        double y,
        double width,
        double height,
        String content,
        boolean centered,
        UUID layerId
) implements DiagramObject {
    public Rectangle {
        content = content == null ? "" : content;
    }

    /** Kompatibilitäts-Konstruktor ohne inneren Text (Bestands-Fixtures). */
    public Rectangle(UUID id, double x, double y, double width, double height, UUID layerId) {
        this(id, x, y, width, height, "", true, layerId);
    }
}
