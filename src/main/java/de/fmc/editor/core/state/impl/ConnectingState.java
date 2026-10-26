package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.model.Waypoint;
import de.fmc.editor.core.state.rule.BaseState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ConnectingState extends BaseState {

    private final DiagramObject sourceObject;
    private final List<Waypoint> waypoints;

    public ConnectingState(DiagramObject sourceObject) {
        this(sourceObject, new ArrayList<>());
    }

    public ConnectingState(DiagramObject sourceObject, List<Waypoint> waypoints) {
        this.sourceObject = sourceObject;
        this.waypoints = new ArrayList<>(waypoints);

        // Klick-Verarbeitung
        addRule(InputEvent.MousePressed.class,
                e -> e.button() == CustomMouseButton.PRIMARY,
                (e, c) -> {
                    double worldX = c.camera().screenToWorldX(e.x());
                    double worldY = c.camera().screenToWorldY(e.y());

                    var hitObject = c.diagram().findTopmostAt(worldX, worldY);

                    if (hitObject.isPresent()) {
                        DiagramObject target = hitObject.get();

                        // Klick auf ein anderes Objekt -> Verbindung abschließen!
                        if (!target.id().equals(sourceObject.id())) {
                            c.publisher().publish(new ActionEvent.CreateConnection(
                                    sourceObject.id(),
                                    target.id(),
                                    List.copyOf(this.waypoints) // Inmutable Kopie der Waypoints
                            ));
                            return new ConnectToolState(); // Fertig, bereit für nächste Verbindung
                        }

                        // Klick auf Quell-Objekt selbst wird ignoriert
                        return this;
                    } else {
                        // Klick ins Leere -> Neuen Waypoint erzeugen & zur Liste hinzufügen
                        var newWaypoint = new Waypoint(
                                UUID.randomUUID(),
                                worldX,
                                worldY,
                                c.camera().defaultLayerId()
                        );
                        this.waypoints.add(newWaypoint);

                        // Im ConnectingState bleiben für weitere Waypoints oder den Ziel-Klick
                        return this;
                    }
                }
        );

        // ESC -> Vorgang abbrechen und Waypoints verwerfen
        addRule(InputEvent.KeyPressed.class,
                e -> e.code() == CustomKeyCode.ESCAPE,
                (e, c) -> new ConnectToolState()
        );
    }

    public List<Waypoint> getWaypoints() {
        return List.copyOf(waypoints);
    }
}