"""Gekachelter, gecachter Download der WCS-Coverages."""
import os
import time
from pathlib import Path

import numpy as np
import rasterio
import requests

import config as C
from pipeline.aoi import tile_name, tiles_touching_aoi


def _get(url, params, dest, timeout=C.HTTP_TIMEOUT):
    last = None
    for attempt in range(C.HTTP_RETRIES):
        try:
            r = requests.get(url, params=params, timeout=timeout, stream=True)
            if r.status_code != 200:
                raise RuntimeError(f"HTTP {r.status_code}: {r.text[:200]}")
            tmp = Path(f"{dest}.{os.getpid()}.part")
            with open(tmp, "wb") as fh:
                for chunk in r.iter_content(1 << 20):
                    fh.write(chunk)
            tmp.replace(dest)
            return dest
        except Exception as e:      # noqa: BLE001 - Netzfehler sind erwartbar
            last = e
            if attempt < C.HTTP_RETRIES - 1:
                time.sleep(2 ** (attempt + 1))
    raise RuntimeError(f"Download fehlgeschlagen nach {C.HTTP_RETRIES} Versuchen: {last}")


def coverage_params(coverage_id, bbox):
    e0, n0, e1, n1 = bbox
    return {
        "service": "WCS", "version": C.WCS_VERSION, "request": "GetCoverage",
        "coverageId": coverage_id, "format": "image/tiff",
        "subset": [f"E({e0},{e1})", f"N({n0},{n1})"],
    }


def _recompress(path):
    """Unkomprimiertes WCS-TIFF durch ein DEFLATE-TIFF ersetzen."""
    with rasterio.open(path) as s:
        prof = s.profile.copy()
        data = s.read(1)
    prof.update(compress="deflate", predictor=2, tiled=True,
                blockxsize=512, blockysize=512, nodata=C.NODATA)
    tmp = Path(f"{path}.{os.getpid()}.z")
    with rasterio.open(tmp, "w", **prof) as d:
        d.write(data, 1)
    tmp.replace(path)
    return data


def download_pair(tile, dgm_dir, dom_dir, verbose=True):
    """Laedt DGM- und DOM-Kachel. Gibt (dgm_path|None, dom_path|None) zurueck.

    Leere DGM-Kacheln bekommen einen .empty-Marker; die zugehoerige
    DOM-Kachel wird dann gar nicht erst angefragt. Vorhandene Dateien und
    Marker werden uebersprungen (idempotenter Wiederanlauf).
    """
    name = tile_name(tile)
    dgm_p = dgm_dir / f"{name}.tif"
    dom_p = dom_dir / f"{name}.tif"
    marker = dgm_dir / f"{name}.empty"

    if marker.exists():
        return None, None
    if dgm_p.exists() and dom_p.exists():
        return dgm_p, dom_p

    if not dgm_p.exists():
        _get(C.WCS_URL, coverage_params(C.COV_DGM, tile), dgm_p)
        data = _recompress(dgm_p)
        if not np.any(data > C.NODATA + 1):
            dgm_p.unlink()
            marker.write_text("keine DGM-Daten in dieser Kachel\n")
            if verbose:
                print(f"  {name}: leer -> uebersprungen", flush=True)
            return None, None

    if not dom_p.exists():
        _get(C.WCS_URL, coverage_params(C.COV_DOM, tile), dom_p)
        _recompress(dom_p)
    return dgm_p, dom_p


def download_all(verbose=True, reverse=False):
    dgm_dir = C.TILE_DIR / "dgm"
    dom_dir = C.TILE_DIR / "dom"
    dgm_dir.mkdir(parents=True, exist_ok=True)
    dom_dir.mkdir(parents=True, exist_ok=True)

    tl = tiles_touching_aoi()
    if reverse:
        tl = tl[::-1]
    got = []
    for i, t in enumerate(tl, 1):
        if verbose:
            print(f"[{i}/{len(tl)}] {tile_name(t)}", flush=True)
        d, s = download_pair(t, dgm_dir, dom_dir, verbose=verbose)
        if d and s:
            got.append((d, s))
    if verbose:
        print(f"fertig: {len(got)} Kacheln mit Daten von {len(tl)}", flush=True)
    return got
