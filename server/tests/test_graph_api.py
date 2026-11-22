import json

import pytest

from app import seed
from app.models import GraphState

COURSE = "suraksan-station-summit"


def pkg(client, auth):
    return client.get(f"/api/courses/{COURSE}/package", headers=auth).json()


def test_verified_mountain_gets_graph_in_package(client, auth):
    p = pkg(client, auth)
    g = p["graph"]
    assert len(g["nodes"]) > 100 and len(g["edges"]) > 150
    n, e = g["nodes"][0], g["edges"][0]
    assert {"id", "lat", "lon", "ele", "type"} <= set(n) and isinstance(n["id"], int)
    assert {"id", "from", "to", "geometry", "length_m", "ascent_m", "descent_m", "closed"} <= set(e)
    assert all(len(pt) == 3 for ed in g["edges"] for pt in ed["geometry"])
    ids = {x["id"] for x in g["nodes"]}
    assert all(ed["from"] in ids and ed["to"] in ids for ed in g["edges"])         # 끊긴 참조 없음
    assert all(ed["id"] < 2**31 for ed in g["edges"])                                 # Kotlin Int
    types = {x["type"] for x in g["nodes"]}
    assert {"summit", "trailhead"} <= types


def test_mountains_list_exposes_reroute_flag(client, auth):
    assert client.get("/api/mountains", headers=auth).json()[0]["reroute"] is True


def test_graph_report_endpoint(client, auth):
    r = client.get("/api/mountains/suraksan/graph-report", headers=auth).json()
    assert r["passed"] is True and r["course_on_graph"] == 1.0
    assert [c["nodes"] for c in r["components"]] == [139, 16]
    assert r["candidates"] and r["candidates"][0]["kind"] == "gap"
    assert client.get("/api/mountains/none/graph-report", headers=auth).status_code == 404
    assert client.get("/api/mountains/suraksan/graph-report").status_code == 401


def test_pages_show_status_and_review_map(client, auth):
    page = client.get("/mountains/suraksan", headers=auth).text
    assert "재경로" in page and "검증 통과" in page and "그래프 검토 지도" in page
    g = client.get("/mountains/suraksan/graph", headers=auth)
    assert g.status_code == 200 and "막다른 끝" in g.text and "끊긴 지점 후보" in g.text and "gap-0-1-0" in g.text
    assert client.get("/mountains/none/graph", headers=auth).status_code == 404


def _replace_graph(client, tmp_path, passed: bool, reasons=()):
    """검증 결과를 바꾼 산출물로 다시 로드(AC8 시험)"""
    real = json.loads((seed.config.GRAPH_DIR / "suraksan.report.json").read_text())
    real["passed"], real["reasons"] = passed, list(reasons)
    (tmp_path / "suraksan.report.json").write_text(json.dumps(real))
    (tmp_path / "suraksan.graph.json").write_text((seed.config.GRAPH_DIR / "suraksan.graph.json").read_text())
    with client.factory() as db:
        seed.load_graphs(db, tmp_path)


def test_failed_validation_disables_reroute_and_hides_graph(client, auth, tmp_path):
    _replace_graph(client, tmp_path, passed=False, reasons=["시작·정상·종점이 서로 다른 연결 요소에 있음 → 길이 끊겨 있음"])
    assert "graph" not in pkg(client, auth)                                           # AC8: 앱은 그래프를 받지 못한다
    assert client.get("/api/mountains", headers=auth).json()[0]["reroute"] is False
    page = client.get("/mountains/suraksan", headers=auth).text
    assert "사용 안 함" in page and "다른 연결 요소" in page and "검증 통과" not in page
    assert "실패" in client.get("/mountains/suraksan/graph", headers=auth).text


def test_reverification_restores_reroute(client, auth, tmp_path):
    _replace_graph(client, tmp_path, passed=False)
    assert "graph" not in pkg(client, auth)
    _replace_graph(client, tmp_path, passed=True)
    assert "graph" in pkg(client, auth)


def test_mountain_without_graph_files_has_no_graph(client, auth):
    with client.factory() as db:
        db.query(GraphState).delete(); db.commit()
    assert "graph" not in pkg(client, auth)
    assert client.get("/api/mountains/suraksan/graph-report", headers=auth).status_code == 404
    assert "재경로" not in client.get("/mountains/suraksan", headers=auth).text


def test_package_with_graph_is_still_a_valid_course_package(client, auth):
    p = pkg(client, auth)
    assert p["package_version"] == 1 and p["course"]["summit_index"] < p["course"]["end_index"] and p["pois"] and p["sunset"]
