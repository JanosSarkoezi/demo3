package de.fmc.editor.core.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;

/**
 * Serialisiert das Diagramm-DTO als JSON (Gson mit Pretty-Printing — wie die
 * Altvorlage demo2, deren Exportformat der De-facto-Kontrakt ist). Rein
 * JavaFX-frei. Fehler (malformed JSON, IO) propagieren als Ausnahmen — das
 * Surface ist die UI (Alert), niemals printStackTrace (demo2-Warte).
 *
 * @author saj
 */
public final class PersistenceService {

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .create();

    private PersistenceService() {
    }

    public static void saveDiagram(PersistedDiagram data, Writer writer) {
        GSON.toJson(data, writer);
    }

    public static PersistedDiagram loadDiagram(Reader reader) {
        return GSON.fromJson(reader, PersistedDiagram.class);
    }
}
