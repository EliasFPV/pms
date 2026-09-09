"""Stufe 7: GeoPackage, CSV, QGIS-Projekt, README."""
from pathlib import Path
from xml.sax.saxutils import escape

import config as C

CSV_COLUMNS = [
    "id", "score",
    "score_wall_height", "score_slope_compact", "score_geology",
    "score_south_aspect", "score_access",
    "vert_extent_m", "area_m2", "slope_mean_deg", "slope_max_deg",
    "slope_std_deg", "aspect_mean_deg", "elev_m", "z_min_m", "z_max_m",
    "ndom_mean_m", "geologie", "geologie_klasse", "geologie_eignung",
    "im_schutzgebiet", "schutzgebiet", "dist_weg_m", "weg_typ",
    "centroid_e", "centroid_n", "lat", "lon",
]

_SRS = """<srs><spatialrefsys>
      <proj4>+proj=utm +zone=32 +ellps=GRS80 +towgs84=0,0,0,0,0,0,0 +units=m +no_defs</proj4>
      <srsid>2649</srsid><srid>25832</srid><authid>EPSG:25832</authid>
      <description>ETRS89 / UTM zone 32N</description>
      <projectionacronym>utm</projectionacronym>
      <ellipsoidacronym>EPSG:7019</ellipsoidacronym>
      <geographicflag>false</geographicflag>
    </spatialrefsys></srs>"""


def write_vector_outputs(gdf, gpkg_path, csv_path, context=None):
    """GeoPackage (Kandidaten + Kontext) und nach Score sortiertes CSV."""
    import geopandas as gpd

    gpkg_path, csv_path = Path(gpkg_path), Path(csv_path)
    gpkg_path.parent.mkdir(parents=True, exist_ok=True)
    if gpkg_path.exists():
        gpkg_path.unlink()

    g = gdf.copy()
    ll = g.geometry.centroid.to_crs(4326)
    g["lat"] = ll.y.values
    g["lon"] = ll.x.values
    g.to_file(gpkg_path, layer="felskandidaten", driver="GPKG")

    for name, layer in (context or {}).items():
        if layer is not None and len(layer):
            layer.to_file(gpkg_path, layer=name, driver="GPKG")

    cols = [c for c in CSV_COLUMNS if c in g.columns]
    (g[cols].sort_values("score", ascending=False)
            .to_csv(csv_path, index=False, float_format="%.4f"))
    return gpkg_path, csv_path


def _raster_layer(lid, name, src, gray=True, vmin=0, vmax=255, ramp=False):
    if ramp:
        renderer = (
            f'<rasterrenderer type="singlebandpseudocolor" band="1" opacity="0.75">'
            f'<rastershader><colorrampshader colorRampType="INTERPOLATED" '
            f'classificationMode="1" clip="0">'
            f'<item label="{vmin}" value="{vmin}" color="#2b83ba" alpha="255"/>'
            f'<item label="{(vmin+vmax)/2:.1f}" value="{(vmin+vmax)/2}" color="#ffffbf" alpha="255"/>'
            f'<item label="{vmax}" value="{vmax}" color="#d7191c" alpha="255"/>'
            f'</colorrampshader></rastershader></rasterrenderer>')
    else:
        renderer = (
            f'<rasterrenderer type="singlebandgray" band="1" grayBand="1" '
            f'opacity="1" gradient="BlackToWhite">'
            f'<contrastEnhancement><minValue>{vmin}</minValue>'
            f'<maxValue>{vmax}</maxValue>'
            f'<algorithm>StretchToMinimumMaximum</algorithm>'
            f'</contrastEnhancement></rasterrenderer>')
    return f"""  <maplayer type="raster" hasScaleBasedVisibilityFlag="0">
    <id>{lid}</id>
    <datasource>{escape(src)}</datasource>
    <layername>{escape(name)}</layername>
    <provider>gdal</provider>
    {_SRS}
    <pipe>{renderer}<brightnesscontrast brightness="0" contrast="0"/>
      <huesaturation saturation="0"/><rasterresampler maxOversampling="2"/>
    </pipe>
  </maplayer>"""


def _vector_layer(lid, name, src, geom, symbol_xml):
    return f"""  <maplayer type="vector" geometry="{geom}" hasScaleBasedVisibilityFlag="0">
    <id>{lid}</id>
    <datasource>{escape(src)}</datasource>
    <layername>{escape(name)}</layername>
    <provider encoding="UTF-8">ogr</provider>
    {_SRS}
    {symbol_xml}
  </maplayer>"""


def _sym(props, symbol_type, layer_class):
    p = "".join(f'<Option type="QString" name="{k}" value="{v}"/>'
                for k, v in props.items())
    return (f'<renderer-v2 type="singleSymbol" forceraster="0" symbollevels="0">'
            f'<symbols><symbol type="{symbol_type}" name="0" alpha="1" clip_to_extent="1">'
            f'<layer class="{layer_class}" enabled="1" pass="0">'
            f'<Option type="Map">{p}</Option></layer></symbol></symbols>'
            f'</renderer-v2>')


def write_qgis_project(qgs_path, gpkg_rel, hillshade_rel, ndom_rel, slope_rel,
                       extent, title="Felskandidaten Gadertal"):
    minx, miny, maxx, maxy = extent
    cand_sym = _sym({"color": "255,0,0,90", "outline_color": "227,26,28,255",
                     "outline_width": "0.5", "style": "solid",
                     "outline_style": "solid"}, "fill", "SimpleFill")
    geo_sym = _sym({"color": "180,180,120,60", "outline_color": "120,120,80,255",
                    "outline_width": "0.26", "style": "solid",
                    "outline_style": "solid"}, "fill", "SimpleFill")
    park_sym = _sym({"color": "0,160,80,40", "outline_color": "0,120,60,255",
                     "outline_width": "0.6", "style": "solid",
                     "outline_style": "dash"}, "fill", "SimpleFill")
    way_sym = _sym({"line_color": "60,60,60,255", "line_width": "0.3",
                    "line_style": "solid"}, "line", "SimpleLine")

    layers = [
        _raster_layer("hs1", "DGM-Schummerung (2 m)", hillshade_rel,
                      vmin=0, vmax=255),
        _raster_layer("ndom1", "nDOM (DOM - DGM)", ndom_rel, ramp=True,
                      vmin=0, vmax=25),
        _raster_layer("slope1", "Hangneigung 2 m [Grad]", slope_rel, ramp=True,
                      vmin=0, vmax=90),
        _vector_layer("geo1", "Geologie (Uebersicht)",
                      f"{gpkg_rel}|layername=geologie", "Polygon", geo_sym),
        _vector_layer("park1", "Naturparke", f"{gpkg_rel}|layername=schutzgebiete",
                      "Polygon", park_sym),
        _vector_layer("way1", "Wege/Strassen (OSM)", f"{gpkg_rel}|layername=wege",
                      "Line", way_sym),
        _vector_layer("cand1", "Felskandidaten",
                      f"{gpkg_rel}|layername=felskandidaten", "Polygon", cand_sym),
    ]
    order = ["cand1", "way1", "park1", "geo1", "slope1", "ndom1", "hs1"]
    tree = "".join(
        f'<layer-tree-layer id="{i}" name="{n}" source="{escape(s)}" '
        f'providerKey="{p}" checked="{"Qt::Checked" if c else "Qt::Unchecked"}" '
        f'expanded="0"/>'
        for i, n, s, p, c in [
            ("cand1", "Felskandidaten", f"{gpkg_rel}|layername=felskandidaten", "ogr", 1),
            ("way1", "Wege/Strassen (OSM)", f"{gpkg_rel}|layername=wege", "ogr", 1),
            ("park1", "Naturparke", f"{gpkg_rel}|layername=schutzgebiete", "ogr", 1),
            ("geo1", "Geologie (Uebersicht)", f"{gpkg_rel}|layername=geologie", "ogr", 0),
            ("slope1", "Hangneigung 2 m [Grad]", slope_rel, "gdal", 0),
            ("ndom1", "nDOM (DOM - DGM)", ndom_rel, "gdal", 0),
            ("hs1", "DGM-Schummerung (2 m)", hillshade_rel, "gdal", 1),
        ])

    xml = f"""<?xml version="1.0" encoding="UTF-8"?>
<qgis projectname="{escape(title)}" version="3.34.0">
  <homePath path=""/>
  <title>{escape(title)}</title>
  <projectCrs>{_SRS}</projectCrs>
  <layer-tree-group>{tree}
    <custom-order enabled="0">{"".join(f"<item>{i}</item>" for i in order)}</custom-order>
  </layer-tree-group>
  <mapcanvas name="theMapCanvas">
    <units>meters</units>
    <extent><xmin>{minx}</xmin><ymin>{miny}</ymin><xmax>{maxx}</xmax><ymax>{maxy}</ymax></extent>
    <destinationsrs>{_SRS}</destinationsrs>
  </mapcanvas>
  <projectlayers>
{chr(10).join(layers)}
  </projectlayers>
  <layerorder>{"".join(f"<layer id='{i}'/>" for i in order)}</layerorder>
  <properties>
    <Paths><Absolute type="bool">false</Absolute></Paths>
  </properties>
</qgis>
"""
    Path(qgs_path).write_text(xml, encoding="utf-8")
    return qgs_path
