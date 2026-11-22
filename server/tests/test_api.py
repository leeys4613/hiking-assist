import json

import pytest

from app import config
from app.pace import haversine

COURSE = "suraksan-station-summit"


def _package(client, auth):
    return client.get(f"/api/courses/{COURSE}/package", headers=auth).json()


def _walk(pkg, start_ms=1_700_000_000_000, rest_at=None, rest_s=0):
    """패키지 polyline 위를 1.0m/s 로 걷는 기록. [t_ms, lat, lon, acc, progress]"""
    pl = pkg["course"]["polyline"]
    pts, t, d = [], start_ms, 0.0
    for a, b in zip(pl, pl[1:]):
        seg = haversine(a, b)
        pts.append([t, a[0], a[1], 8.0, d])
        d += seg
        t += int(seg * 1000)
        if rest_at is not None and d >= rest_at and rest_s:
            for k in range(rest_s // 10):          # 제자리 휴식(10초마다 fix)
                t += 10_000
                pts.append([t, b[0], b[1], 8.0, d])
            rest_s = 0
    pts.append([t, pl[-1][0], pl[-1][1], 8.0, d])
    return pts


# ---- 인증 ----
@pytest.mark.parametrize("path", ["/health", "/api/mountains", f"/api/courses/{COURSE}/package", "/api/hikes?course_id=x"])
def test_requires_token(client, path):
    assert client.get(path).status_code == 401


def test_wrong_token_and_header_variants(client):
    assert client.get("/health", headers={"Authorization": "Bearer nope"}).status_code == 401
    assert client.get("/health", headers={"Authorization": "Bearer test-token-123"}).status_code == 200
    assert client.get("/health", headers={"X-Hiking-Token": "test-token-123"}).status_code == 200


def test_empty_server_token_denies_everything(client, monkeypatch):
    monkeypatch.setattr(config, "TOKEN", "")
    assert client.get("/health").status_code == 401
    assert client.get("/health", headers={"Authorization": "Bearer "}).status_code == 401


def test_browser_flow_redirects_then_cookie_login(client):
    r = client.get("/", headers={"accept": "text/html"}, follow_redirects=False)
    assert r.status_code == 303 and r.headers["location"] == "/login"
    assert client.post("/login", content="token=bad", headers={"content-type": "application/x-www-form-urlencoded"}).status_code == 401
    r = client.post("/login", content="token=test-token-123", headers={"content-type": "application/x-www-form-urlencoded"}, follow_redirects=False)
    assert r.status_code == 303 and "hiking_token" in r.headers["set-cookie"] and "HttpOnly" in r.headers["set-cookie"]
    assert client.get("/").status_code == 200   # 쿠키가 TestClient 에 저장됨


def test_openapi_docs_not_exposed(client, auth):
    for p in ("/docs", "/redoc", "/openapi.json"):
        assert client.get(p, headers=auth).status_code == 404


# ---- 코스/패키지 ----
def test_mountains_and_courses(client, auth):
    ms = client.get("/api/mountains", headers=auth).json()
    assert [m["name"] for m in ms] == ["수락산"] and ms[0]["visited"] is False
    cs = client.get("/api/mountains/suraksan/courses", headers=auth).json()
    assert cs[0]["id"] == COURSE and 8500 < cs[0]["length_m"] < 9000 and 600 < cs[0]["ascent_m"] < 800
    assert 120 < cs[0]["std_minutes"] < 260
    assert client.get("/api/mountains/none/courses", headers=auth).status_code == 404


def test_package_matches_spec_5_1(client, auth):
    pkg = _package(client, auth)
    assert pkg["package_version"] == 1 and pkg["course"]["summit_index"] < pkg["course"]["end_index"]
    assert len(pkg["course"]["polyline"][0]) == 3 and pkg["pois"]
    assert client.get("/api/courses/none/package", headers=auth).status_code == 404


# ---- 기록 업로드 ----
def test_upload_is_idempotent_and_shows_up(client, auth):
    pkg = _package(client, auth)
    track = _walk(pkg, rest_at=4000, rest_s=300)
    summit_t = track[len(track) // 2][0]
    body = {"course_id": COURSE, "track": track, "steps": 12345,
            "events": [{"t": track[0][0], "key": "start", "text": "시작"}, {"t": summit_t, "key": "summit", "text": "정상"}]}
    r = client.post("/api/hikes", json=body, headers=auth)
    assert r.status_code == 201, r.text
    h = r.json()
    assert h["duplicate"] is False and h["steps"] == 12345
    assert 8000 < h["distance_m"] < 9400
    assert h["rest_count"] == 1 and 280 <= h["rest_s"] <= 320
    assert h["summit_s"] == round((summit_t - track[0][0]) / 1000)
    assert 1000 / 1.2 < h["avg_pace_s_per_km"] < 1000 / 0.8     # 1m/s 로 걸었으므로 약 1000초/km

    again = client.post("/api/hikes", json=body, headers=auth)   # WorkManager 재시도 시나리오
    assert again.status_code == 200 and again.json()["duplicate"] is True
    lst = client.get(f"/api/hikes?course_id={COURSE}", headers=auth).json()
    assert len(lst) == 1
    m = client.get("/api/mountains", headers=auth).json()[0]
    assert m["visited"] is True and m["visits"] == 1


def test_upload_validation(client, auth):
    assert client.post("/api/hikes", json={"course_id": "nope", "track": [[1, 1, 1, 1, 1], [2, 1, 1, 1, 1]]}, headers=auth).status_code == 404
    assert client.post("/api/hikes", json={"course_id": COURSE, "track": [[1, 1, 1, 1, 1]]}, headers=auth).status_code == 422
    assert client.post("/api/hikes", json={"course_id": COURSE, "track": [[1, 1, 1, 1, 1], [2, 1, 1, 1, 1]]}).status_code == 401


def test_gpx_export_and_pages(client, auth):
    pkg = _package(client, auth)
    h = client.post("/api/hikes", json={"course_id": COURSE, "track": _walk(pkg)}, headers=auth).json()
    gpx = client.get(f"/api/hikes/{h['id']}/gpx", headers=auth)
    assert gpx.status_code == 200 and "<trkpt" in gpx.text

    idx = client.get("/", headers=auth)
    assert "수락산" in idx.text and "✓" in idx.text
    course = client.get(f"/courses/{COURSE}", headers=auth)
    assert course.status_code == 200 and "이 코스로 출발" in course.text and "window.HikeApp" in course.text
    data = json.loads(course.text.split('type="application/json">')[1].split("</script>")[0])
    assert len(data["polyline"]) > 2000 and len(data["hikes"]) == 1
    assert client.get("/mountains/suraksan", headers=auth).status_code == 200
