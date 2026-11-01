package de.fmc.editor.view;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.Connection;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.model.DiagramQuery;
import de.fmc.editor.core.model.HandlePositions;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.Text;
import de.fmc.editor.core.model.Waypoint;
import de.fmc.editor.core.state.CameraInfo;
import javafx.event.EventHandler;
import javafx.geometry.VPos;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.effect.DropShadow;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.StrokeType;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;
import javafx.scene.transform.Scale;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Stummer Scene-Graph der Diagrammansicht (View-Schicht, JavaFX-frei vom Kern
 * getrennt): hält KEIN eigenes Modell, sondern spiegelt nur die Fakten des
 * Event-Busses in JavaFX-Nodes. Hit-Tests und alle Zustandslogik macht allein
 * der Kern — deshalb sind ALLE Modell-Nodes mouseTransparent und die View
 * publi­ziert selbst nichts auf den Bus.
 *
 * Kamera-APPLIZIEREN statt rechnen (demo2-Lektion: die View rechnete selbst
 * mit sceneToLocal herum): {@link CameraInfo} ist die einzige Wahrheit —
 * nach jedem PanView/ZoomView wird der Snapshot des Buses gelesen und auf
 * die worldGroup-Transform angewendet. Screen→Welt ausschließlich über
 * camera.screenToWorldX/Y.
 */
public class GraphView {

    // --- Stil-Konstanten (aus demo2 portiert) ---
    private static final double STROKE_WIDTH = 1.5;
    private static final double SELECTED_STROKE_WIDTH = 2.0;
    private static final Color HOVER_GLOW_COLOR = Color.web("#808080");
    private static final double HOVER_GLOW_RADIUS = 10.0;
    private static final double HOVER_GLOW_SPREAD = 0.2;
    private static final Color SELECTION_GLOW_COLOR = Color.web("#0078D7");
    private static final double SELECTION_GLOW_RADIUS = 15.0;
    private static final double SELECTION_GLOW_SPREAD = 0.5;
    private static final Color WAYPOINT_FILL = Color.web("#FFD700");
    private static final double WAYPOINT_RADIUS = 10.0;
    /** Hover-Toleranz für Verbindungsketten in BILDSCHIRM-Pixeln — dieselbe
     *  Größe wie der Klick-Eintritt im Kern (BaseState), damit der Glow das
     *  Klick-Ziel exakt anzeigt. */
    private static final double CONNECTION_HOVER_TOLERANCE_PIXELS = 8.0;
    private static final double HANDLE_SIZE = 12.0;
    private static final Font OBJECT_FONT = Font.font(14);

    private final InteractionEventBus bus;
    private final DiagramQuery diagramQuery;

    /** Root-Pane: füllt die verfügbare Fläche, sobald Main es in einen
     *  streckenden Parent (StackPane/BorderPane-Center) legt. */
    private final Pane root = new Pane();

    // Layer-Reihenfolge = z-Reihenfolge: Verbindungen unten, Objekte, UI oben.
    private final Group worldGroup = new Group();
    private final Group connectionLayer = new Group();
    private final Group objectLayer = new Group();
    private final Group uiLayer = new Group();

    /** Zoom als EXPLIZITE Scale-Transform (demo2-Stil): Transform-Objekte
     *  pivotieren am URSRPUNG — die Property-API (setScaleX) pivotiert am
     *  Bounds-ZENTRUM und verstimmte jede Kamera-Mathematik (Zoom schwenkte
     *  zum Inhalts-Schwerpunkt statt zum Mauszeiger; s. PivotProbe im
     *  DEVLOG 30.10., Block 8). */
    private final Scale zoomTransform = new Scale();

    /** Modell-Node je Objekt-ID (einschließlich Waypoints und Texte). */
    private final Map<UUID, Group> objectNodes = new HashMap<>();
    /** Live-Bestand der Verbindungen (per Fakten gepflegt — nicht per Poll). */
    private final Map<UUID, Connection> liveConnections = new HashMap<>();
    private final ConnectionViewMapper connectionMapper;

    // --- Sitzungszustand der View (reine Darstellung, kein Modellinhalt) ---
    private final Set<UUID> selectedIds = new HashSet<>();
    /** Abgeleitete Anzeige (03.11.): Ws einer SELEKTIERTEN Verbindung —
     *  sichtbar + blau markiert, ohne in der Auswahl zu stehen (die Auswahl
     *  trägt nur die Connection-ID; Move-Set/Toggle bleiben sauber). */
    private final Set<UUID> selectedConnectionWaypointIds = new HashSet<>();
    private final Set<UUID> waypointIds = new HashSet<>();
    private boolean waypointsVisible = false;
    private UUID hoveredId;
    /** Verbindung unter dem Mauszeiger (grauer Glow) — null = keine. */
    private UUID hoveredConnectionId;
    private double mouseWorldX;
    private double mouseWorldY;

    // --- Verbindungs-Vorschau ( ConnectingState-Hinweis) ---
    private UUID previewSourceId;
    private List<Waypoint> previewWaypoints = List.of();
    private final Path previewPath = new Path();
    /** Gelbe Marker der noch nicht committten Kettenpunkte (demo2-Stil). */
    private final Group previewWaypointGroup = new Group();

    // --- Gummiband-Vorschau ( BoxSelectPreviewChanged-Hinweis) ---
    private final javafx.scene.shape.Rectangle bandRect = new javafx.scene.shape.Rectangle();

    // --- Resize-Griffe ( ResizeModeChanged-Hinweis) ---
    private UUID resizeModeTarget;
    private final Group handleGroup = new Group();

    /** Objekt-ID der aktiven Resize-Live-Vorschau; null = keine Vorschau. */
    private UUID resizePreviewId;

    private final EventHandler<MouseEvent> mouseMovedHandler;

    public GraphView(InteractionEventBus bus, DiagramQuery diagramQuery) {
        this.bus = bus;
        this.diagramQuery = diagramQuery;
        this.connectionMapper = new ConnectionViewMapper(diagramQuery, connectionLayer);

        setupRoot();
        setupLayers();

        // Initial-Bestand: einmaliger Abruf über die Query, danach lebt die
        // View ausschließlich von den Fakten.
        for (DiagramObject object : diagramQuery.allObjectsInZOrder()) {
            addObjectNode(object);
        }
        for (Connection conn : diagramQuery.allConnections()) {
            liveConnections.put(conn.id(), conn);
            connectionMapper.refresh(conn);
        }

        this.mouseMovedHandler = this::onMouseMoved;
        root.addEventFilter(MouseEvent.MOUSE_MOVED, mouseMovedHandler);
        root.addEventFilter(MouseEvent.MOUSE_EXITED, e -> {
            setHovered(null);
            setHoveredConnection(null);
        });

        subscribeFacts();
        applyCamera();
    }

    // ---------------------------------------------------------------------
    // Öffentlicher Vertrag (Main verdrahtet später dagegen)
    // ---------------------------------------------------------------------

    /** Wurzel-Node der Ansicht (füllt die verfügbare Fläche im Parent-Layout). */
    public javafx.scene.Node viewRoot() {
        return root;
    }

    /** Fügt einen Node in den BILDSCHIRM-Raum des Root ein (wie das
     *  Gummiband-Rechteck): Der Inline-Editor nutzt das für sein Overlay —
     *  unskaliert, unabhängig von Zoom/Pan, positioniert in Canvas-Pixeln. */
    public void addToScreenOverlay(Node node) {
        root.getChildren().add(node);
    }

    /** Entfernt einen Node aus dem Bildschirm-Overlay des Root. */
    public void removeFromScreenOverlay(Node node) {
        root.getChildren().remove(node);
    }

    /** Sichtbarkeit des Waypoint-Layers — wird ausschließlich vom
     *  {@link ActionEvent.WaypointFocusChanged}-Hinweis des Kerns getrieben
     *  (RouteEditMode betreten/verlassen); die Toolbar-Checkbox ist entfallen.
     *  Ws einer selektierten Verbindung bleiben davon unberührt sichtbar
     *  (abgeleitete Anzeige, siehe onSelectionChanged). */
    private void setWaypointsVisible(boolean visible) {
        this.waypointsVisible = visible;
        if (!visible) {
            hoveredId = hoveredId != null && waypointIds.contains(hoveredId) ? null : hoveredId;
        }
        refreshWaypointVisibility();
    }

    /** W-Sichtbarkeit = Modus-Flag ODER abgeleitete Selektions-Markierung. */
    private void refreshWaypointVisibility() {
        for (UUID id : waypointIds) {
            Group node = objectNodes.get(id);
            if (node != null) {
                boolean visible = waypointsVisible || selectedConnectionWaypointIds.contains(id);
                node.setVisible(visible);
                node.setManaged(visible);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Aufbau
    // ---------------------------------------------------------------------

    private void setupRoot() {
        // Clipping: Zeichnungen ragen nicht über den Rand (demo2).
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
        clip.widthProperty().bind(root.widthProperty());
        clip.heightProperty().bind(root.heightProperty());
        root.setClip(clip);
        root.setMaxWidth(Region.USE_COMPUTED_SIZE);
        root.setMaxHeight(Region.USE_COMPUTED_SIZE);
    }

    private void setupLayers() {
        worldGroup.getTransforms().add(zoomTransform);
        worldGroup.getChildren().addAll(connectionLayer, objectLayer, uiLayer);
        root.getChildren().add(worldGroup);

        // Vorschau- und Griff-Overlay im UI-Layer: gestrichelt bzw. dezent,
        // nie klickbar (Griff-Drags laufen über den Kern, nicht über Nodes).
        previewPath.setStroke(Color.GRAY);
        previewPath.setStrokeWidth(1.5);
        previewPath.getStrokeDashArray().addAll(8.0, 4.0);
        previewPath.setMouseTransparent(true);
        previewPath.setVisible(false);
        previewWaypointGroup.setMouseTransparent(true);
        uiLayer.getChildren().addAll(handleGroup, previewWaypointGroup, previewPath);

        // Gummiband-Rechteck im BILDSCHIRM-Raum des Root (nicht im skalierten
        // worldGroup): Der Hinweis trägt Screen-Koordinaten, das Band ist
        // reine Präsentation der Geste — zoomunabhängig konstant.
        bandRect.setFill(Color.web("#0078D7", 0.08));
        bandRect.setStroke(Color.web("#0078D7"));
        bandRect.setStrokeWidth(1.0);
        bandRect.getStrokeDashArray().addAll(6.0, 4.0);
        bandRect.setMouseTransparent(true);
        bandRect.setManaged(false);
        bandRect.setVisible(false);
        root.getChildren().add(bandRect);
    }

    private void subscribeFacts() {
        // Kamera anwenden: PanView/ZoomView aktualisieren den Bus-Snapshot
        // VOR dem Versand an Subscriber — getCameraInfo() ist hier schon frisch.
        bus.subscribe(ActionEvent.PanView.class, e -> applyCamera());
        bus.subscribe(ActionEvent.ZoomView.class, e -> applyCamera());

        bus.subscribe(DiagramEvent.ObjectAdded.class, e -> addObjectNode(e.object()));
        bus.subscribe(DiagramEvent.ObjectsMoved.class, e -> onObjectsMoved(e.movedObjects()));
        bus.subscribe(DiagramEvent.ObjectMoved.class,
                e -> onObjectsMoved(List.of(e)));
        bus.subscribe(DiagramEvent.ObjectResized.class, this::onObjectResized);
        bus.subscribe(DiagramEvent.ObjectTextChanged.class,
                e -> onObjectTextChanged(e.updated()));
        bus.subscribe(DiagramEvent.ObjectsRemoved.class, this::onObjectsRemoved);

        bus.subscribe(DiagramEvent.ConnectionEstablished.class,
                e -> onConnectionAdded(e.connection()));
        bus.subscribe(DiagramEvent.ConnectionsRemoved.class,
                e -> e.connections().forEach(c -> onConnectionRemoved(c.id())));
        bus.subscribe(DiagramEvent.ConnectionsUpdated.class,
                e -> {
                    for (Connection updated : e.updated()) {
                        liveConnections.put(updated.id(), updated);
                        connectionMapper.refresh(updated);
                    }
                });

        bus.subscribe(DiagramEvent.SelectionChanged.class, this::onSelectionChanged);

        // State→UI-Hinweise (Sitzungszustand, keine Modell-Fakten)
        bus.subscribe(ActionEvent.ConnectionPreviewChanged.class, this::onPreviewChanged);
        bus.subscribe(ActionEvent.ResizeModeChanged.class, this::onResizeModeChanged);
        bus.subscribe(ActionEvent.ResizePreviewChanged.class, this::onResizePreview);
        bus.subscribe(ActionEvent.WaypointFocusChanged.class,
                e -> setWaypointsVisible(e.connectionId() != null));
        bus.subscribe(ActionEvent.BoxSelectPreviewChanged.class, this::onBandPreviewChanged);
    }

    /** Setzt die worldGroup-Transform aus dem Kamera-Snapshot des Buses. */
    /** Setzt die worldGroup-Transform aus dem Kamera-Snapshot des Buses:
     *  explizite Scale-Transform (Ursprungs-Pivot) + Translate — genau die
     *  Mathematik, die CameraInfo.screenToWorld invertiert. */
    private void applyCamera() {
        CameraInfo camera = bus.getCameraInfo();
        zoomTransform.setX(camera.zoom());
        zoomTransform.setY(camera.zoom());
        worldGroup.setTranslateX(camera.offsetX());
        worldGroup.setTranslateY(camera.offsetY());
    }

    // ---------------------------------------------------------------------
    // Objekt-Nodes: Erzeugen, Updaten, Entfernen
    // ---------------------------------------------------------------------

    private void addObjectNode(DiagramObject object) {
        Group node = objectNodes.get(object.id());
        if (node != null) {
            // Restore-Pfad: Node existiert schon (Duplicate-Fact) — nur updaten.
            updateObjectNode(node, object);
            refreshConnectionsTouching(object.id());
            return;
        }

        node = new Group();
        node.setMouseTransparent(true); // Hit-Tests macht allein der Kern
        fillNodeChildren(node, object);
        updateObjectNode(node, object);

        // TODO (Block-1-Restchen): z-Ordnung folgt hier der Fakt-Reihenfolge —
        // restaurierte Objekte landen so OBEN statt an ihrer Original-Position.
        // Auflösung später über die zIndices aus ObjectsRemoved/RestoreObjects.
        objectLayer.getChildren().add(node);
        objectNodes.put(object.id(), node);

        if (object instanceof Waypoint) {
            waypointIds.add(object.id());
            node.setVisible(waypointsVisible);
            node.setManaged(waypointsVisible);
        }

        applyStyle(object.id());

        // Ein erscheinendes Objekt kann Referenz einer (restaurierten)
        // Verbindung sein — deren Pfad muss neu abgeleitet werden. Kritisch
        // beim Undo eines Löschens: Die LIFO-Disziplin des UndoService
        // liefert AddConnection VOR den ObjectAdded-Fakten, der erste Refresh
        // kannte die Endpunkte/Ws also noch nicht (und entfernte den Node
        // sogar komplett, falls Quelle oder Ziel fehlten). Die View muss
        // gegen beliebige Fakt-Reihenfolgen konvergieren — hier ist der
        // zweite, abschließende Refresh. Bei frischen Erzeugungen ein No-Op
        // (keine Connection referenziert das neue Objekt noch).
        refreshConnectionsTouching(object.id());
    }

    /** Legt die typisierten Kind-Nodes einmalig an (Reihenfolge ist Vertrag:
     *  Kind 0 = Haupt-Shape für Stroke/Glow, danach Text-Children). */
    private void fillNodeChildren(Group node, DiagramObject object) {
        switch (object) {
            case Circle c -> {
                javafx.scene.shape.Circle shape = new javafx.scene.shape.Circle();
                shape.setFill(Color.WHITE);
                shape.setStroke(Color.BLACK);
                shape.setStrokeWidth(STROKE_WIDTH);
                javafx.scene.text.Text label = new javafx.scene.text.Text();
                label.setFont(OBJECT_FONT);
                label.setFill(Color.BLACK);
                label.setTextOrigin(VPos.CENTER);
                node.getChildren().addAll(shape, label);
            }
            case Rectangle r -> {
                javafx.scene.shape.Rectangle shape = new javafx.scene.shape.Rectangle();
                shape.setFill(Color.WHITE);
                shape.setStroke(Color.BLACK);
                shape.setStrokeWidth(STROKE_WIDTH);
                javafx.scene.text.Text content = new javafx.scene.text.Text();
                content.setFont(OBJECT_FONT);
                content.setFill(Color.BLACK);
                // Vertikale Zentrierung gilt IMMER (User-Entscheidung 02.11.:
                // „in der Mitte der Box" ist die Grundhaltung eines R-Inhalts);
                // der spätere „Zentriert"-Schalter steuert nur horizontal.
                content.setTextOrigin(VPos.CENTER);
                node.getChildren().addAll(shape, content);
            }
            case Text t -> {
                // Box als dezenter gestrichelter Rahmen — NUR sichtbar, wenn selektiert.
                javafx.scene.shape.Rectangle box = new javafx.scene.shape.Rectangle();
                box.setFill(Color.TRANSPARENT);
                box.setStroke(Color.GRAY);
                box.setStrokeWidth(1.0);
                box.getStrokeDashArray().addAll(4.0, 3.0);
                box.setVisible(false);
                javafx.scene.text.Text content = new javafx.scene.text.Text();
                content.setFont(OBJECT_FONT);
                content.setFill(Color.BLACK);
                // MITTELPUNKT-Konvention von demo3 (demo2 nutzte Ecke/Baseline —
                // deshalb ist die Positionierungs-Logik hier neu geschrieben):
                // y = Mittelpunkt via TextOrigin.CENTER, x über die zentrierte Box.
                content.setTextOrigin(VPos.CENTER);
                content.setTextAlignment(TextAlignment.CENTER);
                node.getChildren().addAll(box, content);
            }
            case Waypoint w -> {
                javafx.scene.shape.Circle shape = new javafx.scene.shape.Circle();
                shape.setRadius(WAYPOINT_RADIUS);
                shape.setFill(WAYPOINT_FILL);
                shape.setStroke(Color.DARKSLATEGRAY);
                shape.setStrokeWidth(1.0);
                node.getChildren().add(shape);
            }
        }
    }

    /** Aktualisiert Geometrie und Inhalt eines bestehenden Nodes in-place. */
    private void updateObjectNode(Group node, DiagramObject object) {
        switch (object) {
            case Circle c -> {
                var shape = (javafx.scene.shape.Circle) node.getChildren().get(0);
                shape.setCenterX(c.x());
                shape.setCenterY(c.y());
                shape.setRadius(c.radius());
                var label = (javafx.scene.text.Text) node.getChildren().get(1);
                label.setText(c.label());
                // Horizontal zentriert: Textbreite ist nach dem Setzen synchron messbar.
                label.setX(c.x() - label.getBoundsInLocal().getWidth() / 2);
                label.setY(c.y());
            }
            case Rectangle r -> {
                var shape = (javafx.scene.shape.Rectangle) node.getChildren().get(0);
                shape.setX(r.x() - r.width() / 2);
                shape.setY(r.y() - r.height() / 2);
                shape.setWidth(r.width());
                shape.setHeight(r.height());
                var content = (javafx.scene.text.Text) node.getChildren().get(1);
                content.setText(r.content());
                content.setWrappingWidth(Math.max(0, r.width() - 8));
                content.setTextAlignment(r.centered()
                        ? TextAlignment.CENTER : TextAlignment.LEFT);
                content.setX(r.x() - r.width() / 2 + 4);
                // y = Box-Mitte: Der Textblock sitzt vertikal mittig, egal
                // wie hoch das R wird (VPos.CENTER, siehe fillNodeChildren).
                content.setY(r.y());
            }
            case Text t -> {
                var box = (javafx.scene.shape.Rectangle) node.getChildren().get(0);
                box.setX(t.x() - t.width() / 2);
                box.setY(t.y() - t.height() / 2);
                box.setWidth(t.width());
                box.setHeight(t.height());
                var content = (javafx.scene.text.Text) node.getChildren().get(1);
                content.setText(t.content());
                content.setWrappingWidth(t.width());
                content.setX(t.x() - t.width() / 2);
                content.setY(t.y());
            }
            case Waypoint w -> {
                var shape = (javafx.scene.shape.Circle) node.getChildren().get(0);
                shape.setCenterX(w.x());
                shape.setCenterY(w.y());
            }
        }
    }

    private void removeObjectNode(UUID id) {
        Group node = objectNodes.remove(id);
        if (node != null) {
            objectLayer.getChildren().remove(node);
        }
        waypointIds.remove(id);
        if (hoveredId != null && hoveredId.equals(id)) {
            hoveredId = null;
        }
    }

    // ---------------------------------------------------------------------
    // Fakten-Handler
    // ---------------------------------------------------------------------

    private void onObjectsMoved(List<DiagramEvent.ObjectMoved> movedObjects) {
        for (DiagramEvent.ObjectMoved moved : movedObjects) {
            Group node = objectNodes.get(moved.id());
            diagramQuery.findById(moved.id()).ifPresent(obj -> {
                if (node != null) {
                    updateObjectNode(node, obj);
                }
                refreshConnectionsTouching(moved.id());
            });
        }
        // Laufende Griffe mitziehen, falls das Resize-Target verschoben wurde.
        if (resizeModeTarget != null
                && movedObjects.stream().anyMatch(m -> m.id().equals(resizeModeTarget))) {
            refreshHandles();
        }
    }

    private void onObjectResized(DiagramEvent.ObjectResized event) {
        Group node = objectNodes.get(event.resized().id());
        if (node != null) {
            updateObjectNode(node, event.resized());
        }
        refreshConnectionsTouching(event.resized().id());
        if (event.resized().id().equals(resizeModeTarget)) {
            refreshHandles();
        }
    }

    private void onObjectTextChanged(DiagramObject updated) {
        Group node = objectNodes.get(updated.id());
        if (node != null) {
            // Trägt auch neue Position (verankerte Texte gleiten) und Box.
            updateObjectNode(node, updated);
            applyStyle(updated.id());
        }
    }

    private void onObjectsRemoved(DiagramEvent.ObjectsRemoved event) {
        for (DiagramObject object : event.objects()) {
            removeObjectNode(object.id());
        }
        // Verbindungen, die surviving Waypoints verloren haben, folgen als
        // ConnectionsUpdated-Fakt — hier reicht ein defensiver Refresh aller
        // noch lebender Verbindungen (der Mapper überspringt Fehlendes still).
        liveConnections.values().forEach(connectionMapper::refresh);
    }

    private void onConnectionAdded(Connection conn) {
        liveConnections.put(conn.id(), conn);
        connectionMapper.refresh(conn);
    }

    private void onConnectionRemoved(UUID connectionId) {
        liveConnections.remove(connectionId);
        connectionMapper.remove(connectionId);
        if (connectionId.equals(hoveredConnectionId)) {
            hoveredConnectionId = null;
        }
    }

    /** Refresh aller Verbindungen, die das Objekt als Endpunkt oder Waypoint referenzieren. */
    private void refreshConnectionsTouching(UUID objectId) {
        for (Connection conn : liveConnections.values()) {
            if (conn.sourceId().equals(objectId)
                    || conn.targetId().equals(objectId)
                    || conn.waypointIds().contains(objectId)) {
                connectionMapper.refresh(conn);
            }
        }
    }

    private void onSelectionChanged(DiagramEvent.SelectionChanged event) {
        Set<UUID> fresh = new HashSet<>(event.selectedIds());
        // Diff-basiert stylen: nur die Differenzmenge alt↔neu anfassen.
        Set<UUID> changed = new HashSet<>(selectedIds);
        changed.addAll(fresh);
        changed.removeAll(common(selectedIds, fresh));

        selectedIds.clear();
        selectedIds.addAll(fresh);
        changed.forEach(this::applyStyle);

        // Selektierte Verbindungen: blauer Pfad (demo2-Stil wie bei Objekten).
        changed.forEach(this::applyConnectionStyle);

        // Abgeleitete W-Markierung: Die Ws einer selektierten Verbindung
        // werden sichtbar + blau markiert, OHNE in der Auswahl zu stehen —
        // die Auswahl trägt nur die Connection-ID (Kern-Entscheidung 03.11.:
        // Kaskade beim Löschen, nie Move-Basis beim Ziehen).
        Set<UUID> freshWaypoints = new HashSet<>();
        for (UUID id : selectedIds) {
            Connection conn = liveConnections.get(id);
            if (conn != null) {
                freshWaypoints.addAll(conn.waypointIds());
            }
        }
        Set<UUID> changedWaypoints = new HashSet<>(selectedConnectionWaypointIds);
        changedWaypoints.addAll(freshWaypoints);
        changedWaypoints.removeAll(common(selectedConnectionWaypointIds, freshWaypoints));

        selectedConnectionWaypointIds.clear();
        selectedConnectionWaypointIds.addAll(freshWaypoints);
        changedWaypoints.forEach(this::applyStyle);
        refreshWaypointVisibility();
    }

    private static Set<UUID> common(Set<UUID> a, Set<UUID> b) {
        Set<UUID> result = new HashSet<>(a);
        result.retainAll(b);
        return result;
    }

    // ---------------------------------------------------------------------
    // Hover (reine UI-Sache — erreicht den Kern nie)
    // ---------------------------------------------------------------------

    private void onMouseMoved(MouseEvent event) {
        // Ziel-Greife wie im InputEventTranslator: Über einem Overlay-Control
        // (Inline-Editor) gibt es kein Canvas-Hover — der letzte Glow wird
        // gelöscht, statt stehen zu bleiben, während die Maus im Editor ist.
        if (event.getTarget() != root) {
            setHovered(null);
            setHoveredConnection(null);
            return;
        }
        // Screen→Welt ausschließlich über die Kamera des Buses (niemals
        // sceneToLocal oder eigene Formeln — CameraInfo ist die Wahrheit).
        CameraInfo camera = bus.getCameraInfo();
        double worldX = camera.screenToWorldX(event.getX());
        double worldY = camera.screenToWorldY(event.getY());
        mouseWorldX = worldX;
        mouseWorldY = worldY;

        var hit = diagramQuery.findTopmostAt(worldX, worldY);
        setHovered(hit.map(DiagramObject::id).orElse(null));

        // Kein Objekt unter der Maus -> Verbindungskette in Nähe prüfen
        // (grauer Glow, demo2-Stil; dieselbe Toleranz wie der Klick-Eintritt
        // in den RouteEditMode — der Glow zeigt das Klick-Ziel).
        UUID connectionHover = null;
        if (hoveredId == null) {
            double toleranceWorld =
                    CONNECTION_HOVER_TOLERANCE_PIXELS / camera.zoom();
            connectionHover = diagramQuery
                    .findConnectionNear(worldX, worldY, toleranceWorld)
                    .orElse(null);
        }
        setHoveredConnection(connectionHover);

        if (previewSourceId != null) {
            updatePreview();
        }
    }

    private void setHovered(UUID id) {
        // Unsichtbare Waypoints sind kein Hover-Ziel.
        if (id != null && waypointIds.contains(id) && !waypointsVisible) {
            id = null;
        }
        if (java.util.Objects.equals(id, hoveredId)) {
            return;
        }
        UUID previous = hoveredId;
        hoveredId = id;
        if (previous != null) {
            applyStyle(previous);
        }
        if (hoveredId != null) {
            applyStyle(hoveredId);
        }
    }

    /** Hover auf eine Verbindungskette: grauer Glow auf dem Pfad — nur wenn
     *  kein Objekt gehovert wird (setHovered hat Vorrang). */
    private void setHoveredConnection(UUID id) {
        if (java.util.Objects.equals(id, hoveredConnectionId)) {
            return;
        }
        UUID previous = hoveredConnectionId;
        hoveredConnectionId = id;
        if (previous != null) {
            applyConnectionStyle(previous);
        }
        if (hoveredConnectionId != null) {
            applyConnectionStyle(hoveredConnectionId);
        }
    }

    private void applyConnectionStyle(UUID id) {
        Group group = connectionMapper.groupOf(id);
        if (group == null || group.getChildren().isEmpty()
                || !(group.getChildren().get(0) instanceof Path path)) {
            return;
        }
        if (selectedIds.contains(id)) {
            // Selektierte Verbindung: blauer Glow (03.11.) — derselbe Stil wie
            // bei Objekten; Hover hat dann nichts zu melden.
            path.setEffect(selectionGlow());
        } else if (id.equals(hoveredConnectionId)) {
            DropShadow hoverGlow = new DropShadow();
            hoverGlow.setColor(HOVER_GLOW_COLOR);
            hoverGlow.setRadius(10.0);
            hoverGlow.setSpread(0.2);
            path.setEffect(hoverGlow);
        } else {
            path.setEffect(null);
        }
    }

    // ---------------------------------------------------------------------
    // Stil = Funktion aus (selektiert?, gehovert?)
    // ---------------------------------------------------------------------

    private void applyStyle(UUID id) {
        Group node = objectNodes.get(id);
        if (node == null || node.getChildren().isEmpty()
                || !(node.getChildren().get(0) instanceof javafx.scene.shape.Shape shape)) {
            return;
        }

        boolean selected = selectedIds.contains(id)
                || selectedConnectionWaypointIds.contains(id);
        boolean hovered = id.equals(hoveredId);

        shape.setStroke(Color.BLACK);
        shape.setStrokeWidth(selected ? SELECTED_STROKE_WIDTH : STROKE_WIDTH);
        shape.setEffect(null);

        if (selected) {
            // Selektion: blauer Glow (demo2-Stil)
            shape.setEffect(selectionGlow());
        } else if (hovered) {
            // Hover nur, wenn nicht selektiert: dezenter grauer Glow (demo2-Stil)
            DropShadow hoverGlow = new DropShadow();
            hoverGlow.setColor(HOVER_GLOW_COLOR);
            hoverGlow.setRadius(HOVER_GLOW_RADIUS);
            hoverGlow.setSpread(HOVER_GLOW_SPREAD);
            shape.setEffect(hoverGlow);
        }

        // Text-Box-Rahmen bei Selektion ODER Hover zeigen — NUR beim
        // Modell-Typ Text! (Kind 0 eines FMC-Rechtecks ist ebenfalls ein
        // javafx-Rectangle; der alte instanceof-Check auf den NODE-Typ machte
        // deshalb jedes unselektierte R unsichtbar. Typ-Entscheidungen laufen
        // immer über das Modell, nie über View-Node-Typen. Ohne den Hover-
        // Zweig glühte der Glow auf einer unsichtbaren Box — Texte schienen
        // kein Hover-Feedback zu haben, obwohl der Kern sie längst trifft.)
        boolean isTextObject = diagramQuery.findById(id)
                .map(obj -> obj instanceof Text)
                .orElse(false);
        if (isTextObject && shape instanceof javafx.scene.shape.Rectangle box) {
            box.setVisible(selected || hovered);
        }
    }

    private DropShadow selectionGlow() {
        DropShadow glow = new DropShadow();
        glow.setColor(SELECTION_GLOW_COLOR);
        glow.setRadius(SELECTION_GLOW_RADIUS);
        glow.setSpread(SELECTION_GLOW_SPREAD);
        return glow;
    }

    // ---------------------------------------------------------------------
    // Verbindungs-Vorschau ( ConnectingState)
    // ---------------------------------------------------------------------

    private void onPreviewChanged(ActionEvent.ConnectionPreviewChanged event) {
        previewSourceId = event.sourceId();
        previewWaypoints = event.waypoints();
        previewPath.setVisible(previewSourceId != null);
        rebuildPreviewWaypointMarkers();
        updatePreview();
    }

    /** Gelbe Marker der noch NICHT committeten Kettenpunkte (demo2-Stil:
     *  Füllung/Radius wie die Modell-Ws). Reine Präsentation des Hinweises —
     *  die Punkte werden erst mit CreateConnection Modell-Fakten. */
    private void rebuildPreviewWaypointMarkers() {
        previewWaypointGroup.getChildren().clear();
        if (previewSourceId == null) {
            return;
        }
        for (Waypoint wp : previewWaypoints) {
            javafx.scene.shape.Circle marker =
                    new javafx.scene.shape.Circle(wp.x(), wp.y(), WAYPOINT_RADIUS);
            marker.setFill(WAYPOINT_FILL);
            marker.setStroke(Color.DARKSLATEGRAY);
            marker.setStrokeWidth(1.0);
            previewWaypointGroup.getChildren().add(marker);
        }
    }

    /** source → Waypoints → aktuelle Mausposition, gestrichelt im uiLayer. */
    private void updatePreview() {
        if (previewSourceId == null) {
            return;
        }
        var source = diagramQuery.findById(previewSourceId);
        if (source.isEmpty()) {
            previewPath.getElements().clear();
            return;
        }

        previewPath.getElements().clear();
        previewPath.getElements().add(new MoveTo(source.get().x(), source.get().y()));
        for (Waypoint wp : previewWaypoints) {
            previewPath.getElements().add(new LineTo(wp.x(), wp.y()));
        }
        // Freier Abschnitt: letzter Punkt → Maus (aus eigenem MOUSE_MOVED-Tracking).
        previewPath.getElements().add(new LineTo(mouseWorldX, mouseWorldY));
    }

    // ---------------------------------------------------------------------
    // Gummiband-Vorschau ( BoxSelectPreviewChanged-Hinweis)
    // ---------------------------------------------------------------------

    /** Bildschirm-Rechteck der laufenden Band-Geste — der Kern rechnet die
     *  Treffer erst beim Loslassen (über die Kamera in Welt-Koordinaten);
     *  hier wird nur gezeichnet, was die Geste zeigt. */
    private void onBandPreviewChanged(ActionEvent.BoxSelectPreviewChanged event) {
        if (event.startX() == null) {
            bandRect.setVisible(false);
            return;
        }
        bandRect.setX(Math.min(event.startX(), event.currentX()));
        bandRect.setY(Math.min(event.startY(), event.currentY()));
        bandRect.setWidth(Math.abs(event.currentX() - event.startX()));
        bandRect.setHeight(Math.abs(event.currentY() - event.startY()));
        bandRect.setVisible(true);
    }

    // ---------------------------------------------------------------------
    // Resize-Griffe ( ResizeModeChanged)
    // ---------------------------------------------------------------------

    private void onResizeModeChanged(ActionEvent.ResizeModeChanged event) {
        resizeModeTarget = event.target() != null ? event.target().id() : null;
        refreshHandles();
    }

    /** Griff-Positionen aus HandlePositions (Kern-Geometrie) — 8×8-Quadrate
     *  mit schwarzem Fill und weißem Rand (demo2-Stil). Ohne Argument aus dem
     *  MODELL (nach Fakten); die Live-Vorschau zeichnet gegen ihr eigenes
     *  Geometrie-Record, damit Shape und Griffe nie auseinanderlaufen. */
    private void refreshHandles() {
        handleGroup.getChildren().clear();
        if (resizeModeTarget == null) {
            return;
        }
        diagramQuery.findById(resizeModeTarget).ifPresent(this::drawHandles);
    }

    private void drawHandles(DiagramObject target) {
        for (HandlePositions.HandlePoint point : HandlePositions.of(target).values()) {
            javafx.scene.shape.Rectangle handle = new javafx.scene.shape.Rectangle(
                    point.x() - HANDLE_SIZE / 2, point.y() - HANDLE_SIZE / 2,
                    HANDLE_SIZE, HANDLE_SIZE);
            handle.setFill(Color.BLACK);
            handle.setStroke(Color.WHITE);
            handle.setStrokeWidth(1.0);
            handle.setStrokeType(StrokeType.INSIDE);
            handle.setMouseTransparent(true); // Griff-Drags laufen über den Kern
            handleGroup.getChildren().add(handle);
        }
    }

    /** Live-Vorschau der Resize-Geste: Der Kern hat die Geometrie berechnet
     *  (symmetrisch, Grid-Klemmung) — die View wendet sie nur an. Ende
     *  (preview == null): Beim ABBRUCH kommt kein Fakt, die Geometrie muss
     *  aus dem Modell wiederhergestellt werden; nach dem Commit hat der
     *  ObjectResized-Fakt ohnehin schon gesetzt. */
    private void onResizePreview(ActionEvent.ResizePreviewChanged event) {
        if (event.preview() == null) {
            if (resizePreviewId != null) {
                diagramQuery.findById(resizePreviewId).ifPresent(obj -> {
                    Group node = objectNodes.get(obj.id());
                    if (node != null) {
                        updateObjectNode(node, obj);
                    }
                });
                refreshHandles();
                resizePreviewId = null;
            }
            return;
        }
        resizePreviewId = event.preview().id();
        Group node = objectNodes.get(event.preview().id());
        if (node != null) {
            updateObjectNode(node, event.preview());
            handleGroup.getChildren().clear();
            drawHandles(event.preview());
        }
    }
}
