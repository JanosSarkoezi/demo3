package de.fmc.editor.core.event;

/**
 * Werkzeugneutraler Cursor-Begriff des Kerns (JavaFX-frei, analog zu
 * {@link CustomKeyCode}/{@link CustomMouseButton}): Der Kern publiziert
 * GESTEN-Cursor als State→UI-Hinweis ({@code ActionEvent.CursorChanged}),
 * die View übersetzt am Systemrand in {@code javafx.scene.Cursor}
 * ({@code CursorPresenter.toFx}) und besitzt zusätzlich die reinen
 * Darstellungs-Zuordnungen (Werkzeug-Grundcursor, Hover-Affordanzen,
 * richtungsabhängige Griff-Cursor) — dafür kennt sie diesen Enum als
 * gemeinsames Vokabular.
 */
public enum CustomCursor {
    DEFAULT, CROSSHAIR, HAND, CLOSED_HAND, MOVE,
    NW_RESIZE, N_RESIZE, NE_RESIZE, E_RESIZE,
    SE_RESIZE, S_RESIZE, SW_RESIZE, W_RESIZE
}
