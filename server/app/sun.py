"""일몰 시각(astral). API 키가 필요 없다."""
from datetime import date, datetime
from zoneinfo import ZoneInfo

from astral import LocationInfo
from astral.sun import sun

KST = ZoneInfo("Asia/Seoul")


def today_kst() -> date:
    return datetime.now(KST).date()


def sunset_iso(lat: float, lon: float, d: date) -> str:
    """ISO-8601, 오프셋 포함(예: 2026-11-21T17:16:34+09:00). guidance-core 의 OffsetDateTime.parse 로 읽힌다."""
    obs = LocationInfo(latitude=lat, longitude=lon, timezone="Asia/Seoul").observer
    return sun(obs, date=d, tzinfo=KST)["sunset"].isoformat(timespec="seconds")
