import bisect
import json
from datetime import date
from pathlib import Path

import pytest

from app import pace_model, sun
from app.pace import GRADE_EDGES, CoursePace, bin_index, factor_for, tobler_mps

REPO = Path(__file__).resolve().parents[2]
POLY = json.loads((REPO / "testdata" / "suraksan_package.json").read_text())["course"]["polyline"]
COURSE = "suraksan-station-summit"
T0 = 1_700_000_000_000

# 정답을 아는 개인 모델: 오르막일수록 Tobler 보다 느리고 내리막은 조심스럽게(느리게)
TRUE = {"grade_bins": {"edges": GRADE_EDGES, "factors": [1.5, 1.35, 1.2, 1.0, 1.15, 1.35, 1.6]}}


def walk(poly, model, rest_at_s=None, rest_s=0, dt=1):
    """model 의 시간표대로 걷는 기록 [[t_ms, lat, lon, acc, progress], ...] (정답 데이터)"""
    cp = CoursePace(poly, model)
    out, t, extra = [], 0.0, 0.0
    total = cp.total
    while t <= total:
        k = bisect.bisect_right(cp.time_cum, t) - 1
        k = min(k, len(cp.cum) - 2)
        span = cp.time_cum[k + 1] - cp.time_cum[k]
        f = 0 if span <= 0 else (t - cp.time_cum[k]) / span
        d = cp.cum[k] + (cp.cum[k + 1] - cp.cum[k]) * f
        a, b = poly[k], poly[k + 1]
        lat, lon = a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f
        out.append([T0 + int((t + extra) * 1000), lat, lon, 5.0, d])
        if rest_at_s is not None and t >= rest_at_s and rest_s:     # 제자리 휴식
            for _ in range(rest_s // dt):
                extra += dt
                out.append([T0 + int((t + extra) * 1000), lat, lon, 5.0, d])
            rest_s = 0
        t += dt
    return out


def test_grade_bins():
    assert [bin_index(g) for g in (-0.5, -0.30, -0.2, -0.1, 0.0, 0.1, 0.2, 0.5)] == [0, 1, 1, 2, 3, 4, 5, 6]
    assert factor_for(0.0, None) == 1.0
    assert factor_for(0.0, TRUE) == 1.0 and factor_for(0.2, TRUE) == 1.35


def test_model_changes_course_time_by_slope():
    base = CoursePace(POLY).total
    slow = CoursePace(POLY, TRUE).total
    assert slow > base * 1.1                      # 모든 bin 이 1.0 이상이라 느려진다
    assert CoursePace(POLY, None).total == pytest.approx(base)


def test_learning_recovers_known_factors():
    m = pace_model.learn([(POLY, walk(POLY, TRUE))])
    assert m["n_hikes"] == 1 and m["type"] == "tobler_personal"
    true_f = TRUE["grade_bins"]["factors"]
    got = m["grade_bins"]["factors"]
    # 20초 창으로 경사를 분류하면 이웃 bin 과 조금 섞이고 사전 정보가 극단 bin 을 당기므로 bin 별 오차는 20% 까지 허용
    for k, f in enumerate(got):
        if m["samples_s"][k] >= 1000:
            assert f == pytest.approx(true_f[k], rel=0.20), f"bin {k}: 학습 {f} vs 정답 {true_f[k]}"
    # 구조: 평탄이 가장 빠르고(계수 최소) 양쪽으로 가파를수록 느리다
    assert got[3] == min(got) and got[0] > got[2] > got[3] < got[4] < got[6]
    # 모델의 목적인 총 예상 시간은 정답과 3% 이내
    assert CoursePace(POLY, m).total == pytest.approx(CoursePace(POLY, TRUE).total, rel=0.03)


def test_rest_longer_than_60s_is_excluded():
    plain = pace_model.learn([(POLY, walk(POLY, TRUE))])
    rested = pace_model.learn([(POLY, walk(POLY, TRUE, rest_at_s=1800, rest_s=600))])   # 10분 휴식
    for a, b in zip(plain["grade_bins"]["factors"], rested["grade_bins"]["factors"]):
        assert b == pytest.approx(a, rel=0.03)
    assert rested["factor"] == pytest.approx(plain["factor"], rel=0.02)


def test_rest_if_not_excluded_would_distort():
    """제외 로직이 없다면 휴식이 계수를 크게 올린다 → 위 테스트가 의미 있음을 보장"""
    cp = CoursePace(POLY, TRUE)
    naive = (cp.total + 600) / CoursePace(POLY).total
    assert naive > pace_model.learn([(POLY, walk(POLY, TRUE))])["factor"] * 1.05


def test_few_data_is_shrunk_toward_one():
    short = [p for p in walk(POLY, TRUE) if p[0] - T0 < 90_000]            # 90초치만
    m = pace_model.learn([(POLY, short)])
    assert all(0.99 <= f <= 1.3 for f in m["grade_bins"]["factors"]), m["grade_bins"]["factors"]


def test_unusable_tracks_give_no_model():
    assert pace_model.learn([]) is None
    no_progress = [[T0 + i * 1000, 37.0, 127.0, 5.0, None] for i in range(100)]
    assert pace_model.learn([(POLY, no_progress)]) is None


def test_gps_gaps_are_not_counted():
    tr = walk(POLY, TRUE)
    gappy = [p for i, p in enumerate(tr) if not (500 < i < 900)]            # 400초 끊김
    m = pace_model.learn([(POLY, gappy)])
    assert m["grade_bins"]["factors"][3] == pytest.approx(1.0, rel=0.15)


def test_typical_summit():
    assert pace_model.typical_summit_s([4000, None, 4400, 4200]) == 4200
    assert pace_model.typical_summit_s([None]) is None


# ---- 일몰 ----
def test_sunset_matches_spec_example_and_seasons():
    assert sun.sunset_iso(37.687, 127.075, date(2026, 11, 21)).startswith("2026-11-21T17:1")   # 명세 예시 17:16
    assert sun.sunset_iso(37.687, 127.075, date(2026, 11, 21)).endswith("+09:00")
    summer = sun.sunset_iso(37.687, 127.075, date(2026, 6, 21))
    assert summer[11:13] in ("19", "20")                                                       # 하지: 늦은 일몰


# ---- 서버 통합 ----
def _upload(client, auth, model=None, speed=None):
    pkg = client.get(f"/api/courses/{COURSE}/package", headers=auth).json()
    poly = pkg["course"]["polyline"]
    track = walk(poly, model or TRUE)
    summit_d = CoursePace(poly).cum[pkg["course"]["summit_index"]]     # 한 번만 계산
    summit_t = next(p[0] for p in track if p[4] >= summit_d)
    body = {"course_id": COURSE, "track": track, "steps": 100,
            "events": [{"t": track[0][0], "key": "start", "text": "s"}, {"t": summit_t, "key": "summit", "text": "정상"}]}
    return client.post("/api/hikes", json=body, headers=auth), summit_t - track[0][0]


def test_no_model_before_any_hike(client, auth):
    assert client.get("/api/pace-model", headers=auth).json()["type"] == "tobler"
    pkg = client.get(f"/api/courses/{COURSE}/package", headers=auth).json()
    assert "pace_model" not in pkg and pkg["sunset"].endswith("+09:00")
    cs = client.get("/api/mountains/suraksan/courses", headers=auth).json()
    assert cs[0]["my_minutes"] is None


def test_upload_retrains_and_package_carries_model(client, auth):
    r, summit_ms = _upload(client, auth)
    assert r.status_code == 201
    m = client.get("/api/pace-model", headers=auth).json()
    assert m["n_hikes"] == 1 and m["factor"] > 1.05
    pkg = client.get(f"/api/courses/{COURSE}/package", headers=auth).json()
    pm = pkg["pace_model"]
    assert pm["type"] == "tobler_personal" and len(pm["grade_bins"]["factors"]) == len(pm["grade_bins"]["edges"]) + 1
    assert pm["typical_summit_s"] == pytest.approx(summit_ms / 1000, abs=2)
    cs = client.get("/api/mountains/suraksan/courses", headers=auth).json()
    assert cs[0]["my_minutes"] > cs[0]["std_minutes"]                # 정답 모델은 Tobler 보다 느린 사람

    # 같은 기록을 다시 올려도 모델이 두 배로 학습되지 않는다
    again, _ = _upload(client, auth)
    assert again.status_code == 200 and client.get("/api/pace-model", headers=auth).json()["n_hikes"] == 1


def test_package_date_param(client, auth):
    a = client.get(f"/api/courses/{COURSE}/package?date=2026-06-21", headers=auth).json()["sunset"]
    b = client.get(f"/api/courses/{COURSE}/package?date=2026-12-21", headers=auth).json()["sunset"]
    assert a.startswith("2026-06-21T19") and b.startswith("2026-12-21T17")
    assert client.get(f"/api/courses/{COURSE}/package?date=nope", headers=auth).status_code == 422


def test_web_pages_show_my_time_sunset_and_map(client, auth):
    _upload(client, auth)
    page = client.get(f"/courses/{COURSE}", headers=auth).text
    assert "내 예상" in page and "일몰" in page
    assert "내 예상" in client.get("/mountains/suraksan", headers=auth).text
    idx = client.get("/", headers=auth).text
    assert 'id="map"' in idx and "suraksan" in idx
