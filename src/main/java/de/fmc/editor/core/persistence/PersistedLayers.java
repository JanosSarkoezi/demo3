package de.fmc.editor.core.persistence;

import java.util.List;
import java.util.UUID;

/**
 * Die zwei bekannten Ebenen des Formats (IDs und Namen aus dem spec.md-
 * Beispiel der Altvorlage): „Standard" (sichtbar) und „Wegpunkte" (unsicht-
 * bar). Bis das Layer-System in demo3 existiert, schreibt der Export sie als
 * Fixliste mit (User-Entscheidung 03.11.: demo2-Dateien bleiben bidirektional
 * kompatibel), gelesen wird der Abschnitt tolerant.
 *
 * STANDARD_LAYER_ID ist zugleich der Ersatzwert beim Laden, wenn Objekte/
 * Verbindungen einer älteren Datei keine layerId tragen — das Modell hält
 * nie null als Ebene.
 *
 * @author saj
 */
public final class PersistedLayers {

    public static final UUID STANDARD_LAYER_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000000");
    public static final UUID WAYPOINT_LAYER_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private PersistedLayers() {
    }

    /** Fixliste der Default-Ebenen für den Export (Reihenfolge wie spec.md). */
    public static List<PersistedLayer> defaultLayers() {
        return List.of(
                new PersistedLayer(STANDARD_LAYER_ID, "Standard", true),
                new PersistedLayer(WAYPOINT_LAYER_ID, "Wegpunkte", false));
    }
}
