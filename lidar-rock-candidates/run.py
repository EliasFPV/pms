#!/usr/bin/env python3
"""Gesamtlauf ueber beide Aufloesungsprofile.

  hires : 0,5 m nativ / 2 m Analyse  - wie spezifiziert, deckt aber nur die
          Korridore ab, fuer die die Provinz 0,5-m-Daten fliegt
  wide  : 2,5 m nativ / 5 m Analyse  - flaechendeckend, deutlich grober

Beide Ergebnisse werden getrennt gefuehrt (Attribut "profil"), der zweite
Lauf ersetzt den ersten nicht.

Voraussetzung: download.py ist fuer beide Profile gelaufen.
"""
import math
import sys

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


def run_profile(name):
    """Rasterkette fuer ein Profil. Gibt (gdf_ohne_kontext, stats, rasterpfade)."""
    p = C.set_profile(name)
    tag = p["tag"]
    log(f"Profil {name}: {p['label']}")
    C.DERIVED_DIR.mkdir(parents=True, exist_ok=True)

    dgm_dir, dom_dir = C.TILE_DIR / "dgm", C.TILE_DIR / "dom"
    dgm_tiles = sorted(dgm_dir.glob("*.tif"))
    dom_tiles = sorted(dom_dir.glob("*.tif"))
    names = {q.stem for q in dgm_tiles} & {q.stem for q in dom_tiles}
    dgm_tiles = [q for q in dgm_tiles if q.stem in names]
    dom_tiles = [q for q in dom_tiles if q.stem in names]
    if not dgm_tiles:
        log(f"  Profil {name}: keine Kacheln - uebersprungen")
        return None, None, None
    log(f"  Kacheln mit DGM+DOM: {len(dgm_tiles)}")

    dgm_vrt = build_vrt(dgm_tiles, C.DERIVED_DIR / f"dgm_{tag}.vrt")
    dom_vrt = build_vrt(dom_tiles, C.DERIVED_DIR / f"dom_{tag}.vrt")

    stack = C.DERIVED_DIR / "stack.tif"
    if not stack.exists():
        log("  Analysestack (windowed)")
        build_stack(dgm_vrt, dom_vrt, stack)

    slope_p = C.DERIVED_DIR / "slope.tif"
    aspect_p = C.DERIVED_DIR / "aspect.tif"
    if not (slope_p.exists() and aspect_p.exists()):
        log("  Neigung / Exposition")
        M.derive_slope_aspect(stack, slope_p, aspect_p)

    ndom_p = C.DERIVED_DIR / "ndom.tif"
    hs_p = C.DERIVED_DIR / "hillshade.tif"
    if not ndom_p.exists():
        export_band(stack, "ndom_mean", ndom_p)
    if not hs_p.exists():
        log("  Schummerung")
        M.hillshade(stack, hs_p)

    log("  Felsmaske + Morphologie + Labeling")
    mk = M.rock_mask(stack, slope_p)
    with rasterio.open(stack) as s:
        vf = s.read(BANDS.index("valid_frac") + 1)
    coverage_pct = float(np.mean(vf > 0.5) * 100.0)
    lab, n = M.clean_and_label(mk)
    log(f"  Maskenzellen {int(mk.sum())}, Flaechen {n}, Abdeckung {coverage_pct:.1f} %")

    stats = {
        "tag": tag, "label": p["label"], "profile": name,
        "native": p["native"], "coarse": p["coarse"],
        "cov_dgm": p["cov_dgm"], "cov_dom": p["cov_dom"],
        "tiles_with_data": len(dgm_tiles),
        "tiles_total": len(tiles_touching_aoi()),
        "coverage_pct": coverage_pct,
        "mask_cells": int(mk.sum()), "n_raw": 0, "n_final": 0,
        "bbox": aoi_bbox(),
    }
    rasters = {
        "hillshade": f"../data/derived/{tag}/hillshade.tif",
        "ndom": f"../data/derived/{tag}/ndom.tif",
        "slope": f"../data/derived/{tag}/slope.tif",
    }
    if n == 0:
        return None, stats, rasters

    log("  Kennwerte je Flaeche")
    gdf = P.build_geodataframe(lab, n, stack, slope_p, aspect_p)
    stats["n_raw"] = len(gdf)
    gdf = P.apply_filters(gdf)
    stats["n_final"] = len(gdf)
    log(f"  nach Filter: {len(gdf)} von {stats['n_raw']}")
    return (gdf if len(gdf) else None), stats, rasters


def main():
    only = None
    if "--profile" in sys.argv:
        only = sys.argv[sys.argv.index("--profile") + 1]
    profiles = [only] if only else ["hires", "wide"]

    C.OUT_DIR.mkdir(parents=True, exist_ok=True)
    results = {}
    for name in profiles:
        gdf, stats, rasters = run_profile(name)
        if stats:
            results[name] = {"gdf": gdf, "stats": stats, "rasters": rasters}

    if not results:
        sys.exit("Kein Profil lieferte Daten.")

    log("Kontext: Geologie / Naturparke / OSM-Wege")
    C.set_profile(profiles[0])
    geo = ctx.fetch_geology()
    parks = ctx.fetch_parks()
    ways = ctx.fetch_osm_ways()
    log(f"  Geologie {len(geo)}, Parke {len(parks)}, Wege {len(ways)}")

    gdfs = {}
    for name, r in results.items():
        g = r["gdf"]
        if g is None:
            continue
        g = ctx.join_geology(g, geo)
        g = ctx.flag_protected(g, parks)
        g = ctx.distance_to_ways(g, ways)
        g = S.add_score(g)
        gdfs[r["stats"]["tag"]] = g
        st = r["stats"]
        st["n_protected"] = int(g["im_schutzgebiet"].sum())
        st["n_geo_good"] = int((g["geologie_klasse"] == "gut").sum())
        st["n_geo_bad"] = int((g["geologie_klasse"] == "unbrauchbar_vermutet").sum())
        st["n_geo_mixed"] = int(
            (g["geologie_klasse"] == "gemischt_karbonat_werfener").sum())
        st["max_wall_m"] = float(g["vert_extent_m"].max())
        st["top_score"] = float(g["score"].max())

    log("Ausgaben")
    gpkg, csv, per = O.write_vector_outputs(
        gdfs, C.OUT_DIR / "felskandidaten.gpkg", C.OUT_DIR,
        context={"geologie": geo, "schutzgebiete": parks, "wege": ways})
    O.write_qgis_project(
        C.OUT_DIR / "felskandidaten.qgs", "./felskandidaten.gpkg",
        [(r["stats"]["tag"], r["stats"]["label"], r["rasters"]["hillshade"],
          r["rasters"]["ndom"], r["rasters"]["slope"])
         for r in results.values()],
        results[profiles[0]]["stats"]["bbox"])

    cx, cy = center_utm()
    nx, ny = center_utm(C.AOI_CENTER_LATLON_NOMINATIM)
    R.write_readme(C.BASE_DIR / "README.md", {
        "center_offset_m": math.hypot(nx - cx, ny - cy),
        "profiles": [r["stats"] for r in results.values()],
    })
    log(f"fertig: {gpkg.name}, {csv.name}, "
        f"{', '.join(q.name for q in per)}, felskandidaten.qgs, README.md")
    for tag, g in gdfs.items():
        print(f"\n--- Top 10, Profil {tag} ---")
        print(g.head(10)[["id", "score", "vert_extent_m", "area_m2",
                          "slope_mean_deg", "aspect_mean_deg",
                          "geologie_klasse", "dist_weg_m"]].to_string(index=False))


if __name__ == "__main__":
    main()
