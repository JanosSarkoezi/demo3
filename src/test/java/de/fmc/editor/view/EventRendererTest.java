package de.fmc.editor.view;

import de.fmc.editor.core.model.Event;
import de.fmc.editor.core.model.EventDirection;
import de.fmc.editor.core.model.EventSide;
import de.fmc.editor.core.model.Rectangle;
import javafx.scene.Group;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.Polygon;
import javafx.scene.transform.Rotate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Headless-Test des {@link EventRenderer}: JavaFX-Shapes instanziieren und
 * transformieren ohne gestartetes Toolkit (dasselbe Muster wie
 * ConnectionViewMapperTest) — die Event-Geometrie ist damit ohne UI messbar.
 *
 * Kernfragen: Der Pfad läuft Start → Bogen → Spitze, das Liniensende ist um
 * die Pfeil-Einkerbung zurückgezogen (demo2-Algorithmus), die Pfeilspitze
 * sitzt AUF der R-Oberkante und zeigt NACH UNTEN (Andocken am R). Das
 * Label ist seit 23.11. ein eigenes Text-Objekt und KEIN Kind des
 * Event-Nodes mehr. Die Koordinaten stammen ausschließlich aus
 * EventGeometry — der Renderer rechnet nie selbst.
 */
class EventRendererTest {

    private static final double EPSILON = 1e-9;
    private static final double ARROW_INDENT = 12.0 * 0.65; // ArrowShape.indent()

    private final Rectangle owner = new Rectangle(UUID.randomUUID(),
            100.0, 100.0, 40.0, 30.0, UUID.randomUUID());
    // R: Seitenränder x=80/120, Oberkante y=85. Rechts: Spitze (110/85),
    // Knick (110/45), Start (160/45). Links gespiegelt: Spitze (90/85),
    // Start (40/45).

    private Group rendered(Event event) {
        Group node = new Group();
        EventRenderer.buildNode(node);
        EventRenderer.updateNode(node, event, owner);
        return node;
    }

    @Test
    @DisplayName("Pfad: Start → Bogen → Spitze; Linie endet an der Pfeil-Einkerbung")
    void pathRunsStartArcTipWithIndent() {
        Group node = rendered(new Event(UUID.randomUUID(), owner.id(), EventSide.RIGHT,
                40.0, 40.0, 110.0, 85.0, owner.layerId()));

        // Kind 0 = Path (Haupt-Shape), Kind 1 = Pfeilspitze — das Label ist
        // seit 23.11. ein eigenes Text-Objekt, kein Kind dieses Nodes mehr.
        assertThat(node.getChildren()).hasSize(2);
        Path path = (Path) node.getChildren().get(0);
        assertThat(path.getElements()).isNotEmpty();

        // Erster Punkt = Start (Label-Anker)
        MoveTo start = (MoveTo) path.getElements().get(0);
        assertThat(start.getX()).isCloseTo(160.0, within(EPSILON));
        assertThat(start.getY()).isCloseTo(45.0, within(EPSILON));

        // Letztes Element: zurückgezogenes Liniensende vor der Spitze
        var last = path.getElements().get(path.getElements().size() - 1);
        assertThat(last).isInstanceOf(LineTo.class);
        LineTo lineEnd = (LineTo) last;
        assertThat(lineEnd.getX()).isCloseTo(110.0, within(EPSILON));
        assertThat(lineEnd.getY()).isCloseTo(85.0 - ARROW_INDENT, within(EPSILON));

        // Kein Element springt über die Grundform hinaus: Alle x innerhalb
        // [110, 160], alle y innerhalb [45, 85].
        path.getElements().forEach(element -> {
            if (element instanceof MoveTo m) {
                assertThat(m.getX()).isBetween(109.999, 160.001);
                assertThat(m.getY()).isBetween(44.999, 85.001);
            } else if (element instanceof LineTo l) {
                assertThat(l.getX()).isBetween(109.999, 160.001);
                assertThat(l.getY()).isBetween(44.999, 85.001);
            }
        });
    }

    @Test
    @DisplayName("Pfeilspitze: sitzt AUF der Oberkante, zeigt nach unten (90°)")
    void arrowDockedAtTopEdgePointingDown() {
        Group node = rendered(new Event(UUID.randomUUID(), owner.id(), EventSide.RIGHT,
                40.0, 40.0, 110.0, 85.0, owner.layerId()));

        Polygon arrow = (Polygon) node.getChildren().get(1);
        assertThat(arrow.getLayoutX()).isCloseTo(110.0, within(EPSILON));
        assertThat(arrow.getLayoutY()).isCloseTo(85.0, within(EPSILON));
        assertThat(arrow.getTransforms().get(0))
                .isInstanceOf(Rotate.class);
        assertThat(((Rotate) arrow.getTransforms().get(0)).getAngle())
                .isCloseTo(90.0, within(EPSILON)); // senkrecht nach unten
    }

    @Test
    @DisplayName("Linke Seite: gespiegelter Start (40/45), gleiche Andock-Richtung")
    void leftSideMirrored() {
        Group node = rendered(new Event(UUID.randomUUID(), owner.id(), EventSide.LEFT,
                40.0, 40.0, 90.0, 85.0, owner.layerId()));

        Path path = (Path) node.getChildren().get(0);
        MoveTo start = (MoveTo) path.getElements().get(0);
        assertThat(start.getX()).isCloseTo(40.0, within(EPSILON));
        assertThat(start.getY()).isCloseTo(45.0, within(EPSILON));

        Polygon arrow = (Polygon) node.getChildren().get(1);
        assertThat(arrow.getLayoutX()).isCloseTo(90.0, within(EPSILON));
        assertThat(((Rotate) arrow.getTransforms().get(0)).getAngle())
                .isCloseTo(90.0, within(EPSILON));
    }

    @Test
    @DisplayName("Gezogene Form (80/60): Start wandert mit, Andockpunkt bleibt")
    void pulledShapeMovesStartNotTip() {
        Group node = rendered(new Event(UUID.randomUUID(), owner.id(), EventSide.RIGHT,
                80.0, 60.0, 110.0, 85.0, owner.layerId()));

        Path path = (Path) node.getChildren().get(0);
        MoveTo start = (MoveTo) path.getElements().get(0);
        // Start = Seitenrand 120 + 80 = 200, Knick-Höhe 85 - 60 = 25
        assertThat(start.getX()).isCloseTo(200.0, within(EPSILON));
        assertThat(start.getY()).isCloseTo(25.0, within(EPSILON));

        Polygon arrow = (Polygon) node.getChildren().get(1);
        assertThat(arrow.getLayoutY()).isCloseTo(85.0, within(EPSILON));
    }

    // ---------- OUTGOING (26.11.): Fuß unten, Pfeilspitze außen ----------

    @Test
    @DisplayName("OUTGOING: Pfad läuft Fuß → Bogen → äußeres Ende; Pfeil zeigt nach außen (0°)")
    void outgoingPathRunsFootArcEndWithOutwardArrow() {
        Group node = rendered(new Event(UUID.randomUUID(), owner.id(), EventSide.RIGHT,
                EventDirection.OUTGOING, 40.0, 40.0, 110.0, 115.0, owner.layerId()));

        Path path = (Path) node.getChildren().get(0);
        // Erster Punkt = Fuß an der UNTERKANTE (kein Start außen mehr —
        // die umgedrehte Punktfolge beginnt am R).
        MoveTo foot = (MoveTo) path.getElements().get(0);
        assertThat(foot.getX()).isCloseTo(110.0, within(EPSILON));
        assertThat(foot.getY()).isCloseTo(115.0, within(EPSILON));

        // Letztes Element: zurückgezogenes Liniensende vor dem äußeren Ende.
        var last = path.getElements().get(path.getElements().size() - 1);
        assertThat(last).isInstanceOf(LineTo.class);
        LineTo lineEnd = (LineTo) last;
        assertThat(lineEnd.getX()).isCloseTo(160.0 - ARROW_INDENT, within(EPSILON));
        assertThat(lineEnd.getY()).isCloseTo(155.0, within(EPSILON));

        // Pfeilspitze am äußeren Ende, waagrecht NACH AUSSEN (0°) — der
        // Renderer orientiert am letzten Segment, ohne Richtungswissen.
        Polygon arrow = (Polygon) node.getChildren().get(1);
        assertThat(arrow.getLayoutX()).isCloseTo(160.0, within(EPSILON));
        assertThat(arrow.getLayoutY()).isCloseTo(155.0, within(EPSILON));
        assertThat(((Rotate) arrow.getTransforms().get(0)).getAngle())
                .isCloseTo(0.0, within(EPSILON));
    }

    @Test
    @DisplayName("OUTGOING links: gespiegeltes äußeres Ende, Pfeil nach links (180°)")
    void outgoingLeftMirrored() {
        Group node = rendered(new Event(UUID.randomUUID(), owner.id(), EventSide.LEFT,
                EventDirection.OUTGOING, 40.0, 40.0, 90.0, 115.0, owner.layerId()));

        Polygon arrow = (Polygon) node.getChildren().get(1);
        assertThat(arrow.getLayoutX()).isCloseTo(40.0, within(EPSILON));
        assertThat(arrow.getLayoutY()).isCloseTo(155.0, within(EPSILON));
        assertThat(((Rotate) arrow.getTransforms().get(0)).getAngle())
                .isCloseTo(180.0, within(EPSILON));
    }
}
