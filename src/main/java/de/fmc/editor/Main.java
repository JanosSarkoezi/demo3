package de.fmc.editor;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.model.GridSpec;
import de.fmc.editor.core.persistence.PersistedLayers;
import de.fmc.editor.core.service.DiagramService;
import de.fmc.editor.core.service.SelectionService;
import de.fmc.editor.core.service.UndoService;
import de.fmc.editor.core.state.CameraInfo;
import de.fmc.editor.ui.InlineTextEditorController;
import de.fmc.editor.ui.InputEventTranslator;
import de.fmc.editor.ui.PersistenceController;
import de.fmc.editor.ui.ToolbarController;
import de.fmc.editor.ui.UiMouseState;
import de.fmc.editor.view.GraphView;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import java.util.UUID;

/**
 * Einstiegspunkt des FMC-Editors: verdrahtet den UI-freien Interaktions-Kern
 * (Event-Bus, Services) mit der View-Schicht. Die zweiphasige Initialisierung
 * (erst konstruieren, dann attachTo) löst die Bus-↔-Service-Zirkularität —
 * identisch zum getesteten Aufbau in BusTestSupport.
 *
 * @author saj
 */
public class Main extends Application {

    /** Start-Raster in Welt-Einheiten (User-Entscheidung 1.11.: 20 statt 10).
     *  Produkt-Startwert — die Test-Fixtures (BusTestSupport) bleiben bei 10,
     *  ihre Gesten sind darauf kalibriert; dieser Wert lebt nur hier. */
    private static final double INITIAL_GRID_SIZE = 20.0;

    @Override
    public void start(Stage stage) {
        // 1. Kern: Services + Bus konstruieren, DANACH attachen (Zirkularität).
        DiagramService diagram = new DiagramService();
        SelectionService selection = new SelectionService();
        UndoService undo = new UndoService();
        // Default-Ebene = „Standard" des Speicherformats (Persistenz-Block,
        // 03.11.): Neue Objekte tragen dieselbe layerId, die der Export in
        // den layers-Abschnitt schreibt — gespeicherte Dateien sind damit
        // einheitlich. Die Tests (BusTestSupport) behalten ihre zufällige
        // Ebene: Für sie ist layerId nur ein mitgetragenes Datum.
        UUID defaultLayerId = PersistedLayers.STANDARD_LAYER_ID;
        InteractionEventBus bus = new InteractionEventBus(
                diagram,
                new CameraInfo(1.0, 0.0, 0.0, defaultLayerId),
                new GridSpec(INITIAL_GRID_SIZE),
                selection);
        diagram.attachTo(bus);
        selection.attachTo(bus);
        undo.attachTo(bus);

        // 2. UI-Schicht: View (stummer Scene-Graph), Toolbar (reiner
        //    Publisher), Übersetzer am Systemrand (JavaFX -> InputEvent).
        //    Die View zuerst: Der Toolbar wird der Fokus-Rückgabe-Hook für
        //    das Snap-to-Grid-Feld übergeben (ENTER/ESC geben den Tastatur-
        //    Fokus an die Zeichenfläche zurück, damit Shortcuts leben).
        GraphView graphView = new GraphView(bus, diagram);
        PersistenceController persistence = new PersistenceController(bus, diagram, stage);
        ToolbarController toolbar = new ToolbarController(bus, INITIAL_GRID_SIZE,
                graphView.viewRoot()::requestFocus,
                persistence::saveDiagram, persistence::loadDiagram);
        UiMouseState mouseState = new UiMouseState();
        // Inline-Editor VOR dem Übersetzer konstruieren: Sein „Klick daneben
        // committet"-Filter muss in der Filter-Kette VORRANG haben — der
        // konsumierte Commit-Klick darf den Kern (und dessen requestFocus)
        // nie erreichen. Reihenfolge = Registrierungsfolge auf demselben Node.
        new InlineTextEditorController(bus, diagram, mouseState,
                graphView.viewRoot(),
                graphView::addToScreenOverlay,
                graphView::removeFromScreenOverlay,
                graphView.viewRoot()::requestFocus);
        // CTRL+S/CTRL+O laufen als reine UI-Flows über denselben Controller
        // wie die Toolbar-Buttons (FileChooser -> LoadDiagram-Request).
        new InputEventTranslator(graphView.viewRoot(), bus, mouseState,
                persistence::saveDiagram, persistence::loadDiagram);

        // 3. Layout (demo2-Stil): Toolbar als Seitenpanel links, Zeichenfläche
        //    füllt den Rest. (Die Wegpunkt-Sichtbarkeit steuert seit Block 2
        //    der Kern über WaypointFocusChanged — RouteEditMode per Klick auf
        //    die Verbindung, kein Toolbar-Control mehr.)
        BorderPane layout = new BorderPane();
        layout.setLeft(toolbar.toolbarNode());
        layout.setCenter(graphView.viewRoot());

        stage.setTitle("FMC Editor");
        stage.setScene(new Scene(layout, 1024, 768));
        stage.show();

        // Key-Events (Shortcuts K/R/C/T, ESC, DEL) landen auf der Zeichenfläche.
        graphView.viewRoot().requestFocus();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
