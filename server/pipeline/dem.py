"""고도 보정(명세 §7-6): 산림청 SHP 에는 고도가 없어 AWS Terrain Tiles(terrarium, 약 30m)로 채운다.
키/가입이 필요 없고 산의 공개 좌표만 요청한다. 타일은 로컬에 캐시한다."""
import io
import math
import urllib.request
from pathlib import Path

import numpy as np
from PIL import Image

URL = "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
UA = "hiking-graph-pipeline/0.1"


class Dem:
    def __init__(self, cache_dir: Path | None = None, zoom: int = 14, fetch=None):
        self.z, self.cache, self.tiles = zoom, cache_dir, {}
        self._fetch = fetch or self._http
        if cache_dir:
            cache_dir.mkdir(parents=True, exist_ok=True)

    def _http(self, x, y):
        req = urllib.request.Request(URL.format(z=self.z, x=x, y=y), headers={"User-Agent": UA})
        return urllib.request.urlopen(req, timeout=30).read()

    def _tile(self, x, y):
        if (x, y) not in self.tiles:
            f = self.cache / f"{self.z}_{x}_{y}.png" if self.cache else None
            data = f.read_bytes() if f and f.exists() else self._fetch(x, y)
            if f and not f.exists():
                f.write_bytes(data)
            im = np.array(Image.open(io.BytesIO(data)).convert("RGB"), dtype=float)
            self.tiles[(x, y)] = im[..., 0] * 256 + im[..., 1] + im[..., 2] / 256 - 32768
        return self.tiles[(x, y)]

    def elevation(self, lat: float, lon: float) -> float:
        n = 2 ** self.z
        fx = (lon + 180) / 360 * n
        fy = (1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n
        px, py = (fx - math.floor(fx)) * 256 - 0.5, (fy - math.floor(fy)) * 256 - 0.5
        tx, ty, x0, y0 = int(fx), int(fy), math.floor(px), math.floor(py)
        dx, dy = px - x0, py - y0
        g = lambda ix, iy: self._tile(tx + ix // 256, ty + iy // 256)[iy % 256, ix % 256]
        return float(g(x0, y0) * (1 - dx) * (1 - dy) + g(x0 + 1, y0) * dx * (1 - dy)
                     + g(x0, y0 + 1) * (1 - dx) * dy + g(x0 + 1, y0 + 1) * dx * dy)
