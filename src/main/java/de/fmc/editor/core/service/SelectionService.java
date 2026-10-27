package de.fmc.editor.core.service;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.model.SelectionQuery;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
        // Invariante: Die Auswahl enthält nie tote IDs — Entfern-Fakten
        // stauben gelöschte IDs automatisch heraus.
        bus.subscribe(DiagramEvent.ObjectsRemoved.class, this::onObjectsRemoved);
    }

    private void onSetSelection(ActionEvent.SetSelection event) {
        this.selection = event.selectedIds().stream().distinct().toList();
        publishFact(new DiagramEvent.SelectionChanged(selection));
    }

    private void onObjectsRemoved(DiagramEvent.ObjectsRemoved event) {
        Set<UUID> removedIds = event.objects().stream()
                .map(DiagramObject::id)
                .collect(Collectors.toSet());
        List<UUID> pruned = selection.stream()
                .filter(id -> !removedIds.contains(id))
                .toList();
        if (pruned.size() != selection.size()) {
            this.selection = pruned;
            publishFact(new DiagramEvent.SelectionChanged(selection));
        }
    }

    private void publishFact(DiagramEvent fact) {
        if (bus != null) {
            bus.publish(fact);
        }
    }
}
