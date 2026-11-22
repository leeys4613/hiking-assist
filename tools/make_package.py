"""산림청 SHP 기반 코스(geojson) → 코스 패키지 JSON(명세 §5.1, Phase 1 부분집합).
사용: server/.venv/bin/python -I tools/make_package.py <spot.shp> <course.geojson> <out.json>
왕복 코스: 올라가는 길 + 같은 길 역순. POI는 올라갈 때/내려올 때 각각 course_dist_m을 가진다."""
import json, math, sys
import geopandas as gpd
from pyproj import Transformer
from shapely.geometry import LineString

R = 6371008.8
def hav(a, b):
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp, dl = p2 - p1, math.radians(b[1] - a[1])
    h = math.sin(dp/2)**2 + math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2
    return 2*R*math.asin(math.sqrt(h))

# (SHP DETAIL_SPO 또는 ETC_MATTER 에 포함된 키워드, 표시 이름, 타입)
WANTED = [("벤치", "벤치", "shelter"), ("영원암", "영원암", "landmark"),
          ("치마바위/조망점", "치마바위", "landmark"), ("철모바위", "철모바위", "landmark")]

spot_path, course_path, out_path = sys.argv[1:4]
f = json.load(open(course_path))
up = [(lat, lon, ele) for lon, lat, ele in f["geometry"]["coordinates"]]
cum = [0.0]
for a, b in zip(up, up[1:]): cum.append(cum[-1] + hav(a, b))
up_len = cum[-1]

t = Transformer.from_crs("EPSG:4326", "EPSG:5186", always_xy=True)
line5186 = LineString([t.transform(lon, lat) for lat, lon, _ in up])
spot = gpd.read_file(spot_path, encoding="cp949").set_crs("EPSG:5186", allow_override=True)
seen, pois = set(), []
for _, r in spot.iterrows():
    txt = f"{r.DETAIL_SPO}/{r.ETC_MATTER}"
    for key, name, typ in WANTED:
        if key in txt and name not in seen and line5186.distance(r.geometry) < 15:
            seen.add(name)
            s = line5186.project(r.geometry) / line5186.length * up_len  # 업힐 기준 거리
            la, lo = (lambda p: (p[1], p[0]))(Transformer.from_crs("EPSG:5186", "EPSG:4326", always_xy=True).transform(r.geometry.x, r.geometry.y))
            pois.append((s, name, typ, la, lo))
pois.sort()
poly = [[round(a, 6), round(b, 6), round(c, 1)] for a, b, c in up]
down = [[round(a, 6), round(b, 6), round(c, 1)] for a, b, c in reversed(up[:-1])]
full = poly + down
out_pois = []
for s, name, typ, la, lo in pois:
    out_pois.append({"name": name, "type": typ, "lat": round(la, 6), "lon": round(lo, 6), "course_dist_m": round(s)})
for s, name, typ, la, lo in reversed(pois):
    out_pois.append({"name": name, "type": typ, "lat": round(la, 6), "lon": round(lo, 6), "course_dist_m": round(2*up_len - s)})
pkg = {
    "package_version": 1,
    "mountain": {"id": "suraksan", "name": "수락산"},
    "course": {"id": "suraksan-station-summit", "name": "수락산역 → 정상 왕복",
               "polyline": full, "summit_index": len(poly) - 1, "end_index": len(full) - 1},
    "pois": out_pois,
}
json.dump(pkg, open(out_path, "w"), ensure_ascii=False, separators=(",", ":"))
print(f"편도 {up_len:.0f}m, 점 {len(full)}개, POI {len(out_pois)}개:", [(p['name'], p['course_dist_m']) for p in out_pois])
