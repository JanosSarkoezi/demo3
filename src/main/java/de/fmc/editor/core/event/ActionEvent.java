package de.fmc.editor.core.event;

import de.fmc.editor.core.model.Connection;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.model.ToolType; // Enum: SELECT, CIRCLE, RECTANGLE, CONNECT
import de.fmc.editor.core.model.Waypoint;

import java.util.List;
import java.util.UUID;

public sealed interface ActionEvent extends DomainEvent {

    // --- Kamera & View ---
    record PanView(double deltaX, double deltaY) implements ActionEvent {
    }

    record ZoomView(double zoomFactor, double pivotX, double pivotY) implements ActionEvent {
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

    // Zentrales Löschen inkl. Kaskade (Connections und deren Waypoints);
    // alle Entfernungen erscheinen als Batch-Fakten (ObjectsRemoved,
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

    // Resize-Requests tragen NUR Maße, niemals Positionen: das Zentrum bleibt
    // damit garantiert erhalten (symmetrisches Resize um den Ankerpunkt).
    record ResizeObject(UUID id, double newWidth, double newHeight) implements ActionEvent {
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

    // --- Undo/Redo (UI übersetzt Shortcuts wie CTRL+Z / SHIFT+CTRL+Z hierher) ---
    record RequestUndo() implements ActionEvent {
    }

    record RequestRedo() implements ActionEvent {
    }
}
