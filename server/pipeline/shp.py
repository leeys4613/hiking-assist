"""산림청 등산로 SHP(zip) 읽기. zip 안의 파일명은 cp949 로 인코딩돼 있고, 압축 해제 경로 탈출(zip slip)을 막는다."""
import tempfile
import zipfile
from pathlib import Path

import geopandas as gpd


class ShpError(Exception):
    pass


def _extract(zip_path: Path, dest: Path) -> None:
    with zipfile.ZipFile(zip_path) as z:
        for info in z.infolist():
            if info.is_dir():
                continue
            try:
                name = info.filename.encode("cp437").decode("cp949")   # 한글 파일명
            except (UnicodeEncodeError, UnicodeDecodeError):
                name = info.filename
            target = (dest / Path(name).name).resolve()                  # 디렉터리 구조를 버리고 파일명만 쓴다
            if dest.resolve() not in target.parents:
                raise ShpError(f"unsafe path in zip: {info.filename}")
            target.write_bytes(z.read(info))


def load(zip_path: str | Path):
    """(line GeoDataFrame, spot GeoDataFrame) 를 EPSG:5186 으로 돌려준다.
    PMNTN_* 가 선, PMNTN_SPOT_* 가 점이다."""
    zip_path = Path(zip_path)
    if not zip_path.exists():
        raise ShpError(f"not found: {zip_path}")
    with tempfile.TemporaryDirectory(prefix="shp_") as d:
        dest = Path(d)
        _extract(zip_path, dest)
        shps = list(dest.glob("*.shp"))
        up = lambda p: p.name.upper()
        spot = next((p for p in shps if "SPOT" in up(p) and "SAFE" not in up(p)), None)    # PMNTN_SPOT_*  (정상/분기점/이정표 …)
        line = next((p for p in shps if "SPOT" not in up(p) and "PMNTN" in up(p)), None)   # PMNTN_*       (등산로 선분)
        # PMNTN_SAFE_SPOT_* (안전지점 표지)는 그래프에 쓰지 않는다
        if line is None:
            raise ShpError(f"no line shapefile in {zip_path.name}: {[p.name for p in shps]}")
        rd = lambda p: gpd.read_file(p, encoding="cp949").set_crs("EPSG:5186", allow_override=True)  # .prj 가 ITRF2000 표기라 강제 지정
        return rd(line), (rd(spot) if spot else None)
