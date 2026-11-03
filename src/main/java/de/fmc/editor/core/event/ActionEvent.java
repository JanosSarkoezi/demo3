package de.fmc.editor.core.event;

import de.fmc.editor.core.model.Connection;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.model.TextAnchor;
import de.fmc.editor.core.model.ToolType; // Enum: SELECT, CIRCLE, RECTANGLE, CONNECT, TEXT
import de.fmc.editor.core.model.Waypoint;

import java.util.List;
import java.util.UUID;

public sealed interface ActionEvent extends DomainEvent {

    // --- Kamera & View ---
    record PanView(double deltaX, double deltaY) implements ActionEvent {
    }

    record ZoomView(double zoomFactor, double pivotX, double pivotY) implements ActionEvent {
    }

    /** Neue Rasterweite für das Move-Snapping (Toolbar-Eingabe „Snap to
     *  Grid"). Konsumiert der BUS SELBST wie PanView/ZoomView — die GridSpec
     *  ist ein getrackter Sitzungswert: Jeder Folge-Event rechnet mit dem
     *  neuen Raster (Snap, Resize-Mindestgröße = 1 Zelle). Validierung im
     *  Bus: Werte <= 0, NaN oder unendlich werden still ignoriert, das
     *  Raster bleibt. Bewusst NICHT persistiert (User-Entscheidung 1.11.:
     *  schnell einstellbar) und immer AKTIV (spec: „virtuelles Netz kleben
     *  lassen" — kein Aus-Schalter). */
    record SetGridSize(double gridSize) implements ActionEvent {
    }

    // Anfrage der Tool-Machine an die Camera-Machine, das Panning zu übernehmen.
    // button = die Taste, die die Geste gestartet hat — nur ihr Loslassen
    // beendet das Panning (siehe CameraPanningState).
    record RequestCameraPan(double startX, double startY, CustomMouseButton button) implements ActionEvent {
    }

    // --- Werkzeug-Steuerung (von UI-Toolbar oder Shortcut) ---
    record SelectTool(ToolType tool) implements ActionEvent {
    }

    // --- Auswahl (konsumiert vom SelectionService) ---
    // Deklarativ: "Die Auswahl soll ab jetzt genau diese Menge sein."
    // Leere Liste = Auswahl aufheben. Shift-Klick, Gummiband etc. berechnen
    // die States und emittieren am Ende diese fertige Menge.
    record SetSelection(List<UUID> selectedIds) implements ActionEvent {
    }

    // --- Modell-Modifikationen (für DiagramService / Repository) ---
    // Enthält das fertige Record (Circle, Rectangle etc.) inklusive ID
    record AddElement(DiagramObject object) implements ActionEvent {
    }

    // Zentrales Löschen inkl. Kaskade: IDs können Objekte ODER (seit 03.11.)
    // Verbindungen sein — eine Connection-ID entfernt die Verbindung samt
    // ihrer Waypoints und verankerten Labels, die Endpunkte bleiben. Alle
    // Entfernungen erscheinen als Batch-Fakten (ObjectsRemoved,
    // ConnectionsRemoved) mit vollständigem Zustand — Undo-Grundlage.
    record DeleteObjects(List<UUID> ids) implements ActionEvent {
    }

    /** Undo-Inverse der Lösch-Kaskade: Objekte an ihren originalen
     *  z-Positionen wiederherstellen (objects und zIndices index-parallel). */
    record RestoreObjects(List<DiagramObject> objects, List<Integer> zIndices) implements ActionEvent {
    }

    record MoveObject(UUID id, double deltaX, double deltaY) implements ActionEvent {
    }

    // Absolute Zielposition — das präzise Inverse für Undo (kein Float-Drift).
    record MoveObjectTo(UUID id, double x, double y) implements ActionEvent {
    }

    /** Gruppenverschiebung: dasselbe Delta für alle genannten Objekte
     *  (inkrementell pro Drag-Segment, analog MoveObject). */
    record MoveObjects(List<UUID> ids, double deltaX, double deltaY) implements ActionEvent {
    }

    // Resize-Requests tragen NUR Maße, niemals Positionen: das Zentrum bleibt
    // damit garantiert erhalten (symmetrisches Resize um den Ankerpunkt).
    // Zwei Familien nach Geometrie: Box-Objekte (Rectangle, Text) über
    // Breite/Höhe, der Circle kennt nur einen Radius. ResizeBox auf ein
    // Nicht-Box-Objekt (insb. Circle) ist bewusst ein stiller No-Op —
    // analog ResizeCircle auf ein Nicht-Circle.
    record ResizeBox(UUID id, double newWidth, double newHeight) implements ActionEvent {
    }

    record ResizeCircle(UUID id, double newRadius) implements ActionEvent {
    }

    record CreateConnection(UUID sourceId, UUID targetId, List<Waypoint> waypoints) implements ActionEvent {
    }

    // --- Restore-Anfragen (Inverse der Lösch-/Prune-Kaskade, Original-IDs) ---

    /** Stellt eine Connection mit ihrer ORIGINAL-ID wieder her (Undo des Löschens). */
    record AddConnection(Connection connection) implements ActionEvent {
    }

    /** Entfernt NUR die genannten Connections — ohne Waypoint-Kaskade (präzises Inverse). */
    record RemoveConnections(List<UUID> ids) implements ActionEvent {
    }

    /** Fügt einen Kettenpunkt in eine bestehende Verbindung ein (Doppelklick
     *  auf ein Segment im RouteEditMode; demo2-Semantik). Das W-Record
     *  erzeugt der STATE mit frischer UUID — Präzedenzfall CreateConnection;
     *  der Einfüge-Index wird aus der Kettengeometrie abgeleitet und liegt
     *  deshalb im DiagramService (demo2 trug ihn noch im State-Handler).
     *  Fakten: ObjectAdded (das W) + ConnectionsUpdated (voller Vor-Zustand).
     *  Kehrt am 1.11. zurück: Am 27.10 ohne Konsumenten entfernt (tot),
     *  jetzt mit echtem Anwendungsfall. */
    record AddWaypoint(UUID connectionId, Waypoint waypoint) implements ActionEvent {
    }

    // --- Text: Innere Texte (Felder auf K/R) und Text-Objekte ---

    /** Setzt den inneren Text eines Kreises (per Konvention ein Buchstabe; das Modell bleibt permissiv). */
    record SetCircleLabel(UUID id, String label) implements ActionEvent {
    }

    /** Setzt den inneren mehrzeiligen Text eines Rechtecks inkl. Zentrierungs-Schalter. */
    record SetRectangleContent(UUID id, String content, boolean centered) implements ActionEvent {
    }

    /** Aktualisiert Inhalt und (von der UI gemessene) Box eines Text-Objekts — Position unverändert. */
    record UpdateText(UUID id, String content, double width, double height) implements ActionEvent {
    }

    /** Verankert ein Text-Objekt neu an einem Verbindungsabschnitt — oder löst
     *  den Anker (anchor = null: absolut, Position bleibt, wo sie ist).
     *  Der Service leitet x/y aus der aktuellen Verbindungskette ab. */
    record SetAnchor(UUID id, TextAnchor anchor) implements ActionEvent {
    }

    /** Erzeugt ein Verbindungs-Label (maximal eins je Connection, prüft das
     *  Werkzeug): Der Service leitet x/y aus dem Anker ab. */
    record AddAnchoredText(UUID connectionId, String content, TextAnchor anchor,
                           double width, double height, UUID layerId) implements ActionEvent {
    }

    // --- UI-Intents für den Inline-Editor ---
    // Der DiagramService ignoriert sie still; Konsument ist die UI. Seit
    // 02.11. (Stufe A) abonniert der InlineTextEditorController
    // CreateTextFor, EditTextOfObject (Kreis) und EditText: Er öffnet den
    // Editor an der Klick-Position (behält sie selbst) und publiziert beim
    // Commit die passenden Requests von oben (AddElement, SetCircleLabel,
    // UpdateText). CreateConnectionText (Stufe C) und EditTextOfObject auf
    // ein Rechteck (Stufe B) warten noch auf ihren Zweig. ESC im Editor
    // verbraucht die UI selbst — erreicht den Core nie.

    /** Editor-Intent: Inneren Text dieses K/R bearbeiten. */
    record EditTextOfObject(UUID objectId) implements ActionEvent {
    }

    /** Editor-Intent: Dieses bestehende Text-Objekt bearbeiten. */
    record EditText(UUID textId) implements ActionEvent {
    }

    /** Editor-Intent: Neues absolutes Text-Feld anlegen (ownerId = null: freier Text,
     *  sonst externes Label unterhalb des Owner-Objekts). */
    record CreateTextFor(UUID ownerId) implements ActionEvent {
    }

    /** Editor-Intent: Neues Verbindungs-Label — der Anker wurde vom Werkzeug projiziert. */
    record CreateConnectionText(UUID connectionId, TextAnchor anchor) implements ActionEvent {
    }

    // --- Undo/Redo (UI übersetzt Shortcuts wie CTRL+Z / SHIFT+CTRL+Z hierher) ---
    record RequestUndo() implements ActionEvent {
    }

    record RequestRedo() implements ActionEvent {
    }

    // --- Persistenz: Laden (Restore-Pfad, kein Erzeugen) ---

    /** Ersetzt das GESAMTE Diagramm durch den geladenen Bestand. objects
     *  liegt in z-Ordnung vor (Index 0 = unten; Texte oben auf — sie leben
     *  im eigenen texts-Abschnitt ohne z-Position, demo2-Semantik),
     *  connections mit Original-IDs.
     *
     *  Bewusst OHNE Validierung: ConnectionRules prüfen NEUE Verbindungen —
     *  eine ältere Datei mit heute verbotener Verbindung muss trotzdem
     *  laden (Restore, demo2 umging die Validierung ebenso). Der
     *  DiagramService bestätigt mit EINEM DiagramReset-Fakt; UndoService
     *  leert darauf die History, SelectionService die Auswahl — per
     *  Subscriptions, ohne dass der Loader sie anrufen müsste. */
    record LoadDiagram(List<DiagramObject> objects, List<Connection> connections) implements ActionEvent {
    }

    // --- State→UI-Hinweise (Sitzungszustand, KEINE Modell-Fakten: nicht
    // undo-fähig, vom UndoService bewusst ignoriert — wie Kamera/Selektion).
    // Die Richtung ist die gleiche wie bei den Editor-Intents oben: Der Kern
    // informiert die UI über Zustand, den sie selbst nicht besitzen kann.

    /** Hinweis: Das aktive Werkzeug hat sich geändert (Toolbar-Sync — auch
     *  bei Shortcut-Wechseln K/R/C/T, die die UI nicht selbst verursacht).
     *  Publiziert vom Bus nach jedem State-Übergang mit neuem Tool-Typ. */
    record ToolChanged(ToolType tool) implements ActionEvent {
    }

    /** Hinweis: Der Verbindungsaufbau läuft / seine Kette hat sich geändert.
     *  sourceId = Start-Objekt, waypoints = bisherige Kettenpunkte (Kopie).
     *  sourceId == null heißt: Keine Vorschau (Geste beendet oder abgebrochen).
     *  Der freie Abschnitt vom letzten Punkt zur Maus zeichnet die UI selbst
     *  aus ihrer eigenen Mausposition — dafür braucht es kein Event. */
    record ConnectionPreviewChanged(UUID sourceId, List<Waypoint> waypoints) implements ActionEvent {
    }

    /** Hinweis: Resize-Modus für genau dieses Objekt betreten (Doppelklick;
     *  nur K/R — die UI zeigt die Griffe). target == null: Modus verlassen
     *  (Klick daneben, ESC, Toolbar-Wechsel). Griff-Positionen liefert die UI
     *  aus HandlePositions — der Griff-Drag läuft über den Kern. */
    record ResizeModeChanged(DiagramObject target) implements ActionEvent {
    }

    /** Hinweis: Live-Vorschau einer laufenden Resize-Geste — die Geometrie,
     *  die der KERN für den aktuellen Drag-Zustand berechnet (symmetrisch ums
     *  Zentrum, Grid-Klemmung; dieselbe Mathematik wie der spätere Commit).
     *  preview == null: Vorschau-Ende (Commit oder Abbruch) — die View stellt
     *  die Geometrie dann aus dem Modell her (beim Abbruch kommt KEIN Fakt).
     *  Sitzungszustand, KEIN Modell-Fakt, kein Undo-Punkt. Der Commit bleibt
     *  genau EIN ResizeBox/ResizeCircle beim Loslassen. */
    record ResizePreviewChanged(DiagramObject preview) implements ActionEvent {
    }

    /** Hinweis: RouteEditMode für genau diese Verbindung betreten (Klick auf
     *  die Verbindungskette im Auswahl-Werkzeug; die UI blendet die W-Kreise
     *  ein und macht sie damit greifbar). connectionId == null: Modus
     *  verlassen (Klick daneben/auf ein Objekt, ESC, Toolbar-Wechsel) — die
     *  W-Kreise verschwinden. Sitzungszustand, KEIN Modell-Fakt; die
     *  Klick-Semantik der Ws trägt der RouteEditModeState selbst — der
     *  Zustand IST das Flag, kein gemeinsamer Sitzungsspeicher nötig. */
    record WaypointFocusChanged(UUID connectionId) implements ActionEvent {
    }

    /** Hinweis: Live-Vorschau einer laufenden Gummiband-Geste — das
     *  Bildschirm-Rechteck (Start ≠ Ende), das die UI lokal zeichnet.
     *  startX == null: Band-Ende (Commit oder Abbruch). Bildschirm-
     *  Koordinaten bewusst: Das Band ist reine Präsentation der Geste,
     *  die Treffer-Rechnung läuft separat beim Loslassen über die Kamera
     *  in Welt-Koordinaten. Sitzungszustand, KEIN Modell-Fakt. */
    record BoxSelectPreviewChanged(Double startX, Double startY,
                                   Double currentX, Double currentY) implements ActionEvent {
    }
}
