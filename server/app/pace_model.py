"""개인 페이스 학습(명세 §8): 기록을 경사 bin 별로 나눠 (실제 시간 / Tobler 시간) 을 구한다.
- 60초 이상 쉰 구간은 제외한다(휴식은 걷는 속도가 아님).
- 데이터가 적은 bin 은 전체 평균 쪽으로, 전체 평균은 1.0(순수 Tobler) 쪽으로 당긴다(PRIOR_S 초 분량의 사전 정보)."""
from statistics import median

from .pace import GRADE_EDGES, CoursePace, bin_index

WINDOW_S = 20          # 이 시간 이상으로 묶어서 속도를 본다(GPS 노이즈 완화)
GAP_S = 120            # 표본 사이가 이보다 벌어지면(GPS 끊김) 그 창은 버린다
SLOW_MPS = 0.2         # 창 평균 속도가 이보다 느리면 '정지/휴식 후보'
REST_MIN_S = 60        # 후보가 이만큼 이어지면 휴식으로 보고 제외
MIN_DP_M = 5           # 이보다 조금 간 창은 경사를 못 구하므로 제외
PRIOR_S = 300.0
FACTOR_MIN, FACTOR_MAX = 0.5, 2.5


def _windows(track):
    """[[t_ms, lat, lon, acc, progress], ...] → [(t0_s, t1_s, p0, p1)]"""
    pts = [(p[0] / 1000.0, p[4]) for p in track if p[4] is not None]
    pts.sort()
    out, i = [], 0
    while i < len(pts) - 1:
        j = i + 1
        while j < len(pts) - 1 and pts[j][0] - pts[i][0] < WINDOW_S:
            j += 1
        dt = pts[j][0] - pts[i][0]
        if WINDOW_S <= dt <= GAP_S:
            out.append((pts[i][0], pts[j][0], pts[i][1], pts[j][1]))
        i = j if dt >= WINDOW_S else i + 1
    return out


def collect(polyline, track):
    """한 번의 산행에서 bin 별 (실제초, Tobler초) 를 모은다."""
    base = CoursePace(polyline)   # 보정 없는 Tobler 기준
    wins = _windows(track)
    # 휴식 구간 표시: 느린 창이 REST_MIN_S 이상 연속이면 제외
    slow = [(w[3] - w[2]) / (w[1] - w[0]) < SLOW_MPS for w in wins]
    rest = [False] * len(wins)
    i = 0
    while i < len(wins):
        if not slow[i]:
            i += 1
            continue
        j = i
        while j + 1 < len(wins) and slow[j + 1] and abs(wins[j + 1][0] - wins[j][1]) < 1.0:
            j += 1
        if wins[j][1] - wins[i][0] >= REST_MIN_S:
            for k in range(i, j + 1):
                rest[k] = True
        i = j + 1
    actual = [0.0] * (len(GRADE_EDGES) + 1)
    tobler = [0.0] * (len(GRADE_EDGES) + 1)
    for w, is_rest in zip(wins, rest):
        t0, t1, p0, p1 = w
        dp = p1 - p0
        if is_rest or dp < MIN_DP_M:
            continue
        g = (base.ele_at(p1) - base.ele_at(p0)) / dp
        b = bin_index(g)
        actual[b] += t1 - t0
        tobler[b] += base.eta(p0, p1)
    return actual, tobler


def learn(hikes) -> dict | None:
    """hikes: [(polyline, track), ...] → pace_model 딕셔너리. 쓸 수 있는 구간이 없으면 None"""
    n = len(GRADE_EDGES) + 1
    A, T = [0.0] * n, [0.0] * n
    for polyline, track in hikes:
        a, t = collect(polyline, track)
        for k in range(n):
            A[k] += a[k]
            T[k] += t[k]
    if sum(T) <= 0:
        return None
    clamp = lambda x: max(FACTOR_MIN, min(FACTOR_MAX, x))
    g = clamp((sum(A) + PRIOR_S) / (sum(T) + PRIOR_S))
    factors = [round(clamp((A[k] + PRIOR_S * g) / (T[k] + PRIOR_S)), 4) for k in range(n)]
    return {
        "type": "tobler_personal",
        "factor": round(g, 4),
        "grade_bins": {"edges": GRADE_EDGES, "factors": factors},
        "samples_s": [round(x) for x in T],
        "n_hikes": len(hikes),
    }


def typical_summit_s(summit_times: list[int | None]) -> int | None:
    xs = [x for x in summit_times if x is not None]
    return round(median(xs)) if xs else None
