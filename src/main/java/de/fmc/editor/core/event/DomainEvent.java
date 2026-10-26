package de.fmc.editor.core.event;

public sealed interface DomainEvent permits InputEvent, ActionEvent, DiagramEvent {}
