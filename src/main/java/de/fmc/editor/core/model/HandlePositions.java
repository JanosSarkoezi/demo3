package de.fmc.editor.core.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Reine Griff-Geometrie für Resize-Interaktionen (Welt-Koordinaten).
 * Rechteck und Text-Box: 8 Griffe (4 Ecken + 4 Seiten-Mitten). Kreis: 4
 * Griffe (N/O/S/W auf dem Umfang). Waypoint: keine.
 *
 * Zustandslos und deterministisch — von States (Hit-Test) und der
 * späteren UI (Rendering) gleichermaßen nutzbar.
 */
public final class HandlePositions {

    /** Position eines Griffs in Welt-Koordinaten. */
    public record HandlePoint(double x, double y) {}

    private HandlePositions() {
    }

    public static Map<HandleType, HandlePoint> of(DiagramObject object) {
        return switch (object) {
            case Rectangle rectangle -> ofBox(rectangle.x(), rectangle.y(),
                    rectangle.width(), rectangle.height());
            case Text text -> ofBox(text.x(), text.y(), text.width(), text.height());
            case Circle circle -> ofCircle(circle);
            case Waypoint waypoint -> Map.of();
        };
    }

    private static Map<HandleType, HandlePoint> ofBox(double cx, double cy,
                                                      double width, double height) {
        double halfWidth = width / 2;
        double halfHeight = height / 2;

        Map<HandleType, HandlePoint> handles = new EnumMap<>(HandleType.class);
        handles.put(HandleType.NW, new HandlePoint(cx - halfWidth, cy - halfHeight));
        handles.put(HandleType.N, new HandlePoint(cx, cy - halfHeight));
        handles.put(HandleType.NE, new HandlePoint(cx + halfWidth, cy - halfHeight));
        handles.put(HandleType.E, new HandlePoint(cx + halfWidth, cy));
        handles.put(HandleType.SE, new HandlePoint(cx + halfWidth, cy + halfHeight));
        handles.put(HandleType.S, new HandlePoint(cx, cy + halfHeight));
        handles.put(HandleType.SW, new HandlePoint(cx - halfWidth, cy + halfHeight));
        handles.put(HandleType.W, new HandlePoint(cx - halfWidth, cy));
        return Collections.unmodifiableMap(handles);
    }

    private static Map<HandleType, HandlePoint> ofCircle(Circle circle) {
        double cx = circle.x();
        double cy = circle.y();
        double radius = circle.radius();

        Map<HandleType, HandlePoint> handles = new EnumMap<>(HandleType.class);
        handles.put(HandleType.N, new HandlePoint(cx, cy - radius));
        handles.put(HandleType.E, new HandlePoint(cx + radius, cy));
        handles.put(HandleType.S, new HandlePoint(cx, cy + radius));
        handles.put(HandleType.W, new HandlePoint(cx - radius, cy));
        return Collections.unmodifiableMap(handles);
    }
}
