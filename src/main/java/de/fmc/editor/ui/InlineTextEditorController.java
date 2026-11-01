package de.fmc.editor.ui;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.model.DiagramQuery;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.model.Text;
import de.fmc.editor.core.model.TextAnchor;
import de.fmc.editor.core.state.CameraInfo;
import javafx.scene.Node;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TextFormatter;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.text.Font;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Inline-Text-Editor (Stufe A + B + C — komplett): Der Konsument der Text-
 * Werkzeug-Intents. Abonniert {@code CreateTextFor}, {@code EditTextOfObject}
 * (Kreis UND Rechteck), {@code EditText} und {@code CreateConnectionText}
 * und öffnet einen Editor-Overlay direkt am Platz — freie Texte, externe
 * K/R-Label und R-Inhalte mehrzeilig (ENTER = Umbruch, CTRL+ENTER = Commit —
 * User-Entscheidung 02.11.), der K-Buchstabe als Ein-Zeichen-Feld (ENTER
 * committet, FMC-Konvention), der R-Inhalt über der R-Innenbox (Commit nur
 * den Inhalt — die Innenbox ist abgeleitet), das Verbindungs-Label am
 * projizierten Anker (Position leitet der Service ab; Drag re-ankert,
 * Ketten-Verformungen gleiten — W-Gleiten, Block 12).
 * Nachbearbeiten bestehender Texte läuft über {@code EditText}.
 *
 * Architektur-Disziplin:
 * - Der Editor ist reiner UI-Sitzungszustand: ESC verbraucht er selbst (der
 *   Kern sieht ihn nie), und das Modell wird erst beim COMMIT berührt — ein
 *   Abbruch kostet keinen Request (demo2-Lektion: kein Platzhalter-Gebastel).
 * - „Klick daneben committet" KONSUMIERT den Klick (spec): Der Press-Filter
 *   läuft VOR dem InputEventTranslator (Main verdrahtet die Reihenfolge) und
 *   schluckt die Geste — keine Folgewirkung, der nächste Klick wirkt normal.
 * - Fokus-Disziplin wie beim Snap-to-Grid-Feld (1.11.): Öffnen nimmt den
 *   Fokus, Schließen gibt ihn per Hook an die Zeichenfläche zurück.
 * - Die Kamera ist während des Editierens eingefroren (Scroll konsumiert):
 *   Die gespeicherte Welt-Position des Commits bleibt damit gültig.
 *
 * @author saj
 */
public class InlineTextEditorController {

    /** Editor-Arten: steuern Node-Typ, Tasten-Semantik und Commit-Request. */
    private enum Kind {
        /** Neuer freier Text an der Klick-Stelle (multiline, AddElement). */
        NEW_FREE_TEXT,
        /** Neues externes Label unterhalb eines K/R (multiline, AddElement). */
        NEW_OWNED_TEXT,
        /** Innerer Buchstabe eines Kreises (1 Zeichen, SetCircleLabel). */
        CIRCLE_LABEL,
        /** Bestehendes Text-Objekt bearbeiten (multiline, UpdateText). */
        EDIT_TEXT,
        /** Innerer R-Inhalt (multiline, SetRectangleContent; Stufe B). */
        EDIT_RECTANGLE_CONTENT,
        /** Neues Verbindungs-Label am projizierten Anker (Stufe C:
         *  AddAnchoredText — der Service leitet die Position ab). */
        NEW_CONNECTION_LABEL
    }

    /** Alles, was der Commit wissen muss — beim Öffnen eingefroren. */
    private record Session(Kind kind, UUID targetId, UUID ownerId,
                           double worldX, double worldY,
                           double boxWidth, double boxHeight,
                           String originalContent, TextAnchor anchor) {

        /** Komfort-Konstruktor: Sessions ohne Anker (alle Editor-Arten
         *  außer dem Verbindungs-Label) geben null durch. */
        Session(Kind kind, UUID targetId, UUID ownerId,
                double worldX, double worldY,
                double boxWidth, double boxHeight, String originalContent) {
            this(kind, targetId, ownerId, worldX, worldY,
                    boxWidth, boxHeight, originalContent, null);
        }
    }

    /** Default-Box neuer mehrzeiliger Texte in WELT-Einheiten. */
    private static final double NEW_TEXT_BOX_WIDTH = 140.0;
    private static final double NEW_TEXT_BOX_HEIGHT = 60.0;
    /** Abstand externer Label unterhalb ihres Owners (Welt-Einheiten). */
    private static final double OWNED_LABEL_MARGIN = 10.0;
    private static final Font EDITOR_FONT = Font.font(14);
    private static final String EDITOR_STYLE =
            "-fx-background-color: rgba(255,255,255,0.97);"
                    + "-fx-background-radius: 2;"
                    + "-fx-border-color: #0078D7; -fx-border-width: 1;"
                    + "-fx-padding: 3;";

    private final InteractionEventBus bus;
    private final DiagramQuery diagram;
    private final UiMouseState mouseState;
    private final Node canvasRoot;
    private final Consumer<Node> addToOverlay;
    private final Consumer<Node> removeFromOverlay;
    private final Runnable focusReturn;

    private TextInputControl editorNode;
    private Session session;

    public InlineTextEditorController(InteractionEventBus bus,
                                      DiagramQuery diagram,
                                      UiMouseState mouseState,
                                      Node canvasRoot,
                                      Consumer<Node> addToOverlay,
                                      Consumer<Node> removeFromOverlay,
                                      Runnable focusReturn) {
        this.bus = bus;
        this.diagram = diagram;
        this.mouseState = mouseState;
        this.canvasRoot = canvasRoot;
        this.addToOverlay = addToOverlay;
        this.removeFromOverlay = removeFromOverlay;
        this.focusReturn = focusReturn;

        // Die Intent-Abos: der Weg vom Text-Werkzeug in den Editor.
        bus.subscribe(ActionEvent.CreateTextFor.class, e -> onCreateTextFor(e.ownerId()));
        bus.subscribe(ActionEvent.EditTextOfObject.class, e -> onEditTextOfObject(e.objectId()));
        bus.subscribe(ActionEvent.EditText.class, e -> onEditText(e.textId()));
        bus.subscribe(ActionEvent.CreateConnectionText.class,
                e -> onCreateConnectionText(e.connectionId(), e.anchor()));

        // Klick-daneben-committet — VOR dem Übersetzer registriert (Main
        // konstruiert uns zuerst), damit der konsumierte Klick den Kern
        // nie erreicht. Klicks INS Editor-Feld laufen hindurch (Cursor,
        // Selektieren im Text).
        canvasRoot.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (editorNode != null && !isInsideEditor(event.getTarget())) {
                commit();
                event.consume(); // Nur Abschluss, keine Folgewirkung (spec).
            }
        });
        // Kamera einfrieren während des Editierens: Die Welt-Position des
        // Commits bliebe sonst hinter einer verschobenen Ansicht zurück.
        canvasRoot.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (editorNode != null) {
                event.consume();
            }
        });
    }

    // ---------- Intent-Zweige ----------

    private void onCreateTextFor(UUID ownerId) {
        if (editorNode != null) {
            return; // Unreachable (Klicks sind konsumiert) — defensiv.
        }
        CameraInfo camera = bus.getCameraInfo();
        if (ownerId == null) {
            // Freier Text an der Klick-Stelle: Mittelpunkt-Konvention —
            // die Klick-Welt-Position wird der Box-ZENTRUM (Erzeugung frei,
            // kein Snapping; spec).
            double worldX = camera.screenToWorldX(mouseState.getScreenX());
            double worldY = camera.screenToWorldY(mouseState.getScreenY());
            openMultiline(new Session(Kind.NEW_FREE_TEXT, null, null,
                    worldX, worldY, NEW_TEXT_BOX_WIDTH, NEW_TEXT_BOX_HEIGHT, ""), "");
        } else {
            // Externes Label unterhalb des Owners (spec: Default-Position).
            DiagramObject owner = diagram.findById(ownerId).orElse(null);
            double ownerHeight;
            if (owner instanceof Circle c) {
                ownerHeight = c.radius() * 2;
            } else if (owner instanceof Rectangle r) {
                ownerHeight = r.height();
            } else {
                return; // Nur K/R sind Label-Owner (Werkzeug-Guard).
            }
            double worldX = owner.x();
            double worldY = owner.y() + ownerHeight / 2
                    + NEW_TEXT_BOX_HEIGHT / 2 + OWNED_LABEL_MARGIN;
            openMultiline(new Session(Kind.NEW_OWNED_TEXT, null, ownerId,
                    worldX, worldY, NEW_TEXT_BOX_WIDTH, NEW_TEXT_BOX_HEIGHT, ""), "");
        }
    }

    private void onEditTextOfObject(UUID objectId) {
        if (editorNode != null) {
            return;
        }
        DiagramObject object = diagram.findById(objectId).orElse(null);
        if (object instanceof Circle circle) {
            // K-Buchstabe: Ein-Zeichen-Feld, auf den Kreis zentriert.
            openSingleChar(new Session(Kind.CIRCLE_LABEL, circle.id(), null,
                    circle.x(), circle.y(), 0, 0, circle.label()));
        } else if (object instanceof Rectangle rectangle) {
            // Stufe B: Innerer R-Inhalt — der Editor sitzt über der R-
            // Innenbox (Geometrie abzüglich Innenrand, konsistent mit der
            // View-Renderung), commit nur den INHALT: Die Innenbox ist von
            // der R-Geometrie abgeleitet, ein eigenes Resize gibt es nicht
            // (spec). Der centered-Schalter bleibt unangetastet (Default
            // zentriert; Schalter-UI folgt später — User-Entscheidung).
            double innerWidth = Math.max(20.0, rectangle.width() - 8.0);
            double innerHeight = Math.max(20.0, rectangle.height() - 8.0);
            openMultiline(new Session(Kind.EDIT_RECTANGLE_CONTENT, rectangle.id(), null,
                    rectangle.x(), rectangle.y(), innerWidth, innerHeight,
                    rectangle.content()), rectangle.content());
        }
    }

    private void onEditText(UUID textId) {
        if (editorNode != null) {
            return;
        }
        if (diagram.findById(textId).orElse(null) instanceof Text text) {
            openMultiline(new Session(Kind.EDIT_TEXT, text.id(), null,
                    text.x(), text.y(), text.width(), text.height(),
                    text.content()), text.content());
        }
    }

    /**
     * Stufe C: Verbindungs-Label. Der Anker wurde vom WERKZEUG projiziert
     * (Offset = Klick − Ankerpunkt: „das Label sitzt, wo geklickt wurde") —
     * der Service leitet beim Commit die Position daraus ab. Der Editor
     * öffnet deshalb einfach an der Klick-Stelle: Abgeleitete Mitte und
     * Klick sind identisch, und Geometrie-Wissen bleibt vollständig im Kern.
     * Nachbearbeiten läuft über den EditText-Zweig (das Werkzeug prüft die
     * 0..1-Kardinalität); Verschieben des Labels = Re-Ankerung durch den
     * Kern, Ketten-Verformungen = W-Gleiten.
     */
    private void onCreateConnectionText(UUID connectionId, TextAnchor anchor) {
        if (editorNode != null || connectionId == null || anchor == null) {
            return; // Unreachable (Werkzeug prüft beides) — defensiv.
        }
        CameraInfo camera = bus.getCameraInfo();
        double worldX = camera.screenToWorldX(mouseState.getScreenX());
        double worldY = camera.screenToWorldY(mouseState.getScreenY());
        openMultiline(new Session(Kind.NEW_CONNECTION_LABEL, null, connectionId,
                worldX, worldY, NEW_TEXT_BOX_WIDTH, NEW_TEXT_BOX_HEIGHT, "", anchor), "");
    }

    // ---------- Editor-Aufbau (die EINE Komponente, zwei Node-Typen) ----------

    private void openMultiline(Session openSession, String initialContent) {
        TextArea area = new TextArea(initialContent);
        area.setWrapText(true);
        area.setFont(EDITOR_FONT);
        area.setStyle(EDITOR_STYLE);
        area.setOnKeyPressed(this::onMultilineKey);
        show(openSession, area);
        if (initialContent.isEmpty()) {
            area.positionCaret(0);
        } else {
            area.selectAll(); // Bestehenden Text zum Überschreiben anbieten.
        }
    }

    private void openSingleChar(Session openSession) {
        TextField field = new TextField(openSession.originalContent());
        field.setFont(EDITOR_FONT);
        field.setStyle(EDITOR_STYLE);
        field.setPrefSize(50, 30);
        // FMC-Konvention: genau EIN Buchstabe — mehr nimmt das Feld nicht an.
        field.setTextFormatter(new TextFormatter<>(change ->
                change.getControlNewText().length() <= 1 ? change : null));
        field.setOnKeyPressed(this::onSingleCharKey);
        show(openSession, field);
        field.selectAll();
    }

    /** Gemeinsames Zeigen: Größe (Screen = Welt-Box × Zoom), Position,
     *  Overlay, Fokus. Single-char-Felder haben keine Welt-Box. */
    private void show(Session openSession, TextInputControl control) {
        CameraInfo camera = bus.getCameraInfo();
        double screenW = openSession.kind() == Kind.CIRCLE_LABEL
                ? control.getPrefWidth()
                : openSession.boxWidth() * camera.zoom();
        double screenH = openSession.kind() == Kind.CIRCLE_LABEL
                ? control.getPrefHeight()
                : openSession.boxHeight() * camera.zoom();
        if (openSession.kind() != Kind.CIRCLE_LABEL) {
            control.setPrefSize(screenW, screenH);
        }
        // Stufe B: Über einem sehr kleinen R wäre ein Innenbox-großer Editor
        // untippen dünn — Screen-Minimum hält die Geste komfortabel; der
        // Commit nimmt NUR den Inhalt, die R-Geometrie bleibt unberührt.
        if (openSession.kind() == Kind.EDIT_RECTANGLE_CONTENT) {
            screenW = Math.max(screenW, 140.0);
            screenH = Math.max(screenH, 60.0);
        }
        double screenX = camera.worldToScreenX(openSession.worldX()) - screenW / 2;
        double screenY = camera.worldToScreenY(openSession.worldY()) - screenH / 2;
        control.relocate(Math.max(0, screenX), Math.max(0, screenY));

        this.session = openSession;
        this.editorNode = control;
        addToOverlay.accept(control);
        control.requestFocus();
    }

    // ---------- Tasten-Semantik (User-Entscheidung 02.11.) ----------

    private void onMultilineKey(KeyEvent event) {
        switch (event.getCode()) {
            case ESCAPE -> {
                event.consume();
                close(); // Verwerfen: kein Request, das Modell bleibt unberührt.
            }
            case ENTER -> {
                if (event.isControlDown()) {
                    event.consume();
                    commit();
                }
                // Ohne CTRL: Zeilenumbruch — TextArea-Default, nicht konsumieren.
            }
            default -> { }
        }
    }

    private void onSingleCharKey(KeyEvent event) {
        switch (event.getCode()) {
            case ESCAPE -> {
                event.consume();
                close();
            }
            case ENTER -> {
                event.consume();
                commit();
            }
            default -> { }
        }
    }

    // ---------- Commit / Abbruch ----------

    private void commit() {
        if (editorNode == null) {
            return;
        }
        String content = editorNode.getText();
        Session commitSession = session;
        switch (commitSession.kind()) {
            case NEW_FREE_TEXT, NEW_OWNED_TEXT -> {
                if (!content.isBlank()) {
                    bus.publish(new ActionEvent.AddElement(new Text(
                            UUID.randomUUID(), content,
                            commitSession.worldX(), commitSession.worldY(),
                            commitSession.boxWidth(), commitSession.boxHeight(),
                            null, commitSession.ownerId(),
                            bus.getCameraInfo().defaultLayerId())));
                }
                // Leer-Commit = stiller Verwurf: kein leeres Objekt erzeugen.
            }
            case CIRCLE_LABEL -> {
                // Nur bei Änderung wirkt sich die Eingabe aus (spec); ein
                // geleertes Feld löscht den Buchstaben — gültige Änderung.
                if (!content.equals(commitSession.originalContent())) {
                    bus.publish(new ActionEvent.SetCircleLabel(commitSession.targetId(), content));
                }
            }
            case EDIT_TEXT -> {
                if (!content.equals(commitSession.originalContent())) {
                    bus.publish(new ActionEvent.UpdateText(commitSession.targetId(),
                            content, commitSession.boxWidth(), commitSession.boxHeight()));
                }
            }
            case EDIT_RECTANGLE_CONTENT -> {
                // Nur den INHALT committen; centered unangetastet (Default
                // zentriert — der Schalter folgt später mit eigenem UI-Ort).
                // Frischer Read: Das R kann theoretisch inzwischen verschoben
                // worden sein — Position und Box liest der Commit nicht aus
                // der Session, sondern aus dem Modell.
                if (!content.equals(commitSession.originalContent())
                        && diagram.findById(commitSession.targetId()).orElse(null)
                                instanceof Rectangle rectangle) {
                    bus.publish(new ActionEvent.SetRectangleContent(
                            rectangle.id(), content, rectangle.centered()));
                }
            }
            case NEW_CONNECTION_LABEL -> {
                // Stufe C: Die Position leitet der SERVICE aus dem Anker ab
                // (einzige Geometrie-Wahrheit — derselbe Pfad wie bei
                // Erzeugung und Gleiten). Leer-Commit = stiller Verwurf.
                if (!content.isBlank()) {
                    bus.publish(new ActionEvent.AddAnchoredText(commitSession.ownerId(),
                            content, commitSession.anchor(),
                            commitSession.boxWidth(), commitSession.boxHeight(),
                            bus.getCameraInfo().defaultLayerId()));
                }
            }
        }
        close();
    }

    private void close() {
        if (editorNode != null) {
            removeFromOverlay.accept(editorNode);
            editorNode = null;
            session = null;
            focusReturn.run(); // Fokus zurück auf die Zeichenfläche (Hook).
        }
    }

    /** True, wenn das Klick-Ziel im Editor selbst liegt (Parent-Kette hoch). */
    private boolean isInsideEditor(Object target) {
        Node node = target instanceof Node n ? n : null;
        while (node != null) {
            if (node == editorNode) {
                return true;
            }
            node = node.getParent();
        }
        return false;
    }
}
