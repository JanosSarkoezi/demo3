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
  `RequestCameraPan`, `SelectTool`, `AddElement` (fertiges Record inkl.
  UUID), `MoveObject` (kumuliertes Delta in Welt-Koordinaten),
  `ResizeObject`, `CreateConnection` (mit Waypoint-Liste), `AddWaypoint`.
* **`DiagramEvent`** (Fakten des `DiagramService`): Antwort auf Requests —
  `ObjectAdded`, `ObjectMovedApplied`, `ObjectResizedApplied`,
  `ConnectionEstablished`, `ConnectionRejected` (mit Begründung). Daran dockt
  die spätere UI an.

### 3. `DiagramService` — zentrale Datenhalterung (core.service)
Der `DiagramService` besitzt die Objekte und Verbindungen (LinkedHashMap,
Einfüge-Reihenfolge = z-Ordnung). **Der Event-Bus ist der einzige Schreibpfad:**
keine öffentlichen Mutatoren. Er konsumiert `ActionEvent`s und veröffentlicht
bestätigte Änderungen als `DiagramEvent`s. Verbindungen validiert er zentral
über steckbare `ConnectionRule`s (Standardregel: gleicher Typ nicht verbindbar
→ `ConnectionRejected`). Zweiphasige Initialisierung gegen die Zirkularität
Bus ↔ Service: `new DiagramService()` → `new InteractionEventBus(service, …)`
→ `service.attachTo(bus)`.

### 4. `InteractionEventBus` — zwei parallele State-Machines
Der Bus ist die zentrale Schaltstelle (`implements EventBusPublisher`):

1. **Kamera-Machine zuerst:** verarbeitet `InputEvent`s und
   `RequestCameraPan`.
2. **Tool-Machine nur, wenn die Kamera nicht aktiv panned**
   (`cameraState.isActive()`); während des Pannings ist das Werkzeug
   pausiert.
3. **Subscriber-Versand:** Externe (spätere UI, DiagramService) abonnieren
   typgefiltert via `subscribe(Class<T>, Consumer<T>)`.

### 5. State Pattern — regelbasiert statt if-else
Alle Tool-States erben von **`BaseState`** (`core.state.rule`) und registrieren
im Konstruktor typsichere **Regeln**: `addRule(EventTyp, Guard, Action)` —
Guard/Action erhalten Event **und** `InteractionContext`. `handleInput`
dispatcht die erste passende Regel; greift keine, bleibt der Zustand erhalten.
**Neue Interaktionslogik gehört immer als Regel in einen State, nie als
if-else-Kaskade.**

* **Tool-States** (`core.state.impl`): `IdleToolState` (Zoom, Tool-Wechsel,
  Shortcuts K/R/C, Hit-Test), `CircleToolState` / `RectangleToolState` /
  `ConnectToolState`, `PendingCreateOrPanState` (Klick-oder-Drag-Entscheidung),
  `MovingState`, `PendingMoveState`, `ConnectingState` (Waypoints sammeln,
  ESC bricht ab).
* **Kamera-States**: `CameraIdleState`, `CameraPanningState`
  (`isActive() = true` signalisiert dem Bus die Tool-Pause).

### 6. Gesten-Erkennung (Schutz vor Event-Flut)
* **Drag-Schwellenwert** `DRAG_THRESHOLD_PIXELS = 4.0`: Erst ab Überschreiten
  entscheidet sich Klick vs. Drag/Pan (`PendingMoveState`,
  `PendingCreateOrPanState`).
* **Ein `MoveObject`-Event pro Drag-Geste:** `MovingState` akkumuliert das
  Delta ab Drag-Start und veröffentlicht erst beim Ziehen die Summe — nicht
  pro Mausbewegung. Diesen Schutz beibehalten.

### 7. Tests ohne UI
Die Interaktionslogik ist vollständig ohne JavaFX testbar:
`CircleCreationTest` und `CircleDragAndDropTest` (JUnit 5 + AssertJ) füttern
den Bus mit `InputEvent`s, verwenden Stubs für `DiagramQuery` und prüfen die
resultierenden `ActionEvent`s sowie die Zustandsübergänge.
`DiagramServiceTest` testet den Service als reinen Event-Flow (Request rein →
Fakt + Zustand raus), inklusive Ablehnung ungültiger Verbindungen.
**Jedes neue Interaktions-Feature bekommt einen solchen Event-basierten Test.**

---

## 🏆 Goldene Regeln gegen Code-Verkrautung

1. **Einbahnstraßen-Kommunikation:** States lesen nur über den
   `InteractionContext` (read-only: `DiagramQuery`, `CameraInfo`,
   `EventBusPublisher`) und **publishen Absichten als `ActionEvent`s**. Sie
   manipulieren niemals direkt Daten oder rufen die UI. Die Datenhaltung liegt
   allein im `DiagramService` — Schreiben nur über den Bus.
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
* **Resize & Routing:** `ResizeObject` existiert als Event, aber kein
  auslösender State; Verbindungslinien werden noch nicht geroutet (keine
  `RoutingStrategy`).
* **Selektion:** `PendingMoveState` enthält eine vorbereitete Klick-Auswahl
  (kommentiertes `ObjectSelected`-Event) — noch nicht aktiv.

---

## 🧹 Hygiene / Bekannte Baustellen

* `src/test/java/.../CodePackerOptimized.java` ist ein Dev-Werkzeug (packt den
  Quellcode in `gesamtes_projekt_kontext.md`), **kein** Test. Es gehört nicht
  dauerhaft in die Test-Sources; die generierte Datei nicht committen.
* Beim Erweitern von `ActionEvent` Bearbeitungs-Kommentare („In ActionEvent.java
  anpassen:") wieder entfernen.
