package de.fmc.editor.core;

import de.fmc.editor.core.bus.InteractionEventBus;
import de.fmc.editor.core.event.ActionEvent;
import de.fmc.editor.core.event.DiagramEvent;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.model.DiagramQuery;
import de.fmc.editor.core.model.GridSpec;
import de.fmc.editor.core.service.SelectionService;
import de.fmc.editor.core.state.CameraInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SelectionServiceTest {

    private InteractionEventBus bus;
    private SelectionService selection;
    private final List<DiagramEvent.SelectionChanged> facts = new ArrayList<>();

    @BeforeEach
    void setUp() {
        facts.clear();

        DiagramQuery emptyDiagram = new DiagramQuery() {
            @Override
            public Optional<DiagramObject> findTopmostAt(double x, double y) {
                return Optional.empty();
            }

            @Override
            public Optional<DiagramObject> findById(UUID id) {
                return Optional.empty();
            }
        };

        selection = new SelectionService();
        bus = new InteractionEventBus(emptyDiagram,
                new CameraInfo(1.0, 0.0, 0.0, UUID.randomUUID()),
                new GridSpec(10.0), selection);
        selection.attachTo(bus);
        bus.subscribe(DiagramEvent.SelectionChanged.class, facts::add);
    }

    @Test
    @DisplayName("SetSelection ersetzt die Auswahl und feuert den SelectionChanged-Fakt")
    void setSelectionShouldReplaceSelectionAndFireFact() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        bus.publish(new ActionEvent.SetSelection(List.of(a, b)));

        assertThat(selection.selectedIds()).containsExactly(a, b);
        assertThat(facts).hasSize(1);
        assertThat(facts.getFirst().selectedIds()).containsExactly(a, b);
    }

    @Test
    @DisplayName("Duplikate werden entfernt, die Reihenfolge bleibt erhalten")
    void setSelectionShouldRemoveDuplicates() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        bus.publish(new ActionEvent.SetSelection(List.of(a, a, b)));

        assertThat(selection.selectedIds()).containsExactly(a, b);
    }

    @Test
    @DisplayName("Leere Auswahl hebt die Selektion auf und feuert ebenfalls den Fakt")
    void emptySelectionShouldClearSelection() {
        UUID a = UUID.randomUUID();
        bus.publish(new ActionEvent.SetSelection(List.of(a)));
        assertThat(selection.isEmpty()).isFalse();

        bus.publish(new ActionEvent.SetSelection(List.of()));

        assertThat(selection.isEmpty()).isTrue();
        assertThat(facts).hasSize(2);
        assertThat(facts.getLast().selectedIds()).isEmpty();
    }
}
