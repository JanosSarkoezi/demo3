package de.fmc.editor.ui;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.model.ToolType;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Rectangle;

/**
 * Werkzeugleiste im demo2-Stil: seitliches Panel mit Sektionen („Shapes",
 * „Bearbeiten", „Settings"), Icon-Buttons und blauer Akzentleiste am
 * aktiven Werkzeug. Reiner Publisher von Bus-Events plus Sync des aktiven
 * Zustands — bewusst kein eigener „Select"-Button (gar kein aktiver Toggle
 * MEINT das Select-Werkzeug, wie in demo2).
 *
 * Nicht in Block 1: Text-Sektion (Font/Größe/Bold/Italic — Block 3),
 * Snap/Sticky-Checkboxen (Block 2+), Speichern/Laden (Persistenz-Block).
 *
 * @author saj
 */
public final class ToolbarController {

    private final InteractionEventBus bus;
    private final ToggleButton circleButton;
    private final ToggleButton rectangleButton;
    private final ToggleButton connectButton;
    private final ToggleButton textButton;
    /** Sichtbarkeit des Wegpunkt-Layers — reine UI-Sache, geht nie auf den Bus. */
    private final BooleanProperty waypointsVisible = new SimpleBooleanProperty(false);

    public ToolbarController(final InteractionEventBus bus) {
        this.bus = bus;

        circleButton = createToolButton("Kreis", "Kreis zeichnen (K)", ToolType.CIRCLE,
                new Circle(8.0));
        rectangleButton = createToolButton("Rechteck", "Rechteck zeichnen (R)", ToolType.RECTANGLE,
                demo2RectIcon());
        connectButton = createToolButton("Verbinden", "Verbindungsmodus (C)", ToolType.CONNECT,
                demo2ConnectIcon());
        textButton = createToolButton("Text", "Text einfügen (T)", ToolType.TEXT,
                demo2TextIcon());

        // Der Kern wechselt das Werkzeug auch per Shortcuts K/R/C/T — die UI
        // muss folgen. Programmatisches setSelected(true) feuert KEIN
        // onAction (nur Nutzerinteraktion tut das), daher ist kein
        // Loop-Schutz nötig.
        bus.subscribe(ActionEvent.ToolChanged.class, hint -> {
            final ToggleButton toggle = switch (hint.tool()) {
                case CIRCLE -> circleButton;
                case RECTANGLE -> rectangleButton;
                case CONNECT -> connectButton;
                case TEXT -> textButton;
                // Kein Select-Toggle existiert: alle abwählen.
                case SELECT -> null;
            };
            if (toggle == null) {
                deselectAll();
            } else {
                selectExclusive(toggle);
            }
        });
    }

    /**
     * Liefert das fertige Seitenpanel (demo2-Stil: VBox, 150 px breit).
     */
    public Node toolbarNode() {
        final VBox bar = new VBox(10);
        bar.setPrefWidth(150);
        bar.setPadding(new Insets(15, 12, 15, 12));
        bar.getStyleClass().add("toolbar");
        final String css = getClass().getResource("/graph/css/style.css").toExternalForm();
        if (css != null) {
            bar.getStylesheets().add(css);
        }

        // --- Shapes ---
        bar.getChildren().add(sectionLabel("Shapes"));
        bar.getChildren().addAll(circleButton, rectangleButton, connectButton, textButton);

        // --- Bearbeiten (demo2 hatte hier noch keine Undo-Buttons; Stil der
        //     Vollbreiten-Buttons entspricht demo2s „Diagramm"-Sektion) ---
        bar.getChildren().add(sectionSeparator());
        bar.getChildren().add(sectionLabel("Bearbeiten"));
        bar.getChildren().addAll(
                createActionButton("Undo", () -> bus.publish(new ActionEvent.RequestUndo())),
                createActionButton("Redo", () -> bus.publish(new ActionEvent.RequestRedo())));

        // --- Settings ---
        bar.getChildren().add(sectionSeparator());
        bar.getChildren().add(sectionLabel("Settings"));
        bar.getChildren().add(createWaypointsCheckBox());

        return bar;
    }

    /**
     * Zustand der Wegpunkte-Sichtbarkeit für die View-Anbindung.
     */
    public BooleanProperty waypointsVisibleProperty() {
        return waypointsVisible;
    }

    private Label sectionLabel(final String text) {
        final Label label = new Label(text);
        label.getStyleClass().add("section-label");
        return label;
    }

    private Separator sectionSeparator() {
        final Separator separator = new Separator();
        VBox.setMargin(separator, new Insets(10, 0, 10, 0));
        return separator;
    }

    private ToggleButton createToolButton(final String label, final String tooltipText,
                                          final ToolType tool, final Node icon) {
        final ToggleButton button = new ToggleButton(label, icon);
        button.getStyleClass().add("tool-button");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setTooltip(new Tooltip(tooltipText));
        if (icon instanceof Circle circleIcon) {
            circleIcon.getStyleClass().add("tool-icon-circle");
        } else if (icon instanceof Rectangle rectIcon) {
            rectIcon.getStyleClass().add("tool-icon-rect");
        }
        // Fokus-Steuerung: Buttons dürfen den Tastaturfokus NICHT übernehmen —
        // Shortcuts (K/R/C/T, ESC, DEL) sollen auch nach einem Toolbar-Klick
        // auf der Zeichenfläche landen.
        button.setFocusTraversable(false);
        // Bewusst KEINE ToggleGroup: deren Desektierung bräuchte
        // setAllowDeselect (erst ab JavaFX 23) — die Exklusivität verwalten
        // wir selbst, der Klick auf den aktiven Toggle bedeutet Select.
        button.setOnAction(event -> {
            if (button.isSelected()) {
                selectExclusive(button);
                bus.publish(new ActionEvent.SelectTool(tool));
            } else {
                bus.publish(new ActionEvent.SelectTool(ToolType.SELECT));
            }
        });
        return button;
    }

    /** Selektiert genau einen Toggle, alle anderen ab (Exklusivität manuell). */
    private void selectExclusive(final ToggleButton active) {
        if (circleButton != null) circleButton.setSelected(active == circleButton);
        if (rectangleButton != null) rectangleButton.setSelected(active == rectangleButton);
        if (connectButton != null) connectButton.setSelected(active == connectButton);
        if (textButton != null) textButton.setSelected(active == textButton);
    }

    private void deselectAll() {
        selectExclusive(null);
    }

    private Button createActionButton(final String label, final Runnable action) {
        final Button button = new Button(label);
        button.getStyleClass().add("tool-button");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setFocusTraversable(false); // Fokus bleibt auf der Zeichenfläche
        button.setOnAction(event -> action.run());
        return button;
    }

    private CheckBox createWaypointsCheckBox() {
        // Default unselektiert — Wegpunkte-Layer startet unsichtbar.
        final CheckBox checkBox = new CheckBox("Wegpunkte");
        checkBox.setSelected(false);
        checkBox.getStyleClass().add("settings-checkbox");
        checkBox.setFocusTraversable(false); // Fokus bleibt auf der Zeichenfläche
        checkBox.selectedProperty().bindBidirectional(waypointsVisible);
        return checkBox;
    }

    // --- demo2-Icon-Graphics (1:1 aus toolbar-view.fxml portiert) ---

    private Rectangle demo2RectIcon() {
        return new Rectangle(16.0, 16.0);
    }

    private Line demo2ConnectIcon() {
        final Line line = new Line(0, 0, 15, 0);
        line.setStroke(Color.web("#3498db"));
        line.setStrokeWidth(2);
        return line;
    }

    private Label demo2TextIcon() {
        final Label icon = new Label("A");
        icon.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        return icon;
    }
}
