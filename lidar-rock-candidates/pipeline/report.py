"""README-Erzeugung - Schwellenwerte kommen direkt aus config.py."""
from datetime import date
from pathlib import Path

import config as C


def write_readme(path, stats):
    w = C.WEIGHTS
    tw = sum(w.values())
    gl = "\n".join(
        f"| {cls} | {sc:.2f} | {', '.join(keys[:8])}{' …' if len(keys) > 8 else ''} |"
        for sc, cls, keys in C.GEOLOGY_RULES)

    txt = f"""# Felskandidaten aus LiDAR - Welschellen/Rina, Gadertal

Automatisch abgeleitete Kandidatenflaechen fuer Klettern/Bouldern im 5-km-Umkreis
um Welschellen/Rina (Gadertal, Suedtirol), aus den LiDAR-Hoehenmodellen der
Autonomen Provinz Bozen.

Erzeugt am {date.today().isoformat()}.

**Das Ergebnis ist eine Vorauswahl fuer die Kartenarbeit, kein Kletterfuehrer.**
Jede Flaeche muss vor Ort geprueft werden.

---

## 1. Gebiet

| | |
|---|---|
| Zentrum (WGS84, Vorgabe) | {C.AOI_CENTER_LATLON[0]} N, {C.AOI_CENTER_LATLON[1]} E |
| Zentrum laut Nominatim ("Rina - Welschellen") | {C.AOI_CENTER_LATLON_NOMINATIM[0]} N, {C.AOI_CENTER_LATLON_NOMINATIM[1]} E |
| Abweichung | {stats['center_offset_m']:.0f} m |
| Puffer | {C.AOI_BUFFER_M:.0f} m |
| Arbeits-CRS | {C.CRS} (ETRS89 / UTM 32N) |
| Bounding Box | {stats['bbox'][0]:.2f}, {stats['bbox'][1]:.2f} .. {stats['bbox'][2]:.2f}, {stats['bbox'][3]:.2f} |

Die Nominatim-Abfrage bestaetigt die Vorgabe grob: der Ortskern liegt rund
{stats['center_offset_m']:.0f} m suedwestlich des vorgegebenen Zentrums, also weit
innerhalb des 5-km-Puffers. Gerechnet wurde mit der Nutzervorgabe
(`AOI_CENTER_LATLON` in `config.py`).

## 2. Datenquellen

WCS der Autonomen Provinz Bozen,
`https://geoservices9.civis.bz.it/geoserver/wcs` (WCS {C.WCS_VERSION}).
Die Coverage-Liste aus `GetCapabilities` liegt in `docs/wcs_coverages.txt`.

| Rolle | Coverage-ID | Aufloesung |
|---|---|---|
| DGM | `{C.COV_DGM}` | {C.NATIVE_RES} m |
| DOM | `{C.COV_DOM}` | {C.NATIVE_RES} m |

DGM und DOM liegen beide nativ in {C.NATIVE_RES} m vor - ein Resampling des DOM
auf das DGM-Grid war **nicht** noetig, beide teilen dasselbe Raster
(Ursprung, Zellgroesse, NoData = {C.NODATA:.0f}).

Kontextlayer:

| Layer | Quelle |
|---|---|
| Geologie | WFS `{C.LYR_GEOLOGY}` (geoservices1) |
| Naturparke | WFS `{C.LYR_PARKS}` (geoservices1) |
| Wege/Strassen | OSM via Overpass |

Die detaillierte geologische Karte (`GeologicalUnits-Detailed`, CARG-Blaetter)
wurde getestet und liefert im Gadertal **0 Features** - das Blatt ist dort nicht
kartiert. Deshalb die Uebersichtskarte, die entsprechend grober ist.

## 3. Verarbeitung

1. **Download** gekachelt zu {C.TILE_SIZE_M} x {C.TILE_SIZE_M} m, jede Kachel auf
   Platte gecacht, vorhandene Kacheln werden uebersprungen. Kacheln ohne
   DGM-Daten bekommen einen `.empty`-Marker und werden nicht erneut angefragt.
2. **Mosaik** als VRT ueber die Kacheln; NoData {C.NODATA:.0f}, CRS {C.CRS}.
3. **Zwei Neigungsskalen**: Hangneigung nach Horn einmal nativ auf {C.NATIVE_RES} m,
   einmal auf {C.COARSE_RES} m aggregiert. Die {C.NATIVE_RES}-m-Variante ist
   verrauscht (Wurzelteller, Blockwerk, Filterartefakte); die
   {C.COARSE_RES}-m-Variante traegt die Wandstruktur und ist das Hauptkriterium.
   Beide werden im Analysestack gefuehrt und verschnitten
   (`REQUIRE_BOTH_SCALES = {C.REQUIRE_BOTH_SCALES}`).
4. **nDOM** = DOM - DGM, zellweise auf dem gemeinsamen {C.NATIVE_RES}-m-Grid.
5. **Felsmaske**: Neigung({C.COARSE_RES} m) > {C.SLOPE_MIN_DEG:.0f} Grad **und**
   nDOM < {C.NDOM_MAX_M} m.
6. **Morphologie**: Opening ({C.OPENING_ITER}x) gegen Salt-and-Pepper, danach
   Closing ({C.CLOSING_ITER}x) gegen Loecher, 8er-Nachbarschaft; Flaechen unter
   {C.MIN_PIXELS} Zellen entfallen. Danach Labeling zusammenhaengender Flaechen.
7. **Kennwerte je Flaeche**: Grundflaeche, vertikale Erstreckung (max-min der
   DGM-Hoehe), mittlere/maximale Neigung, mittlere Exposition (zirkulaeres
   Mittel), Zentroid, Hoehe ue.M.
8. **Filter**: vertikale Erstreckung >= {C.MIN_VERTICAL_EXTENT_M:.0f} m
   **und** Flaeche >= {C.MIN_AREA_M2:.0f} m2.

Die {C.NATIVE_RES}-m-Raster werden ausschliesslich blockweise (rasterio windowed,
Blockkante {C.BLOCK_SIZE}) gelesen und direkt zu einem {C.COARSE_RES}-m-Analysestack
verdichtet. Die vertikale Erstreckung bleibt dabei exakt: je {C.COARSE_RES}-m-Zelle
werden Minimum und Maximum der darunterliegenden {C.AGG}x{C.AGG}
{C.NATIVE_RES}-m-Zellen mitgefuehrt, sodass die Spanne je Flaeche der
Vollaufloesung entspricht.

## 4. Schwellenwerte

Alle Werte stehen als Konstanten oben in `config.py`.

| Konstante | Wert | Bedeutung |
|---|---|---|
| `SLOPE_MIN_DEG` | {C.SLOPE_MIN_DEG} Grad | Mindestneigung auf {C.COARSE_RES} m |
| `NDOM_MAX_M` | {C.NDOM_MAX_M} m | maximale Objekthoehe ueber Grund |
| `REQUIRE_BOTH_SCALES` | {C.REQUIRE_BOTH_SCALES} | zusaetzlich Steilheit auf {C.NATIVE_RES} m |
| `SLOPE_MIN_DEG_NATIVE` | {C.SLOPE_MIN_DEG_NATIVE} Grad | Schwelle dafuer |
| `OPENING_ITER` / `CLOSING_ITER` | {C.OPENING_ITER} / {C.CLOSING_ITER} | Morphologie |
| `MIN_PIXELS` | {C.MIN_PIXELS} | Mindestzellen vor Vektorisierung |
| `MIN_VERTICAL_EXTENT_M` | {C.MIN_VERTICAL_EXTENT_M} m | Filter Wandhoehe |
| `MIN_AREA_M2` | {C.MIN_AREA_M2} m2 | Filter Grundflaeche |

## 5. Score

Gewichtete Summe von fuenf auf [0, 1] normierten Komponenten
(Gewichte in `config.py`, Summe wird intern auf 1 normiert):

| Komponente | Gewicht | Normierung |
|---|---|---|
| Wandhoehe | {w['wall_height']} | vertikale Erstreckung / {C.WALL_HEIGHT_FULL_SCORE_M:.0f} m, gekappt bei 1 |
| Neigungskompaktheit | {w['slope_compact']} | 1 - Neigungs-Std / {C.SLOPE_STD_FULL_PENALTY_DEG:.0f} Grad |
| Geologie-Eignung | {w['geology']} | Tabelle unten |
| Suedexposition | {w['south_aspect']} | (1 - cos(Exposition)) / 2; 1 = Sued |
| Wegnaehe | {w['access']} | 1 bei <= {C.ACCESS_BEST_M:.0f} m, 0 ab {C.ACCESS_WORST_M:.0f} m |

Summe der Rohgewichte: {tw:.2f}.

Geologie-Bewertung (erster Treffer gewinnt, case-insensitiv):

| Klasse | Eignung | Schluesselwoerter |
|---|---|---|
{gl}
| unbekannt | {C.GEOLOGY_DEFAULT[0]:.2f} | kein Treffer |

Moraene, Werfener und Bellerophon-Schichten werden als
`unbrauchbar_vermutet` markiert und im Score abgewertet, aber **nicht entfernt** -
sie stehen mit Flag in GeoPackage und CSV.

## 6. Ergebnisse

| | |
|---|---|
| Kacheln mit Daten / angefragt | {stats['tiles_with_data']} / {stats['tiles_total']} |
| Abdeckung {C.NATIVE_RES}-m-DGM im AOI | {stats['coverage_pct']:.1f} % |
| Rohflaechen nach Labeling | {stats['n_raw']} |
| Kandidaten nach Filter | {stats['n_final']} |
| davon im Schutzgebiet | {stats['n_protected']} |
| davon Geologie "gut" | {stats['n_geo_good']} |
| davon Geologie "unbrauchbar_vermutet" | {stats['n_geo_bad']} |

Dateien:

- `output/felskandidaten.gpkg` - Kandidatenpolygone samt Attributen, dazu die
  Kontextlayer `geologie`, `schutzgebiete`, `wege`
- `output/felskandidaten.csv` - nach Score sortiert
- `output/felskandidaten.qgs` - QGIS-Projekt mit Schummerung, nDOM, Neigung,
  Kandidaten und Kontextlayern
- `data/derived/` - Analysestack, Neigung, Exposition, Schummerung, nDOM

---

## 7. Einschraenkungen

**Diese Liste ist Teil des Ergebnisses. Wer die Kandidaten ohne sie liest,
liest sie falsch.**

### 7.1 2,5D - Ueberhaenge sind physikalisch nicht abbildbar

DGM und DOM sind 2,5D-Raster: ein Hoehenwert pro Zelle. Eine Wand mit Dach,
ein Ueberhang, eine Hoehle oder ein Blockdach koennen darin grundsaetzlich
nicht dargestellt werden. Solche Formen erscheinen bestenfalls als **senkrecht**
(90 Grad) und werden dadurch systematisch unterschaetzt: die interessantesten
Boulder- und Sportkletterformen sind im Modell nicht von einer schlichten
senkrechten Wand zu unterscheiden. Umgekehrt bedeutet das: eine als "90 Grad"
gefundene Flaeche kann vor Ort deutlich ueberhaengend sein - oder eben nicht.
Das Verfahren kann Ueberhaenge weder finden noch ausschliessen.

### 7.2 Bodenfilterung

Das DGM entsteht aus der Bodenpunktklassifikation der LiDAR-Punktwolke. Diese
Filterung entfernt regelmaessig auch echten Fels: freistehende Bloecke, Zinnen,
Tuerme und schmale Rippen werden als "nicht Boden" verworfen oder in die
Umgebung eingeglaettet, kleine Baender und Absaetze verschwinden ganz. In
steilem Gelaende neigen Bodenfilter zusaetzlich dazu, Wandfuesse zu ueber- und
Wandkoepfe zu unterschaetzen, sodass die vertikale Erstreckung eher zu klein
als zu gross ausfaellt. Genau die Objekte, die fuer Bouldern interessant sind -
einzelne Bloecke von wenigen Metern - liegen an der Grenze dessen, was die
Filterung ueberhaupt stehen laesst. Umgekehrt erzeugen Filterartefakte
(Wurzelteller, Gelaendekanten, Restvegetation) senkrechte Stufen, die als
Fels erscheinen, ohne welcher zu sein.

### 7.3 Abdeckungsluecke im {C.NATIVE_RES}-m-Modell

Das {C.NATIVE_RES}-m-Produkt der Provinz ist **kein flaechendeckendes Modell**.
Im AOI deckt es nur rund **{stats['coverage_pct']:.0f} %** der Flaeche ab, in
korridorfoermigen Streifen entlang der Gewaesser und Talboeden. Rund
{100 - stats['coverage_pct']:.0f} % des Umkreises - darunter ein grosser Teil der
Hangflanken, auf denen Felswaende ueberhaupt erst zu erwarten sind - sind in
{C.NATIVE_RES} m **gar nicht erfasst**. Die Kandidatenliste ist deshalb
*nicht* als vollstaendige Inventur des 5-km-Umkreises zu lesen, sondern als
vollstaendige Auswertung der {stats['coverage_pct']:.0f} % Flaeche, fuer die
{C.NATIVE_RES}-m-Daten existieren. Flaechendeckend verfuegbar waeren nur
DGM/DOM in 2,5 m (`{C.COV_DGM_FALLBACK}`); das ist bewusst **nicht**
automatisch verwendet worden.

### 7.4 nDOM-Schwelle schneidet in beide Richtungen

`nDOM < {C.NDOM_MAX_M} m` entfernt bewachsene Steilhaenge, entfernt aber auch
**Fels unter Baumkronen** - im Gadertal betrifft das viele niedrigere
Wandstufen im Wald. Umgekehrt bleiben Felspartien mit dichtem Bewuchs
faelschlich stehen, wenn die Bodenfilterung die Vegetation bereits ins DGM
uebernommen hat.

### 7.5 Skalenwahl

Die Maske arbeitet auf {C.COARSE_RES} m. Bloecke unter etwa
{2 * C.COARSE_RES:.0f} m Kantenlaenge werden dadurch weggemittelt - fuer
Bouldern also die falsche Skala. Der Filter
`MIN_VERTICAL_EXTENT_M = {C.MIN_VERTICAL_EXTENT_M:.0f}` entfernt Boulderbloecke
ohnehin; wer Bloecke sucht, muss ihn und `MIN_AREA_M2` deutlich senken und mit
mehr Fehlalarmen rechnen.

### 7.6 Geologie und Kontext

Die Formation wird **nur am Zentroid** gejoint. Grosse Flaechen ueber einer
Formationsgrenze bekommen dadurch eine einzige, moeglicherweise falsche
Zuordnung. Die verwendete Uebersichtskarte ist zudem stark generalisiert; die
Bewertung "Dolomit/Kalk = gut" sagt nichts ueber Felsqualitaet im Detail
(Bruechigkeit, Schichtung, Verwitterung) aus. Die Wegdistanz ist Luftlinie zum
naechsten OSM-Weg, **kein** Zustiegsweg: Hoehenunterschied, Steilheit,
Gewaesser und Zaeune sind nicht beruecksichtigt.

### 7.7 Rechtliches und Sicherheit

Das Flag `im_schutzgebiet` markiert Lage in Naturpark bzw. Nationalpark
(Naturparke Puez-Geisler und Fanes-Sennes-Prags liegen im Umkreis). In
Schutzgebieten ist Klettern regelmaessig eingeschraenkt oder verboten.
Unabhaengig davon sagt keine dieser Flaechen etwas ueber Grundeigentum,
Betretungsrechte, Vogelschutz-Sperrzeiten, Steinschlag oder Felsqualitaet.
Erschliessung nur nach Klaerung mit Grundeigentuemern, Behoerden und dem
zustaendigen Alpenverein.
"""
    Path(path).write_text(txt, encoding="utf-8")
    return path
