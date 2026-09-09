#!/usr/bin/env python3
"""Schritt 1: Kacheln herunterladen (idempotent, mit Groessenabschaetzung).

Aufruf: python3 download.py --yes [--profile hires|wide] [--reverse]
"""
import sys

import config as C
from pipeline.aoi import aoi_bbox, tiles_touching_aoi
from pipeline.wcs import download_all

if __name__ == "__main__":
    prof = "hires"
    if "--profile" in sys.argv:
        prof = sys.argv[sys.argv.index("--profile") + 1]
    p = C.set_profile(prof)
    print(f"Profil : {prof} ({p['label']})")
    print(f"DGM    : {C.COV_DGM}")
    print(f"DOM    : {C.COV_DOM}")
    minx, miny, maxx, maxy = aoi_bbox()
    tl = tiles_touching_aoi()
    cells = ((maxx - minx) / C.NATIVE_RES) * ((maxy - miny) / C.NATIVE_RES)
    print(f"AOI  : {minx:.2f} {miny:.2f} .. {maxx:.2f} {maxy:.2f}  ({C.CRS})")
    print(f"Groesse: {maxx-minx:.0f} x {maxy-miny:.0f} m, Puffer {C.AOI_BUFFER_M:.0f} m")
    print(f"Kacheln: {len(tl)} a {C.TILE_SIZE_M} m")
    print(f"Zellen : {cells/1e6:.0f} Mio je Band ({cells*4/1e9:.2f} GB float32)")
    per = (C.TILE_SIZE_M / C.NATIVE_RES) ** 2 * 4 / 1e6
    print(f"Erwartet: {len(tl)*2*per/1000:.2f} GB roh "
          f"({per:.1f} MB je Kachel/Band), komprimiert deutlich weniger")
    if "--yes" not in sys.argv:
        print("\n(Start mit --yes)")
        raise SystemExit(0)
    download_all(reverse="--reverse" in sys.argv)
