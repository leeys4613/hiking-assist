"""합성 GPX 시나리오 생성기 (명세 §9). 코스 polyline 에 노이즈 / 점프 / 이탈을 주입해
testdata/scenarios/ 에 <이름>.gpx + <이름>.json(기대 이벤트) 를 만든다.

기대 이벤트는 guidance-core 코드를 쓰지 않고, 생성한 좌표의 실제 기하(코스까지 거리)와
명세 §5.3 의 규칙(30m/15초, 15m/10초)으로 이 파일 안에서 따로 계산한다 → Kotlin 구현과 교차 검증된다.

사용: server/.venv/bin/python -I server/tools/gen_scenarios.py
"""
import json
import math
import random
from datetime import datetime, timedelta, timezone
from pathlib import Path

from pyproj import Transformer
from shapely.geometry import LineString, Point

REPO = Path(__file__).resolve().parents[2]
OUT = REPO / "testdata" / "scenarios"
KST = timezone(timedelta(hours=9))
SPEED = 1.2          # m/s
NOISE = 3.0          # m, 균일 ±
WATCH = ["off_course", "back", "alt_route", "off_trail", "no_route", "sunset", "summit", "descent", "end"]


# ------------------------------------------------------------------ 좌표계
class RealFrame:
    """수락산: 4326 ↔ EPSG:5186(미터)"""
    def __init__(self):
        self.f = Transformer.from_crs("EPSG:4326", "EPSG:5186", always_xy=True)
        self.b = Transformer.from_crs("EPSG:5186", "EPSG:4326", always_xy=True)

    def xy(self, lat, lon): return self.f.transform(lon, lat)
    def ll(self, x, y):
        lon, lat = self.b.transform(x, y); return lat, lon


class EnuFrame:
    """합성 산: 원점 기준 동쪽 x(m), 북쪽 y(m). Kotlin RerouterTest 와 같은 변환"""
    LAT0, LON0 = 37.69, 127.08
    M = math.pi / 180 * 6371008.8

    def xy(self, lat, lon): return (lon - self.LON0) * self.M * math.cos(math.radians(self.LAT0)), (lat - self.LAT0) * self.M
    def ll(self, x, y): return self.LAT0 + y / self.M, self.LON0 + x / (self.M * math.cos(math.radians(self.LAT0)))


class Path:
    def __init__(self, pts):
        self.pts = [tuple(p) for p in pts]
        self.cum = [0.0]
        for a, b in zip(self.pts, self.pts[1:]):
            self.cum.append(self.cum[-1] + math.dist(a, b))
        self.length = self.cum[-1]
        self.line = LineString(self.pts)

    def at(self, d):
        d = min(max(d, 0.0), self.length)
        i = max(0, min(len(self.cum) - 2, next((k for k in range(len(self.cum) - 1) if self.cum[k + 1] >= d), len(self.cum) - 2)))
        span = self.cum[i + 1] - self.cum[i]
        f = 0 if span == 0 else (d - self.cum[i]) / span
        a, b = self.pts[i], self.pts[i + 1]
        return a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f

    def normal(self, d):
        a, b = self.at(max(0, d - 5)), self.at(min(self.length, d + 5))
        dx, dy = b[0] - a[0], b[1] - a[1]
        n = math.hypot(dx, dy) or 1
        return -dy / n, dx / n


# ------------------------------------------------------------------ 표본 / GPX
def finalize(samples, frame, seed, noise=NOISE):
    rnd = random.Random(seed)
    for s in samples:
        s["x"] += rnd.uniform(-noise, noise)
        s["y"] += rnd.uniform(-noise, noise)
    return samples


def write_gpx(path, samples, frame, start):
    lines = ['<?xml version="1.0" encoding="UTF-8"?>',
             '<gpx version="1.1" creator="gen_scenarios.py" xmlns="http://www.topografix.com/GPX/1/1"><trk><trkseg>']
    for s in samples:
        la, lo = frame.ll(s["x"], s["y"])
        t = (start + timedelta(seconds=s["t"])).astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        lines.append(f'<trkpt lat="{la:.7f}" lon="{lo:.7f}"><time>{t}</time><hdop>{s.get("acc", 8.0)}</hdop></trkpt>')
    lines.append("</trkseg></trk></gpx>")
    path.write_text("\n".join(lines) + "\n")


def detector(times, dists, off=30, off_hold=15, on=15, on_hold=10):
    """명세 §5.3 을 그대로 옮긴 독립 구현. (t, 'off'|'back') 목록과 각 시각의 상태를 돌려준다"""
    st, since, ev, states = "ON", None, [], []
    for t, d in zip(times, dists):
        if st == "ON":
            if d > off: st, since = "SUSPECT", t
        elif st == "SUSPECT":
            if d <= off: st = "ON"
            elif t - since >= off_hold: st = "OFF"; ev.append((t, "off"))
        elif st == "OFF":
            if d <= on: st, since = "REC", t
        elif st == "REC":
            if d > on: st = "OFF"
            elif t - since >= on_hold: st = "ON"; ev.append((t, "back"))
        states.append(st)
    return ev, states


def seg_walk(samples, t, path_pts, speed, dt, jitter_only=False):
    """점 목록을 따라 걷는다. 마지막 시각을 돌려준다"""
    for a, b in zip(path_pts, path_pts[1:]):
        L = math.dist(a, b)
        n = max(1, int(L / (speed * dt)))
        for i in range(1, n + 1):
            f = i / n
            t += L / n / speed
            samples.append({"t": round(t, 3), "x": a[0] + (b[0] - a[0]) * f, "y": a[1] + (b[1] - a[1]) * f})
    return t


def rest(samples, t, pos, secs, dt):
    for _ in range(int(secs / dt)):
        t += dt
        samples.append({"t": round(t, 3), "x": pos[0], "y": pos[1]})
    return t


def resample(samples, dt):
    """걷기를 dt 간격으로 다시 뽑는다(선형 보간)"""
    out, t = [], samples[0]["t"]
    j = 0
    while t <= samples[-1]["t"]:
        while j < len(samples) - 2 and samples[j + 1]["t"] < t: j += 1
        a, b = samples[j], samples[j + 1]
        f = 0 if b["t"] == a["t"] else (t - a["t"]) / (b["t"] - a["t"])
        out.append({"t": round(t - samples[0]["t"], 3), "x": a["x"] + (b["x"] - a["x"]) * f, "y": a["y"] + (b["y"] - a["y"]) * f})
        t += dt
    return out


def save(name, pkg_file, frame, start, samples, expect, extra=None):
    write_gpx(OUT / f"{name}.gpx", samples, frame, start)
    meta = {"name": name, "package": pkg_file, "gpx": f"{name}.gpx", "watch": WATCH, "expect": expect}
    meta.update(extra or {})
    (OUT / f"{name}.json").write_text(json.dumps(meta, ensure_ascii=False, indent=1) + "\n")
    print(f"{name:14s} {len(samples):5d} fixes, expect {[(e['type'], e.get('atS')) for e in expect]}")


# ------------------------------------------------------------------ 수락산(실코스) 시나리오
def real_scenarios():
    frame = RealFrame()
    pkg = json.loads((REPO / "testdata" / "suraksan_package.json").read_text())
    poly = pkg["course"]["polyline"]
    path = Path([frame.xy(p[0], p[1]) for p in poly])
    summit_xy = frame.xy(*poly[pkg["course"]["summit_index"]][:2])
    summit_d = path.cum[pkg["course"]["summit_index"]]
    start = datetime(2026, 11, 21, 9, 0, tzinfo=KST)
    sunset = "2026-11-21T17:16+09:00"

    # 1) 정상 완주: 오경보 0건. 정상 5분 휴식. 오전 출발이라 하산 권고도 없어야 한다
    dt = 3
    raw, t = [{"t": 0.0, "x": path.pts[0][0], "y": path.pts[0][1]}], 0.0
    idx_s = pkg["course"]["summit_index"]
    t = seg_walk(raw, t, path.pts[: idx_s + 1], SPEED, dt)
    t_arrive = t
    t = rest(raw, t, path.pts[idx_s], 300, dt)
    t = seg_walk(raw, t, path.pts[idx_s:], SPEED, dt)
    s = finalize(resample(raw, dt), frame, 1)
    first_summit = next(x["t"] for x in s if math.dist((x["x"], x["y"]), summit_xy) <= 30)
    t_desc = t_arrive + 300 + 30 / SPEED          # 정상 지점에서 30m 더 내려간 시각(참 위치 기준)
    t_end = t - 30 / SPEED
    save("clean_full", "suraksan_package.json", frame, start, s, [
        {"type": "summit", "atS": first_summit, "tolS": 8},
        {"type": "descent", "atS": t_desc, "tolS": 12},
        {"type": "end", "atS": t_end, "tolS": 12},
    ], {"sunset": sunset})

    # 직선 구간 찾기: 코스에서 법선 방향으로 40m 떨어진 점들이 코스 어느 곳과도 36m 이상 떨어진 곳
    def find_straight(from_d=300, to_d=3000):
        for d0 in range(from_d, to_d, 25):
            for side in (1, -1):
                ok = True
                for k in range(0, 80, 4):
                    x, y = path.at(d0 + k)
                    nx, ny = path.normal(d0 + k)
                    p = Point(x + side * 40 * nx, y + side * 40 * ny)
                    if path.line.distance(p) < 36: ok = False; break
                if ok: return d0, side
        raise SystemExit("직선 구간을 찾지 못함")

    d0, side = find_straight()

    # 2) GPS 튐: 순간 50m 점프 / 연속 3개 60m / 정확도 나쁜 먼 점 → 이탈 없음
    dt = 1
    raw, t = [], 0.0
    t_total = 110
    for i in range(t_total + 1):
        x, y = path.at(d0 + SPEED * i); raw.append({"t": float(i), "x": x, "y": y, "acc": 8.0})
    nx, ny = path.normal(d0)
    for i, off in ((30, 50), (60, 60), (61, 60), (62, 60)):
        raw[i]["x"] += side * off * nx; raw[i]["y"] += side * off * ny
    raw[80]["x"] += 300; raw[80]["acc"] = 90.0          # 정확도 90m → 무시되어야 함
    s = finalize(raw, frame, 2)
    save("gps_jump", "suraksan_package.json", frame, start, s, [])

    # 3) 실제 이탈: 40m 이탈 → 20초 유지 후 복귀. 이탈 1회 + 복귀 1회
    raw, tt = [], 0
    def lateral(i):   # i초의 옆으로 벌어진 거리
        if i < 40: return 0.0
        if i < 50: return 40.0 * (i - 40) / 10
        if i < 75: return 40.0
        if i < 85: return 40.0 * (85 - i) / 10
        return 0.0
    for i in range(0, 130):
        x, y = path.at(d0 + SPEED * i); nx, ny = path.normal(d0 + SPEED * i)
        o = lateral(i); raw.append({"t": float(i), "x": x + side * o * nx, "y": y + side * o * ny, "acc": 8.0})
    s = finalize(raw, frame, 3)
    dists = [path.line.distance(Point(x["x"], x["y"])) for x in s]
    ev, _ = detector([x["t"] for x in s], dists)
    assert [e for _, e in ev] == ["off", "back"], ev
    save("real_departure", "suraksan_package.json", frame, start, s, [
        {"type": "off_course", "atS": ev[0][0], "tolS": 2.5, "textContains": "코스를 벗어났어요"},
        {"type": "back", "atS": ev[1][0], "tolS": 2.5, "textContains": "돌아왔어요"},
    ])

    # 7) 일몰 임박: 14:30 출발 → 예상 하산 완료(약 2시간 40분 뒤)가 일몰 17:16 − 30분 이후 → 하산 권고 1회
    dt = 2
    raw = [{"t": float(i * dt), "x": path.at(SPEED * i * dt)[0], "y": path.at(SPEED * i * dt)[1]} for i in range(0, 100)]
    s = finalize(raw, frame, 7)
    save("sunset_late", "suraksan_package.json", frame, datetime(2026, 11, 21, 14, 30, tzinfo=KST), s, [
        {"type": "sunset", "atS": 0.0, "tolS": 3, "textContains": "하산을 권해요"},
    ], {"sunset": sunset})


# ------------------------------------------------------------------ 합성 산(그래프) 시나리오
def synthetic_package(closed=()):
    f = EnuFrame()
    N = {0: (0, 0), 1: (0, 1000), 2: (0, 2000), 3: (300, 1000), 4: (300, 2000)}
    def densify(a, b, step=20):
        n = max(2, int(math.dist(a, b) / step) + 1)
        return [(a[0] + (b[0] - a[0]) * i / (n - 1), a[1] + (b[1] - a[1]) * i / (n - 1)) for i in range(n)]
    def geo(pts):
        return [[round(f.ll(x, y)[0], 7), round(f.ll(x, y)[1], 7), round(100 + 0.1 * y, 1)] for x, y in pts]
    E = {1: (0, 1), 2: (1, 2), 3: (1, 3), 4: (3, 4), 5: (4, 2)}
    edges = []
    for eid, (a, b) in E.items():
        pts = densify(N[a], N[b])
        up = sum(max(0, 0.1 * (q[1] - p[1])) for p, q in zip(pts, pts[1:]))
        dn = sum(max(0, 0.1 * (p[1] - q[1])) for p, q in zip(pts, pts[1:]))
        edges.append({"id": eid, "from": a, "to": b, "geometry": geo(pts), "length_m": round(sum(math.dist(p, q) for p, q in zip(pts, pts[1:])), 1),
                      "ascent_m": round(up, 1), "descent_m": round(dn, 1), "closed": eid in closed})
    nodes = [{"id": i, "lat": round(f.ll(*N[i])[0], 7), "lon": round(f.ll(*N[i])[1], 7), "ele": 100 + 0.1 * N[i][1],
              "type": {0: "trailhead", 2: "summit"}.get(i, "junction")} for i in N]
    # 원래 코스(오르는 길): N0 → J → B1 → B2 → S 로 돌아가는 길, 내려올 때는 같은 길 역순
    up_pts = densify(N[0], N[1]) + densify(N[1], N[3])[1:] + densify(N[3], N[4])[1:] + densify(N[4], N[2])[1:]
    full = up_pts + list(reversed(up_pts[:-1]))
    poly = geo(full)
    return f, full, {
        "package_version": 1, "mountain": {"id": "synthetic", "name": "합성산"},
        "course": {"id": "synthetic-long", "name": "돌아가는 길 왕복", "polyline": poly,
                   "summit_index": len(up_pts) - 1, "end_index": len(full) - 1},
        "pois": [], "graph": {"nodes": nodes, "edges": edges},
    }, N


def synthetic_scenarios():
    start = datetime(2026, 11, 21, 9, 0, tzinfo=KST)
    for variant, closed in (("synthetic_package.json", ()), ("synthetic_closed_package.json", (2,))):
        f, full, pkg, N = synthetic_package(closed)
        (OUT / variant).write_text(json.dumps(pkg, ensure_ascii=False, separators=(",", ":")))
    f, full, pkg, N = synthetic_package(())
    course = Path(full)
    summit = N[2]

    # 4) 다른 코스 진입: J 에서 코스(동쪽으로 돌아가는 길) 대신 지름길(북쪽)로 → 대안 경로 1회 → 정상 → 하산
    dt = 2
    pts = [N[0], N[1]] + [(0, 1000 + k) for k in range(20, 1001, 20)]        # J 에서 지름길로 정상까지
    down = list(reversed(full[: len(full) // 2 + 1]))                         # 내려올 때: S → B2 → B1 → J → N0 (원래 하산로)
    raw, t = [{"t": 0.0, "x": 0.0, "y": 0.0}], 0.0
    t = seg_walk(raw, t, pts, SPEED, dt)
    t_summit_true = t
    t = rest(raw, t, summit, 60, dt)
    t = seg_walk(raw, t, down[0:1] + down[1:], SPEED, dt)
    s = finalize(resample(raw, dt), f, 4)
    dists = [course.line.distance(Point(x["x"], x["y"])) for x in s]
    ev, _ = detector([x["t"] for x in s], dists)
    t_off = ev[0][0]
    first_summit = next(x["t"] for x in s if x["t"] > t_off and math.dist((x["x"], x["y"]), summit) <= 30)
    t_desc = t_summit_true + 60 + 30 / SPEED
    t_end = t - 30 / SPEED
    save("alt_route", "synthetic_package.json", f, start, s, [
        {"type": "alt_route", "atS": t_off, "tolS": 3, "textContains": "다른 코스로 들어섰어요"},
        {"type": "summit", "atS": first_summit, "tolS": 8},
        {"type": "descent", "atS": t_desc, "tolS": 12},
        {"type": "end", "atS": t_end, "tolS": 12},
    ])

    # 5) 등산로 밖: J 에서 서쪽 숲으로 90m, 60초 머문 뒤 돌아와 코스를 계속 → 30초마다 방향 안내, 복귀 안내
    dt = 1
    raw, t = [{"t": 0.0, "x": 0.0, "y": 0.0}], 0.0
    t = seg_walk(raw, t, [N[0], N[1], (-90, 1000)], SPEED, dt)
    t = rest(raw, t, (-90, 1000), 60, dt)
    t = seg_walk(raw, t, [(-90, 1000), (0, 1000), (60, 1000)], SPEED, dt)
    s = finalize(resample(raw, dt), f, 5)
    dists = [course.line.distance(Point(x["x"], x["y"])) for x in s]
    ev, states = detector([x["t"] for x in s], dists)
    t_off, t_back = ev[0][0], ev[1][0]
    expect = [{"type": "off_trail", "atS": t_off, "tolS": 2.5, "textContains": "동쪽"}]
    nxt = t_off + 30
    for x, d, st in zip(s, dists, states):          # 30초마다 되풀이(이탈 상태이고 코스에서 30m 넘게 떨어져 있을 때)
        if x["t"] >= nxt and st == "OFF" and d > 30:
            expect.append({"type": "off_trail", "atS": x["t"], "tolS": 2.5, "textContains": "동쪽"}); nxt = x["t"] + 30
    expect.append({"type": "back", "atS": t_back, "tolS": 2.5, "textContains": "돌아왔어요"})
    save("off_trail", "synthetic_package.json", f, start, s, expect)

    # 6) 폐쇄 구간: 지름길(2)이 폐쇄. 그 길로 100m 올라갔다 돌아옴 → 폐쇄 구간으로 안내하지 않고 열린 등산로(남쪽) 방향 안내
    dt = 1
    raw, t = [{"t": 0.0, "x": 0.0, "y": 0.0}], 0.0
    t = seg_walk(raw, t, [N[0], N[1], (0, 1100)], SPEED, dt)
    t = rest(raw, t, (0, 1100), 40, dt)
    t = seg_walk(raw, t, [(0, 1100), (0, 1000), (60, 1000)], SPEED, dt)
    s = finalize(resample(raw, dt), f, 6)
    dists = [course.line.distance(Point(x["x"], x["y"])) for x in s]
    ev, states = detector([x["t"] for x in s], dists)
    t_off, t_back = ev[0][0], ev[1][0]
    expect = [{"type": "off_trail", "atS": t_off, "tolS": 2.5, "textContains": "남쪽"}]
    nxt = t_off + 30
    for x, d, st in zip(s, dists, states):
        if x["t"] >= nxt and st == "OFF" and d > 30:
            expect.append({"type": "off_trail", "atS": x["t"], "tolS": 2.5, "textContains": "남쪽"}); nxt = x["t"] + 30
    expect.append({"type": "back", "atS": t_back, "tolS": 2.5})
    save("closed_edge", "synthetic_closed_package.json", f, start, s, expect)


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    real_scenarios()
    synthetic_scenarios()
