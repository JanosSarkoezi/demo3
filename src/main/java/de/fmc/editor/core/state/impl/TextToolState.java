package de.fmc.editor.core.state.impl;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomKeyCode;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.model.Text;
import de.fmc.editor.core.model.ToolType;

/**
 * Text-Werkzeug: Alle Platzierungs- und Bearbeitungs-Intents als schlanke
 * UI-Intents (noch ohne Service-Konsument — die UI öffnet beim Empfang den
 * Inline-Editor und publiziert beim Commit die passenden Requests:
 * SetCircleLabel, SetRectangleContent, AddElement/AddAnchoredText, UpdateText).
 *
 * Gesten (gemäß spec.md):
 * - Klick auf K/R: inneren Text bearbeiten.
 * - Ctrl+Klick auf K/R: neues externes Label unterhalb des Objekts.
 * - Klick auf Text-Objekt: dieses bearbeiten.
 * - Klick in Verbindungsnähe: bestehendes Label bearbeiten (Kardinalität
 *   0..1), sonst neues an dem geklickten Abschnitt verankern.
 * - Klick auf leere Fläche: freier Text.
 *
 * Guard-Disjunktheit: GENAU EINE MousePressed-Regel — alle Verzweigungen
 * liegen in der Aktion; die KeyPressed-Regeln (ESC, Shortcuts) treffen auf
 * disjunkte Keys.
 */
public final class TextToolState extends ToolState {

    /** Wie weit ein Klick von einer Verbindung entfernt noch als
     *  Verbindungs-Klick gilt (Welt-Einheiten, wie demo2). */
    private static final double CONNECTION_TOLERANCE = 10.0;

    public TextToolState() {
        super(ToolType.TEXT);

        // 1. Linksklick: Text platzieren oder bearbeiten — eine Regel,
        //    Verzweigungen in der Aktion (Reihenfolge trägt keine Bedeutung).
        addRule(InputEvent.MousePressed.class,
                e -> e.button() == CustomMouseButton.PRIMARY && e.clickCount() == 1,
                (e, c) -> {
                    double worldX = c.camera().screenToWorldX(e.x());
                    double worldY = c.camera().screenToWorldY(e.y());

                    var target = c.diagram().findTopmostAt(worldX, worldY);
                    if (target.isPresent()) {
                        DiagramObject hit = target.get();
                        if (hit instanceof Text text) {
                            // Bestehendes Text-Objekt bearbeiten
                            c.publisher().publish(new ActionEvent.EditText(text.id()));
                        } else if (e.controlDown()) {
                            // Ctrl+Klick auf K/R: externes Label darunter
                            c.publisher().publish(new ActionEvent.CreateTextFor(hit.id()));
                        } else {
                            // Klick auf K/R: inneren Text bearbeiten
                            c.publisher().publish(new ActionEvent.EditTextOfObject(hit.id()));
                        }
                        return this;
                    }

                    // Verbindung in der Nähe? -> Verbindungs-Label (0..1)
                    var connection = c.diagram().findConnectionNear(worldX, worldY, CONNECTION_TOLERANCE);
                    if (connection.isPresent()) {
                        var existing = c.diagram().findTextIdsOwnedBy(connection.get());
                        if (!existing.isEmpty()) {
                            // Kardinalität 0..1: bestehendes Label bearbeiten
                            c.publisher().publish(new ActionEvent.EditText(existing.getFirst()));
                        } else {
                            var anchor = c.diagram().projectOntoConnection(connection.get(), worldX, worldY);
                            if (anchor.isPresent()) {
                                c.publisher().publish(new ActionEvent.CreateConnectionText(
                                        connection.get(), anchor.get()));
                            }
                        }
                        return this;
                    }

                    // Leere Fläche: freier Text (ownerId = null)
                    c.publisher().publish(new ActionEvent.CreateTextFor(null));
                    return this;
                });

        // 2. ESC -> Werkzeug verlassen und zurück zum Auswahl-Werkzeug
        addRule(InputEvent.KeyPressed.class,
                e -> e.code() == CustomKeyCode.ESCAPE,
                (e, c) -> new IdleToolState());

        // 3. Werkzeug-Wechsel (Toolbar + Shortcuts) — zentral in ToolState
        registerToolSwitching();
    }
}
