"""위경도 → 기상청 동네예보 5km 격자(nx, ny). 기상청이 공개한 LCC(람베르트 정각원추) DFS 변환식이다.
기준: 지구 반경 6371.00877km, 격자 5km, 표준위도 30°/60°, 기준점 (126°E, 38°N) = 격자 (43, 136)."""
import math

RE, GRID = 6371.00877, 5.0
SLAT1, SLAT2, OLON, OLAT, XO, YO = 30.0, 60.0, 126.0, 38.0, 43.0, 136.0


def latlon_to_grid(lat: float, lon: float) -> tuple[int, int]:
    d2r = math.pi / 180.0
    re = RE / GRID
    slat1, slat2, olon, olat = SLAT1 * d2r, SLAT2 * d2r, OLON * d2r, OLAT * d2r
    sn = math.tan(math.pi * 0.25 + slat2 * 0.5) / math.tan(math.pi * 0.25 + slat1 * 0.5)
    sn = math.log(math.cos(slat1) / math.cos(slat2)) / math.log(sn)
    sf = math.tan(math.pi * 0.25 + slat1 * 0.5)
    sf = math.pow(sf, sn) * math.cos(slat1) / sn
    ro = math.tan(math.pi * 0.25 + olat * 0.5)
    ro = re * sf / math.pow(ro, sn)
    ra = math.tan(math.pi * 0.25 + lat * d2r * 0.5)
    ra = re * sf / math.pow(ra, sn)
    theta = lon * d2r - olon
    if theta > math.pi:
        theta -= 2.0 * math.pi
    if theta < -math.pi:
        theta += 2.0 * math.pi
    theta *= sn
    return int(math.floor(ra * math.sin(theta) + XO + 0.5)), int(math.floor(ro - ra * math.cos(theta) + YO + 0.5))
