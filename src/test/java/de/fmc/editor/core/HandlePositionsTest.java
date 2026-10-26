package de.fmc.editor.core;

import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.HandlePositions;
import de.fmc.editor.core.model.HandlePositions.HandlePoint;
import de.fmc.editor.core.model.HandleType;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.Waypoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class HandlePositionsTest {

    private final UUID layerId = UUID.randomUUID();

    @Test
    @DisplayName("Rechteck hat 8 Griffe: 4 Ecken + 4 Seiten-Mitten")
    void rectangleShouldHaveEightHandles() {
        Rectangle rect = new Rectangle(UUID.randomUUID(), 100.0, 100.0, 40.0, 30.0, layerId);

        Map<HandleType, HandlePoint> handles = HandlePositions.of(rect);

        assertThat(handles).hasSize(8);
        assertThat(handles.get(HandleType.NW)).isEqualTo(new HandlePoint(80.0, 85.0));
        assertThat(handles.get(HandleType.N)).isEqualTo(new HandlePoint(100.0, 85.0));
        assertThat(handles.get(HandleType.NE)).isEqualTo(new HandlePoint(120.0, 85.0));
        assertThat(handles.get(HandleType.E)).isEqualTo(new HandlePoint(120.0, 100.0));
        assertThat(handles.get(HandleType.SE)).isEqualTo(new HandlePoint(120.0, 115.0));
        assertThat(handles.get(HandleType.S)).isEqualTo(new HandlePoint(100.0, 115.0));
        assertThat(handles.get(HandleType.SW)).isEqualTo(new HandlePoint(80.0, 115.0));
        assertThat(handles.get(HandleType.W)).isEqualTo(new HandlePoint(80.0, 100.0));
    }

    @Test
    @DisplayName("Kreis hat 4 Griffe auf dem Umfang (N/O/S/W)")
    void circleShouldHaveFourHandlesOnCircumference() {
        Circle circle = new Circle(UUID.randomUUID(), 100.0, 100.0, 20.0, layerId);

        Map<HandleType, HandlePoint> handles = HandlePositions.of(circle);

        assertThat(handles).hasSize(4);
        assertThat(handles.get(HandleType.N)).isEqualTo(new HandlePoint(100.0, 80.0));
        assertThat(handles.get(HandleType.E)).isEqualTo(new HandlePoint(120.0, 100.0));
        assertThat(handles.get(HandleType.S)).isEqualTo(new HandlePoint(100.0, 120.0));
        assertThat(handles.get(HandleType.W)).isEqualTo(new HandlePoint(80.0, 100.0));
    }

    @Test
    @DisplayName("Waypoints haben keine Griffe")
    void waypointShouldHaveNoHandles() {
        Waypoint waypoint = new Waypoint(UUID.randomUUID(), 10.0, 10.0, layerId);

        assertThat(HandlePositions.of(waypoint)).isEmpty();
    }
}
