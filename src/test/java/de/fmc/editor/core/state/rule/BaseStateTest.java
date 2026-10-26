package de.fmc.editor.core.state.rule;

import de.fmc.editor.core.event.CustomMouseButton;
import de.fmc.editor.core.event.InputEvent;
import de.fmc.editor.core.model.DiagramObject;
import de.fmc.editor.core.model.DiagramQuery;
import de.fmc.editor.core.model.GridSpec;
import de.fmc.editor.core.state.CameraInfo;
import de.fmc.editor.core.state.InteractionContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Beweist die Guard-Disjunktions-Invariante von BaseState direkt:
 * Greifen für ein (Event, Kontext)-Paar mehrere Guards gleichzeitig, scheitert
 * der Dispatcher sofort — die Registrierungsreihenfolge darf niemals
 * Verhalten tragen.
 */
class BaseStateTest {

    /** Zwei absichtlich überlappende Guards: beide greifen bei PRIMARY-Press. */
    static final class AmbiguousState extends BaseState {
        AmbiguousState() {
            addRule(InputEvent.MousePressed.class,
                    e -> e.button() == CustomMouseButton.PRIMARY,
                    (e, c) -> this);
            addRule(InputEvent.MousePressed.class,
                    e -> e.button() == CustomMouseButton.PRIMARY,
                    (e, c) -> this);
        }
    }

    /** Guard-disjunkte Regeln: einfacher Klick vs. Doppelklick. */
    static final class DisjointState extends BaseState {
        DisjointState() {
            addRule(InputEvent.MousePressed.class,
                    e -> e.button() == CustomMouseButton.PRIMARY && e.clickCount() == 1,
                    (e, c) -> this);
            addRule(InputEvent.MousePressed.class,
                    e -> e.button() == CustomMouseButton.PRIMARY && e.clickCount() == 2,
                    (e, c) -> this);
        }
    }

    private final DiagramQuery emptyDiagram = new DiagramQuery() {
        @Override
        public Optional<DiagramObject> findTopmostAt(double worldX, double worldY) {
            return Optional.empty();
        }

        @Override
        public Optional<DiagramObject> findById(UUID id) {
            return Optional.empty();
        }
    };

    private final InteractionContext context = new InteractionContext(
            event -> { },
            emptyDiagram,
            new CameraInfo(1.0, 0.0, 0.0, UUID.randomUUID()),
            new GridSpec(10.0),
            () -> List.of()
    );

    @Test
    @DisplayName("Überlappende Guards scheitern sofort mit aussagekräftiger Meldung")
    void shouldFailFastOnAmbiguousRules() {
        AmbiguousState state = new AmbiguousState();
        InputEvent.MousePressed press =
                new InputEvent.MousePressed(10.0, 20.0, CustomMouseButton.PRIMARY, 1);

        assertThatThrownBy(() -> state.handleInput(press, context))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ambige Regeln")
                .hasMessageContaining("AmbiguousState");
    }

    @Test
    @DisplayName("Disjunkte Guards dispatchen eindeutig; ohne Treffer bleibt der Zustand")
    void shouldDispatchUniquelyWithDisjointGuards() {
        DisjointState state = new DisjointState();
        InputEvent.MousePressed singleClick =
                new InputEvent.MousePressed(10.0, 20.0, CustomMouseButton.PRIMARY, 1);
        InputEvent.MousePressed doubleClick =
                new InputEvent.MousePressed(10.0, 20.0, CustomMouseButton.PRIMARY, 2);
        InputEvent.MousePressed middleClick =
                new InputEvent.MousePressed(10.0, 20.0, CustomMouseButton.MIDDLE, 1);

        assertThat(state.handleInput(singleClick, context)).isSameAs(state);
        assertThat(state.handleInput(doubleClick, context)).isSameAs(state);
        // Keine Regel passt => Zustand bleibt (bewusster Rest der Partition)
        assertThat(state.handleInput(middleClick, context)).isSameAs(state);
    }
}
