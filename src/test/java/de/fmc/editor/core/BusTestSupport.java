package de.fmc.editor.core;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.Circle;
import de.fmc.editor.core.model.GridSpec;
import de.fmc.editor.core.model.Rectangle;
import de.fmc.editor.core.service.DiagramService;
import de.fmc.editor.core.service.SelectionService;
import de.fmc.editor.core.service.UndoService;
import de.fmc.editor.core.state.CameraInfo;

import java.util.UUID;

/**
 * Geteilter Test-Rahmen für Event-basierte Tests: verdrahtet Bus,
 * DiagramService, SelectionService und UndoService in der
 * Produktions-Reihenfolge (Bus zuerst konstruieren, danach attachTo —
 * Zirkularität Bus ↔ Service) und liefert kleine Gesten-Helfer.
 * Zuvor als closeGesture()/addCircle()-Duplikat in UndoRedoTest und
 * ZOrderRestoreTest vorhanden (KI-Review-Runde 2, deepseek #7).
 */
class BusTestSupport {

    final InteractionEventBus bus;
    final DiagramService diagram;
    final UUID defaultLayerId = UUID.randomUUID();

    BusTestSupport() {
        diagram = new DiagramService();
        SelectionService selectionService = new SelectionService();
        UndoService undoService = new UndoService();
        bus = new InteractionEventBus(diagram,
                new CameraInfo(1.0, 0.0, 0.0, defaultLayerId),
                new GridSpec(10.0), selectionService);
        diagram.attachTo(bus);
        selectionService.attachTo(bus);
        undoService.attachTo(bus);
    }

    /** Neutrale Gesten-Grenze für synthetische Abläufe: IdleToolState
     *  ignoriert ein Release ohne vorangegangenen Press, der UndoService
     *  schließt den laufenden Eintrag. Schließt KEINE eigene Geste ab —
     *  ob ein AddElement ein eigener Undo-Punkt ist, entscheidet der Aufrufer. */
    void closeGesture() {
        bus.publish(new InputEvent.MouseReleased(0.0, 0.0, CustomMouseButton.PRIMARY));
    }

    Circle addCircle(double x, double y) {
        Circle circle = new Circle(UUID.randomUUID(), x, y, 20.0, defaultLayerId);
        bus.publish(new ActionEvent.AddElement(circle));
        return circle;
    }

    Rectangle addRectangle(double x, double y) {
        Rectangle rect = new Rectangle(UUID.randomUUID(), x, y, 40.0, 30.0, defaultLayerId);
        bus.publish(new ActionEvent.AddElement(rect));
        return rect;
    }
}
