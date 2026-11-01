package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.ToolType;
import de.fmc.editor.core.model.Waypoint;
import de.fmc.editor.core.state.InteractionState;
import de.fmc.editor.core.state.rule.BaseState;

import java.util.UUID;

public final class PendingCreateOrPanState extends BaseState {

    private final double startScreenX;
    private final double startScreenY;
    private final ToolType activeTool;
    private final CustomMouseButton button;

    /** button: die Taste, mit der die Geste startete — nur deren Loslassen
     *  beendet das angeforderte Panning (kein hardcodierter PRIMARY-Glaube). */
    public PendingCreateOrPanState(double startScreenX, double startScreenY, ToolType activeTool,
                                   CustomMouseButton button) {
        this.startScreenX = startScreenX;
        this.startScreenY = startScreenY;
        this.activeTool = activeTool;
        this.button = button;

        // 1. Dragging über Schwellenwert -> Panning anfordern, Kamera übernimmt
        addRule(InputEvent.MouseDragged.class,
                (e, c) -> distance(startScreenX, startScreenY, e.x(), e.y()) > DRAG_THRESHOLD_PIXELS,
                (e, c) -> {
                    c.publisher().publish(new ActionEvent.RequestCameraPan(startScreenX, startScreenY, button));
                    return returnToToolState(); // Tool pausiert und geht zurück in Grundzustand
                });

        // 2. Loslassen ohne Drag -> Element an Welt-Koordinaten erzeugen —
        //    aber NUR auf freier Fläche: Ein Klick auf ein bestehendes
        //    Objekt ist ein stiller No-Op. Ohne diesen Hit-Test entstünden
        //    deckungsgleiche Stapel, die praktisch unselektierbar sind
        //    (findTopmostAt entscheidet nach z-Order — Auswahl-Lotterie).
        //    Waypoints sind KEINE Blocker: Außerhalb des RouteEditMode sind
        //    sie unsichtbar (Sitzungszustand der UI) und dürfen Klicks nicht
        //    verschlucken — dasselbe Filter-Muster wie im ConnectingState.
        addRule(InputEvent.MouseReleased.class,
                e -> e.button() == button,
                (e, c) -> {
                    // Bildschirm-Pixel in Welt-Koordinaten umrechnen
                    // (Press-Position: Dorthin zielte der Klick).
                    double worldX = c.camera().screenToWorldX(startScreenX);
                    double worldY = c.camera().screenToWorldY(startScreenY);

                    var hit = c.diagram().findTopmostAt(worldX, worldY);
                    boolean blockedByObject = hit.isPresent()
                            && !(hit.get() instanceof Waypoint);

                    if (!blockedByObject) {
                        // Je nach aktivem Werkzeug Objekt erzeugen
                        if (activeTool == ToolType.CIRCLE) {
                            var circle = new Circle(UUID.randomUUID(), worldX, worldY, 20.0, c.camera().defaultLayerId());
                            c.publisher().publish(new ActionEvent.AddElement(circle));
                        } else if (activeTool == ToolType.RECTANGLE) {
                            var rect = new Rectangle(UUID.randomUUID(), worldX, worldY, 40.0, 30.0, c.camera().defaultLayerId());
                            c.publisher().publish(new ActionEvent.AddElement(rect));
                        }
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