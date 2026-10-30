package de.fmc.editor;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.model.GridSpec;
import de.fmc.editor.core.service.DiagramService;
import de.fmc.editor.core.service.SelectionService;
import de.fmc.editor.core.service.UndoService;
import de.fmc.editor.core.state.CameraInfo;
import de.fmc.editor.ui.InputEventTranslator;
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

    @Override
    public void start(Stage stage) {
        // 1. Kern: Services + Bus konstruieren, DANACH attachen (Zirkularität).
        DiagramService diagram = new DiagramService();
        SelectionService selection = new SelectionService();
        UndoService undo = new UndoService();
        UUID defaultLayerId = UUID.randomUUID();
        InteractionEventBus bus = new InteractionEventBus(
                diagram,
                new CameraInfo(1.0, 0.0, 0.0, defaultLayerId),
                new GridSpec(10.0),
                selection);
        diagram.attachTo(bus);
        selection.attachTo(bus);
        undo.attachTo(bus);

        // 2. UI-Schicht: View (stummer Scene-Graph), Toolbar (reiner
        //    Publisher), Übersetzer am Systemrand (JavaFX -> InputEvent).
        ToolbarController toolbar = new ToolbarController(bus);
        GraphView graphView = new GraphView(bus, diagram);
        UiMouseState mouseState = new UiMouseState();
        new InputEventTranslator(graphView.viewRoot(), bus, mouseState);

        // 3. Wegpunkte-Checkbox (reine UI-Sichtbarkeit, nie ein Bus-Event).
        graphView.setWaypointsVisible(toolbar.waypointsVisibleProperty().get());
        toolbar.waypointsVisibleProperty().addListener((obs, oldValue, newValue) ->
                graphView.setWaypointsVisible(newValue));

        // 4. Layout (demo2-Stil): Toolbar als Seitenpanel links, Zeichenfläche
        //    füllt den Rest.
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
