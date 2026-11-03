package de.fmc.editor.core.persistence;

import java.util.UUID;

/**
 * DTO einer Ebene (Sichtbarkeits-Steuerung). Das Layer-System ist in demo3
 * noch nicht modelliert (siehe AGENTS.md „Geplant"); das Speicherformat
 * führt die Ebenen-Definitionen trotzdem bereits mit (demo2-Kompatibilität,
 * Beispiel in doc/spec.md) — gelesen wird heute nur tolerant, geschrieben
 * werden die zwei bekannten Default-Ebenen ({@link PersistedLayers}).
 *
 * @author saj
 */
public record PersistedLayer(
        UUID id,
        String name,
        boolean visible
) {
}
