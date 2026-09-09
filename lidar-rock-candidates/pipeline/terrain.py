"""Neigung und Exposition nach Horn (3x3), NaN-tolerant."""
import numpy as np


def horn_gradients(z, res):
    """dz/dx, dz/dy fuer das Innere eines (H+2, W+2)-Fensters."""
    a, b, c = z[:-2, :-2], z[:-2, 1:-1], z[:-2, 2:]
    d, _e, f = z[1:-1, :-2], z[1:-1, 1:-1], z[1:-1, 2:]
    g, h, i = z[2:, :-2], z[2:, 1:-1], z[2:, 2:]
    dzdx = ((c + 2 * f + i) - (a + 2 * d + g)) / (8.0 * res)
    dzdy = ((g + 2 * h + i) - (a + 2 * b + c)) / (8.0 * res)
    return dzdx, dzdy


def slope_deg(z, res):
    """Hangneigung [Grad] fuer das Innere eines gepolsterten Fensters."""
    dzdx, dzdy = horn_gradients(z, res)
    return np.degrees(np.arctan(np.hypot(dzdx, dzdy)))


def aspect_deg(z, res):
    """Exposition [Grad, 0=N, im Uhrzeigersinn]; flach -> NaN."""
    dzdx, dzdy = horn_gradients(z, res)
    asp = np.degrees(np.arctan2(dzdy, -dzdx))
    asp = np.where(asp < 0, 90.0 - asp, np.where(asp > 90.0, 450.0 - asp, 90.0 - asp))
    flat = (dzdx == 0) & (dzdy == 0)
    return np.where(flat, np.nan, asp % 360.0)


def block_reduce(arr, factor, how="mean"):
    """Aggregiert ein 2D-Array um ganzzahligen Faktor, NaN-tolerant."""
    h, w = arr.shape
    assert h % factor == 0 and w % factor == 0, (h, w, factor)
    v = arr.reshape(h // factor, factor, w // factor, factor)
    with np.errstate(all="ignore"):
        import warnings
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", RuntimeWarning)
            if how == "mean":
                return np.nanmean(v, axis=(1, 3))
            if how == "min":
                return np.nanmin(v, axis=(1, 3))
            if how == "max":
                return np.nanmax(v, axis=(1, 3))
            if how == "std":
                return np.nanstd(v, axis=(1, 3))
            if how == "count":
                return np.sum(np.isfinite(v), axis=(1, 3))
    raise ValueError(how)
