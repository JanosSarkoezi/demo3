package de.fmc.editor.core.state.rule;

import de.fmc.editor.core.event.DomainEvent;
import de.fmc.editor.core.state.InteractionContext;
import de.fmc.editor.core.state.InteractionState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public abstract class BaseState implements InteractionState {

    /** Prüft eine Regel unter Einbeziehung von Event UND Context (z. B. für Hit-Testing) */
    @FunctionalInterface
    public interface EventGuard<T extends DomainEvent> {
        boolean test(T event, InteractionContext context);
    }

    /** Führt die Aktion aus und gibt den Folge-Zustand zurück */
    @FunctionalInterface
    public interface EventAction<T extends DomainEvent> {
        InteractionState execute(T event, InteractionContext context);
    }

    /** Interne Record-Klasse zur typsicheren Speicherung einer Regel */
    private record Rule<T extends DomainEvent>(
            Class<T> eventType,
            EventGuard<T> guard,
            EventAction<T> action
    ) {
        boolean matches(DomainEvent event, InteractionContext context) {
            if (eventType.isInstance(event)) {
                return guard.test(eventType.cast(event), context);
            }
            return false;
        }

        InteractionState execute(DomainEvent event, InteractionContext context) {
            return action.execute(eventType.cast(event), context);
        }
    }

    private final List<Rule<?>> rules = new ArrayList<>();

    // --- Overloads für einfaches Registrieren von Regeln ---

    /** 1. Regel ohne Bedingung: Reagiert auf jeden Treffer des Event-Typs */
    protected <T extends DomainEvent> void addRule(Class<T> eventType, EventAction<T> action) {
        addRule(eventType, (e, c) -> true, action);
    }

    /** 2. Regel mit reiner Event-Bedingung (z. B. e -> e.button() == PRIMARY) */
    protected <T extends DomainEvent> void addRule(
            Class<T> eventType,
            Predicate<T> eventCondition,
            EventAction<T> action
    ) {
        addRule(eventType, (e, c) -> eventCondition.test(e), action);
    }

    /** 3. Regel mit Event- UND Context-Bedingung (z. B. (e, c) -> c.diagram().findTopmostAt(...).isEmpty()) */
    protected <T extends DomainEvent> void addRule(
            Class<T> eventType,
            EventGuard<T> guard,
            EventAction<T> action
    ) {
        rules.add(new Rule<>(eventType, guard, action));
    }

    // --- Die zentrale Dispatcher-Methode ---

    /**
     * Dispatcht das Event an die (per Invariante höchstens eine) passende Regel.
     *
     * Guard-Disjunktions-Invariante: Für jedes (Event, Kontext)-Paar darf
     * höchstens EINE Regel greifen — andernfalls hinge das Verhalten an der
     * Registrierungsreihenfolge und die zweite Regel liefe stillschweigend
     * nie. Mehrfach-Treffern wird daher sofort mit einer aussagekräftigen
     * IllegalStateException begegnet. Konsequenz: Guards müssen
     * seiteneffektfrei sein, da hier alle ausgewertet werden.
     */
    @Override
    public InteractionState handleInput(DomainEvent event, InteractionContext context) {
        Rule<?> singleMatch = null;
        int matchCount = 0;

        for (var rule : rules) {
            if (rule.matches(event, context)) {
                matchCount++;
                if (matchCount > 1) {
                    throw new IllegalStateException(
                            "Ambige Regeln in " + getClass().getSimpleName()
                                    + " für " + event + ": "
                                    + matchCount + " Guards greifen gleichzeitig");
                }
                singleMatch = rule;
            }
        }

        // Greift keine Regel, bleibt die State-Machine im aktuellen Zustand
        if (singleMatch == null) {
            return this;
        }
        return singleMatch.execute(event, context);
    }
}