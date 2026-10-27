package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.ToolType;
import de.fmc.editor.core.state.InteractionState;
import de.fmc.editor.core.state.rule.BaseState;

import java.util.UUID;

public final class PendingCreateOrPanState extends BaseState {

    private static final double DRAG_THRESHOLD_PIXELS = 4.0;

    private final double startScreenX;
    private final double startScreenY;
    private final ToolType activeTool;

    public PendingCreateOrPanState(double startScreenX, double startScreenY, ToolType activeTool) {
        this.startScreenX = startScreenX;
        this.startScreenY = startScreenY;
        this.activeTool = activeTool;

        // 1. Dragging über Schwellenwert -> Panning anfordern, Kamera übernimmt
        addRule(InputEvent.MouseDragged.class,
                (e, c) -> distance(startScreenX, startScreenY, e.x(), e.y()) > DRAG_THRESHOLD_PIXELS,
                (e, c) -> {
                    // PRIMARY: Diese Geste startete mit einem Linksklick —
                    // nur dessen Loslassen beendet das Panning.
                    c.publisher().publish(new ActionEvent.RequestCameraPan(startScreenX, startScreenY, CustomMouseButton.PRIMARY));
                    return returnToToolState(); // Tool pausiert und geht zurück in Grundzustand
                });

        // 2. Loslassen ohne Drag -> Element an Welt-Koordinaten erzeugen
        addRule(InputEvent.MouseReleased.class,
                e -> e.button() == CustomMouseButton.PRIMARY,
                (e, c) -> {
                    // Bildschirm-Pixel in Welt-Koordinaten umrechnen
                    double worldX = c.camera().screenToWorldX(startScreenX);
                    double worldY = c.camera().screenToWorldY(startScreenY);

                    // Je nach aktivem Werkzeug Objekt erzeugen
                    if (activeTool == ToolType.CIRCLE) {
                        var circle = new Circle(UUID.randomUUID(), worldX, worldY, 20.0, c.camera().defaultLayerId());
                        c.publisher().publish(new ActionEvent.AddElement(circle));
                    } else if (activeTool == ToolType.RECTANGLE) {
                        var rect = new Rectangle(UUID.randomUUID(), worldX, worldY, 40.0, 30.0, c.camera().defaultLayerId());
                        c.publisher().publish(new ActionEvent.AddElement(rect));
                    }

                    return returnToToolState();
                });
    }

    private InteractionState returnToToolState() {
        return switch (activeTool) {
            case CIRCLE -> new CircleToolState();
            case RECTANGLE -> new RectangleToolState();
            default -> new IdleToolState();
        };
    }

    private double distance(double x1, double y1, double x2, double y2) {
        return Math.hypot(x2 - x1, y2 - y1);
    }
}