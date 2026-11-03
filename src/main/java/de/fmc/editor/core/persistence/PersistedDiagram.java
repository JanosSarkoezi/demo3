package de.fmc.editor.core.persistence;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Wurzel-DTO des Speicherformats (Struktur = Gson-Export der Altvorlage
 * demo2, Beispiel in doc/spec.md): objects als Liste, connections als Map
 * (ID → Verbindung), layers und texts.
 *
 * Die Objekt-Liste ist die z-Ordnung (unten → oben): Der Export schreibt sie
 * deterministisch aus der zOrder-Liste des DiagramService — demo2 exportierte
 * dagegen objects.values() einer Map (Reihenfolge zufällig; nicht kopieren).
 * Beim Lesen ist jede Reihenfolge willkommen, die Dateireihenfolge WIRD die
 * neue z-Ordnung. Fehlende Abschnitte alter Dateien werden zu leeren Mengen
 * normalisiert.
 *
 * @author saj
 */
public record PersistedDiagram(
        List<PersistedObject> objects,
        Map<UUID, PersistedConnection> connections,
        List<PersistedLayer> layers,
        List<PersistedText> texts
) {
    public PersistedDiagram {
        objects = objects == null ? List.of() : List.copyOf(objects);
        // LinkedHashMap-Sicht: stabile Iterationsreihenfolge für den Export.
        connections = connections == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(connections));
        layers = layers == null ? List.of() : List.copyOf(layers);
        texts = texts == null ? List.of() : List.copyOf(texts);
    }
}
