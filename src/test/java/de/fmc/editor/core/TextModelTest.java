package de.fmc.editor.core;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.Connection;
import de.fmc.editor.core.model.GridSpec;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.Text;
import de.fmc.editor.core.model.TextAnchor;
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
 * Text-Feature auf Service-Ebene: Hit-Test und Gummiband für Text-Boxen,
 * Move-Skip verankerter Texte, Mitwandern externer Label (Gruppen-Move),
 * Lösch-Kaskade inkl. Undo, innere Texte (K-Label, R-Content) inkl.
 * Undo/Redo, Anker-Ableitung und -Projektion.
 */
class TextModelTest {

    private InteractionEventBus eventBus;
    private DiagramService diagram;
    private UUID defaultLayerId;

    private final List<DiagramEvent.ObjectAdded> addedFacts = new ArrayList<>();
    private final List<DiagramEvent.ObjectsMoved> movedBatchFacts = new ArrayList<>();
    private final List<DiagramEvent.ObjectsRemoved> removedFacts = new ArrayList<>();
    private final List<DiagramEvent.ConnectionEstablished> connectionFacts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        addedFacts.clear();
        movedBatchFacts.clear();
        removedFacts.clear();
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

        eventBus.subscribe(DiagramEvent.ObjectAdded.class, addedFacts::add);
        eventBus.subscribe(DiagramEvent.ObjectsMoved.class, movedBatchFacts::add);
        eventBus.subscribe(DiagramEvent.ObjectsRemoved.class, removedFacts::add);
        eventBus.subscribe(DiagramEvent.ConnectionEstablished.class, connectionFacts::add);
    }

    // ---------- Helfer ----------

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

    private Text addText(String content, double x, double y, UUID ownerId) {
        Text text = new Text(UUID.randomUUID(), content, x, y, 80.0, 20.0, null, ownerId, defaultLayerId);
        eventBus.publish(new ActionEvent.AddElement(text));
        return text;
    }

    private Connection connect(Circle source, Rectangle target) {
        connectionFacts.clear();
        eventBus.publish(new ActionEvent.CreateConnection(source.id(), target.id(), List.of()));
        assertThat(connectionFacts).hasSize(1);
        return connectionFacts.get(0).connection();
    }

    /** Schließt den laufenden Undo-Gesten-Eintrag (wie ein echtes Loslassen). */
    private void gestureBoundary() {
        eventBus.publish(new InputEvent.MouseReleased(0.0, 0.0, CustomMouseButton.PRIMARY));
    }

    private Text textById(UUID id) {
        return (Text) diagram.findById(id).orElseThrow();
    }

    // ---------- Hit-Test & Gummiband ----------

    @Test
    @DisplayName("Absolute Text-Box ist Klick-Ziel und Gummiband-Ziel (Mittelpunkt-Konvention)")
    void absoluteTextIsHitTargetAndBandSelectable() {
        Text text = addText("Lager", 200.0, 200.0, null);

        assertThat(diagram.findTopmostAt(190.0, 195.0)).contains(text);
        assertThat(diagram.findTopmostAt(200.0, 211.0)).isEmpty(); // außerhalb der Box (h/2 = 10)

        var band = diagram.findObjectsInBounds(150.0, 180.0, 250.0, 220.0);
        assertThat(band).extracting(de.fmc.editor.core.model.DiagramObject::id).contains(text.id());
    }

    // ---------- Verankerte Texte ----------

    @Test
    @DisplayName("AddAnchoredText leitet x/y aus dem Anker ab; verankerte Texte sind nicht delta-verschiebbar")
    void anchoredTextDerivesPositionAndSkipsDeltaMoves() {
        Circle source = addCircle(100.0, 100.0);
        Rectangle target = addRectangle(400.0, 100.0);
        Connection connection = connect(source, target);

        addedFacts.clear();
        eventBus.publish(new ActionEvent.AddAnchoredText(connection.id(), "lagert",
                new TextAnchor(0, 0.5, 10.0, 5.0), 40.0, 20.0, defaultLayerId));
        gestureBoundary();

        assertThat(addedFacts).hasSize(1);
        Text added = (Text) addedFacts.get(0).object();
        assertThat(added.ownerId()).isEqualTo(connection.id());
        assertThat(added.anchor()).isEqualTo(new TextAnchor(0, 0.5, 10.0, 5.0));
        // Mitte des Abschnitts (100,100)-(400,100) plus Offset: (260, 105)
        assertThat(added.x()).isEqualTo(260.0);
        assertThat(added.y()).isEqualTo(105.0);

        movedBatchFacts.clear();
        eventBus.publish(new ActionEvent.MoveObjects(List.of(added.id()), 50.0, 0.0));
        assertThat(movedBatchFacts).isEmpty(); // verankert => still übersprungen
        assertThat(textById(added.id()).x()).isEqualTo(260.0);
    }

    @Test
    @DisplayName("SetAnchor verankert neu (Position wird abgeleitet); Undo stellt Anker und Position wieder her")
    void setAnchorReanchorsAndUndoRestores() {
        Circle source = addCircle(100.0, 100.0);
        Rectangle target = addRectangle(400.0, 100.0);
        Connection connection = connect(source, target);

        eventBus.publish(new ActionEvent.AddAnchoredText(connection.id(), "lagert",
                new TextAnchor(0, 0.5, 10.0, 5.0), 40.0, 20.0, defaultLayerId));
        UUID textId = ((Text) addedFacts.get(addedFacts.size() - 1).object()).id();
        gestureBoundary();

        eventBus.publish(new ActionEvent.SetAnchor(textId, new TextAnchor(0, 1.0, 0.0, 0.0)));
        gestureBoundary();
        assertThat(textById(textId).x()).isEqualTo(400.0); // Zielpunkt: Ende des Abschnitts
        assertThat(textById(textId).y()).isEqualTo(100.0);

        eventBus.publish(new ActionEvent.RequestUndo());
        Text restored = textById(textId);
        assertThat(restored.anchor()).isEqualTo(new TextAnchor(0, 0.5, 10.0, 5.0));
        assertThat(restored.x()).isEqualTo(260.0);
        assertThat(restored.y()).isEqualTo(105.0);
    }

    @Test
    @DisplayName("SetAnchor(null) löst den Anker — Position bleibt, ab jetzt delta-verschiebbar")
    void setAnchorNullMakesAbsoluteKeepsPosition() {
        Circle source = addCircle(100.0, 100.0);
        Rectangle target = addRectangle(400.0, 100.0);
        Connection connection = connect(source, target);

        eventBus.publish(new ActionEvent.AddAnchoredText(connection.id(), "lagert",
                new TextAnchor(0, 0.5, 0.0, 0.0), 40.0, 20.0, defaultLayerId));
        UUID textId = ((Text) addedFacts.get(addedFacts.size() - 1).object()).id();
        gestureBoundary();

        eventBus.publish(new ActionEvent.SetAnchor(textId, null));
        assertThat(textById(textId).anchor()).isNull();
        assertThat(textById(textId).x()).isEqualTo(250.0); // Position bleibt

        movedBatchFacts.clear();
        eventBus.publish(new ActionEvent.MoveObjects(List.of(textId), 30.0, 0.0));
        assertThat(movedBatchFacts).hasSize(1); // jetzt beweglich
        assertThat(textById(textId).x()).isEqualTo(280.0);
    }

    @Test
    @DisplayName("Wird ein Abschnitt-Waypoint entfernt, klemmt der Anker-Index auf den letzten Abschnitt")
    void anchorIndexClampsWhenWaypointIsDeleted() {
        Circle source = addCircle(100.0, 100.0);
        Rectangle target = addRectangle(400.0, 100.0);

        // Verbindung mit einem Waypoint: (100,100) -> W(250,100) -> (400,100)
        var waypoint = new de.fmc.editor.core.model.Waypoint(
                UUID.randomUUID(), 250.0, 100.0, defaultLayerId);
        connectionFacts.clear();
        eventBus.publish(new ActionEvent.CreateConnection(
                source.id(), target.id(), List.of(waypoint)));
        Connection connection = connectionFacts.get(0).connection();

        // Anker auf Abschnitt 1 (W -> Ziel), t = 0.5 => (325, 100)
        eventBus.publish(new ActionEvent.AddAnchoredText(connection.id(), "lagert",
                new TextAnchor(1, 0.5, 0.0, 0.0), 40.0, 20.0, defaultLayerId));
        UUID textId = ((Text) addedFacts.get(addedFacts.size() - 1).object()).id();
        assertThat(textById(textId).x()).isEqualTo(325.0);
        gestureBoundary();

        // Waypoint löschen: Kette schrumpft auf EINEN Abschnitt => Index klemmt
        // auf 0, t = 0.5 bleibt => (250, 100)
        eventBus.publish(new ActionEvent.DeleteObjects(List.of(waypoint.id())));
        eventBus.publish(new ActionEvent.SetAnchor(textId, textById(textId).anchor()));
        assertThat(textById(textId).x()).isEqualTo(250.0);
        assertThat(textById(textId).y()).isEqualTo(100.0);
    }

    // ---------- Mitwandern & Kaskade ----------

    @Test
    @DisplayName("Externes Label wandert mit seinem Owner mit — auch ohne eigene Selektion")
    void ownedTextMovesWithOwnerGroupMove() {
        Rectangle owner = addRectangle(100.0, 100.0);
        Text label = addText("unten", 100.0, 140.0, owner.id());

        // Plain-Press auf das R + Drag um 30: movingIds = [R, Label].
        // Der ERSTE Drag wechselt nur in den MovingState (Schwellenwert) und
        // wird vom Übergang konsumiert — die Bewegung macht der zweite.
        eventBus.publish(new InputEvent.MousePressed(100.0, 100.0, CustomMouseButton.PRIMARY, 1));
        eventBus.publish(new InputEvent.MouseDragged(115.0, 100.0, CustomMouseButton.PRIMARY));
        eventBus.publish(new InputEvent.MouseDragged(130.0, 100.0, CustomMouseButton.PRIMARY));
        eventBus.publish(new InputEvent.MouseReleased(130.0, 100.0, CustomMouseButton.PRIMARY));

        assertThat(diagram.findById(owner.id()).orElseThrow().x()).isEqualTo(130.0);
        assertThat(textById(label.id()).x()).isEqualTo(130.0);

        eventBus.publish(new ActionEvent.RequestUndo());
        assertThat(textById(label.id()).x()).isEqualTo(100.0); // Batch-Fakt inkl. Label
    }

    @Test
    @DisplayName("Löschen eines Objekts kaskadiert seine Owned-Texte; Undo stellt alles wieder her")
    void ownedTextCascadesOnDeleteAndUndoRestoresIt() {
        Circle circle = addCircle(100.0, 100.0);
        Text label = addText("unten", 100.0, 140.0, circle.id());
        gestureBoundary();

        removedFacts.clear();
        eventBus.publish(new ActionEvent.DeleteObjects(List.of(circle.id())));
        assertThat(removedFacts).hasSize(1);
        var removedIds = removedFacts.get(0).objects().stream()
                .map(de.fmc.editor.core.model.DiagramObject::id).toList();
        assertThat(removedIds).contains(circle.id(), label.id());
        assertThat(diagram.findById(label.id())).isEmpty();

        eventBus.publish(new ActionEvent.RequestUndo());
        assertThat(textById(label.id()).x()).isEqualTo(100.0);
        assertThat(diagram.findById(circle.id())).isPresent();
    }

    @Test
    @DisplayName("Löschen eines Endpunkts kaskadiert Verbindung UND deren verankertes Label")
    void connectionOwnedTextCascadesWhenEndpointDeleted() {
        Circle source = addCircle(100.0, 100.0);
        Rectangle target = addRectangle(400.0, 100.0);
        Connection connection = connect(source, target);

        eventBus.publish(new ActionEvent.AddAnchoredText(connection.id(), "lagert",
                new TextAnchor(0, 0.5, 0.0, 0.0), 40.0, 20.0, defaultLayerId));
        UUID textId = ((Text) addedFacts.get(addedFacts.size() - 1).object()).id();
        gestureBoundary();

        removedFacts.clear();
        eventBus.publish(new ActionEvent.DeleteObjects(List.of(source.id())));
        var removedIds = removedFacts.get(0).objects().stream()
                .map(de.fmc.editor.core.model.DiagramObject::id).toList();
        assertThat(removedIds).contains(source.id(), textId);
    }

    // ---------- Innere Texte ----------

    @Test
    @DisplayName("SetCircleLabel ändert das Label; Undo/Redo stellt es exakt wieder her")
    void setCircleLabelChangeUndoRedo() {
        Circle circle = addCircle(100.0, 100.0);
        gestureBoundary(); // Setup-Geste abschließen — Undo darf nur die Label-Änderung treffen

        eventBus.publish(new ActionEvent.SetCircleLabel(circle.id(), "A"));
        assertThat(((Circle) diagram.findById(circle.id()).orElseThrow()).label()).isEqualTo("A");
        gestureBoundary();

        eventBus.publish(new ActionEvent.RequestUndo());
        assertThat(((Circle) diagram.findById(circle.id()).orElseThrow()).label()).isEmpty();

        eventBus.publish(new ActionEvent.RequestRedo());
        assertThat(((Circle) diagram.findById(circle.id()).orElseThrow()).label()).isEqualTo("A");
    }

    @Test
    @DisplayName("SetRectangleContent setzt Text UND Zentrierungs-Schalter; Undo stellt beides her")
    void setRectangleContentChangeUndoRedo() {
        Rectangle rectangle = addRectangle(100.0, 100.0);
        gestureBoundary(); // Setup-Geste abschließen — Undo darf nur die Content-Änderung treffen

        eventBus.publish(new ActionEvent.SetRectangleContent(rectangle.id(), "Zeile 1\nZeile 2", false));
        Rectangle updated = (Rectangle) diagram.findById(rectangle.id()).orElseThrow();
        assertThat(updated.content()).isEqualTo("Zeile 1\nZeile 2");
        assertThat(updated.centered()).isFalse();
        gestureBoundary();

        eventBus.publish(new ActionEvent.RequestUndo());
        Rectangle restored = (Rectangle) diagram.findById(rectangle.id()).orElseThrow();
        assertThat(restored.content()).isEmpty();
        assertThat(restored.centered()).isTrue(); // Default aus dem Kompat-Konstruktor
    }

    @Test
    @DisplayName("UpdateText ändert Inhalt und Box, nicht die Position; Undo stellt beides her")
    void updateTextChangeUndo() {
        Text text = addText("alt", 200.0, 200.0, null);
        gestureBoundary(); // Setup-Geste abschließen — Undo darf nur die Text-Änderung treffen

        eventBus.publish(new ActionEvent.UpdateText(text.id(), "neu", 120.0, 30.0));
        Text updated = textById(text.id());
        assertThat(updated.content()).isEqualTo("neu");
        assertThat(updated.width()).isEqualTo(120.0);
        assertThat(updated.x()).isEqualTo(200.0); // Position unberührt
        gestureBoundary();

        eventBus.publish(new ActionEvent.RequestUndo());
        Text restored = textById(text.id());
        assertThat(restored.content()).isEqualTo("alt");
        assertThat(restored.width()).isEqualTo(80.0);
    }

    @Test
    @DisplayName("K-Label überlebt Verschieben und Resizen (Feld-Mitführung, kein Kompat-Konstruktor-Verlust)")
    void circleLabelSurvivesMoveAndResize() {
        Circle circle = addCircle(100.0, 100.0);
        eventBus.publish(new ActionEvent.SetCircleLabel(circle.id(), "A"));

        eventBus.publish(new ActionEvent.MoveObjects(List.of(circle.id()), 50.0, 0.0));
        eventBus.publish(new ActionEvent.ResizeCircle(circle.id(), 30.0));

        Circle after = (Circle) diagram.findById(circle.id()).orElseThrow();
        assertThat(after.label()).isEqualTo("A");
        assertThat(after.x()).isEqualTo(150.0);
        assertThat(after.radius()).isEqualTo(30.0);
    }

    // ---------- Verbindungs-Geometrie ----------

    @Test
    @DisplayName("findConnectionNear findet innerhalb der Toleranz und lehnt außerhalb ab")
    void findConnectionNearFindsAndRejects() {
        Circle source = addCircle(100.0, 100.0);
        Rectangle target = addRectangle(400.0, 100.0);
        Connection connection = connect(source, target);

        assertThat(diagram.findConnectionNear(250.0, 108.0, 10.0)).contains(connection.id());
        assertThat(diagram.findConnectionNear(250.0, 112.0, 10.0)).isEmpty();
        // Anmerkung: Der Mittelpunkt eines Endpunkts liegt geometrisch AUF der
        // Kette (polyPoints nutzt die Zentren) — findConnectionNear findet sie
        // dort zu Recht. Dass Objekt-Klicks trotzdem gewinnen, stellt der
        // TextToolTest sicher (Hit-Test zuerst).
    }

    @Test
    @DisplayName("projectOntoConnection liefert Abschnitt, t und Offset zum Klick")
    void projectOntoConnectionYieldsAnchor() {
        Circle source = addCircle(100.0, 100.0);
        Rectangle target = addRectangle(400.0, 100.0);
        Connection connection = connect(source, target);

        var anchor = diagram.projectOntoConnection(connection.id(), 250.0, 108.0);
        assertThat(anchor).contains(new TextAnchor(0, 0.5, 0.0, 8.0));
    }
}
