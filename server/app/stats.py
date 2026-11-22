"""업로드된 산행 기록 → 통계 (총 시간, 정상까지, 휴식, 평균 페이스)."""
from .pace import haversine

STILL_MPS = 0.3      # 이보다 느리면 정지로 본다
REST_MIN_S = 60      # 이 시간 이상 정지해야 '휴식 1회'


def compute(track: list[list], events: list[dict]) -> dict:
    """track: [[t_ms, lat, lon, acc, progress], ...] (시간순), events: [{"t":ms,"key":..}]"""
    if len(track) < 2:
        raise ValueError("track needs at least 2 points")
    start, end = track[0][0], track[-1][0]
    total_s = max(0, round((end - start) / 1000))
    dist = 0.0
    rest_count = rest_s = 0
    run_start = None  # 정지 구간 시작 시각(ms)
    for prev, cur in zip(track, track[1:]):
        dt = (cur[0] - prev[0]) / 1000
        if dt <= 0:
            continue
        d = haversine((prev[1], prev[2]), (cur[1], cur[2]))
        dist += d
        if d / dt < STILL_MPS:
            if run_start is None:
                run_start = prev[0]
        elif run_start is not None:
            n = (prev[0] - run_start) / 1000  # 정지 쌍들이 prev 까지 이어졌다
            if n >= REST_MIN_S:
                rest_count += 1; rest_s += round(n)
            run_start = None
    if run_start is not None:
        n = (end - run_start) / 1000
        if n >= REST_MIN_S:
            rest_count += 1; rest_s += round(n)
    summit_t = next((e["t"] for e in events if e.get("key") == "summit"), None)
    moving_s = max(1, total_s - rest_s)
    return {
        "total_s": total_s,
        "summit_s": None if summit_t is None else max(0, round((summit_t - start) / 1000)),
        "rest_count": rest_count,
        "rest_s": rest_s,
        "distance_m": round(dist, 1),
        "avg_pace_s_per_km": round(moving_s / (dist / 1000), 1) if dist > 50 else None,
    }
