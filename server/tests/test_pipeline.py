import json
import zipfile
from pathlib import Path

import geopandas as gpd
import pytest
from shapely.geometry import LineString, Point

from pipeline import candidates, cli, geo, shp, validate
from pipeline.export import export_graph
from pipeline.graph import Edge, Graph, add_link, build, components, ensure_node
from pipeline.run import run

REPO = Path(__file__).resolve().parents[2]
O = (212000.0, 564000.0)          # EPSG:5186 의 서울 부근 임의 원점


def L(*pts):
    return LineString([(O[0] + x, O[1] + y) for x, y in pts])


def P(x, y):
    return (O[0] + x, O[1] + y)


def lines(*geoms):
    return [(100 + i, g, f"t{i}") for i, g in enumerate(geoms)]


class FlatDem:
    """고도 = 위도에 비례(북쪽이 높다). 네트워크 없이 export 를 시험한다."""
    def elevation(self, lat, lon):
        return (lat - 37.0) * 111000   # 북쪽 1m = 고도 1m


# ------------------------------------------------------------------ 그래프 생성
def test_endpoints_within_tolerance_are_snapped():
    g = build(lines(L((0, 0), (100, 0)), L((103, 0), (200, 0))), tol=5)      # 3m 떨어짐
    assert len(g.nodes) == 3 and len(components(g)) == 1
    g = build(lines(L((0, 0), (100, 0)), L((108, 0), (200, 0))), tol=5)      # 8m 떨어짐
    assert len(g.nodes) == 4 and len(components(g)) == 2


def test_t_junction_splits_the_line_it_touches():
    g = build(lines(L((0, 0), (100, 0)), L((50, 3), (50, 80))), tol=5)       # 세로선 끝이 가로선 중간에 닿음
    assert len(g.edges) == 3 and len(components(g)) == 1
    ids = sorted(e.id for e in g.edges)
    assert len(set(ids)) == 3 and all(i < 2**31 for i in ids)


def test_edge_geometry_is_continuous_at_snapped_nodes():
    g = build(lines(L((0, 0), (100, 0)), L((103, 2), (200, 0))), tol=5)
    for e in g.edges:
        assert tuple(e.coords[0]) == g.nodes[e.a] and tuple(e.coords[-1]) == g.nodes[e.b]


def test_tiny_snapping_loop_is_dropped():
    g = build(lines(L((0, 0), (2, 1)), L((50, 0), (150, 0))), tol=5)
    assert len(g.edges) == 1


# ------------------------------------------------------------------ 검증 (AC8)
def graph_two_pieces(gap=40.0):
    return build(lines(L((0, 0), (300, 0)), L((300, 0), (300, 300)),             # A: 들머리 → 정상(300,300)
                       L((300 + gap, 300), (300 + gap, 600))), tol=5)             # B: gap 만큼 떨어진 다른 조각


def course_a():
    return [P(0, 0), P(300, 0), P(300, 300)]


def test_validation_passes_when_start_summit_end_share_a_component():
    g = build(lines(L((0, 0), (300, 0)), L((300, 0), (300, 300))), tol=5)
    r = validate.validate(g, course_a(), P(0, 0), P(300, 300), P(0, 0))
    assert r["passed"] and r["reasons"] == [] and r["course_on_graph"] == 1.0
    assert r["summary"]["components"] == 1


def test_validation_fails_when_summit_is_in_another_component():
    g = graph_two_pieces()
    r = validate.validate(g, course_a(), P(0, 0), P(340, 600), P(0, 0))
    assert not r["passed"] and any("다른 연결 요소" in x for x in r["reasons"])
    assert r["summary"]["components"] == 2 and sum(1 for c in r["components"] if c["has_course"]) == 1


def test_validation_fails_when_no_node_near_summit():
    g = build(lines(L((0, 0), (300, 0))), tol=5)
    r = validate.validate(g, course_a(), P(0, 0), P(300, 300), P(0, 0))
    assert not r["passed"] and any("summit" in x for x in r["reasons"])


def test_validation_fails_when_course_is_not_on_the_graph():
    g = build(lines(L((0, 0), (300, 0)), L((300, 0), (300, 300))), tol=5)
    off = [P(0, 0), P(150, 120), P(300, 300)]                                  # 그래프와 따로 노는 코스
    r = validate.validate(g, off, P(0, 0), P(300, 300), P(0, 0))
    assert not r["passed"] and r["course_on_graph"] < 0.98


def test_closed_edges_do_not_connect_components():
    g = build(lines(L((0, 0), (300, 0)), L((300, 0), (300, 300))), tol=5)
    assert validate.validate(g, course_a(), P(0, 0), P(300, 300), P(0, 0))["passed"]
    for e in g.edges:
        if e.sn == 101:
            e.closed = True                                                     # 정상으로 가는 유일한 길 폐쇄
    r = validate.validate(g, course_a(), P(0, 0), P(300, 300), P(0, 0))
    assert not r["passed"] and r["summary"]["closed_edges"] == 1


# ------------------------------------------------------------------ 후보 / 승인
def test_gap_candidate_is_found_within_limit_and_not_beyond():
    c = candidates.gap_candidates(graph_two_pieces(40))
    assert len(c) >= 1 and 35 <= c[0]["length_m"] <= 45 and c[0]["kind"] == "gap"
    assert candidates.gap_candidates(graph_two_pieces(90)) == []


def test_approved_gap_merges_components_and_validation_then_passes():
    g = graph_two_pieces(40)
    cand = candidates.gap_candidates(g)[0]
    assert len(components(g)) == 2
    assert add_link(g, [geo.to_xy(*p) for p in cand["coords"]], "gap")
    assert len(components(g)) == 1
    r = validate.validate(g, course_a(), P(0, 0), P(340, 600), P(0, 0))
    assert r["passed"]
    assert any(e.source == "gap" for e in g.edges)


def test_link_that_touches_nothing_is_rejected():
    g = graph_two_pieces(40)
    n = len(g.edges)
    assert not add_link(g, [P(1000, 1000), P(1040, 1000)], "gap")
    assert len(g.edges) == n


def osm_way(wid, *pts, name="", hw="path"):
    return {"id": wid, "name": name, "highway": hw, "coords": [geo.to_latlon(*P(x, y)) for x, y in pts]}


def test_osm_way_bridging_two_components_becomes_a_candidate():
    g = graph_two_pieces(120)                                                  # 직선 간격 후보는 없는 거리
    way = osm_way(777, (300, 280), (330, 330), (420, 330), (420, 320), name="샛길")     # A 끝 근처 → B 시작 근처
    c = candidates.osm_candidates(g, [way], attach_tol=25)
    assert len(c) == 1 and c[0]["kind"] == "osm" and "777" in c[0]["id"] and "샛길" in c[0]["note"]


def test_osm_way_touching_one_component_only_is_ignored():
    g = graph_two_pieces(120)
    assert candidates.osm_candidates(g, [osm_way(1, (100, 5), (100, 80))], attach_tol=10) == []


def test_osm_way_too_long_is_ignored():
    g = graph_two_pieces(120)
    far = osm_way(2, (300, 280), (300, 900), (420, 900), (420, 320))
    assert candidates.osm_candidates(g, [far], attach_tol=25, max_len=400) == []


# ------------------------------------------------------------------ 노드 삽입 / 내보내기
def test_ensure_node_splits_an_edge_at_a_poi():
    g = build(lines(L((0, 0), (300, 0))), tol=5)
    n = ensure_node(g, P(150, 6), snap_tol=8, split_tol=15)
    assert n is not None and len(g.nodes) == 3 and len(g.edges) == 2
    assert {g.edges[0].b, g.edges[1].a} & {n}
    assert ensure_node(g, P(150, 60), split_tol=15) is None                      # 15m 넘게 떨어지면 만들지 않는다


def test_export_matches_the_package_schema():
    g = build(lines(L((0, 0), (300, 0)), L((300, 0), (300, 300))), tol=5)
    g.edges[1].closed = True
    g.node_types[ensure_node(g, P(300, 300))] = "summit"
    out = export_graph(g, FlatDem(), spacing=20.0)
    assert set(out) == {"nodes", "edges"}
    n0, e0 = out["nodes"][0], out["edges"][0]
    assert {"id", "lat", "lon", "ele", "type"} <= set(n0) and isinstance(n0["id"], int)
    assert {"id", "from", "to", "geometry", "length_m", "ascent_m", "descent_m", "closed"} <= set(e0)
    assert all(len(p) == 3 for e in out["edges"] for p in e["geometry"])
    assert sorted(e["closed"] for e in out["edges"]) == [False, True]
    assert any(n["type"] == "summit" for n in out["nodes"])
    # 점 간격은 20m 이하, 북쪽 간선은 오르막이다
    for e in out["edges"]:
        pts = [geo.to_xy(p[0], p[1]) for p in e["geometry"]]
        assert all(Point(a).distance(Point(b)) <= 20.5 for a, b in zip(pts, pts[1:]))
    north = next(e for e in out["edges"] if e["ascent_m"] > 0)
    assert north["ascent_m"] > 250 and north["descent_m"] < 5


# ------------------------------------------------------------------ SHP 읽기
def make_zip(tmp_path, line_geoms, name="PMNTN_TEST_1"):
    rows = [{"PMNTN_SN": 100 + i, "PMNTN_NM": f"구간{i}" if i % 2 == 0 else None, "geometry": g} for i, g in enumerate(line_geoms)]
    spot = gpd.GeoDataFrame([{"MANAGE_SP2": "정상", "geometry": Point(P(300, 300))}], crs="EPSG:5186")
    d = tmp_path / "shp"; d.mkdir()
    gpd.GeoDataFrame(rows, crs="EPSG:5186").to_file(d / f"{name}.shp", encoding="cp949")
    spot.to_file(d / f"PMNTN_SPOT_TEST_1.shp", encoding="cp949")
    safe = gpd.GeoDataFrame([{"SAFE_SPOT1": 1, "geometry": Point(P(1, 1))}], crs="EPSG:5186")
    safe.to_file(d / "PMNTN_SAFE_SPOT_TEST_1.shp", encoding="cp949")
    z = tmp_path / "t.zip"
    with zipfile.ZipFile(z, "w") as zf:
        for f in d.iterdir():
            zf.write(f, f.name)
    return z


def test_shp_load_picks_line_and_spot_not_the_safe_spot_file(tmp_path):
    line, spot = shp.load(make_zip(tmp_path, [L((0, 0), (300, 0)), L((300, 0), (300, 300))]))
    assert len(line) == 2 and line.geom_type.iloc[0] == "LineString"
    assert spot is not None and "MANAGE_SP2" in spot.columns


def test_zip_with_path_traversal_cannot_write_outside(tmp_path):
    z = tmp_path / "evil.zip"
    with zipfile.ZipFile(z, "w") as zf:
        zf.writestr("../../evil_marker.txt", "x")
        zf.writestr("PMNTN_X.shp", b"")
    with pytest.raises(Exception):
        shp.load(z)                                                          # 읽을 수 없는 shp 라 실패하되
    assert not (tmp_path.parent / "evil_marker.txt").exists() and not (tmp_path / "evil_marker.txt").exists()


def test_missing_zip(tmp_path):
    with pytest.raises(shp.ShpError):
        shp.load(tmp_path / "nope.zip")


# ------------------------------------------------------------------ 전체 실행
def make_package(tmp_path, summit=(300, 300)):
    pts = [(0, 0), (300, 0), summit, (300, 0), (0, 0)]
    poly = [[*geo.to_latlon(*P(x, y)), 100.0] for x, y in pts]
    f = tmp_path / "pkg.json"
    f.write_text(json.dumps({"course": {"id": "c", "polyline": poly, "summit_index": 2, "end_index": 4}}))
    return f


def test_run_end_to_end_and_osm_failure_does_not_block(tmp_path):
    z = make_zip(tmp_path, [L((0, 0), (300, 0)), L((300, 0), (300, 300))])
    pkg = make_package(tmp_path)

    def boom(bbox):
        raise TimeoutError("overpass down")

    r = run("t", z, pkg, tmp_path / "ap.json", tmp_path / "out", FlatDem(), osm_fetch=boom)
    assert r["passed"] and r["osm_checked"] is False and "TimeoutError" in r["osm_error"]
    g = json.loads((tmp_path / "out" / "t.graph.json").read_text())
    assert len(g["edges"]) == 2 and any(n["type"] == "summit" for n in g["nodes"])
    assert json.loads((tmp_path / "out" / "t.report.json").read_text())["passed"] is True


def test_run_applies_approvals_and_closed_edges(tmp_path):
    z = make_zip(tmp_path, [L((0, 0), (300, 0)), L((300, 0), (300, 300)), L((340, 300), (340, 600))])
    pkg = make_package(tmp_path, summit=(340, 600))                           # 정상이 끊긴 조각에 있다
    ap = tmp_path / "ap.json"
    r = run("t", z, pkg, ap, tmp_path / "out", FlatDem())
    assert not r["passed"] and r["candidates"]
    c = r["candidates"][0]
    ap.write_text(json.dumps({"links": [{"kind": c["kind"], "coords": c["coords"], "note": "승인"}], "closed_edges": []}))
    r2 = run("t", z, pkg, ap, tmp_path / "out", FlatDem())
    assert r2["passed"] and r2["approvals"]["applied"] == ["승인"] and r2["summary"]["components"] == 1
    ap.write_text(json.dumps({"links": [{"kind": c["kind"], "coords": c["coords"]}], "closed_edges": [101]}))   # 정상 가는 길 폐쇄
    r3 = run("t", z, pkg, ap, tmp_path / "out", FlatDem())
    assert not r3["passed"] and r3["approvals"]["closed_edges"] == [101]
    g = json.loads((tmp_path / "out" / "t.graph.json").read_text())
    assert any(e["closed"] for e in g["edges"])


def test_cli_approve_and_close_edit_the_approvals_file(tmp_path, monkeypatch):
    monkeypatch.setattr(cli, "OUT", tmp_path)
    (tmp_path / "m.report.json").write_text(json.dumps({"candidates": [{"id": "gap-0-1-0", "kind": "gap", "note": "직선 40m", "coords": [[37.0, 127.0], [37.0001, 127.0]]}]}))
    cli.main(["approve", "m", "gap-0-1-0", "--note", "현장 확인"])
    cli.main(["close", "m", "123"]); cli.main(["close", "m", "123"]); cli.main(["close", "m", "7"])
    ap = json.loads((tmp_path / "m.approvals.json").read_text())
    assert ap["links"][0]["note"] == "현장 확인" and ap["closed_edges"] == [7, 123]
    cli.main(["open", "m", "7"])
    assert json.loads((tmp_path / "m.approvals.json").read_text())["closed_edges"] == [123]
    with pytest.raises(SystemExit):
        cli.main(["approve", "m", "nope"])


# ------------------------------------------------------------------ 실제 수락산 데이터 (SHP 가 있을 때만)
REAL_ZIP = REPO / "docs" / "mountain" / "113500201.zip"


@pytest.mark.skipif(not REAL_ZIP.exists(), reason="산림청 SHP 가 없는 환경")
def test_real_suraksan_graph_is_valid_for_the_course(tmp_path):
    r = run("suraksan", REAL_ZIP, REPO / "testdata" / "suraksan_package.json", tmp_path / "none.json", tmp_path / "out", FlatDem())
    assert r["passed"], r["reasons"]
    assert r["summary"]["components"] == 2 and [c["nodes"] for c in r["components"]] == [139, 16]
    assert r["course_on_graph"] == 1.0 and r["candidates"] and r["candidates"][0]["length_m"] < 60
    assert [p["component"] for p in r["key_points"]] == [0, 0, 0]
