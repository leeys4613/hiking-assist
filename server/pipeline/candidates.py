"""끊긴 지점 보완 후보(명세 §7-4). 후보만 만들고 절대 자동으로 합치지 않는다 — 사람이 승인한 것만 approvals 에 들어간다."""
import itertools

from shapely.geometry import LineString, Point
from shapely.ops import nearest_points, substring, unary_union

from . import geo
from .graph import Graph, components


def _comp_lines(g: Graph):
    comps = components(g)
    out = []
    for c in comps:
        lines = [e.line for e in g.open_edges() if e.a in c]
        out.append(unary_union(lines) if lines else None)
    return comps, out


def _ll(pts):
    return [list(geo.to_latlon(x, y)) for x, y in pts]


def gap_candidates(g: Graph, max_gap: float = 60.0, per_pair: int = 3) -> list[dict]:
    """서로 다른 연결 요소 사이의 짧은 직선 간격. 조각 A 의 노드에서 조각 B 의 가장 가까운 점까지."""
    comps, unions = _comp_lines(g)
    out = []
    for i, j in itertools.combinations(range(len(comps)), 2):
        if unions[i] is None or unions[j] is None:
            continue
        found = []
        for a, b in ((i, j), (j, i)):
            for n in comps[a]:
                p = Point(g.nodes[n])
                d = p.distance(unions[b])
                if d <= max_gap:
                    q = nearest_points(unions[b], p)[0]
                    found.append((d, (p.x, p.y), (q.x, q.y)))
        found.sort()
        kept = []
        for d, p, q in found:
            if all(Point(p).distance(Point(k[1])) > 30 for k in kept):    # 같은 자리 중복 제거
                kept.append((d, p, q))
            if len(kept) >= per_pair:
                break
        for d, p, q in kept:
            out.append({"id": f"gap-{i}-{j}-{len(out)}", "kind": "gap", "components": [i, j], "length_m": round(d, 1),
                        "coords": _ll([p, q]), "note": f"직선 {d:.0f}m 간격"})
    return out


def osm_candidates(g: Graph, ways: list[dict], attach_tol: float = 10.0, max_len: float = 400.0) -> list[dict]:
    """OSM 길이 서로 다른 두 연결 요소에 모두 닿을 때, 그 길을 따라 이은 보완 후보."""
    comps, unions = _comp_lines(g)
    out = []
    for w in ways:
        line = LineString([geo.to_xy(lat, lon) for lat, lon in w["coords"]])
        touch = []   # (component, 길 위 위치, 길 위 점, 그래프 위 점)
        for ci, u in enumerate(unions):
            if u is not None and line.distance(u) <= attach_tol:
                pw, pg = nearest_points(line, u)
                touch.append((ci, line.project(pw), pw, pg))
        for (c1, s1, _, g1), (c2, s2, _, g2) in itertools.combinations(touch, 2):
            lo, hi = sorted((s1, s2))
            if hi - lo < 1 or hi - lo > max_len:
                continue
            first, last = (g1, g2) if s1 <= s2 else (g2, g1)
            mid = list(substring(line, lo, hi).coords)
            coords = [(first.x, first.y)] + mid + [(last.x, last.y)]
            out.append({"id": f"osm-{w['id']}-{min(c1, c2)}-{max(c1, c2)}", "kind": "osm", "components": [min(c1, c2), max(c1, c2)],
                        "length_m": round(hi - lo, 1), "coords": _ll(coords),
                        "note": f"OSM way {w['id']} {w['name']} ({w['highway']}) {hi - lo:.0f}m"})
    out.sort(key=lambda c: c["length_m"])
    return out
