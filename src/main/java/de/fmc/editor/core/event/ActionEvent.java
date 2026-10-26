package de.fmc.editor.core.event;

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

    // Anfrage der Tool-Machine an die Camera-Machine, das Panning zu übernehmen
    record RequestCameraPan(double startX, double startY) implements ActionEvent {
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

    record MoveObject(UUID id, double deltaX, double deltaY) implements ActionEvent {
    }

    // Resize-Requests tragen NUR Maße, niemals Positionen: das Zentrum bleibt
    // damit garantiert erhalten (symmetrisches Resize um den Ankerpunkt).
    record ResizeObject(UUID id, double newWidth, double newHeight) implements ActionEvent {
    }

    record ResizeCircle(UUID id, double newRadius) implements ActionEvent {
    }

    record CreateConnection(UUID sourceId, UUID targetId, List<Waypoint> waypoints) implements ActionEvent {
    }

    record AddWaypoint(UUID connectionId, double x, double y) implements ActionEvent {
    }
}
