"""강수확률 예보 조회 + 캐시 + 장애 대응. 앱에는 키 대신 결과(시간별 POP)만 내려보낸다."""
import json
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone

import httpx
from sqlalchemy.orm import Session

from app import config
from app.models import WeatherCache
from .grid import latlon_to_grid
from .kma import KST, KmaError, KmaNoData, choose_base, fetch

CACHE_TTL = timedelta(minutes=90)     # 발표는 3시간마다라 이 안에서는 같은 발표분을 다시 부르지 않는다


@dataclass
class Forecast:
    status: str                 # ok | stale(API 장애로 지난 예보) | no_key | error
    hours: list = field(default_factory=list)      # [{"time": ISO+09:00, "pop": int}]
    base: str | None = None
    message: str = ""


def forecast_for(db: Session, lat: float, lon: float, now: datetime | None = None, *,
                 key: str | None = None, client: httpx.Client | None = None) -> Forecast:
    key = config.KMA_KEY if key is None else key
    now = (now or datetime.now(timezone.utc)).astimezone(KST)
    nx, ny = latlon_to_grid(lat, lon)
    row = db.get(WeatherCache, (nx, ny))
    base_date, base_time = choose_base(now)
    want = base_date + base_time
    naive_now = now.replace(tzinfo=None)

    if row and row.base == want and naive_now - row.fetched_at < CACHE_TTL:
        return Forecast("ok", json.loads(row.hours_json), row.base)
    if not key:
        # 키가 없어도 캐시가 있으면(예: 키를 지운 뒤) 지난 예보를 보여 준다
        return Forecast("stale", json.loads(row.hours_json), row.base, "키 없음") if row else Forecast("no_key", message="HIKING_KMA_KEY 미설정")

    last_err = ""
    for back in (0, 1):                                  # 최신 발표분에 자료가 없으면(03) 한 발표 이전으로
        bd, bt = choose_base(now, back)
        try:
            hours = fetch(nx, ny, bd, bt, key, client)
        except KmaNoData as e:
            last_err = str(e)
            continue
        except (KmaError, httpx.HTTPError) as e:
            last_err = str(e)
            break
        if hours:
            base = bd + bt
            if row:
                row.base, row.fetched_at, row.hours_json = base, naive_now, json.dumps(hours)
            else:
                db.add(WeatherCache(nx=nx, ny=ny, base=base, fetched_at=naive_now, hours_json=json.dumps(hours)))
            db.commit()
            return Forecast("ok", hours, base)
    if row:                                              # 호출 실패: 마지막으로 받은 예보를 대신 쓰되 stale 로 표시
        return Forecast("stale", json.loads(row.hours_json), row.base, last_err)
    return Forecast("error", message=last_err or "no data")


def hours_for_date(fc: Forecast, day: date) -> list:
    return [h for h in fc.hours if h["time"].startswith(day.isoformat())]


def status_for_date(fc: Forecast, day: date) -> str:
    """패키지의 weather_status: ok | stale | no_forecast(예보 범위 밖 날짜) | no_key | error"""
    if fc.status in ("no_key", "error"):
        return fc.status
    return fc.status if hours_for_date(fc, day) else "no_forecast"
