#!/usr/bin/env python3
"""Kalibriert die Neigungsschwelle des groben Profils gegen das feine.

Dieselbe Gradzahl bedeutet auf 2 m und auf 5 m nicht dasselbe. Statt die
Schwelle fuer das Profil "wide" zu raten, wird sie auf der Flaeche bestimmt,
auf der beide Profile Daten haben: welche 5-m-Schwelle reproduziert die
0,5-m/2-m-Felsmaske am besten?

Aufruf: python3 calibrate.py
"""
import numpy as np
import rasterio
from rasterio.enums import Resampling
from rasterio.warp import reproject

import config as C
from pipeline.stack import BANDS, out_grid

THRESHOLDS = np.arange(30.0, 66.0, 2.5)


def _read(profile):
    C.set_profile(profile)
    w, h, transform = out_grid()
    with rasterio.open(C.DERIVED_DIR / "stack.tif") as s:
        ndom = s.read(BANDS.index("ndom_mean") + 1)
        valid = s.read(BANDS.index("valid_frac") + 1)
    with rasterio.open(C.DERIVED_DIR / "slope.tif") as s:
        sl = s.read(1)
    return dict(slope=sl, ndom=ndom, valid=valid, transform=transform,
                shape=(h, w))


def main():
    hi = _read("hires")
    wi = _read("wide")

    hi_rock = ((hi["slope"] > C.SLOPE_MIN_DEG) & (hi["ndom"] < C.NDOM_MAX_M) &
               (hi["valid"] > 0.5)).astype("float32")
    hi_have = (hi["valid"] > 0.5).astype("float32")

    def to_wide(src):
        dst = np.zeros(wi["shape"], dtype="float32")
        reproject(src, dst,
                  src_transform=hi["transform"], src_crs=C.CRS,
                  dst_transform=wi["transform"], dst_crs=C.CRS,
                  resampling=Resampling.max, src_nodata=None, dst_nodata=None)
        return dst

    target = to_wide(hi_rock) > 0.5
    overlap = (to_wide(hi_have) > 0.5) & (wi["valid"] > 0.5) & np.isfinite(wi["slope"])

    n_ov = int(overlap.sum())
    n_pos = int((target & overlap).sum())
    print(f"Vergleichsflaeche (beide Profile mit Daten): {n_ov:,} Zellen a "
          f"{C.PROFILES['wide']['coarse']:.0f} m")
    print(f"davon im feinen Profil als Fels erkannt   : {n_pos:,} "
          f"({n_pos/max(n_ov,1)*100:.2f} %)")
    if n_pos == 0:
        print("Keine Referenzzellen - Kalibrierung nicht moeglich.")
        return

    print(f"\nReferenz: Profil hires, Neigung(2 m) > {C.SLOPE_MIN_DEG:.0f} Grad "
          f"und nDOM < {C.NDOM_MAX_M} m")
    print(f"{'Schwelle 5 m':>13} {'Treffer':>9} {'Precision':>10} "
          f"{'Recall':>8} {'F1':>7}")
    best = (0.0, None)
    for t in THRESHOLDS:
        pred = (wi["slope"] > t) & (wi["ndom"] < C.NDOM_MAX_M) & overlap
        tp = int((pred & target).sum())
        fp = int((pred & ~target).sum())
        fn = int((~pred & target & overlap).sum())
        prec = tp / (tp + fp) if tp + fp else 0.0
        rec = tp / (tp + fn) if tp + fn else 0.0
        f1 = 2 * prec * rec / (prec + rec) if prec + rec else 0.0
        print(f"{t:>13.1f} {tp:>9,} {prec:>10.3f} {rec:>8.3f} {f1:>7.3f}")
        if f1 > best[0]:
            best = (f1, t)
    if best[1] is not None:
        print(f"\nbestes F1 bei {best[1]:.1f} Grad (F1 = {best[0]:.3f})")
        print(f"aktuell in config.py: SLOPE_MIN_DEG_PROFILE['wide'] = "
              f"{C.SLOPE_MIN_DEG_PROFILE['wide']}")


if __name__ == "__main__":
    main()
