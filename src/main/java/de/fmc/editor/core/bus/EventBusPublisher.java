package de.fmc.editor.core.bus;

import de.fmc.editor.core.event.DomainEvent;

@FunctionalInterface
public interface EventBusPublisher {
    void publish(DomainEvent event);
}
