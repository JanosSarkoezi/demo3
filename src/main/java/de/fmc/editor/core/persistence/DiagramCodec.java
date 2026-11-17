package de.fmc.editor.core.persistence;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.Connection;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.model.DiagramQuery;
import de.fmc.editor.core.model.Event;
import de.fmc.editor.core.model.EventGeometry;
import de.fmc.editor.core.model.EventSide;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.Text;
import de.fmc.editor.core.model.Waypoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Abbildung Kern ↔ Speicherformat am Serialisierungsrand. Das sealed,
 * typisierte Modell bleibt unangetastet — die uniforme Bounding-Box des
 * demo2-Formats entsteht HIER (Circle.radius ↔ width/height = Durchmesser,
 * Waypoint-Box 10×10) statt das Modell zu de-typen (demo2-Lektion fürs
 * Portieren: Das alte Ein-Record-mit-Type-Enum-Modell erklärt zwar die
 * flache JSON-Form, die Neuimplementierung behält aber compiler-erzwungene
 * Exhaustivität und konvertiert nur am Rand).
 *
 * Lesen ist RESTORE, kein Erzeugen: KEINE ConnectionRules, keine Kaskaden —
 * der DiagramService übernimmt den Bestand, wie er ist (demo2 lud ebenso an
 * der Validierung vorbei; sonst ließe eine ältere Datei mit heute
 * verbotener Verbindung nicht mehr laden). Defekte Einträge (fehlende ID
 * oder Typangabe) überspringt der Codec still — ein Ladevorgang crasht nie
 * an einer einzelnen kaputten Zeile.
 *
 * Bekannte Format-Grenze (demo2-Semantik, bewusst übernommen): Texte leben
 * im eigenen texts-Abschnitt ohne z-Position — sie laden deshalb OBEN auf
 * die z-Ordnung. Die relative Reihenfolge der K/R/W bleibt erhalten.
 *
 * @author saj
 */
public final class DiagramCodec {

    /** Waypoint-Größe im Format (demo2-Konvention; das Modell trägt keine Maße). */
    private static final double WAYPOINT_BOX_SIZE = 10.0;

    private DiagramCodec() {
    }

    // ---------- Export: DiagramQuery → DTO ----------

    /**
     * Exportiert den kompletten Bestand in z-Ordnung (unten → oben,
     * deterministisch — demo2 exportierte Map-Reihenfolge: nicht kopieren).
     * Speichern ist Lesewissen wie Hover: reine Query, kein Bus-Request.
     */
    public static PersistedDiagram export(DiagramQuery query) {
        List<PersistedObject> objects = new ArrayList<>();
        List<PersistedText> texts = new ArrayList<>();
        for (DiagramObject obj : query.allObjectsInZOrder()) {
            switch (obj) {
                case Circle c -> objects.add(new PersistedObject(c.id(), PersistedObjectType.CIRCLE,
                        c.x(), c.y(), c.radius() * 2.0, c.radius() * 2.0, c.layerId(),
                        c.label(), null, null, null, null, null, null));
                case Rectangle r -> objects.add(new PersistedObject(r.id(), PersistedObjectType.RECTANGLE,
                        r.x(), r.y(), r.width(), r.height(), r.layerId(),
                        null, r.content(), r.centered(), null, null, null, null));
                case Waypoint w -> objects.add(new PersistedObject(w.id(), PersistedObjectType.WAYPOINT,
                        w.x(), w.y(), WAYPOINT_BOX_SIZE, WAYPOINT_BOX_SIZE, w.layerId(),
                        null, null, null, null, null, null, null));
                // Events: Maße irrelevant (Geometrie abgeleitet) — gespeichert
                // werden Owner, Seite, Label und die zwei Form-Längen; die
                // Spitzen-Position läuft als abgeleiteter Wert mit und wird
                // beim Laden neu abgeleitet.
                case Event e -> objects.add(new PersistedObject(e.id(), PersistedObjectType.EVENT,
                        e.x(), e.y(), 0.0, 0.0, e.layerId(),
                        e.label(), null, null, e.ownerId(), e.side().name(),
                        e.horizontalExtent(), e.verticalRise()));
                case Text t -> texts.add(new PersistedText(t.id(), t.content(), t.x(), t.y(),
                        t.width(), t.height(), t.anchor(), t.ownerId(), t.layerId()));
            }
        }
        Map<UUID, PersistedConnection> connections = new LinkedHashMap<>();
        for (Connection conn : query.allConnections()) {
            connections.put(conn.id(), new PersistedConnection(conn.sourceId(), conn.targetId(),
                    conn.waypointIds(), conn.layerId()));
        }
        return new PersistedDiagram(objects, connections, PersistedLayers.defaultLayers(), texts);
    }

    // ---------- Import: DTO → LoadDiagram-Request ----------

    /** Formatiert das DTO zurück in Kern-Records und packt sie in den
     *  Restore-Request: objects in z-Ordnung (Texte oben auf, siehe Klassen-
     *  doc), connections mit Original-IDs. Der Aufrufer publisht ihn auf den
     *  Bus — der DiagramService bestätigt mit EINEM DiagramReset-Fakt. */
    public static ActionEvent.LoadDiagram toLoadRequest(PersistedDiagram data) {
        List<DiagramObject> objects = new ArrayList<>();
        for (PersistedObject dto : data.objects()) {
            if (dto == null || dto.id() == null || dto.type() == null) {
                continue; // Defekten Eintrag still überspringen — kein Crash
            }
            switch (dto.type()) {
                case CIRCLE -> objects.add(new Circle(dto.id(), dto.x(), dto.y(), circleRadius(dto),
                        dto.label(), layerId(dto.layerId())));
                case RECTANGLE -> objects.add(new Rectangle(dto.id(), dto.x(), dto.y(), dto.width(), dto.height(),
                        dto.content(), dto.centered() == null || dto.centered(),
                        layerId(dto.layerId())));
                case WAYPOINT -> objects.add(new Waypoint(dto.id(), dto.x(), dto.y(), layerId(dto.layerId())));
                // Event ohne Owner ist defekt (still übersprungen); Seite
                // ungültig/fehlend -> RIGHT (Standard); Form-Längen
                // fehlend/ungültig -> 40/40 (demo3-Defaults). x/y läuft mit
                // — der DiagramService dockt die Spitze beim Laden frisch ab.
                case EVENT -> {
                    if (dto.ownerId() == null) {
                        continue;
                    }
                    objects.add(new Event(dto.id(), dto.ownerId(), tolerantSide(dto.side()),
                            dto.label(), tolerantExtent(dto.horizontalExtent()),
                            tolerantExtent(dto.verticalRise()),
                            dto.x(), dto.y(), layerId(dto.layerId())));
                }
            }
        }
        for (PersistedText dto : data.texts()) {
            if (dto == null || dto.id() == null) {
                continue;
            }
            objects.add(new Text(dto.id(), dto.text(), dto.x(), dto.y(), dto.width(), dto.height(),
                    dto.anchor(), dto.parentObjectId(), layerId(dto.layerId())));
        }
        List<Connection> connections = new ArrayList<>();
        for (Map.Entry<UUID, PersistedConnection> entry : data.connections().entrySet()) {
            PersistedConnection dto = entry.getValue();
            if (dto == null || entry.getKey() == null || dto.sourceId() == null || dto.targetId() == null) {
                continue;
            }
            connections.add(new Connection(entry.getKey(), dto.sourceId(), dto.targetId(),
                    dto.waypointIds(), layerId(dto.layerId())));
        }
        return new ActionEvent.LoadDiagram(objects, connections);
    }

    /** Radius aus der formatierten Bounding-Box: width ist der Durchmesser;
     *  fehlt sie (0), fällt die Höhe ein (Lese-Toleranz für Rand-Dateien). */
    private static double circleRadius(PersistedObject dto) {
        return dto.width() > 0.0 ? dto.width() / 2.0 : dto.height() / 2.0;
    }

    /** Alte Dateien ohne layerId (demo2s Connection hatte keins) landen auf
     *  der Standard-Ebene — das Modell hält nie null als Ebene. */
    private static UUID layerId(UUID layerId) {
        return layerId != null ? layerId : PersistedLayers.STANDARD_LAYER_ID;
    }

    /** Seitenlage tolerant gelesen: unbekannt/fehlend -> RIGHT (Standard). */
    private static EventSide tolerantSide(String side) {
        return "LEFT".equalsIgnoreCase(side) ? EventSide.LEFT : EventSide.RIGHT;
    }

    /** Form-Länge tolerant gelesen: fehlend/ungültig -> demo3-Default 40. */
    private static double tolerantExtent(Double extent) {
        return extent != null && Double.isFinite(extent) && extent > 0.0
                ? extent
                : EventGeometry.DEFAULT_EXTENT;
    }
}
