"""Stufe 6: Score aus Wandhoehe, Neigungskompaktheit, Geologie, Exposition, Wegnaehe."""
import numpy as np

import config as C

COMPONENTS = ["wall_height", "slope_compact", "geology", "south_aspect", "access"]


def _clip01(a):
    return np.clip(np.nan_to_num(np.asarray(a, dtype="float64"), nan=0.0), 0.0, 1.0)


def score_components(gdf):
    out = {}
    out["wall_height"] = _clip01(
        gdf["vert_extent_m"] / C.WALL_HEIGHT_FULL_SCORE_M)
    out["slope_compact"] = _clip01(
        1.0 - gdf["slope_std_deg"] / C.SLOPE_STD_FULL_PENALTY_DEG)
    out["geology"] = _clip01(gdf["geologie_eignung"])

    asp = np.asarray(gdf["aspect_mean_deg"], dtype="float64")
    south = (1.0 - np.cos(np.radians(asp))) / 2.0     # 1 = Sued, 0 = Nord
    out["south_aspect"] = np.where(np.isfinite(asp), south, 0.5)

    d = np.asarray(gdf["dist_weg_m"], dtype="float64")
    span = max(C.ACCESS_WORST_M - C.ACCESS_BEST_M, 1e-9)
    acc = (C.ACCESS_WORST_M - d) / span
    out["access"] = np.where(np.isfinite(d), np.clip(acc, 0.0, 1.0), 0.0)
    return out


def add_score(gdf):
    comps = score_components(gdf)
    total_w = sum(C.WEIGHTS[k] for k in COMPONENTS)
    out = gdf.copy()
    score = np.zeros(len(gdf), dtype="float64")
    for k in COMPONENTS:
        out[f"score_{k}"] = np.round(comps[k], 4)
        score += C.WEIGHTS[k] * comps[k]
    out["score"] = np.round(score / total_w, 4)
    return out.sort_values("score", ascending=False).reset_index(drop=True)
