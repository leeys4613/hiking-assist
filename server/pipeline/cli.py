"""명령줄: 그래프 생성 / 후보 승인 / 폐쇄 구간 지정.
  server/.venv/bin/python -I server/tools/graph.py build   suraksan --zip docs/mountain/113500201.zip --course testdata/suraksan_package.json [--osm]
  server/.venv/bin/python -I server/tools/graph.py approve suraksan <candidate-id> [--note 메모]
  server/.venv/bin/python -I server/tools/graph.py close   suraksan <산림청 일련번호>
  server/.venv/bin/python -I server/tools/graph.py open    suraksan <산림청 일련번호>
승인/폐쇄 후에는 build 를 다시 실행해 검증 결과를 확인한다."""
import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
OUT = REPO / "testdata" / "graphs"
CACHE = REPO / "server" / "data" / "raw"


def _paths(m):
    return OUT / f"{m}.approvals.json", OUT / f"{m}.report.json"


def cmd_build(a):
    from . import osm
    from .dem import Dem
    from .run import run
    # 산 등산로 영역의 공개 좌표 범위만 Overpass 로 보낸다(개인 정보 없음)
    fetch = (lambda bbox: osm.fetch(bbox, CACHE / f"{a.mountain}.osm.json", a.refresh)) if a.osm else None
    r = run(a.mountain, a.zip, a.course, _paths(a.mountain)[0], OUT, Dem(CACHE / "dem"), fetch, a.tol)
    s = r["summary"]
    print(f"그래프: 노드 {s['nodes']} · 간선 {s['edges']} · 폐쇄 {s['closed_edges']} · 막다른 끝 {s['dead_ends']} · 연결 요소 {s['components']}")
    for c in r["components"]:
        print(f"  요소 {c['id']}: 노드 {c['nodes']} 간선 {c['edges']} {c['length_m'] / 1000:.1f}km" + ("  ← 코스 포함" if c["has_course"] else ""))
    for p in r["key_points"]:
        print(f"  {p['name']:7s} 노드 {p['node']} ({p['distance_m']}m) 요소 {p['component']} {'OK' if p['ok'] else 'X'}")
    print(f"코스가 그래프 위: {r['course_on_graph']}")
    print("검증:", "통과 → 재경로 사용" if r["passed"] else "실패 → 재경로 사용 안 함")
    for why in r["reasons"]:
        print("  -", why)
    ap = r["approvals"]
    print(f"승인 반영: 연결 {len(ap['applied'])}건(미적용 {len(ap['skipped'])}) · 폐쇄 {len(ap['closed_edges'])}건")
    osm_note = "" if r["osm_checked"] else (f" (OSM 실패: {r['osm_error']})" if r.get("osm_error") else " (OSM 미확인: --osm)")
    print(f"끊긴 지점 후보 {len(r['candidates'])}건" + osm_note)
    for c in r["candidates"][:15]:
        print(f"  {c['id']:28s} {c['length_m']:6.1f}m  요소 {c['components']}  {c['note']}")


def cmd_approve(a):
    ap_path, rp_path = _paths(a.mountain)
    cand = next((c for c in json.loads(rp_path.read_text())["candidates"] if c["id"] == a.candidate), None)
    if cand is None:
        sys.exit(f"후보 {a.candidate} 없음. build 결과의 id 를 확인하세요.")
    ap = json.loads(ap_path.read_text()) if ap_path.exists() else {"links": [], "closed_edges": []}
    ap["links"].append({"kind": cand["kind"], "coords": cand["coords"], "note": a.note or cand["note"],
                        "approved_at": datetime.now(timezone.utc).isoformat(timespec="seconds")})
    ap_path.write_text(json.dumps(ap, ensure_ascii=False, indent=1))
    print(f"승인했습니다: {cand['note']}. build 를 다시 실행해 반영/검증하세요.")


def _closed(a, add):
    ap_path, _ = _paths(a.mountain)
    ap = json.loads(ap_path.read_text()) if ap_path.exists() else {"links": [], "closed_edges": []}
    s = set(ap["closed_edges"])
    (s.add if add else s.discard)(a.sn)
    ap["closed_edges"] = sorted(s)
    ap_path.write_text(json.dumps(ap, ensure_ascii=False, indent=1))
    print(("폐쇄" if add else "개방") + f" 지정: 일련번호 {a.sn}. build 를 다시 실행하세요. 현재 폐쇄: {ap['closed_edges']}")


def main(argv=None):
    p = argparse.ArgumentParser(prog="graph")
    sub = p.add_subparsers(dest="cmd", required=True)
    b = sub.add_parser("build"); b.add_argument("mountain"); b.add_argument("--zip", required=True); b.add_argument("--course", required=True)
    b.add_argument("--tol", type=float, default=5.0); b.add_argument("--osm", action="store_true"); b.add_argument("--refresh", action="store_true")
    ap = sub.add_parser("approve"); ap.add_argument("mountain"); ap.add_argument("candidate"); ap.add_argument("--note", default="")
    for name in ("close", "open"):
        c = sub.add_parser(name); c.add_argument("mountain"); c.add_argument("sn", type=int)
    a = p.parse_args(argv)
    {"build": cmd_build, "approve": cmd_approve, "close": lambda x: _closed(x, True), "open": lambda x: _closed(x, False)}[a.cmd](a)


if __name__ == "__main__":
    main()
