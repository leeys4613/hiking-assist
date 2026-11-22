"""산 하나의 그래프 파이프라인: SHP → 그래프 → (승인 반영) → 검증 → 후보 → 패키지용 그래프 JSON."""
import json
from datetime import datetime, timezone
from pathlib import Path

from . import candidates, geo, shp, validate
from .export import export_graph
from .graph import build, add_link, ensure_node


def load_approvals(path: Path) -> dict:
    return json.loads(path.read_text()) if path.exists() else {"links": [], "closed_edges": []}


def run(mountain: str, zip_path, package_path, approvals_path, out_dir, dem, osm_fetch=None, tol: float = 5.0) -> dict:
    """osm_fetch(bbox) → OSM 길 목록. 실패해도 그래프 생성은 계속하고 리포트에 사유를 남긴다."""
    out_dir = Path(out_dir)
    line, _spot = shp.load(zip_path)
    lines = [(int(r.PMNTN_SN), r.geometry, (r.PMNTN_NM.strip() if isinstance(r.PMNTN_NM, str) else "")) for r in line.itertuples()
             if r.geometry is not None and not r.geometry.is_empty]
    sns = [s for s, _, _ in lines]
    if len(set(sns)) != len(sns):
        raise ValueError("PMNTN_SN is not unique; edge ids would collide")
    g = build(lines, tol)

    ap = load_approvals(approvals_path)
    applied, skipped = [], []
    for link in ap.get("links", []):
        ok = add_link(g, [geo.to_xy(la, lo) for la, lo in link["coords"]], link.get("kind", "gap"), link.get("note", ""))
        (applied if ok else skipped).append(link.get("note", link.get("kind")))
    closed = set(ap.get("closed_edges", []))
    for e in g.edges:
        if e.sn in closed:
            e.closed = True

    pkg = json.loads(Path(package_path).read_text())
    poly = pkg["course"]["polyline"]
    start, end = geo.to_xy(*poly[0][:2]), geo.to_xy(*poly[-1][:2])
    summit = geo.to_xy(*poly[pkg["course"]["summit_index"]][:2])
    # 정상/들머리가 간선 중간에 있어도 재경로 목표가 되도록 노드를 만든다(15m 이내일 때만)
    for name, p in (("trailhead", start), ("summit", summit), ("trailhead", end)):
        n = ensure_node(g, p, snap_tol=8.0, split_tol=15.0)
        if n is not None:
            g.node_types[n] = name
    course_xy = [geo.to_xy(p[0], p[1]) for p in poly]
    report = validate.validate(g, course_xy, start, summit, end)

    cands = candidates.gap_candidates(g)
    osm_ways, osm_error = None, None
    if osm_fetch is not None:
        xs = [p[0] for e in g.edges for p in e.coords]; ys = [p[1] for e in g.edges for p in e.coords]
        (s, w), (n, e_) = geo.to_latlon(min(xs), min(ys)), geo.to_latlon(max(xs), max(ys))
        try:
            osm_ways = osm_fetch((s - 0.002, w - 0.002, n + 0.002, e_ + 0.002))
            cands += candidates.osm_candidates(g, osm_ways)
        except Exception as ex:           # 네트워크/서비스 오류: OSM 후보는 선택 정보이므로 빌드를 막지 않는다
            osm_error = f"{type(ex).__name__}: {ex}"
    report.update({
        "mountain": mountain, "source": Path(zip_path).name, "snap_tol_m": tol,
        "built_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "approvals": {"applied": applied, "skipped": skipped, "closed_edges": sorted(closed)},
        "candidates": cands, "osm_checked": osm_ways is not None, "osm_error": osm_error,
    })
    graph_json = export_graph(g, dem)
    out_dir.mkdir(parents=True, exist_ok=True)
    (out_dir / f"{mountain}.graph.json").write_text(json.dumps(graph_json, ensure_ascii=False, separators=(",", ":")))
    (out_dir / f"{mountain}.report.json").write_text(json.dumps(report, ensure_ascii=False, indent=1))
    return report
