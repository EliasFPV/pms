"""Stufe 3: Neigung/Exposition auf 2 m, Felsmaske, Morphologie, Labeling."""
import numpy as np
import rasterio
from rasterio.windows import Window
from scipy import ndimage as ndi

import config as C
from pipeline.stack import BANDS, out_grid
from pipeline.terrain import aspect_deg, slope_deg


def derive_slope_aspect(stack_path, slope_path, aspect_path, block=1024, verbose=True):
    """Neigung/Exposition aus dgm_mean (2 m), blockweise mit Halo."""
    w, h, transform = out_grid()
    prof = dict(driver="GTiff", width=w, height=h, count=1, dtype="float32",
                crs=C.CRS, transform=transform, nodata=np.nan,
                compress="deflate", predictor=2, tiled=True,
                blockxsize=512, blockysize=512)
    bi = BANDS.index("dgm_mean") + 1
    with rasterio.open(stack_path) as src, \
            rasterio.open(slope_path, "w", **prof) as ds, \
            rasterio.open(aspect_path, "w", **prof) as da:
        for row in range(0, h, block):
            for col in range(0, w, block):
                bh = min(block, h - row)
                bw = min(block, w - col)
                win = Window(col - 1, row - 1, bw + 2, bh + 2)
                z = src.read(bi, window=win, boundless=True,
                             fill_value=np.nan).astype("float32")
                out = Window(col, row, bw, bh)
                ds.write(slope_deg(z, C.COARSE_RES).astype("float32"), 1, window=out)
                da.write(aspect_deg(z, C.COARSE_RES).astype("float32"), 1, window=out)
            if verbose:
                print(f"  slope/aspect: Zeile {row//block+1}", flush=True)
    return slope_path, aspect_path


def rock_mask(stack_path, slope_path):
    """Boolesche Felsmaske nach den Schwellen aus config.py."""
    with rasterio.open(stack_path) as s:
        ndom = s.read(BANDS.index("ndom_mean") + 1)
        valid = s.read(BANDS.index("valid_frac") + 1)
        sl05 = s.read(BANDS.index("slope05_mean") + 1)
    with rasterio.open(slope_path) as s:
        sl2 = s.read(1)

    m = (sl2 > C.SLOPE_MIN_DEG) & (ndom < C.NDOM_MAX_M)
    m &= np.isfinite(sl2) & np.isfinite(ndom) & (valid > 0.5)
    if C.REQUIRE_BOTH_SCALES:
        m &= sl05 > C.SLOPE_MIN_DEG_NATIVE
    return np.nan_to_num(m, nan=False).astype(bool)


def clean_and_label(mask):
    """Opening/Closing gegen Salt-and-Pepper, dann zusammenhaengende Flaechen."""
    st = ndi.generate_binary_structure(2, 2)      # 8er-Nachbarschaft
    m = mask
    if C.OPENING_ITER:
        m = ndi.binary_opening(m, structure=st, iterations=C.OPENING_ITER)
    if C.CLOSING_ITER:
        m = ndi.binary_closing(m, structure=st, iterations=C.CLOSING_ITER)
    lab, n = ndi.label(m, structure=st)
    if C.MIN_PIXELS > 1 and n:
        counts = np.bincount(lab.ravel())
        too_small = np.where(counts < C.MIN_PIXELS)[0]
        if too_small.size:
            lab[np.isin(lab, too_small[too_small > 0])] = 0
            # Labels neu durchnummerieren
            uniq = np.unique(lab)
            remap = np.zeros(lab.max() + 1, dtype="int32")
            remap[uniq] = np.arange(uniq.size)
            lab = remap[lab]
            n = int(lab.max())
    return lab, n


def hillshade(stack_path, out_path, azimuth=315.0, altitude=45.0,
              z_factor=1.0, block=1024):
    """Schummerung aus dgm_mean fuer die QGIS-Darstellung."""
    from pipeline.terrain import horn_gradients
    w, h, transform = out_grid()
    prof = dict(driver="GTiff", width=w, height=h, count=1, dtype="float32",
                crs=C.CRS, transform=transform, nodata=np.nan,
                compress="deflate", predictor=2, tiled=True,
                blockxsize=512, blockysize=512)
    az = np.radians(360.0 - azimuth + 90.0)
    ze = np.radians(90.0 - altitude)
    bi = BANDS.index("dgm_mean") + 1
    with rasterio.open(stack_path) as src, rasterio.open(out_path, "w", **prof) as dst:
        for row in range(0, h, block):
            for col in range(0, w, block):
                bh, bw = min(block, h - row), min(block, w - col)
                z = src.read(bi, window=Window(col - 1, row - 1, bw + 2, bh + 2),
                             boundless=True, fill_value=np.nan).astype("float32")
                dzdx, dzdy = horn_gradients(z, C.COARSE_RES)
                dzdx, dzdy = dzdx * z_factor, dzdy * z_factor
                slope = np.arctan(np.hypot(dzdx, dzdy))
                aspect = np.arctan2(dzdy, -dzdx)
                hs = (np.cos(ze) * np.cos(slope) +
                      np.sin(ze) * np.sin(slope) * np.cos(az - aspect))
                dst.write((np.clip(hs, 0, 1) * 255).astype("float32"), 1,
                          window=Window(col, row, bw, bh))
    return out_path
