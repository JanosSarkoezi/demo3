# FMC Editor — Projekt-Richtlinien & Architektur-Dokumentation

Dieses Dokument beschreibt die **tatsächlich implementierte** Architektur des
FMC-Editors (Stand: Kern-/Interaktions-Schicht, Java 21, Maven). Ziel ist eine
strikte Trennung zwischen Interaktionslogik (Core) und späterer UI (JavaFX).

> **Wichtig:** Die UI-Schicht läuft und ist über „Erstes Licht" (30.10.)
> hinaus: seit 02.11. mit vollständigem Inline-Text-Editor (freier Text,
> externes K/R-Label, K-Buchstabe, R-Inhalt, Verbindungs-Label — Stufe
> A/B/C, siehe Abschnitt 9). Offen: „Zentriert"-Schalter-UI (User hat eigene
> Ideen), Layer-System, Persistenz — Details unter „Geplant / fehlt noch";
> dort stehende Klassennamen sind **Ziel-Vision, nicht Realität**.

---

## 🎯 Produktvision (verbindlich — Quelle: `spec.md`)

Die `spec.md` im `doc/`-Verzeichnis beschreibt die Zielversion des Editors. Die
`tools.md` daneben ist die Bedienungs-Referenz (Soll-Verhalten je Werkzeug und
Modus — zugleich Abhakliste für den visuellen Smoke-Test). Kernanforderungen:

* **Canvas:** Panning; Zoom am Mauszeiger per Mausrad (implementiert).
* **Werkzeuge (Sidebar + Shortcuts):** Kreis (K), Rechteck (R), Verbindung,
  Text.
* **Verbindungen:** Klick auf Start-Objekt, 0-n Waypoints (W), Klick auf
  Ziel-Objekt. Segmente an den Ecken abgerundet, am Ziel-Ende ein Pfeil
  (gerichtete Kante).
* **Selektion & Move:** Auswahl umfasst 1..n Objekte (Ks, Rs und Ws). Beim
  Verschieben snappt die Position an Grid-Kreuzungspunkte.
* **Resize:** Ks, Rs und Text-Boxen (symmetrisch ums Zentrum; K/R/Text
  seit jeher kern-seitig, der Text-Zugang seit 02.11. offen).
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
  `ResizeBox` / `ResizeCircle` (nur Maße —
  das Zentrum bleibt garantiert erhalten), `CreateConnection` (mit
  Waypoint-Liste — der Service speichert die Waypoints als Objekte und
  referenziert sie per ID), `AddConnection` (Restore mit Original-ID),
  `RemoveConnections` (ohne Kaskade — präzises Inverse), `AddWaypoint`
  (Kettenpunkt in eine bestehende Verbindung einfügen — Doppelklick auf ein
  Segment im RouteEditMode; das W-Record erzeugt der State nach dem
  CreateConnection-Präzidenzfall, der Einfüge-Index aus der Kettengeometrie
  leitet der DiagramService ab), Text-Requests:
  `SetCircleLabel`, `SetRectangleContent` (inkl. centered-Schalter),
  `UpdateText` (nur Inhalt/Box), `SetAnchor` (nullable TextAnchor — null =
  Anker lösen, Position bleibt), `AddAnchoredText` (Service leitet x/y aus
  dem Anker ab), `RequestUndo` /
  `RequestRedo` (UI übersetzt CTRL+Z / SHIFT+CTRL+Z hierher).
* **UI-Intents auf dem Bus:** `EditTextOfObject` (innerer Text K/R), `EditText`
  (bestehendes Text-Objekt), `CreateTextFor` (ownerId null = freier Text),
  `CreateConnectionText` (mit projiziertem Anker). Das Text-Werkzeug
  emittiert sie; seit 02.11. (Stufe A/B/C) konsumiert der
  `InlineTextEditorController` ALLE vier. ESC im Editor verbraucht
  die UI selbst — erreicht den Core nie (seit der Ziel-Greife am
  Systemrand garantiert).
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
* **Verankerte Texte — Drag = Re-Ankerung, Restore = Skip:** Der
  Gesten-Pfad (`MoveObject`/`MoveObjects`) re-ankert Texte mit Anker:
  Die Zielposition wird auf die Kette projiziert und schreibt die
  Platzierungs-Absicht (t + Offset) neu — pro Drag-Segment ein
  `ObjectTextChanged`-Fakt, das Undo-Merge macht die Geste zu EINEM
  Undo-Punkt. Der mechanische Restore-Pfad (`MoveObjectTo`, Undo-Inverse)
  überspringt sie weiter still: Verankerte Positionen stellen sich über
  die Ableitung aus der per LIFO wiederhergestellten Kette her.
* **W-Gleiten:** Jede Ketten-Verformung (W-/Endpunkt-Move, Gruppen-Move,
  `AddWaypoint`, W-Löschen, `RestoreObjects`, `AddConnection`) leitet die
  Positionen verankerter Texte aus ihren UNVERÄNDERTEN Ankern neu ab —
  als sichtbare Einträge im selben `ObjectsMoved`-Batch-Fakt (bzw. als
  eigener Batch auf den Restore-Pfaden), nie versteckt. Beim W-Einfügen
  wandern Anker-Indizes hinter der Einfügestelle mit (+1,
  `ObjectTextChanged`); beim W-Löschen klemmt der Index bei der Ableitung
  — t und Offset bleiben als Absicht erhalten.
* **Lösch-Kaskade Phase 1b:** Texte, deren Owner (Objekt ODER Connection)
  doomed ist, kaskadierten mit — als sichtbare Fakten in `ObjectsRemoved`,
  nie versteckt (demo2-Lektion). Das gilt für K/R-Owner wie für
  Connection-Owner.
* **Direkte Verbindungs-Löschung (03.11.):** `DeleteObjects` nimmt seit der
  Verbindungs-Selektion auch Connection-IDs auf — der Handler löst sie über
  `collectConnectionCascade` auf (Ws + verankerte Labels kaskadieren, die
  Endpunkte bleiben). Dieselbe Maschinerie wie beim Endpunkt-Löschen, damit
  Undo/Redo mechanisch bleibt (Inverse: `AddConnection` + `RestoreObjects`,
  beides existierte schon). Der Früh-Return prüft BOTH Stores
  (`doomedObjects` UND `doomedConnections`) — sonst wäre eine
  Direktverbindung ohne Ws ein stiller No-Op; `ObjectsRemoved` wird nur bei
  tatsächlichen Objekt-Entfernungen publiziert.
* **Text-Queries** (Default-Methoden auf `DiagramQuery`, überschrieben im
  Service): `findTextIdsOwnedBy(ownerId)` (Move-Union + 0..1-Kardinalität),
  `findConnectionNear(x, y, tolerance)` (Punkt-zu-Kette-Distanz — die
  nächstgelegene Connection gewinnt), `projectOntoConnection(connectionId, x, y)` →
  `TextAnchor` (Offset = Klick − Ankerpunkt: Das Label sitzt, wo geklickt
  wurde — Kreation frei, Snapping greift erst beim Verschieben).
* **Move-Mengen-Query** `findMoveSet(basisIds)` (Default auf `DiagramQuery`,
  überschrieben im Service): die effektive Move-Menge ist eine reine Funktion
  der Auswahl-Basis — Basis selbst + externe Label + Waypoints
  VOLLSELEKTierter Verbindungen (beide Endpunkte in der Basis). Der State
  (IdleToolState) ruft nur diese eine Query; Move-Expander-Wissen liegt nie
  inline in States. Connection-IDs in der Basis werden gefiltert (03.11.):
  Verbindungen sind nicht bewegbar — vor allem dürfen ihre owned-Texte
  nicht als Move-BASIS hinegelangen (ein Delta-Move würde das Label
  RE-ANKERN statt gleiten lassen).

### 4. Auswahl, Grid & Handles (read-only im Interaktions-Kontext)
* **`SelectionService`** (`core.service`): hält die geordnete, duplikatfreie
  Auswahl — seit 03.11. auch mit Connection-IDs (Plain-Klick auf eine
  Verbindung; DEL-fähig, nicht bewegbar). Schreibpfad nur über
  `SetSelection`-Requests (leer = abwählen),
  Bestätigung als `SelectionChanged`-Fakt; read-only-Sicht: `SelectionQuery`
  (`core.model`). Auswahl ist Sitzungszustand, bewusst NICHT im DiagramService.
  Invariante „keine toten IDs": Der Service abonniert `ObjectsRemoved` UND
  `ConnectionsRemoved` und staubt gelöschte IDs automatisch heraus (letztere
  auch für die Endpunkt-Kaskade, bei der die Verbindung nie selbst in der
  Anfrage stand). Identische Mengen feuern KEINEN
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
   beendet das Panning). Der **Mausrad-Zoom lebt hier** (`CameraIdleState`,
   Regel 3) und wirkt damit in JEDEM Werkzeug — demo3-Äquivalent zu demo2s
   Controller-Zoom; eine Zweitregel in einem Tool-State würde ihn verdoppeln.
   Null-Delta-Scrolls (VNC-Begleit-Events) werden still ignoriert; die
   Klemmung 0.2–10 (`MIN_ZOOM`/`MAX_ZOOM`) macht `CameraInfo.zoomAt`.
2. **Tool-Machine nur, wenn die Kamera nicht aktiv panned**
   (`cameraState.isActive()`); während des Pannings ist das Werkzeug
   pausiert.
3. **Kamera-Snapshot aktuell halten:** Der Bus konsumiert `PanView`/`ZoomView`
   selbst und ersetzt seinen `CameraInfo`-Snapshot via `panBy`/`zoomAt` —
   jeder folgende Event-Durchlauf rechnet mit der aktuellen Kamera.
   Read-only-Zugriff für Tests/UI: `getCameraInfo()`.
3b. **Rasterweite als zweiter getrackter Sitzungswert:** `SetGridSize` (Toolbar
   „Snap to Grid", Block 2) konsumiert der Bus genauso selbst und tauscht
   seine `GridSpec` aus — Snap und Resize-Mindestgröße (1 Zelle) folgen
   sofort. Validierung im Bus: Werte ≤ 0/NaN/unendlich werden still
   ignoriert (die GridSpec-Konstruktion würde sonst werfen und ein
   Eingabefehler zum Crash werden). Produkt-Startwert 20 (Main-Konstante,
   User-Entscheidung 1.11.); bewusst NICHT persistiert; immer aktiv (spec:
   „virtuelles Netz" — kein Aus-Schalter).
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
* **Tool-States** (`core.state.impl`): `IdleToolState` (Hit-Test,
  Selektion bei Press — einfach = exklusiv,
  Ctrl+Klick = Toggle, Leere/ESC = leeren —, DEL = Auswahl löschen
  (seit 03.11. auch Connection-IDs: Misch-Auswahl in EINER Geste),
  Doppelklick → `ResizeModeState` (K/R/Text — alles mit Griffen; Ws sind
  Routenpunkte ohne Griffe — Doppelklick selektiert sie nur); Plain-Klick
  NAHE einer Verbindung
  (Toleranz `CONNECTION_CLICK_TOLERANCE_PIXELS` = 8 Bildschirm-px / Zoom)
  selektiert NUR die Verbindung (`SetSelection({connectionId})` — blauer
  Pfad, DEL-fähig; Drag = bewusster No-Op, die Ws zeigt die View abgeleitet
  mit, sie stehen NICHT in der Auswahl), CTRL+Klick → `RouteEditModeState`
  mit `WaypointFocusChanged`-Hinweis; Doppelklick auf die Verbindungskette
  aus dem Idle betritt den Modus UND fügt den Kettenpunkt in EINER Geste
  ein (trifft er ein bestehendes W, selektiert er es nur); Ws werden im
  Idle aus Hit-Ergebnissen herausgefiltert — unsichtbare Objekte sind
  keine Klick-Ziele (das W steuert über die Verbindungskette in den
  RouteEditMode); movingIds =
  `findMoveSet(Auswahl bzw. Ziel)` — effektive Move-Menge aus dem Service
  (Basis + externe Label + Ws vollselektierter Verbindungen;
  Connection-IDs werden herausgefiltert)),
  `CircleToolState` / `RectangleToolState` / `ConnectToolState`,
  `TextToolState` (EINE Press-Regel, Verzweigungen in der Aktion: Klick auf
  K/R → `EditTextOfObject`; Ctrl+Klick auf K/R → `CreateTextFor(owner)`;
  Klick auf Text → `EditText`; Verbindungsnähe → bestehendes Label
  bearbeiten [Kardinalität 0..1] sonst `CreateConnectionText` mit
  projiziertem Anker; Leere → `CreateTextFor(null)`; ESC → Idle),
  `PendingCreateOrPanState` (Klick-oder-Drag-Entscheidung; Erzeugen NUR
  auf freier Fläche — Klick auf ein bestehendes Objekt ist ein stiller
  No-Op gegen deckungsgleiche Stapel, unsichtbare Ws blockieren nicht),
  `MovingState`, `PendingMoveState`, `PendingBoxSelectState` (Gummiband:
  Ctrl+Leerraum-Drag, stumm bis zum Release, EIN `SetSelection`; aus dem
  Idle OHNE Ws, aus dem RouteEditMode MIT Ws — der State trägt das Flag;
  live `BoxSelectPreviewChanged`-Hinweise mit dem Bildschirm-Rechteck),
  `ConnectingState` (Waypoints sammeln,
  ESC bricht ab; Klick auf ein vorhandenes W gilt wie Leerraum — neuer
  Kettenpunkt, Ws sind keine Verbindungsziele),
  `ResizeModeState` (Auswahl + Handles, Griff-Hit-Test),
  `HandleDragState` (Resize-Geste: intern sammeln, Commit beim Loslassen)
  und `RouteEditModeState` (Klick auf eine Verbindung — der Hover-Glow
  der View zeigt den Hotspot: ihre Ws werden sichtbar
  (`WaypointFocusChanged`) und greifbar; Klick/Ctrl+Klick/Move/Band MIT
  Ws, Modus bleibt; Klick auf K/R/Text = Idle-Semantik mit
  Modus-Verlassen; Klick daneben = Auswahl leeren + verlassen; ESC =
  Modus verlassen, nicht-W-Auswahl behalten; DEL = Auswahl löschen —
  lebt die fokussierte Verbindung nach der Kaskade nicht mehr, endet der
  Modus mit ihr; Klick auf eine andere Verbindung wechselt den Fokus).
  Doppelklick auf ein FREIES SEGMENT fügt dort einen neuen Kettenpunkt ein
  (`AddWaypoint` — demo2-Semantik; Einfüge-Index = nächstgelegenes Segment,
  im DiagramService berechnet; auch bei Direktverbindungen K↔R ohne Ws;
  KEINE Auto-Selektion — Sichtbar = greifbar, Press erledigt die Selektion).
  Der Modus ist das direkte Gegenstück zum `ResizeModeState` (Doppelklick)
  und lebt nach demselben Muster: **Der Zustand IST das Flag** — Idle
  filtert Ws heraus, der Modus nicht; kein gemeinsamer Sitzungsspeicher.
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
* **Move-Snapping (Block 2):** Das Ziel des PRIMÄR gezogenen Objekts
  (Press-Ziel, als `MovingState.MoveAnchor` mit Gesten-Start-Position an
  die Geste gereicht) snappt über `GridSpec.snapToGrid` auf den nächsten
  Kreuzungspunkt; die Gruppe folgt dem resultierenden effektiven Delta
  (demo2-Semantik, Mathe zentral in GridSpec). Zwei Gesten-Feinheiten:
  Die Snap-Rechnung läuft über die MAUS-SUMME seit Gesten-Start —
  Segmentsicht klebte am ersten Schnittpunkt fest, weil jedes Segment nur
  wenige Pixel über der alten Position sähe. Und das effektive Delta wird
  gegen die bereits publizierten Deltas verrechnet (eigene Buchhaltung
  statt Modell-Neulesen) — immun gegen Service-seitige Skips verankerter
  Texte. Null-Delta-Segmente (Wiggle innerhalb der Zelle) publizieren
  NICHTS: kein Fakt, kein Undo-Rauschen — das Objekt klebt sichtbar.
  Erzeugung bleibt frei (spec: nur das Verschieben snappt); Ws snappen
  uniform mit (User-Entscheidung 1.11.).
* **Resize: Commit erst beim Loslassen — Vorschau als Hinweis:** Während des
  Ziehens publiziert `HandleDragState` pro Drag-Segment einen
  `ResizePreviewChanged`-HINWEIS mit der exakten Commit-Geometrie (symmetrisch
  ums Zentrum, geklemmt auf mindestens eine Grid-Zelle; dieselbe Methode
  `computeGeometry` wie der Commit — Kern ist die einzige Rechen-Wahrheit,
  die View rendert nur). Hinweise sind Sitzungszustand, KEINE Modell-Fakten,
  kein Undo-Punkt; beim Loslassen bleibt es bei genau EINEM
  `ResizeBox`/`ResizeCircle`, danach beendet ein Null-Hinweis die Vorschau
  (auch beim ESC-Abbruch — dort stellt die View aus dem Modell her, da kein
  Fakt kommt).
* **Resize-Modus bleibt nach dem Commit (Option B, 30.10.)** — mit FRISCHEM
  Record: Der Rückkehr-`ResizeModeState` liest das Objekt nach dem Commit per
  `findById` neu (das geschnappte target trägt die Alt-Geometrie; ohne
  Fresh-Read hitesteten die Griffe gegen veraltete Positionen — User-Fund
  „Griffe nicht mehr greifbar"). Regel für unveränderliche Modelle: **Nach
  jedem Commit, der in einen State mit Objekt-Bezug zurückkehrt, frisch
  lesen.**

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
Text-Boxen, mechanischer Move-Skip verankert, Mitwandern externer Label,
Lösch-Kaskade inkl. Undo, innere Texte mit Undo/Redo, Anker-Ableitung/
-Klemmung, Verbindungs-Geometrie) und `TextToolTest` (Gesten-Intents,
0..1-Kardinalität, KEY-T/ESC) decken das Text-Feature ab.
`TextGlidingTest` sichert W-Gleiten & Re-Ankerung (Gleiten im selben
Batch-Fakt bei W-/Endpunkt-/Gruppen-Move, Anker-Index-Wanderung beim
Einfügen, Klemmung beim Löschen, Drag = Re-Ankerung mit t/Offset-Projektion,
Ein-Undo-Punkt pro Drag, Undo-Konvergenz über LIFO + Ableitung). `WaypointSelectionMoveTest` sichert
die W-Semantik (vollselektiert = Route wandert mit; W-Klick/Band steuert
seit Block 2 über den RouteEditMode), `RouteEditModeTest` den kompletten
Modus (Einstieg mit Klick-Toleranz, W-Greifen einzeln/mehrfach/Band,
Fokus-Wechsel, DEL mit Prune, Austritts-Semantik, Band-Vorschau-Hinweise,
Undo-Integration, Doppelklick-Einfügen auf erstem/mittlerem/letztem Segment
sowie Direktverbindungen ohne Ws, W-Doppelklick-Regression,
Einfüge-Undo/Redo, keine Auto-Selektion des neuen W), `SnapToGridTest` das Move-Snapping (Mittellinien-Sprung,
Wiggle-Kleben ohne Fakt, Gruppen-Delta, W-Snap, Ein-Undo-Punkt, freie
Erzeugung; wählbarer Raster-Abstand: `SetGridSize` wirkt sofort, ungültige
Werte bleiben still ignoriert, neue Zelle = neue Resize-Mindestgröße),
`ConnectionSelectionTest` (03.11.) die Verbindungs-Selektion (Plain-Klick =
nur die Connection-ID, CTRL+Klick = Modus, Drag = No-Op,
Move-Mengen-Reinheit bei gemischter Auswahl — das Verbindungs-Label wird
nie Move-Basis —, DEL-Kaskade mit Endpunkt-Erhalt + Undo,
Auswahl-Stauben über ConnectionsRemoved), `UiSessionIntentsTest`
die State→UI-Hinweise und Initial-Queries, `BusTestSupport` ist der geteilte
Test-Rahmen — **179 Tests gesamt, alle grün**.
**Jedes neue Interaktions-Feature bekommt einen solchen Event-basierten Test.**

### 9. UI-Schicht („Erstes Licht" 30.10. → Inline-Editor Stufe A/B/C 02.11.)
* **`Main`** (`de.fmc.editor`): Bootstrap in Produktions-Reihenfolge —
  Services + Bus konstruieren, DANACH `attachTo` (Zirkularität, identisch
  zu `BusTestSupport`), danach View/Toolbar/Übersetzer verdrahten,
  `requestFocus` auf die Zeichenfläche. Start: `mvn javafx:run`.
* **`ui`-Paket (Systemrand):** `InputEventTranslator` übersetzt JavaFX-Events
  in `InputEvent`s (immer SCREEN-Koordinaten — die Welt-Umrechnung macht der
  Kern über `CameraInfo`). CTRL+Z → `RequestUndo`, CTRL+Y / CTRL+SHIFT+Z →
  `RequestRedo` als DIREKTE ActionEvents: `CustomKeyCode` kennt Z/Y bewusst
  nicht. **Ziel-Greife** (User-Fund 02.11.): Der Übersetzer fasst nur Events
  an, deren ZIEL der Canvas-Root selbst ist — der Inline-Editor ist ein Kind
  des Root, seine Tasten und Klicks liefen sonst durch die Capture-Phase in
  den Kern (Werkzeug-Buchstaben wechselten das Tool, CTRL+Z/ESC leakten).
  Overlays sind damit automatisch außen vor, wie die Toolbar als Sibling.
  `UiMouseState` hält die Mausposition (Sitzungszustand der UI).
  `ToolbarController`: reiner Publisher (`SelectTool`, `RequestUndo/Redo`,
  `SetGridSize`), Sync über `ToolChanged`; KEIN Select-Button (kein aktiver
  Toggle = SELECT, demo2-Stil); Sektion „Snap to Grid" mit freiem numerischem
  Eingabefeld (Validierung: Zahl > 0, Komma erlaubt; ungültige Eingabe stellt
  auf den letzten gültigen Wert zurück; Commit bei ENTER und Fokus-Verlust);
  alle Controls `setFocusTraversable(false)` — Shortcuts bleiben auf der
  Zeichenfläche. **Fokus-Disziplin** (User-Fund 1.11.): Ein Textfeld MUSS den
  Fokus zum Tippen nehmen — ENTER committet und ESC verwirft, beide geben den
  Fokus per Hook an die Zeichenfläche zurück; und JEDER Klick auf die Fläche
  holt den Fokus aktiv zurück (`InputEventTranslator.onMousePressed` —
  JavaFX-Regionen übernehmen ihn beim Klick nicht von selbst). Ohne dies
  erreichten ESC/DEL/Shortcuts den Übersetzer nie wieder.
* **`InlineTextEditorController` (Stufe A + B + C, 02.11. — komplett):** Der
  Konsument der Text-Werkzeug-Intents — öffnet den Editor-Overlay im
  BILDSCHIRM-Raum des Root
  (wie das Band-Rechteck, via `GraphView.addToScreenOverlay`). EINE
  Komponente, zwei Node-Typen: TextArea für mehrzeilige Texte (ENTER =
  Zeilenumbruch, **CTRL+ENTER** committet — User-Entscheidung 02.11.),
  TextField für den K-Buchstaben (auf 1 Zeichen begrenzt via TextFormatter,
  ENTER committet). Abgedeckt: freier Text (Klick-Position = Box-Zentrum,
  Erzeugung ohne Snapping), externes K/R-Label (Default unterhalb des
  Owners), K-Buchstabe, Nachbearbeiten bestehender Texte (`EditText`,
  vormarkierter Text), R-Inhalt (Editor über der R-Innenbox, Screen-Minimum
  140×60; Commit nur den INHALT als `SetRectangleContent` — die Innenbox ist
  von der R-Geometrie abgeleitet) und Verbindungs-Label (`CreateConnectionText`:
  Editor an der Klick-Stelle, Commit als `AddAnchoredText` — der Service
  leitet die Position aus dem projizierten Anker ab, genau wie beim Gleiten).
  Drei Disziplinen: (1) „Klick daneben
  committet" KONSUMIERT den Klick — der Press-Filter steht in der Kette VOR
  dem Übersetzer (Main konstruiert den Editor zuerst) und schluckt die
  Geste, der nächste Klick wirkt normal (spec). (2) ESC verbraucht der
  Editor selbst; das Modell wird erst beim Commit berührt (leerer Commit =
  stiller Verwurf, kein leeres Objekt; „nur bei Änderung" für Label/Edit).
  (3) Die Kamera ist während des Editierens eingefroren (Scroll konsumiert)
  — die gespeicherte Welt-Position bleibt gültig. Positionierung über
  `CameraInfo.worldToScreenX/Y` (Roundtrip-getestet), Screen-Größe =
  Welt-Box × Zoom.
* **`view`-Paket (stummer Scene-Graph):** `GraphView` spiegelt Fakten in
  JavaFX-Nodes — hält KEIN eigenes Modell, ALLE Modell-Nodes
  `mouseTransparent` (Hit-Tests macht allein der Kern). Layer:
  Verbindungen unten, Objekte, UI oben in einer `worldGroup`; das
  Gummiband-Rechteck liegt im BILDSCHIRM-Raum des Root (zoom-unabhängige
  Präsentation der Geste). Kamera wird nur APPLIZIERT
  (`bus.getCameraInfo()` → Scale/Translate) —
  nie selbst gerechnet (demo2-Anti-Pattern vermieden).
  `RoutingStrategy` (Straight/RoundedCorner, Radius 20) und
  `ConnectionViewMapper` (Pfeilspitze arrowSize 12/indent 0.65,
  Boundary-Schnitt, in-place Path-Refresh) sind 1:1 aus demo2 portiert.
  Hover: eigener MOUSE_MOVED-Filter — Objekte über `findTopmostAt`,
  Verbindungsketten (wenn kein Objekt getroffen) über `findConnectionNear`
  mit grauem Glow auf dem Pfad (demo2-Stil, Toleranz in Bildschirm-px/Zoom).
  Selektions-Darstellung (03.11.): Objekte blauer Glow (diff-basiert über
  `SelectionChanged`), selektierte Verbindung blauer Glow auf dem Pfad (Prio
  über Hover), ihre Ws abgeleitet mitmarkiert — `selectedConnectionWaypoint-
  Ids` aus den `waypointIds` der LIVE-Connection gelesen, sichtbar + blau
  ohne in der Auswahl zu stehen; W-Sichtbarkeit = Modus-Flag ODER Selektion.
  Die Verbindungs-Vorschau zeigt zusätzlich GELBE Marker an den noch nicht
  committeten Kettenpunkten (demo2-Stil; reine Präsentation des
  `ConnectionPreviewChanged`-Hinweises — Modell-Fakten werden die Ws erst
  mit `CreateConnection`).
* **State→UI-Hinweis-Intents** (in `ActionEvent`, KEINE Modell-Fakten — der
  UndoService ignoriert sie wie Kamera/Selektion): `ToolChanged` (vom Bus
  nach jedem Tool-Übergang), `ConnectionPreviewChanged` (sourceId=null
  räumt die Vorschau auf), `ResizeModeChanged` (target=null = Griffe weg),
  `WaypointFocusChanged` (connectionId=null = W-Kreise weg — treibt seit
  Block 2 die W-Sichtbarkeit, die Toolbar-Checkbox ist entfallen) und
  `BoxSelectPreviewChanged` (startX=null räumt das Band-Rechteck auf).
  Muster: Versteckter Kern-Zustand quert die Grenze als Intent — die UI
  besitzt ihn nie selbst. Prinzip: **Bus für Zustandsänderungen, Query für
  Lesewissen — Hover ist Lesewissen.**
* **Bekannte Restchen:** View-z-Ordnung folgt der Fakt-Reihenfolge
  (Restauration landet visuell oben — TODO im `GraphView`); Preview ohne
  Rundung auf dem freien Abschnitt; Shortcuts K/R/C/T wirken nicht in den
  langen Modi (Connecting/ResizeMode/RouteEditMode) — nur Toolbar-Klick
  und ESC beenden sie; das Layer-System (Sichtbarkeits-Steuerung als
  Ebenen) fehlt noch.

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

* **UI-Schicht — ✅ „Erstes Licht" implementiert (30.10.), siehe Abschnitt 9.
  Inline-Text-Editor ✅ Stufe A + B + C (02.11.) — KOMPLETT: freier Text,
  externes K/R-Label, K-Buchstabe, R-Inhalt, Verbindungs-Label.** Offen:
  „Zentriert"-Schalter-UI für den R-Inhalt (Default bleibt zentriert;
  vertikale Mitte gilt immer — User hat eigene Ideen für den Schalter,
  Klärung ausstehend).
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
* **Verbindungs-Selektion & -Löschung:** ✅ Implementiert (03.11.) —
  Plain-Klick auf eine Verbindung selektiert sie (nur die Connection-ID in
  der Auswahl; die View markiert ihre Ws abgeleitet mit, sie werden NICHT
  selektiert); CTRL+Klick betritt den RouteEditMode; DEL löscht die
  Verbindung samt Ws + Verbindungs-Label, die Endpunkte bleiben (EIN
  Undo-Punkt); Drag an der Verbindung ist bewusst ein No-Op — Verbindungen
  sind nicht bewegbar, Route-Editing läuft über die Ws im Modus.
* **Waypoint-Nachbearbeitung:** ✅ Vollständig (Block 2, 1.11.; Einstieg
  seit 03.11. per CTRL+Klick):
  CTRL+Klick auf Verbindung blendt die Ws ein — als `RouteEditModeState` mit
  `WaypointFocusChanged`-Hinweis statt als Layer-Schalter; Drag/Klick
  daneben blendet sie wieder aus; im Modus sind die Ws greifbar (Klick,
  Ctrl+Klick, Band MIT Ws, Multi-Move). **Doppelklick auf ein Segment
  fügt einen Waypoint an der Klick-Position ein** (`AddWaypoint`-Request —
  am 27.10. tot entfernt, jetzt mit echtem Anwendungsfall zurück; Einfüge-
  Index per Kettengeometrie im DiagramService, testbar — demo2 trug ihn
  noch im State-Handler; funktioniert auch bei Direktverbindungen ohne Ws;
  aus dem Idle weiterhin in EINER Geste: Modus + Einfügen, ein bestehendes
  W unter dem Doppelklick wird nur selektiert).
* **Move-Snapping:** ✅ Implementiert (Block 2, 1.11.) — `GridSpec.snapToGrid`
  im Move-Pfad über den `MovingState` (Ziel des primär gezogenen Objekts
  snappt, Gruppe folgt dem effektiven Delta; Mathe in `GridSpec`, Gesten-
  Semantik im State — nie inline dupliziert wie in demo2). Erzeugung bleibt
  frei, Ws snappen uniform, Texte über die Mittelpunkt-Konvention. Details
  siehe Abschnitt 7.
* **Text-Feature:** ✅ Core implementiert (28.10.; W-Gleiten & Re-Ankerung
  02.11., siehe Abschnitt 3) — Modell (`Text` mit
  Mittelpunkt-Konvention, `TextAnchor`, `Circle.label`,
  `Rectangle.content/centered`), Service-Schreibpfad inkl. Anker-Ableitung,
  Lösch-Kaskade für Owned-Texte, Undo/Redo (`ObjectTextChanged` +
  mechanische Inversion), `TextToolState` mit UI-Intents,
  `ToolState`-Zentralisierung als Vorbereitung. Offen:
  * **UI-Editor:** ✅ Stufe A + B + C (02.11.) — KOMPLETT.
    `InlineTextEditorController` konsumiert alle vier Intents: `CreateTextFor`
    (frei + externes K/R-Label, Default „unterhalb des Objekts"),
    `EditTextOfObject` (K-Buchstabe UND R-Inhalt: Editor über der
    R-Innenbox, Commit als `SetRectangleContent` mit unangetastetem
    centered-Default), `EditText` (Nachbearbeiten) und
    `CreateConnectionText` (Verbindungs-Label: Commit als `AddAnchoredText`,
    Position leitet der Service aus dem projizierten Anker ab — Drag
    re-ankert, Ketten-Verformungen gleiten über das W-Gleiten). Offen:
    „Zentriert"-Schalter-UI (User hat eigene Ideen).
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
3. **Move-Semantik:** Ws sind Routenpunkte ihrer Verbindung, keine
   eigenständigen Objekte (sie formen den Polygonzug K→R). Daraus:
   Selektierbar per Klick/Gummiband (Umformen durch direkten Griff);
   **vollselektierte Verbindung** (beide Endpunkte in der Auswahl) → die
   Ws wandern als Ganzes mit; teilselektiert → Ws bleiben verankert;
   Connections folgen ihren Endpunkt-IDs automatisch; externe Texte
   wandern mit — in demo3 als eigener Fakt vom DiagramService publiziert
   (demo2 löste das versteckt in der Registry — nicht kopieren). Die
   Move-Menge berechnet zentral `findMoveSet` (reine Funktion der
   Auswahl-Basis). Geteilte Ws über zwei Verbindungen sind konzeptionell
   eine Anomalie (ein W gehört genau einer Route).
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
