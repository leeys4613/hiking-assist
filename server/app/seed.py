"""testdata 의 코스 패키지를 DB 로 upsert (서버 시작 시)."""
import json
from pathlib import Path

from sqlalchemy.orm import Session

from . import config
from .models import Course, GraphState, Mountain
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
    load_graphs(db)
    return n


def load_graphs(db: Session, graph_dir: Path | None = None) -> int:
    """pipeline 산출물(<산>.graph.json + <산>.report.json)을 DB 로 올린다. 검증 통과 여부가 enabled 가 된다."""
    d = graph_dir or config.GRAPH_DIR
    n = 0
    for mt in db.query(Mountain).all():
        gf, rf = d / f"{mt.id}.graph.json", d / f"{mt.id}.report.json"
        if not (gf.exists() and rf.exists()):
            continue
        report = json.loads(rf.read_text())
        st = db.get(GraphState, mt.id) or GraphState(mountain_id=mt.id)
        st.enabled = bool(report.get("passed"))
        st.report_json, st.graph_json = rf.read_text(), gf.read_text()
        db.merge(st)
        n += 1
    db.commit()
    return n
