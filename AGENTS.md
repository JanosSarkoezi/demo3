# FMC Editor — Projekt-Richtlinien & Architektur-Dokumentation

Dieses Dokument beschreibt die **tatsächlich implementierte** Architektur des
FMC-Editors (Stand: Kern-/Interaktions-Schicht, Java 21, Maven). Ziel ist eine
strikte Trennung zwischen Interaktionslogik (Core) und späterer UI (JavaFX).

> **Wichtig:** Die UI-Schicht existiert noch nicht. Das Projekt ist aktuell ein
> reiner, UI-unabhängiger Interaktions-Motor. Nicht implementierte, aber
> angedachte Features sind unten unter „Geplant / fehlt noch" aufgelistet —
> dort stehende Klassennamen sind **Ziel-Vision, nicht Realität**.

---

## 🎯 Produktvision (verbindlich — Quelle: `spec.md`)

Die `spec.md` im `doc/`-Verzeichnis beschreibt die Zielversion des Editors. Kernanforderungen:

* **Canvas:** Panning; Zoom am Mauszeiger per Mausrad (implementiert).
* **Werkzeuge (Sidebar + Shortcuts):** Kreis (K), Rechteck (R), Verbindung,
  Text.
* **Verbindungen:** Klick auf Start-Objekt, 0-n Waypoints (W), Klick auf
  Ziel-Objekt. Segmente an den Ecken abgerundet, am Ziel-Ende ein Pfeil
  (gerichtete Kante).
* **Selektion & Move:** Auswahl umfasst 1..n Objekte (Ks, Rs und Ws). Beim
  Verschieben snappt die Position an Grid-Kreuzungspunkte.
* **Resize:** nur Ks und Rs (symmetrisch ums Zentrum — wie implementiert).
* **Text (noch unmodelliert — größte offene Funktionslücke):**
  * K enthält genau einen Buchstaben (auch in der Altvorlage demo2 NICHT
    umgesetzt — echte Neuentwicklung).
  * R enthält mehrzeiligen, zentrierbaren Text.
  * Externe Texte: 1..n pro K, 0..n pro R — als eigener DiagramObject-Typ
    `Text` mit `parentObjectId`-Assoziation (demo2-Modell), damit einheitlich
    selektierbar/verschiebbar und EINE Auswahl-Menge genügt (demo2 hatte zwei
    parallele Selection-Sets — nicht kopieren).
* **Persistenz (JSON, Beispiel siehe `doc/spec.md`):** Das Format ist der
  Gson-Export der Altvorlage demo2 (`DiagramData`): `objects` uniformly als
  Bounding-Box (x/y/width/height), `connections` als Map mit
  sourceId/targetId/waypointIds, `layers` mit Sichtbarkeits-Flag — plus
  `texts` (demo2 hatte sie im DTO, das spec-Beispiel zeigt sie noch nicht).

### Aus der Spec abgeleitete Modell-Entscheidungen

* **Waypoint-Referenzierung per ID:** Waypoints sind eigenständige
  DiagramObjects (ID, Position, layerId); Connections referenzieren sie per
  `waypointIds` — so hat es demo2 praktiziert. ✅ Implementiert: Der
  DiagramService speichert Waypoints beim `CreateConnection` als Objekte
  (je ein `ObjectAdded`-Fakt); die Connection hält nur die IDs.
* **Circle-Speicherung — typisiertes Modell behalten:** JSON nutzt
  width/height, weil demo2 EIN `FmcObject`-Record mit Bounding-Box für alle
  Typen nutzte. Empfehlung: Das sealed, typisierte Modell behalten
  (compiler-erzwungene Exhaustivität statt Enum-Switches) und am
  Serialisierungsrand per DTO konvertieren (`Circle.radius` ↔ width/height =
  Durchmesser). Offen: ob x/y im Format Ecke-oben-links ist (Modell rechnet
  resize-seitig zentriert).

---

## 🏗️ Kernarchitektur (Ist-Zustand)

### 1. Datenmodell (Core, immutable)
* **Sealed Interface `DiagramObject`** mit den Records `Circle`, `Rectangle`,
  `Waypoint` und `Text`. Alle sind immutable, threadsicher und frei von JavaFX-Imports.
  `ToolType` (SELECT, CIRCLE, RECTANGLE, CONNECT, TEXT) steuert die Werkzeuge.
  * **`Circle`** trägt `label` (innerer Text, per Konvention 1 Buchstabe —
    Modell permissiv, UI erzwingt), **`Rectangle`** trägt `content` +
    `centered` (Default true; die innere Text-Box ist von der R-Geometrie
    abgeleitet — Resize gratis). Kompatibilitäts-Konstruktoren ohne Text-
    Felder halten Bestands-Fixtures kompilierbar; `moved()`/Resize tragen die
    Text-Felder mit (Kompat-Konstruktor dort wäre ein stiller Datenverlust).
  * **`Text`** (Record): eigener Text mit rechteckiger Box — x/y ist der
    MITTELPUNKT (uniform mit K/R: Hit, Band, Move, Snap nach derselben
    Konvention). `ownerId` referenziert ein K/R (externes Label, 0..n), eine
    Connection (Verbindungs-Label, 0..1) oder nichts (freier Text).
    `anchor` (nullable): null = absolut positioniert; gesetzt = an einem
    Verbindungsabschnitt verankert (Position vom Service abgeleitet bei
    Erzeugung/Re-Ankerung). **`TextAnchor`** (segmentIndex, t, offsetX/Y):
    Index klemmt bei W-Löschung, t und Offset bleiben als Absicht erhalten.
* **Datenhaltung im `DiagramService`** (`core.service`): implementiert das
  **read-only Interface `DiagramQuery`** (`findTopmostAt`, `findById`) —
  Details siehe Abschnitt 3.
* **`Connection`** (Record): Kante zwischen zwei DiagramObjects; referenziert
  ihre Waypoints per `waypointIds` — die Waypoints liegen als eigenständige
  Objekte im DiagramService (keine zweite Kopie). Bewusst kein `DiagramObject`
  (kein Klick-Ziel).
* **`CameraInfo`** (Record): zoom, offsetX/offsetY, defaultLayerId — inklusive
  `screenToWorldX/Y`-Umrechnung sowie `panBy`/`zoomAt` (reine
  Koordinaten-Mathematik; der Bus aktualisiert seinen Snapshot bei
  `PanView`/`ZoomView`). Bildschirm-Koordinaten werden **immer** am
  `InteractionContext` in Welt-Koordinaten umgerechnet, nie direkt in States.

### 2. Event-System (Sealed DomainEvents)
Die gesamte Kommunikation läuft über das sealed Interface `DomainEvent` mit
drei Zweigen:

* **`InputEvent`** (rohe Eingaben): `MousePressed` (mit clickCount, 2 =
  Doppelklick, und `controlDown` für den Selektions-Toggle / späteres
  Gummiband; public Kompatibilitäts-Konstruktor ohne Modifier-Info),
  `MouseDragged`, `MouseReleased`, `MouseScrolled`, `KeyPressed`,
  `KeyReleased`. Verwendet die eigenen Enums `CustomMouseButton` /
  `CustomKeyCode` — **keine** JavaFX-Typen in Events.
* **`ActionEvent`** (Absichten/Ergebnisse): `PanView`, `ZoomView`,
  `RequestCameraPan`, `SelectTool`, `SetSelection` (deklarative Auswahl-Menge,
  leer = abwählen), `AddElement` (fertiges Record inkl. UUID),
  `DeleteObjects` (zentrales Löschen inkl. Kaskade), `MoveObject`
  (Delta in Welt-Koordinaten), `MoveObjectTo` (absolute Position —
  Undo-Inverse ohne Float-Drift), `MoveObjects` (Gruppen-Delta; die Geste
  nutzt immer den pluralen Request, n=1 ist der Spezialfall),
  `ResizeObject` / `ResizeCircle` (nur Maße —
  das Zentrum bleibt garantiert erhalten), `CreateConnection` (mit
  Waypoint-Liste — der Service speichert die Waypoints als Objekte und
  referenziert sie per ID), `AddConnection` (Restore mit Original-ID),
  `RemoveConnections` (ohne Kaskade — präzises Inverse), Text-Requests:
  `SetCircleLabel`, `SetRectangleContent` (inkl. centered-Schalter),
  `UpdateText` (nur Inhalt/Box), `SetAnchor` (nullable TextAnchor — null =
  Anker lösen, Position bleibt), `AddAnchoredText` (Service leitet x/y aus
  dem Anker ab), `RequestUndo` /
  `RequestRedo` (UI übersetzt CTRL+Z / SHIFT+CTRL+Z hierher).
* **UI-Intents auf dem Bus (noch ohne Service-Konsument):** `EditTextOfObject`
  (innerer Text K/R), `EditText` (bestehendes Text-Objekt), `CreateTextFor`
  (ownerId null = freier Text), `CreateConnectionText` (mit projiziertem
  Anker). Das Text-Werkzeug emittiert sie; die spätere UI abonniert sie,
  öffnet den Inline-Editor an der letzten Klick-Position (behält sie selbst)
  und publiziert beim Commit die passenden Requests. ESC im Editor verbraucht
  die UI selbst — erreicht den Core nie.
* **`DiagramEvent`** (Fakten von Diagram- & SelectionService): Antwort auf
  Requests — `ObjectAdded`, `ObjectMoved` (Vor- UND Nach-Position),
  `ObjectsMoved` (Batch: ein Eintrag je Objekt einer Gruppenverschiebung),
  `ObjectResized` (Vor- UND Nach-Zustand), `ObjectTextChanged` (Vor- UND
  Nach-Zustand: innerer Text von K/R oder Inhalt/Box/Anker/Position eines
  Text-Objekts), `ConnectionEstablished`,
  `ConnectionRejected` (mit Begründung), `SelectionChanged`; plus
  Batch-Fakten einer Lösch-Geste mit vollständigem Zustand (Undo-Grundlage):
  `ObjectsRemoved` (inkl. kaskadierter Waypoints), `ConnectionsRemoved`,
  `ConnectionsUpdated` (previous + updated index-parallel). **Design-Regel:
  Ein Modell-Fakt ist genau dann undo-fähig, wenn er den vollständigen
  Vor-Zustand trägt.** Daran dockt die spätere UI an.

**Namenskonvention:** Requests im Imperativ, Verb zuerst (`AddElement`,
`MoveObject`) — Fakten im schlichten Präteritum, Subjekt zuerst
(`ObjectAdded`, `ObjectMoved`, `ConnectionEstablished`). Keine Suffixe wie
„Applied": Dass ein Fakt vollzogen ist, trägt seine Zugehörigkeit zur
DiagramEvent-Familie bereits selbst. Die Verb-Familien Add und Create sind
bewusst verschieden: `AddElement` fügt ein fertiges Record ein (Restore-Pfad
inklusive), `CreateConnection` prüft und erzeugt eine NEUE Connection mit
frischer ID.

### 3. `DiagramService` — zentrale Datenhalterung (core.service)
Der `DiagramService` speichert Objekte als Zwei-Struktur-Kombination
(User-Idee): `HashMap` für O(1)-Lookup per UUID plus explizite `zOrder`-Liste
(UUIDs, Index 0 = unten). Nur Hinzufügen/Entfernen synchronisieren beide
Strukturen — Move/Resize berühren allein die Map. `ObjectsRemoved` trägt die
z-Indizes zum Lösch-Zeitpunkt (Snapshot VOR der ersten Entfernung der Geste);
`RestoreObjects` fügt aufsteigend nach Original-Index wieder ein — die
LIFO-Disziplin des Undo macht alte Indizes selbstheilend.
**Der Event-Bus ist der einzige Schreibpfad:**
keine öffentlichen Mutatoren. Er konsumiert `ActionEvent`s und veröffentlicht
bestätigte Änderungen als `DiagramEvent`s. Verbindungen validiert er zentral
über steckbare `ConnectionRule`s (Standardregel: gleicher Typ nicht verbindbar
→ `ConnectionRejected`). Zweiphasige Initialisierung gegen die Zirkularität
Bus ↔ Service: `new DiagramService()` → `new InteractionEventBus(service, …)`
→ `service.attachTo(bus)`.
* **Text-Schreibpfad:** `SetCircleLabel`/`SetRectangleContent`/`UpdateText`/
  `SetAnchor`/`AddAnchoredText` → je ein `ObjectTextChanged`-Fakt (bzw.
  `ObjectAdded`). Die Anker-Position leitet der Service zentral ab
  (`polyPoints` + Projektion — Geometrie-Wissen liegt nie in den States);
  `SetAnchor(null)` löst den Anker und hält die Position.
* **Verankerte Texte sind nicht delta-verschiebbar:** `MoveObjects`/
  `MoveObjectTo` überspringen Texte mit Anker still (ihre Position ist
  abgeleitet; Änderung nur über Re-Ankerung).
* **Lösch-Kaskade Phase 1b:** Texte, deren Owner (Objekt ODER Connection)
  doomed ist, kaskadierten mit — als sichtbare Fakten in `ObjectsRemoved`,
  nie versteckt (demo2-Lektion). Das gilt für K/R-Owner wie für
  Connection-Owner.
* **Text-Queries** (Default-Methoden auf `DiagramQuery`, überschrieben im
  Service): `findTextIdsOwnedBy(ownerId)` (Move-Union + 0..1-Kardinalität),
  `findConnectionNear(x, y, tolerance)` (Punkt-zu-Kette-Distanz — die
  nächstgelegene Connection gewinnt), `projectOntoConnection(connectionId, x, y)` →
  `TextAnchor` (Offset = Klick − Ankerpunkt: Das Label sitzt, wo geklickt
  wurde — Kreation frei, Snapping greift erst beim Verschieben).

### 4. Auswahl, Grid & Handles (read-only im Interaktions-Kontext)
* **`SelectionService`** (`core.service`): hält die geordnete, duplikatfreie
  Auswahl. Schreibpfad nur über `SetSelection`-Requests (leer = abwählen),
  Bestätigung als `SelectionChanged`-Fakt; read-only-Sicht: `SelectionQuery`
  (`core.model`). Auswahl ist Sitzungszustand, bewusst NICHT im DiagramService.
  Invariante „keine toten IDs": Der Service abonniert `ObjectsRemoved` und
  staubt gelöschte IDs automatisch heraus. Identische Mengen feuern KEINEN
  Fakt (Plain-Press auf bereits Selektiertes kollabiert die Auswahl nicht
  und rauscht nicht).
* **`UndoService`** (`core.service`): Sitzungs-Undo/Redo über den Bus —
  Undo ist nur ein weiterer Schreiber. Der Service leitet aus den Modell-
  Fakten mechanisch inverse Requests ab (kein eigenes Kaskade-Wissen —
  demo2-Lektion), sammelt Ops pro Gesten-Eintrag (MousePressed/Released/
  KeyPressed sind Grenzen; MouseDragged bewusst nicht, damit Move-Segmente
  verschmelzen), fährt einen Replay-Guard gegen Selbst-Erfassung und leert
  den Redo-Stack bei neuen Aktionen. Kamera- und Auswahl-Fakten bleiben
  bewusst draußen (Sitzungszustand, kein Modellinhalt).
  * **TextChanged-Op:** `ObjectTextChanged` → Op mit previous/updated; Merge
    bei aufeinanderfolgenden Änderungen desselben Objekts (Re-Ankerung in
    Drag-Segmenten = EIN Undo-Punkt). Inverse wie Replay laufen mechanisch
    über `publishTextState`: K → `SetCircleLabel`, R → `SetRectangleContent`,
    Text → `UpdateText` + `SetAnchor` (bei absoluten Texten zusätzlich
    `SetAnchor(null)` + `MoveObjectTo` für die Original-Position — kein
    Float-Drift; bei verankerten leitet der Service aus der per LIFO
    wiederhergestellten Geometrie ab).
* **`GridSpec`** (`core.model`, Record): Grid-Größe in Welt-Koordinaten;
  `snapToGrid(...)` rundet auf den nächsten Schnittpunkt. Heute Referenz für
  Minimalgrößen (kleinstes Objekt = 1 Zelle); Move-Snapping später.
* **`HandlePositions`** (`core.model`): reine Griff-Geometrie — Rechteck und
  Text-Box 8 Griffe (Ecken + Seiten-Mitten, gemeinsame `ofBox`), Kreis 4
  (N/O/S/W), Waypoint keine.
  Zustandslos; von States (Hit-Test) und späterer UI (Rendering) genutzt.

### 5. `InteractionEventBus` — zwei parallele State-Machines
Der Bus ist die zentrale Schaltstelle (`implements EventBusPublisher`):

1. **Kamera-Machine zuerst:** verarbeitet `InputEvent`s und
   `RequestCameraPan` (trägt die auslösende Maustaste — nur deren Loslassen
   beendet das Panning).
2. **Tool-Machine nur, wenn die Kamera nicht aktiv panned**
   (`cameraState.isActive()`); während des Pannings ist das Werkzeug
   pausiert.
3. **Kamera-Snapshot aktuell halten:** Der Bus konsumiert `PanView`/`ZoomView`
   selbst und ersetzt seinen `CameraInfo`-Snapshot via `panBy`/`zoomAt` —
   jeder folgende Event-Durchlauf rechnet mit der aktuellen Kamera.
   Read-only-Zugriff für Tests/UI: `getCameraInfo()`.
4. **Subscriber-Versand:** Externe (spätere UI, DiagramService,
   SelectionService) abonnieren typgefiltert via
   `subscribe(Class<T>, Consumer<T>)`.

### 6. State Pattern — regelbasiert statt if-else
Alle Tool-States erben von **`BaseState`** (`core.state.rule`) und registrieren
im Konstruktor typsichere **Regeln**: `addRule(EventTyp, Guard, Action)` —
Guard/Action erhalten Event **und** `InteractionContext`. `handleInput`
dispatcht die passende Regel; greift keine, bleibt der Zustand erhalten.
**Neue Interaktionslogik gehört immer als Regel in einen State, nie als
if-else-Kaskade.**

**Guard-Disjunktions-Invariante:** Für jedes (Event, Kontext)-Paar darf
höchstens EINE Regel eines States greifen — die Registrierungsreihenfolge darf
niemals Verhalten tragen. `BaseState` erzwingt das: Mehrfach-Treffer scheitern
sofort mit `IllegalStateException` (State, Event, Trefferzahl in der Meldung);
ein `BaseStateTest` beweist die Invariante direkt. Konsequenzen:

* Guards werden explizit vervollständigt statt auf Reihenfolge zu vertrauen
  (z. B. Doppelklick `clickCount == 2` vs. einfacher Klick `clickCount == 1`;
  pro Shortcut-Key genau eine Regel statt `default`-Switch).
* Guards müssen seiteneffektfrei sein (der Dispatcher wertet alle aus).
* Bewusste „keine-Regel-passt"-Reste sind erlaubt — der Zustand bleibt einfach.

* **`ToolState`** (`core.state.impl`, abstrakt): gemeinsame Basis aller
  Werkzeug-States — zentralisiert Werkzeug-Wechsel per `SelectTool`-Event
  (Toolbar) und per Shortcuts K/R/C/T mit reflexivem self-Check (im eigenen
  Werkzeug bleibt der State). Guard-disjunkt: eine Regel pro Key, kein
  default-Switch (zuvor in jedem State dupliziert — größte demo2-Baustelle).
* **Tool-States** (`core.state.impl`): `IdleToolState` (Zoom, Hit-Test,
  Selektion bei Press — einfach = exklusiv,
  Ctrl+Klick = Toggle, Leere/ESC = leeren —, DEL = Auswahl löschen,
  Doppelklick → `ResizeModeState`; movingIds = Auswahl bzw. Ziel ∪
  `findTextIdsOwnedBy` — externe Label wandern mit ihrem Owner mit, auch
  ohne eigene Selektion),
  `CircleToolState` / `RectangleToolState` / `ConnectToolState`,
  `TextToolState` (EINE Press-Regel, Verzweigungen in der Aktion: Klick auf
  K/R → `EditTextOfObject`; Ctrl+Klick auf K/R → `CreateTextFor(owner)`;
  Klick auf Text → `EditText`; Verbindungsnähe → bestehendes Label
  bearbeiten [Kardinalität 0..1] sonst `CreateConnectionText` mit
  projiziertem Anker; Leere → `CreateTextFor(null)`; ESC → Idle),
  `PendingCreateOrPanState` (Klick-oder-Drag-Entscheidung), `MovingState`,
  `PendingMoveState`, `PendingBoxSelectState` (Gummiband: Ctrl+Leerraum-Drag,
  stumm bis zum Release, EIN `SetSelection`), `ConnectingState` (Waypoints sammeln, ESC bricht ab),
  `ResizeModeState` (Auswahl + Handles, Griff-Hit-Test) und
  `HandleDragState` (Resize-Geste: intern sammeln, Commit beim Loslassen).
* **Kamera-States**: `CameraIdleState`, `CameraPanningState`
  (`isActive() = true` signalisiert dem Bus die Tool-Pause).

### 7. Gesten-Erkennung (Schutz vor Event-Flut)
* **Drag-Schwellenwert** `DRAG_THRESHOLD_PIXELS = 4.0` (zentral in `BaseState`):
  Erst ab Überschreiten entscheidet sich Klick vs. Drag/Pan/Gummiband
  (`PendingMoveState`, `PendingCreateOrPanState`, `PendingBoxSelectState`).
* **Move: inkrementelle Deltas pro Drag-Segment:** `MovingState` feuert pro
  `MouseDragged` ein Delta seit der letzten Position — immer als pluraler
  `MoveObjects`-Request (n=1 ist der Spezialfall); der DiagramService
  addiert zur absoluten Position und publiziert EINEN `ObjectsMoved`-
  Batch-Fakt pro Segment. Der erste Drag-Event einer Geste wird vom
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
Commit beim Loslassen). `TextModelTest` (Service-Ebene: Hit/Band für
Text-Boxen, Move-Skip verankert, Mitwandern externer Label, Lösch-Kaskade
inkl. Undo, innere Texte mit Undo/Redo, Anker-Ableitung/-Klemmung,
Verbindungs-Geometrie) und `TextToolTest` (Gesten-Intents, 0..1-Kardinalität,
KEY-T/ESC) decken das Text-Feature ab — **92 Tests gesamt, alle grün**.
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
* **Undo/Redo:** ✅ Core implementiert (`UndoService`, `RequestUndo`/
  `RequestRedo` — die UI übersetzt CTRL+Z / SHIFT+CTRL+Z am Systemrand,
  semantisch wie Toolbar → `SelectTool`). Fakten-basiert statt
  Command-Klassen: Modell-Fakten tragen den Vor-Zustand, der Service leitet
  inverse Requests mechanisch ab; pro Geste genau ein History-Punkt.
  z-Ordnung wird beim Restore exakt wiederhergestellt (zOrder-Liste +
  Original-Indizes). Offen: UI-Anbindung.
* **Layer-System:** Records haben bereits `layerId` und `CameraInfo` ein
  `defaultLayerId` — aber es gibt keine `Layer`-Objekte und keine
  Sichtbarkeitssteuerung. *(Bestätigt durch spec.md: Sichtbarkeits-Steuerung,
  Ebenen „Standard" und „Wegpunkte", letztere default unsichtbar.)*
* **Routing:** Verbindungslinien werden noch nicht geroutet (keine
  `RoutingStrategy`). demo2 zeigt die Ziel-Form: Strategy-Interface mit
  Straight/Orthogonal/RoundedCorner — reine View-Schicht, 1:1 portierbar.
* **Waypoint-Nachbearbeitung:** Doppelklick auf eine Verbindung fügt einen
  Waypoint an der Klick-Position ein (demo2: Einfüge-Index per Geometrie —
  diese Logik gehört in den Core und ist testbar); Klick auf Verbindung
  blendet den Wegpunkt-Layer ein, Drag ins Leere wieder aus. Genau hier
  kehrt das am 27.10. entfernte `AddWaypoint`-Event als Request zurück
  (damals tot — jetzt mit echtem Anwendungsfall).
* **Move-Snapping:** spec.md fordert Grid-Snapping beim Verschieben.
  `GridSpec.snapToGrid(...)` existiert, muss in den Move-Pfad integriert
  werden. Demo2-Semantik: Zielposition des primär gezogenen Objekts snappen,
  Gruppe folgt dem resultierenden Delta — zentral in `GridSpec`, nicht wie
  in demo2 inline dupliziert. Für Texte gilt die Mittelpunkt-Konvention
  (Text.x/y snapt), hängt aber an diesem selben Block.
* **Text-Feature:** ✅ Core implementiert (28.10.) — Modell (`Text` mit
  Mittelpunkt-Konvention, `TextAnchor`, `Circle.label`,
  `Rectangle.content/centered`), Service-Schreibpfad inkl. Anker-Ableitung,
  Lösch-Kaskade für Owned-Texte, Undo/Redo (`ObjectTextChanged` +
  mechanische Inversion), `TextToolState` mit UI-Intents,
  `ToolState`-Zentralisierung als Vorbereitung. Offen:
  * **UI-Editor:** Die Intents (`EditTextOfObject`, `EditText`,
    `CreateTextFor`, `CreateConnectionText`) haben noch keinen Konsumenten —
    Inline-Editor mit ENTER-Commit/ESC-Verwerfen ist UI-Meilenstein
    („Entschiedene Fragen" Nr. 4). Die Default-Position externer Labels
    („unterhalb des Objekts") berechnet die UI aus ihrer faktensynchronisierten
    Sicht.
  * **W-Gleiten:** Verankerte Texte leiten x/y bei Erzeugung/Re-Ankerung ab;
    das „Gleiten mit Waypoint-Verschiebungen" (spec.md) folgt mit der
    W-Sichtbarkeit — heute sind W-Moves unmöglich, x/y kann nicht stale
    werden. Bei der Implementierung in `onMoveObjects` als Teil desselben
    `ObjectsMoved`-Batch-Fakts ableiten (keine versteckte Kaskade).
  * **Serialisierung:** `Text`/`label`/`content` ins DTO (Persistence-Block
    unten), EINE Auswahl-Menge statt demo2s zwei parallelen Sets ist bereits
    durch das UUID-basierte SelectionService-Design gewährleistet.
* **Persistenz/Serialisierung:** JSON-Format = demo2s Gson-Export (Beispiel:
  `doc/spec.md`), plus `texts`; DTO an der Serialisierungsgrenze konvertiert
  (`Circle.radius` ↔ width/height), Connections per `waypointIds`.

### ✅ Entschiedene Fragen (beantwortet über die Altvorlage `../demo2`)

1. **Löschen:** Kaskadierend — Objekt weg = externe Texte weg, Connections
   weg (inkl. deren Waypoints), ID aus Waypoint-Listen anderer Connections
   entfernen. Eine Lösch-Geste = ein Undo-Punkt (Composite).
2. **Undo/Redo:** Ja, Anforderung. Pro Geste genau ein Command; Redo-Stack
   leert sich bei neuer Aktion.
3. **Move-Semantik:** Waypoints bleiben stehen; Connections folgen ihren
   Endpunkt-IDs automatisch; externe Texte wandern mit — in demo3 als
   eigener Fakt vom DiagramService publiziert (demo2 löste das versteckt in
   der Registry — nicht kopieren).
4. **Text-UX:** Klick erzeugt Platzhalter und öffnet sofort den
   Inline-Editor; ENTER committet (nur bei Änderung ein Undo-Punkt), ESC
   verwirft, Klick daneben committet; Doppelklick auf Text = erneut
   editieren.
5. **Mehrfachauswahl:** Selektion erfolgt bei Maus-Druck (nicht erst beim
   Loslassen): einfacher Klick = exklusive Auswahl (ersetzt die bisherige),
   Ctrl+Klick = Objekt zusätzlich aufnehmen bzw. bei bereits Selektiertem
   wieder herausnehmen (Toggle), Klick auf leere Fläche / ESC = Auswahl
   leeren. Gummiband: Ctrl+Leerraum-Drag (implementiert). Gruppen-Move:
   Plain-Press auf Selektiertes verschiebt die Menge (implementiert).
   Löschen: ausschließlich DEL-Taste auf die aktuelle Auswahl.

⚠️ Warnung aus demo2 (Verkrautungs-Beweis, NICHT kopieren): Kaskade und
Undo-Wissen drifteten auseinander — Undo eines Objekt-Löschens verlor dort
die mitgelöschten Texte dauerhaft, weil `DeleteObjectCommand` sie nicht
mitgesichert hatte. Konsequenz für demo3: Kaskaden leben als sichtbare
Fakten im DiagramService (einziger Schreibpfad); Commands bauen darauf auf
und bauen Kaskade-Wissen nie doppelt nach.

---

## 🧹 Hygiene / Bekannte Baustellen

* `src/test/java/.../CodePackerOptimized.java` ist ein Dev-Werkzeug (packt den
  Quellcode in `gesamtes_projekt_kontext.md`), **kein** Test. Es gehört nicht
  dauerhaft in die Test-Sources; die generierte Datei nicht committen.
