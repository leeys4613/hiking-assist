from app import stats

LAT0, LON0 = 37.68, 127.06
M = 1 / 111_000   # 위도 1m ≈ 1/111000도


def pts(spec):
    """spec: [(dt_s, meters_north)] 누적 → track"""
    t, y, out = 1_000_000, 0.0, []
    out.append([t, LAT0, LON0, 5.0, 0.0])
    for dt, dy in spec:
        t += dt * 1000; y += dy
        out.append([t, LAT0 + y * M, LON0, 5.0, y])
    return out


def test_no_rest_when_always_moving():
    s = stats.compute(pts([(10, 10)] * 30), [])
    assert s["rest_count"] == 0 and s["rest_s"] == 0 and s["total_s"] == 300
    assert 295 < s["distance_m"] < 305


def test_short_stop_under_60s_is_not_a_rest():
    s = stats.compute(pts([(10, 10)] * 5 + [(10, 0)] * 4 + [(10, 10)] * 5), [])
    assert s["rest_count"] == 0


def test_rest_counted_with_duration():
    s = stats.compute(pts([(10, 10)] * 5 + [(10, 0)] * 12 + [(10, 10)] * 5), [])
    assert s["rest_count"] == 1 and s["rest_s"] == 120


def test_rest_at_end_and_two_rests():
    s = stats.compute(pts([(10, 10)] * 3 + [(10, 0)] * 8 + [(10, 10)] * 3 + [(10, 0)] * 9), [])
    assert s["rest_count"] == 2


def test_summit_time_from_events():
    tr = pts([(10, 10)] * 10)
    s = stats.compute(tr, [{"t": tr[0][0] + 45_000, "key": "summit"}])
    assert s["summit_s"] == 45


def test_pace_excludes_rest():
    s = stats.compute(pts([(10, 10)] * 6 + [(10, 0)] * 12 + [(10, 10)] * 6), [])
    assert 950 < s["avg_pace_s_per_km"] < 1050     # 1m/s → 1000 s/km, 휴식 120초는 제외
