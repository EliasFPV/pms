"""AOI-Geometrie, Kachelraster und VRT-Bau."""
import math
from pathlib import Path

from pyproj import Transformer

import config as C

# Der Rasterursprung kommt aus DescribeCoverage und haengt am Profil
# (0,5-m- und 2,5-m-Coverage haben verschiedene Ursprunge), damit unsere
# Fenster exakt auf dem jeweiligen Quellgrid liegen.


def center_utm(latlon=None):
    lat, lon = latlon or C.AOI_CENTER_LATLON
    tf = Transformer.from_crs(4326, 25832, always_xy=True)
    return tf.transform(lon, lat)


def _snap(v, origin):
    return origin + round((v - origin) / C.NATIVE_RES) * C.NATIVE_RES


def aoi_bbox():
    """Auf das Quellgrid gerastete Bounding Box (minx, miny, maxx, maxy)."""
    cx, cy = center_utm()
    b = C.AOI_BUFFER_M
    return (
        _snap(cx - b, C.GRID_ORIGIN_E), _snap(cy - b, C.GRID_ORIGIN_N),
        _snap(cx + b, C.GRID_ORIGIN_E), _snap(cy + b, C.GRID_ORIGIN_N),
    )


def aoi_circle():
    """Der eigentliche 5-km-Puffer als Shapely-Polygon."""
    from shapely.geometry import Point
    cx, cy = center_utm()
    return Point(cx, cy).buffer(C.AOI_BUFFER_M, quad_segs=128)


def tiles():
    """Kachelliste [(e0, n0, e1, n1)] ueber die AOI-BBox."""
    minx, miny, maxx, maxy = aoi_bbox()
    ts = C.TILE_SIZE_M
    out = []
    nx = math.ceil((maxx - minx) / ts)
    ny = math.ceil((maxy - miny) / ts)
    for j in range(ny):
        for i in range(nx):
            e0 = minx + i * ts
            n0 = miny + j * ts
            out.append((e0, n0, min(e0 + ts, maxx), min(n0 + ts, maxy)))
    return out


def tiles_touching_aoi():
    """Nur Kacheln, die den 5-km-Kreis wirklich schneiden."""
    from shapely.geometry import box
    circ = aoi_circle()
    return [t for t in tiles() if box(t[0], t[1], t[2], t[3]).intersects(circ)]


def tile_name(t):
    return f"tile_{int(t[0])}_{int(t[1])}"


def build_vrt(tif_paths, vrt_path, nodata=C.NODATA):
    """Minimales VRT ueber gleich aufgeloeste, CRS-gleiche Kacheln.

    Ersetzt gdalbuildvrt (osgeo ist hier nicht installiert). Setzt voraus,
    dass alle Kacheln auf demselben Grid liegen - das garantiert das
    Snapping in aoi_bbox()/tiles().
    """
    import rasterio
    from xml.sax.saxutils import escape

    tif_paths = [Path(p) for p in tif_paths]
    if not tif_paths:
        raise ValueError("build_vrt: keine Kacheln")

    infos = []
    for p in tif_paths:
        with rasterio.open(p) as s:
            infos.append((p, s.bounds, s.width, s.height, s.transform, str(s.crs), s.dtypes[0]))

    res_x = abs(infos[0][4].a)
    res_y = abs(infos[0][4].e)
    minx = min(i[1].left for i in infos)
    maxx = max(i[1].right for i in infos)
    miny = min(i[1].bottom for i in infos)
    maxy = max(i[1].top for i in infos)
    width = int(round((maxx - minx) / res_x))
    height = int(round((maxy - miny) / res_y))

    with rasterio.open(infos[0][0]) as s:
        wkt = s.crs.to_wkt()
    dtype_map = {"float32": "Float32", "float64": "Float64", "int16": "Int16"}
    gdt = dtype_map.get(infos[0][6], "Float32")

    parts = [
        f'<VRTDataset rasterXSize="{width}" rasterYSize="{height}">',
        f"  <SRS>{escape(wkt)}</SRS>",
        f"  <GeoTransform>{minx:.6f}, {res_x:.10f}, 0.0, "
        f"{maxy:.6f}, 0.0, {-res_y:.10f}</GeoTransform>",
        f'  <VRTRasterBand dataType="{gdt}" band="1">',
        f"    <NoDataValue>{nodata}</NoDataValue>",
        "    <ColorInterp>Gray</ColorInterp>",
    ]
    for p, b, w, h, _t, _c, _d in infos:
        xoff = int(round((b.left - minx) / res_x))
        yoff = int(round((maxy - b.top) / res_y))
        parts += [
            "    <SimpleSource>",
            f'      <SourceFilename relativeToVRT="0">{escape(str(p))}</SourceFilename>',
            "      <SourceBand>1</SourceBand>",
            f'      <SourceProperties RasterXSize="{w}" RasterYSize="{h}" '
            f'DataType="{gdt}" BlockXSize="{min(w, 512)}" BlockYSize="{min(h, 512)}"/>',
            f'      <SrcRect xOff="0" yOff="0" xSize="{w}" ySize="{h}"/>',
            f'      <DstRect xOff="{xoff}" yOff="{yoff}" xSize="{w}" ySize="{h}"/>',
            f"      <NODATA>{nodata}</NODATA>",
            "    </SimpleSource>",
        ]
    parts += ["  </VRTRasterBand>", "</VRTDataset>"]
    Path(vrt_path).write_text("\n".join(parts))
    return vrt_path
