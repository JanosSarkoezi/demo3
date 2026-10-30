# DEVLOG

Chronik der Arbeitssessions — die neuesten Einträge stehen OBEN. Format: Datum / Was / Warum so / Grenzen & Verbesserungskandidaten.
(Die aktuelle Architektur-Wahrheit steht in der AGENTS.md — hier nur Entscheidungen und ihre Begründung.)

## 30.10.2026 (9. Arbeitsblock) — Resize: Option B + Fresh-Read-Fix + Live-Vorschau

**Was:**

- User-Fund aus dem visuellen Test (mit der neuen State-Trace als
  Diagnose-Hilfe im Gespräch): Nach einem Resize bleibt der Modus zwar
  (Option B, User-Entscheidung), aber die Griffe sind NICHT mehr greifbar.
- Ursache: `HandleDragState` kehrte mit dem GESNAPPTEN target zurück —
  unveränderliches Record mit ALT-Geometrie. Die View zeichnete die Griffe
  aus Fakten (neue Positionen), der State hitestete gegen das alte Record —
  Griff-Klicks verfehlten und galten als „Klick daneben" (Modus verließ
  sich still selbst).
- Fix: Nach dem Commit frisch per `findById` lesen (der Bus ist synchron —
  nach dem Request hält das Modell die neue Geometrie). Neue Regel für
  unveränderliche Modelle: Nach jedem Commit, der in einen State mit
  Objekt-Bezug zurückkehrt, frisch lesen.
- Live-Vorschau (User-Wunsch, Beobachtung 2): `ResizePreviewChanged`-Hinweis
  pro Drag-Segment; `computeGeometry` extrahiert, Vorschau und Commit teilen
  dieselbe Methode (laufen nie auseinander). View wendet Hinweis-Geometrie
  an und lässt die GRIFFE mitwandern; Null-Hinweis beendet (Commit UND
  Abbruch — beim Abbruch stellt die View aus dem Modell her, da kein Fakt
  kommt). Hinweise sind kein Undo-Punkt; Commit bleibt EIN Fakt.
- `ResizeFlowTest` (5 Tests): Griffe nach Commit erneut greifbar (Regression),
  Vorschau = Commit-Mathematik, Grid-Klemmung in der Vorschau, ESC ohne
  Commit räumt auf, Hinweise sind keine Undo-Punkte.

**Warum so:**

- Die Live-Vorschau folgt dem etablierten Hinweis-Muster (dritte Instanz
  nach ToolChanged und ConnectionPreviewChanged): Versteckter Kern-Zustand
  quert die Grenze als Intent, die View besitzt nie eigene Geometrie-Logik.
  Der entscheidende Unterschied zu demo2: Dort hätte die View die
  Vorschau selbst rechnen können (oder müssen) — hier bleibt der Kern die
  einzige Rechen-Wahrheit, inklusive Grid-Klemmung.

**Grenzen & Verbesserungskandidaten:**

- Text-Objekte können weiterhin nicht in den Resize-Modus (Doppelklick-Guard
  nur K/R) — HandlePositions kennt Text-Boxen bereits; Öffnung folgt mit
  Block 3 (Text-Editor), wenn Text-Boxen editierbar werden.
- `mvn test`: 119/119 grün (5 neue).

## 30.10.2026 (8. Arbeitsblock) — Fix: Zoom schwenkte zum Inhalts-Schwerpunkt statt zum Mauszeiger

**Was:**

- User-Report nach dem Null-Delta-Fix: Zoom wirkt, aber nicht am Mauszeiger
  zentriert. Kern-Mathematik war korrekt und testgedeckt
  (`CameraInfo.zoomAt` hält den Pivot fixiert) — der Fehler saß in der
  ANWENDUNG: `GraphView.applyCamera` nutzte die Property-API
  (`worldGroup.setScaleX/Y`), die in JavaFX um das BOUNDS-ZENTRUM
  pivotiert, nicht um den Ursprung. Die Kamera-Gleichung
  (screen = world·zoom + offset) verlangt Ursprungs-Pivot — die Ansicht
  schwenkte deshalb um den Inhalts-Schwerpunkt, und Hit-Tests wären ab
  zoom ≠ 1 ebenfalls verstimmt gewesen.
- Beweis vor Fix: PivotProbe (Mini-JavaFX-App) rechnete beide Varianten
  durch — Property-Scale: (100,100) → (95,95); explizite Scale-Transform:
  (100,100) → (200,200) = exakt die CameraInfo-Erwartung.
- Fix (1:1 demo2): `zoomTransform = new Scale()` in
  `worldGroup.getTransforms()` — Transform-OBJEKTE pivotieren am Ursprung —
  plus `setTranslateX/Y` für den Offset. Genau demo2s GraphView-Struktur.

**Warum so:**

- Gleiche Fehlerfamilie wie der unsichtbare-R-Bug: ähnlich klingende API,
  andere Semantik. Zweite Instanz der Regel: demo2-Strukturen wörtlich
  portieren, nicht „sinngemäß modernisieren" — das Vorbild hatte für jede
  dieser Fallen schon eine Lösung.
- Die Probe-Methodik (Mini-App rechnet die Transform-Semantik isoliert
  durch) hat den Bug bewiesen, ohne auf visuelles Testen warten zu müssen.

**Grenzen & Verbesserungskandidaten:**

- `mvn test`: 114/114 grün (Kern unverändert — der Bug war reine View-
  Anwendung, erneut die Bestätigung dass die Schichtentrennung greift).

## 30.10.2026 (7. Arbeitsblock) — Fix: Mausrad-Zoom (Null-Delta-Zappeln eliminiert)

**Was:**

- User-Report „Zoom funktioniert nicht". Live-Instrumentierung (temporäre
  Debug-Prints in Translator + View, User scrollte im echten Fenster)
  zeigte: Die komplette Kette war intakt — ABER das Log enthüllte pro
  Mausraste ein **Null-Delta-Begleit-Event** (VNC-Desktop liefert
  deltaY=0.0 plus das echte ±40). Die Zoom-Regel behandelte 0 wie
  «nach unten» → pro Raste rein-und-raus-Zappeln, netto fast wirkungslos.
- Zwei versäumte demo2-Portierungen nachgeholt:
  1. **delta==0-Wächter** (demo2 GraphView, Zeile 68 — beim ersten Port
     hatte ich nur den CTRL-Teil der Zeile diskutiert, den delta-Teil
     übersehen): Null-Delta-Scrolls werden still ignoriert.
  2. **Zoom-Klemmung 0.2–10.0** (demo2 MIN_SCALE/MAX_SCALE) — jetzt in
     `CameraInfo.zoomAt` via `Math.clamp`, als einzige Wahrheit.
- Doppel-Zoom aufgeräumt: Die Zoom-Regel existierte BOTH in
  `CameraIdleState` (Regel 3) UND `IdleToolState` — in SELECT zoomte jede
  Raste doppelt (1.1², im Test als 1.21 entlarvt). Zoom gehört allein der
  Kamera-Maschine: Sie läuft vor jedem Tool-Dispatch und zoomt damit in
  JEDEM Werkzeug — das demo3-Äquivalent zu demo2s Controller-Zoom.
- Neue Tests: einmaliger Zoom je Werkzeug (CIRCLE/TEXT/SELECT),
  Null-Delta-ignoriert (Kamera unverändert), Klemmung an beiden Grenzen.

**Warum so:**

- Debug-Methodik-Lehrstück: Der JavaFX-Robot-Probe scheiterte an der
  geteilten Remote-Desktop-Umgebung (keine System-Events injizierbar).
  Der entscheidende Befund kam durch Live-Instrumentierung mit dem USER
  als physischem Aktuator — auf Remote-Desktops ist der User der
  zuverlässigste Test-Treiber, und die Debug-Prints machen sein Handeln
  für den Agenten sichtbar.

**Grenzen & Verbesserungskandidaten:**

- CTRL+Wheel bewusst NICHT übernommen (spec: „Zoom … mit dem Mausrad",
  ohne Modifier). Randnotiz X11: Wheel-Events gehen ans FOKUSSIERTEN
  Fenster — ohne vorherigen Klick ins Editor-Fenster kann eine Raste ins
  Terminal statt in den Editor gehen.
- `mvn test`: 114/114 grün.

## 30.10.2026 (6. Arbeitsblock) — Fix: unselektierte Rechtecke waren unsichtbar

**Was:**

- User-Diagnose aus dem visuellen Smoke („Sichtbarkeitsproblem"): Ein per
  R-Tool erzeugtes Rechteck blieb unsichtbar, erschien erst bei Selektion
  (blauer Glow) und verschwand beim Deselektieren wieder. Modell, Position
  und Hit-Test waren die ganze Zeit korrekt — reines View-Problem.
- Ursache: `GraphView.applyStyle` entschied über
  `instanceof javafx.scene.shape.Rectangle`, ob die gestrichelte Box eines
  TEXT-Objekts nur bei Selektion sichtbar ist. Kind 0 eines FMC-Rechtecks
  ist aber derselbe JavaFX-Typ — der Check traf JEDES R und setzte es auf
  `setVisible(selected)`. Kreise (Kind 0 = javafx Circle) waren vom Check
  nie betroffen — daher die scheinbar paradoxe Asymmetrie K sichtbar /
  R unsichtbar.
- Fix: Typ-Entscheidung über das MODELL (`diagramQuery.findById(id) …
  instanceof Text`), nicht über den View-Node-Typ; der Node-Check bleibt
  nur als Absicherung daneben.

**Warum so:**

- Zwei Modell-Typen teilen sich denselben JavaFX-Node-Typ (R-Shape und
  Text-Box sind beides `javafx.scene.shape.Rectangle`) — `instanceof` auf
  Node-Typen ist damit mehrdeutig. Typ-Entscheidungen gehören an die
  Modell-Wahrheit (Query), View-Typen sind Implementierungsdetail.
- Dritter Fund des User-Smoke-Tests (nach Koordinaten-Basis und dem
  R-Verdacht selbst): Der Prozess „Kern grün + visuelle Prüfung durch den
  User" fängt genau die Fehlerklasse, die Headless-Tests prinzipiell
  nicht sehen können.

**Grenzen & Verbesserungskandidaten:**

- Die Text-Box-Logik wäre noch robuster mit einem expliziten Marker am
  Node (z. B. `setUserData`) statt Modell-Rückfrage pro Stilwechsel —
  akzeptabler Kompromiss: `findById` ist O(1) und applyStyle läuft nur
  bei Selektions-/Hover-Diffs.
- `mvn test`: 111/111 grün; Smoke ohne Exceptions.

## 30.10.2026 (5. Arbeitsblock) — Koordinaten-Basis-Fix: Klick = Objektmitte

**Was:**

- User-Beobachtung aus dem visuellen Smoke-Test: Objekte entstehen nicht
  am Klickpunkt, sondern versetzt. Ursache: `InputEventTranslator`
  publizierte `getSceneX()/getSceneY()` (SZENE-Global — schließt die
  Toolbar-Breite ein), während die Kern-Kamera und der `GraphView`-Hover
  zeichenflächen-LOKALE Koordinaten erwarten (`event.getX()/getY()`).
  Mit der Toolbar oben war der Fehler nur ~40 px vertikal (subtil), seit
  dem Seitenpanel-Layout ~174 px horizontal — sichtbar falsch.
- Fix: alle Maus-/Scroll-Koordinaten im Translator auf `getX()/getY()`
  (flächen-lokal), konsistent mit `GraphView`-Hover und der
  `CameraInfo`-Basis; Javadoc dokumentiert die Konvention.

**Warum so:**

- Eine Koordinaten-Basis pro System: „Screen" im Kern heißt immer
  „relativ zur Zeichenfläche" — die Szene gehört der UI-Anordnung
  (Toolbar), nicht der Kamera. Der Bug war latent seit „Erstes Licht",
  aber erst die Toolbar-Links-Umstellung machte ihn sichtbar — ein
  Lehrstück dafür, warum der visuelle Smoke-Test des Users zum Prozess
  gehört: Headless-Tests können diese Klasse von Fehler prinzipiell
  nicht sehen (der Kern rechnete konsequent mit der falschen Basis
  weiter; alle Kern-Tests blieben grün).

**Grenzen & Verbesserungskandidaten:**

- K/R entstehen jetzt mittig zum Klick (Kern-Semantik war längst richtig —
  nur die Basis war falsch). Freie Texte folgen mit Block 3: Das Modell
  hat die Mittelpunkt-Konvention für Text-Boxen bereits verbindlich
  (`Text.x/y` = Mittelpunkt), der künftige Editor-Overlay setzt sie um.
- `mvn test`: 111/111 grün; Smoke ohne Exceptions.

## 30.10.2026 (4. Arbeitsblock) — Toolbar im demo2-Look

**Was:**

- `ToolbarController` auf demo2s Erscheinungsbild umgebaut (User-Wunsch
  „visuell wie demo2"): Seitenpanel statt Leiste oben (VBox, 150 px,
  Sektionen „Shapes"/„Bearbeiten"/„Settings"), Icon-Graphics aus
  demo2s FXML (Kreis/Rechteck/blaue Linie/fettes „A"), Tooltips mit
  UNSEREN Shortcuts (K/R/C/T statt demo2s Alt-Kombis), Vollbreiten-Buttons,
  CSS 1:1 portiert (`src/main/resources/graph/css/style.css`):
  Hover #e8f4fd, Selektion #dceefb mit blauer Akzentleiste (#3498db).
  `Main`: Toolbar jetzt `BorderPane.setLeft` statt `setTop`.
- demo2s „Bearbeiten"-Lücke gefüllt: Undo/Redo als Vollbreiten-Buttons
  (demo2 hatte keine). Text-Sektion (Font/Bold/Italic), Snap/Sticky,
  Speichern/Laden bewusst NICHT portiert — tote Controls ohne Funktion;
  die Blöcke 2–4 bringen sie mit Funktion.

**Warum so:**

- Visuelle Parität ohne Funktionslüge: Nur Controls, die wirklich wirken.
  demo2s Initial-Zustand (Kreis vorselektiert) wurde NICHT übernommen —
  demo3s Semantik ist „kein Toggle = Select".

**Grenzen & Verbesserungskandidaten:**

- Beim Portieren entdeckt: demo2s `.graph-grid`-CSS war KAPUTT — JavaFX
  erlaubt keine Gradients in `-fx-background-image` (nur url()); die Regel
  erzeugte auch dort nur eine Parse-Warnung und renderte nie. Bewusst
  NICHT portiert. Ein sichtbares Raster gehört später als echte View-Logik
  (zusammen mit Move-Snapping, Block 2) in die worldGroup, nicht als
  statisches CSS-Screen-Muster.
- `mvn test`: 111/111 grün; `mvn javafx:run`: Smoke ohne Warnungen.

## 30.10.2026 (3. Arbeitsblock) — „Erstes Licht": UI-Schicht (orchestriert)

**Was:**

- Orchestrator-Block in vier Lanes: Explorer-Lotse minete demo2 (Ergebnis
  u. a.: demo2 nutzte KEIN Canvas, sondern einen Scene-Graph mit
  mouseTransparent-Picking-AUS — die „zwei Wahrheiten"-Falle der
  Canvas-Meditation trat dort nie ein; User-Entscheidung nach Vorlage des
  Befunds: **Scene-Graph stumm** statt Canvas).
- Kern-Zusätze (eigene Lane, test-first): Drei State→UI-Hinweis-Intents —
  `ToolChanged` (Toolbar-Sync auch bei Kern-Shortcuts K/R/C/T, vom Bus als
  Folge-Event publiziert), `ConnectionPreviewChanged` (Kette beim
  Verbindungsaufbau; sourceId=null räumt auf), `ResizeModeChanged`
  (Griffe an/aus) — alle Sitzungszustand wie Kamera/Selektion, bewusst
  KEINE Modell-Fakten, Undo unberührt. Initial-Queries
  `allObjectsInZOrder()`/`allConnections()` auf DiagramQuery.
  `SelectTool`-Regeln für die zwei LANGEN Modi (ConnectingState,
  ResizeModeState), damit Toolbar-Klick sie sauber beendet statt sie
  hängen zu lassen.
- Lane Übersetzer (fixer): `InputEventTranslator` + `UiMouseState` —
  CTRL+Z → `RequestUndo`, CTRL+Y / CTRL+SHIFT+Z → `RequestRedo` als
  direkte ActionEvents (CustomKeyCode kennt Z/Y bewusst nicht). Beim
  Verify gefunden und gefixt: CTRL+Y landete zunächst in RequestUndo.
- Lane Toolbar (fixer): `ToolbarController` — Toggle-Exklusivität manuell
  verwaltet (`setAllowDeselect` erst ab JavaFX 23), `ToolChanged`-Sync,
  Undo/Redo-Buttons, Wegpunkte-Checkbox (reine UI-Sichtbarkeit, nie ein
  Bus-Event). Integrations-Fix: `setFocusTraversable(false)` auf allen
  Controls — sonst klauen Toolbar-Klicks den Tastaturfokus und Shortcuts
  landen nicht mehr auf der Zeichenfläche.
- Lane View (fixer): `GraphView` + `RoutingStrategy`/`RoundedCorner`/
  `Straight` + `ConnectionViewMapper` — demo2-Algorithmen 1:1 (Pfeilspitze
  arrowSize 12 / indent 0.65, Ecken via d=r·cot(θ/2) mit Klemmung),
  Kamera nur APPLIZIERT aus `bus.getCameraInfo()` (nie selbst gerechnet),
  Hover per `findTopmostAt`-Query („Bus für Zustandsänderungen, Query für
  Lesewissen — Hover ist Lesewissen"), Preview aus Hinweis + eigenem
  Maus-Tracking, Resize-Griffe aus `HandlePositions`.
- Integration (eigener Part): `Main` — Bootstrap identisch zum getesteten
  BusTestSupport-Muster (zweiphasige Initialisierung), Verdrahtung aller
  Lanes, requestFocus auf die Zeichenfläche.

**Warum so:**

- „Lesen delegieren, Bauen integrieren": Der Lotse destillierte demo2-
  Semantik und Portabilität; die Fixer-Lanes arbeiteten gegen exakte
  Verträge (`GraphView(bus, query)` / `viewRoot()` / `setWaypointsVisible`)
  mit getrennten Schreibbereichen parallel; Konventionen und Integration
  blieben in einem Kontext. Kohärenz schlug Parallelität dort, wo die
  Dateien ineinander greifen.
- Alle drei Hinweis-Intents folgen einem Muster: Versteckter Kern-Zustand,
  den die UI nicht besitzen kann (aktives Werkzeug, laufende Kette,
  Resize-Modus), quert die Grenze als Intent — wie die Text-Editor-Intents
  vorher. Nie als Modell-Fakt: Der UndoService bleibt unberührt.

**Grenzen & Verbesserungskandidaten:**

- View-z-Ordnung folgt der Fakt-Reihenfolge: Restaurierte Objekte landen
  visuell OBEN statt an der Originalposition (TODO im GraphView;
  daten-seitig ist z exakt — ZOrderRestoreTest grün).
- Verbindungs-Preview nutzt gerade Segmente (freier Abschnitt ohne
  Rundung) — bewusst einfach.
- Shortcut-Klicks (K/R/C/T) wirken nicht in langen Modi — nur Toolbar und
  ESC beenden sie (dokumentiertes Block-1-Restchen).
- Offene Blöcke: Move-Snapping + Checkbox (Kern zuerst), Inline-Text-Editor
  (Intents warten seit 28.10.), Layer-System, Persistenz inkl.
  Undo-Suspend beim Laden.
- `mvn test`: 111/111 grün; `mvn javafx:run` startet (20 s stabiler
  Smoke-Lauf: Bootstrap, Initial-Render, Event-Registrierung werfen nicht).

## 30.10.2026 (2. Arbeitsblock) — Waypoint-Semantik: Routenpunkte statt Objekte

**Was:**

- Produktdefinitions-Meditation mit dem User: Ws sind die Erfindung, den
  Polygonzug von K nach R zu formen — sie sind **Teil der Verbindung
  (Routenpunkte), keine eigenständigen Objekte**. Daraus ein Kompromiss-
  Beschluss (User-Idee, geschärft): Ws VOLLSELEKTierter Verbindungen
  (beide Endpunkte in der Auswahl) wandern IMMER mit — egal ob sichtbar;
  teilselektierte bleiben verankert; Feinsteuerung per direktem Griff
  (Klick auf die Verbindung blendet die Ws ein, siehe spec). Damit ist die
  Move-Menge eine reine Funktion der Auswahl-Basis — kein Sitzungszustand,
  keine Sichtbarkeitsabhängigkeit, Fall 3 (unsichtbare Ws) ohne Layer-
  System umsetzbar.
- `findTopmostAt`/`findObjectsInBounds`: Waypoint-Skip entfernt — Ws sind
  volle Klick- und Gummiband-Ziele; `boundsIntersect` bekommt einen
  Punkt-im-Rechteck-Fall für W.
- Neue Query `findMoveSet(basisIds)` (Default auf `DiagramQuery`,
  überschrieben im DiagramService): Basis + externe Label + Ws
  vollselektierter Verbindungen. `IdleToolState` ruft nur diese eine
  Query — das inline-Text-Expander-Wissen ist aus dem State raus (KI-
  Review deepseek #4 wurde damit konkret, statt spekulativ).
- `ConnectingState`: Klick auf ein vorhandenes W gilt wie Leerraum —
  setzt einen NEUEN Kettenpunkt statt das W als Ziel zu nehmen.
- `IdleToolState`-Doppelklick: Resize-Modus nur für K/R; Doppelklick auf
  ein W selektiert nur (Routenpunkte haben keine Griffe).
- `WaypointSelectionMoveTest` (8 Tests): Klick-Selektion, Gummiband,
  vollselektiert-mit / teilselektiert-bleibt, allein bewegt, Connect über
  vorhandenes W, Undo der impliziten W-Moves, Doppelklick ohne Resize.
  `BoxSelectionTest`-Spezifikation auf neue Semantik umgestellt (alter
  Test festschrieb das alte Verhalten).
- Doku: AGENTS.md (4 Stellen, inkl. präzisierter „Entschiedener Frage 3"
  mit dem Routenpunkt-Prinzip) und `doc/spec.md` (neuer Unterpunkt-Block
  zur W-Semantik im Verbindungs-Abschnitt, in der Spez-Stimme des Users).

**Warum so:**

- „Man besitzt nur, was man sieht" war die sichtbarkeitsabhängige Variante
  (unsichtbare Ws mitwandern lassen) — der User entschied sich für die
  einfachere, zustandsunabhängige Regel mit Feinsteuerungs-Luke über die
  Verbindung. Dieselbe Geste liefert damit immer dasselbe Modell-Ergebnis.
- Das Löschen behandelt Ws längst als zur Verbindung gehörig (Kaskade);
  jetzt tut es die Bewegung auch — symmetrische Zugehörigkeit.
- Implizite W-Moves laufen als sichtbare `ObjectsMoved`-Einträge durch den
  normalen Faktenweg — Undo erbt sie mechanisch (demo2-Lektion: nie
  versteckte Mitnahmen).

**Grenzen & Verbesserungskandidaten:**

- Geteilte Ws über zwei Verbindungen: konzeptionell eine Anomalie (ein W
  gehört einer Route) — Modell erlaubt sie noch (demo2-Erbe); wandert ein
  geteiltes W mit, verformt das die Fremd-Route mit (sichtbar im Fakt).
  Falls das je stört: Eindeutigkeit erzwingen oder beim Mitnehmen auf
  exklusives Ownership prüfen.
- Klick-Toleranz für Ws fest bei 5 Welt-Einheiten (`hit()`) — für zoom-
  abhängiges Greifen bei der UI-Anbindung revisitieren.
- `mvn test`: 100/100 grün (92 + 8 neue).

## 30.10.2026 — KI-Review-Runde 2: Faktencheck + drei Sofortmaßnahmen

**Was:**

- Drei externe KI-Reviews (chatgpt/deepseek/gemini, Runde 2 mit aktuellem
  Snapshot inkl. Tests) gegen den Code verifiziert: alle konkreten Claims
  dieser Runde bestätigt, keine widerlegt (Runde 1 war überwiegend stale).
  Bestätigt u. a.: DiagramService-Tendenz zur God-Class (662 Zeilen),
  Subscriber-Exceptions brechen die Bus-Verteilung (Fail-fast, bewusst),
  UI-Text-Intents ohne Konsument, Grid-Snapping nicht im Move-Pfad.
- Sofortmaßnahme 1 (deepseek #3): `PendingCreateOrPanState` erhält den
  auslösenden Maus-Button als Konstruktor-Parameter statt `PRIMARY` zu
  hardcodieren — alle drei Konstruktionsstellen (Circle, Rectangle, Idle)
  reichen `e.button()` durch; auch die Release-Regel vergleicht jetzt gegen
  den gespeicherten Button.
- Sofortmaßnahme 2 (deepseek #5): `ToolType.NONE` entfernt — ein Enum-Wert,
  den niemand setzt, ist eine Einladung, ihn irgendwann „mal" zu setzen;
  `ToolState`-Switch auf `case SELECT` reduziert.
- Sofortmaßnahme 3 (deepseek #7): `BusTestSupport` als geteilter Test-Rahmen
  (Bus + Services in Produktions-Reihenfolge verdrahtet, plus
  `closeGesture`/`addCircle`/`addRectangle`) — ersetzt die Duplikate in
  `UndoRedoTest` und `ZOrderRestoreTest`. Semantik-Unterschied bleibt
  sichtbar: ZOrderRestoreTest schließt JEDS Erstellen als eigene Geste
  (lokale Wrapper), UndoRedoTest entscheidet pro Test.

**Warum so:**

- Der Button-Parameter entfernt eine stille Annahme („Geste startet immer
  mit Linksklick"): Heute noch wahr, aber bei „Space+Drag" oder
  Rechtsklick-Pan wäre der Hardcode falsch geworden — 3-Zeilen-Änderung
  jetzt statt Debugging später.
- Nur bestätigte Findings umsetzen; widerlegte aus Runde 1 (Kamera-Snapshot,
  fehlende Selektion, ToolState-Duplikation) waren bereits erledigt —
  Beleg, dass der Faktencheck vor der Umsetzung kommen muss.
- `BusTestSupport` hält künftige Tests klein; die bewusste Entscheidung,
  OB ein AddElement ein eigener Undo-Punkt ist, bleibt beim Aufrufer
  (kein verstecktes Verhalten im Helfer).

**Grenzen & Verbesserungskandidaten:**

- Nicht umgesetzt (Design-Entscheidungen aussteigend): DiagramService-Split
  (am Persistenz-Meilenstein festmachen), Subscriber-Exception-Politik
  dokumentieren + Test, ConnectionRule-Signatur vor der zweiten Regel,
  Undo-nach-Load-Semantik (deepseeks Frage nach `suspendRecording()`).
- Offen aus Review + Nutzerwunsch: Waypoints als volle Selektions- und
  Gummiband-Ziele (Move-Maschinerie unterstützt es bereits; blockiert nur
  durch die Typ-Filter in `findTopmostAt`/`findObjectsInBounds`) — nächster
  Arbeitsblock.
- `mvn test`: 92/92 grün.

## 28.10.2026 (4. Arbeitsblock) — Text-Feature: ToolState-Zentralisierung, Text-Modell, TextToolState

**Was:**

- **Block A — `ToolState`-Zentralisierung:** Abstrakte Basisklasse für alle
  Werkzeug-States; Werkzeug-Wechsel per `SelectTool`-Event und Shortcuts
  K/R/C/T genau einmal zentral, mit reflexivem self-Check. Idle/Circle/
  Rectangle/Connect auf die neue Basis verschlankt — verhaltensidentisch
  (70/70 grün danach).
- **Block B — Text-Modell:** `Text` (Record, implements DiagramObject) mit
  Mittelpunkt-Konvention (x/y = Box-Zentrum, uniform mit K/R), `ownerId`
  (K/R, Connection oder null = frei), nullable `anchor` (`TextAnchor`:
  segmentIndex, t, offsetX/Y). `Circle` + `label`, `Rectangle` + `content`/
  `centered` (Kompat-Konstruktoren für Bestands-Fixtures).
  DiagramService: 5 neue Request-Handler (`SetCircleLabel`,
  `SetRectangleContent`, `UpdateText`, `SetAnchor`, `AddAnchoredText`),
  Anker-Ableitung zentral im Service (`polyPoints` + Projektion), Phase-1b-
  Text-Kaskade beim Löschen (Owned-Texte doomeder Objekte UND Connections),
  verankerte Texte nicht delta-verschiebbar, 3 neue Queries auf
  `DiagramQuery` (Defaults leer für Stubs): `findTextIdsOwnedBy`,
  `findConnectionNear`, `projectOntoConnection`. `ObjectTextChanged`-Fakt
  (previous + updated) + `TextChanged`-Undo-Op mit Merge und mechanischer
  Inversion (`publishTextState`).
- **Block C — `TextToolState`:** ToolType.TEXT + KEY_T; EINE Press-Regel
  mit Verzweigungen in der Aktion (Guard-Disjunktheit per Konstruktion —
  User-Forderung): Klick K/R → `EditTextOfObject`, Ctrl+Klick K/R →
  `CreateTextFor(owner)` (Label unterhalb, Default vom User bestätigt),
  Klick Text → `EditText`, Verbindungsnähe → bestehendes Label bearbeiten
  (0..1-Kardinalität) sonst `CreateConnectionText` mit projiziertem Anker,
  Leere → `CreateTextFor(null)`. Intents sind UI-Intents ohne Service-
  Konsument (dokumentiert). IdleToolState: movingIds ∪ Owned-Texte.
- **Bugfix nebenbei:** `moved()`/Resize trugen label/content nicht mit —
  Verschieben/Resizen hätte innere Texte still gelöscht (Kompat-Konstruktor-
  Falle). Jetzt Feld-Mitführung + Regressionstest.
- Tests: `TextModelTest` (14) + `TextToolTest` (8); **92/92 grün**.

**Warum so:**

- **Anker als nullable Feld statt Positions-Variante:** Die ursprüngliche
  Idee (`TextPosition` sealed: Absolute | Anchored, x/y on demand abgeleitet)
  scheiterte an `DiagramObject.x()/y()`: Ein verankertes Text-Record KANN
  x/y ohne Service-Zugriff nicht beantworten. Materialisierte x/y + Anker
  als Absicht hält das Interface intakt, Hit/Band/moved uniform — und x/y
  kann heute nicht stale werden, weil Waypoints gar nicht beweglich sind
  (W-Sichtbarkeit fehlt). Ehrlich als Phase dokumentiert: „Gleiten mit W"
  folgt mit dem Layer-Feature, dann als Teil desselben `ObjectsMoved`-
  Batch-Fakts (keine versteckte Kaskade — demo2-Lektion).
- **Löschen ohne Erzeugung:** Das Text-Werkzeug erzeugt NICHTS im Modell,
  bevor der Editor committet — die Intents tragen nur die Absicht. Cancel
  (ESC im Editor) kostet damit keinen Modelleingriff; die UI publiziert beim
  Commit die passenden Requests. Sauberer als demo2s Platzhalter-Text plus
  Nach-Räumen.
- **Intents ohne Konsumenten:** Bewusst gewagt, aber die Architektur sieht
  UI-Docking über Fakten/Intents vor; die Gesten-Semantik ist so OHNE UI
  per Event-Flow testbar (Goldene Regel 2) — sonst wäre das Werkzeug ein
  untestbarer No-Op bis zum UI-Meilenstein.

**Grenzen & Verbesserungskandidaten:**

- `SetAnchor(null)` hält die aktuelle Position (reines Undo-Inverse) — ein
  bewusster, dokumentierter Semantik-Unterschied zum Setzen eines Ankers.
- Test-Gestaltungslektion: Direkt publizierte Setup-Requests (AddElement)
  verschmelzen ohne Gesten-Grenze mit der Folgewirkung zu EINEM Undo-
  Eintrag — Fixtures brauchen explizite Boundaries (`MouseReleased`) vor
  Undo-Assertionen.
- Move-Snapping (auch Text-Mittelpunkt) hängt am bestehenden Snapping-Block;
  W-Gleiten und Serialisierung (Text/label/content ins DTO) sind offene,
  in AGENTS.md verortete Folgeblöcke.

## 28.10.2026 (3. Arbeitsblock) — Namenskonvention: Fakten im schlichten Präteritum

**Was:**

- `ObjectMovedApplied` → `ObjectMoved`, `ObjectResizedApplied` →
  `ObjectResized`, `ObjectsMovedApplied` → `ObjectsMoved` umbenannt
  (betroffen: DiagramEvent, DiagramService, UndoService, DiagramServiceTest;
  35 Referenzen, rein mechanisch, Verhalten unverändert).
- Namensregel in AGENTS.md Abschnitt 2 dokumentiert: Requests imperativ,
  Fakten schlichtes Präteritum, keine „Applied"-Suffixe; Add/Create-
  Verb-Familien bewusst verschieden (fertiges Record einfügen inkl.
  Restore-Pfad vs. neue Connection mit frischer ID erzeugen).

**Warum so:**

- User-Infragestellung zu Recht: „Applied" war seit Tag 1 inkonsequent
  (`ObjectAdded` hatte es nie) und redundant — die Zugehörigkeit zur
  DiagramEvent-Familie kodiert „vollzogen" bereits. CQRS-Konvention ist
  schlichtes Präteritum („OrderShipped", nicht „OrderShippedApplied").
- Umbenennen jetzt ist kostenlos (noch keine UI, das künftige JSON-Format
  hängt nicht an Klassennamen); nach dem UI-Bau würde es teuer.

**Grenzen:**

- Die DEVLOG-Chronik behält historisch bedingte alte Namen (Chronik wird
  nicht umgeschrieben).
- `mvn test`: 70/70 grün — reines Refactoring ohne Verhaltensänderung.

## 28.10.2026 (2. Arbeitsblock) — Gruppen-Move: Multi-Select komplett

**Was:**

- Pluraler Request `MoveObjects(ids, deltaX, deltaY)` + Batch-Fakt
  `ObjectsMovedApplied` (ein Eintrag je Objekt mit Vor-/Nach-Position).
- `MovingState`/`PendingMoveState` auf ID-Mengen umgestellt; die Geste
  spricht IMMER den pluralen Request (n=1 ist der Spezialfall) — der
  Service publiziert EINEN Batch-Fakt pro Drag-Segment.
- `IdleToolState`: Plain-Press auf bereits Selektiertes kollabiert die
  Auswahl nicht mehr — die Geste verschiebt die ganze Menge. Ctrl-Toggle-out
  zieht nur das Ziel selbst.
- `SelectionService` deduped identische Mengen (kein Fakten-Rauschen; der
  Plain-Press auf ein Gruppenmitglied ist auch akustisch kein Auswahlwechsel).
- `UndoService`: neuer `MovedObjects`-Op mit Mengen-Merge — aufeinander-
  folgende Batch-Moves derselben ID-Menge verschmelzen zu EINEM Undo-Punkt
  (je ID: Vor-Position aus dem ersten, Nach-Position aus dem letzten Segment).

**Warum so:**

- Immer plural: ein Gesten-Pfad statt zweien; der Batch-Fakt hält den
  Fakten-Strom flach (1 pro Segment statt n) und das Undo-Merge simpel.
- Der Kollaps-Schutz war der letzte Baustein der entschiedenen Semantik:
  Erst „Press selektiert", dann „Press auf Selektiertes erhält die Menge" —
  damit ist Drag-Interaktion mit Mehrfachauswahl vollständig.

**Grenzen & Verbesserungskandidaten:**

- Move-Snapping (spec.md) fehlt noch: Gruppen-Snap auf das primär gezogene
  Objekt, Gruppe folgt dem Delta (demo2-Semantik, in AGENTS.md vermerkt).
- Der singuläre `MoveObject`-Request hat keinen Produzenten mehr (die Geste
  spricht plural) — bleibt als Service-API für Tests/direkte Aufrufer.
- `mvn test`: 70/70 grün (64 + 6 `GroupMoveTest`: Gruppen-Move, Einzel-
  Kollaps, kein SelectionChanged bei Plain-Press auf Selektiertes,
  Toggle-out-Einzelzug, Ein-Undo-pro-Gruppengeste inkl. Redo,
  Unknown-ID-Ignore).

## 28.10.2026 (1. Arbeitsblock) — Gummiband-Auswahl (Ctrl+Leerraum-Drag)

**Was:**

- `DiagramQuery.findObjectsInBounds(minX, minY, maxX, maxY)` — neue Lese-Query
  (Bounding-Box-Schnitt, Waypoints ausgenommen, z-Ordnungs-determiniert);
  als Default-Methode im Interface, damit die sechs Test-Stubs stumm bleiben;
  der DiagramService überschreibt sie echt.
- Neue `PendingBoxSelectState`: Ctrl+Leerraum-Press → stumm bis zum Release
  (das sichtbare Rechteck ist reine UI-Sache — Commit-on-Release wie bei
  Resize/Move) → beim Loslassen Welt-Rechteck via Kamera berechnen,
  `findObjectsInBounds` fragen, EIN deklaratives `SetSelection` publizieren
  (das Band ersetzt die Auswahl, demo2-Semantik). Ctrl+Klick ohne Drag =
  No-Op; ESC = Abbruch ohne Event.
- `IdleToolState`: Ctrl-Zweig bei Leerraum-Press. Normales Leerraum-Drag
  bleibt Panning (bewusst so entschieden — demo2-Semantik).
- `DRAG_THRESHOLD_PIXELS` nach `BaseState` zentralisiert — eine Konstante
  statt drei Kopien (PendingMove, PendingCreateOrPan, BoxSelect).

**Warum so:**

- EIN Event statt Koordinaten-Strom (User-Frage aus der Planungs-Session,
  gemeinsam entschieden): Welche Objekte im Band liegen, ist eine
  Lese-Operation (Goldene Regel 1 — States lesen via Query); die fertige
  Menge ist die Absicht. `SetSelection` wurde dafür seit Tag 1 gebaut
  („Gummiband konvergiert im State, braucht nie neue Event-Typen").
- Default-Methode statt sechs Stub-Anpassungen: Interface-Wachstum ohne
  Bestands-Bruch; der echte Implementierer überschreibt explizit.

**Grenzen & Verbesserungskandidaten:**

- Additives Band (z. B. Shift+Ctrl+Drag) ist nicht umgesetzt — das Band
  ersetzt immer (demo2-Semantik); bei Bedarf später als kleine Erweiterung.
- Waypoints bleiben band-seitig unsichtbar bis zur Layer-Sichtbarkeit
  (konsistent mit `findTopmostAt`).
- Das sichtbare Rechteck selbst kommt mit dem UI-Meilenstein (die UI ist der
  Ursprung der Maus-Events und kann lokal zeichnen; alternativ Preview-Events
  — Entscheidung dort, wie bei ConnectingState notiert).
- `mvn test`: 64/64 grün (56 + 8 `BoxSelectionTest`: Band-Treffer in
  z-Ordnung, Leere-Band-Reset, Ctrl-Klick-No-Op, ESC-Abbruch,
  Pan-Regression, Kamera-Welt-Rechnung, Band+DEL-Sammellöschen,
  Waypoint-Ausschluss).

## 27.10.2026 (6. Arbeitsblock) — z-Ordnung als explizite Struktur (User-Idee)

**Was:**

- Objekt-Store umgebaut von LinkedHashMap (Einfüge-Reihenfolge = z-Ordnung)
  zur Zwei-Struktur-Kombination (User-Vorschlag): `HashMap` für O(1)-Lookup
  plus explizite `zOrder`-Liste (UUIDs, Index 0 = unten). Neue Objekte
  landen oben; Move/Resize berühren allein die Map — nur Hinzufügen/
  Entfernen synchronisieren beide Strukturen.
- `ObjectsRemoved` trägt jetzt die z-Indizes zum Lösch-Zeitpunkt; neuer
  Request `RestoreObjects` (Inverse des Löschens, fügt aufsteigend nach
  Original-Index wieder ein). `DiagramService.zOrderIds()` exponiert die
  Ordnung read-only (UI/Tests).
- `onDeleteObjects` in Phasen zerlegt: Sammeln (ohne Mutation) → z-Index-
  Snapshot → Entfernen → Waypoint-Listen-Strich → Fakten. Der Snapshot
  MUSS vor der ersten Entfernung liegen, sonst verschieben frühere
  Entfernungen der Geste die Indizes späterer Objekte.

**Warum so:**

- z-Ordnung ist Modellzustand: Ohne sie verletzte der Restore unsere eigene
  Undo-Regel („Fakt trägt den vollständigen Vor-Zustand") — keine Kosmetik,
  sondern eine Lücke.
- Die Liste trägt UUIDs statt Records: Move/Resize (die häufigsten
  Operationen) brauchen keine Synchronisation; die Sync-Bürde bleibt auf
  zwei Stellen begrenzt.
- Die aufgezeichneten Indizes sind unter Undo selbstheilend: LIFO poppt
  jede spätere z-ändernde Geste zurück, bevor der Restore läuft — die
  Indizes vom Lösch-Zeitpunkt sind dann wieder gültig.
- Aufsteigendes Wiedereinfügen ist beweisbar exakt: kleinere Indizes sind
  schon wieder besetzt, wenn die späteren folgen.

**Grenzen & Verbesserungskandidaten:**

- `zOrder.contains/indexOf` ist O(n) — bei Editor-Größenordnungen
  (hunderte Objekte) irrelevant; eine Position-Map wäre vorsorgliches
  Optimieren (bewusst NICHT eingebaut).
- Explizite z-Manipulation („nach vorne/hinten") ist durch die Struktur
  jetzt möglich, aber noch nicht als Request ausgeformt — kein Spec-Bedarf.
- `mvn test`: 56/56 grün (53 + 3 `ZOrderRestoreTest`: Mittel-Position-
  Restore inkl. Hit-Test-Beweis, Kaskaden-Restore mit Waypoint,
  Multi-Delete mit Index-Snapshot).

## 27.10.2026 (5. Arbeitsblock) — Undo/Redo: Fakten-Inversion statt Command-Klassen

**Was:**

- „Meditations-Design" (mit dem User erarbeitete Session) umgesetzt: Undo ist
  nur ein weiterer Schreiber über den Bus — CTRL+Z löst Requests aus, der
  Schreibpfad bleibt einzeln.
- Fakten mit Vor-Zustand angereichert: `ObjectMovedApplied(id, oldX, oldY,
  newX, newY)`, `ObjectResizedApplied(previous, resized)`,
  `ConnectionsUpdated(previous, updated)` (index-parallel).
- Neue Requests: `MoveObjectTo` (absolut — präzises Inverse ohne Float-Drift,
  denn a + (b-a) == b ist in IEEE754 nicht garantiert), `AddConnection`
  (Restore mit Original-ID), `RemoveConnections` (ohne Kaskade — präzises
  Inverse), `RequestUndo`/`RequestRedo` (UI übersetzt CTRL+Z/SHIFT+CTRL+Z
  am Systemrand — semantisch wie Toolbar → SelectTool; funktioniert in
  jedem Tool-Zustand ohne Regel-Duplizierung).
- Neuer `UndoService` (Sitzungszustand analog SelectionService): leitet aus
  Fakten mechanisch inverse Requests ab; Ops pro Gesten-Eintrag;
  Move-Segmente desselben Objekts verschmelzen (erster Drag bleibt vom
  Übergang konsumiert — bekannt); Gesten-Grenzen sind MousePressed/Released/
  KeyPressed, MouseDragged bewusst NICHT (sonst zerfiele jede Move-Geste in
  N Undo-Punkte); Replay-Guard verhindert Selbst-Erfassung; neue Aktion
  leert den Redo-Stack. Undo = inverse Requests rückwärts, Redo = schlichtes
  Replay der Original-Requests (Beantwortung der Meditations-Frage: JA, ein
  normales `AddElement` genügt — kein „recreate" nötig).
- Kaskaden-Undo gratis: Der Lösch-Eintrag besteht nur aus den Batch-Fakten,
  das Undo-„Command" ist stumm. Genau dort starb demo2 (Undo verlor
  mitgelöschte Texte) — hier strukturell ausgeschlossen.

**Warum so:**

- Fakten-Inversion statt Command-Klassen: Das Kaskade-Wissen liegt allein
  im DiagramService (einziger Schreibpfad); der UndoService besitzt kein
  Modellwissen und kann gegen ihn nie driften.
- Enrichment statt Altbestand: Vor-Zustand IN den Fakt legen (Undo-Regel als
  Typ-Invariante) statt Vor-Zustand in Commands zu spiegeln.

**Grenzen & Verbesserungskandidaten:**

- z-Order beim Restore: Wiedereingefügte Objekte landen oben in der
  Einfüge-Reihenfolge (= z-Ordnung) — User hat eine Verbesserungsidee,
  nächste Session besprechen.
- Synthetische Abläufe ohne Eingabe-Grenzen verschmelzen zu einem
  Undo-Punkt (Tests brauchen `closeGesture()`-Hilfe) — in echten Abläufen
  liegen immer Eingaben zwischen Gesten.
- `mvn test`: 53/53 grün (45 + 8 `UndoRedoTest`: Erstellen-Zyklus,
  Move-Merging, Zwei-Gesten-Trennung, Kaskaden-Restore mit Original-IDs,
  VerbindungsWith- Waypoint-Undo, Resize-Undo, Redo-Invalidierung,
  Empty-History-No-Op).

## 27.10.2026 (4. Arbeitsblock) — Selektion bei Press, DEL-Löschen & Waypoint-ID-Migration

**Was:**

- Semantik vorher doppelt dokumentiert: doc/spec.md (Produktsicht: Klick-/
  Ctrl+Klick-Regeln, DEL) und AGENTS.md (Umsetzungssicht: Press-Zeitpunkt,
  `SetSelection`, Kaskade).
- **Waypoint-ID-Migration:** `Connection` referenziert `waypointIds`; der
  DiagramService speichert Waypoints beim `CreateConnection` als echte
  Objekte (je ein `ObjectAdded`-Fakt) — keine zweite Kopie mehr.
- **Selektion bei Maus-Druck** (IdleToolState): einfach = exklusiv, Ctrl+Klick
  = Toggle über das neue `controlDown`-Flag am `InputEvent.MousePressed`
  (public Kompatibilitäts-Konstruktor hält Bestands-Call-Sites grün; Flag
  dient später auch dem Gummiband), Leere-Klick und ESC = Auswahl leeren.
- **DEL-Löschen:** neuer `CustomKeyCode.DELETE` (bewusst ohne Backspace),
  `DeleteObjects`-Request, uniforme Kaskadenregel im DiagramService — jedes
  entfernte Objekt wird aus ALLEN Waypoint-Listen gestrichen, K/R-Delete
  entfernt ihre Connections samt Waypoints. Batch-Fakten `ObjectsRemoved` /
  `ConnectionsRemoved` / `ConnectionsUpdated` mit vollständigem Zustand.
  `SelectionService` staubt tote IDs automatisch ab (Invariante: Auswahl
  enthält nie Tote).

**Warum so:**

- Selektion bei Press statt beim Loslassen: keine Click-vs-Drag-Sonderfälle,
  demo2-erprobt; der spätere Gruppen-Move bekommt die Auswahl gratis
  (Guard-Erweiterung: Press auf bereits Selektiertes kollabiert die Menge
  nicht).
- EINE uniforme Kaskadenregel statt demo2s zweigeteilter Logik: Geteilte
  Waypoints räumen sich in fremden Connections automatisch mit ab — genau
  die Ecke, in der demo2 kippte (Undo verlor dort mitgelöschte Texte).
- Batch-Fakten tragen den vollständigen entfernten Zustand: Das künftige
  Undo-Command baut darauf auf und braucht kein eigenes Kaskade-Wissen.

**Grenzen & Verbesserungskandidaten:**

- Waypoints sind bis zur Layer-Sichtbarkeit nicht klick-selektierbar
  (`findTopmostAt` spart sie aus) — Ctrl+Klick auf Waypoints greift daher
  noch nicht.
- Löschen ist noch nicht undo-fähig; `ConnectionsUpdated` trägt den
  Nach-Zustand, Undo braucht zusätzlich den Vor-Zustand (Design in Block 3).
- `mvn test`: 45/45 grün (33 + 8 `ClickSelectionTest` + 4 `DeleteObjectsTest`;
  `DiagramServiceTest` auf `waypointIds` + Objekt-Store umgestellt).

## 27.10.2026 (3. Arbeitsblock) — Altvorlagen-Analyse demo2: Fragen entschieden

**Was:**

- Die ältere, weiterentwickelte Projektversion `~/dev/git/demo2` (72 Java-
  Dateien: UI, CommandHistory, Text, Layer, Persistenz, Routing) systematisch
  gesichtet und gegen demo3 abgeglichen.
- Alle fünf offenen Fragen als entschieden in die AGENTS.md eingearbeitet
  („✅ Entschiedene Fragen"): Lösch-Kaskade, Undo/Redo ja,
  Waypoints-bleiben-stehen/Texte-folgen, Inline-Text-UX (ENTER/ESC),
  Ctrl-Mechanik für Mehrfachauswahl.
- Weitere Geplant-Punkte mit demo2-Wissen angereichert: Waypoint-
  Nachbearbeitung (Doppelklick auf Verbindung, Einfüge-Index per Geometrie),
  Routing als View-Strategy (1:1 portierbar), Text als DiagramObject mit
  EINER Auswahl-Menge, JSON-Format = demo2s Gson-Export inkl. `texts`,
  Move-Snapping-Semantik (primäres Objekt snappen, Gruppe folgt Delta).
- Zwei Neuentwicklungen identifiziert (auch ggü. demo2 neu): innerer
  Buchstabe im K, Modell-Entscheidung typisierte Records behalten + DTO
  konvertiert `Circle.radius` ↔ JSON-Bounding-Box.

**Warum so:**

- demo2 beantwortet die Fragen praktisch — zeigt aber zugleich exakt die
  Verkrautung, deretwegen demo3 neu startet: `CoreRegistry` als Gott-Objekt
  mit öffentlichen Mutatoren (zwei Schreibpfade), versteckte Kaskaden
  (`moveObject` verschiebt still assoziierte Texte mit), Dual-Mode-States
  (`eventBus != null ? publish : direkt` — halbfertige Migration), JavaFX
  im State-Layer, zwei parallele Selection-Sets, inline duplizierte
  Snap-Logik, Debug-Debris in der CommandHistory.
- Konkreter Bug als Warnung dokumentiert: Undo eines Objekt-Löschens verlor
  in demo2 die mitgelöschten Texte dauerhaft — Kaskade (Registry) und
  Undo-Wissen (Command) waren auseinandergedriftet. Konsequenz für demo3:
  Kaskaden als sichtbare Fakten im DiagramService; Commands bauen auf den
  Fakten auf und bilden Kaskade-Wissen nie doppelt ab.

**Grenzen & Verbesserungskandidaten:**

- Die demo2-Sichtung war fokussiert (Registry, Commands, States, Modell,
  Persistenz, Selection); ViewMapper/Controller-Details (507 Zeilen
  CanvasController) nur am Rande — bei UI-Beginn ggf. vertiefen.
- Kein Code geändert in diesem Block — die Entscheidungen wirken erst in
  den nächsten Implementationsblöcken (Reihenfolge vorgeschlagen:
  Löschen → Undo/Redo-Fundament → Waypoint-ID-Migration → Multi-Select →
  Text → Persistenz).

## 27.10.2026 (2. Arbeitsblock) — doc/-Umzug, Kamera-Konsistenz & Guard-Fixes

**Was:**

- `doc/` angelegt; KI-Analysen (`chatgpt.md`, `gemini.md`, `deepseek.md`) und
  `spec.md` dorthin verschoben — Root enthält nur noch AGENTS.md/DEVLOG.md;
  AGENTS.md-Referenz auf `doc/spec.md` angepasst.
- **Kamera-Konsistenz** (wichtigster Fund aus dem Faktencheck, ChatGPT): Der
  Bus hielt `CameraInfo` als finalen Start-Snapshot. `publish()` konsumiert
  jetzt selbst `PanView`/`ZoomView` und ersetzt den Snapshot via neuer
  `CameraInfo.panBy(...)`/`zoomAt(factor, pivotX, pivotY)`-Methoden;
  `getCameraInfo()` exponiert den Snapshot read-only (Tests, spätere UI).
- **Guard-Fix `IdleToolState`** (DeepSeek #2): Shortcut-Regeln jetzt
  guard-disjunkt pro Key statt `default`-Switch, der auf jedes `KeyPressed`
  matchte.
- **Button-Guard `CameraPanningState`** (DeepSeek #7): `RequestCameraPan`
  trägt nun die auslösende Taste; Panning endet nur beim Loslassen genau
  dieser Taste.
- **Totes `ActionEvent.AddWaypoint` entfernt** (ChatGPT: „Events müssen einen
  klaren Zweck haben"); Preview-Events für ConnectingState kommen mit dem
  UI-Meilenstein zurück.

**Warum so:**

- Ohne Snapshot-Update hätte nach dem ersten Pan/Zoom jede
  screenToWorld-Rechnung (Hit-Tests, Erstellen, Verschieben) mit den
  Start-Offsets gerechnet. Die Mathematik liegt laut Goldener Regel 3 in
  `CameraInfo` (reine Funktionen), der Bus ist Besitzer des Snapshots.
- Der default-Switch im `IdleToolState` hätte beim ersten Ergänzen einer
  ESC-Regel die Guard-Disjunktions-Invariante zur Laufzeit gesprengt — jetzt
  gilt die Konvention überall einheitlich.
- `AddWaypoint` hatte weder Publisher noch Konsumenten; die Absicht
  (Waypoint-Preview) lebt als DEVLOG-Notiz weiter statt als toter Code.

**Grenzen & Verbesserungskandidaten:**

- `zoomAt` weist newZoom <= 0 ab (Kamera unverändert); sinnvolle Zoom-Limits
  (min/max) sind noch offen.
- Bekannter Jump beim tool-initiierten Panning: der erste Drag-Event wird vom
  Zustandsübergang konsumiert (analog Move), Pan-Deltas laufen ab der
  Start-Position — kosmetisch, erst mit UI sichtbar.
- `mvn test`: 33/33 grün (28 bestehende + 5 neue `CameraConsistencyTest`:
  Pan-Snapshot, Zoom-am-Pivot, Hit-Test nach Pan, Button-Guard,
  tool-initiiertes Panning).

## 27.10.2026 — KI-Analysen-Faktencheck & spec.md-Integration in AGENTS.md

**Was:**

- Drei externe KI-Projektanalysen (`chatgpt.md`, `gemini.md`, `deepseek.md`)
  vor Übernahme Behauptung für Behauptung gegen den echten Code verifiziert.
  Bestätigt u. a.: finaler `CameraInfo`-Snapshot im Bus (nie aktualisiert nach
  Pan/Zoom), guard-lose `KeyPressed`-Regel im `IdleToolState`, totes
  `AddWaypoint`-Event, fehlender Button-Guard in `CameraPanningState`.
  Widerlegt: DeepSeeks „keine Tests" (8 Testklassen vorhanden — alle drei
  Analysen basierten offenbar auf einem gepackten Snapshot ohne Test-Sources).
- Findings triagiert: sofort sinnvoll (Kamera-Aktualisierung, Guard-Fix,
  AddWaypoint, Button-Guard) / Design-Entscheidung (Klick-Semantik,
  Tool-State-Zentralisierung) / bewusst NICHT machen (Geminis vorsorgliche
  Abstraktionen — widerspricht Goldener Regel „keine Generalisierung auf
  Vorrat").
- `spec.md` als verbindliche Produktvision in die AGENTS.md eingearbeitet:
  neue Sektion „Produktvision", Geplant-Einträge um spec-Bestätigungen ergänzt
  (Layer-System, Mehrfachauswahl), neue Punkte (Move-Snapping, Text-Feature,
  Persistenz, Löschen) und ein Offene-Fragen-Block.
- Zwei aus der Spec abgeleitete Modell-Entscheidungen dokumentiert:
  Waypoint-Referenzierung per ID (Ist: `Connection` bettet noch
  `List<Waypoint>`-Instanzen ein) und Circle `radius` vs. JSON-Bounding-Box.

**Warum so:**

- KI-Reviews liefern Hypothesen, keine Fakten: erst Code-Verifikation, dann
  Integration — sonst landen Phantom-Befunde („keine Tests") in Entscheidungen.
  Dass zwei Modelle unabhängig dieselbe falsche Aussage trafen, zeigt: Das
  Problem war ihr unvollständiger Input, nicht der Code.
- Die Vision steht jetzt in der AGENTS.md statt nur als spec.md-Verweis:
  Jede Session sieht Zielrichtung und offene Entscheidungen sofort; die
  spec.md bleibt unangetastetes Beispieldokument (inkl. JSON-Format).
- Spec-Inhalte wurden mit Ist-Zustand verschränkt („implementiert" /
  „noch unmodelliert" / „Ist-Zustand weicht ab") statt wörtlich kopiert —
  die AGENTS.md bleibt Doku des Ist-Zustands mit klar markierter Zukunft.

**Grenzen & Verbesserungskandidaten:**

- Die fünf offenen Fragen (Löschen, Undo/Redo, Move-Semantik bei Connections,
  Text-UX, Multi-Select-Mechanik) sind dokumentiert, aber ungelöst — Klärung
  über die ältere, weiterentwickelte Projektversion geplant.
- Keine Code-Änderungen in dieser Session — die vier „sofort sinnvoll"-Fixes
  aus dem Faktencheck stehen noch aus (gute Kandidaten für den nächsten Block).
- `chatgpt.md`/`gemini.md`/`deepseek.md` liegen als Artefakte im Repo;
  über Aufnahme ins Repo oder .gitIgnore sollte der User entscheiden.

## 26.10.2026 (3. Arbeitsblock) — Guard-Disjunktions-Invariante

**Was:**

- `BaseState.handleInput` erzwingt jetzt die Guard-Disjunktions-Invariante:
  Greifen für ein (Event, Kontext)-Paar mehrere Guards, scheitert der
  Dispatcher sofort mit `IllegalStateException` (State, Event, Trefferzahl).
- Beide bestehenden Überlappungen beseitigt: `IdleToolState` (Doppelklick
  `clickCount == 2` vs. einfacher Klick `clickCount == 1`; Dreifach-Klick
  trifft bewusst keine Regel) und die drei Tool-States (Shortcut-Regeln jetzt
  eine Guard-disjunkte Regel pro Key statt `default`-Switch, das ESC
  überlappte).
- Neuer `BaseStateTest` beweist die Invariante direkt: ambige Regeln werfen,
  disjunkte dispatchen eindeutig, kein Treffer lässt den Zustand einfach.

**Warum so:**

- Ohne den Check könnte kein Test eine Überlappung sehen — der First-Match-
  Dispatcher ließe die zweite Regel stillschweigend nie laufen. Mit dem Check
  wird jeder bestehende und künftige Interaktions-Test gratis zum
  Überlappungs-Detektor, und die Registrierungsreihenfolge trägt nie wieder
  Verhalten. States bleiben deklarative Übergangstabellen, keine Programme
  mit versteckter Priorität.
- Konsequenz (in AGENTS.md dokumentiert): Guards müssen seiteneffektfrei
  sein, da der Dispatcher jetzt alle auswertet.

**Grenzen & Verbesserungskandidaten:**

- Der Check ist ereignisbasiert: schlafende Überlappungen fallen erst auf,
  wenn ein Test das auslösende Szenario tatsächlich durchfährt. Die Abdeckung
  folgt der Konvention „ein Event-Test pro Feature" — bewusst gewählter
  Pragmatismus statt Property-Based-Testing.
- `mvn test`: 28/28 grün (alle bestehenden Interaktions-Tests bestätigten,
  dass die Guard-Verschärfungen kein Verhalten geändert haben).

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
