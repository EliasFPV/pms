"""Stufe 4: Flaechen vektorisieren und je Flaeche Kennwerte berechnen."""
import numpy as np
import rasterio
from rasterio import features
from scipy import ndimage as ndi
from shapely.geometry import shape

import config as C
from pipeline.stack import BANDS, out_grid


def _circular_mean(sin_sum, cos_sum):
    a = np.degrees(np.arctan2(sin_sum, cos_sum)) % 360.0
    return np.where((sin_sum == 0) & (cos_sum == 0), np.nan, a)


def region_stats(lab, n, stack_path, slope_path, aspect_path):
    """Kennwerte je Label. Gibt ein dict von Arrays (Index 0 = Label 1)."""
    idx = np.arange(1, n + 1)
    with rasterio.open(stack_path) as s:
        dgm_min = s.read(BANDS.index("dgm_min") + 1)
        dgm_max = s.read(BANDS.index("dgm_max") + 1)
        dgm_mean = s.read(BANDS.index("dgm_mean") + 1)
        ndom = s.read(BANDS.index("ndom_mean") + 1)
        sl05_std = s.read(BANDS.index("slope05_std") + 1)
    with rasterio.open(slope_path) as s:
        sl2 = s.read(1)
    with rasterio.open(aspect_path) as s:
        asp = s.read(1)

    def _f(a):
        return np.nan_to_num(a, nan=0.0)

    counts = ndi.sum(np.ones_like(lab, dtype="float32"), lab, idx)
    cell = C.COARSE_RES ** 2

    # min/max ueber die 0,5-m-Extremwerte -> exakte vertikale Erstreckung
    zmin = ndi.minimum(np.where(np.isfinite(dgm_min), dgm_min, np.inf), lab, idx)
    zmax = ndi.maximum(np.where(np.isfinite(dgm_max), dgm_max, -np.inf), lab, idx)

    a_rad = np.radians(_f(asp))
    ok_asp = np.isfinite(asp).astype("float32")
    sin_s = ndi.sum(np.sin(a_rad) * ok_asp, lab, idx)
    cos_s = ndi.sum(np.cos(a_rad) * ok_asp, lab, idx)

    return {
        "n_cells": counts,
        "area_m2": counts * cell,
        "z_min": zmin, "z_max": zmax,
        "vert_extent_m": zmax - zmin,
        "elev_m": ndi.mean(_f(dgm_mean), lab, idx),
        "slope_mean_deg": ndi.mean(_f(sl2), lab, idx),
        "slope_max_deg": ndi.maximum(_f(sl2), lab, idx),
        "slope_std_deg": ndi.standard_deviation(_f(sl2), lab, idx),
        "slope05_std_deg": ndi.mean(_f(sl05_std), lab, idx),
        "ndom_mean_m": ndi.mean(_f(ndom), lab, idx),
        "aspect_mean_deg": _circular_mean(sin_s, cos_s),
    }


def vectorize(lab, transform):
    """Ein Polygon je Label (Loecher bleiben erhalten)."""
    geoms = {}
    for geom, val in features.shapes(lab.astype("int32"), mask=lab > 0,
                                     transform=transform, connectivity=8):
        v = int(val)
        g = shape(geom)
        if v in geoms:
            geoms[v] = geoms[v].union(g)
        else:
            geoms[v] = g
    return geoms


def build_geodataframe(lab, n, stack_path, slope_path, aspect_path):
    import geopandas as gpd

    _w, _h, transform = out_grid()
    stats = region_stats(lab, n, stack_path, slope_path, aspect_path)
    geoms = vectorize(lab, transform)

    labels = sorted(geoms)
    rows = []
    for lb in labels:
        i = lb - 1
        g = geoms[lb]
        rows.append({
            "id": lb,
            "geometry": g,
            "area_m2": float(stats["area_m2"][i]),
            "n_cells": int(stats["n_cells"][i]),
            "vert_extent_m": float(stats["vert_extent_m"][i]),
            "z_min_m": float(stats["z_min"][i]),
            "z_max_m": float(stats["z_max"][i]),
            "elev_m": float(stats["elev_m"][i]),
            "slope_mean_deg": float(stats["slope_mean_deg"][i]),
            "slope_max_deg": float(stats["slope_max_deg"][i]),
            "slope_std_deg": float(stats["slope_std_deg"][i]),
            "slope05_std_deg": float(stats["slope05_std_deg"][i]),
            "ndom_mean_m": float(stats["ndom_mean_m"][i]),
            "aspect_mean_deg": float(stats["aspect_mean_deg"][i]),
        })
    gdf = gpd.GeoDataFrame(rows, geometry="geometry", crs=C.CRS)
    gdf["centroid_e"] = gdf.geometry.centroid.x
    gdf["centroid_n"] = gdf.geometry.centroid.y
    return gdf


def apply_filters(gdf):
    keep = (gdf["vert_extent_m"] >= C.MIN_VERTICAL_EXTENT_M) & \
           (gdf["area_m2"] >= C.MIN_AREA_M2)
    return gdf[keep].reset_index(drop=True)
