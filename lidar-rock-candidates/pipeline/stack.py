"""Stufe 2: 0,5-m-Mosaike blockweise zu einem 2-m-Analysestack verdichten.

Der Stack traegt alles, was fuer Maske, Statistik und Score gebraucht wird,
und ist mit 5000x5000 Zellen handhabbar - die 0,5-m-Daten selbst werden nie
vollstaendig in den Speicher geladen.
"""
import numpy as np
import rasterio
from rasterio.transform import from_origin
from rasterio.windows import Window

import config as C
from pipeline.aoi import aoi_bbox
from pipeline.terrain import block_reduce, slope_deg

BANDS = ["dgm_mean", "dgm_min", "dgm_max", "ndom_mean",
         "slope05_mean", "slope05_std", "valid_frac"]


def out_grid():
    minx, miny, maxx, maxy = aoi_bbox()
    w = int(round((maxx - minx) / C.COARSE_RES))
    h = int(round((maxy - miny) / C.COARSE_RES))
    return w, h, from_origin(minx, maxy, C.COARSE_RES, C.COARSE_RES)


def _read_padded(src, col0, row0, w, h, pad):
    """Boundless-Read mit Rand; liefert float32 mit NaN statt NoData."""
    win = Window(col0 - pad, row0 - pad, w + 2 * pad, h + 2 * pad)
    a = src.read(1, window=win, boundless=True, fill_value=C.NODATA).astype("float32")
    return np.where(a <= C.NODATA + 1, np.nan, a)


def build_stack(dgm_vrt, dom_vrt, out_path, block=500, verbose=True):
    """block = Kantenlaenge im 2-m-Zielraster (500 -> 1000 m Fenster)."""
    w, h, transform = out_grid()
    minx, _miny, _maxx, maxy = aoi_bbox()

    prof = dict(driver="GTiff", width=w, height=h, count=len(BANDS),
                dtype="float32", crs=C.CRS, transform=transform,
                nodata=np.nan, compress="deflate", predictor=2,
                tiled=True, blockxsize=512, blockysize=512)

    with rasterio.open(dgm_vrt) as sd, rasterio.open(dom_vrt) as ss, \
            rasterio.open(out_path, "w", **prof) as dst:
        for i, b in enumerate(BANDS, 1):
            dst.set_band_description(i, b)

        # Offset des VRT-Ursprungs gegenueber dem AOI-Ursprung, in 0,5-m-Zellen
        off_col = int(round((minx - sd.transform.c) / C.NATIVE_RES))
        off_row = int(round((sd.transform.f - maxy) / C.NATIVE_RES))

        nby = (h + block - 1) // block
        nbx = (w + block - 1) // block
        for by in range(nby):
            for bx in range(nbx):
                oh = min(block, h - by * block)
                ow = min(block, w - bx * block)
                # zugehoeriger Bereich im 0,5-m-Raster
                src_col = off_col + bx * block * C.AGG
                src_row = off_row + by * block * C.AGG
                sw, sh = ow * C.AGG, oh * C.AGG

                dgm = _read_padded(sd, src_col, src_row, sw, sh, 1)
                dom = _read_padded(ss, src_col, src_row, sw, sh, 1)

                sl05 = slope_deg(dgm, C.NATIVE_RES)      # (sh, sw)
                core = dgm[1:-1, 1:-1]
                ndom = dom[1:-1, 1:-1] - core

                out = np.stack([
                    block_reduce(core, C.AGG, "mean"),
                    block_reduce(core, C.AGG, "min"),
                    block_reduce(core, C.AGG, "max"),
                    block_reduce(ndom, C.AGG, "mean"),
                    block_reduce(sl05, C.AGG, "mean"),
                    block_reduce(sl05, C.AGG, "std"),
                    block_reduce(core, C.AGG, "count") / (C.AGG * C.AGG),
                ]).astype("float32")

                dst.write(out, window=Window(bx * block, by * block, ow, oh),
                          indexes=list(range(1, len(BANDS) + 1)))
            if verbose:
                print(f"  stack: Zeile {by+1}/{nby}", flush=True)
    return out_path
