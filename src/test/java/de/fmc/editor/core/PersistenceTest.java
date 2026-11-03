package de.fmc.editor.core;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.Connection;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.Text;
import de.fmc.editor.core.model.TextAnchor;
import de.fmc.editor.core.model.Waypoint;
import de.fmc.editor.core.persistence.DiagramCodec;
import de.fmc.editor.core.persistence.PersistedLayer;
import de.fmc.editor.core.persistence.PersistedLayers;
import de.fmc.editor.core.persistence.PersistedObject;
import de.fmc.editor.core.persistence.PersistedObjectType;
import de.fmc.editor.core.persistence.PersistedDiagram;
import de.fmc.editor.core.persistence.PersistenceService;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistenz als reiner Event-Flow (Kern ohne UI): Export über die Query,
 * Laden über den LoadDiagram-Request, Bestätigung als EIN DiagramReset-Fakt.
 *
 * Formatkontrakt ist der Gson-Export der Altvorlage demo2 (Beispiel in
 * doc/spec.md) — eine originale demo2-Datei muss 1:1 laden. Demo3-
 * Erweiterungen (label/content/centered, texts mit Anker) roundtrippen.
 *
 * @author saj
 */
class PersistenceTest extends BusTestSupport {

    /** Publisht den Lade-Request und bestätigt: genau EIN DiagramReset-Fakt. */
    private DiagramEvent.DiagramReset load(ActionEvent.LoadDiagram request) {
        List<DiagramEvent.DiagramReset> resets = new ArrayList<>();
        bus.subscribe(DiagramEvent.DiagramReset.class, resets::add);
        bus.publish(request);
        assertThat(resets).hasSize(1);
        return resets.get(0);
    }

    @Test
    void roundTripPreservesFullState() {
        Circle circle = addCircle(100.0, 200.0); // r = 20
        bus.publish(new ActionEvent.SetCircleLabel(circle.id(), "A"));
        Rectangle rect = addRectangle(300.0, 400.0); // 40×30
        bus.publish(new ActionEvent.SetRectangleContent(rect.id(), "Speicher", false));
        Text freeText = new Text(UUID.randomUUID(), "Freitext",
                500.0, 500.0, 80.0, 20.0, null, null, defaultLayerId);
        bus.publish(new ActionEvent.AddElement(freeText));
        Waypoint waypoint = new Waypoint(UUID.randomUUID(), 250.0, 300.0, defaultLayerId);
        bus.publish(new ActionEvent.CreateConnection(circle.id(), rect.id(), List.of(waypoint)));
        Connection connection = diagram.connections().get(0);
        bus.publish(new ActionEvent.AddAnchoredText(connection.id(), "Label",
                new TextAnchor(0, 0.5, 10.0, -10.0), 60.0, 16.0, defaultLayerId));

        // Export: Objekte in z-Ordnung, Kreis als DURCHMESSER, Texte separat.
        PersistedDiagram dto = DiagramCodec.export(diagram);
        assertThat(dto.objects()).extracting(PersistedObject::id)
                .containsExactly(circle.id(), rect.id(), waypoint.id());
        assertThat(dto.texts()).hasSize(2); // freier Text + Verbindungs-Label
        assertThat(dto.connections()).hasSize(1);
        assertThat(dto.layers()).extracting(PersistedLayer::name)
                .containsExactly("Standard", "Wegpunkte");
        PersistedObject circleDto = dto.objects().get(0);
        assertThat(circleDto.type()).isEqualTo(PersistedObjectType.CIRCLE);
        assertThat(circleDto.width()).isEqualTo(40.0); // 2 × Radius
        assertThat(circleDto.height()).isEqualTo(40.0);
        assertThat(circleDto.label()).isEqualTo("A");
        assertThat(dto.objects().get(1).centered()).isFalse();

        // Gson-Roundtrip über einen String und zurück auf den Bus.
        StringWriter buffer = new StringWriter();
        PersistenceService.saveDiagram(dto, buffer);
        PersistedDiagram reloaded = PersistenceService.loadDiagram(
                new StringReader(buffer.toString()));
        load(DiagramCodec.toLoadRequest(reloaded));

        // Zustand identisch: Geometrie, innere Texte, Verbindung, Anker.
        Circle loadedCircle = (Circle) diagram.findById(circle.id()).orElseThrow();
        assertThat(loadedCircle.x()).isEqualTo(100.0);
        assertThat(loadedCircle.radius()).isEqualTo(20.0);
        assertThat(loadedCircle.label()).isEqualTo("A");
        Rectangle loadedRect = (Rectangle) diagram.findById(rect.id()).orElseThrow();
        assertThat(loadedRect.content()).isEqualTo("Speicher");
        assertThat(loadedRect.centered()).isFalse();
        Waypoint loadedWaypoint = (Waypoint) diagram.findById(waypoint.id()).orElseThrow();
        assertThat(loadedWaypoint.x()).isEqualTo(250.0);
        assertThat(loadedWaypoint.y()).isEqualTo(300.0);
        Connection loadedConnection = diagram.connections().get(0);
        assertThat(loadedConnection.id()).isEqualTo(connection.id());
        assertThat(loadedConnection.sourceId()).isEqualTo(circle.id());
        assertThat(loadedConnection.targetId()).isEqualTo(rect.id());
        assertThat(loadedConnection.waypointIds()).containsExactly(waypoint.id());

        // Verbindungs-Label: Anker-Absicht + Owner überleben den Roundtrip.
        List<UUID> labelIds = diagram.findTextIdsOwnedBy(connection.id());
        assertThat(labelIds).hasSize(1);
        Text label = (Text) diagram.findById(labelIds.get(0)).orElseThrow();
        assertThat(label.content()).isEqualTo("Label");
        assertThat(label.anchor()).isEqualTo(new TextAnchor(0, 0.5, 10.0, -10.0));
        assertThat(diagram.findById(freeText.id())).isPresent();
        assertThat(diagram.allObjectsInZOrder()).hasSize(5);
    }

    @Test
    void loadReplacesStateClearsSelectionAndHistory() {
        Circle oldCircle = addCircle(100.0, 100.0);
        bus.publish(new ActionEvent.MoveObject(oldCircle.id(), 30.0, 0.0));
        closeGesture(); // ein Undo-Eintrag für den Move

        List<DiagramEvent.SelectionChanged> selectionFacts = new ArrayList<>();
        bus.subscribe(DiagramEvent.SelectionChanged.class, selectionFacts::add);
        bus.publish(new ActionEvent.SetSelection(List.of(oldCircle.id())));
        assertThat(selectionFacts).hasSize(1);

        Circle fresh = new Circle(UUID.randomUUID(), 50.0, 60.0, 15.0, defaultLayerId);
        DiagramEvent.DiagramReset reset =
                load(new ActionEvent.LoadDiagram(List.of(fresh), List.of()));

        assertThat(reset.objects()).hasSize(1);
        assertThat(reset.connections()).isEmpty();
        // Das alte Dokument ist komplett ersetzt — Restore, kein Merge.
        assertThat(diagram.findById(oldCircle.id())).isEmpty();
        assertThat(diagram.findById(fresh.id())).isPresent();
        // Auswahl geleert (zweiter Fakt = leere Menge).
        assertThat(selectionFacts).hasSize(2);
        assertThat(selectionFacts.get(1).selectedIds()).isEmpty();

        // History verworfen (demo2-Semantik: Datei öffnen = neues Dokument):
        // Undo nach dem Laden ist ein kompletter No-Op — das geladene
        // Dokument bleibt unverändert, und KEIN Fakt entsteht (auch kein
        // stummer Müll aus Vor-Lade-Gesten).
        List<DiagramEvent> afterLoad = new ArrayList<>();
        bus.subscribe(DiagramEvent.class, afterLoad::add);
        bus.publish(new ActionEvent.RequestUndo());
        bus.publish(new ActionEvent.RequestUndo());
        assertThat(afterLoad).isEmpty();
        Circle untouched = (Circle) diagram.findById(fresh.id()).orElseThrow();
        assertThat(untouched.x()).isEqualTo(50.0);
        assertThat(untouched.y()).isEqualTo(60.0);
        assertThat(diagram.allObjectsInZOrder()).hasSize(1);
    }

    @Test
    void demo2FileFormatLoads() {
        // Original-Ausschnitt aus doc/spec.md: kein label, kein content/
        // centered, keine texts — das Altvorlagen-Format ist der Kontrakt.
        String json = """
                {
                  "objects": [
                    {"id":"646e01ac-9b48-4503-8a55-c7b7c6cfa766","type":"CIRCLE","x":200.0,"y":200.0,"width":40.0,"height":40.0,"layerId":"00000000-0000-0000-0000-000000000000"},
                    {"id":"9baf090b-06a5-46ef-aa16-8e146ab0dade","type":"RECTANGLE","x":200.0,"y":720.0,"width":124.0,"height":74.0,"layerId":"00000000-0000-0000-0000-000000000000"},
                    {"id":"4a90fc9e-4569-4f65-8cf2-2cbc88370e98","type":"WAYPOINT","x":300.0,"y":640.0,"width":10.0,"height":10.0,"layerId":"11111111-1111-1111-1111-111111111111"}
                  ],
                  "connections": {
                    "4372db31-9b26-4266-be31-59f039068335": {
                      "sourceId":"646e01ac-9b48-4503-8a55-c7b7c6cfa766",
                      "targetId":"9baf090b-06a5-46ef-aa16-8e146ab0dade",
                      "waypointIds":["4a90fc9e-4569-4f65-8cf2-2cbc88370e98"]
                    }
                  },
                  "layers": [
                    {"id":"00000000-0000-0000-0000-000000000000","name":"Standard","visible":true},
                    {"id":"11111111-1111-1111-1111-111111111111","name":"Wegpunkte","visible":false}
                  ]
                }
                """;
        UUID circleId = UUID.fromString("646e01ac-9b48-4503-8a55-c7b7c6cfa766");
        UUID rectId = UUID.fromString("9baf090b-06a5-46ef-aa16-8e146ab0dade");
        UUID waypointId = UUID.fromString("4a90fc9e-4569-4f65-8cf2-2cbc88370e98");
        UUID connectionId = UUID.fromString("4372db31-9b26-4266-be31-59f039068335");

        load(DiagramCodec.toLoadRequest(
                PersistenceService.loadDiagram(new StringReader(json))));

        // width/height = Durchmesser -> Radius; x/y bleibt Mittelpunkt.
        Circle circle = (Circle) diagram.findById(circleId).orElseThrow();
        assertThat(circle.radius()).isEqualTo(20.0);
        assertThat(circle.x()).isEqualTo(200.0);
        assertThat(circle.label()).isEmpty(); // altes Format: kein Feld
        assertThat(circle.layerId()).isEqualTo(PersistedLayers.STANDARD_LAYER_ID);
        // Fehlende Text-Felder werden zu demo3-Defaults, nicht zu false/""-Sonderfällen.
        Rectangle rect = (Rectangle) diagram.findById(rectId).orElseThrow();
        assertThat(rect.content()).isEmpty();
        assertThat(rect.centered()).isTrue();
        Waypoint waypoint = (Waypoint) diagram.findById(waypointId).orElseThrow();
        assertThat(waypoint.layerId()).isEqualTo(PersistedLayers.WAYPOINT_LAYER_ID);
        // Connection mit ORIGINAL-ID und Waypoint-Referenz (die Ws sind Objekte).
        Connection connection = diagram.findConnectionById(connectionId).orElseThrow();
        assertThat(connection.sourceId()).isEqualTo(circleId);
        assertThat(connection.targetId()).isEqualTo(rectId);
        assertThat(connection.waypointIds()).containsExactly(waypointId);
        // Dateireihenfolge wird zur z-Ordnung.
        assertThat(diagram.zOrderIds()).containsExactly(circleId, rectId, waypointId);
    }

    @Test
    void loadToleratesMissingSectionsAndDefects() {
        // Vorhandenes Diagramm wird durch eine leere Datei komplett ersetzt.
        addCircle(10.0, 10.0);
        load(DiagramCodec.toLoadRequest(
                PersistenceService.loadDiagram(new StringReader("{\"objects\": []}"))));
        assertThat(diagram.allObjectsInZOrder()).isEmpty();
        assertThat(diagram.connections()).isEmpty();

        // Defekte Einträge (Typ/ID fehlt) werden still übersprungen — ein
        // Ladevorgang crasht nie an einer einzelnen kaputten Zeile; ein
        // Kreis ohne width fällt auf die Höhe zurück.
        load(DiagramCodec.toLoadRequest(PersistenceService.loadDiagram(new StringReader("""
                {"objects": [
                  {"id":"aaaaaaaa-0000-0000-0000-000000000001","x":5.0,"y":5.0},
                  {"id":"aaaaaaaa-0000-0000-0000-000000000002","type":"CIRCLE","x":5.0,"y":5.0,"height":40.0}
                ]}
                """))));
        assertThat(diagram.allObjectsInZOrder()).hasSize(1);
        Circle fallback = (Circle) diagram
                .findById(UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002"))
                .orElseThrow();
        assertThat(fallback.radius()).isEqualTo(20.0);
        // Fehlende layerId alter Dateien -> Standard-Ebene, nie null im Modell.
        assertThat(fallback.layerId()).isEqualTo(PersistedLayers.STANDARD_LAYER_ID);
    }

    @Test
    void malformedJsonFailsLoudlyInsteadOfLoadingGarbage() {
        // Gson wirft bei Müll eine RuntimeException (JsonSyntaxException) —
        // die UI fängt sie am Systemrand und zeigt einen Alert (statt
        // demo2s printStackTrace und still weiterlaufendem Programm).
        assertThatThrownBy(() -> PersistenceService.loadDiagram(
                new StringReader("kein json")))
                .isInstanceOf(RuntimeException.class);
    }
}
