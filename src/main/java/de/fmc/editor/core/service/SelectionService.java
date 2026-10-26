package de.fmc.editor.core.service;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.model.SelectionQuery;

import java.util.List;
import java.util.UUID;

/**
 * Hält die aktuelle Auswahl (geordnet, duplikatfrei).
 *
 * Wie beim DiagramService ist der Event-Bus der einzige Schreibpfad:
 * SetSelection-Requests rein, SelectionChanged-Fakten raus. Auswahl ist
 * Sitzungszustand und liegt bewusst NICHT im DiagramService, damit sie
 * nie zum Modellinhalt (Persistenz) wird.
 */
public class SelectionService implements SelectionQuery {

    private List<UUID> selection = List.of();
    private InteractionEventBus bus;

    @Override
    public List<UUID> selectedIds() {
        return selection;
    }

    /** Zweite Aufbau-Phase — analog zu DiagramService.attachTo(bus). */
    public void attachTo(InteractionEventBus bus) {
        this.bus = bus;
        bus.subscribe(ActionEvent.SetSelection.class, this::onSetSelection);
    }

    private void onSetSelection(ActionEvent.SetSelection event) {
        this.selection = event.selectedIds().stream().distinct().toList();
        publishFact(new DiagramEvent.SelectionChanged(selection));
    }

    private void publishFact(DiagramEvent fact) {
        if (bus != null) {
            bus.publish(fact);
        }
    }
}
