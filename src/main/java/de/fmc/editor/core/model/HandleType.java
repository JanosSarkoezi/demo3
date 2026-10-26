package de.fmc.editor.core.model;

/**
 * Griff-Punkte für Resize-Interaktionen.
 * Rechteck nutzt alle 8 (Ecken + Seiten-Mitten), Kreis nur N/E/S/W.
 */
public enum HandleType {
    NW, N, NE, E, SE, S, SW, W
}
