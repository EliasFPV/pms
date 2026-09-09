#!/usr/bin/env python3
"""Exportiert die Kandidaten in handytaugliche Formate.

  .gpx      Wegpunkte + Umrisse, laeuft in jeder Wander-/Outdoor-App mit GPS
            (OsmAnd, Locus, Alpenvereinaktiv, Gaia, Organic Maps ...)
  .kmz      farbige Polygone fuer Google Earth (Android/iOS)
  .geojson  fuer QField und andere GIS-Apps

Alles in WGS84 (EPSG:4326). Aufruf: python3 export_mobile.py
"""
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape

import geopandas as gpd

import config as C

GPKG = C.OUT_DIR / "felskandidaten.gpkg"
LAYERS = [("felskandidaten_05m", "0,5 m (fein)"), ("felskandidaten_25m", "2,5 m (grob)")]


def _desc(r):
    """Kurzbeschreibung, die auf einem Handydisplay noch lesbar ist."""
    bits = [
        f"Score {r['score']:.2f}",
        f"Wand {r['vert_extent_m']:.0f} m",
        f"Flaeche {r['area_m2']:.0f} m2",
        f"Neigung {r['slope_mean_deg']:.0f}/{r['slope_max_deg']:.0f} Grad",
        f"Exposition {r['aspect_mean_deg']:.0f} Grad",
        f"Hoehe {r['elev_m']:.0f} m",
        f"Weg {r['dist_weg_m']:.0f} m",
        f"Geologie: {r['geologie_klasse']}",
    ]
    if r.get("im_schutzgebiet"):
        bits.append(f"ACHTUNG Schutzgebiet: {r.get('schutzgebiet')}")
    return " | ".join(bits)


def _load():
    out = []
    for layer, label in LAYERS:
        try:
            g = gpd.read_file(GPKG, layer=layer).to_crs(4326)
        except Exception:
            continue
        if len(g):
            out.append((layer, label, g.sort_values("score", ascending=False)))
    return out


def write_gpx(path, sets):
    """Wegpunkt je Kandidat, dazu der Umriss als Track."""
    p = ['<?xml version="1.0" encoding="UTF-8"?>',
         '<gpx version="1.1" creator="lidar-rock-candidates" '
         'xmlns="http://www.topografix.com/GPX/1/1">',
         "  <metadata><name>Felskandidaten Gadertal</name></metadata>"]
    for _layer, label, g in sets:
        for _, r in g.iterrows():
            c = r.geometry.centroid
            name = f"{r['id']:03d} {label[:5]} S{r['score']:.2f} {r['vert_extent_m']:.0f}m"
            p += [f'  <wpt lat="{c.y:.6f}" lon="{c.x:.6f}">',
                  f"    <ele>{r['elev_m']:.0f}</ele>",
                  f"    <name>{escape(name)}</name>",
                  f"    <desc>{escape(_desc(r))}</desc>",
                  "    <sym>Summit</sym>", "  </wpt>"]
    for _layer, label, g in sets:
        for _, r in g.iterrows():
            geoms = (r.geometry.geoms if r.geometry.geom_type == "MultiPolygon"
                     else [r.geometry])
            pts = []
            for gm in geoms:
                pts += list(gm.exterior.coords)
            if not pts:
                continue
            trk_name = escape("Umriss {:03d} {}".format(r["id"], label[:5]))
            p += [f"  <trk><name>{trk_name}</name><trkseg>"]
            p += [f'    <trkpt lat="{y:.6f}" lon="{x:.6f}"/>' for x, y in pts]
            p += ["  </trkseg></trk>"]
    p.append("</gpx>")
    Path(path).write_text("\n".join(p), encoding="utf-8")
    return path


def _kml_color(score):
    """KML-Farbe aabbggrr: gelb (niedrig) -> rot (hoch)."""
    g = int(255 * (1.0 - min(max(score, 0.0), 1.0)))
    return f"a0 00 {g:02x} ff".replace(" ", "")


def write_kmz(path, sets):
    p = ['<?xml version="1.0" encoding="UTF-8"?>',
         '<kml xmlns="http://www.opengis.net/kml/2.2"><Document>',
         "<name>Felskandidaten Gadertal</name>"]
    styles = set()
    for _layer, label, g in sets:
        for _, r in g.iterrows():
            sid = f"s{int(round(r['score']*20))}"
            if sid not in styles:
                styles.add(sid)
                p += [f'<Style id="{sid}"><LineStyle><color>ff0000ff</color>'
                      f"<width>2</width></LineStyle>"
                      f"<PolyStyle><color>{_kml_color(r['score'])}</color>"
                      f"</PolyStyle></Style>"]
    for _layer, label, g in sets:
        p += [f"<Folder><name>{escape(label)} ({len(g)})</name>"]
        for _, r in g.iterrows():
            sid = f"s{int(round(r['score']*20))}"
            geoms = (r.geometry.geoms if r.geometry.geom_type == "MultiPolygon"
                     else [r.geometry])
            rings = "".join(
                "<outerBoundaryIs><LinearRing><coordinates>"
                + " ".join(f"{x:.6f},{y:.6f},0" for x, y in gm.exterior.coords)
                + "</coordinates></LinearRing></outerBoundaryIs>" for gm in geoms)
            p += [f"<Placemark><name>{r['id']:03d} S{r['score']:.2f} "
                  f"{r['vert_extent_m']:.0f}m</name>",
                  f"<description>{escape(_desc(r))}</description>",
                  f"<styleUrl>#{sid}</styleUrl>",
                  f"<Polygon><altitudeMode>clampToGround</altitudeMode>{rings}</Polygon>",
                  "</Placemark>"]
        p += ["</Folder>"]
    p += ["</Document></kml>"]
    kml = "\n".join(p)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("doc.kml", kml)
    return path


def main():
    sets = _load()
    if not sets:
        raise SystemExit("Keine Kandidaten gefunden - zuerst run.py laufen lassen.")
    C.OUT_DIR.mkdir(parents=True, exist_ok=True)
    gpx = write_gpx(C.OUT_DIR / "felskandidaten.gpx", sets)
    kmz = write_kmz(C.OUT_DIR / "felskandidaten.kmz", sets)
    for layer, _label, g in sets:
        g.to_file(C.OUT_DIR / f"{layer}.geojson", driver="GeoJSON")
    n = sum(len(g) for _l, _lb, g in sets)
    print(f"{n} Kandidaten exportiert:")
    for f in (gpx, kmz, *sorted(C.OUT_DIR.glob("*.geojson"))):
        print(f"  {f.name:32s} {f.stat().st_size/1024:8.1f} KB")


if __name__ == "__main__":
    main()
