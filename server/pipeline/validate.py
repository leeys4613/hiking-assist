"""그래프 검증(명세 §7-3, AC8): 연결 요소 수, 고립 조각, 코스 시작↔정상↔종점이 같은 요소인지.
통과한 산에서만 앱에 그래프를 내려주고 재경로를 켠다."""
from shapely.geometry import LineString, Point
from shapely.ops import unary_union

from . import geo
from .graph import Graph, components, nearest_node

TARGET_SNAP_M = 60.0       # guidance-core Rerouter.TARGET_SNAP_M 와 같아야 한다
COURSE_ON_GRAPH_M = 8.0    # 코스 점이 열린 간선에서 이 거리 이내면 '그래프 위'
COVERAGE_MIN = 0.98


def validate(g: Graph, course_xy: list, start_xy, summit_xy, end_xy) -> dict:
    """course_xy: 코스 polyline [(x, y)], 나머지는 미터 좌표. 반환값은 JSON 으로 저장되는 리포트."""
    comps = components(g)
    comp_of = {n: i for i, c in enumerate(comps) for n in c}
    opened = [e.line for e in g.open_edges()]
    union = unary_union(opened) if opened else None

    def key_point(name, p):
        n, d = nearest_node(g, p)
        ok = n is not None and d <= TARGET_SNAP_M and n in comp_of
        return {"name": name, "node": n, "distance_m": round(d, 1), "component": comp_of.get(n), "ok": bool(ok)}

    pts = [key_point("start", start_xy), key_point("summit", summit_xy), key_point("end", end_xy)]
    same = all(p["ok"] for p in pts) and len({p["component"] for p in pts}) == 1
    cov = None
    if union is not None and course_xy:
        near = sum(1 for p in course_xy if union.distance(Point(p)) <= COURSE_ON_GRAPH_M)
        cov = round(near / len(course_xy), 4)
    reasons = []
    for p in pts:
        if not p["ok"]:
            reasons.append(f"{p['name']} 지점 근처(≤{TARGET_SNAP_M:.0f}m)에 연결된 그래프 노드가 없음 (가장 가까운 노드 {p['distance_m']}m)")
    if all(p["ok"] for p in pts) and not same:
        reasons.append("시작·정상·종점이 서로 다른 연결 요소에 있음 → 길이 끊겨 있음")
    if cov is None or cov < COVERAGE_MIN:
        reasons.append(f"코스가 그래프 위에 있는 비율 {cov} < {COVERAGE_MIN}")
    course_comp = pts[1]["component"]
    out = []
    for i, c in enumerate(comps):
        edges = [e for e in g.open_edges() if e.a in c]
        xs = [g.nodes[n][0] for n in c]; ys = [g.nodes[n][1] for n in c]
        lat0, lon0 = geo.to_latlon(min(xs), min(ys)); lat1, lon1 = geo.to_latlon(max(xs), max(ys))
        out.append({"id": i, "nodes": len(c), "edges": len(edges), "length_m": round(sum(e.length for e in edges)),
                    "bbox": [lat0, lon0, lat1, lon1], "has_course": i == course_comp})
    return {
        "passed": bool(same and cov is not None and cov >= COVERAGE_MIN),
        "reasons": reasons,
        "key_points": pts,
        "course_on_graph": cov,
        "components": out,
        "summary": {"nodes": len(g.nodes), "edges": len(g.edges), "closed_edges": sum(1 for e in g.edges if e.closed),
                    "components": len(comps), "dead_ends": sum(1 for n in range(len(g.nodes))
                                                              if sum(1 for e in g.edges if n in (e.a, e.b)) == 1)},
    }
