package de.fmc.editor.core.persistence;

import de.fmc.editor.core.model.TextAnchor;

import java.util.UUID;

/**
 * DTO eines Text-Objekts. Feldnamen folgen demo2s FmcText (text,
 * parentObjectId), damit Altvorlagen-Dateien ihre Zuordnung behalten — die
 * Kerndomäne heißt weiter content/ownerId (Abbildung im {@link DiagramCodec}).
 * Der owner kann ein Objekt ODER eine Connection sein (demo2 kannte nur
 * Objekte; das Format ist permissiv).
 *
 * Demo3-Erweiterungen gegenüber demo2: height (demo2 maß nur width für den
 * Wrap) und anchor (Verankerung an einem Verbindungsabschnitt; null = absolut
 * positioniert — Gson lässt das Feld dann einfach weg).
 *
 * @author saj
 */
public record PersistedText(
        UUID id,
        String text,
        double x,
        double y,
        double width,
        double height,
        TextAnchor anchor,
        UUID parentObjectId,
        UUID layerId
) {
    public PersistedText {
        text = text == null ? "" : text;
    }
}
