"""
Zentrale Konfiguration fuer die Felskandidaten-Ableitung Gadertal.

Alle Schwellenwerte und Score-Gewichte stehen hier oben und koennen ohne
Eingriff in den Analysecode veraendert werden.
"""
from pathlib import Path

# ---------------------------------------------------------------- AOI --
# Vom Nutzer vorgegebenes Zentrum (WGS84). Nominatim liefert fuer
# "Rina - Welschellen" 46.715605 N / 11.879643 E, also rund 890 m
# suedwestlich. Beide Punkte liegen tief im 5-km-Puffer, daher wird die
# Nutzervorgabe verwendet. Zum Umschalten einfach ersetzen.
AOI_CENTER_LATLON = (46.7185, 11.8905)
AOI_CENTER_LATLON_NOMINATIM = (46.715605, 11.879643)
AOI_BUFFER_M = 5000.0

CRS = "EPSG:25832"          # ETRS89 / UTM 32N - durchgaengiges Arbeits-CRS
NODATA = -9999.0

# ------------------------------------------------------------ Aufloesung --
# Zwei Durchlaeufe:
#   "hires" - 0,5 m nativ, Analyse auf 2 m. Wie spezifiziert, aber das
#             0,5-m-Produkt der Provinz deckt nur Korridore ab (~27 % des AOI).
#   "wide"  - 2,5 m nativ, Analyse auf 5 m. Flaechendeckend, deutlich grober.
# Der zweite Lauf ersetzt den ersten nicht, er ergaenzt ihn; die Ergebnisse
# werden getrennt gefuehrt und tragen das Attribut "profil".
PROFILES = {
    "hires": {
        "cov_dgm": "p_bz-Elevation__DigitalTerrainModel-0.5m",
        "cov_dom": "p_bz-Elevation__DigitalElevationModel-0.5m",
        "native": 0.5, "coarse": 2.0, "tile": 1000, "tag": "05m",
        "grid_origin": (610544.75, 5216600.25),
        "label": "0,5 m nativ / 2 m Analyse",
    },
    "wide": {
        "cov_dgm": "p_bz-Elevation__DigitalTerrainModel-2.5m",
        "cov_dom": "p_bz-Elevation__DigitalElevationModel-2.5m",
        "native": 2.5, "coarse": 5.0, "tile": 2000, "tag": "25m",
        "grid_origin": (604998.75, 5220801.25),
        "label": "2,5 m nativ / 5 m Analyse",
    },
}
ACTIVE_PROFILE = "hires"

NATIVE_RES = 0.5            # native Rasterweite [m] - via set_profile()
COARSE_RES = 2.0            # Analyse-/Wandstrukturskala [m]
AGG = int(round(COARSE_RES / NATIVE_RES))
TILE_SIZE_M = 1000          # Kachelkantenlaenge fuer den Download [m]
GRID_ORIGIN_E, GRID_ORIGIN_N = 610544.75, 5216600.25
SLOPE_MIN_ACTIVE = 60.0     # wird von set_profile() gesetzt

# ------------------------------------------------------------- Coverages --
WCS_URL = "https://geoservices9.civis.bz.it/geoserver/wcs"
WCS_VERSION = "2.0.1"
COV_DGM = "p_bz-Elevation__DigitalTerrainModel-0.5m"
COV_DOM = "p_bz-Elevation__DigitalElevationModel-0.5m"
# Flaechendeckende, aber groebere Alternative (nur nach Ruecksprache nutzen):
COV_DGM_FALLBACK = "p_bz-Elevation__DigitalTerrainModel-2.5m"
COV_DOM_FALLBACK = "p_bz-Elevation__DigitalElevationModel-2.5m"

# ------------------------------------------------------ Felsmaske-Schwellen --
SLOPE_MIN_DEG = 60.0        # Neigung auf COARSE_RES (2 m) - Hauptkriterium

# Dieselbe Gradzahl bedeutet auf verschiedenen Analyseskalen NICHT dasselbe:
# ein Horn-3x3-Kernel misst ueber zwei Zellweiten, bei 5 m also ueber 10 m
# statt ueber 4 m, und mittelt dieselbe Wand entsprechend flacher. 60 Grad
# liefern auf der 5-m-Skala praktisch nichts mehr. Fuer das grobe Profil
# wird die Schwelle deshalb kalibriert - siehe calibrate.py und README 3.
# None bedeutet: SLOPE_MIN_DEG unveraendert uebernehmen.
SLOPE_MIN_DEG_PROFILE = {"hires": None, "wide": 50.0}
NDOM_MAX_M = 1.5            # nDOM = DOM - DGM; darunter gilt "unbewachsen"

# Zweite Skala (0,5 m): Verschneidung der beiden Neigungsskalen.
# REQUIRE_BOTH_SCALES=True fordert zusaetzlich Steilheit im nativen Raster.
REQUIRE_BOTH_SCALES = False
SLOPE_MIN_DEG_NATIVE = 55.0

# --------------------------------------------------------- Morphologie --
# Reihenfolge: eine senkrechte Wand hat im Grundriss kaum Ausdehnung - sie
# ist im Raster oft nur ein bis zwei Zellen breit und in Fragmente zerrissen.
# Ein Opening als ERSTER Schritt loescht sie deshalb vollstaendig: im
# Testlauf blieben von 1893 Maskenzellen ganze 21 uebrig, von 38 brauchbaren
# Flaechen genau eine. Erst schliessen (Wandspur zusammenfuegen), dann oeffnen
# (isolierte Einzelzellen entfernen) erhaelt 25 Flaechen. Daher per Default
# "closing_first"; "opening_first" bleibt zum Vergleich waehlbar.
MORPH_ORDER = "closing_first"       # "closing_first" | "opening_first"
OPENING_ITER = 1            # entfernt Salt-and-Pepper
CLOSING_ITER = 2            # schliesst Loecher und Luecken in der Wandspur
# Nachbarschaft: 1 = 4er (schonend), 2 = 8er. Das Opening laeuft bewusst mit
# der schonenderen 4er-Nachbarschaft, damit schmale Wandspuren stehenbleiben.
OPENING_CONNECTIVITY = 1
CLOSING_CONNECTIVITY = 2
MIN_PIXELS = 10             # Mindestzellzahl vor der Vektorisierung

# ------------------------------------------------------------- Filter --
MIN_VERTICAL_EXTENT_M = 8.0   # vertikale Erstreckung (max-min DGM) [m]
MIN_AREA_M2 = 40.0            # Grundflaeche [m2]

# ------------------------------------------------------- Score-Gewichte --
# Werden intern auf Summe 1 normiert; jede Komponente liegt in [0, 1].
WEIGHTS = {
    "wall_height":      0.35,   # vertikale Erstreckung
    "slope_compact":    0.20,   # Kompaktheit/Konstanz der Neigung
    "geology":          0.20,   # Gesteinseignung
    "south_aspect":     0.15,   # Suedexposition
    "access":           0.10,   # Wegnaehe
}

# Normierungsanker fuer die Score-Komponenten
WALL_HEIGHT_FULL_SCORE_M = 40.0   # ab hier volle Punktzahl
SLOPE_STD_FULL_PENALTY_DEG = 25.0 # Neigungs-Std, ab der Kompaktheit = 0
ACCESS_BEST_M = 100.0             # Distanz zum Weg mit voller Punktzahl
ACCESS_WORST_M = 2000.0           # ab hier Punktzahl 0

# ------------------------------------------------------ Geologie-Bewertung --
# Schluesselwoerter werden case-insensitiv gegen die Formationsbezeichnung
# geprueft. Erster Treffer gewinnt, Reihenfolge ist daher relevant.
GEOLOGY_RULES = [
    # (Eignung, Klasse, [Schluesselwoerter])
    (0.10, "unbrauchbar_vermutet", [
        "moraen", "moran", "morän", "moren", "grundmoraene",
        "werfen", "bellerophon",
        "schutt", "hangschutt", "blockschutt", "detrit", "detrito",
        "quartaer", "quartär", "quaternario", "alluvi", "talfuellung",
        "eisrand", "glazial", "gletscher", "deposito", "sediment quaternari",
        "bergsturz", "rutschung", "frana",
    ]),
    # Die Uebersichtskarte fasst die dolomit-/kalkfuehrende Abfolge mit den
    # weichen Werfener- und Bellerophon-Schichten zu EINER Einheit zusammen
    # und kann sie nicht trennen. Bewertung leicht positiv, weil in dieser
    # Abfolge die wandbildenden Gesteine die Karbonate sind - Werfener bildet
    # Wiesenhaenge, keine Waende. Die Neigungsschwelle selektiert also
    # bereits weitgehend das kompetente Gestein. Siehe README 7.6.
    (0.70, "gemischt_karbonat_werfener", [
        "permo-jurassische sedimentabfolge", "sedimentabfolge",
        "permo-giurassica", "successione sedimentaria",
    ]),
    (1.00, "gut", [
        "dolomit", "dolomia", "kalk", "calcare", "riff", "schlern",
        "sciliar", "hauptdolomit", "principale", "mendel", "contrin",
        "dachstein", "rosengarten", "catinaccio", "cassian", "raibl",
    ]),
    (0.65, "mittel", [
        "granit", "granodiorit", "tonalit", "pluton", "porphyr", "vulkanit",
        "ignimbrit", "quarzphyllit", "phyllit", "gneis", "glimmerschiefer",
        "orthogneis", "paragneis", "metamorph", "quarzit",
    ]),
]
GEOLOGY_DEFAULT = (0.50, "unbekannt")

# ------------------------------------------------- Kontextlayer (WFS/OSM) --
WFS_URL_GS1 = "https://geoservices1.civis.bz.it/geoserver/wfs"
WFS_VERSION = "2.0.0"
# Die detaillierte geologische Karte (CARG-Blaetter) deckt das Gadertal
# nicht ab - getestet, liefert 0 Features. Daher die Uebersichtskarte:
LYR_GEOLOGY = "p_bz-Geology:GeologicalUnitsOverview"
GEOLOGY_NAME_FIELDS = ["LEG_DE", "LEG_IT"]
LYR_PARKS = "p_bz-TerritorialPlans:LandscapePlan-NationalAndNaturalParks"
PARK_NAME_FIELDS = ["NSO_DESC_DE", "NSO_DESC_IT", "BEZ_D"]

OVERPASS_URL = "https://overpass-api.de/api/interpreter"
OVERPASS_MIRRORS = [
    "https://overpass-api.de/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
]
# Wege/Strassen, die als Zustieg taugen
OSM_HIGHWAY_FILTER = (
    '["highway"~"^(path|footway|track|bridleway|steps|residential|'
    'unclassified|tertiary|secondary|primary|service|living_street)$"]'
)

# ------------------------------------------------------------ I/O-Pfade --
BASE_DIR = Path(__file__).resolve().parent
DATA_DIR = BASE_DIR / "data"
TILE_DIR = DATA_DIR / "tiles"
DERIVED_DIR = DATA_DIR / "derived"
CONTEXT_DIR = DATA_DIR / "context"
OUT_DIR = BASE_DIR / "output"

def set_profile(name):
    """Aktiviert ein Aufloesungsprofil. Module lesen die Konstanten zur
    Laufzeit, daher wirkt das Umschalten auf die gesamte Pipeline."""
    global ACTIVE_PROFILE, NATIVE_RES, COARSE_RES, AGG, TILE_SIZE_M
    global COV_DGM, COV_DOM, GRID_ORIGIN_E, GRID_ORIGIN_N, TILE_DIR, DERIVED_DIR
    global SLOPE_MIN_ACTIVE
    p = PROFILES[name]
    ACTIVE_PROFILE = name
    NATIVE_RES, COARSE_RES = p["native"], p["coarse"]
    AGG = int(round(COARSE_RES / NATIVE_RES))
    TILE_SIZE_M = p["tile"]
    COV_DGM, COV_DOM = p["cov_dgm"], p["cov_dom"]
    GRID_ORIGIN_E, GRID_ORIGIN_N = p["grid_origin"]
    ov = SLOPE_MIN_DEG_PROFILE.get(name)
    SLOPE_MIN_ACTIVE = SLOPE_MIN_DEG if ov is None else float(ov)
    TILE_DIR = DATA_DIR / "tiles" / p["tag"]
    DERIVED_DIR = DATA_DIR / "derived" / p["tag"]
    return p


HTTP_TIMEOUT = 300
HTTP_RETRIES = 4
BLOCK_SIZE = 2048           # Fensterkantenlaenge fuer windowed processing
