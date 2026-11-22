"""SHP 선분 → 그래프 (명세 §7-2). 끝점 스냅(허용오차 3~5m) + T자 교차 분할.
모든 좌표는 EPSG:5186(미터). 간선 ID 는 산림청 일련번호(PMNTN_SN) 기반이라 SHP 가 그대로면 재생성해도 같다."""
from dataclasses import dataclass, field

import networkx as nx
from shapely.geometry import LineString, Point
from shapely.ops import substring

DEFAULT_TOL = 5.0


@dataclass
class Edge:
    id: int                    # sn*100 + 분할 번호
    sn: int                    # 산림청 일련번호(승인 파일에서 폐쇄 구간을 가리킬 때 쓴다)
    a: int
    b: int
    coords: list               # [(x, y), ...] a → b
    name: str = ""
    closed: bool = False
    source: str = "shp"        # shp | gap(승인된 직선 연결) | osm(승인된 OSM 보완)

    @property
    def line(self) -> LineString:
        return LineString(self.coords)

    @property
    def length(self) -> float:
        return self.line.length


@dataclass
class Graph:
    nodes: list = field(default_factory=list)   # [(x, y)]
    edges: list = field(default_factory=list)   # [Edge]
    node_types: dict = field(default_factory=dict)   # node idx → summit | trailhead | junction

    def open_edges(self):
        return [e for e in self.edges if not e.closed]

    def nx(self, include_closed: bool = False) -> nx.MultiGraph:
        g = nx.MultiGraph()
        g.add_nodes_from(range(len(self.nodes)))
        for e in self.edges:
            if include_closed or not e.closed:
                g.add_edge(e.a, e.b, key=e.id, length=e.length)
        return g


def _split_t_junctions(lines, tol):
    """한 선의 끝점이 다른 선의 '중간'에 tol 이내로 닿으면 그 선을 거기서 쪼갠다. [(sn, k, LineString, name)]"""
    ends = []
    for sn, g, _ in lines:
        ends += [Point(g.coords[0][:2]), Point(g.coords[-1][:2])]
    out = []
    for sn, g, name in lines:
        cuts = set()
        for p in ends:
            if g.distance(p) <= tol:
                pos = g.project(p)
                if tol < pos < g.length - tol:
                    cuts.add(round(pos, 1))
        prev, k = 0.0, 0
        for c in sorted(cuts) + [g.length]:
            if c - prev > 0.5:
                out.append((sn, k, substring(g, prev, c) if cuts else g, name)); k += 1
            prev = c
    return out


class _Snapper:
    """tol 이내의 끝점을 한 노드로 묶는다(격자 탐색). 노드 좌표는 묶인 점들의 평균."""

    def __init__(self, tol):
        self.tol, self.pts, self.members, self.grid = tol, [], [], {}

    def _cell(self, p):
        return int(p[0] // self.tol), int(p[1] // self.tol)

    def get(self, p):
        cx, cy = self._cell(p)
        best, bd = None, self.tol
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for i in self.grid.get((cx + dx, cy + dy), ()):
                    d = ((p[0] - self.pts[i][0]) ** 2 + (p[1] - self.pts[i][1]) ** 2) ** 0.5
                    if d <= bd:
                        best, bd = i, d
        if best is None:
            best = len(self.pts)
            self.pts.append(p); self.members.append([p])
            self.grid.setdefault(self._cell(p), []).append(best)
        else:
            self.members[best].append(p)
        return best

    def centers(self):
        return [(sum(q[0] for q in m) / len(m), sum(q[1] for q in m) / len(m)) for m in self.members]


def build(lines, tol: float = DEFAULT_TOL) -> Graph:
    """lines: [(sn, LineString, name)] → Graph"""
    pieces = _split_t_junctions(lines, tol)
    sn_ = _Snapper(tol)
    raw = []
    for sn, k, g, name in pieces:
        c = [tuple(p[:2]) for p in g.coords]
        raw.append((sn, k, sn_.get(c[0]), sn_.get(c[-1]), c, name))
    centers = sn_.centers()
    gr = Graph(nodes=centers)
    for sn, k, a, b, c, name in raw:
        c = [centers[a]] + c[1:-1] + [centers[b]]       # 끝점을 노드 좌표에 맞춰 이어 붙인다
        if a == b and LineString(c).length < tol * 2:
            continue                                      # 스냅으로 생긴 점 같은 루프는 버린다
        gr.edges.append(Edge(id=sn * 100 + k, sn=sn, a=a, b=b, coords=c, name=name))
    return gr


# ---------------------------------------------------------------- 편집 (승인된 변경만 반영)
def nearest_node(g: Graph, p, within=None):
    best, bd = None, float("inf")
    for i, q in enumerate(g.nodes):
        d = ((p[0] - q[0]) ** 2 + (p[1] - q[1]) ** 2) ** 0.5
        if d < bd:
            best, bd = i, d
    return (best, bd) if within is None or bd <= within else (None, bd)


def ensure_node(g: Graph, p, snap_tol: float = 8.0, split_tol: float = 15.0) -> int | None:
    """p 근처에 노드가 있으면 그 노드, 없고 간선 위 split_tol 이내면 거기서 간선을 쪼개 노드를 만든다. 둘 다 아니면 None."""
    i, d = nearest_node(g, p)
    if i is not None and d <= snap_tol:
        return i
    best, bd, bpos = None, split_tol, 0.0
    for e in g.edges:
        line = e.line
        dist = line.distance(Point(p))
        if dist <= bd:
            best, bd, bpos = e, dist, line.project(Point(p))
    if best is None:
        return None
    line = best.line
    if bpos < snap_tol:
        return best.a
    if bpos > line.length - snap_tol:
        return best.b
    q = line.interpolate(bpos)
    n = len(g.nodes)
    g.nodes.append((q.x, q.y))
    first, second = substring(line, 0, bpos), substring(line, bpos, line.length)
    k = max((e.id for e in g.edges if e.sn == best.sn), default=best.id) + 1
    g.edges.remove(best)
    g.edges.append(Edge(best.id, best.sn, best.a, n, list(first.coords), best.name, best.closed, best.source))
    g.edges.append(Edge(k, best.sn, n, best.b, list(second.coords), best.name, best.closed, best.source))
    return n


def add_link(g: Graph, coords, source: str, name: str = "") -> bool:
    """승인된 연결(미터 좌표 목록)을 그래프에 간선으로 추가한다. 양 끝이 기존 노드/간선에 닿지 않으면 False."""
    a = ensure_node(g, coords[0], snap_tol=8.0, split_tol=15.0)
    b = ensure_node(g, coords[-1], snap_tol=8.0, split_tol=15.0)
    if a is None or b is None or a == b:
        return False
    sn = 900000 + len([e for e in g.edges if e.source != "shp"])
    c = [g.nodes[a]] + [tuple(p) for p in coords[1:-1]] + [g.nodes[b]]
    g.edges.append(Edge(sn * 100, sn, a, b, c, name, False, source))
    return True


def components(g: Graph, include_closed: bool = False) -> list[set]:
    return sorted(nx.connected_components(g.nx(include_closed)), key=len, reverse=True)
