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
