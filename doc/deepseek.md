# Review: FMC-Editor

Kurz vorweg: Für ein Projekt in der Anfangsphase ist das **architektonisch überdurchschnittlich sauber**. Man sieht an vielen Stellen bewusste Entscheidungen (Guard-Disjunktions-Invariante, CQRS-artige Trennung, deferred commits, sealed interfaces + Pattern Matching). Das Fundament trägt. Ich fokussiere im Folgenden auf das, was *jetzt* günstig zu korrigieren ist, bevor es sich festfrisst – also genau der „Verkrautung"-Prävention.

## Was richtig stark ist

- **Zwei parallele State-Machines** (Tool vs. Camera) mit `isActive()` als Prioritätssignal – elegant gelöst. Die Kamera „pausiert" das Tool, ohne dass das Tool davon wissen muss.
- **`BaseState` mit Ambiguity-Check**: Regeln, die gleichzeitig greifen, knallen mit `IllegalStateException`. Das ist der beste Anti-Verkrautung-Mechanismus im ganzen Code – er zwingt dazu, Guards disjunkt zu halten. Sehr gut.
- **`Pending*State`-Muster** (Click-vs-Drag-Threshold). Verhindert, dass Klick- und Drag-Semantik sich gegenseitig ins Gehege kommen.
- **`HandleDragState` mit deferred commit**: Live-Vorschau ist UI-Sache, Backend bekommt genau ein Resize-Event beim Release. Verhindert Event-Spam und Modell-Churn.
- **Sealed-Hierarchien + exhaustives Switch**: `moved()` und `hit()` in `DiagramService` sind Musterbeispiele – der Compiler warnt, wenn eine neue Objektart dazukommt.

## Die echten Verkrautungskandidaten

### 1. Tool-Wechsel + Shortcuts sind in jedem ToolState dupliziert 🔴

Das ist die größte Baustelle. `CircleToolState`, `RectangleToolState`, `ConnectToolState` und `IdleToolState` enthalten jeweils denselben `ActionEvent.SelectTool`-Switch **und** dieselben Shortcut-Regeln (`KEY_K`, `KEY_R`, `KEY_C`), nur mit anderem Ziel. Wenn du jetzt ein `LineToolState` oder `TextToolState` ergänzt, musst du in **jedem existierenden State** die Switch-Zweige und Shortcut-Regeln nachziehen. Vergisst du einen, wechselt das Tool dort nicht mehr. Genau so entsteht Verkrautung.

**Vorschlag:** `abstract class ToolState extends BaseState` mit einer `registerCommonRules(ToolType self)`-Methode, die den `SelectTool`-Switch und die globalen Shortcuts einmalig registriert. Konkrete States erben und ergänzen nur ihr Spezifika:

```java
protected void registerToolSwitching(ToolType self) {
    addRule(ActionEvent.SelectTool.class, (e, c) -> switch (e.tool()) {
        case CIRCLE -> self == ToolType.CIRCLE ? this : new CircleToolState();
        case RECTANGLE -> self == ToolType.RECTANGLE ? this : new RectangleToolState();
        case CONNECT -> self == ToolType.CONNECT ? this : new ConnectToolState();
        case SELECT, NONE -> self == ToolType.SELECT ? this : new IdleToolState();
    });
    addRule(InputEvent.KeyPressed.class, e -> e.code() == KEY_K, (e, c) -> new CircleToolState());
    // ...
}
```

Dann bleibt pro State nur noch „Was passiert bei meinem Linksklick?" und „Was bedeutet ESC *für mich*?" übrig.

### 2. `IdleToolState` ist inkonsistent bei den Shortcuts 🟠

In `IdleToolState` steht:

```java
addRule(InputEvent.KeyPressed.class, (e, c) -> switch (e.code()) {
    case KEY_K -> ...
    case KEY_R -> ...
    case KEY_C -> ...
    default -> this;
});
```

Diese Regel hat **keinen expliziten Guard** (also `(e, c) -> true`), greift damit auf *jedes* `KeyPressed`-Event. In den anderen ToolStates hast du genau das bewusst vermieden (siehe Kommentar im `ConnectToolState`: *„kein default-Switch, der ESC/Vorhandenes überlappen könnte"*).

Sobald du in `IdleToolState` eine ESC-Regel ergänzt (z. B. „Auswahl aufheben"), hast du **zwei** Regeln, die auf `KeyPressed(ESCAPE)` matchen – und `BaseState` wirft `IllegalStateException`. Das ist zwar besser als stilles Fehlverhalten, aber der Crash kommt zur Laufzeit und ist ärgerlich.

**Vorschlag:** Entweder den Switch in diskrete, guard-getrennte Regeln auflösen (wie in den anderen States), oder – besser – in `ToolState` zentralisieren (siehe #1). Damit verschwindet auch dieser Footgun.

### 3. `CameraInfo.defaultLayerId` gehört nicht zur Kamera 🟡

Ein Layer hat nichts mit Zoom/Offset zu tun. Aktuell müssen alle States, die eine Layer-ID brauchen (`PendingCreateOrPanState`, `ConnectingState`), durch das `CameraInfo`-Record greifen – das wird sich später rächen, wenn Layer-Verwaltung (Sichtbarkeit, aktive Layer pro Dokument) dazukommt. 

**Vorschlag:** `defaultLayerId` in einen eigenen `LayerQuery` (oder `DiagramConfig`) verschieben und über den `InteractionContext` separat injizieren. `CameraInfo` bleibt rein geometrisch (`zoom`, `offsetX`, `offsetY`).

### 4. `ConnectingState` hat keine Preview 🟠

Während des Verbindungsaufbaus sammelt der State Waypoints in einer internen Liste – aber niemand kann sie sehen. Es gibt keine `WaypointsChanged`- oder `ConnectionPreview`-Fakten, und der State exponiert sich nicht selbst über den Context. Der User klickt also blind ins Leere, bis er auf einem Zielobjekt landet. Das ist kein Architektur-, sondern ein UX-Gap – aber es ist genau die Sorte Lücke, die später zu einem parallelen Preview-Mechanismus führt, der dann nebenher existiert.

**Vorschlag:** Eine der beiden Varianten:

- **Events:** `ActionEvent.WaypointAdded` / `ConnectionPreviewChanged` beim Hinzufügen publizieren; ein ViewMapper rendert daraus die Vorschau.
- **Query:** `PreviewQuery` im `InteractionContext`, die von `ConnectingState` (via Context-Callback) aktuelle Waypoints + Mausposition liefert.

Ich würde zur Event-Variante greifen – sie passt zur bestehenden „Requests rein, Fakten raus"-Linie, und `ActionEvent.AddWaypoint` existiert bereits ungenutzt, was sehr nach geplantem Vorhaben aussieht.

### 5. `IdleToolState`: einfacher Klick selektiert nicht 🟡

In `PendingMoveState` steht als Kommentar:

```java
// Hier könnte optional ein Select-Event gefeuert werden:
// c.publisher().publish(new ActionEvent.ObjectSelected(target.id()));
```

Aktuell selektiert nur der Doppelklick (via `ResizeModeState`). Ein einfacher Klick auf ein Objekt tut nichts. Das ist entweder ein bewusster Zwischenstand oder ein UX-Loch. Wichtig: Das fehlende Selection-Event ist **nicht** das Problem – das Problem ist, dass die *Semantik* von „Klick auf Objekt" jetzt in `PendingMoveState` (nichts tun) und `ResizeModeState` (auswählen + Resize) unterschiedlich ist. Wenn du später Selektionserweiterung (Shift-Klick) brauchst, hast du zwei Stellen, an denen du das einbauen musst.

**Vorschlag:** Objekt-Klick (ohne Drag) sollte generell `SetSelection([id])` publizieren – an einer Stelle (`PendingMoveState`). Der Doppelklick in `IdleToolState` bleibt die Ausnahme (Resize-Mode-Einstieg).

### 6. Modell-Records validieren ihre Werte nicht 🟡

`Circle(radius)`, `Rectangle(width, height)` und `Connection` haben keine Validierung. `GridSpec` macht es richtig (Compact-Constructor wirft). Da alle Schreibpfade über den Bus laufen und `HandleDragState` clamped, ist es aktuell nicht akut – aber sobald du Serialisierung (Persistenz/Load) bekommst, fliegen ungültige Geometrien ungeprüft ins Modell. Eine stille 0/negative Größe ist deutlich schwerer zu finden als eine Exception beim Erzeugen.

**Vorschlag:** Compact-Constructor für die Geometrie-Records:

```java
public Circle {
    if (radius <= 0) throw new IllegalArgumentException("radius > 0");
}
```

### 7. Kleinere Punkte

- **`DiagramService.connections()`** ist eine öffentliche Klassenmethode, nicht im `ConnectionQuery`-Interface. Für UI/Tests bequem, aber die Schichtung „Query-Interfaces im model-Package" wird inkonsistent. Entweder konsequent `ConnectionQuery` einführen oder bewusst als Service-API dokumentieren.
- **`ActionEvent.AddWaypoint`** ist totes Kapital (nie publiziert). Absicht oder Vorbereitung? Wenn Vorbereitung: kommentieren, sonst raus.
- **`DiagramEvent.ObjectMovedApplied`** trägt `(id, x, y)`, `ObjectResizedApplied` trägt das ganze Objekt. Asymmetrie. Vermutlich weil Move inkrementell kommt (Delta-Events) und Resize nur final. Nachvollziehbar – aber ein kurzer Kommentar am Event würde künftigen Lesern helfen.
- **Keine Tests sichtbar** – bei *dieser* Architektur ist Testen so leicht wie selten (reine `handleInput(event, context)`-Funktionen mit Mock-Context). Nutze das jetzt, solange die States klein sind. Insbesondere die Guard-Disjunktion sollte einen Test haben, der einen neuen State mit kollidierenden Regeln als Fail entlarvt.
- **`InputEvent.MouseReleased` in `CameraPanningState`** reagiert auf *jede* Maustaste, nicht nur die, mit der das Panning gestartet wurde. Bei gleichzeitigem Drücken von Mittel + Links könnte das seltsam werden. Vermutlich irrelevant, aber `MouseReleased` ohne Button-Guard ist ein kleines Risiko.

## Fazit

**Solide Basis mit zwei echten Aufräum-Baustellen (#1 und #2) und drei „jetzt oder nie"-Gelegenheiten (#4, #5, #6).**

Wenn du **nur eine** Sache machst: ziehe den Tool-Switch + die Shortcuts in einen `ToolState`-Abstrakt (Punkt #1). Das ist der einzige Punkt, der bei jedem neuen Feature zwangsweise schmerzt und dann nur noch mit Refactoring zu beheben ist.

Wenn du **zwei** Sachen machst: zusätzlich die `IdleToolState`-Inkonsistenz (#2) beseitigen – die ist ein latenter Laufzeit-Crash, sobald du ESC dort ergänzt.

Alles andere ist „Aufräumen beim nächsten Anlass" und kein Grund, den Bau zu stoppen.

Und noch ein methodischer Tipp: die **Guard-Disjunktions-Invariante ist Gold** – halte sie auch in `ToolState` konsequent ein, sonst hast du das zentrale Qualitätsmerkmal des Projekts an der Stelle aufgegeben, an der die meisten Regeln leben.
