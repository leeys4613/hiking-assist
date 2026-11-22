"""testdata 의 코스 패키지를 DB 로 upsert (서버 시작 시)."""
import json

from sqlalchemy.orm import Session

from . import config
from .models import Course, Mountain
from .pace import course_metrics


def seed(db: Session, paths=None) -> int:
    n = 0
    for path in paths if paths is not None else config.SEED_PACKAGES:
        if not path.exists():
            continue
        text = path.read_text()
        pkg = json.loads(text)
        poly = pkg["course"]["polyline"]
        m = course_metrics(poly)
        lat = sum(p[0] for p in poly) / len(poly)
        lon = sum(p[1] for p in poly) / len(poly)
        mt = db.get(Mountain, pkg["mountain"]["id"]) or Mountain(id=pkg["mountain"]["id"], name="", lat=0, lon=0)
        mt.name, mt.lat, mt.lon = pkg["mountain"]["name"], lat, lon
        db.merge(mt)
        c = db.get(Course, pkg["course"]["id"]) or Course(id=pkg["course"]["id"])
        c.mountain_id = mt.id
        c.name = pkg["course"]["name"]
        c.length_m, c.ascent_m, c.descent_m = m["length_m"], m["ascent_m"], m["descent_m"]
        c.std_minutes = round(m["eta_s"] / 60)
        c.package_json = text
        db.merge(c)
        n += 1
    db.commit()
    return n
