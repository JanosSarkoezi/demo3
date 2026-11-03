package de.fmc.editor.core.persistence;

/**
 * Typ-Diskriminator der serialisierten Objekte — Namenskontrakt mit der
 * Altvorlage demo2 (dort FmcType): "CIRCLE", "RECTANGLE", "WAYPOINT".
 * Texte leben im eigenen texts-Abschnitt des Formats und brauchen keinen Typ.
 *
 * @author saj
 */
public enum PersistedObjectType {
    CIRCLE,
    RECTANGLE,
    WAYPOINT
}
