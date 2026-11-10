package de.fmc.editor.view;

import de.fmc.editor.core.event.CustomCursor;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.ToolType;
import javafx.scene.Cursor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifiziert die Cursor-Auflösung des {@link CursorPresenter} headless
 * (dasselbe Muster wie ConnectionViewMapperTest — JavaFX-Klassen sind
 * ohne gestartetes Toolkit nutzbar): Prioritäten der Zustände,
 * richtungsabhängige Griff-Cursor mit Zoom-Skalierung und die
 * Übersetzung am Systemrand.
 */
class CursorPresenterTest {

    private final UUID layerId = UUID.randomUUID();

    /** 40×30 an (100,100): NW=(80,85), N=(100,85), E=(120,100),
     *  SE=(120,115), S=(100,115), W=(80,100). */
    private final Rectangle rect = new Rectangle(UUID.randomUUID(), 100.0, 100.0, 40.0, 30.0, layerId);

    /** Radius 20 an (100,100): N=(100,80), E=(120,100), S=(100,120),
     *  W=(80,100) — Kreis hat KEINE Ecken-Griffe. */
    private final Circle circle = new Circle(UUID.randomUUID(), 100.0, 100.0, 20.0, layerId);

    @Test
    @DisplayName("Grundzustand: SELECT ohne alles = Default-Pfeil")
    void freshPresenterShowsDefault() {
        assertThat(new CursorPresenter().resolve()).isEqualTo(CustomCursor.DEFAULT);
    }

    @Test
    @DisplayName("Erzeugungs-Werkzeuge zeigen das Fadenkreuz als Grundcursor")
    void creationToolsShowCrosshair() {
        CursorPresenter presenter = new CursorPresenter();
        for (ToolType tool : ToolType.values()) {
            presenter.setTool(tool);
            CustomCursor expected = tool == ToolType.SELECT
                    ? CustomCursor.DEFAULT : CustomCursor.CROSSHAIR;
            assertThat(presenter.resolve())
                    .as("Werkzeug %s", tool)
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("Kern-Geste hat Vorrang vor allem Darstellenden")
    void gestureCursorOverridesEverything() {
        CursorPresenter presenter = new CursorPresenter();
        presenter.setGestureCursor(CustomCursor.CLOSED_HAND);
        presenter.setTool(ToolType.CIRCLE);
        presenter.setResizeTarget(rect);
        presenter.setMouseWorld(80.0, 85.0); // über dem NW-Griff
        presenter.setObjectHovered(true);

        assertThat(presenter.resolve()).isEqualTo(CustomCursor.CLOSED_HAND);

        // Freigabe (null) stellt die darstellende Auflösung wieder her:
        // über dem Griff im Resize-Modus erscheint der Resize-Cursor.
        presenter.setGestureCursor(null);
        assertThat(presenter.resolve()).isEqualTo(CustomCursor.NW_RESIZE);
    }

    @Test
    @DisplayName("Laufende Verbindungs-Geste: Fadenkreuz statt MOVE über Objekten")
    void connectingGestureShowsCrosshairOverObject() {
        CursorPresenter presenter = new CursorPresenter();
        presenter.setConnecting(true);
        presenter.setObjectHovered(true); // Klick würde VERBINDEN, nicht ziehen
        assertThat(presenter.resolve()).isEqualTo(CustomCursor.CROSSHAIR);
    }

    @Test
    @DisplayName("Laufende Gummiband-Geste: Fadenkreuz")
    void bandGestureShowsCrosshair() {
        CursorPresenter presenter = new CursorPresenter();
        presenter.setBandActive(true);
        assertThat(presenter.resolve()).isEqualTo(CustomCursor.CROSSHAIR);
    }

    @Test
    @DisplayName("Resize-Modus: richtungsabhängige Cursor über den Box-Griffen")
    void resizeModeShowsDirectionalCursorsOverBoxHandles() {
        CursorPresenter presenter = new CursorPresenter();
        presenter.setResizeTarget(rect);

        presenter.setMouseWorld(80.0, 85.0);
        assertThat(presenter.resolve()).isEqualTo(CustomCursor.NW_RESIZE);

        presenter.setMouseWorld(100.0, 85.0);
        assertThat(presenter.resolve()).isEqualTo(CustomCursor.N_RESIZE);

        presenter.setMouseWorld(120.0, 100.0);
        assertThat(presenter.resolve()).isEqualTo(CustomCursor.E_RESIZE);

        presenter.setMouseWorld(100.0, 115.0);
        assertThat(presenter.resolve()).isEqualTo(CustomCursor.S_RESIZE);

        presenter.setMouseWorld(80.0, 100.0);
        assertThat(presenter.resolve()).isEqualTo(CustomCursor.W_RESIZE);

        // Nicht über einem Griff: schlicht (Klick daneben verlässt den Modus).
        presenter.setMouseWorld(100.0, 100.0);
        assertThat(presenter.resolve()).isEqualTo(CustomCursor.DEFAULT);

        presenter.setMouseWorld(500.0, 500.0);
        assertThat(presenter.resolve()).isEqualTo(CustomCursor.DEFAULT);
    }

    @Test
    @DisplayName("Resize-Modus: Kreis kennt nur N/E/S/W — über der fehlenden Ecke kein Resize-Cursor")
    void circleResizeShowsOnlyCompassHandles() {
        CursorPresenter presenter = new CursorPresenter();
        presenter.setResizeTarget(circle);

        presenter.setMouseWorld(100.0, 80.0);
        assertThat(presenter.resolve()).isEqualTo(CustomCursor.N_RESIZE);

        presenter.setMouseWorld(120.0, 100.0);
        assertThat(presenter.resolve()).isEqualTo(CustomCursor.E_RESIZE);

        // Die NW-Ecke existiert beim Kreis nicht (Abstand zum nächsten
        // Griff W=(80,100) beträgt 15 Welt-Einheiten > Grab-Radius 6):
        presenter.setMouseWorld(80.0, 85.0);
        assertThat(presenter.resolve()).isEqualTo(CustomCursor.DEFAULT);
    }

    @Test
    @DisplayName("Griff-Grab-Radius skaliert mit dem Zoom (Bildschirm-Pixel bleiben konstant)")
    void handleGrabRadiusScalesWithZoom() {
        CursorPresenter presenter = new CursorPresenter();
        presenter.setResizeTarget(rect);
        presenter.setZoom(2.0); // Grab-Radius = 6/2 = 3 Welt-Einheiten

        presenter.setMouseWorld(82.9, 85.0); // 2.9 vom NW-Griff entfernt
        assertThat(presenter.resolve()).isEqualTo(CustomCursor.NW_RESIZE);

        presenter.setMouseWorld(83.2, 85.0); // 3.2 — knapp außerhalb
        assertThat(presenter.resolve()).isEqualTo(CustomCursor.DEFAULT);
    }

    @Test
    @DisplayName("Hover im Auswahl-Werkzeug: MOVE über Objekten, HAND über Verbindungen, Objekt gewinnt")
    void selectHoverAffordances() {
        CursorPresenter presenter = new CursorPresenter();

        presenter.setConnectionHovered(true);
        assertThat(presenter.resolve()).isEqualTo(CustomCursor.HAND);

        presenter.setObjectHovered(true);
        assertThat(presenter.resolve())
                .as("Objekt liegt oberhalb der Verbindung (Kern-Hit zuerst)")
                .isEqualTo(CustomCursor.MOVE);
    }

    @Test
    @DisplayName("Erzeugungs-Werkzeuge ignorieren Hover-Affordanzen: Klick erzeugt, nicht zieht")
    void creationToolsIgnoreHover() {
        CursorPresenter presenter = new CursorPresenter();
        presenter.setTool(ToolType.TEXT);
        presenter.setObjectHovered(true);
        presenter.setConnectionHovered(true);
        assertThat(presenter.resolve()).isEqualTo(CustomCursor.CROSSHAIR);
    }

    @Test
    @DisplayName("Übersetzung am Systemrand: CustomCursor → javafx.scene.Cursor")
    void toFxTranslation() {
        assertThat(CursorPresenter.toFx(CustomCursor.DEFAULT)).isEqualTo(Cursor.DEFAULT);
        assertThat(CursorPresenter.toFx(CustomCursor.CROSSHAIR)).isEqualTo(Cursor.CROSSHAIR);
        assertThat(CursorPresenter.toFx(CustomCursor.HAND)).isEqualTo(Cursor.HAND);
        assertThat(CursorPresenter.toFx(CustomCursor.CLOSED_HAND)).isEqualTo(Cursor.CLOSED_HAND);
        assertThat(CursorPresenter.toFx(CustomCursor.MOVE)).isEqualTo(Cursor.MOVE);
        assertThat(CursorPresenter.toFx(CustomCursor.NW_RESIZE)).isEqualTo(Cursor.NW_RESIZE);
        assertThat(CursorPresenter.toFx(CustomCursor.SE_RESIZE)).isEqualTo(Cursor.SE_RESIZE);
    }
}
