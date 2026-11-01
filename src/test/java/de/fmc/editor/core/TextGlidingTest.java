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
import de.fmc.editor.core.model.Waypoint;
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
 * W-Gleiten & Re-Ankerung (Block 3, Stufe 0/0b): Verbindungs-Label sind an
 * der Kette verankert — ihre Position ist ABGELEITET (Anker = Absicht).
 *
 * Zwei Beweisziele:
 * 1. GLEITEN: Jede Ketten-Verformung (W-/Endpunkt-Move, Gruppen-Move,
 *    W-Einfügen, W-Löschen) leitet die Label-Position neu ab — als sichtbare
 *    Move-Einträge im (Batch-)Fakt, nie als versteckte Kaskade.
 * 2. RE-ANKERUNG: Ein Drag auf das Label selbst projiziert die Zielposition
 *    auf die Kette und schreibt die Absicht (t + Offset) neu — der letzte
 *    Drag gewinnt immer; das Gleiten bewahrt anschließend genau diese.
 *
 * Undo konvergiert mechanisch: Das Label-Inverse (MoveObjectTo) ist ein
 * stiller No-Op, die Kette wird per LIFO wiederhergestellt und die Ableitung
 * zieht das Label exakt nach.
 */
class TextGlidingTest {

    private InteractionEventBus eventBus;
    private DiagramService diagram;
    private UUID defaultLayerId;

    private final List<DiagramEvent.ObjectAdded> addedFacts = new ArrayList<>();
    private final List<DiagramEvent.ObjectsMoved> movedBatchFacts = new ArrayList<>();
    private final List<DiagramEvent.ObjectTextChanged> textChangedFacts = new ArrayList<>();
    private final List<DiagramEvent.ObjectsRemoved> removedFacts = new ArrayList<>();
    private final List<DiagramEvent.ConnectionEstablished> connectionFacts = new ArrayList<>();

    // Standard-Fixture: K(100,100) --W1(250,100)--W2(350,100)-- R(500,100).
    // Abschnitte: 0 = K->W1, 1 = W1->W2, 2 = W2->R.
    private Circle k;
    private Rectangle r;
    private Waypoint w1;
    private Waypoint w2;
    private Connection connection;

    @BeforeEach
    void setUp() {
        addedFacts.clear();
        movedBatchFacts.clear();
        textChangedFacts.clear();
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
        eventBus.subscribe(DiagramEvent.ObjectTextChanged.class, textChangedFacts::add);
        eventBus.subscribe(DiagramEvent.ObjectsRemoved.class, removedFacts::add);
        eventBus.subscribe(DiagramEvent.ConnectionEstablished.class, connectionFacts::add);

        k = addCircle(100.0, 100.0);
        r = addRectangle(500.0, 100.0);
        w1 = new Waypoint(UUID.randomUUID(), 250.0, 100.0, defaultLayerId);
        w2 = new Waypoint(UUID.randomUUID(), 350.0, 100.0, defaultLayerId);
        eventBus.publish(new ActionEvent.CreateConnection(k.id(), r.id(), List.of(w1, w2)));
        connection = connectionFacts.get(0).connection();
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

    /** Verankertes Verbindungs-Label; Rückgabe = der Text aus dem Fakt
     *  (mit der abgeleiteten Position). */
    private Text addAnchoredLabel(TextAnchor anchor) {
        eventBus.publish(new ActionEvent.AddAnchoredText(connection.id(), "liefert",
                anchor, 60.0, 20.0, defaultLayerId));
        return (Text) addedFacts.get(addedFacts.size() - 1).object();
    }

    private Text textById(UUID id) {
        return (Text) diagram.findById(id).orElseThrow();
    }

    /** Schließt den laufenden Undo-Gesten-Eintrag (wie ein echtes Loslassen). */
    private void closeGesture() {
        eventBus.publish(new InputEvent.MouseReleased(0.0, 0.0, CustomMouseButton.PRIMARY));
    }

    private List<DiagramEvent.ObjectMoved> allMovedEntries() {
        return movedBatchFacts.stream()
                .flatMap(fact -> fact.movedObjects().stream())
                .toList();
    }

    // ---------- Stufe 0: W-Gleiten auf Move-Pfaden ----------

    @Test
    @DisplayName("Waypoint-Move: Label gleitet im SELBEN Batch-Fakt mit, Anker bleibt Absicht")
    void waypointMoveGlidesLabelInSameBatch() {
        // Abschnitt 1 (W1->W2), Mitte (300,100), Offset 15 darunter: (300,115)
        Text label = addAnchoredLabel(new TextAnchor(1, 0.5, 0.0, 15.0));
        assertThat(label.x()).isEqualTo(300.0);
        assertThat(label.y()).isEqualTo(115.0);
        closeGesture();

        movedBatchFacts.clear();
        eventBus.publish(new ActionEvent.MoveObjects(List.of(w1.id()), 0.0, 100.0));

        // EIN Batch-Fakt: W1-Eintrag UND Label-Eintrag (keine versteckte Kaskade).
        assertThat(movedBatchFacts).hasSize(1);
        List<DiagramEvent.ObjectMoved> entries = movedBatchFacts.get(0).movedObjects();
        assertThat(entries).extracting(DiagramEvent.ObjectMoved::id)
                .containsExactlyInAnyOrder(w1.id(), label.id());

        // W1: (250,100) -> (250,200); Abschnitt 1 wird (250,200)->(350,100),
        // Mitte (300,150) + Offset 15 => Label (300,165).
        Text after = textById(label.id());
        assertThat(after.x()).isEqualTo(300.0);
        assertThat(after.y()).isEqualTo(165.0);
        assertThat(after.anchor()).isEqualTo(new TextAnchor(1, 0.5, 0.0, 15.0)); // Absicht unverändert
    }

    @Test
    @DisplayName("Endpunkt-Move: Label auf dem ersten Abschnitt folgt der Verformung")
    void endpointMoveGlidesLabelOnFirstSegment() {
        // Abschnitt 0 (K->W1), Mitte (175,100) + 15 => (175,115)
        Text label = addAnchoredLabel(new TextAnchor(0, 0.5, 0.0, 15.0));
        closeGesture();

        eventBus.publish(new ActionEvent.MoveObjects(List.of(k.id()), 50.0, 0.0));

        // K bei (150,100): Abschnitt-0-Mitte (200,100) + 15 => (200,115)
        Text after = textById(label.id());
        assertThat(after.x()).isEqualTo(200.0);
        assertThat(after.y()).isEqualTo(115.0);
        assertThat(allMovedEntries())
                .anySatisfy(m -> {
                    assertThat(m.id()).isEqualTo(label.id());
                    assertThat(m.oldX()).isEqualTo(175.0);
                    assertThat(m.newX()).isEqualTo(200.0);
                });
    }

    @Test
    @DisplayName("Alltagsfall: Gruppen-Move beider Endpunkte — Label übersetzt mit der Kette")
    void groupMoveOfBothEndpointsTranslatesLabel() {
        // Abschnitt 1, Mitte (300,100) + 15 => (300,115)
        Text label = addAnchoredLabel(new TextAnchor(1, 0.5, 0.0, 15.0));
        closeGesture();

        // Vollselektierte Verbindung: K und R in der Basis, Ws kommen über
        // findMoveSet dazu — die ganze Kette übersetzt um (40,30).
        List<UUID> moveSet = diagram.findMoveSet(List.of(k.id(), r.id()));
        assertThat(moveSet).contains(w1.id(), w2.id());
        eventBus.publish(new ActionEvent.MoveObjects(moveSet, 40.0, 30.0));

        // Uniform übersetzte Kette => gleitendes Label bei (340,145).
        Text after = textById(label.id());
        assertThat(after.x()).isEqualTo(340.0);
        assertThat(after.y()).isEqualTo(145.0);
        assertThat(after.anchor()).isEqualTo(new TextAnchor(1, 0.5, 0.0, 15.0));
    }

    @Test
    @DisplayName("Gleiten fasst nur verankerte Texte BERÜHRTER Verbindungen an — freie und Owner-Label unberührt")
    void glideTouchesOnlyAnchoredTextsOfAffectedConnections() {
        Text anchored = addAnchoredLabel(new TextAnchor(1, 0.5, 0.0, 15.0));
        Text freeText = new Text(UUID.randomUUID(), "frei", 300.0, 300.0,
                60.0, 20.0, null, null, defaultLayerId);
        eventBus.publish(new ActionEvent.AddElement(freeText));
        Text kLabel = new Text(UUID.randomUUID(), "unten", 100.0, 140.0,
                60.0, 20.0, null, k.id(), defaultLayerId);
        eventBus.publish(new ActionEvent.AddElement(kLabel));
        closeGesture();

        movedBatchFacts.clear();
        eventBus.publish(new ActionEvent.MoveObjects(List.of(w1.id()), 0.0, 100.0));

        // Nur W1 und das verankerte Label sind im Batch.
        assertThat(allMovedEntries())
                .extracting(DiagramEvent.ObjectMoved::id)
                .containsExactlyInAnyOrder(w1.id(), anchored.id());
        assertThat(textById(freeText.id()).y()).isEqualTo(300.0);
        assertThat(textById(kLabel.id()).y()).isEqualTo(140.0);
    }

    @Test
    @DisplayName("Singulärer Delta-Move (Service-API) gleitet ebenfalls — Auslöser-Vollständigkeit")
    void singleDeltaMoveGlidesLabel() {
        Text label = addAnchoredLabel(new TextAnchor(1, 0.5, 0.0, 15.0));
        closeGesture();

        movedBatchFacts.clear();
        eventBus.publish(new ActionEvent.MoveObject(w1.id(), 0.0, 100.0));

        // Einzel-Move publiziert den Gleit-Pfad als eigenen Batch-Fakt.
        assertThat(movedBatchFacts).hasSize(1);
        assertThat(allMovedEntries())
                .extracting(DiagramEvent.ObjectMoved::id)
                .containsExactly(label.id());
        assertThat(textById(label.id()).y()).isEqualTo(165.0);
    }

    // ---------- Undo/Redo des Gleitens ----------

    @Test
    @DisplayName("Undo des W-Moves stellt das Label per Ableitung wieder her (LIFO-Kette + Gleiten)")
    void undoOfWaypointMoveRestoresLabelByDerivation() {
        Text label = addAnchoredLabel(new TextAnchor(1, 0.5, 0.0, 15.0));
        closeGesture();

        eventBus.publish(new ActionEvent.MoveObjects(List.of(w1.id()), 0.0, 100.0));
        closeGesture();
        eventBus.publish(new ActionEvent.RequestUndo());

        // Kette zurück => Ableitung zieht das Label exakt nach; der Anker
        // war nie verändert. Das Label-Inverse (MoveObjectTo) war ein No-Op.
        assertThat(diagram.findById(w1.id()).orElseThrow().y()).isEqualTo(100.0);
        Text after = textById(label.id());
        assertThat(after.x()).isEqualTo(300.0);
        assertThat(after.y()).isEqualTo(115.0);
        assertThat(after.anchor()).isEqualTo(new TextAnchor(1, 0.5, 0.0, 15.0));

        eventBus.publish(new ActionEvent.RequestRedo());
        assertThat(diagram.findById(w1.id()).orElseThrow().y()).isEqualTo(200.0);
        assertThat(textById(label.id()).y()).isEqualTo(165.0);
    }

    // ---------- Stufe 0b: Drag = Re-Ankerung ----------

    @Test
    @DisplayName("Drag auf das Label re-ankert: Position folgt der Maus, Anker wird neu projiziert")
    void draggingLabelReanchorsIt() {
        Text label = addAnchoredLabel(new TextAnchor(1, 0.5, 0.0, 15.0));
        closeGesture();

        textChangedFacts.clear();
        movedBatchFacts.clear();
        // Drag um (50,0): Ziel (350,115) — Projektion liefert Abschnitt 1,
        // t = 1.0 (Endpunkt W2), Offset bleibt (0,15).
        eventBus.publish(new ActionEvent.MoveObjects(List.of(label.id()), 50.0, 0.0));

        assertThat(textChangedFacts).hasSize(1);
        DiagramEvent.ObjectTextChanged reanchorFact = textChangedFacts.get(0);
        assertThat(((Text) reanchorFact.previous()).anchor()).isEqualTo(new TextAnchor(1, 0.5, 0.0, 15.0));
        Text updated = (Text) reanchorFact.updated();
        assertThat(updated.anchor()).isEqualTo(new TextAnchor(1, 1.0, 0.0, 15.0));
        assertThat(updated.x()).isEqualTo(350.0);
        assertThat(updated.y()).isEqualTo(115.0);
        // Re-Ankerung ist KEIN Move: kein ObjectsMoved-Batch.
        assertThat(movedBatchFacts).isEmpty();
    }

    @Test
    @DisplayName("Vertikale Linie: Links-Rechts-Drag ändert nur den Offset — t bleibt (Lesbarkeits-Fall)")
    void verticalLineLeftRightDragChangesOffsetOnly() {
        // Eigene Fixture: vertikale Direktverbindung K(100,100) -> R(100,400).
        Circle top = addCircle(100.0, 100.0);
        Rectangle bottom = addRectangle(100.0, 400.0);
        connectionFacts.clear();
        eventBus.publish(new ActionEvent.CreateConnection(top.id(), bottom.id(), List.of()));
        Connection vertical = connectionFacts.get(0).connection();

        eventBus.publish(new ActionEvent.AddAnchoredText(vertical.id(), "liefert",
                new TextAnchor(0, 0.5, 0.0, 0.0), 60.0, 20.0, defaultLayerId));
        Text label = (Text) addedFacts.get(addedFacts.size() - 1).object();
        assertThat(label.y()).isEqualTo(250.0); // auf der Linie
        closeGesture();

        // 30 px nach links ziehen: t bleibt 0.5, Offset wird (-30, 0).
        eventBus.publish(new ActionEvent.MoveObjects(List.of(label.id()), -30.0, 0.0));
        Text after = textById(label.id());
        assertThat(after.anchor()).isEqualTo(new TextAnchor(0, 0.5, -30.0, 0.0));
        assertThat(after.x()).isEqualTo(70.0);
        assertThat(after.y()).isEqualTo(250.0);
    }

    @Test
    @DisplayName("Re-Ankerungs-Drag über mehrere Segmente = EIN Undo-Punkt; Undo stellt Anker exakt her")
    void dragSegmentsMergeToOneUndoPoint() {
        Text label = addAnchoredLabel(new TextAnchor(1, 0.5, 0.0, 15.0));
        closeGesture();

        // Zwei Drag-Segmente (MouseDragged ist keine Gesten-Grenze):
        // (300,115) -> (310,115) [t=0.6] -> (325,115) [t=0.75]
        eventBus.publish(new ActionEvent.MoveObjects(List.of(label.id()), 10.0, 0.0));
        eventBus.publish(new ActionEvent.MoveObjects(List.of(label.id()), 15.0, 0.0));
        closeGesture();

        Text dragged = textById(label.id());
        assertThat(dragged.anchor()).isEqualTo(new TextAnchor(1, 0.75, 0.0, 15.0));

        // EIN Undo genügt: UpdateText + SetAnchor(previous) leiten die
        // Original-Position aus der unveränderten Kette ab.
        eventBus.publish(new ActionEvent.RequestUndo());
        Text restored = textById(label.id());
        assertThat(restored.anchor()).isEqualTo(new TextAnchor(1, 0.5, 0.0, 15.0));
        assertThat(restored.x()).isEqualTo(300.0);
        assertThat(restored.y()).isEqualTo(115.0);
    }

    // ---------- W-Gleiten auf Einfügen & Löschen ----------

    @Test
    @DisplayName("W-Einfügen: Anker auf dahinterliegenden Abschnitten wandern um eins mit (Position bleibt)")
    void insertedWaypointShiftsLaterAnchors() {
        // Abschnitt 2 (W2->R), Mitte (425,100) + 10 => (425,110)
        Text label = addAnchoredLabel(new TextAnchor(2, 0.5, 0.0, 10.0));
        closeGesture();

        textChangedFacts.clear();
        movedBatchFacts.clear();
        // Einfügen in Abschnitt 0 (Klick 175,100): Abschnitte 1 und 2 rücken
        // auf 2 und 3 — der Anker zieht auf Abschnitt 3 mit, Geometrie und
        // Position bleiben unverändert.
        eventBus.publish(new ActionEvent.AddWaypoint(connection.id(),
                new Waypoint(UUID.randomUUID(), 175.0, 100.0, defaultLayerId)));

        assertThat(textChangedFacts).hasSize(1);
        DiagramEvent.ObjectTextChanged fact = textChangedFacts.get(0);
        assertThat(((Text) fact.previous()).anchor()).isEqualTo(new TextAnchor(2, 0.5, 0.0, 10.0));
        assertThat(((Text) fact.updated()).anchor()).isEqualTo(new TextAnchor(3, 0.5, 0.0, 10.0));
        assertThat(fact.updated().x()).isEqualTo(425.0);
        assertThat(fact.updated().y()).isEqualTo(110.0);
        assertThat(movedBatchFacts).isEmpty(); // keine Positionsänderung => kein Glide-Fakt
    }

    @Test
    @DisplayName("W-Einfügen in den verankerten Abschnitt: Label gleitet auf die erste Hälfte; Undo stellt her")
    void insertIntoAnchoredSegmentGlidesLabelAndUndoRestores() {
        // Abschnitt 0 (K->W1), Mitte (175,100)
        Text label = addAnchoredLabel(new TextAnchor(0, 0.5, 0.0, 0.0));
        closeGesture();

        movedBatchFacts.clear();
        textChangedFacts.clear();
        // Einfügen bei (200,100) teilt Abschnitt 0: Der Anker (0, t=0.5)
        // zeigt jetzt auf K->NEU mit Mitte (150,100).
        eventBus.publish(new ActionEvent.AddWaypoint(connection.id(),
                new Waypoint(UUID.randomUUID(), 200.0, 100.0, defaultLayerId)));

        assertThat(movedBatchFacts).hasSize(1);
        assertThat(allMovedEntries())
                .anySatisfy(m -> {
                    assertThat(m.id()).isEqualTo(label.id());
                    assertThat(m.oldX()).isEqualTo(175.0);
                    assertThat(m.newX()).isEqualTo(150.0);
                });
        Text glided = textById(label.id());
        assertThat(glided.anchor()).isEqualTo(new TextAnchor(0, 0.5, 0.0, 0.0)); // Absicht bleibt
        closeGesture();

        eventBus.publish(new ActionEvent.RequestUndo());
        // AddConnection(previous) stellt die Kette her, die Ableitung zieht
        // das Label auf (175,100) zurück.
        Text restored = textById(label.id());
        assertThat(restored.x()).isEqualTo(175.0);
        assertThat(restored.y()).isEqualTo(100.0);
        assertThat(restored.anchor()).isEqualTo(new TextAnchor(0, 0.5, 0.0, 0.0));
    }

    @Test
    @DisplayName("W-Löschen: Anker klemmt bei der Ableitung, Label gleitet sichtbar mit")
    void deletingWaypointGlidesLabelWithClampedAnchor() {
        // Abschnitt 2 (W2->R), Mitte (425,100)
        Text label = addAnchoredLabel(new TextAnchor(2, 0.5, 0.0, 0.0));
        closeGesture();

        movedBatchFacts.clear();
        removedFacts.clear();
        eventBus.publish(new ActionEvent.DeleteObjects(List.of(w2.id())));

        // Kette schrumpft: Abschnitt 2 klemmt auf 1 (W1->R), Mitte (375,100).
        Text after = textById(label.id());
        assertThat(after.x()).isEqualTo(375.0);
        assertThat(after.y()).isEqualTo(100.0);
        assertThat(after.anchor()).isEqualTo(new TextAnchor(2, 0.5, 0.0, 0.0)); // Absicht bleibt
        assertThat(allMovedEntries())
                .anySatisfy(m -> assertThat(m.id()).isEqualTo(label.id()));
        // Das Label ÜBERLEBT (nur der Kettenpunkt war doomed).
        assertThat(removedFacts.get(0).objects())
                .extracting(obj -> obj.id())
                .doesNotContain(label.id());

        // Undo: AddConnection(previous) + RestoreObjects lassen die Ableitung
        // das Label exakt auf (425,100) zurückziehen.
        eventBus.publish(new ActionEvent.RequestUndo());
        Text restored = textById(label.id());
        assertThat(restored.x()).isEqualTo(425.0);
        assertThat(restored.y()).isEqualTo(100.0);
        assertThat(restored.anchor()).isEqualTo(new TextAnchor(2, 0.5, 0.0, 0.0));
    }
}
