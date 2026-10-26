# FMC Editor — Projekt-Richtlinien & Architektur-Dokumentation

Dieses Dokument beschreibt die **tatsächlich implementierte** Architektur des
FMC-Editors (Stand: Kern-/Interaktions-Schicht, Java 21, Maven). Ziel ist eine
strikte Trennung zwischen Interaktionslogik (Core) und späterer UI (JavaFX).

> **Wichtig:** Die UI-Schicht existiert noch nicht. Das Projekt ist aktuell ein
> reiner, UI-unabhängiger Interaktions-Motor. Nicht implementierte, aber
> angedachte Features sind unten unter „Geplant / fehlt noch" aufgelistet —
> dort stehende Klassennamen sind **Ziel-Vision, nicht Realität**.

---

## 🏗️ Kernarchitektur (Ist-Zustand)

### 1. Datenmodell (Core, immutable)
* **Sealed Interface `DiagramObject`** mit den Records `Circle`, `Rectangle`
  und `Waypoint`. Alle sind immutable, threadsicher und frei von JavaFX-Imports.
  `ToolType` (SELECT, CIRCLE, RECTANGLE, CONNECT, NONE) steuert die Werkzeuge.
* **Datenhaltung im `DiagramService`** (`core.service`): implementiert das
  **read-only Interface `DiagramQuery`** (`findTopmostAt`, `findById`) —
  Details siehe Abschnitt 3.
* **`Connection`** (Record): Kante zwischen zwei DiagramObjects mit
  Waypoint-Liste; bewusst kein `DiagramObject` (kein Klick-Ziel).
* **`CameraInfo`** (Record): zoom, offsetX/offsetY, defaultLayerId — inklusive
  `screenToWorldX/Y`-Umrechnung. Bildschirm-Koordinaten werden **immer** am
  `InteractionContext` in Welt-Koordinaten umgerechnet, nie direkt in States.

### 2. Event-System (Sealed DomainEvents)
Die gesamte Kommunikation läuft über das sealed Interface `DomainEvent` mit
drei Zweigen:

* **`InputEvent`** (rohe Eingaben): `MousePressed` (mit clickCount, 2 =
  Doppelklick), `MouseDragged`, `MouseReleased`, `MouseScrolled`, `KeyPressed`,
  `KeyReleased`. Verwendet die eigenen Enums `CustomMouseButton` /
  `CustomKeyCode` — **keine** JavaFX-Typen in Events.
* **`ActionEvent`** (Absichten/Ergebnisse): `PanView`, `ZoomView`,
  `RequestCameraPan`, `SelectTool`, `SetSelection` (deklarative Auswahl-Menge,
  leer = abwählen), `AddElement` (fertiges Record inkl. UUID), `MoveObject`
  (Delta in Welt-Koordinaten), `ResizeObject` / `ResizeCircle` (nur Maße —
  das Zentrum bleibt garantiert erhalten), `CreateConnection` (mit
  Waypoint-Liste), `AddWaypoint`.
* **`DiagramEvent`** (Fakten von Diagram- & SelectionService): Antwort auf
  Requests — `ObjectAdded`, `ObjectMovedApplied`, `ObjectResizedApplied`
  (trägt das geänderte Objekt), `ConnectionEstablished`,
  `ConnectionRejected` (mit Begründung), `SelectionChanged`. Daran dockt die
  spätere UI an.

### 3. `DiagramService` — zentrale Datenhalterung (core.service)
Der `DiagramService` besitzt die Objekte und Verbindungen (LinkedHashMap,
Einfüge-Reihenfolge = z-Ordnung). **Der Event-Bus ist der einzige Schreibpfad:**
keine öffentlichen Mutatoren. Er konsumiert `ActionEvent`s und veröffentlicht
bestätigte Änderungen als `DiagramEvent`s. Verbindungen validiert er zentral
über steckbare `ConnectionRule`s (Standardregel: gleicher Typ nicht verbindbar
→ `ConnectionRejected`). Zweiphasige Initialisierung gegen die Zirkularität
Bus ↔ Service: `new DiagramService()` → `new InteractionEventBus(service, …)`
→ `service.attachTo(bus)`.

### 4. Auswahl, Grid & Handles (read-only im Interaktions-Kontext)
* **`SelectionService`** (`core.service`): hält die geordnete, duplikatfreie
  Auswahl. Schreibpfad nur über `SetSelection`-Requests (leer = abwählen),
  Bestätigung als `SelectionChanged`-Fakt; read-only-Sicht: `SelectionQuery`
  (`core.model`). Auswahl ist Sitzungszustand, bewusst NICHT im DiagramService.
* **`GridSpec`** (`core.model`, Record): Grid-Größe in Welt-Koordinaten;
  `snapToGrid(...)` rundet auf den nächsten Schnittpunkt. Heute Referenz für
  Minimalgrößen (kleinstes Objekt = 1 Zelle); Move-Snapping später.
* **`HandlePositions`** (`core.model`): reine Griff-Geometrie — Rechteck 8
  Griffe (Ecken + Seiten-Mitten), Kreis 4 (N/O/S/W), Waypoint keine.
  Zustandslos; von States (Hit-Test) und späterer UI (Rendering) genutzt.

### 5. `InteractionEventBus` — zwei parallele State-Machines
Der Bus ist die zentrale Schaltstelle (`implements EventBusPublisher`):

1. **Kamera-Machine zuerst:** verarbeitet `InputEvent`s und
   `RequestCameraPan`.
2. **Tool-Machine nur, wenn die Kamera nicht aktiv panned**
   (`cameraState.isActive()`); während des Pannings ist das Werkzeug
   pausiert.
3. **Subscriber-Versand:** Externe (spätere UI, DiagramService,
   SelectionService) abonnieren typgefiltert via
   `subscribe(Class<T>, Consumer<T>)`.

### 6. State Pattern — regelbasiert statt if-else
Alle Tool-States erben von **`BaseState`** (`core.state.rule`) und registrieren
im Konstruktor typsichere **Regeln**: `addRule(EventTyp, Guard, Action)` —
Guard/Action erhalten Event **und** `InteractionContext`. `handleInput`
dispatcht die erste passende Regel; greift keine, bleibt der Zustand erhalten.
**Neue Interaktionslogik gehört immer als Regel in einen State, nie als
if-else-Kaskade.**

* **Tool-States** (`core.state.impl`): `IdleToolState` (Zoom, Tool-Wechsel,
  Shortcuts K/R/C, Hit-Test, Doppelklick → `ResizeModeState`),
  `CircleToolState` / `RectangleToolState` / `ConnectToolState`,
  `PendingCreateOrPanState` (Klick-oder-Drag-Entscheidung), `MovingState`,
  `PendingMoveState`, `ConnectingState` (Waypoints sammeln, ESC bricht ab),
  `ResizeModeState` (Auswahl + Handles, Griff-Hit-Test) und
  `HandleDragState` (Resize-Geste: intern sammeln, Commit beim Loslassen).
* **Kamera-States**: `CameraIdleState`, `CameraPanningState`
  (`isActive() = true` signalisiert dem Bus die Tool-Pause).

### 7. Gesten-Erkennung (Schutz vor Event-Flut)
* **Drag-Schwellenwert** `DRAG_THRESHOLD_PIXELS = 4.0`: Erst ab Überschreiten
  entscheidet sich Klick vs. Drag/Pan (`PendingMoveState`,
  `PendingCreateOrPanState`).
* **Move: inkrementelle Deltas pro Drag-Segment:** `MovingState` feuert pro
  `MouseDragged` ein Delta seit der letzten Position; der DiagramService
  addiert zur absoluten Position. Der erste Drag-Event einer Geste wird vom
  `PendingMoveState`-Übergang konsumiert.
* **Resize: Commit erst beim Loslassen:** `HandleDragState` publiziert
  während des Ziehens nichts (Live-Vorschau ist reine UI-Sache) und feuert
  beim Loslassen genau EINEN `ResizeObject`/`ResizeCircle`-Request — Größe
  symmetrisch ums Zentrum (der Mittelpunkt bleibt damit am Gridpunkt),
  geklemmt auf mindestens eine Grid-Zelle.

### 8. Tests ohne UI
Die Interaktionslogik ist vollständig ohne JavaFX testbar:
`CircleCreationTest` und `CircleDragAndDropTest` (JUnit 5 + AssertJ) füttern
den Bus mit `InputEvent`s, verwenden Stubs für `DiagramQuery` und prüfen die
resultierenden `ActionEvent`s sowie die Zustandsübergänge.
`DiagramServiceTest` testet den Service als reinen Event-Flow (Request rein →
Fakt + Zustand raus), inklusive Ablehnung ungültiger Verbindungen.
`GridSpecTest`, `HandlePositionsTest`, `SelectionServiceTest` und
`ResizeModeStateTest` decken Grid-Mathematik, Griff-Geometrie, Auswahl und
die komplette Resize-Geste ab (kein Request während des Drags, genau ein
Commit beim Loslassen).
**Jedes neue Interaktions-Feature bekommt einen solchen Event-basierten Test.**

---

## 🏆 Goldene Regeln gegen Code-Verkrautung

1. **Einbahnstraßen-Kommunikation:** States lesen nur über den
   `InteractionContext` (read-only: `DiagramQuery`, `CameraInfo`, `GridSpec`,
   `SelectionQuery`, `EventBusPublisher`) und **publishen Absichten als
   `ActionEvent`s**. Sie manipulieren niemals direkt Daten oder rufen die UI.
   Die Datenhaltung liegt allein im `DiagramService`, die Auswahl im
   `SelectionService` — Schreiben nur über den Bus.
2. **Erst Test, dann UI:** Neue Interaktionslogik wird als reiner Event-Flow
   (Input rein → ActionEvent raus) implementiert und mit einem JUnit-Test ohne
   gestartete UI verifiziert, **bevor** irgendeine JavaFX-Klasse entsteht.
3. **Keine Gott-Klassen:** Maus-/Tastatur-Logik gehört in regelbasierte
   `BaseState`-Subklassen, neue Event-Typen als Records in die sealed
   Interfaces `InputEvent`/`ActionEvent`, Koordinaten-Logik in `CameraInfo`.
   JavaFX-Imports sind im Package `core` **tabu**.
4. **Kein JavaFX unter `core`:** Events nutzen `CustomMouseButton` /
   `CustomKeyCode`; die spätere UI übersetzt JavaFX-Events am Rand des Systems
   in diese Core-Typen.

---

## 🗺️ Geplant / fehlt noch (NICHT implementiert — Namen sind Vorschläge)

Beim Umsetzen dieser Features die obigen Goldenen Regeln einhalten:

* **UI-Schicht (JavaFX):** Es existiert kein `Main`, kein `ViewMapper`, keine
  Canvas-Renderlogik. `pom.xml` referenziert bereits `de.fmc.editor.Main` per
  `javafx-maven-plugin` — diese Klasse muss bei der UI-Anbindung angelegt
  werden. Die UI abonniert `ActionEvent`s und übersetzt JavaFX-Events in
  `InputEvent`s.
* **Weitere Validierung:** Neue Verbindungsregeln (Waypoint-Anzahl, Layer-Regeln
  u. ä.) als zusätzliche `ConnectionRule`s im `DiagramService` ergänzen —
  nie in den States.
* **Undo/Redo:** Kein Command-Pattern vorhanden. Falls eingeführt: pro Geste
  genau ein Command (siehe Abschnitt 5).
* **Layer-System:** Records haben bereits `layerId` und `CameraInfo` ein
  `defaultLayerId` — aber es gibt keine `Layer`-Objekte und keine
  Sichtbarkeitssteuerung.
* **Routing:** Verbindungslinien werden noch nicht geroutet (keine
  `RoutingStrategy`).
* **Mehrfachauswahl:** Infrastruktur steht (`SelectionService`, `SetSelection`,
  `SelectionChanged`). Offen: Shift-Klick, Gummiband-Auswahl und Gruppen-Move
  (pluraler `MoveObjects`-Request). Resize-Handles sind nur bei genau einem
  ausgewählten Objekt sinnvoll.

---

## 🧹 Hygiene / Bekannte Baustellen

* `src/test/java/.../CodePackerOptimized.java` ist ein Dev-Werkzeug (packt den
  Quellcode in `gesamtes_projekt_kontext.md`), **kein** Test. Es gehört nicht
  dauerhaft in die Test-Sources; die generierte Datei nicht committen.
