package de.fmc.editor.core;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.Connection;
import de.fmc.editor.core.model.GridSpec;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.Text;
import de.fmc.editor.core.model.TextAnchor;
import de.fmc.editor.core.model.ToolType;
import de.fmc.editor.core.service.DiagramService;
import de.fmc.editor.core.service.SelectionService;
import de.fmc.editor.core.service.UndoService;
import de.fmc.editor.core.state.CameraInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Text-Werkzeug auf Gesten-Ebene: Die Press-Regel verzweigt in der Aktion
 * und emittiert schlanke UI-Intents (EditTextOfObject, EditText,
 * CreateTextFor, CreateConnectionText). Kardinalität 0..1 für
 * Verbindungs-Labels; KEY_T-Wechsel; ESC verlässt das Werkzeug.
 */
class TextToolTest {

    private InteractionEventBus eventBus;
    private DiagramService diagram;
    private UUID defaultLayerId;

    private final List<ActionEvent.EditTextOfObject> editObjectIntents = new ArrayList<>();
    private final List<ActionEvent.EditText> editTextIntents = new ArrayList<>();
    private final List<ActionEvent.CreateTextFor> createTextIntents = new ArrayList<>();
    private final List<ActionEvent.CreateConnectionText> createConnectionTextIntents = new ArrayList<>();
    private final List<DiagramEvent.ObjectAdded> addedFacts = new ArrayList<>();
    private final List<DiagramEvent.ConnectionEstablished> connectionFacts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        editObjectIntents.clear();
        editTextIntents.clear();
        createTextIntents.clear();
        createConnectionTextIntents.clear();
        addedFacts.clear();
        connectionFacts.clear();
        defaultLayerId = UUID.randomUUID();

        SelectionService selectionService = new SelectionService();
        diagram = new DiagramService();
        eventBus = new InteractionEventBus(diagram,
                new CameraInfo(1.0, 0.0, 0.0, defaultLayerId),
                new GridSpec(10.0), selectionService);
        diagram.attachTo(eventBus);
        selectionService.attachTo(eventBus);
        new UndoService().attachTo(eventBus);

        eventBus.subscribe(ActionEvent.EditTextOfObject.class, editObjectIntents::add);
        eventBus.subscribe(ActionEvent.EditText.class, editTextIntents::add);
        eventBus.subscribe(ActionEvent.CreateTextFor.class, createTextIntents::add);
        eventBus.subscribe(ActionEvent.CreateConnectionText.class, createConnectionTextIntents::add);
        eventBus.subscribe(DiagramEvent.ObjectAdded.class, addedFacts::add);
        eventBus.subscribe(DiagramEvent.ConnectionEstablished.class, connectionFacts::add);
    }

    // ---------- Helfer ----------

    private void selectTextTool() {
        eventBus.publish(new ActionEvent.SelectTool(ToolType.TEXT));
    }

    private Circle addCircle(double x, double y) {
        Circle circle = new Circle(UUID.randomUUID(), x, y, 20.0, defaultLayerId);
        eventBus.publish(new ActionEvent.AddElement(circle));
        return circle;
    }

    private Rectangle addRectangle(double x, double y) {
        Rectangle rectangle = new Rectangle(UUID.randomUUID(), x, y, 40.0, 30.0, defaultLayerId);
        eventBus.publish(new ActionEvent.AddElement(rectangle));
        return rectangle;
    }

    private Connection connect(Circle source, Rectangle target) {
        connectionFacts.clear();
        eventBus.publish(new ActionEvent.CreateConnection(source.id(), target.id(), List.of()));
        return connectionFacts.get(0).connection();
    }

    private void press(double x, double y, boolean ctrl) {
        eventBus.publish(new InputEvent.MousePressed(x, y, CustomMouseButton.PRIMARY, 1, ctrl));
    }

    // ---------- Gesten ----------

    @Test
    @DisplayName("Klick auf K/R -> EditTextOfObject; Ctrl+Klick -> CreateTextFor(owner)")
    void pressOnShapeYieldsInnerOrExternalIntent() {
        Circle circle = addCircle(100.0, 100.0);
        selectTextTool();

        press(100.0, 100.0, false);
        assertThat(editObjectIntents).extracting(ActionEvent.EditTextOfObject::objectId)
                .containsExactly(circle.id());

        press(100.0, 100.0, true);
        assertThat(createTextIntents).extracting(ActionEvent.CreateTextFor::ownerId)
                .containsExactly(circle.id());
    }

    @Test
    @DisplayName("Klick auf ein Text-Objekt -> EditText für genau dieses")
    void pressOnTextYieldsEditText() {
        Text text = new Text(UUID.randomUUID(), "Notiz", 200.0, 200.0, 80.0, 20.0, null, null, defaultLayerId);
        eventBus.publish(new ActionEvent.AddElement(text));
        selectTextTool();

        press(200.0, 200.0, false);
        assertThat(editTextIntents).extracting(ActionEvent.EditText::textId)
                .containsExactly(text.id());
    }

    @Test
    @DisplayName("Klick in Verbindungsnähe -> CreateConnectionText mit projiziertem Anker")
    void pressNearConnectionCreatesConnectionTextIntent() {
        Circle source = addCircle(100.0, 100.0);
        Rectangle target = addRectangle(400.0, 100.0);
        Connection connection = connect(source, target);
        selectTextTool();

        press(250.0, 105.0, false);
        assertThat(createConnectionTextIntents).hasSize(1);
        ActionEvent.CreateConnectionText intent = createConnectionTextIntents.get(0);
        assertThat(intent.connectionId()).isEqualTo(connection.id());
        assertThat(intent.anchor()).isEqualTo(new TextAnchor(0, 0.5, 0.0, 5.0));
    }

    @Test
    @DisplayName("Kardinalität 0..1: Klick nahe einer Verbindung mit bestehendem Label bearbeitet dieses")
    void pressNearConnectionWithExistingLabelEditsIt() {
        Circle source = addCircle(100.0, 100.0);
        Rectangle target = addRectangle(400.0, 100.0);
        Connection connection = connect(source, target);
        eventBus.publish(new ActionEvent.AddAnchoredText(connection.id(), "lagert",
                new TextAnchor(0, 0.5, 0.0, 0.0), 40.0, 20.0, defaultLayerId));
        UUID textId = ((Text) addedFacts.get(addedFacts.size() - 1).object()).id();
        selectTextTool();

        press(250.0, 105.0, false);
        assertThat(editTextIntents).extracting(ActionEvent.EditText::textId)
                .containsExactly(textId);
        assertThat(createConnectionTextIntents).isEmpty(); // kein zweites Label
    }

    @Test
    @DisplayName("Klick auf leere Fläche -> CreateTextFor(null) = freier Text")
    void pressOnEmptyYieldsFreeTextIntent() {
        selectTextTool();

        press(600.0, 600.0, false);
        assertThat(createTextIntents).extracting(ActionEvent.CreateTextFor::ownerId)
                .containsExactly((UUID) null);
    }

    @Test
    @DisplayName("Klick auf ein Objekt schlägt Verbindungsnähe: Hit-Test zuerst")
    void objectHitBeatsConnectionNear() {
        Circle source = addCircle(100.0, 100.0);
        Rectangle target = addRectangle(400.0, 100.0);
        connect(source, target);
        selectTextTool();

        // Quelle liegt AUF der Verbindungslinie — trotzdem Objekt-Klick
        press(100.0, 100.0, false);
        assertThat(editObjectIntents).extracting(ActionEvent.EditTextOfObject::objectId)
                .containsExactly(source.id());
        assertThat(createConnectionTextIntents).isEmpty();
    }

    // ---------- Werkzeug-Wechsel ----------

    @Test
    @DisplayName("KEY_T wechselt ins Text-Werkzeug; KEY_K zurück zum Kreis-Werkzeug")
    void keyTAndKeyKSwitchTools() {
        addCircle(100.0, 100.0);
        eventBus.publish(new InputEvent.KeyPressed(CustomKeyCode.KEY_T));

        press(600.0, 600.0, false);
        assertThat(createTextIntents).hasSize(1); // T-Tool aktiv

        eventBus.publish(new InputEvent.KeyPressed(CustomKeyCode.KEY_K));
        press(700.0, 700.0, false);
        assertThat(createTextIntents).hasSize(1); // KEIN neuer Intent: Kreis-Tool aktiv
    }

    @Test
    @DisplayName("ESC verlässt das Text-Werkzeug zurück zum Auswahl-Werkzeug")
    void escLeavesTextTool() {
        selectTextTool();
        eventBus.publish(new InputEvent.KeyPressed(CustomKeyCode.ESCAPE));

        press(600.0, 600.0, false);
        assertThat(createTextIntents).isEmpty(); // Idle: kein Text-Intent
    }
}
