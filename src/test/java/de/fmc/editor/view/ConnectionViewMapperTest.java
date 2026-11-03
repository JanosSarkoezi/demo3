package de.fmc.editor.view;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.Connection;
import de.fmc.editor.core.model.GridSpec;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.service.DiagramService;
import de.fmc.editor.core.service.SelectionService;
import de.fmc.editor.core.state.CameraInfo;
import javafx.scene.Group;
import javafx.scene.shape.Polygon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Headless-Test des {@link ConnectionViewMapper}: JavaFX-Shapes (Path,
 * Polygon, Group, Rotate) instanziieren und transformieren ohne gestartetes
 * Toolkit — die Pfeil-Geometrie ist damit ohne UI messbar (Goldene Regel 2
 * im View-Sinn: erst messen, dann manueller Smoke-Test).
 *
 * Kernfrage (User-Fund 09.11.): Die Pfeilspitze muss während der Resize-
 * Geste an der PREVIEW-Geometrie kleben — refresh(conn, override) — und
 * beim Refresh ohne Override wieder am Modellstand liegen. Die Verdrahtung
 * entspricht 1:1 dem GraphView (onConnectionAdded → refresh,
 * onObjectResized → refreshConnectionsTouching); BusTestSupport ist
 * package-private und damit hier nicht zugänglich, der Aufbau ist derselbe.
 */
class ConnectionViewMapperTest {

    private static final double EPSILON = 1e-9;

    private final DiagramService diagram = new DiagramService();
    private final InteractionEventBus bus;
    private final ConnectionViewMapper mapper;
    private final AtomicReference<Connection> establishedConnection = new AtomicReference<>();

    ConnectionViewMapperTest() {
        bus = new InteractionEventBus(diagram,
                new CameraInfo(1.0, 0.0, 0.0, UUID.randomUUID()),
                new GridSpec(10.0),
                new SelectionService());
        diagram.attachTo(bus);
        mapper = new ConnectionViewMapper(diagram, new Group());
        bus.subscribe(DiagramEvent.ConnectionEstablished.class,
                e -> establishedConnection.set(e.connection()));
    }

    @Test
    @DisplayName("Pfeilspitze sitzt am Zielrand und folgt der Resize-Live-Vorschau (Override)")
    void arrowFollowsResizePreview() {
        Fixture fx = connectCircleToRectangle();

        // Initial: linke R-Kante bei x = 400 - 60/2 = 370
        assertThat(arrowTip(fx.connection)).containsExactly(
                new double[]{370.0, 100.0}, within(EPSILON));

        // Live-Vorschau: R symmetrisch auf 200x200 gezogen — Modell UNVERÄNDERT,
        // linke Kante bei x = 400 - 200/2 = 300
        mapper.refresh(fx.connection,
                new Rectangle(fx.target.id(), 400, 100, 200, 200, null, true, fx.layerId));
        assertThat(arrowTip(fx.connection)).containsExactly(
                new double[]{300.0, 100.0}, within(EPSILON));

        // Gegenprobe Schrumpfen: 20x20 -> linke Kante bei x = 390
        mapper.refresh(fx.connection,
                new Rectangle(fx.target.id(), 400, 100, 20, 20, null, true, fx.layerId));
        assertThat(arrowTip(fx.connection)).containsExactly(
                new double[]{390.0, 100.0}, within(EPSILON));
    }

    @Test
    @DisplayName("Refresh ohne Override stellt den Modellstand wieder her (Abbruch-Semantik)")
    void refreshWithoutOverrideRestoresModelGeometry() {
        Fixture fx = connectCircleToRectangle();

        mapper.refresh(fx.connection,
                new Rectangle(fx.target.id(), 400, 100, 200, 200, null, true, fx.layerId));
        assertThat(arrowTip(fx.connection)[0]).isCloseTo(300.0, within(EPSILON));

        // Kein Commit passiert: refresh(conn) = Restore aus dem Modell -> x = 370.
        // Genau das läuft im GraphView beim Null-Hinweis nach dem ESC-Abbruch.
        mapper.refresh(fx.connection);
        assertThat(arrowTip(fx.connection)).containsExactly(
                new double[]{370.0, 100.0}, within(EPSILON));
    }

    @Test
    @DisplayName("Nach dem Commit (ResizeBox) zeichnet refresh gegen die neue Modellgeometrie")
    void refreshAfterCommitUsesNewModelGeometry() {
        Fixture fx = connectCircleToRectangle();

        bus.publish(new ActionEvent.ResizeBox(fx.target.id(), 200.0, 200.0));
        mapper.refresh(fx.connection);
        assertThat(arrowTip(fx.connection)).containsExactly(
                new double[]{300.0, 100.0}, within(EPSILON));
    }

    @Test
    @DisplayName("Override einer fremden ID lässt die Verbindung unberührt")
    void overrideOfUnrelatedIdIsIgnored() {
        Fixture fx = connectCircleToRectangle();

        mapper.refresh(fx.connection,
                new Rectangle(UUID.randomUUID(), 0, 0, 500, 500, null, true, fx.layerId));
        assertThat(arrowTip(fx.connection)).containsExactly(
                new double[]{370.0, 100.0}, within(EPSILON));
    }

    // -----------------------------------------------------------------
    // Aufbau
    // -----------------------------------------------------------------

    /** K (Quelle) → R (Ziel), direkte Verbindung ohne Waypoints. */
    private Fixture connectCircleToRectangle() {
        UUID layerId = UUID.randomUUID();
        Circle source = new Circle(UUID.randomUUID(), 100, 100, 20, "K", layerId);
        Rectangle target = new Rectangle(UUID.randomUUID(), 400, 100, 60, 40, null, true, layerId);
        bus.publish(new ActionEvent.AddElement(source));
        bus.publish(new ActionEvent.AddElement(target));
        bus.publish(new ActionEvent.CreateConnection(source.id(), target.id(), List.of()));

        Connection connection = establishedConnection.get();
        assertThat(connection).as("Verbindung muss etabliert sein (K→R ist regelkonform)").isNotNull();
        mapper.refresh(connection);
        return new Fixture(layerId, target, connection);
    }

    /** Position der Pfeilspitze (Kind 1 der Verbindungs-Group). */
    private double[] arrowTip(Connection connection) {
        Group group = mapper.groupOf(connection.id());
        assertThat(group).as("Verbindungs-Node muss existieren").isNotNull();
        Polygon arrow = (Polygon) group.getChildren().get(1);
        return new double[]{arrow.getLayoutX(), arrow.getLayoutY()};
    }

    private record Fixture(UUID layerId, Rectangle target, Connection connection) {}
}
