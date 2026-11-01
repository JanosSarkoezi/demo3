package de.fmc.editor.core.service;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.model.Connection;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.model.SelectionQuery;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Hält die aktuelle Auswahl (geordnet, duplikatfrei). Die Menge kann seit
 * 03.11. auch Connection-IDs enthalten (Plain-Klick auf eine Verbindung):
 * Verbindungen sind damit DEL-fähig, aber nicht bewegbar — das Steuern
 * übernimmt der Kern (findMoveSet überspringt sie).
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
        // stauben gelöschte IDs automatisch heraus. Seit 03.11. stehen auch
        // Connection-IDs in der Auswahl (Plain-Klick auf eine Verbindung):
        // ConnectionsRemoved staubt sie genauso wie ObjectsRemoved die
        // Objekt-IDs — auch bei der Endpunkt-Kaskade, bei der die Verbindung
        // nie selbst in der Anfrage stand.
        bus.subscribe(DiagramEvent.ObjectsRemoved.class, this::onObjectsRemoved);
        bus.subscribe(DiagramEvent.ConnectionsRemoved.class, this::onConnectionsRemoved);
    }

    private void onSetSelection(ActionEvent.SetSelection event) {
        List<UUID> requested = event.selectedIds().stream().distinct().toList();
        // Identische Menge => kein Fakt (Ruhe für UI und Tests; Plain-Press auf
        // bereits Selektiertes kollabiert die Auswahl nicht und rauscht nicht).
        if (requested.equals(selection)) {
            return;
        }
        this.selection = requested;
        publishFact(new DiagramEvent.SelectionChanged(selection));
    }

    private void onObjectsRemoved(DiagramEvent.ObjectsRemoved event) {
        Set<UUID> removedIds = event.objects().stream()
                .map(DiagramObject::id)
                .collect(Collectors.toSet());
        pruneIds(removedIds);
    }

    private void onConnectionsRemoved(DiagramEvent.ConnectionsRemoved event) {
        Set<UUID> removedIds = event.connections().stream()
                .map(Connection::id)
                .collect(Collectors.toSet());
        pruneIds(removedIds);
    }

    /** Staubt die entfernten IDs aus der Auswahl — nur falls tatsächlich
     *  welche betroffen sind (identische Mengen feuern KEINEN Fakt). */
    private void pruneIds(Set<UUID> removedIds) {
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
