package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.model.Event;
import de.fmc.editor.core.model.EventDirection;
import de.fmc.editor.core.model.EventGeometry;
import de.fmc.editor.core.model.EventHandle;
import de.fmc.editor.core.model.EventSide;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.Text;
import de.fmc.editor.core.model.ToolType;
import de.fmc.editor.core.state.InteractionContext;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Event-Werkzeug (Shortcut E): Erstellt und bearbeitet Event-Symbole an
 * Rechtecken (FMC: Ereignis, das am R verarbeitet wird). Kardinalität 0..1
 * JE RICHTUNG pro R (26.11.) — das R liest sich als VIER-QUADRANTEN-
 * Platzierungspad (Quadranten-Modell, 26.11., ablösend des Shift-
 * Modifiers): Die VERTIKALE Klickhälfte wählt die RICHTUNG (oben =
 * hereinkommend — die Spitze dockt ja oben an; unten = rausgehend — der
 * Fuß startet unten), die HORIZONTALE wählt die Seite:
 *
 * Gesten:
 * - Press auf einen FORMGRIFF eines bestehenden Events (Start/Knick — im
 *   Event-Werkzeug sichtbar): achs-gebundener Drag über den
 *   {@link EventPathDragState} (Start horizontal, Knick vertikal).
 * - Klick auf ein bestehendes Event (Pfad) ODER auf seine Label-Textbox:
 *   Label-INHALT bearbeiten (EditEventLabel — mehrzeiliger Editor),
 *   unabhängig von der Richtung.
 * - Klick in ein R-Quadrant, dessen Event der adressierten Richtung FEHLT:
 *   Erstellung (Seite + Richtung aus dem Quadrant), Editor öffnet am
 *   Label-Anker. Das Event der anderen Richtung koexistiert unberührt.
 * - Klick in ein R-Quadrant, dessen Event der adressierten Richtung auf der
 *   ANDEREN Seite sitzt: UMZIEHEN — SetEventSide + die Label-Textbox folgt
 *   der Spiegelung an der vertikalen R-Achse (MoveObjectTo). Reine
 *   Positions-Geste, KEIN Editor; beide Requests in derselben Geste = ein
 *   Undo-Punkt.
 * - Klick in das Quadrant, in dem das Event der adressierten Richtung
 *   bereits sitzt: Label-Inhalt bearbeiten (kein zweites — 0..1 je
 *   Richtung; das Event der ANDEREN Richtung bleibt unberührt).
 * - Klick daneben / auf K/Text(ohne Event-Owner)/W: stiller No-Op.
 * - Shift wird NICHT mehr ausgewertet (Quadranten-Modell 26.11.) — der
 *   Modifier ist bewusst tot, nicht nur optional.
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

        // 1. Linksklick: Formgriff greifen, Inhalt bearbeiten, erstellen
        //    oder umziehen — eine Regel, Verzweigungen in der Aktion
        //    (Reihenfolge trägt keine Bedeutung).
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
                    if (target.isEmpty()) {
                        return this; // Leere Fläche: stiller No-Op — Events brauchen ein R.
                    }
                    DiagramObject hit = target.get();

                    // b) Klick auf ein Event: Label-Inhalt bearbeiten
                    if (hit instanceof Event clickedEvent) {
                        c.publisher().publish(
                                new ActionEvent.EditEventLabel(clickedEvent.id()));
                        return this;
                    }

                    // b') Klick auf die Label-TEXTBOX eines Events: ebenfalls
                    //     Inhalt bearbeiten — der Text hängt am Event (Kette
                    //     R → E → Text), nicht am R.
                    if (hit instanceof Text label
                            && c.diagram().findById(label.ownerId()).orElse(null)
                                    instanceof Event owningEvent) {
                        c.publisher().publish(
                                new ActionEvent.EditEventLabel(owningEvent.id()));
                        return this;
                    }

                    // c) Klick auf ein R: Vier-Quadranten-Modell — die
                    //    VERTIKALE Klickhälfte adressiert die Richtung
                    //    (oben = IE, dockt oben; unten = OE, startet
                    //    unten), die HORIZONTALE die Seite. Kardinalität
                    //    0..1 je Richtung + Seitenlage entscheiden
                    //    (Erstellen / Bearbeiten / Umziehen).
                    if (hit instanceof Rectangle owner) {
                        EventDirection direction = clickedDirection(owner, worldY);
                        EventSide side = clickedSide(owner, worldX);
                        Optional<Event> existing = ownedEventOfDirection(c, owner.id(), direction);
                        if (existing.isEmpty()) {
                            // Kein Event dieser Richtung: Erstellung — Richtung
                            // UND Seite aus dem Quadrant (das Event der anderen
                            // Richtung koexistiert unberührt).
                            double[] anchor = EventGeometry.labelAnchor(owner, side, direction,
                                    EventGeometry.DEFAULT_EXTENT, EventGeometry.DEFAULT_RISE);
                            c.publisher().publish(new ActionEvent.CreateEventFor(
                                    owner.id(), side, direction, anchor[0], anchor[1]));
                            return this;
                        }
                        Event event = existing.get();
                        if (event.side() != side) {
                            flipSide(c, event, side);
                        } else {
                            c.publisher().publish(
                                    new ActionEvent.EditEventLabel(event.id()));
                        }
                        return this;
                    }

                    // K/W/fremder Text: stiller No-Op
                    return this;
                });

        // 2. ESC -> Werkzeug verlassen und zurück zum Auswahl-Werkzeug
        addRule(InputEvent.KeyPressed.class,
                e -> e.code() == CustomKeyCode.ESCAPE,
                (e, c) -> new IdleToolState());

        // 3. Werkzeug-Wechsel (Toolbar + Shortcuts) — zentral in ToolState
        registerToolSwitching();
    }

    /** Seitenlage der geklickten R-Hälfte (links vom Zentrum = LEFT). */
    private static EventSide clickedSide(Rectangle owner, double worldX) {
        return worldX < owner.x() ? EventSide.LEFT : EventSide.RIGHT;
    }

    /** Richtung der geklickten R-Hälfte (Vier-Quadranten-Modell, 26.11.):
     *  Oben = hereinkommend (die Spitze dockt an der OBEREN Kante an),
     *  unten = rausgehend (der Fuß startet an der UNTEREN) — die Metapher
     *  „Klick dorthin, wo der Pfeil sitzen soll" stimmt räumlich.
     *  Die Mittellinie gehört zur unteren Hälfte (deterministisch). */
    private static EventDirection clickedDirection(Rectangle owner, double worldY) {
        return worldY < owner.y() ? EventDirection.INCOMING : EventDirection.OUTGOING;
    }

    /** Das Event DER gewählten Richtung am R (Kardinalität 0..1 je
     *  Richtung, 26.11.) — z-ordnungserstes Vorkommen; die andere Richtung
     *  koexistiert und bleibt von dieser Geste unberührt. */
    private static Optional<Event> ownedEventOfDirection(InteractionContext c,
                                                         UUID ownerId,
                                                         EventDirection direction) {
        for (UUID id : c.diagram().findEventIdsOwnedBy(ownerId)) {
            if (c.diagram().findById(id).orElse(null) instanceof Event event
                    && event.direction() == direction) {
                return Optional.of(event);
            }
        }
        return Optional.empty();
    }

    /**
     * UMZIEHEN: Wechselt die Seite und spiegelt die Label-Textbox an der
     * vertikalen Symmetrieachse des R (User-Entscheidung 23.11.). Die
     * GESTE komponiert beide Requests (SetEventSide + MoveObjectTo) —
     * nicht der Service — damit das Undo rein mechanisch bleibt: beide
     * Fakten fallen in denselben Gesten-Eintrag, das Inverse (MoveObjectTo
     * + SetEventSide) wendet nichts doppelt an.
     *
     * Warum SPIEGELN statt Delta-Wanderung: Der Pfeil selbst ist exakt
     * diese Spiegelung (Spitze bei x ± w/4, Start am Seitenrand ± Ausleger)
     * — die Spiegelung erhält die RELATIVE Lage des Labels zum Pfeil. Eine
     * Delta-Wanderung um das Spitzen-Delta ließe ein weit gezogenes Label
     * auf der ALTEN Seite zurück, während der Pfeil die Seite wechselt.
     * MoveObjectTo (absolut) ist zugleich das präzise Inverse — kein
     * Float-Drift.
     */
    private void flipSide(InteractionContext c, Event event, EventSide newSide) {
        c.publisher().publish(new ActionEvent.SetEventSide(event.id(), newSide));
        if (!(c.diagram().findById(event.ownerId()).orElse(null) instanceof Rectangle owner)) {
            return; // Kaskaden-Fenster: ohne Owner keine Spiegelachse
        }
        double axisX = owner.x();
        for (UUID labelId : c.diagram().findTextIdsOwnedBy(event.id())) {
            if (!(c.diagram().findById(labelId).orElse(null) instanceof Text label)) {
                continue;
            }
            double mirroredX = 2.0 * axisX - label.x();
            if (mirroredX != label.x()) {
                c.publisher().publish(new ActionEvent.MoveObjectTo(
                        labelId, mirroredX, label.y()));
            }
        }
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
            double[] start = EventGeometry.end(owner, event.side(), event.direction(),
                    event.horizontalExtent(), event.verticalRise());
            if (Math.hypot(worldX - start[0], worldY - start[1]) <= grabRadiusWorld) {
                return Optional.of(new GripHit(event, EventHandle.START));
            }
            double[] kink = EventGeometry.kink(owner, event.side(), event.direction(),
                    event.verticalRise());
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
