package de.fmc.editor.view;

import de.fmc.editor.core.event.CustomCursor;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.model.HandlePositions;
import de.fmc.editor.core.model.HandleType;
import de.fmc.editor.core.model.ToolType;

import java.util.Map;
import java.util.Optional;

/**
 * Cursor-Auflösung als reine Funktion des Interaktions-Zustands (View-
 * Schicht, headless testbar — dasselbe Muster wie ConnectionViewMapper).
 * Die Prioritäten von oben nach unten:
 *
 * <ol>
 *   <li><b>Kern-Geste</b> ({@code CursorChanged}-Hinweis): Panning und
 *       Verschieben greifen mit CLOSED_HAND zu — hat Vorrang vor allem
 *       Darstellenden.</li>
 *   <li><b>Laufende Band-/Verbindungs-Geste</b> (aus den Preview-Hinweisen
 *       abgeleitet): Fadenkreuz — Hover-Affordanzen würden während der
 *       Geste lügen.</li>
 *   <li><b>Resize-Modus</b>: richtungsabhängiger Resize-Cursor über den
 *       Griffen (derselbe Grab-Radius wie der Kern-Hit-Test — der Cursor
 *       zeigt exakt die Fläche, die der Kern greift), sonst schlicht.</li>
 *   <li><b>Hover-Affordanzen</b> — NUR im Auswahl-Werkzeug: MOVE über
 *       Objekten (Press startet eine Verschiebe-Geste), HAND über
 *       Verbindungsketten. In Erzeugungs-Werkzeugen wäre MOVE gelogen:
 *       Dort verbindet/erzeugt der Klick.</li>
 *   <li><b>Werkzeug-Grundcursor</b>: SELECT = Default-Pfeil, alle
 *       Erzeugungs-Werkzeuge = Fadenkreuz.</li>
 * </ol>
 *
 * Während eines Griff-Drags feuert JavaFX keine MOUSE_MOVED-Events mehr —
 * der beim Pressen gesetzte Resize-Cursor klebt deshalb automatisch für
 * die ganze Geste (kein eigener Zustand nötig); beim Loslassen rechnet
 * die View über den nächsten Move neu.
 */
public final class CursorPresenter {

    /** Griff-Radius in Bildschirm-Pixeln — derselbe Wert wie der Kern-Hit-
     *  Test (ResizeModeState.HANDLE_GRAB_RADIUS_PIXELS): Der Cursor zeigt
     *  exakt die Fläche an, die der Kern greift. */
    private static final double HANDLE_GRAB_RADIUS_PIXELS = 6.0;

    private ToolType tool = ToolType.SELECT;
    /** GESTEN-Override aus dem Kern (CursorChanged); null = keiner. */
    private CustomCursor gestureCursor;
    /** Verbindungsaufbau läuft (ConnectionPreviewChanged mit Quelle). */
    private boolean connecting;
    /** Gummiband läuft (BoxSelectPreviewChanged mit Start-Rechteck). */
    private boolean bandActive;
    /** Resize-Modus-Ziel — die GraphView reicht es FRISCH aus dem Modell
     *  hinein (nach jedem Commit ist das geschnappte Record veraltet,
     *  siehe „Griffe nicht mehr greifbar"). */
    private DiagramObject resizeTarget;
    private boolean objectHovered;
    private boolean connectionHovered;
    private double mouseWorldX;
    private double mouseWorldY;
    private double zoom = 1.0;

    public void setTool(ToolType tool) {
        this.tool = tool;
    }

    public void setGestureCursor(CustomCursor cursor) {
        this.gestureCursor = cursor;
    }

    public void setConnecting(boolean active) {
        this.connecting = active;
    }

    public void setBandActive(boolean active) {
        this.bandActive = active;
    }

    public void setResizeTarget(DiagramObject target) {
        this.resizeTarget = target;
    }

    public void setObjectHovered(boolean hovered) {
        this.objectHovered = hovered;
    }

    public void setConnectionHovered(boolean hovered) {
        this.connectionHovered = hovered;
    }

    public void setMouseWorld(double worldX, double worldY) {
        this.mouseWorldX = worldX;
        this.mouseWorldY = worldY;
    }

    public void setZoom(double zoom) {
        this.zoom = zoom;
    }

    /** Der aktuell wirksame Cursor — reine Funktion des gesetzten Zustands. */
    public CustomCursor resolve() {
        if (gestureCursor != null) {
            return gestureCursor;
        }
        if (connecting || bandActive) {
            return CustomCursor.CROSSHAIR;
        }
        if (resizeTarget != null) {
            return handleAt(mouseWorldX, mouseWorldY)
                    .map(CursorPresenter::cursorFor)
                    .orElse(CustomCursor.DEFAULT);
        }
        if (tool == ToolType.SELECT) {
            if (objectHovered) {
                return CustomCursor.MOVE;
            }
            if (connectionHovered) {
                return CustomCursor.HAND;
            }
        }
        return baseCursor(tool);
    }

    /** Werkzeug-Grundcursor: Auswahl = Pfeil, Erzeugungs-Werkzeuge =
     *  Fadenkreuz (Position wählen). */
    public static CustomCursor baseCursor(ToolType tool) {
        return tool == ToolType.SELECT ? CustomCursor.DEFAULT : CustomCursor.CROSSHAIR;
    }

    /** Richtungsabhängiger Cursor je Griff (Kreis nutzt nur N/E/S/W). */
    public static CustomCursor cursorFor(HandleType handle) {
        return switch (handle) {
            case NW -> CustomCursor.NW_RESIZE;
            case N -> CustomCursor.N_RESIZE;
            case NE -> CustomCursor.NE_RESIZE;
            case E -> CustomCursor.E_RESIZE;
            case SE -> CustomCursor.SE_RESIZE;
            case S -> CustomCursor.S_RESIZE;
            case SW -> CustomCursor.SW_RESIZE;
            case W -> CustomCursor.W_RESIZE;
        };
    }

    /** Übersetzung am Systemrand — der einzige Ort, an dem JavaFX-Cursor
     *  erwähnt wird (der Kern kennt nur CustomCursor, Goldene Regel 4). */
    public static javafx.scene.Cursor toFx(CustomCursor cursor) {
        return switch (cursor) {
            case DEFAULT -> javafx.scene.Cursor.DEFAULT;
            case CROSSHAIR -> javafx.scene.Cursor.CROSSHAIR;
            case HAND -> javafx.scene.Cursor.HAND;
            case CLOSED_HAND -> javafx.scene.Cursor.CLOSED_HAND;
            case MOVE -> javafx.scene.Cursor.MOVE;
            case NW_RESIZE -> javafx.scene.Cursor.NW_RESIZE;
            case N_RESIZE -> javafx.scene.Cursor.N_RESIZE;
            case NE_RESIZE -> javafx.scene.Cursor.NE_RESIZE;
            case E_RESIZE -> javafx.scene.Cursor.E_RESIZE;
            case SE_RESIZE -> javafx.scene.Cursor.SE_RESIZE;
            case S_RESIZE -> javafx.scene.Cursor.S_RESIZE;
            case SW_RESIZE -> javafx.scene.Cursor.SW_RESIZE;
            case W_RESIZE -> javafx.scene.Cursor.W_RESIZE;
        };
    }

    /** Hit-Test: Welcher Griff (falls einer) liegt in Griff-Nähe der
     *  Welt-Position? Dieselbe Geometrie-Disziplin wie im Kern. */
    private Optional<HandleType> handleAt(double worldX, double worldY) {
        double grabRadiusWorld = HANDLE_GRAB_RADIUS_PIXELS / zoom;
        return HandlePositions.of(resizeTarget).entrySet().stream()
                .filter(entry -> Math.hypot(
                        entry.getValue().x() - worldX,
                        entry.getValue().y() - worldY) <= grabRadiusWorld)
                .map(Map.Entry::getKey)
                .findFirst();
    }
}
