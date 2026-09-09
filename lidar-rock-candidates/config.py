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
NATIVE_RES = 0.5            # native Rasterweite der WCS-Coverages [m]
COARSE_RES = 2.0            # Analyse-/Wandstrukturskala [m]
AGG = int(round(COARSE_RES / NATIVE_RES))   # Aggregationsfaktor 0.5 m -> 2 m

TILE_SIZE_M = 1000          # Kachelkantenlaenge fuer den Download [m]

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
NDOM_MAX_M = 1.5            # nDOM = DOM - DGM; darunter gilt "unbewachsen"

# Zweite Skala (0,5 m): Verschneidung der beiden Neigungsskalen.
# REQUIRE_BOTH_SCALES=True fordert zusaetzlich Steilheit im nativen Raster.
REQUIRE_BOTH_SCALES = False
SLOPE_MIN_DEG_NATIVE = 55.0

# --------------------------------------------------------- Morphologie --
OPENING_ITER = 1            # entfernt Salt-and-Pepper
CLOSING_ITER = 2            # schliesst Loecher in Wandflaechen
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

HTTP_TIMEOUT = 300
HTTP_RETRIES = 4
BLOCK_SIZE = 2048           # Fensterkantenlaenge fuer windowed processing
