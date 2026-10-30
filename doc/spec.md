Was habe ich so geplant fuer den FMC-Editor:
- Es soll allgemein eine Verschiebung des Canvas moegliche sein
- Es soll allgemein ein Zoom an der Stelle des Mauszeigers mit dem Mausrad moeglich sein.
- Erstellen von Kreisen (K) und Rechtecken (R) durch Auswahl eines Tools an der Seite. Also ein Tool fuer Ks udn eins fuer Rs.
- Erstellen von Verbindugen durch die Auswahl eines Tool an der Seite.
  - Klick auf das Start Objekt (zB K), dann moegliche zwischenpunkte (Waypoints W), und klick auf das Ziel Objekt (zB R). Es koennen 0-n Zwischenpunkte geben. Diese werden durch eine Linie verbunden und an den Ecken abgerunde. Wo die Linie endet, wird ein pfeil zu sehen sein.
  - Die Ws sind Routenpunkte ihrer Verbindung — Teil der Verbindung, keine eigenstaendigen Objekte. Sie sind die Erfindung, den Polygonzug von K nach R zu formen. Daraus ergibt sich:
    - Ws lassen sich per Klick oder Gummiband selektieren und einzeln verschieben, um die Form der Route nach eigenem Geschmack zu veraendern. Doppelklick auf ein W selektiert es nur (keine Resize-Griffe — Ws haben keine Groesse).
    - Sind BEIDE Endobjekte einer Verbindung in der Auswahl, wandern ihre Ws mit der gesamten Auswahl mit — die Route gehoert zur Verbindung, ihre Form bleibt erhalten. Das gilt auch dann, wenn die Ws gerade nicht sichtbar sind: Der Benutzer denkt in dem Moment nicht an unsichtbare Ws und erwartet, dass die ganze Verbindung mitkommt.
    - Ist nur EIN Endobjekt (oder keines) der Verbindung in der Auswahl, bleiben die Ws an ihrem Ort verankert.
    - Feinsteuerung: Klick auf die Verbindung blendet den W-Layer ein (siehe Ebenen); danach lassen sich die Ws einzeln greifen und verschieben.
    - Waehrend des Verbindungsaufbaus gilt ein Klick auf ein vorhandenes W wie ein Klick auf die leere Flaeche: Es wird ein NEUER Zwischenpunkt an dieser Stelle gesetzt. Ws sind keine Verbindungsziele.
- Verschieben von ausgewaehlten Objekten. Die Auswahl kann ein oder mehrere Objekte enhalten. Diese konnen sowohl Ks, Rs und Ws sien.
  - Beim Verschieben soll ein virtuelles Netz die Objekte an den Kreuzungspunkten kleben lassen bis die Maus naeher an dem neuen Kreuzungspunkt liegt. Spapping.
- Auswahl von Objekten (Selektion):
  - Einfacher Klick auf ein Objekt: selektiert dieses Objekt und deselektiert die bisher ausgewaehlten Objekte.
  - Einfacher Klick auf ein anderes Objekt: selektiert das andere Objekt und deselektiert das aktuell selektierte Objekt.
  - CTRL+Klick auf ein zweites Objekt: selektiert das zweite Objekt zusaetzlich zum bereits selektierten Objekt.
  - CTRL+Klick auf ein drittes Objekt: selektiert das dritte Objekt zusaetzlich zu den bereits selektierten Objekten.
  - CTRL+Klick auf ein bereits selektiertes Objekt: entfernt dieses Objekt wieder aus der Auswahl.
  - Einfacher Klick auf die leere Flaeche: hebt die Auswahl auf. Ebenso ESC.
  - CTRL+Klick+Drag auf der leeren Flaeche spannt ein Auswahlrechteck (Gummiband) auf. Beim Loslassen werden alle Objekte im Rechteck ausgewaehlt; das Band ersetzt die bisherige Auswahl. Ein CTRL+Klick ohne Drag aendert nichts, ESC bricht das Band ab.
- Loeschen: Die DEL-Taste loescht alle aktuell selektierten Objekte.
- Groessenanpassung der Ks und Rs. Auch Text-Boxen (siehe unten) sind auf diese Weise in der Groesse veraenderbar.
- Angaben von Text durch Auswahl eines Text-Werkzeugs an der Seite:
  - Innerer Text im K: Ein K enthaelt 0 oder 1 Text, und dieser besteht aus genau einem Buchstaben. Die Eingabemaske begrenzt auf ein Zeichen.
  - Innerer Text im R: Ein R enthaelt 0 oder 1 mehrzeiligen Text. Der Text kann zentriert werden (Schalter je R).
    - Der innere Text nutzt die Box des R abzueglich eines kleinen Innenrands. Wird das R in der Groesse geaendert, passt sich die Text-Box automatisch mit an (kein eigenes Resize noetig).
  - Externe Texte an K und R: 0 bis beliebig viele Texte koennen ausserhalb zu einem K oder R gehoeren.
    - Jeder externe Text hat eine eigene rechteckige Box und ist in der Groesse veraenderbar (Resize).
    - Wird das zugehoerige K oder R verschoben, wandern alle seine externen Texte mit.
    - Wird das zugehoerige K oder R geloescht, werden seine externen Texte mitgeloelscht.
  - Text an Verbindungen: Jede Verbindung kann 0 oder 1 Text haben.
    - Der Text ist an einem Verbindungsabschnitt verankert (Anker): Ein Klick auf die Verbindung legt den Text am geklickten Abschnitt fest (z. B. zwischen W1 und W2), inklusive Abstand zur Linie.
    - Verschiebt der Benutzer den Text, wird der Anker neu gesetzt (naechstliegender Abschnitt plus Position entlang der Linie). Das Platzieren bleibt damit beim Benutzer.
    - Veraendert sich die Verbindung (z. B. ein W wird verschoben oder ein Endpunkt wandert), gleitet der Text mit seinem Abschnitt mit — die Platzierungs-Absicht bleibt erhalten.
    - Wird ein W geloescht, rutscht der Anker auf den dann letzten Abschnitt; Position entlang der Linie und Abstand bleiben.
    - Der Text liegt in einer eigenen rechteckigen Box und ist in der Groesse veraenderbar.
    - Beim Verschieben gilt auch hier das Snapping: Der Anker wird so gesetzt, dass der Mittelpunkt der Box auf einem Gitterpunkt liegt. Veraendert sich die Verbindung spaeter, folgt der Text seinem Abschnitt (siehe oben).
    - Wird die Verbindung geloescht, verschwindet ihr Text mit.
  - Freier Text: Ein Klick auf die leere Flaeche erzeugt einen freien Text ohne Zuordnung (z. B. fuer Anmerkungen).
  - Snapping von Texten: Beim VERSCHIEBEN eines Textes landet der Mittelpunkt seiner Bounding-Box auf einem Gitterpunkt des virtuellen Netzes. Bei der Erstellung bleibt der Text frei an der Klickstelle bzw. an seiner Default-Position. So lassen sich Texte neben oder ueber einem R so anordnen, dass der Text-Mittelpunkt und der R-Mittelpunkt auf einer Linie liegen.
- Bedienung des Text-Werkzeugs:
  - Klick auf ein K oder R: legt den inneren Text an bzw. oeffnet ihn zum Bearbeiten.
  - CTRL+Klick auf ein K oder R: legt ein externes Text-Feld unterhalb des Objekts an und oeffnet es zum Bearbeiten.
  - Klick auf einen bestehenden Text: oeffnet diesen Text zum Bearbeiten.
  - Klick in die Naehe einer Verbindung: legt das Verbindungs-Label am geklickten Abschnitt an. Hat die Verbindung bereits einen Text, wird dieser stattdessen geoeffnet (maximal einer pro Verbindung).
  - Doppelklick (im Auswahl-Werkzeug) oeffnet die Resize-Griffe — einheitlich bei K, R und Texten.
- Textbearbeitung: Der Text wird in einem Inline-Editor direkt am Platz bearbeitet. ENTER schliesst die Eingabe ab (nur bei Aenderung wirkt sie sich aus), ESC verwirft sie, ein Klick daneben schliesst ebenfalls ab.
- Speicherformat: Das JSON-Format erhaelt zusaetzlich einen "texts"-Abschnitt (freie, externe und Verbindungs-Texte mit Box und Zuordnung) sowie ein "label"-Feld beim K und "content"/"centered" beim R.
- Das geammte gezeichnete Objekt kann im volgenden Format gespeichert werden:
```json
{
  "objects": [
    {
      "id": "646e01ac-9b48-4503-8a55-c7b7c6cfa766",
      "type": "CIRCLE",
      "x": 200.0,
      "y": 200.0,
      "width": 40.0,
      "height": 40.0,
      "layerId": "00000000-0000-0000-0000-000000000000"
    },
    {
      "id": "9baf090b-06a5-46ef-aa16-8e146ab0dade",
      "type": "RECTANGLE",
      "x": 200.0,
      "y": 720.0,
      "width": 124.0,
      "height": 74.0,
      "layerId": "00000000-0000-0000-0000-000000000000"
    },
    {
      "id": "4a90fc9e-4569-4f65-8cf2-2cbc88370e98",
      "type": "WAYPOINT",
      "x": 300.0,
      "y": 640.0,
      "width": 10.0,
      "height": 10.0,
      "layerId": "11111111-1111-1111-1111-111111111111"
    },
    {
      "id": "3ca4ac09-d15b-4074-adc9-a911be15bb6a",
      "type": "CIRCLE",
      "x": 200.0,
      "y": 400.0,
      "width": 40.0,
      "height": 40.0,
      "layerId": "00000000-0000-0000-0000-000000000000"
    },
    {
      "id": "96b4e753-e2e0-4844-a640-90dabb1ef442",
      "type": "WAYPOINT",
      "x": 80.0,
      "y": 560.0,
      "width": 10.0,
      "height": 10.0,
      "layerId": "11111111-1111-1111-1111-111111111111"
    },
    {
      "id": "8a2432d5-7469-461b-afa8-dbe5a6cfa2c6",
      "type": "WAYPOINT",
      "x": 540.0,
      "y": 520.0,
      "width": 10.0,
      "height": 10.0,
      "layerId": "11111111-1111-1111-1111-111111111111"
    },
    {
      "id": "f59ff5d8-6d0a-46eb-a409-cec6e010c071",
      "type": "WAYPOINT",
      "x": 200.0,
      "y": 560.0,
      "width": 10.0,
      "height": 10.0,
      "layerId": "11111111-1111-1111-1111-111111111111"
    },
    {
      "id": "371a67e4-57cb-46f3-99b4-62e94eaf345e",
      "type": "RECTANGLE",
      "x": 200.0,
      "y": 280.0,
      "width": 144.0,
      "height": 68.0,
      "layerId": "00000000-0000-0000-0000-000000000000"
    },
    {
      "id": "79787493-a9bd-478f-9ca7-5422884b6d7b",
      "type": "RECTANGLE",
      "x": 200.0,
      "y": 500.0,
      "width": 131.4051513671875,
      "height": 76.0074462890625,
      "layerId": "00000000-0000-0000-0000-000000000000"
    },
    {
      "id": "68b21af4-c774-426b-99d0-df180cca77d7",
      "type": "WAYPOINT",
      "x": 300.0,
      "y": 600.0,
      "width": 10.0,
      "height": 10.0,
      "layerId": "11111111-1111-1111-1111-111111111111"
    },
    {
      "id": "6986719e-21a1-4407-a03f-11752ab35ef3",
      "type": "WAYPOINT",
      "x": 440.0,
      "y": 720.0,
      "width": 10.0,
      "height": 10.0,
      "layerId": "11111111-1111-1111-1111-111111111111"
    },
    {
      "id": "45b321ce-a369-44a2-a5ce-e60c79ecf0ed",
      "type": "CIRCLE",
      "x": 200.0,
      "y": 620.0,
      "width": 40.0,
      "height": 40.0,
      "layerId": "00000000-0000-0000-0000-000000000000"
    },
    {
      "id": "c7523b4a-1efc-4168-a557-4bbdb4984c0b",
      "type": "WAYPOINT",
      "x": 540.0,
      "y": 720.0,
      "width": 10.0,
      "height": 10.0,
      "layerId": "11111111-1111-1111-1111-111111111111"
    },
    {
      "id": "16116ae9-ce2b-4ac5-9a1b-b80f2e03ccf7",
      "type": "WAYPOINT",
      "x": 80.0,
      "y": 340.0,
      "width": 10.0,
      "height": 10.0,
      "layerId": "11111111-1111-1111-1111-111111111111"
    },
    {
      "id": "bafe3a89-cd6a-41b5-98cd-56414c0bd916",
      "type": "WAYPOINT",
      "x": 200.0,
      "y": 340.0,
      "width": 10.0,
      "height": 10.0,
      "layerId": "11111111-1111-1111-1111-111111111111"
    },
    {
      "id": "e6c2ab3d-1660-4e59-8c9d-d65c36dd3006",
      "type": "WAYPOINT",
      "x": 440.0,
      "y": 520.0,
      "width": 10.0,
      "height": 10.0,
      "layerId": "11111111-1111-1111-1111-111111111111"
    },
    {
      "id": "94115c80-3693-492e-8c89-1505c47b7d64",
      "type": "RECTANGLE",
      "x": 540.0,
      "y": 620.0,
      "width": 188.0,
      "height": 112.0,
      "layerId": "00000000-0000-0000-0000-000000000000"
    }
  ],
  "connections": {
    "4372db31-9b26-4266-be31-59f039068335": {
      "sourceId": "3ca4ac09-d15b-4074-adc9-a911be15bb6a",
      "targetId": "79787493-a9bd-478f-9ca7-5422884b6d7b",
      "waypointIds": []
    },
    "90fed3d5-3213-4547-9646-baa26b3edd94": {
      "sourceId": "45b321ce-a369-44a2-a5ce-e60c79ecf0ed",
      "targetId": "9baf090b-06a5-46ef-aa16-8e146ab0dade",
      "waypointIds": []
    },
    "ed1ab53a-eaa4-4ec6-abdb-ecec44630bb7": {
      "sourceId": "371a67e4-57cb-46f3-99b4-62e94eaf345e",
      "targetId": "45b321ce-a369-44a2-a5ce-e60c79ecf0ed",
      "waypointIds": [
        "bafe3a89-cd6a-41b5-98cd-56414c0bd916",
        "16116ae9-ce2b-4ac5-9a1b-b80f2e03ccf7",
        "96b4e753-e2e0-4844-a640-90dabb1ef442",
        "f59ff5d8-6d0a-46eb-a409-cec6e010c071"
      ]
    },
    "d86e972a-d8a1-4572-b201-252edaf8ebf4": {
      "sourceId": "79787493-a9bd-478f-9ca7-5422884b6d7b",
      "targetId": "45b321ce-a369-44a2-a5ce-e60c79ecf0ed",
      "waypointIds": []
    },
    "720655dd-9b18-4a17-9a34-4cc6dcb34a32": {
      "sourceId": "94115c80-3693-492e-8c89-1505c47b7d64",
      "targetId": "45b321ce-a369-44a2-a5ce-e60c79ecf0ed",
      "waypointIds": [
        "c7523b4a-1efc-4168-a557-4bbdb4984c0b",
        "6986719e-21a1-4407-a03f-11752ab35ef3",
        "4a90fc9e-4569-4f65-8cf2-2cbc88370e98"
      ]
    },
    "fc8801da-45a5-407f-bad6-430ca487eccf": {
      "sourceId": "646e01ac-9b48-4503-8a55-c7b7c6cfa766",
      "targetId": "371a67e4-57cb-46f3-99b4-62e94eaf345e",
      "waypointIds": []
    },
    "4a8b98e5-ce47-484b-b6c4-4ccaa36067d0": {
      "sourceId": "45b321ce-a369-44a2-a5ce-e60c79ecf0ed",
      "targetId": "94115c80-3693-492e-8c89-1505c47b7d64",
      "waypointIds": [
        "68b21af4-c774-426b-99d0-df180cca77d7",
        "e6c2ab3d-1660-4e59-8c9d-d65c36dd3006",
        "8a2432d5-7469-461b-afa8-dbe5a6cfa2c6"
      ]
    },
    "069cc530-1334-4fd0-9f12-759d30234669": {
      "sourceId": "371a67e4-57cb-46f3-99b4-62e94eaf345e",
      "targetId": "3ca4ac09-d15b-4074-adc9-a911be15bb6a",
      "waypointIds": []
    }
  },
  "layers": [
    {
      "id": "00000000-0000-0000-0000-000000000000",
      "name": "Standard",
      "visible": true
    },
    {
      "id": "11111111-1111-1111-1111-111111111111",
      "name": "Wegpunkte",
      "visible": false
    }
  ]
}
```
