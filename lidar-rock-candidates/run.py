#!/usr/bin/env python3
"""Gesamtlauf: Mosaik -> Stack -> Maske -> Polygone -> Kontext -> Score -> Output.

Voraussetzung: `python3 download.py --yes` ist gelaufen.
"""
import math
import sys
from pathlib import Path

import numpy as np
import rasterio

import config as C
from pipeline import context as ctx
from pipeline import mask as M
from pipeline import outputs as O
from pipeline import polygons as P
from pipeline import report as R
from pipeline import scoring as S
from pipeline.aoi import aoi_bbox, build_vrt, center_utm, tiles_touching_aoi
from pipeline.stack import BANDS, build_stack, out_grid


def log(msg):
    print(f"[{msg}]", flush=True)


def export_band(stack_path, band, out_path):
    _w, _h, transform = out_grid()
    with rasterio.open(stack_path) as s:
        a = s.read(BANDS.index(band) + 1)
        prof = dict(driver="GTiff", width=s.width, height=s.height, count=1,
                    dtype="float32", crs=C.CRS, transform=transform,
                    nodata=np.nan, compress="deflate", predictor=2,
                    tiled=True, blockxsize=512, blockysize=512)
    with rasterio.open(out_path, "w", **prof) as d:
        d.write(a.astype("float32"), 1)
    return out_path


def main():
    for d in (C.DERIVED_DIR, C.CONTEXT_DIR, C.OUT_DIR, C.BASE_DIR / "docs"):
        d.mkdir(parents=True, exist_ok=True)

    dgm_dir, dom_dir = C.TILE_DIR / "dgm", C.TILE_DIR / "dom"
    dgm_tiles = sorted(dgm_dir.glob("*.tif"))
    dom_tiles = sorted(dom_dir.glob("*.tif"))
    if not dgm_tiles:
        sys.exit("Keine Kacheln vorhanden - zuerst download.py laufen lassen.")
    # nur Paare verwenden
    names = {p.stem for p in dgm_tiles} & {p.stem for p in dom_tiles}
    dgm_tiles = [p for p in dgm_tiles if p.stem in names]
    dom_tiles = [p for p in dom_tiles if p.stem in names]
    log(f"Kacheln mit DGM+DOM: {len(dgm_tiles)}")

    log("Mosaik (VRT)")
    dgm_vrt = build_vrt(dgm_tiles, C.DERIVED_DIR / "dgm_05m.vrt")
    dom_vrt = build_vrt(dom_tiles, C.DERIVED_DIR / "dom_05m.vrt")

    stack = C.DERIVED_DIR / "stack_2m.tif"
    if not stack.exists():
        log("Analysestack 2 m (windowed)")
        build_stack(dgm_vrt, dom_vrt, stack)

    slope_p = C.DERIVED_DIR / "slope_2m.tif"
    aspect_p = C.DERIVED_DIR / "aspect_2m.tif"
    if not (slope_p.exists() and aspect_p.exists()):
        log("Neigung / Exposition 2 m")
        M.derive_slope_aspect(stack, slope_p, aspect_p)

    ndom_p = C.DERIVED_DIR / "ndom_2m.tif"
    hs_p = C.DERIVED_DIR / "hillshade_2m.tif"
    if not ndom_p.exists():
        export_band(stack, "ndom_mean", ndom_p)
    if not hs_p.exists():
        log("Schummerung")
        M.hillshade(stack, hs_p)

    log("Felsmaske + Morphologie + Labeling")
    mk = M.rock_mask(stack, slope_p)
    with rasterio.open(stack) as s:
        vf = s.read(BANDS.index("valid_frac") + 1)
    coverage_pct = float(np.nanmean(vf > 0.5) * 100.0)
    lab, n = M.clean_and_label(mk)
    log(f"Maskenzellen {int(mk.sum())}, Flaechen nach Bereinigung {n}")
    if n == 0:
        sys.exit("Keine Flaechen gefunden - Schwellen pruefen.")

    log("Kennwerte je Flaeche")
    gdf = P.build_geodataframe(lab, n, stack, slope_p, aspect_p)
    n_raw = len(gdf)
    gdf = P.apply_filters(gdf)
    log(f"nach Filter: {len(gdf)} von {n_raw}")
    if not len(gdf):
        sys.exit("Alle Flaechen weggefiltert - Filter pruefen.")

    log("Kontext: Geologie")
    geo = ctx.fetch_geology()
    gdf = ctx.join_geology(gdf, geo)
    log("Kontext: Naturparke")
    parks = ctx.fetch_parks()
    gdf = ctx.flag_protected(gdf, parks)
    log("Kontext: OSM-Wege")
    ways = ctx.fetch_osm_ways()
    gdf = ctx.distance_to_ways(gdf, ways)

    log("Score")
    gdf = S.add_score(gdf)

    log("Ausgaben")
    gpkg, csv = O.write_vector_outputs(
        gdf, C.OUT_DIR / "felskandidaten.gpkg", C.OUT_DIR / "felskandidaten.csv",
        context={"geologie": geo, "schutzgebiete": parks, "wege": ways})
    O.write_qgis_project(
        C.OUT_DIR / "felskandidaten.qgs",
        "./felskandidaten.gpkg",
        "../data/derived/hillshade_2m.tif",
        "../data/derived/ndom_2m.tif",
        "../data/derived/slope_2m.tif",
        aoi_bbox())

    cx, cy = center_utm()
    nx, ny = center_utm(C.AOI_CENTER_LATLON_NOMINATIM)
    R.write_readme(C.BASE_DIR / "README.md", {
        "bbox": aoi_bbox(),
        "center_offset_m": math.hypot(nx - cx, ny - cy),
        "tiles_with_data": len(dgm_tiles),
        "tiles_total": len(tiles_touching_aoi()),
        "coverage_pct": coverage_pct,
        "n_raw": n_raw,
        "n_final": len(gdf),
        "n_protected": int(gdf["im_schutzgebiet"].sum()),
        "n_geo_good": int((gdf["geologie_klasse"] == "gut").sum()),
        "n_geo_bad": int((gdf["geologie_klasse"] == "unbrauchbar_vermutet").sum()),
    })
    log(f"fertig: {gpkg.name}, {csv.name}, felskandidaten.qgs, README.md")
    print(gdf.head(10)[["id", "score", "vert_extent_m", "area_m2",
                        "slope_mean_deg", "geologie_klasse", "dist_weg_m"]]
          .to_string(index=False))


if __name__ == "__main__":
    main()
