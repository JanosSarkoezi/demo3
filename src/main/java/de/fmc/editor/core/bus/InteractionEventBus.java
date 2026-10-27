package de.fmc.editor.core.bus;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.DomainEvent;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.DiagramQuery;
import de.fmc.editor.core.model.GridSpec;
import de.fmc.editor.core.model.SelectionQuery;
import de.fmc.editor.core.state.CameraInfo;
import de.fmc.editor.core.state.CameraState;
import de.fmc.editor.core.state.InteractionContext;
import de.fmc.editor.core.state.InteractionState;
import de.fmc.editor.core.state.impl.CameraIdleState;
import de.fmc.editor.core.state.impl.IdleToolState;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class InteractionEventBus implements EventBusPublisher {

    // 1. Zwei parallele State-Machines (Ansatz C)
    private InteractionState toolState = new IdleToolState();
    private CameraState cameraState = new CameraIdleState();

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
            this.cameraState = cameraState.handleInput(event, context);
        }

        // 3. Tool-Machine NUR verarbeiten, wenn die Kamera NICHT aktiv panned
        if (!cameraState.isActive()) {
            // Reagiert auf InputEvents ODER Steuerungs-Events von der UI (z.B. SelectTool)
            if (event instanceof InputEvent || event instanceof ActionEvent.SelectTool) {
                this.toolState = toolState.handleInput(event, context);
            }
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