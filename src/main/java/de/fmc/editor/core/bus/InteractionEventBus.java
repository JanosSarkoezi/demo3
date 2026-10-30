package de.fmc.editor.core.bus;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.DomainEvent;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.DiagramQuery;
import de.fmc.editor.core.model.GridSpec;
import de.fmc.editor.core.model.SelectionQuery;
import de.fmc.editor.core.model.ToolType;
import de.fmc.editor.core.state.CameraInfo;
import de.fmc.editor.core.state.CameraState;
import de.fmc.editor.core.state.InteractionContext;
import de.fmc.editor.core.state.InteractionState;
import de.fmc.editor.core.state.impl.CameraIdleState;
import de.fmc.editor.core.state.impl.IdleToolState;
import de.fmc.editor.core.state.impl.ToolState;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class InteractionEventBus implements EventBusPublisher {

    // Diagnose-Hilfe: State-Übergänge live auf der Konsole (A -> B + auslösendes
    // Event), vollständige synchron zum Editor (alles läuft auf dem JavaFX-
    // Application-Thread). Aktivierung: FMC_TRACE_STATES=true mvn javafx:run
    // (Umgebungsvariable wirkt auch in geforkten JVMs) oder -Dfmc.traceStates=true.
    // Default aus — hält die Test-Ausgabe sauber.
    private static final boolean STATE_TRACE =
            Boolean.getBoolean("fmc.traceStates")
                    || "true".equalsIgnoreCase(System.getenv("FMC_TRACE_STATES"));

    // 1. Zwei parallele State-Machines (Ansatz C)
    private InteractionState toolState = new IdleToolState();
    private CameraState cameraState = new CameraIdleState();

    // Aktives Werkzeug (Session-Zustand, read-only getrackt) — Grundlage
    // für den ToolChanged-Hinweis an die UI (Toolbar-Sync).
    private ToolType currentTool = ToolType.SELECT;

    // Read-Only-Dependencies für den InteractionContext
    private final DiagramQuery diagramQuery;
    // Bewusst NICHT final: Der Bus hält den aktuellen Kamera-Snapshot und
    // aktualisiert ihn selbst bei PanView/ZoomView (siehe publish) — sonst
    // rechnen alle nachfolgenden Interaktionen mit veralteten Offsets/Zoom.
    private CameraInfo cameraInfo;
    private final GridSpec gridSpec;
    private final SelectionQuery selectionQuery;

    private final List<Consumer<DomainEvent>> subscribers = new CopyOnWriteArrayList<>();

    // Injektion der Read-Only-Schnittstellen beim Erstellen des Bus
    public InteractionEventBus(DiagramQuery diagramQuery, CameraInfo cameraInfo,
                               GridSpec gridSpec, SelectionQuery selectionQuery) {
        this.diagramQuery = diagramQuery;
        this.cameraInfo = cameraInfo;
        this.gridSpec = gridSpec;
        this.selectionQuery = selectionQuery;
        if (STATE_TRACE) {
            System.out.println("[Tool]   initial: " + toolState.getClass().getSimpleName()
                    + "   [Camera] initial: " + cameraState.getClass().getSimpleName());
        }
    }

    /** Diagnose-Ausgabe eines Maschinen-Übergangs — nur bei KLASSEN-Wechsel
     *  (gleiche Klasse = neues Exemplar desselben Zustands, keine Zeile). */
    private <T> void traceTransition(String machine, T before, T after, DomainEvent event) {
        if (!STATE_TRACE || before.getClass() == after.getClass()) {
            return;
        }
        System.out.println("[" + machine + "] " + before.getClass().getSimpleName()
                + " -> " + after.getClass().getSimpleName()
                + "  (" + event.getClass().getSimpleName() + ")");
    }

    public <T extends DomainEvent> void subscribe(Class<T> eventType, Consumer<T> subscriber) {
        subscribers.add(event -> {
            if (eventType.isInstance(event)) {
                subscriber.accept(eventType.cast(event));
            }
        });
    }

    @Override
    public void publish(DomainEvent event) {
        // Frischen Context für diesen Event-Durchlauf bauen
        InteractionContext context = new InteractionContext(
                this, diagramQuery, cameraInfo, gridSpec, selectionQuery);

        // 2. Kamera-Machine ZUERST verarbeiten
        // Reagiert auf InputEvents sowie Initiierungs-Events wie RequestCameraPan
        if (event instanceof InputEvent || event instanceof ActionEvent.RequestCameraPan) {
            CameraState before = this.cameraState;
            this.cameraState = cameraState.handleInput(event, context);
            traceTransition("Camera", before, this.cameraState, event);
        }

        // 3. Tool-Machine NUR verarbeiten, wenn die Kamera NICHT aktiv panned
        if (!cameraState.isActive()) {
            // Reagiert auf InputEvents ODER Steuerungs-Events von der UI (z.B. SelectTool)
            if (event instanceof InputEvent || event instanceof ActionEvent.SelectTool) {
                InteractionState before = this.toolState;
                this.toolState = toolState.handleInput(event, context);
                traceTransition("Tool", before, this.toolState, event);
            }
        }

        // 3b. Toolbar-Sync: Hat der Übergang das WERKZEUG geändert (nicht nur
        // den Gesten-State), merke einen ToolChanged-Hinweis. Gesten-States
        // ohne Tool-Typ (Connecting, Moving, ResizeMode, ...) lassen den
        // zuletzt aktiven Tool-Typ implizit unverändert.
        ActionEvent.ToolChanged toolHint = null;
        if (toolState instanceof ToolState ts && ts.toolType() != currentTool) {
            currentTool = ts.toolType();
            toolHint = new ActionEvent.ToolChanged(currentTool);
        }

        // 3a. Kamera-Snapshot aktualisieren: PanView/ZoomView sind Fakten, die
        // der Bus selbst konsumiert — jeder NÄCHSTE Event-Durchlauf baut seinen
        // InteractionContext bereits mit der aktuellen Kamera (Hit-Tests,
        // Erstellen, Verschieben rechnen dann mit echten Welt-Koordinaten).
        if (event instanceof ActionEvent.PanView pan) {
            this.cameraInfo = cameraInfo.panBy(pan.deltaX(), pan.deltaY());
        } else if (event instanceof ActionEvent.ZoomView zoom) {
            this.cameraInfo = cameraInfo.zoomAt(zoom.zoomFactor(), zoom.pivotX(), zoom.pivotY());
        }

        // 4. Event an alle externen Subscriber verteilen (Canvas, DiagramService, etc.)
        for (var subscriber : subscribers) {
            subscriber.accept(event);
        }

        // 5. ToolChanged als Folge-Hinweis NACH dem Versand des auslösenden
        // Events. Rekursionstiefe 1: ToolChanged ist weder InputEvent noch
        // SelectTool — die State-Machines ignorieren ihn, kein Loop.
        if (toolHint != null) {
            publish(toolHint);
        }
    }

    public InteractionState getToolState() {
        return toolState;
    }

    public CameraState getCameraState() {
        return cameraState;
    }

    /** Aktueller Kamera-Snapshot (read-only) — für Tests und die spätere UI. */
    public CameraInfo getCameraInfo() {
        return cameraInfo;
    }
}