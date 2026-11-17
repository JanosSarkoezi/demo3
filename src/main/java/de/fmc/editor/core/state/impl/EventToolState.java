package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Event;
import de.fmc.editor.core.model.EventGeometry;
import de.fmc.editor.core.model.EventHandle;
import de.fmc.editor.core.model.EventSide;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.ToolType;
import de.fmc.editor.core.state.InteractionContext;

import java.util.Optional;

/**
 * Event-Werkzeug (Shortcut E): Erstellt und bearbeitet Event-Symbole an
 * Rechtecken (FMC: Ereignis, das am R verarbeitet wird).
 *
 * Gesten:
 * - Press auf einen FORMGRIFF eines bestehenden Events (Start/Knick — im
 *   Event-Werkzeug sichtbar): achs-gebundener Drag über den
 *   {@link EventPathDragState} (Start horizontal, Knick vertikal).
 * - Klick auf ein bestehendes Event: Label bearbeiten (Inline-Editor).
 * - Klick auf ein R: neues Event — die Klickhälfte bestimmt die Seite
 *   (links → Pfeil von links, rechts → von rechts; Standard rechts,
 *   User-Entscheidung 17.11.).
 * - Klick daneben / auf K/Text/W: stiller No-Op (Events brauchen ein R).
 *
 * Guard-Disjunktheit: GENAU EINE MousePressed-Regel — alle Verzweigungen
 * liegen in der Aktion; die KeyPressed-Regeln (ESC, Shortcuts) treffen auf
 * disjunkte Keys.
 *
 * @author saj
 */
public final class EventToolState extends ToolState {

    /** Griff-Greif-Radius in Bildschirm-Pixeln — derselbe Wert wie der
     *  Resize-Griff-Hit-Test: Der Griff-Marker der View zeigt exakt die
     *  Fläche, die hier greift. */
    private static final double HANDLE_GRAB_RADIUS_PIXELS = 6.0;

    public EventToolState() {
        super(ToolType.EVENT);

        // 1. Linksklick: Formgriff greifen, Label bearbeiten oder Event
        //    anlegen — eine Regel, Verzweigungen in der Aktion (Reihenfolge
        //    trägt keine Bedeutung).
        addRule(InputEvent.MousePressed.class,
                e -> e.button() == CustomMouseButton.PRIMARY && e.clickCount() == 1,
                (e, c) -> {
                    double worldX = c.camera().screenToWorldX(e.x());
                    double worldY = c.camera().screenToWorldY(e.y());

                    // a) Formgriff eines bestehenden Events (oberstes zuerst):
                    //    Der feinere Griff schlägt Objekt- und R-Treffer.
                    Optional<GripHit> grip = handleHitAt(c, worldX, worldY);
                    if (grip.isPresent()) {
                        return new EventPathDragState(grip.get().event(), grip.get().handle());
                    }

                    var target = c.diagram().findTopmostAt(worldX, worldY);
                    if (target.isPresent()) {
                        // b) Klick auf ein Event -> Label bearbeiten
                        if (target.get() instanceof Event clickedEvent) {
                            c.publisher().publish(
                                    new ActionEvent.EditEventLabel(clickedEvent.id()));
                            return this;
                        }
                        // c) Klick auf ein R -> neues Event anlegen; die
                        //    Klickhälfte bestimmt die Seite. Der Intent trägt
                        //    den Label-Anker (EventGeometry = die eine
                        //    Rechen-Wahrheit), der Editor öffnet dort. Das
                        //    Modell entsteht erst beim Commit (CreateEvent) —
                        //    leerer Commit verwirft still (User-Entscheidung).
                        if (target.get() instanceof Rectangle owner) {
                            EventSide side = worldX < owner.x() ? EventSide.LEFT : EventSide.RIGHT;
                            double[] anchor = EventGeometry.labelAnchor(owner, side,
                                    EventGeometry.DEFAULT_EXTENT, EventGeometry.DEFAULT_RISE);
                            c.publisher().publish(new ActionEvent.CreateEventFor(
                                    owner.id(), side, anchor[0], anchor[1]));
                            return this;
                        }
                        // K/Text/W: stiller No-Op
                        return this;
                    }

                    // d) Leere Fläche: stiller No-Op — Events brauchen ein R.
                    return this;
                });

        // 2. ESC -> Werkzeug verlassen und zurück zum Auswahl-Werkzeug
        addRule(InputEvent.KeyPressed.class,
                e -> e.code() == CustomKeyCode.ESCAPE,
                (e, c) -> new IdleToolState());

        // 3. Werkzeug-Wechsel (Toolbar + Shortcuts) — zentral in ToolState
        registerToolSwitching();
    }

    /**
     * Formgriff unter der Welt-Position — oberstes Event zuerst. Greif-
     * Radius in Bildschirm-Pixeln, per Zoom in Welt-Einheiten gewandelt
     * (derselbe Wert wie beim Resize-Griff-Hit). Die Griffpositionen sind
     * abgeleitete Punkte aus EventGeometry (Start = äußeres Pfadende,
     * Knick = senkrecht über der Spitze) — reines Lesewissen über die
     * Query, kein Schreiben.
     */
    private Optional<GripHit> handleHitAt(InteractionContext c,
                                          double worldX, double worldY) {
        double grabRadiusWorld = HANDLE_GRAB_RADIUS_PIXELS / c.camera().zoom();
        var all = c.diagram().allObjectsInZOrder();
        for (int i = all.size() - 1; i >= 0; i--) {
            if (!(all.get(i) instanceof Event event)) {
                continue;
            }
            if (!(c.diagram().findById(event.ownerId()).orElse(null) instanceof Rectangle owner)) {
                continue; // Kaskaden-Fenster: ohne Owner keine Geometrie
            }
            double[] start = EventGeometry.end(owner, event.side(),
                    event.horizontalExtent(), event.verticalRise());
            if (Math.hypot(worldX - start[0], worldY - start[1]) <= grabRadiusWorld) {
                return Optional.of(new GripHit(event, EventHandle.START));
            }
            double[] kink = EventGeometry.kink(owner, event.side(), event.verticalRise());
            if (Math.hypot(worldX - kink[0], worldY - kink[1]) <= grabRadiusWorld) {
                return Optional.of(new GripHit(event, EventHandle.KINK));
            }
        }
        return Optional.empty();
    }

    /** Treffer eines Formgriffs: Event-Record zum Press-Zeitpunkt (frisch
     *  gelesen) plus Griff-Kante. */
    private record GripHit(Event event, EventHandle handle) {
    }
}
