package de.fmc.editor.ui;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.model.DiagramQuery;
import de.fmc.editor.core.model.ToolType;
import de.fmc.editor.core.persistence.DiagramCodec;
import de.fmc.editor.core.persistence.PersistedDiagram;
import de.fmc.editor.core.persistence.PersistenceService;
import javafx.scene.control.Alert;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Speichern & Laden am Systemrand (Persistenz-Block, 03.11.):
 *
 * - SPEICHERN ist Lesewissen wie Hover: Der Bestand kommt über die
 *   DiagramQuery in den DTO-Export — kein Bus-Request, nichts schreibt.
 * - LADEN ist ein Schreibpfad: Der Datei-Inhalt wird über den DiagramCodec
 *   zum LoadDiagram-Request gemappt und über den Bus publiziert; der
 *   DiagramService bestätigt mit EINEM DiagramReset-Fakt (Undo-History und
 *   Auswahl leeren sich per Subscription — niemand wird von hier aus
 *   angerufen, demo2 rief seine CommandHistory von der Toolbar: nicht
 *   kopieren). Danach verlässt die UI laufende Modi über SelectTool(SELECT)
 *   — ein neues Dokument kennt keinen RouteEditMode/ResizeMode des alten.
 * - Fehler werden hier als Alert sichtbar (demo2 warf printStackTrace und
 *   lief still weiter: Warze). Das alte Dokument bleibt bei einem
 *   Lade-Fehler unangetastet — der Request geht erst nach erfolgreichem
 *   Parsen auf den Bus.
 *
 * @author saj
 */
public final class PersistenceController {

    private final InteractionEventBus bus;
    private final DiagramQuery diagramQuery;
    private final Window ownerWindow;

    public PersistenceController(InteractionEventBus bus, DiagramQuery diagramQuery,
                                 Window ownerWindow) {
        this.bus = bus;
        this.diagramQuery = diagramQuery;
        this.ownerWindow = ownerWindow;
    }

    /** „Speichern unter …" (User-Entscheidung 03.11.: bewusst ohne gemerkten
     *  Datei-Pfad — jeder Speichern-Klick öffnet den Dialog; kein gemerkter
     *  Pfad, kein „ungespeichert"-Zustand, demo2-Stil). */
    public void saveDiagram() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Diagramm speichern");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("JSON Dateien", "*.json"));
        File file = fileChooser.showSaveDialog(ownerWindow);
        if (file == null) {
            return; // Abbruch ist keine Fehlermeldung wert
        }
        if (!file.getName().toLowerCase().endsWith(".json")) {
            file = new File(file.getParentFile(), file.getName() + ".json");
        }
        try (Writer writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
            PersistenceService.saveDiagram(DiagramCodec.export(diagramQuery), writer);
        } catch (IOException | RuntimeException e) {
            showError("Speichern fehlgeschlagen", e);
        }
    }

    /** „Laden …": Datei einlesen, Restore-Request auf den Bus, Modi des
     *  alten Dokuments verlassen. */
    public void loadDiagram() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Diagramm laden");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("JSON Dateien", "*.json"));
        File file = fileChooser.showOpenDialog(ownerWindow);
        if (file == null) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            PersistedDiagram data = PersistenceService.loadDiagram(reader);
            bus.publish(DiagramCodec.toLoadRequest(data));
            bus.publish(new ActionEvent.SelectTool(ToolType.SELECT));
        } catch (IOException | RuntimeException e) {
            showError("Laden fehlgeschlagen", e);
        }
    }

    private void showError(String title, Exception e) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.setContentText(String.valueOf(e.getMessage()));
        alert.showAndWait();
    }
}
