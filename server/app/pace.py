"""Tobler 보행 함수 기반 예상 시간 (guidance-core 의 Course.etaSeconds 와 같은 식)."""
import math


def haversine(a, b) -> float:
    r = 6371008.8
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp, dl = p2 - p1, math.radians(b[1] - a[1])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def tobler_mps(grade: float) -> float:
    return 6.0 * math.exp(-3.5 * abs(grade + 0.05)) / 3.6


def course_metrics(polyline: list[list[float]]) -> dict:
    """polyline [[lat, lon, ele], ...] → 길이, 누적 상승/하강, 예상 소요(초)."""
    cum = [0.0]
    for a, b in zip(polyline, polyline[1:]):
        cum.append(cum[-1] + haversine(a, b))
    length = cum[-1]

    def ele_at(d: float) -> float:
        d = min(max(d, 0.0), length)
        lo, hi = 0, len(cum) - 1
        while hi - lo > 1:
            m = (lo + hi) // 2
            if cum[m] <= d:
                lo = m
            else:
                hi = m
        span = cum[hi] - cum[lo]
        f = 0.0 if span <= 0 else (d - cum[lo]) / span
        return polyline[lo][2] + (polyline[hi][2] - polyline[lo][2]) * f

    seconds = 0.0
    for i in range(1, len(cum)):
        seg = cum[i] - cum[i - 1]
        mid = (cum[i] + cum[i - 1]) / 2
        span = max(1.0, min(mid + 25, length) - max(mid - 25, 0.0))
        g = (ele_at(mid + 25) - ele_at(mid - 25)) / span
        seconds += seg / tobler_mps(g)
    up = sum(max(0.0, b[2] - a[2]) for a, b in zip(polyline, polyline[1:]))
    down = sum(max(0.0, a[2] - b[2]) for a, b in zip(polyline, polyline[1:]))
    return {"length_m": length, "ascent_m": up, "descent_m": down, "eta_s": seconds}
