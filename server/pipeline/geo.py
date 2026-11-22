"""좌표 변환: 산림청 SHP 는 EPSG:5186(중부원점 TM, 미터), 앱/서버는 EPSG:4326."""
from pyproj import Transformer

_to_m = Transformer.from_crs("EPSG:4326", "EPSG:5186", always_xy=True)
_to_ll = Transformer.from_crs("EPSG:5186", "EPSG:4326", always_xy=True)


def to_xy(lat: float, lon: float) -> tuple[float, float]:
    return _to_m.transform(lon, lat)


def to_latlon(x: float, y: float) -> tuple[float, float]:
    lon, lat = _to_ll.transform(x, y)
    return lat, lon
