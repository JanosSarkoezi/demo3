package de.fmc.editor.core.persistence;

/**
 * Typ-Diskriminator der serialisierten Objekte — Namenskontrakt mit der
 * Altvorlage demo2 (dort FmcType): "CIRCLE", "RECTANGLE", "WAYPOINT".
 * Texte leben im eigenen texts-Abschnitt des Formats und brauchen keinen Typ.
 * EVENT ist eine demo3-Erweiterung (17.11.): demo2 kannte das Symbol nicht,
 * der Typ ist damit frei definierbar — demo2-Dateien enthalten ihn nie.
 *
 * @author saj
 */
public enum PersistedObjectType {
    CIRCLE,
    RECTANGLE,
    WAYPOINT,
    EVENT
}
