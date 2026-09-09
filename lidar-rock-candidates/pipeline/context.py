"""Stufe 5: Kontextlayer - Geologie, Schutzgebiete, Wege (OSM)."""
import time

import geopandas as gpd
import requests
from shapely.geometry import LineString, box

import config as C
from pipeline.aoi import aoi_bbox


def _wfs(layer, bbox, url=C.WFS_URL_GS1):
    params = {
        "service": "WFS", "version": C.WFS_VERSION, "request": "GetFeature",
        "typeNames": layer, "outputFormat": "application/json",
        "srsName": "EPSG:25832",
        "bbox": f"{bbox[0]},{bbox[1]},{bbox[2]},{bbox[3]},EPSG:25832",
    }
    last = None
    for attempt in range(C.HTTP_RETRIES):
        try:
            r = requests.get(url, params=params, timeout=C.HTTP_TIMEOUT)
            r.raise_for_status()
            gdf = gpd.GeoDataFrame.from_features(r.json()["features"], crs=C.CRS)
            return gdf
        except Exception as e:      # noqa: BLE001
            last = e
            if attempt < C.HTTP_RETRIES - 1:
                time.sleep(2 ** (attempt + 1))
    raise RuntimeError(f"WFS {layer} fehlgeschlagen: {last}")


def fetch_geology(bbox=None):
    return _wfs(C.LYR_GEOLOGY, bbox or aoi_bbox())


def fetch_parks(bbox=None):
    return _wfs(C.LYR_PARKS, bbox or aoi_bbox())


def fetch_osm_ways(bbox=None):
    """Wege und Strassen aus OSM via Overpass (mit Mirror-Fallback)."""
    bbox = bbox or aoi_bbox()
    poly = gpd.GeoSeries([box(*bbox)], crs=C.CRS).to_crs(4326).iloc[0]
    s, w, n, e = poly.bounds[1], poly.bounds[0], poly.bounds[3], poly.bounds[2]
    q = (f'[out:json][timeout:180];way{C.OSM_HIGHWAY_FILTER}'
         f'({s},{w},{n},{e});out geom;')

    last = None
    for endpoint in C.OVERPASS_MIRRORS:
        for attempt in range(2):
            try:
                r = requests.post(endpoint, data={"data": q}, timeout=C.HTTP_TIMEOUT)
                r.raise_for_status()
                els = r.json().get("elements", [])
                rows = []
                for el in els:
                    g = el.get("geometry") or []
                    if len(g) < 2:
                        continue
                    rows.append({
                        "osm_id": el.get("id"),
                        "highway": (el.get("tags") or {}).get("highway"),
                        "name": (el.get("tags") or {}).get("name"),
                        "geometry": LineString([(p["lon"], p["lat"]) for p in g]),
                    })
                if not rows:
                    raise RuntimeError("Overpass lieferte keine Wege")
                return gpd.GeoDataFrame(rows, crs=4326).to_crs(C.CRS)
            except Exception as ex:     # noqa: BLE001
                last = ex
                time.sleep(3 * (attempt + 1))
    raise RuntimeError(f"Overpass fehlgeschlagen: {last}")


# ------------------------------------------------------------ Verschneidung --
def _geology_label(name):
    if not name:
        return C.GEOLOGY_DEFAULT[0], C.GEOLOGY_DEFAULT[1]
    low = str(name).lower()
    for score, cls, keys in C.GEOLOGY_RULES:
        if any(k in low for k in keys):
            return score, cls
    return C.GEOLOGY_DEFAULT


def join_geology(gdf, geo):
    """Formation am Zentroid joinen und Eignung ableiten."""
    cents = gpd.GeoDataFrame(
        {"id": gdf["id"]},
        geometry=gpd.points_from_xy(gdf["centroid_e"], gdf["centroid_n"]),
        crs=C.CRS)
    name_field = next((f for f in C.GEOLOGY_NAME_FIELDS if f in geo.columns), None)
    cols = ["geometry"] + ([name_field] if name_field else [])
    j = gpd.sjoin(cents, geo[cols], how="left", predicate="within")
    j = j.drop_duplicates(subset="id", keep="first").set_index("id")

    names = j[name_field] if name_field else None
    out = gdf.copy()
    out["geologie"] = [
        (names.get(i) if names is not None else None) for i in out["id"]]
    lab = [_geology_label(v) for v in out["geologie"]]
    out["geologie_eignung"] = [x[0] for x in lab]
    out["geologie_klasse"] = [x[1] for x in lab]
    return out


def flag_protected(gdf, parks):
    name_field = next((f for f in C.PARK_NAME_FIELDS if f in parks.columns), None)
    cents = gpd.GeoDataFrame(
        {"id": gdf["id"]},
        geometry=gpd.points_from_xy(gdf["centroid_e"], gdf["centroid_n"]),
        crs=C.CRS)
    cols = ["geometry"] + ([name_field] if name_field else [])
    j = gpd.sjoin(cents, parks[cols], how="left", predicate="within")
    j = j.drop_duplicates(subset="id", keep="first").set_index("id")
    out = gdf.copy()
    vals = [(j[name_field].get(i) if name_field else None) for i in out["id"]]
    out["schutzgebiet"] = [v if v is not None and v == v else None for v in vals]
    out["im_schutzgebiet"] = [v is not None and v == v for v in vals]
    return out


def distance_to_ways(gdf, ways):
    """Distanz vom Zentroid zum naechsten Weg [m] als Zustiegsproxy."""
    cents = gpd.GeoDataFrame(
        {"id": gdf["id"]},
        geometry=gpd.points_from_xy(gdf["centroid_e"], gdf["centroid_n"]),
        crs=C.CRS)
    near = gpd.sjoin_nearest(cents, ways[["geometry", "highway", "name"]],
                             how="left", distance_col="dist_weg_m")
    near = near.drop_duplicates(subset="id", keep="first").set_index("id")
    out = gdf.copy()
    out["dist_weg_m"] = [float(near["dist_weg_m"].get(i, float("nan")))
                         for i in out["id"]]
    out["weg_typ"] = [near["highway"].get(i) for i in out["id"]]
    return out
