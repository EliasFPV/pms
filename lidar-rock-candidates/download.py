#!/usr/bin/env python3
"""Schritt 1: Kacheln herunterladen (idempotent, mit Groessenabschaetzung)."""
import sys

import config as C
from pipeline.aoi import aoi_bbox, tiles_touching_aoi
from pipeline.wcs import download_all

if __name__ == "__main__":
    minx, miny, maxx, maxy = aoi_bbox()
    tl = tiles_touching_aoi()
    cells = ((maxx - minx) / C.NATIVE_RES) * ((maxy - miny) / C.NATIVE_RES)
    print(f"AOI  : {minx:.2f} {miny:.2f} .. {maxx:.2f} {maxy:.2f}  ({C.CRS})")
    print(f"Groesse: {maxx-minx:.0f} x {maxy-miny:.0f} m, Puffer {C.AOI_BUFFER_M:.0f} m")
    print(f"Kacheln: {len(tl)} a {C.TILE_SIZE_M} m")
    print(f"Zellen : {cells/1e6:.0f} Mio je Band ({cells*4/1e9:.2f} GB float32)")
    print(f"Erwartet: bis {len(tl)*2*16.8/1024:.2f} GB roh, komprimiert deutlich weniger")
    if "--yes" not in sys.argv:
        print("\n(Start mit --yes)")
        raise SystemExit(0)
    download_all(reverse="--reverse" in sys.argv)
