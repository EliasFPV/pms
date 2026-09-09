#!/usr/bin/env python3
"""Schwellenwert-Sensitivitaet: wie viele Kandidaten liefert welche Kombination.

Nutzt den bereits gebauten 2-m-Stack, laedt nichts nach.
Aufruf: python3 sensitivity.py
"""
import numpy as np
import rasterio
from scipy import ndimage as ndi

import config as C
from pipeline.stack import BANDS

SLOPES = [50, 55, 60, 65, 70]
NDOMS = [1.0, 1.5, 2.5, 5.0]


def main():
    stack = C.DERIVED_DIR / "stack_2m.tif"
    with rasterio.open(stack) as s:
        ndom = s.read(BANDS.index("ndom_mean") + 1)
        valid = s.read(BANDS.index("valid_frac") + 1)
        dmin = s.read(BANDS.index("dgm_min") + 1)
        dmax = s.read(BANDS.index("dgm_max") + 1)
    with rasterio.open(C.DERIVED_DIR / "slope_2m.tif") as s:
        sl = s.read(1)

    base = np.isfinite(sl) & np.isfinite(ndom) & (valid > 0.5)
    st = ndi.generate_binary_structure(2, 2)
    cell = C.COARSE_RES ** 2

    print(f"Filter: vert >= {C.MIN_VERTICAL_EXTENT_M} m, Flaeche >= {C.MIN_AREA_M2} m2")
    print(f"{'Neigung':>8} {'nDOM':>6} {'Zellen':>9} {'Flaechen':>9} "
          f"{'Kandidaten':>11} {'max Wand [m]':>13}")
    for sd in SLOPES:
        for nd in NDOMS:
            m = base & (sl > sd) & (ndom < nd)
            m = ndi.binary_opening(m, structure=st, iterations=C.OPENING_ITER)
            m = ndi.binary_closing(m, structure=st, iterations=C.CLOSING_ITER)
            lab, n = ndi.label(m, structure=st)
            if n == 0:
                print(f"{sd:>8} {nd:>6} {int(m.sum()):>9} {0:>9} {0:>11} {'-':>13}")
                continue
            idx = np.arange(1, n + 1)
            cnt = np.bincount(lab.ravel())[1:]
            zmin = ndi.minimum(np.where(np.isfinite(dmin), dmin, np.inf), lab, idx)
            zmax = ndi.maximum(np.where(np.isfinite(dmax), dmax, -np.inf), lab, idx)
            vert = zmax - zmin
            keep = (vert >= C.MIN_VERTICAL_EXTENT_M) & (cnt * cell >= C.MIN_AREA_M2)
            mx = f"{vert[keep].max():.1f}" if keep.any() else "-"
            print(f"{sd:>8} {nd:>6} {int(m.sum()):>9} {n:>9} {int(keep.sum()):>11} {mx:>13}")


if __name__ == "__main__":
    main()
