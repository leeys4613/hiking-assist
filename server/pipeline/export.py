"""그래프 → 코스 패키지의 graph 필드(명세 §5.1). 간선 geometry 에 고도를 채운다."""
from shapely.geometry import LineString

from . import geo
from .graph import Graph


def _densify(coords, spacing):
    out = [coords[0]]
    for a, b in zip(coords, coords[1:]):
        d = ((b[0] - a[0]) ** 2 + (b[1] - a[1]) ** 2) ** 0.5
        n = int(d // spacing)
        for k in range(1, n + 1):
            f = k * spacing / d
            if f < 1:
                out.append((a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f))
        out.append(b)
    return out


def _smooth(z, w=3):
    h = w // 2
    return [sum(z[max(0, i - h): i + h + 1]) / len(z[max(0, i - h): i + h + 1]) for i in range(len(z))]


def export_graph(g: Graph, dem, spacing: float = 20.0, simplify_tol: float = 2.0) -> dict:
    """dem.elevation(lat, lon) 로 고도를 채운다. DEM(30m 격자)의 요철은 3점 이동평균으로 누그러뜨린다."""
    edges, end_ele = [], {}
    for e in g.edges:
        c = list(LineString(e.coords).simplify(simplify_tol).coords) if len(e.coords) > 2 else list(e.coords)
        c[0], c[-1] = e.coords[0], e.coords[-1]
        pts = _densify(c, spacing)
        ll = [geo.to_latlon(x, y) for x, y in pts]
        z = _smooth([dem.elevation(la, lo) for la, lo in ll])
        length = sum(((b[0] - a[0]) ** 2 + (b[1] - a[1]) ** 2) ** 0.5 for a, b in zip(pts, pts[1:]))
        up = sum(max(0.0, b - a) for a, b in zip(z, z[1:]))
        down = sum(max(0.0, a - b) for a, b in zip(z, z[1:]))
        end_ele.setdefault(e.a, []).append(z[0]); end_ele.setdefault(e.b, []).append(z[-1])
        assert e.id < 2**31, "edge id must fit Kotlin Int"
        edges.append({"id": e.id, "from": e.a, "to": e.b,
                      "geometry": [[round(la, 6), round(lo, 6), round(zz, 1)] for (la, lo), zz in zip(ll, z)],
                      "length_m": round(length, 1), "ascent_m": round(up, 1), "descent_m": round(down, 1), "closed": e.closed})
    nodes = []
    for i, (x, y) in enumerate(g.nodes):
        la, lo = geo.to_latlon(x, y)
        es = end_ele.get(i)
        ele = sum(es) / len(es) if es else dem.elevation(la, lo)
        nodes.append({"id": i, "lat": round(la, 6), "lon": round(lo, 6), "ele": round(ele, 1), "type": g.node_types.get(i, "junction")})
    return {"nodes": nodes, "edges": edges}
