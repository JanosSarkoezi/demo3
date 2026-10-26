# DEVLOG

Chronik der Arbeitssessions. Format: Datum / Was / Warum so / Grenzen & Verbesserungskandidaten.
(Die aktuelle Architektur-Wahrheit steht in der AGENTS.md — hier nur Entscheidungen und ihre Begründung.)

## 26.10.2026 — AGENTS.md-Realitätsabgleich, DiagramService, Request-Renaming

**Was:**

- AGENTS.md von der Phantom-Architektur (CoreRegistry, ViewMapper, CommandHistory,
  RoutingStrategy …) auf den tatsächlichen Ist-Zustand umgeschrieben: echte Klassen
  und Regeln als Ist-Zustand, fehlende Features unter explizit markiertem „Geplant".
- `DiagramService` (`core.service`) eingeführt: zentrale Datenhalterung als reiner
  Event-Konsument (Requests rein → Fakten raus). Dazu das `Connection`-Record,
  steckbare `ConnectionRule`-Validierung (Standard: gleicher Typ nicht verbindbar
  → `ConnectionRejected`) und der `DiagramEvent`-Zweig mit Fakten
  (`ObjectAdded`, `ObjectMovedApplied`, `ObjectResizedApplied`,
  `ConnectionEstablished`, `ConnectionRejected`).
- Alle ActionEvents auf Imperativ umgetauft (`ElementCreated` → `AddElement`,
  `ConnectionCreated` → `CreateConnection` u. a.; `RequestCameraPan` blieb),
  60 Referenzen über 13 Dateien — rein mechanisch, Verhalten unverändert.

**Warum so:**

- Die alte Doku beschrieb Klassen, die nie existierten — Agenten hätten neuen Code
  an einer Phantom-Struktur orientiert. Erst Doku gegen Code auditieren, dann trauen.
- Keine Registry mit öffentlichen Mutatoren: sonst zwei Schreibpfade → Einbahnstraße
  (Goldene Regel 1) gebrochen. Der Event-Bus bleibt der einzige Schreibweg; Bus↔Service-
  Zirkularität über zweiphasige Initialisierung gelöst
  (`new DiagramService()` → `new InteractionEventBus(service, …)` → `service.attachTo(bus)`).
- Request/Fact-Trennung (CQRS-Stil): Requests im Imperativ sind Wünsche, Fakten im
  Präteritum sind vollzogene Wahrheit. Die spätere UI kann rein auf Fakten zeichnen
  und damit nie vom Modell abweichen; das Fakten-Journal ist zugleich die Basis für
  künftiges Undo/Redo und Persistenz.

**Grenzen & Verbesserungskandidaten:**

- Resize nur für Rectangles; andere Typen erhalten keinen Resize-Fakt.
- Kein Undo/Redo, kein Layer-System, kein Routing, keine UI
  (siehe AGENTS.md „Geplant / fehlt noch").
- Offene Kosmetik: Bearbeitungskommentar in `ActionEvent.java`,
  `CodePackerOptimized.java` liegt noch in den Test-Sources.
- `mvn test` zum Session-Ende: 9/9 grün (7 × DiagramServiceTest, CircleCreationTest,
  CircleDragAndDropTest).

## 26.10.2026 (2. Arbeitsblock) — Resize mit Handles, Auswahl- & Grid-Infrastruktur

**Was:**

- Resize-Interaktion komplett: Doppelklick wählt aus und öffnet den
  `ResizeModeState`; Griff-Geometrie via `HandlePositions` (Rechteck 8 Griffe =
  Ecken + Seiten-Mitten, Kreis 4 = N/O/S/W); Ziehen im `HandleDragState`
  publiziert nichts, beim Loslassen genau EIN `ResizeObject`/`ResizeCircle`.
- Auswahl-Infrastruktur: `SelectionService` (Schreibpfad nur via
  `SetSelection`, Fakt `SelectionChanged`, read-only `SelectionQuery` im
  `InteractionContext`) — vorbereitet für Mehrfachauswahl/Gruppen-Move ohne
  künftige Umbenennung.
- `GridSpec` (Welt-Koordinaten): heute Minimalgröße = 1 Zelle pro Dimension;
  `snapToGrid(...)` für späteres Move-Snapping.
- `ObjectResizedApplied` trägt nun das geänderte Objekt (analog `ObjectAdded`)
  — Kreis & Rechteck teilen typsicher einen Fakt.

**Warum so:**

- Handles sind reine Griff-Punkte, die Mathematik ist symmetrisch ums Zentrum:
  Resize-Requests tragen nur Maße, nie Positionen — der Mittelpunkt bleibt
  damit garantiert an seinem Platz. Zentren-Alignment ans Grid ist Aufgabe des
  späteren Move-Snappings; Resize darf es niemals brechen.
- Commit-on-Release: Backend während des Ziehens stumm, Live-Vorschau ist
  reine UI-Sache; ein Request pro Geste hält das künftige Fakten-Journal
  (Undo/Redo) sauber.
- Auswahl als deklarative Menge (`SetSelection`) statt inkrementeller Events:
  Shift-Klick, Gummiband etc. konvergieren im State und brauchen nie neue
  Event-Typen. Auswahl liegt im SelectionService, NICHT im DiagramService —
  sie ist Sitzungszustand und darf nicht zum Modellinhalt (Persistenz) werden.

**Grenzen & Verbesserungskandidaten:**

- Griff-Grab-Radius fest (6 px) und Mindestgröße fest (1 Grid-Zelle) — beides
  benannte Konstanten, leicht anpassbar.
- Offen: Mehrfachauswahl-Flow (Shift-Klick, Gummiband), Gruppen-Move,
  Move-Snapping, sichtbares Grid, Resize von Waypoints.
- Störfälle beseitigt: Bearbeitungskommentar aus `ActionEvent.java` entfernt;
  AGENTS.md-Abschnitt „ein MoveObject-Event pro Geste" korrigiert (real:
  inkrementelle Deltas pro Drag-Segment, Kumulation im DiagramService).
- `mvn test`: 26/26 grün (DiagramServiceTest 9, ResizeModeStateTest 7,
  HandlePositionsTest 3, SelectionServiceTest 3, GridSpecTest 2,
  CircleCreationTest 1, CircleDragAndDropTest 1).
