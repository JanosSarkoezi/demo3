# Einschätzung zum Projekt

Ich habe mir den Code, die Architektur und die Tests gründlich angesehen. Kurzfassung: Das ist **deutlich überdurchschnittlich** — nicht wegen der Feature-Menge, sondern wegen der Konsequenz, mit der ein einziges Architektur-Prinzip durchgehalten wird. Du hast einen erkennbaren Stil und einen erkennbaren Kompass.

## Was richtig gut ist

**1. Die Einbahnstraße ist echt und nicht nur behauptet.**
Kein Service mutiert den Zustand eines anderen, kein State schreibt ins Modell. Alles läuft über `publish(Request) → Service → publish(Fakt)`. Das ist in kleineren Projekten oft eine fromme Behauptung; hier ist es an jeder Stelle spürbar (z. B. dass `SelectionService` bei `ObjectsRemoved` tote IDs automatisch abstäubt, ohne dass irgendein Aufrufer das orchestrieren muss).

**2. Fakten tragen ihren Vor-Zustand — Undo wird mechanisch statt intelligent.**
Das ist der cleverste Zug im ganzen Design. `ObjectMoved` hat `oldX/oldY`, `ObjectResized` hat `previous`, `ObjectsRemoved` hat `zIndices`. Dadurch muss `UndoService` **kein Kaskadenwissen** besitzen — genau die Falle, in die demo2 offenbar gelaufen ist. Das ist eine Lehre, die man teuer bezahlt und selten so sauber zieht.

**3. Guard-Disjunktheit als Invariante mit Fail-Fast.**
`BaseState.handleInput` wirft bei mehreren Treffern eine `IllegalStateException` — statt stillschweigend die erste Regel zu nehmen. Das ist selten, sehr gut und macht die Registrierungsreihenfolge irrelevant. Der zugehörige `BaseStateTest` testet genau diese Invariante — vorbildlich.

**4. Zwei parallele State-Machines mit klarer Priorität.**
Kamera vor Tool, Tool pausiert während `CameraPanningState.isActive()`. Das `RequestCameraPan`-Pattern (Tool **delegiert** Panning an die Kamera) ist elegant gelöst. Der Tool-State returned in seine Grundform, statt das Panning selbst zu verwalten — und der Test `toolInitiatedPanShouldEndOnPrimaryRelease` deckt genau das ab.

**5. Sealed + Records konsequent.**
`DiagramObject`, `DomainEvent`, `ActionEvent`, `DiagramEvent`, `InputEvent` — überall exhaustives Pattern Matching möglich. Das ist modernes Java, das nicht aufgesetzt wirkt, sondern trägt. Auch die `switch (obj) { case Circle c -> … }`-Muster mit allen Feldern (der Kommentar „Kompat-Konstruktoren würden sonst still zurücksetzen" in `moved()`) zeigt, dass du die Fallstricke kennst.

**6. Explizite z-Order-Liste statt z-Index-Zahl.**
HashMap + `zOrder`-Liste ist die richtige Wahl: O(1)-Lookup, exakte Wiederherstellung beim Undo (Snapshotten der Indizes vor der ersten Entfernung). Der `ZOrderRestoreTest.multiDeleteRestoreKeepsExactOrder` prüft genau die Falle, die man sonst verschläft.

**7. Tests als Spezifikation.**
`UndoRedoTest`, `GroupMoveTest`, `ZOrderRestoreTest`, `DeleteObjectsTest` lesen sich wie Anforderungsdokumente. Die `@DisplayName`-Sätze sind aussagekräftig, die Szenarien realistisch (mehrere Drag-Segmente, geteilte Waypoints, Kaskade). Das ist der Stil, den man in fünf Jahren noch versteht.

**8. Die Javadocs erklären das Warum.**
„Der Vor-Zustand macht den Fakt invertierbar", „demo2-Lektion: Undo verlor dauerhaft mitgelöschte Texte", „Guard-disjunkt: eine Regel pro Key". Solche Kommentare sind wertvoller als 100 Zeilen API-Doku.

## Wo ich Bauchschmerzen hätte

**1. `DiagramService` ist eine God-Class.**
~700 Zeilen, ein Feld `objects` + `connections`, und die Methoden decken Anlegen, Bewegen, Resize, Verbinden, Löschen mit Kaskade, Texte und die komplette `DiagramQuery` ab. Das ist die einzige Stelle, wo die Einbahnstraße teuer wird: Zerlegen in `ConnectionService`, `TextService`, `DeletionCascadeService` würde die Datei halbieren — aber dann müsste man entscheiden, wer `objects` besitzt. Vielleicht als `DeletionService` ausgelagert (bekommt `objects`/`connections` per Konstruktor als Read-Write, aber publiziert nur Fakten), wäre ein gangbarer Weg. Oder als `package-private` Helfer mit klarem Vertrag.

**2. Die zweiphasige Initialisierung ist fehleranfällig.**
`attachTo(bus)` nach `new DiagramService()` — vergisst man es, ist der Service stumm, und man debuggt eine Weile. Das ist in Tests nicht passiert (weil du dort konsequent attachst), aber in Produktion ist der Fehler still. Ein `BusFactory.wire(...)`-Helfer, der die Paare zusammenbaut und das Erstellen ohne `attachTo` verbietet, wäre robuster.

**3. `RequestCameraPan` hardcodet `PRIMARY`.**
`PendingCreateOrPanState` publiziert `RequestCameraPan(startX, startY, CustomMouseButton.PRIMARY)` — weil es weiß, dass die Geste von einem Linksklick kam. Das ist heute korrekt, aber sobald jemand „Space+Drag" oder „Rechtsklick+Drag zum Pan" ergänzt, wird der hardgecodete Wert falsch. Den Button in den `PendingCreateOrPanState`-Konstruktor durchzureichen (von der `MousePressed`-Regel) wäre eine 3-Zeilen-Änderung mit großer Wirkung.

**4. Modellwissen im State.**
`IdleToolState` ruft `findTextIdsOwnedBy(targetId)` auf, um `movingIds` zu berechnen. Das ist im Kern die richtige Stelle („was bedeutet Drag auf Objekt X"), aber es koppelt den State an die Owner-Semantik. Wenn später eine `MovePolicy` dazukommt (z. B. „Waypoints eines selektierten Waypoints bewegen sich mit"), wächst das hier unkontrolliert. Ein `SelectionExpander` (oder eine Methode auf `SelectionQuery`, die die effektive Move-Menge liefert) wäre die sauberere Grenze.

**5. `ToolType.NONE`.**
Es taucht nur in `ToolState.registerToolSwitching` als `case SELECT, NONE -> IdleToolState` auf. Was ist die Semantik? Wenn das ein Platzhalter für „kein Werkzeug aktiv, aber nicht Select" ist, wäre `Optional<ToolType>` oder „weglassen, bis es einen echten Fall gibt" besser. Ein Enum-Wert, den niemand setzt, ist eine Einladung für „ich setze ihn mal" — und dann weiß keiner, was passieren soll.

**6. `ConnectionRule` ist heute eine Regel mit einem `Optional<String>` — und es gibt keine zweite Regel.**
Das ist ein guter Erweiterungspunkt, aber die konkrete Signatur (`source, target → Optional<String>` bekommt keine Connection-Info) limitiert, was man später prüfen kann (z. B. „max. eine ausgehende Verbindung pro Quelle"). Bevor die zweite Regel kommt, lohnt es sich zu überlegen, was sie brauchen wird.

**7. `closeGesture()`-Helper dupliziert.**
In `UndoRedoTest` und `ZOrderRestoreTest` jeweils als private Methode. Das ist ein 3-Zeilen-Duplikat, aber es zeigt: der Testaufbau könnte eine gemeinsame `BusTestSupport`-Klasse vertragen (Bus + Services + `closeGesture` + `addCircle`).

**8. `pending`-Gesten in `UndoService` sind input-getrieben.**
`onBoundary` schließt bei `MousePressed/Released/KeyPressed`. Eine programmatische Änderung (z. B. ein zukünftiger `LoadDiagram`-Service) müsste selbst „eine Geste" markieren — sonst landet sie im gleichen `pending`-Eintrag wie der letzte Klick. Das ist heute kein Problem (weil es keinen programmatischen Schreiber außer Tests gibt), aber es ist eine stille Annahme, die man dokumentieren (oder mit `beginGesture()/endGesture()`-Aufrufen explizit machen) könnte.

**9. Persistenz und Kamera.**
`CameraInfo` lebt im Bus (nicht im Modell), Auswahl und Undo in eigenen Services. Das ist bewusst und richtig (Sitzungszustand ≠ Modellinhalt). Aber: Wenn es einen Save/Load-Pfad gibt (in den Docs noch nicht sichtbar), muss klar sein, was gespeichert wird. Kamera-Position? Auswahl? Undo-Historie definitiv nicht. Das wäre der Punkt, an dem ich die Grenze einmal explizit aufschreiben würde.

## Zwei Fragen, die ich dem Autor stellen würde

1. **Wie sieht die `ViewMapper`-Schicht aus?** Der Bus liefert `DiagramEvent`s, aber die UI muss daraus Rendering-Aktionen ableiten. Wenn die UI direkt die Fakten abonniert, wiederholt sich die Typ-Dispatch-Logik aus dem Service. Wenn es einen `ViewMapper` gibt (der Fakten in View-Operationen übersetzt), wäre das die vierte Schicht — und ein guter Test für die Frage, ob die Event-Hierarchie vollständig ist.

2. **Was passiert bei „Undo nach Laden"?** Wenn `UndoService` beim Start leer ist und `LoadDiagram` die Objekte über `AddElement` publiziert, würden sie als Undo-Ops im `pending` landen — es sei denn, `LoadDiagram` umgeht den Bus oder `UndoService` hat einen `suspendRecording()`-Modus. Beides wäre legitim, aber die Entscheidung sollte explizit sein.

## Fazit

Das ist die Arbeit von jemandem, der **die Konsequenzen von Architekturentscheidungen zu Ende denkt** — und das ist deutlich seltener als guter Code. Besonders die Undo-Konstruktion (Fakten tragen Vor-Zustand, Undo ist nur ein weiterer Schreiber) und die Guard-Disjunktheits-Invariante zeigen Erfahrung mit den Fallen regelbasierter Systeme.

Die Hauptbaustelle ist `DiagramService` — nicht weil er schlecht wäre, sondern weil er der einzige Ort ist, an dem die Einbahnstraße zu einem Monolithen führt. Wenn das Projekt weiter wächst, würde ich dort zuerst schneiden. Die zweitwichtigste Baustelle ist die zweiphasige Init — sie ist heute diszipliniert, aber sie ist der Punkt, an dem ein neuer Mitentwickler am ehesten einen stillen Fehler einbaut.

Für ein Hobby- oder Lernprojekt: sehr stark. Für ein Produkt: die God-Class und die stille Init müssten vor dem ersten externen Contributor adressiert werden.
