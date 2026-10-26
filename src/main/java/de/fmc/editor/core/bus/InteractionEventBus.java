package de.fmc.editor.core.bus;

import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.DomainEvent;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.DiagramQuery;
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
    private final CameraInfo cameraInfo;

    private final List<Consumer<DomainEvent>> subscribers = new CopyOnWriteArrayList<>();

    // Injektion der Read-Only-Schnittstellen beim Erstellen des Bus
    public InteractionEventBus(DiagramQuery diagramQuery, CameraInfo cameraInfo) {
        this.diagramQuery = diagramQuery;
        this.cameraInfo = cameraInfo;
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
        InteractionContext context = new InteractionContext(this, diagramQuery, cameraInfo);

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
}