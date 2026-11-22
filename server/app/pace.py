"""보행 시간 모델: Tobler 기본 + 경사 bin 별 개인 보정(명세 §8). android/guidance-core 의 Course 와 같은 계산이다."""
import bisect
import math

# 경사(상승/수평거리, 오르막 +) bin 경계. 7개 bin: (-inf,-0.30) [-0.30,-0.15) [-0.15,-0.05) [-0.05,0.05) [0.05,0.15) [0.15,0.30) [0.30,inf)
GRADE_EDGES = [-0.30, -0.15, -0.05, 0.05, 0.15, 0.30]


def haversine(a, b) -> float:
    r = 6371008.8
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp, dl = p2 - p1, math.radians(b[1] - a[1])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def tobler_mps(grade: float) -> float:
    return 6.0 * math.exp(-3.5 * abs(grade + 0.05)) / 3.6


def bin_index(grade: float) -> int:
    return bisect.bisect_right(GRADE_EDGES, grade)


def factor_for(grade: float, model: dict | None) -> float:
    """개인 보정 계수. 모델이 없으면 1.0 (순수 Tobler)"""
    if not model:
        return 1.0
    return model["grade_bins"]["factors"][bin_index(grade)]


class CoursePace:
    """polyline [[lat, lon, ele], ...] 위의 구간별 예상 시간. 구간 중앙 ±25m 의 평균 경사를 쓴다(DEM 노이즈 완화)."""

    def __init__(self, polyline, model: dict | None = None):
        self.poly = polyline
        self._ele = [p[2] for p in polyline]   # 호출마다 만들지 않는다(O(n^2) 방지)
        self.cum = [0.0]
        for a, b in zip(polyline, polyline[1:]):
            self.cum.append(self.cum[-1] + haversine(a, b))
        self.length = self.cum[-1]
        self.time_cum = [0.0]
        for i in range(1, len(self.cum)):
            seg = self.cum[i] - self.cum[i - 1]
            mid = (self.cum[i] + self.cum[i - 1]) / 2
            span = max(1.0, min(mid + 25, self.length) - max(mid - 25, 0.0))
            g = (self.ele_at(mid + 25) - self.ele_at(mid - 25)) / span
            self.time_cum.append(self.time_cum[-1] + seg / tobler_mps(g) * factor_for(g, model))

    def _interp(self, arr, d):
        d = min(max(d, 0.0), self.length)
        lo, hi = 0, len(self.cum) - 1
        while hi - lo > 1:
            m = (lo + hi) // 2
            if self.cum[m] <= d:
                lo = m
            else:
                hi = m
        span = self.cum[hi] - self.cum[lo]
        f = 0.0 if span <= 0 else (d - self.cum[lo]) / span
        return arr[lo] + (arr[hi] - arr[lo]) * f

    def ele_at(self, d: float) -> float:
        return self._interp(self._ele, d)

    def eta(self, a: float, b: float) -> float:
        """a 에서 b 까지(a<b) 걷는 예상 시간(초)"""
        return 0.0 if b <= a else self._interp(self.time_cum, b) - self._interp(self.time_cum, a)

    @property
    def total(self) -> float:
        return self.time_cum[-1]


def course_metrics(polyline: list[list[float]]) -> dict:
    """길이, 누적 상승/하강, 기본(Tobler) 예상 소요(초)."""
    cp = CoursePace(polyline)
    up = sum(max(0.0, b[2] - a[2]) for a, b in zip(polyline, polyline[1:]))
    down = sum(max(0.0, a[2] - b[2]) for a, b in zip(polyline, polyline[1:]))
    return {"length_m": cp.length, "ascent_m": up, "descent_m": down, "eta_s": cp.total}
