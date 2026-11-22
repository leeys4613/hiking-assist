import json
from datetime import datetime, timedelta

import httpx
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app import config, db as dbm, sun
from app.main import create_app
from weather import kma, service
from weather.grid import latlon_to_grid
from weather.kma import KST, KmaError, KmaNoData, choose_base, clean_key, parse_response, pop_hours

COURSE = "suraksan-station-summit"
KEY_DECODED = "abc+/def==key"
KEY_ENCODED = "abc%2B%2Fdef%3D%3Dkey"
TOKEN = "test-token-123"


# ------------------------------------------------------------------ 격자 / 발표시각
@pytest.mark.parametrize("name,lat,lon,expect", [
    ("서울시청", 37.5665, 126.9780, (60, 127)), ("부산", 35.1796, 129.0756, (98, 76)),
    ("광주", 35.1595, 126.8526, (58, 74)), ("대전", 36.3504, 127.3845, (67, 100)),
    ("수락산(노원구)", 37.687, 127.075, (61, 129)),
])
def test_grid_matches_known_kma_cells(name, lat, lon, expect):
    assert latlon_to_grid(lat, lon) == expect


def at(s):
    return datetime.strptime(s, "%Y-%m-%d %H:%M").replace(tzinfo=KST)


@pytest.mark.parametrize("now,expect", [
    ("2026-11-21 00:30", ("20261120", "2300")),       # 02시 발표 전 → 어제 23시분
    ("2026-11-21 02:14", ("20261120", "2300")),       # 발표 직후엔 아직 자료가 없을 수 있어 이전 발표분
    ("2026-11-21 02:16", ("20261121", "0200")),
    ("2026-11-21 14:20", ("20261121", "1400")),
    ("2026-11-21 13:59", ("20261121", "1100")),
    ("2026-11-21 23:59", ("20261121", "2300")),
])
def test_choose_base(now, expect):
    assert choose_base(at(now)) == expect


def test_choose_base_back_steps_one_publication():
    assert choose_base(at("2026-11-21 14:20"), back=1) == ("20261121", "1100")
    assert choose_base(at("2026-11-21 02:20"), back=1) == ("20261120", "2300")


def test_encoded_key_is_not_double_encoded():
    assert clean_key(KEY_ENCODED) == KEY_DECODED and clean_key(f"  {KEY_DECODED} ") == KEY_DECODED


# ------------------------------------------------------------------ 응답 파싱
def ok_body(items):
    return json.dumps({"response": {"header": {"resultCode": "00", "resultMsg": "NORMAL_SERVICE"},
                                    "body": {"dataType": "JSON", "items": {"item": items}, "totalCount": len(items)}}})


def item(cat, date, time, val):
    return {"baseDate": "20261121", "baseTime": "1100", "category": cat, "fcstDate": date, "fcstTime": time, "fcstValue": val, "nx": 61, "ny": 129}


def test_pop_is_extracted_sorted_and_other_categories_ignored():
    items = [item("TMP", "20261121", "1200", "9"), item("POP", "20261121", "1500", "70"), item("POP", "20261121", "1300", "20"),
             item("PCP", "20261121", "1300", "강수없음"), item("POP", "20261121", "1400", "60")]
    hrs = pop_hours(parse_response(ok_body(items)))
    assert hrs == [{"time": "2026-11-21T13:00+09:00", "pop": 20}, {"time": "2026-11-21T14:00+09:00", "pop": 60},
                   {"time": "2026-11-21T15:00+09:00", "pop": 70}]


def test_pop_values_are_clamped_and_garbage_skipped():
    items = [item("POP", "20261121", "1300", "150"), item("POP", "20261121", "1400", "x"), {"category": "POP"}]
    assert pop_hours(items) == [{"time": "2026-11-21T13:00+09:00", "pop": 100}]


def test_no_data_code_03():
    with pytest.raises(KmaNoData):
        parse_response(json.dumps({"response": {"header": {"resultCode": "03", "resultMsg": "NO_DATA"}}}))


def test_xml_auth_error_even_though_json_was_requested():
    xml = ('<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>SERVICE ERROR</errMsg><returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR'
           '</returnAuthMsg><returnReasonCode>30</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>')
    with pytest.raises(KmaError) as e:
        parse_response(xml)
    assert e.value.code == "30" and "NOT_REGISTERED" in e.value.msg and not isinstance(e.value, KmaNoData)


def test_garbage_and_empty_items():
    with pytest.raises(KmaError):
        parse_response("not json at all")
    empty = json.dumps({"response": {"header": {"resultCode": "00", "resultMsg": "OK"}, "body": {"items": ""}}})
    assert parse_response(empty) == []


# ------------------------------------------------------------------ 호출 / 서비스
class Fake:
    """httpx.MockTransport 핸들러. 호출 기록 + 응답 시나리오"""
    def __init__(self, hours=None):
        self.calls, self.mode = [], "ok"
        self.hours = hours

    def __call__(self, request: httpx.Request) -> httpx.Response:
        q = dict(request.url.params)
        self.calls.append(q)
        if self.mode == "down":
            return httpx.Response(503, text="unavailable")
        if self.mode == "denied":
            return httpx.Response(200, text="<OpenAPI_ServiceResponse><cmmMsgHeader><returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg><returnReasonCode>30</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>")
        if self.mode == "nodata_latest" and q["base_time"] == self.latest:
            return httpx.Response(200, text=json.dumps({"response": {"header": {"resultCode": "03", "resultMsg": "NO_DATA"}}}))
        d = datetime.strptime(q["base_date"], "%Y%m%d")
        items = self.hours or [item("POP", (d + timedelta(hours=h)).strftime("%Y%m%d"), (d + timedelta(hours=h)).strftime("%H00"), str(10 + h)) for h in range(3, 60)]
        return httpx.Response(200, text=ok_body(items))

    def client(self):
        return httpx.Client(transport=httpx.MockTransport(self))


@pytest.fixture()
def session():
    engine = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    dbm.Base.metadata.create_all(engine)
    with sessionmaker(bind=engine, expire_on_commit=False)() as s:
        yield s


LAT, LON = 37.687, 127.075


def test_request_has_the_expected_parameters(session):
    f = Fake()
    fc = service.forecast_for(session, LAT, LON, at("2026-11-21 14:20"), key=KEY_ENCODED, client=f.client())
    assert fc.status == "ok" and fc.base == "202611211400" and fc.hours
    q = f.calls[0]
    assert q["serviceKey"] == KEY_DECODED and q["nx"] == "61" and q["ny"] == "129" and q["dataType"] == "JSON"
    assert q["base_date"] == "20261121" and q["base_time"] == "1400" and q["numOfRows"] == "1000"


def test_no_key_means_no_call_and_no_key_status(session):
    f = Fake()
    fc = service.forecast_for(session, LAT, LON, at("2026-11-21 14:20"), key="", client=f.client())
    assert fc.status == "no_key" and f.calls == [] and fc.hours == []


def test_same_publication_is_cached(session):
    f = Fake()
    for m in ("14:20", "14:40", "15:30"):                 # 모두 14시 발표분, TTL(90분) 안
        service.forecast_for(session, LAT, LON, at(f"2026-11-21 {m}"), key="k", client=f.client())
    assert len(f.calls) == 1


def test_new_publication_triggers_a_new_call(session):
    f = Fake()
    service.forecast_for(session, LAT, LON, at("2026-11-21 14:20"), key="k", client=f.client())
    fc = service.forecast_for(session, LAT, LON, at("2026-11-21 17:20"), key="k", client=f.client())
    assert len(f.calls) == 2 and fc.base == "202611211700"


def test_falls_back_to_previous_publication_on_no_data(session):
    f = Fake(); f.mode, f.latest = "nodata_latest", "1400"
    fc = service.forecast_for(session, LAT, LON, at("2026-11-21 14:20"), key="k", client=f.client())
    assert fc.status == "ok" and fc.base == "202611211100" and [c["base_time"] for c in f.calls] == ["1400", "1100"]


def test_api_down_uses_last_forecast_as_stale(session):
    f = Fake()
    service.forecast_for(session, LAT, LON, at("2026-11-21 14:20"), key="k", client=f.client())
    f.mode = "down"
    fc = service.forecast_for(session, LAT, LON, at("2026-11-21 17:20"), key="k", client=f.client())
    assert fc.status == "stale" and fc.hours and fc.base == "202611211400"


def test_api_down_without_cache_is_error_not_crash(session):
    f = Fake(); f.mode = "down"
    fc = service.forecast_for(session, LAT, LON, at("2026-11-21 14:20"), key="k", client=f.client())
    assert fc.status == "error" and fc.hours == []


def test_bad_key_reports_error_and_does_not_poison_cache(session):
    f = Fake(); f.mode = "denied"
    fc = service.forecast_for(session, LAT, LON, at("2026-11-21 14:20"), key="bad", client=f.client())
    assert fc.status == "error" and "NOT_REGISTERED" in fc.message
    f.mode = "ok"
    assert service.forecast_for(session, LAT, LON, at("2026-11-21 14:20"), key="good", client=f.client()).status == "ok"


def test_date_outside_forecast_range():
    fc = service.Forecast("ok", [{"time": "2026-11-21T13:00+09:00", "pop": 10}])
    from datetime import date
    assert service.status_for_date(fc, date(2026, 11, 21)) == "ok"
    assert service.status_for_date(fc, date(2026, 11, 28)) == "no_forecast" and service.hours_for_date(fc, date(2026, 11, 28)) == []


# ------------------------------------------------------------------ 서버 통합
@pytest.fixture()
def wclient(monkeypatch):
    monkeypatch.setattr(config, "TOKEN", TOKEN)
    monkeypatch.setattr(config, "KMA_KEY", KEY_DECODED)
    today = sun.today_kst()

    def handler(request):
        items = [item("POP", (datetime.combine(today, datetime.min.time()) + timedelta(hours=h)).strftime("%Y%m%d"),
                      (datetime.combine(today, datetime.min.time()) + timedelta(hours=h)).strftime("%H00"),
                      "70" if h == 14 else "10") for h in range(0, 24 * 3)]
        return httpx.Response(200, text=ok_body(items))

    engine = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    factory = sessionmaker(bind=engine, expire_on_commit=False)
    with TestClient(create_app(factory, weather_client=httpx.Client(transport=httpx.MockTransport(handler)))) as c:
        yield c


H = {"Authorization": f"Bearer {TOKEN}"}


def test_package_carries_hourly_pop_for_the_requested_date(wclient):
    p = wclient.get(f"/api/courses/{COURSE}/package", headers=H).json()
    assert p["weather_status"] == "ok" and len(p["weather"]) == 24
    assert {"time", "pop"} == set(p["weather"][0]) and p["weather"][14]["pop"] == 70
    assert p["weather"][14]["time"].endswith("T14:00+09:00")


def test_date_beyond_range_has_no_weather_and_says_so(wclient):
    far = (sun.today_kst() + timedelta(days=10)).isoformat()
    p = wclient.get(f"/api/courses/{COURSE}/package?date={far}", headers=H).json()
    assert p["weather"] == [] and p["weather_status"] == "no_forecast"


def test_package_without_key_still_works(client, auth):
    p = client.get(f"/api/courses/{COURSE}/package", headers=auth).json()
    assert p["weather"] == [] and p["weather_status"] == "no_key" and p["course"]["polyline"]


def test_weather_api_shows_only_hiking_hours(wclient):
    w = wclient.get("/api/weather?mountain_id=suraksan", headers=H).json()
    assert w["status"] == "ok" and [h["hour"] for h in w["hours"]] == list(range(6, 21))
    assert next(h for h in w["hours"] if h["hour"] == 14)["pop"] == 70
    assert wclient.get("/api/weather?mountain_id=none", headers=H).status_code == 404
    assert wclient.get("/api/weather?mountain_id=suraksan&date=nope", headers=H).status_code == 422
    assert wclient.get("/api/weather?mountain_id=suraksan").status_code == 401


def test_pages_show_chips_with_warning_color_and_date_links(wclient):
    page = wclient.get(f"/courses/{COURSE}", headers=H).text
    assert "시간별 강수확률" in page and "14시 70%" in page and 'chip hi">14시 70%' in page and "다음날" in page
    assert "시간별 강수확률" in wclient.get("/mountains/suraksan", headers=H).text


def test_service_key_never_leaks_to_clients(wclient):
    blobs = [wclient.get(f"/api/courses/{COURSE}/package", headers=H).text, wclient.get("/api/weather?mountain_id=suraksan", headers=H).text,
             wclient.get(f"/courses/{COURSE}", headers=H).text, wclient.get("/mountains/suraksan", headers=H).text]
    assert all(KEY_DECODED not in b and KEY_ENCODED not in b for b in blobs)


def test_no_key_page_explains(client, auth):
    page = client.get("/mountains/suraksan", headers=auth).text
    assert "날씨 연동 전이에요" in page
