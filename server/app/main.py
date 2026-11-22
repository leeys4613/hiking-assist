import json
from datetime import datetime, timezone
from pathlib import Path

import gpxpy.gpx
from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI, HTTPException, Request, Response
from fastapi.responses import HTMLResponse, JSONResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel, Field
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.orm import Session

from . import config, db as dbm, pace_model, stats, sun
from .pace import CoursePace
from .auth import require_token, token_ok
from .models import Course, Hike, Mountain, PaceState
from .seed import seed

HERE = Path(__file__).resolve().parent
templates = Jinja2Templates(directory=str(HERE / "templates"))


def create_app(session_factory=None) -> FastAPI:
    factory = session_factory or dbm.SessionLocal

    @asynccontextmanager
    async def lifespan(_app):
        dbm.Base.metadata.create_all(bind=factory.kw["bind"])
        with factory() as s:
            seed(s)
        yield

    app = FastAPI(title="hiking-assistant", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)

    def get_db():
        s = factory()
        try:
            yield s
        finally:
            s.close()

    app.mount("/static", StaticFiles(directory=str(HERE / "static")), name="static")

    # ---- 로그인(브라우저용 쿠키) ----
    @app.get("/login", response_class=HTMLResponse)
    def login_page(request: Request):
        return templates.TemplateResponse(request, "login.html", {"error": None})

    @app.post("/login")
    async def login(request: Request):
        form = (await request.body()).decode()
        from urllib.parse import parse_qs
        tok = parse_qs(form).get("token", [""])[0]
        if not token_ok(tok):
            return templates.TemplateResponse(request, "login.html", {"error": "토큰이 맞지 않아요"}, status_code=401)
        r = RedirectResponse("/", status_code=303)
        secure = request.headers.get("x-forwarded-proto") == "https"
        r.set_cookie(config.COOKIE_NAME, tok, httponly=True, samesite="lax", secure=secure, max_age=60 * 60 * 24 * 365)
        return r

    @app.exception_handler(HTTPException)
    async def _http_exc(request: Request, exc: HTTPException):
        if exc.status_code == 401 and not request.url.path.startswith("/api") and request.url.path != "/health" \
                and "text/html" in request.headers.get("accept", ""):
            return RedirectResponse("/login", status_code=303)
        return JSONResponse({"detail": exc.detail}, status_code=exc.status_code)

    # ---- 개인 페이스 모델 ----
    def current_model(db: Session) -> dict | None:
        row = db.get(PaceState, 1)
        return json.loads(row.model_json) if row else None

    def retrain(db: Session) -> dict | None:
        """모든 기록으로 다시 학습해 저장한다(명세 §4.4: 업로드 시 재학습)."""
        data = []
        for h in db.scalars(select(Hike)):
            c = db.get(Course, h.course_id)
            data.append((json.loads(c.package_json)["course"]["polyline"], json.loads(h.track_json)))
        model = pace_model.learn(data)
        row = db.get(PaceState, 1)
        if model is None:
            if row:
                db.delete(row)
        elif row:
            row.model_json, row.updated_at = json.dumps(model), datetime.utcnow()
        else:
            db.add(PaceState(id=1, model_json=json.dumps(model)))
        db.commit()
        return model

    def build_package(db: Session, c: Course, day) -> dict:
        """저장된 패키지에 날짜별 일몰과 개인 페이스 모델을 얹어 앱에 내려줄 패키지를 만든다."""
        pkg = json.loads(c.package_json)
        pkg["sunset"] = sun.sunset_iso(c.mountain.lat, c.mountain.lon, day)
        model = current_model(db)
        if model:
            summit = pace_model.typical_summit_s(
                [h.summit_s for h in db.scalars(select(Hike).where(Hike.course_id == c.id))])
            pkg["pace_model"] = {**model, **({"typical_summit_s": summit} if summit else {})}
        return pkg

    def my_minutes(db: Session, c: Course) -> int | None:
        model = current_model(db)
        if not model:
            return None
        return round(CoursePace(json.loads(c.package_json)["course"]["polyline"], model).total / 60)

    # ---- API ----
    auth = [Depends(require_token)]

    @app.get("/health", dependencies=auth)
    def health():
        return {"ok": True}

    def _visit_counts(db: Session) -> dict[str, int]:
        rows = db.execute(
            select(Course.mountain_id, func.count(Hike.id)).join(Hike, Hike.course_id == Course.id).group_by(Course.mountain_id)
        ).all()
        return {m: n for m, n in rows}

    @app.get("/api/mountains", dependencies=auth)
    def api_mountains(db: Session = Depends(get_db)):
        visits = _visit_counts(db)
        return [
            {"id": m.id, "name": m.name, "lat": m.lat, "lon": m.lon,
             "visited": visits.get(m.id, 0) > 0, "visits": visits.get(m.id, 0)}
            for m in db.scalars(select(Mountain).order_by(Mountain.name))
        ]

    def _course_summary(c: Course, n_hikes: int) -> dict:
        return {"id": c.id, "name": c.name, "length_m": round(c.length_m), "ascent_m": round(c.ascent_m),
                "difficulty": c.difficulty or None, "std_minutes": c.std_minutes,
                "my_minutes": None, "visits": n_hikes, "visited": n_hikes > 0}

    @app.get("/api/mountains/{mountain_id}/courses", dependencies=auth)
    def api_courses(mountain_id: str, db: Session = Depends(get_db)):
        if db.get(Mountain, mountain_id) is None:
            raise HTTPException(404, "mountain not found")
        out = []
        for c in db.scalars(select(Course).where(Course.mountain_id == mountain_id)):
            n = db.scalar(select(func.count(Hike.id)).where(Hike.course_id == c.id)) or 0
            summary = _course_summary(c, n)
            summary["my_minutes"] = my_minutes(db, c)
            out.append(summary)
        return out

    @app.get("/api/courses/{course_id}/package", dependencies=auth)
    def api_package(course_id: str, date: str | None = None, db: Session = Depends(get_db)):
        c = db.get(Course, course_id)
        if c is None:
            raise HTTPException(404, "course not found")
        try:
            day = datetime.strptime(date, "%Y-%m-%d").date() if date else sun.today_kst()
        except ValueError:
            raise HTTPException(422, "date must be YYYY-MM-DD")
        # 날씨(강수확률)는 아직 연동하지 않는다 → weather 필드 없음
        return JSONResponse(build_package(db, c, day))

    @app.get("/api/pace-model", dependencies=auth)
    def api_pace_model(db: Session = Depends(get_db)):
        return current_model(db) or {"type": "tobler", "factor": 1.0}

    class HikeIn(BaseModel):
        course_id: str
        track: list[list[float | None]] = Field(min_length=2)   # [t_ms, lat, lon, acc, progress]
        events: list[dict] = []
        steps: int | None = None

    def _hike_dict(h: Hike) -> dict:
        return {"id": h.id, "course_id": h.course_id, "date": h.started_at.strftime("%Y-%m-%d %H:%M"),
                "total_s": h.total_s, "summit_s": h.summit_s, "rest_count": h.rest_count, "rest_s": h.rest_s,
                "distance_m": h.distance_m, "avg_pace_s_per_km": h.avg_pace_s_per_km, "steps": h.steps}

    @app.post("/api/hikes", dependencies=auth)
    def api_post_hike(body: HikeIn, response: Response, db: Session = Depends(get_db)):
        if db.get(Course, body.course_id) is None:
            raise HTTPException(404, "course not found")
        track = [[p[0], p[1], p[2], p[3], p[4]] for p in body.track if p[0] is not None and p[1] is not None and p[2] is not None]
        if len(track) < 2:
            raise HTTPException(422, "track needs at least 2 valid points")
        track.sort(key=lambda p: p[0])
        s = stats.compute(track, body.events)
        started = datetime.fromtimestamp(track[0][0] / 1000, tz=timezone.utc).replace(tzinfo=None)
        ended = datetime.fromtimestamp(track[-1][0] / 1000, tz=timezone.utc).replace(tzinfo=None)
        existing = db.scalar(select(Hike).where(Hike.course_id == body.course_id, Hike.started_at == started))
        if existing:  # 업로드 재시도에 안전(멱등)
            response.status_code = 200
            return {**_hike_dict(existing), "duplicate": True}
        h = Hike(course_id=body.course_id, started_at=started, ended_at=ended, steps=body.steps,
                 track_json=json.dumps(track, separators=(",", ":")),
                 events_json=json.dumps(body.events, ensure_ascii=False, separators=(",", ":")), **s)
        db.add(h)
        try:
            db.commit()
        except IntegrityError:
            db.rollback()
            raise HTTPException(409, "duplicate hike")
        retrain(db)
        response.status_code = 201
        return {**_hike_dict(h), "duplicate": False}

    @app.get("/api/hikes", dependencies=auth)
    def api_hikes(course_id: str, db: Session = Depends(get_db)):
        hs = db.scalars(select(Hike).where(Hike.course_id == course_id).order_by(Hike.started_at)).all()
        return [_hike_dict(h) for h in hs]

    @app.get("/api/hikes/{hike_id}/gpx", dependencies=auth)
    def api_hike_gpx(hike_id: int, db: Session = Depends(get_db)):
        h = db.get(Hike, hike_id)
        if h is None:
            raise HTTPException(404, "hike not found")
        gpx = gpxpy.gpx.GPX()
        seg = gpxpy.gpx.GPXTrackSegment()
        for t, lat, lon, acc, _ in json.loads(h.track_json):
            seg.points.append(gpxpy.gpx.GPXTrackPoint(lat, lon, time=datetime.fromtimestamp(t / 1000, tz=timezone.utc)))
        trk = gpxpy.gpx.GPXTrack(name=h.course_id)
        trk.segments.append(seg)
        gpx.tracks.append(trk)
        return Response(content=gpx.to_xml(), media_type="application/gpx+xml")

    # ---- 웹 UI ----
    def _fmt_hm(minutes: int | None) -> str:
        if minutes is None:
            return "-"
        return f"{minutes // 60}:{minutes % 60:02d}"

    templates.env.filters["hm"] = _fmt_hm
    templates.env.filters["hms"] = lambda s: "-" if s is None else f"{s // 3600}:{(s % 3600) // 60:02d}:{s % 60:02d}"
    templates.env.filters["km"] = lambda m: f"{m / 1000:.1f}"
    templates.env.filters["pace"] = lambda p: "-" if p is None else f"{int(p // 60)}:{int(p % 60):02d}/km"

    @app.get("/", response_class=HTMLResponse, dependencies=auth)
    def page_index(request: Request, db: Session = Depends(get_db)):
        ms = api_mountains(db)
        return templates.TemplateResponse(request, "index.html", {
            "mountains": ms, "data_json": json.dumps(ms, ensure_ascii=False).replace("</", "<\\/")})

    @app.get("/mountains/{mountain_id}", response_class=HTMLResponse, dependencies=auth)
    def page_mountain(mountain_id: str, request: Request, db: Session = Depends(get_db)):
        m = db.get(Mountain, mountain_id)
        if m is None:
            raise HTTPException(404, "mountain not found")
        return templates.TemplateResponse(request, "mountain.html", {"m": m, "courses": api_courses(mountain_id, db)})

    @app.get("/courses/{course_id}", response_class=HTMLResponse, dependencies=auth)
    def page_course(course_id: str, request: Request, db: Session = Depends(get_db)):
        c = db.get(Course, course_id)
        if c is None:
            raise HTTPException(404, "course not found")
        pkg = json.loads(c.package_json)
        hikes = db.scalars(select(Hike).where(Hike.course_id == c.id).order_by(Hike.started_at)).all()
        overlay = [{"id": h.id, "date": h.started_at.strftime("%Y-%m-%d"),
                    "pts": [[p[1], p[2]] for p in json.loads(h.track_json)][::3]} for h in hikes]
        data = {"polyline": [[p[0], p[1]] for p in pkg["course"]["polyline"]],
                "summit": pkg["course"]["polyline"][pkg["course"]["summit_index"]][:2],
                "pois": pkg.get("pois", []), "hikes": overlay}
        return templates.TemplateResponse(request, "course.html", {
            "c": c, "m": c.mountain, "hikes": [_hike_dict(h) for h in hikes],
            "my_min": my_minutes(db, c), "sunset": sun.sunset_iso(c.mountain.lat, c.mountain.lon, sun.today_kst())[11:16],
            "data_json": json.dumps(data, ensure_ascii=False).replace("</", "<\\/"),
        })

    return app


app = create_app()
