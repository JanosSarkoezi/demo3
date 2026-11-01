# Werkzeug-Referenz (Soll-Verhalten)

Bedienungs-Referenz des FMC-Editors: pro Werkzeug und Modus die Aktionen
und die erwartete Reaktion — zugleich Abhakliste für den visuellen
Smoke-Test. (Produktvision: `spec.md`; Architektur: `AGENTS.md`; hier
steht, was die Hand am Mauszeiger tut.)

## Allgemein

| Sache | Verhalten |
|---|---|
| Shortcuts | **K** Kreis, **R** Rechteck, **C** Verbindung, **T** Text — wirken nur außerhalb der langen Modi (siehe Restchen) |
| Toolbar | Demo2-Look; KEIN Select-Button — kein aktiver Toggle bedeutet SELECT |
| Undo/Redo | **CTRL+Z** macht die letzte Geste rückgängig (pro Geste genau EIN Schritt, inkl. Lösch-Kaskade); **SHIFT+CTRL+Z** / **CTRL+Y** wiederholt; jede neue Aktion leert die Redo-Historie |
| Löschen | **DEL** löscht ausschließlich die aktuelle Auswahl — nie „unter dem Cursor" |
| Snap-to-Grid | Toolbar-Feld „Snap to Grid": freier Zahlenwert (> 0, Komma erlaubt); Commit bei ENTER oder Fokus-Verlust, ungültige Eingabe stellt still auf den letzten gültigen Wert zurück. Das Netz ist IMMER aktiv, wirkt sofort (Snapping + Resize-Mindestgröße = 1 Zelle) und wird nicht gespeichert |
| Fokus | Jeder Klick auf die Zeichenfläche holt den Tastatur-Fokus zurück — Shortcuts leben nur auf der Fläche |

## Kamera (in JEDEM Werkzeug aktiv)

Die Kamera ist eine eigene Maschine, die bei jedem Event VOR dem Werkzeug
läuft — deshalb zoomt das Mausrad überall, und während des Schwenkens ist
das Werkzeug pausiert.

| Aktion | Erwartung |
|---|---|
| Mausrad | Zoom AM MAUSZEIGER (der Punkt unter dem Cursor bleibt fixiert), Klemmung 0,2-fach bis 10-fach. Null-Delta-Begleit-Events (VNC) werden still ignoriert |
| Leerraum-Drag (ohne Ctrl) | Ansicht schwenken (Pan) — erst nach Überschreiten des 4-px-Schwellenwerts; nur die Taste, die die Geste startete, beendet sie |
| Während des Pannings | Werkzeug pausiert (kein versehentliches Erzeugen/Selektieren unterwegs) |

## SELECT (Auswahl-Werkzeug)

Auslöser: Toolbar / kein aktiver Toggle. Basis-State: `IdleToolState`.

| Aktion | Erwartung |
|---|---|
| Klick auf Objekt | Selektiert es EXKLUSIV (ersetzt die bisherige Auswahl) — zum Zeitpunkt des Drucks, nicht des Loslassens |
| Klick auf bereits Selektiertes + Drag | Gruppen-Move: die ganze Auswahl wandert; die Auswahl kollabiert NICHT. Das primär gezogene Objekt snappt mit seinem Mittelpunkt auf den nächsten Gitterpunkt, die Gruppe folgt dem effektiven Delta. Wiggen innerhalb der Zelle bewegt nichts („kleben") |
| CTRL+Klick | Objekt zur Auswahl hinzufügen; auf bereits Selektiertem: herausnehmen (Toggle) |
| Klick auf leere Fläche / ESC | Auswahl leeren |
| CTRL+Leerraum-Drag | Gummiband: stummes Rechteck bis zum Loslassen, dann werden alle Objekte darin ausgewählt (das Band ERSETZT die Auswahl). CTRL+Klick ohne Drag ändert nichts, ESC bricht ab |
| Doppelklick auf K oder R | Resize-Modus (siehe unten). Doppelklick auf W selektiert nur (Ws haben keine Griffe) |
| Klick NAHE einer Verbindung (≈ 8 px) | **Selektiert die Verbindung** (blauer Glow auf dem Pfad, ihre Ws erscheinen abgeleitet mitmarkiert, ohne selbst in der Auswahl zu stehen). Verbindungen sind nicht bewegbar: Drag an der Verbindung ist ein No-Op. DEL löscht die ausgewählte Verbindung samt ihrer Ws und Verbindungs-Labels — die Endpunkte bleiben; EIN Undo-Punkt bringt alles zurück. Der graue Hover-Glow zeigt das Klick-Ziel bereits an |
| CTRL+Klick NAHE einer Verbindung | RouteEditMode (siehe unten): Die Ws erscheinen als gelbe Kreise und sind greifbar |
| Doppelklick NAHE einer Verbindung | Modus + Waypoint-Einfügen in EINER Geste (auch bei Direktverbindungen ohne Ws). Trifft der Doppelklick ein bestehendes W, wird es nur selektiert |
| DEL | Kaskadierendes Löschen der Auswahl: Objekt weg = externe Label weg, seine Verbindungen weg (inkl. deren Ws). Verbindung (seit 03.11. selbst selektierbar) weg = ihre Ws und ihr Label weg, Endpunkte bleiben. Ein gelöschtes W wird nur aus überlebenden Verbindungen gestrichen (Prune). Eine Lösch-Geste = EIN Undo-Punkt (alles kommt zusammen zurück) — auch bei Misch-Auswahl aus Objekten UND Verbindungen |
| Waypoints verschieben | Nur im RouteEditMode — im SELECT sind Ws keine Klick-Ziele (sie erscheinen nur als abgeleitete Markierung einer selektierten Verbindung; siehe Modus unten) |

## K (Kreis-Werkzeug)

| Aktion | Erwartung |
|---|---|
| Klick auf freie Fläche | Kreis (Standardradius 20) an der Klick-Stelle — Erzeugung OHNE Snapping, Mittelpunkt = Klickpunkt |
| Klick auf bestehendes Objekt | Stiller No-Op (kein deckungsgleicher Stapel — sonst wäre die Auswahl Lotterie) |
| Klick auf unsichtbares W | Erzeugt normal (unsichtbare Objekte blockieren nicht) |
| Drag statt Klick | Pan (siehe Kamera) |
| ESC | Zurück zu SELECT |

## R (Rechteck-Werkzeug)

Wie K, nur: Klick auf freie Fläche erzeugt ein Rechteck (Standard 40×30),
Mittelpunkt = Klickpunkt, Erzeugung frei von Snapping.

## C (Verbindungs-Werkzeug)

| Aktion | Erwartung |
|---|---|
| Klick auf Start-Objekt | Verbindungsaufbau beginnt: gestrichelte Vorschau folgt der Maus |
| Weitere Klicks (Leerraum) | Setzen je einen Waypoint (gelber Marker in der Vorschau) |
| Klick auf ein vorhandenes W | Gilt wie Leerraum — NEUER Kettenpunkt, Ws sind keine Verbindungsziele |
| Klick auf Ziel-Objekt | Verbindung entsteht: gerichtet (Pfeil am Ziel), Segmente an den Ecken abgerundet; die Ws werden Modell-Objekte |
| Klick auf Objekt des GLEICHEN Typs | Abgelehnt (K↔K, R↔R — Validierung im Kern, nicht im Werkzeug) |
| ESC | Bricht den Aufbau ab — nichts entsteht, kein Undo-Punkt |

## T (Text-Werkzeug)

| Aktion | Erwartung |
|---|---|
| Klick auf leere Fläche | Freier Text: Editor-Overlay an der Klickstelle (mehrzeilig) |
| Klick auf K | Buchstaben-Editor: Ein-Zeichen-Feld auf dem Kreis, alter Buchstabe vormarkiert |
| CTRL+Klick auf K oder R | Externes Label: Editor unterhalb des Objekts — wandert künftig mit seinem Owner mit |
| Klick auf bestehenden Text | Nachbearbeiten: alter Inhalt vormarkiert (Überschreiben oder erweitern) |
| Klick auf R (innerer Text) | R-Inhalt-Editor über der R-Innenbox: mehrzeilig tippen, **CTRL+ENTER** committet — die Box des R bleibt unverändert, der Text passt sich beim Resizen automatisch mit an (zentriert; der Zentrier-Schalter folgt später) |
| Klick NAHE einer Verbindung | Verbindungs-Label am geklickten Abschnitt anlegen — es sitzt dort, wo geklickt wurde. Hat die Verbindung bereits eins, wird es stattdessen geöffnet (maximal eins je Verbindung). Das Label lässt sich verschieben (Drag setzt Anker neu: Position entlang der Linie + Abstand) und **gleitet mit**, wenn die Verbindung umgeformt wird (W ziehen, Endpunkt verschieben, W einfügen/löschen) |
| ESC | Zurück zu SELECT (nicht zu verwechseln mit ESC IM Editor — siehe unten) |

## Modus: Resize (aus SELECT per Doppelklick auf K/R/Text)

| Aktion | Erwartung |
|---|---|
| Betreten | Doppelklick auf K, R oder ein Text-Objekt. Griffe erscheinen: 8 an R und Text-Box (Ecken + Seitenmitten), 4 am K (N/O/S/W). Live-Vorschau folgt dem Drag exakt |
| Griff ziehen | Symmetrisch ums Zentrum; Mindestgröße = eine Gitterzelle. Während des Ziehens KEIN Modell-Fakt (reine Vorschau) |
| Loslassen | GENAU EIN Commit (ein Undo-Punkt); der Modus BLEIBT offen mit frischen Griffen (weiteres Resizen möglich) |
| Klick daneben / ESC / Toolbar-Wechsel | Modus verlassen, Griffe weg |

## Modus: RouteEdit (CTRL+Klick nahe einer Verbindung)

| Aktion | Erwartung |
|---|---|
| Betreten | CTRL+Klick auf die Verbindungskette (seit 03.11. — der einfache Klick selektiert nur): Die Waypoints der Verbindung erscheinen (gelbe Kreise) und sind greifbar; der Hover-Glow hatte das Ziel angezeigt. Alternativ erreicht der Doppelklick auf ein freies Segment den Modus gleich mit dem Einfügen |
| Klick / CTRL+Klick / Gummiband | Selektion MIT Waypoints (wie SELECT, nur dass Ws jetzt Klick-Ziele sind) |
| Drag auf W | Route umformen: W einzeln verschieben (snappt ans Grid) |
| DEL | Auswahl löschen; überlebende Verbindungen verlieren nur ihre gelöschten Ws (Prune). Stirbt die fokussierte Verbindung, endet der Modus mit ihr |
| Doppelklick auf ein freies Segment | NEUEN Waypoint an der Klickstelle einfügen (auch bei Direktverbindungen ohne Ws); kein Undo-Spam — Einfügen ist EIN Schritt |
| Klick auf eine andere Verbindung | Fokus wechselt dorthin (alte Ws verschwinden, neue erscheinen) |
| Klick daneben | Auswahl leeren UND Modus verlassen |
| Klick auf K/R/Text | Normale SELECT-Semantik, Modus verlassen |
| ESC | Modus verlassen; eine bestehende nicht-W-Auswahl BLEIBT erhalten |
| Toolbar-Wechsel | Modus verlassen |

## Editor-Overlay (Inline-Text-Editor)

Gilt für freien Text, externe Label, R-Inhalt, Verbindungs-Label und
Nachbearbeiten — EINE Komponente.

| Taste/Aktion | Erwartung |
|---|---|
| **ENTER** | Neue Zeile (mehrzeilig überall — User-Entscheidung 02.11.) |
| **CTRL+ENTER** | Commit — nur bei Änderung wirkt er sich aus (ein Undo-Punkt) |
| **ESC** | Verwerfen — das Modell bleibt unberührt, kein Objekt entsteht |
| Klick daneben | Commit — und der Klick wirkt NUR als Abschluss (nichts wird daneben selektiert); der nächste Klick wirkt ganz normal |
| Leere Eingabe + Commit | Stiller Verwurf — kein leeres Text-Objekt |
| Mausrad während des Editierens | Kamera eingefroren (der Commit soll nicht neben der Klickstelle landen) |
| Buchstaben-Editor (K) | Nimmt genau EIN Zeichen an (ENTER committet, kein CTRL nötig); Leeren + ENTER = Buchstabe löschen |

Nach dem Commit: freie Texte und externe Label sind normale Objekte —
verschiebbar (snappt), selektierbar, löschbar; externe Label wandern mit
ihrem Owner mit und kaskadieren beim Löschen.

## Bekannte Restchen (bewusst offen)

- Shortcuts K/R/C/T wirken NICHT in den langen Modi (Connecting, Resize,
  RouteEdit) — dort beenden nur Toolbar-Klick und ESC.
- Restaurierte Objekte landen visuell OBEN statt an ihrer Original-z-Position
  (daten-seitig ist z exakt wiederhergestellt).
- Verbindungs-Vorschau ohne Eckenrundung auf dem freien Abschnitt.
- Der „Zentriert"-Schalter für den R-Inhalt hat noch keinen UI-Ort
  (Default bleibt zentriert, vertikale Mitte gilt immer — der User hat
  eigene Ideen für den Schalter, Klärung ausstehend).
