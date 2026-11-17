# FMC Editor — Projekt-Richtlinien & Architektur-Dokumentation

Dieses Dokument beschreibt die **tatsächlich implementierte** Architektur des
FMC-Editors (Stand: Kern-/Interaktions-Schicht, Java 21, Maven). Ziel ist eine
strikte Trennung zwischen Interaktionslogik (Core) und späterer UI (JavaFX).

> **Wichtig:** Die UI-Schicht läuft und ist über „Erstes Licht" (30.10.)
> hinaus: seit 02.11. mit vollständigem Inline-Text-Editor (freier Text,
> externes K/R-Label, K-Buchstabe, R-Inhalt, Verbindungs-Label — Stufe
> A/B/C, siehe Abschnitt 9), seit 03.11. mit Speichern/Laden (JSON im
> demo2-Format, siehe Abschnitt 3 „Persistenz"), seit 10.11. mit modus-
> und hover-gerechtem Mauszeiger (siehe Abschnitt 9 „Cursor-Präsentation"),
> seit 17.11. mit Event-Symbolen (gebogener Pfeil am R mit ziehbarer Form,
> siehe Abschnitt 3 „Event-Schreibpfad"), seit 26.11. mit zwei Event-
> RICHTUNGEN (INCOMING dockt oben an, OUTGOING startet unten — Vier-
> Quadranten-Klick im Event-Werkzeug wählt Richtung UND Seite, 0..1 je
> Richtung). Offen: „Zentriert"-Schalter-UI
> (User hat eigene Ideen), Layer-System — Details unter „Geplant / fehlt
> noch"; dort stehende Klassennamen sind **Ziel-Vision, nicht Realität**.

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
  Typen nutzte. ✅ Implementiert (03.11.): Das sealed, typisierte Modell
  blieb; der `DiagramCodec` (`core.persistence`) konvertiert am
  Serialisierungsrand (`Circle.radius` ↔ width/height = Durchmesser).
  **Beantwortet** (demo2-Beweis über den `ShapeViewMapper`: `Circle(obj.x(),
  obj.y(), w/2)`, Rechteck bei `x − w/2`): x/y im Format ist der
  MITTELPUNKT — identisch zur demo3-Modellkonvention, keinerlei
  Koordinaten-Konvertierung nötig.

---

## 🏗️ Kernarchitektur (Ist-Zustand)

### 1. Datenmodell (Core, immutable)
* **Sealed Interface `DiagramObject`** mit den Records `Circle`, `Rectangle`,
  `Waypoint`, `Text` und `Event`. Alle sind immutable, threadsicher und frei von JavaFX-Imports.
  `ToolType` (SELECT, CIRCLE, RECTANGLE, CONNECT, TEXT, EVENT) steuert die Werkzeuge.
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
  * **`Event`** (Record, 17.11., Kardinalität/Label-Kette 23.11., Richtungen
    26.11.): Ereignis-Symbol am R — gebogener Pfeil, dessen LABEL ein
    eigenes `Text`-Objekt mit ownerId = Event-ID ist (Kette R → E → Text,
    analog zu den Außentexten am R: mehrzeilig, frei bewegbar, selektier-/
    löschbar). `ownerId` referenziert das besitzende Rectangle mit
    Kardinalität 0..1 **JE RICHTUNG** (Werkzeug erzwingt, Service
    verteidigt still, Laden bleibt tolerant): `direction` INCOMING =
    hereinkommend (Spitze dockt an der OBEREN Kante an, Pfeil zeigt in R),
    OUTGOING = rausgehend (Fuß startet an der UNTEREN Kante, Pfeilspitze
    sitzt waagrecht nach außen am Arm-Ende) — beide dürfen am selben R
    koexistieren (FMC-Lesart: rein oben, raus unten). Die FORM lebt als
    RELATIVE Längen im Record: `horizontalExtent` = Ausleger über den
    Seitenrand, `verticalRise` = Länge des senkrechten Stücks von der
    Andockkante weg (Standard 40/40, Klemm-Untergrenze 20 = Bogen-
    Radius). x/y ist der ANDOCKPUNKT auf der R-Kante (¼ der Breite vom
    Seitenrand; `EventSide` LEFT/RIGHT, Standard rechts; oben bei
    INCOMING, unten bei OUTGOING) — vom Service abgeleitet und gepflegt
    wie bei verankerten Texten. Die zwei Formgriffe (Start nur
    horizontal, Knick nur vertikal ziehbar) sind abgeleitete Punkte aus
    **`EventGeometry`** (pure Klasse: Andockpunkt/Knick/Ende/Polyline
    inkl. 90°-Bogen, Hit-Test, Bounds, Label-Anker — je Richtung
    parametrisiert; die OUTGOING-Polyline ist die gespiegelte + UMGE-
    KEHRTE Incoming-Punktefolge, deshalb orientiert der Renderer die
    Pfeilspitze am letzten Segment richtungslos) — bewusst KEINE
    Waypoint-Objekte: absolute Punkte blieben beim Move/Resize des R
    zurück und zögen die ganze W-Maschinerie an ein Symbol, das sie nicht
    braucht.
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
  Doppelklick, `controlDown` für den Selektions-Toggle / späteres
  Gummiband und `shiftDown` — wird seit dem Vier-Quadranten-Modell,
  26.11., NICHT mehr ausgewertet, das Feld bleibt am Record; public
  Kompatibilitäts-Konstruktoren ohne Modifier-Info),
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
  dem Anker ab), Event-Requests: `CreateEvent` (Owner muss ein R sein,
  Kardinalität 0..1 JE RICHTUNG (26.11.) — Validierung zentral im
  Service; erzeugt Event UND Label-Textbox in EINEM Request, Andockpunkt +
  Label-Anker aus Owner + Seite + Richtung abgeleitet; Create-Präzedenzfall
  wie `CreateConnection`),
  `SetEventSide` (Seitentausch: reine Positions-Geste, Spitze dockt neu
  ab — die Label-Textbox bewegt die GESTE mit, nie der Service, siehe
  Gotcha unten), `ResizeEvent` (nur die zwei Form-Längen; Klemm-Untergrenze
  im Service),
  `RequestUndo` /
  `RequestRedo` (UI übersetzt CTRL+Z / SHIFT+CTRL+Z hierher),
  `LoadDiagram` (Persistenz-Restore: kompletter Bestand in z-Ordnung +
  Connections mit Original-IDs — ohne Validierung, siehe Abschnitt 3).
* **UI-Intents auf dem Bus:** `EditTextOfObject` (innerer Text K/R), `EditText`
  (bestehendes Text-Objekt), `CreateTextFor` (ownerId null = freier Text),
  `CreateConnectionText` (mit projiziertem Anker), `CreateEventFor`
  (ownerId + Seite + Label-Anker aus EventGeometry — Editor öffnet dort,
  Commit erzeugt Event UND Label über `CreateEvent`; leerer Commit
  verwirft still) und `EditEventLabel` (Label-Textbox des Events
  bearbeiten — der Editor löst zum owned Text auf bzw. legt ihn neu an).
  Die Text-Werkzeug-Intents
  emittiert das Text-Werkzeug, die Event-Intents das Event-Werkzeug; der
  `InlineTextEditorController` konsumiert sie alle. ESC im Editor verbraucht
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
  `ConnectionsUpdated` (previous + updated index-parallel); plus
  `DiagramReset` (vollständiger Ersatz durch Laden: objects + connections =
  kompletter Nach-Zustand; bewusst KEIN Undo-Träger — Undo- und
  SelectionService leeren sich auf diesen Fakt selbst). **Design-Regel:
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
über steckbare `ConnectionRule`s (Standardregeln: gleicher Typ nicht
verbindbar; Events sind keine Verbindungsendpunkte → jeweils
`ConnectionRejected`). Zweiphasige Initialisierung gegen die Zirkularität
Bus ↔ Service: `new DiagramService()` → `new InteractionEventBus(service, …)`
→ `service.attachTo(bus)`.
* **Text-Schreibpfad:** `SetCircleLabel`/`SetRectangleContent`/`UpdateText`/
  `SetAnchor`/`AddAnchoredText` → je ein `ObjectTextChanged`-Fakt (bzw.
  `ObjectAdded`). Die Anker-Position leitet der Service zentral ab
  (`polyPoints` + Projektion — Geometrie-Wissen liegt nie in den States);
  `SetAnchor(null)` löst den Anker und hält die Position.
* **Event-Schreibpfad (17.11., Kardinalität/Label-Kette 23.11., Richtungen
  26.11.):** `CreateEvent` → ZWEI `ObjectAdded`-Fakten (Event, dann
  Label-Textbox) im selben Gesten-Eintrag = ein Undo-Punkt; Validierung
  zentral: Owner muss ein R sein und die Kardinalität 0..1 JE RICHTUNG
  erfüllen (alles andere bleibt stiller No-Op; hereinkommend und
  rausgehend koexistieren am selben R). `SetEventSide` → `ObjectResized`
  mit vollem Vor-Zustand (Andockpunkt dockt an der neuen Seite ab);
  `ResizeEvent` → `ObjectResized` (Klemm-Untergrenze 20). Die Andock-
  Position (x/y) ist eine reine Funktion von (Owner-Geometrie, Seite,
  Richtung) und wird auf JEDEM Event-Write idempotent neu abgeleitet
  (`EventGeometry` — Geometrie-Wissen nie im Aufrufer). R-**Resize**
  dockt die Andockpunkte aller Owned-Events sichtbar
  neu ab (eigener `ObjectsMoved`-Batch im selben Gesten-Eintrag —
  Präzedenzfall W-Gleiten); R-**Move** nimmt Events UND ihre Labels per
  TRANSITIVER Move-Expansion mit (Delta auf den Andockpunkt, Längen
  relativ → gezogene Form bleibt exakt). Der mechanische Restore-Pfad
  (MoveObjectTo, Undo-Inverse) überspringt die Ableitung still — dieselbe
  Asymmetrie wie bei verankerten Texten. Laden dockt die Andockpunkte
  frisch ab (selbstheilend gegen randeditierte Dateien, still —
  `DiagramReset` trägt den Nach-Zustand).
* **Event-Umziehen — die GESTE komponiert, der Service bleibt
  single-effect (23.11., Session-Fund):** Der Seitentausch bewegt die
  Label-Textbox bewusst NICHT im `SetEventSide`-Handler, sondern das
  Event-Werkzeug publiziert beide Requests (`SetEventSide` +
  `MoveObjectTo` je Label mit an der vertikalen R-Symmetrieachse
  gespiegelter Position: `newX = 2·R.x − label.x`, y unverändert — der
  Pfeil selbst ist exakt diese Spiegelung, also behält das Label seine
  relative Lage; eine Delta-Wanderung ließe ein weit gezogenes Label auf
  der alten Seite zurück). Grund für die Geste-Komposition: Eine
  Service-seitige Mitnahme würde im Undo DOPPELT angewandt — einmal über
  das mechanische MoveObjectTo-Inverse des Label-Moves, einmal über die
  Mitnahme im SetEventSide-Inverse. Beide Fakten im selben Gesten-Eintrag
  halten das Undo rein mechanisch („Kaskade und Undo dürfen nie
  auseinanderdriften" — derselbe Grundsatz wie bei der Lösch-Kaskade).
  Als Gotcha im Skill `derived-positions-converging-undo` festgehalten.
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
* **Lösch-Kaskade Phase 1b (Fixpoint, 23.11.):** Texte, deren Owner
  (Objekt, Connection ODER Event — Kette R → E → Text) doomed ist, und
  Events, deren R doomed ist, kaskadierten mit — als sichtbare Fakten in
  `ObjectsRemoved`, nie versteckt (demo2-Lektion). Die Sammel-Schleife
  läuft bis zur Stabilität: Die HashMap-Iteration ist beliebig geordnet,
  ein einziger Durchlauf hätte das Event-Label je nach Iterations-
  reihenfolge überleben lassen (sein Owner-Event wäre evtl. erst NACH dem
  Text gesammelt worden).
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
* **Persistenz — Laden als Restore (03.11.):** Paket `core.persistence`
  (JavaFX-frei): DTOs (`PersistedDiagram/Object/Text/Connection/Layer`,
  Feldnamen = demo2-Kontrakt), `DiagramCodec` (Abbildung Kern ↔ DTO:
  Durchmesser-Konvertierung, fehlende Felder alter Dateien → demo3-Defaults,
  fehlende layerId → Standard-Ebene, defekte Einträge still übersprungen),
  `PersistenceService` (Gson, Pretty-Printing; Fehler propagieren — das
  Surface ist der UI-Alert, nie printStackTrace). Der Lade-Schreibpfad läuft
  über den Bus: `LoadDiagram`-Request → DiagramService ersetzt beide Stores
  **ohne Validierung** (ConnectionRules prüfen NEUE Verbindungen — eine
  ältere Datei mit heute verbotener Verbindung muss trotzdem laden; demo2
  umging die Validierung genauso) und publiziert EINEN `DiagramReset`-Fakt
  mit vollständigem Nach-Zustand. Undo- und SelectionService abonnieren den
  Fakt und leeren sich selbst (demo2 rief seine History von der Toolbar aus
  — nicht kopieren). Export = Lesewissen über die Query in
  z-Ordnung (deterministisch; demo2 exportierte Map-Reihenfolge: nicht
  kopieren). **Format-Grenze (bewusst, demo2-Semantik):** Texte leben im
  eigenen texts-Abschnitt ohne z-Position und laden deshalb OBEN auf die
  z-Ordnung. Events (17.11.) reisen als PersistedObject mit Typ EVENT
  (demo2 kannte ihn nicht — kein Kompatibilitätsrisiko): gespeichert werden
  ownerId, side, direction (26.11.) und die zwei Form-Längen; fehlende
  Felder fallen im Codec auf die Defaults (RIGHT, INCOMING, 40/40),
  fehlender Owner macht den Eintrag defekt (still übersprungen); die
  mitgeschriebene Andock-Position ist
  abgeleitet und wird beim Laden frisch abgedockt. Das Event-LABEL reist
  seit 23.11. als normaler texts-Eintrag mit parentObjectId = Event-ID;
  das label-Feld des EVENT-Typs bleibt MIGRATIONS-Eingabe für Dateien vom
  17.11. (nicht-leeres label → owned Text am abgeleiteten Anker, still —
  Migration ist immer INCOMING, Dateien vor dem 26.11. kannten nur die).
  Layers-Abschnitt: die zwei Default-Ebenen („Standard"/
  „Wegpunkte", IDs aus spec.md) werden als Fixliste mitgeschrieben,
  tolerant gelesen — bis das Layer-System existiert. `PersistedLayers.
  STANDARD_LAYER_ID` ist zugleich Produkt-Default (Main).
* **Move-Mengen-Query** `findMoveSet(basisIds)` (Default auf `DiagramQuery`,
  überschrieben im Service): die effektive Move-Menge ist eine reine Funktion
  der Auswahl-Basis — Basis selbst + externe Label + Owned-Events +
  (transitiv, 23.11.) deren owned Label-Textboxen + Waypoints
  VOLLSELEKTierter Verbindungen (beide Endpunkte in der Basis). Der State
  (IdleToolState) ruft nur diese eine Query; Move-Expander-Wissen liegt nie
  inline in States. Connection-IDs in der Basis werden gefiltert (03.11.):
  Verbindungen sind nicht bewegbar — vor allem dürfen ihre owned-Texte
  nicht als Move-BASIS hineingelangen (ein Delta-Move würde das Label
  RE-ANKERN statt gleiten lassen). Event-IDs werden ebenso gefiltert
  (17.11.): Events sind nicht selbst bewegbar (Geometrie vom R abgeleitet,
  Drag am Event = bewusster No-Op wie an Verbindungen); als Anhängsel
  ihres R wandern sie über die Objekt-Expansion mit.

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
  Anfrage stand); `DiagramReset` (Laden) leert die Auswahl komplett.
  Identische Mengen feuern KEINEN
  Fakt (Plain-Press auf bereits Selektiertes kollabiert die Auswahl nicht
  und rauscht nicht).
* **`UndoService`** (`core.service`): Sitzungs-Undo/Redo über den Bus —
  Undo ist nur ein weiterer Schreiber. Der Service leitet aus den Modell-
  Fakten mechanisch inverse Requests ab (kein eigenes Kaskade-Wissen —
  demo2-Lektion), sammelt Ops pro Gesten-Eintrag (MousePressed/Released/
  KeyPressed sind Grenzen; MouseDragged bewusst nicht, damit Move-Segmente
  verschmelzen), fährt einen Replay-Guard gegen Selbst-Erfassung und leert
  den Redo-Stack bei neuen Aktionen. Kamera- und Auswahl-Fakten bleiben
  bewusst draußen (Sitzungszustand, kein Modellinhalt). Auf `DiagramReset`
  (Laden) verwirft der Service die komplette History — Datei öffnen =
  neues Dokument (User-Entscheidung 03.11., demo2-Semantik); der Reset
  selbst wird nie als Undo-Op aufgezeichnet.
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
   pausiert. Ausnahme: Modell-Fakten (`ObjectsRemoved`,
   `ConnectionsRemoved`) erreichen die Machine auch während des Pannings —
   Modus-States mit Ziel-Referenz (ResizeModeState, RouteEditMode) beenden
   sich damit selbst, wenn ihr Ziel über einen Modell-Pfad stirbt
   (z. B. Undo der Erstellung) statt nur über Interaktionspfade; sie
   publizieren ihren null-Hinweis und kehren zum Idle zurück
   (`ModeTargetRemovalTest`, 17.11.).
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
  Routenpunkte ohne Griffe — Doppelklick selektiert sie nur; Events
  gleichfalls ohne Resize-Modus — Doppelklick auf ein Event →
  `EditEventLabel`); Plain-Klick auf ein Event = reine Selektion ohne
  Move-Vorbereitung (nicht bewegbar wie Verbindungen; Ctrl-Toggle bleibt,
  das R zieht über die Move-Expansion mit). Plain-Klick
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
  `EventToolState` (17.11., Shortcut E; Kardinalität/Seitenlogik 23.11.,
  Vier-Quadranten-Modell 26.11. —
  EINE Press-Regel, Verzweigungen in der Aktion: Press auf einen Formgriff
  → `EventPathDragState` (oberstes Event zuerst, Greif-Radius 6 px/Zoom
  wie beim Resize-Griff); Klick auf ein Event ODER seine Label-Textbox →
  `EditEventLabel`; Klick in ein R-QUADRANT — die VERTIKALE Klickhälfte
  adressiert die RICHTUNG (oben = INCOMING, das dockt oben an; unten =
  OUTGOING, das startet unten — die Mittellinie gehört zur unteren
  Hälfte), die HORIZONTALE die Seite — dreigeteilt nach Kardinalität
  0..1 JE RICHTUNG: ohne Event der adressierten Richtung →
  `CreateEventFor` (Seite UND Richtung aus dem Quadrant, Label-Anker aus
  `EventGeometry` — bei OUTGOING Mitte des äußeren Segments unterhalb),
  Event der adressierten Richtung auf der GEKLICKTEN Seite →
  `EditEventLabel` (Inhalts-Geste), auf der ANDEREN Seite →
  UMZIEHEN (`SetEventSide` + `MoveObjectTo` je Label mit an der vertikalen
  R-Symmetrieachse GESPIEGELTER Position — der Pfeil ist selbst exakt
  diese Spiegelung, das Label behält seine relative Lage; reine Positions-
  Geste, KEIN Editor; Geste komponiert, siehe Abschnitt 3); Shift wird
  NICHT mehr ausgewertet (Modifier bewusst tot statt optional); Leere/K/
  fremder Text/W → stiller No-Op; ESC → Idle),
  `EventPathDragState` (achs-gebundener Form-Drag: Start-Griff schreibt NUR
  horizontalExtent, Knick-Griff NUR verticalRise — die andere Maus-Achse
  wird still verworfen; pro Drag-Segment ein `ResizePreviewChanged`-Hinweis
  mit geklemmter Preview, beim Loslassen GENAU EIN `ResizeEvent`, bei ESC
  Abbruch ohne Commit — Muster `HandleDragState`),
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
  die View rendert nur). Die View wendet den Hinweis auf Shape, Griffe UND
  die berührenden Verbindungen an: `ConnectionViewMapper.refresh(conn,
  override)` zeichnet Pfad/Pfeil gegen die Preview-Geometrie, damit die
  Pfeilspitze beim Ziehen am gezogenen Rand klebt (09.11.; demo2 löste das
  Sichtverhalten über Live-Registry-Mutation pro Drag-Segment — bewusst
  nicht kopiert). Der Null-Hinweis stellt alles aus dem Modell wieder her —
  beim Commit idempotent (der Fakt war zuerst da), beim ESC-Abbruch der
  zwingende Restore. Hinweise sind Sitzungszustand, KEINE Modell-Fakten,
  kein Undo-Punkt; beim Loslassen bleibt es bei genau EINEM
  `ResizeBox`/`ResizeCircle` (auch beim ESC-Abbruch — da kein Fakt kommt,
  stellt die View aus dem Modell her).
* **Resize-Modus bleibt nach dem Commit (Option B, 30.10.)** — mit FRISCHEM
  Record: Der Rückkehr-`ResizeModeState` liest das Objekt nach dem Commit per
  `findById` neu (das geschnappte target trägt die Alt-Geometrie; ohne
  Fresh-Read hitesteten die Griffe gegen veraltete Positionen — User-Fund
  „Griffe nicht mehr greifbar"). Regel für unveränderliche Modelle: **Nach
  jedem Commit, der in einen State mit Objekt-Bezug zurückkehrt, frisch
  lesen.** Dieselbe Regel gilt seither 17.11. für den Griff-Hit-Test selbst:
  `ResizeModeState` liest das Ziel zum PRESS-Zeitpunkt frisch — Modell-
  änderungen zwischen Modus-Eintritt und Press (Undo/Redo eines Moves oder
  Resizes) altern das Entry-Record sonst, und die sichtbaren Griffe (View
  liest frisch) wären nicht mehr greifbar; das frische Record wandert weiter
  in den `HandleDragState`, damit Vorschau und Commit gegen die aktuelle
  Geometrie rechnen.

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
Auswahl-Stauben über ConnectionsRemoved), `PersistenceTest` (03.11.) die
Persistenz als reinen Event-Flow (Gson-Roundtrip über String mit vollem
Modell inkl. Anker, demo2-Format-Fixture aus doc/spec.md lädt 1:1 —
Radius aus Durchmesser, fehlende Felder → demo3-Defaults; DiagramReset
ersetzt komplett, leert Auswahl + Undo-History; defekte Einträge/leere
Abschnitte tolerant; malformed JSON wirft statt still zu laden),
`UiSessionIntentsTest`
die State→UI-Hinweise und Initial-Queries, `ConnectionViewMapperTest` (09.11.)
die Pfeil-Geometrie headless — JavaFX-Shapes (Path/Polygon/Group/Rotate)
funktionieren ohne gestartetes Toolkit, deshalb ist die View-Geometrie
ohne UI messbar (Preview-Override am Zielrand, Modell-Restore, Commit),
`CursorHintTest` (10.11.) die Gesten-Cursor-Hinweise des Kerns (Pan/Move
greifen einmalig mit CLOSED_HAND zu, Klick-ohne-Drag bleibt still,
Release gibt frei; inkl. Undo-Regression: Hinweise sind KEINE
Undo-Punkte), `CursorPresenterTest` (10.11.) die Cursor-Auflösung
headless (Prioritäten Kern-Geste > Band/Verbindung > Griff-Hover >
Hover-Affordanzen > Werkzeug-Grundcursor, Griff-Geometrie mit
Zoom-Skalierung, JavaFX-Übersetzung am Systemrand),
`ModeTargetRemovalTest` (17.11.) die Invariante „ein Modus endet mit
seinem Ziel" auf Modell-Pfaden (Undo der Erstellung beendet den
Resize-Modus / Undo der Verbindung den RouteEditMode je mit null-Hinweis
und Rückkehr zum Idle; unbezogene Entfernungen lassen den Modus
bestehen) UND den Fresh-Read des Griff-Hit-Tests (Undo eines Moves oder
Resizes im Modus: Griffe greifen an der wiederhergestellten Position,
Modus überlebt),
`EventGeometryTest` (17.11., Richtungen 26.11.) die reine Event-Geometrie
(Andockpunkt bei ¼ — oben/je Richtung, Spiegelung links/rechts,
OUTGOING als gespiegelte+umgedrehte Kette, 90°-Bogen mit Kreis-Treue
und Klemmung, Hit auf Kette inkl. Bogen je Richtung, Label-Anker je
Richtung, Ketten-Bounds), `EventModelTest` (17.11./23.11./26.11.) den
Service-Flow (Create mit Event+Label und 0..1-Verteidigung JE RICHTUNG,
Koexistenz von INCOMING und OUTGOING am selben R, Outgoing-Andock an
der Unterkante mit Arm-Mitten-Label, SetEventSide
mit gespiegelter Spitze und Undo, Flip-Undo OHNE Doppel-Move des Labels,
ResizeEvent mit Klemmung, transitive Move-Expansion von R über E zum
Label, Re-Dock bei R-Resize mit Undo, Lösch-Kaskade der ganzen Kette
inkl. Restore, Hit-Test mit Label-Überlappungs-Präzedenz,
Nicht-Connectbarkeit, Load mit frischem Re-Dock, Band-Treffer),
`EventToolTest` (17.11./23.11./26.11.) die Gesten (Quadrant-Klick →
Erstellung OHNE bestehendes Event der adressierten Richtung — auch OE
ohne Modifier, Eigen-Quadrant → EditEventLabel, horizontal anderes
Quadrant → SetEventSide + Label-Spiegelung, Press auf die Label-Textbox →
EditEventLabel, untere Quadranten → OUTGOING-Intents inkl. Koexistenz
neben bestehendem INCOMING, oberes Quadrant ignoriert ein bestehendes
Outgoing, Shift-Regression (Modifier tot), achs-gebundene Form-Drags mit
genau einem
Commit, Klemmung als EIN Undo-Punkt, ESC-Abbruch ohne Commit,
KEY_E/KEY_K, ESC)
und `EventRendererTest` (17.11., Richtungen 26.11.) die Pfeil-Geometrie
headless (Start→Bogen→Spitze mit Einkerbung, Spitze auf der Oberkante
nach unten gedreht, gespiegelte linke Seite, gezogene Form; OUTGOING:
Fuß→Bogen→Ende, Pfeil am äußeren Ende waagrecht nach außen — 0°/180°),
`PersistenceTest` (23.11./26.11.) zusätzlich Event-Roundtrip mit Label im
texts-Abschnitt, OUTGOING-Roundtrip mit direction-Feld + Legacy-Default
INCOMING, die Migration alter EVENT-label-Felder zu owned Texten
und die Toleranz ohne label-Feld,
`EventOwnerRefreshTest` (23.11., Bugfix „Pfeil klebt nach Undo des
R-Moves") den owner-derivierten View-Refresh headless: R-Move + Undo
zeichnet den Pfeil wieder an der alten Position (Modell- und Replay-
Fakt-Asserts inklusive), Negative Probe mit ausgeschaltetem Refresh-
Schalter reproduziert exakt den User-Befund, Redo folgt vorwärts, und
ein weit gezogenes Label bleibt von Owner-Refreshes unberührt,
`BusTestSupport` ist der geteilte
Test-Rahmen — **271 Tests gesamt, alle grün**.
**Jedes neue Interaktions-Feature bekommt einen solchen Event-basierten Test.**

### 9. UI-Schicht („Erstes Licht" 30.10. → Inline-Editor 02.11. → Persistenz 03.11.)
* **`Main`** (`de.fmc.editor`): Bootstrap in Produktions-Reihenfolge —
  Services + Bus konstruieren, DANACH `attachTo` (Zirkularität, identisch
  zu `BusTestSupport`), danach View/PersistenceController/Toolbar/Übersetzer
  verdrahten, `requestFocus` auf die Zeichenfläche. Default-Ebene =
  `PersistedLayers.STANDARD_LAYER_ID` (Dateien einheitlich). Start:
  `mvn javafx:run`.
* **`ui`-Paket (Systemrand):** `InputEventTranslator` übersetzt JavaFX-Events
  in `InputEvent`s (immer SCREEN-Koordinaten — die Welt-Umrechnung macht der
  Kern über `CameraInfo`). CTRL+Z → `RequestUndo`, CTRL+Y / CTRL+SHIFT+Z →
  `RequestRedo` als DIREKTE ActionEvents: `CustomKeyCode` kennt Z/Y bewusst
  nicht. CTRL+S/CTRL+O (03.11.) sind dagegen reine UI-Flows (FileChooser) —
  der Übersetzer greift sie am Systemrand ab und reicht sie an den
  `PersistenceController` durch, es gibt dafür bewusst keine Core-Events.
  **Ziel-Greife** (User-Fund 02.11.): Der Übersetzer fasst nur Events
  an, deren ZIEL der Canvas-Root selbst ist — der Inline-Editor ist ein Kind
  des Root, seine Tasten und Klicks liefen sonst durch die Capture-Phase in
  den Kern (Werkzeug-Buchstaben wechselten das Tool, CTRL+Z/ESC leakten).
  Overlays sind damit automatisch außen vor, wie die Toolbar als Sibling.
  `UiMouseState` hält die Mausposition (Sitzungszustand der UI).
  `ToolbarController`: reiner Publisher (`SelectTool`, `RequestUndo/Redo`,
  `SetGridSize`), Sync über `ToolChanged`; KEIN Select-Button (kein aktiver
  Toggle = SELECT, demo2-Stil); seit 17.11. ein Event-Button (Shortcut E,
  Icon = Miniatur des gebogenen Pfeils); Sektion „Datei" mit Speichern/Laden-Buttons
  (Actions durchgereicht aus dem `PersistenceController` — die Toolbar hat
  keinen Datei-Zugriff); Sektion „Snap to Grid" mit freiem numerischem
  Eingabefeld (Validierung: Zahl > 0, Komma erlaubt; ungültige Eingabe stellt
  auf den letzten gültigen Wert zurück; Commit bei ENTER und Fokus-Verlust);
  alle Controls `setFocusTraversable(false)` — Shortcuts bleiben auf der
  Zeichenfläche. **Fokus-Disziplin** (User-Fund 1.11.): Ein Textfeld MUSS den
  Fokus zum Tippen nehmen — ENTER committet und ESC verwirft, beide geben den
  Fokus per Hook an die Zeichenfläche zurück; und JEDER Klick auf die Fläche
  holt den Fokus aktiv zurück (`InputEventTranslator.onMousePressed` —
  JavaFX-Regionen übernehmen ihn beim Klick nicht von selbst). Ohne dies
  erreichten ESC/DEL/Shortcuts den Übersetzer nie wieder.
* **`PersistenceController` (03.11.):** Speichern & Laden am Systemrand.
  Speichern = Lesewissen über die Query (wie Hover): FileChooser →
  `DiagramCodec.export` → `PersistenceService.saveDiagram`. Laden =
  Schreibpfad über den Bus: Datei → `PersistenceService.loadDiagram` →
  `DiagramCodec.toLoadRequest` → `LoadDiagram`-Request; danach
  `SelectTool(SELECT)`, damit Modi des alten Dokuments (RouteEditMode/
  ResizeMode/Connecting) enden. Fehler (IO, malformed JSON) als Alert —
  demo2 warf printStackTrace und lief still weiter (nicht kopieren); bei
  Lade-Fehler bleibt das alte Dokument unangetastet. Nur „Speichern unter"
  (User-Entscheidung 03.11.): kein gemerkter Datei-Pfad.
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
  Seit 17.11. zusätzlich die Event-Label-Zweige (seit 23.11. mehrzeilig wie
  alle Texte): `CreateEventFor` (Editor am mitgegebenen Label-Anker,
  Commit als `CreateEvent` — erzeugt Event UND Label-Textbox im Service,
  mit SEITE und RICHTUNG aus dem Intent; Modellkontakt erst hier, leerer
  Commit verwirft still) und
  `EditEventLabel` (löst zum owned Text des Events auf — vorhanden:
  derselbe EDIT_TEXT-Weg wie bei jedem Text; fehlt er: Neuanlage am
  abgeleiteten EventGeometry-Anker über den AddElement-Weg).
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
  **Events (17.11.):** `EventRenderer` zeichnet das Event-Symbol (Kind 0 =
  Path für Stroke/Glow, Kind 1 = Pfeilspitze — das Label ist seit 23.11.
  ein eigenes Text-Objekt mit eigenem Node) komplett aus `EventGeometry` —
  die View rechnet nie selbst; `ArrowShape` ist die
  extrahierte Pfeilspitzen-Form (shared von Connection- und Event-Pfeil).
  **Owner-derivierter Refresh (23.11., Bugfix „Pfeil klebt nach Undo des
  R-Moves"):** Da die Pfeilgeometrie aus dem OWNER abgeleitet wird, frischt
  die View die Event-Nodes bei JEDER Owner-Änderung sofort auf — im Move-
  Pfad (`onObjectsMoved` → `refreshEventNodesAgainst`) wie im Resize-Pfad
  (`onObjectResized`). Beim Undo läuft die inverse Wiedergabe RÜCKWÄRTS:
  Der eigene Move-Entry des Events zeichnet noch gegen den ALTEN
  Owner-Stand; erst der Owner-Restore konvergiert die View. Regressionstest
  `EventOwnerRefreshTest` (headless) inkl. Negative Probe über den
  package-private Schalter `REFRESH_OWNER_DERIVED_NODES`.
  Die zwei Formgriff-Marker liegen im uiLayer und sind NUR im Event-Werkzeug
  sichtbar (ToolChanged-getrieben, denselben Klebe-Refresh wie die
  Resize-Griffe: Objekt-Fakten und Event-Drag-Preview speisen sie);
  während des R-Resize klebt die Event-Spitze an der PREVIEW-Kante
  (refreshEventNodesAgainst — Modell-Fakten erst beim Commit).
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
* **Cursor-Präsentation (10.11.):** Der Mauszeiger spiegelt den Interaktions-
  zustand. Auflösung als reine Funktion in `CursorPresenter` (headless
  testbar, Prioritäten: Kern-Geste > laufende Band-/Verbindungs-Geste >
  Griff-Hover im Resize-Modus > Hover-Affordanzen im Auswahl-Werkzeug >
  Werkzeug-Grundcursor); die Übersetzung nach JavaFX macht allein
  `CursorPresenter.toFx` am Systemrand — der Kern kennt nur das Enum
  `CustomCursor` (`core.event`, analog `CustomKeyCode`). Bewusst SPARSAMER
  Kern-Hinweis: `CursorChanged` (cursor=null = Override freigegeben)
  publizieren nur `CameraPanningState` und `MovingState` — einmal pro
  Geste, nicht pro Drag-Segment; Klick-ohne-Drag bleibt still. Alles
  Darstellende leitet die View selbst ab: Werkzeug-Grundcursor aus
  `ToolChanged`, Gummiband/Verbindungsaufbau aus den vorhandenen
  Preview-Hinweisen (Fadenkreuz), richtungsabhängige Resize-Cursor über
  den Griffen aus ihrem Hover-Lesewissen (derselbe Grab-Radius 6 px/Zoom
  wie der Kern-Hit-Test — der Cursor zeigt exakt die Greif-Fläche), MOVE
  über Objekten und HAND über Verbindungsketten nur im Auswahl-Werkzeug
  (im Verbindungs-Werkzeug wäre MOVE über dem Ziel gelogen). Griff-Drags
  kosten keinen eigenen Zustand: Unter gebundener Taste feuert JavaFX
  keine MOUSE_MOVED-Events, der richtungsabhängige Cursor klebt für die
  Geste; beim Vorschau-Ende rechnet die View neu. `updateCursor()` liest
  das Resize-Ziel FRISCH per findById (O(1)) — das Record vom Modus-
  Eintritt trüge nach einem Commit die Alt-Geometrie („Griffe nicht mehr
  greifbar"). `DiagramReset` entsorgt Override/Gesten-Flags des alten
  Dokuments.
* **State→UI-Hinweis-Intents** (in `ActionEvent`, KEINE Modell-Fakten — der
  UndoService ignoriert sie wie Kamera/Selektion): `ToolChanged` (vom Bus
  nach jedem Tool-Übergang), `ConnectionPreviewChanged` (sourceId=null
  räumt die Vorschau auf), `ResizeModeChanged` (target=null = Griffe weg),
  `WaypointFocusChanged` (connectionId=null = W-Kreise weg — treibt seit
  Block 2 die W-Sichtbarkeit, die Toolbar-Checkbox ist entfallen),
  `BoxSelectPreviewChanged` (startX=null räumt das Band-Rechteck auf) und
  `CursorChanged` (Gesten-Cursor des Kerns, cursor=null = Override
  freigegeben — siehe „Cursor-Präsentation" unten). Muster: Versteckter
  Kern-Zustand quert die Grenze als Intent — die UI besitzt ihn nie
  selbst. Prinzip: **Bus für Zustandsänderungen, Query für Lesewissen —
  Hover ist Lesewissen.**
* **Bekannte Restchen:** View-z-Ordnung folgt der Fakt-Reihenfolge
  (Restauration landet visuell oben — TODO im `GraphView`); Preview ohne
  Rundung auf dem freien Abschnitt; Shortcuts K/R/C/T/E wirken nicht in den
  langen Modi (Connecting/ResizeMode/RouteEditMode) — nur Toolbar-Klick
  und ESC beenden sie; Event-Formgriffe sind nur im Event-Werkzeug
  greifbar (Ctrl+Klick-Zugang aus dem Auswahl-Werkzeug als möglicher
  Nachzug, bewusst erst nach Alltagstest — User-Entscheidung 17.11.);
  die Standard-Label-Box (140×60 wie bei allen neuen Texten) überlappt
  den Pfeil — Klicks dort treffen das z-obere Label (beide Ziele enden
  im Event-Werkzeug im selben Editor; im Auswahl-Werkzeug selektiert der
  Klick den bewegbaren Text — dokumentiert im EventModelTest);
  das Layer-System (Sichtbarkeits-Steuerung als
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
  * **Serialisierung:** ✅ (03.11.) `Text`/`label`/`content`/`anchor` sind im
    DTO (`PersistedText` mit demo2-Feldnamen text/parentObjectId für
    Altvorlagen-Kompatibilität); EINE Auswahl-Menge statt demo2s zwei
    parallelen Sets war bereits durch das UUID-basierte SelectionService-
    Design gewährleistet.
* **Persistenz/Serialisierung:** ✅ Implementiert (03.11.) — Speichern
  („Speichern unter", CTRL+S) & Laden (CTRL+O) im demo2-JSON-Format
  (Beispiel: `doc/spec.md`) plus `texts`; DTO-Abbildung im `DiagramCodec`
  (`Circle.radius` ↔ width/height = Durchmesser), Connections per
  `waypointIds`, Export in z-Ordnung. Details: Abschnitt 3 „Persistenz"
  und Abschnitt 9 (`PersistenceController`). Offen: Layer-System (der
  layers-Abschnitt wird mit Fixliste geschrieben, bis es existiert).

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
