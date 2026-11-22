"""기상청 단기예보 조회서비스(공공데이터포털 VilageFcstInfoService_2.0/getVilageFcst) 클라이언트.
강수확률(POP)만 쓴다. 키는 서버 환경(HIKING_KMA_KEY)에만 두고 앱으로는 내려보내지 않는다."""
import json
import re
from datetime import datetime, timedelta, timezone
from urllib.parse import unquote

import httpx

URL = "http://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst"
KST = timezone(timedelta(hours=9))
BASE_HOURS = (2, 5, 8, 11, 14, 17, 20, 23)     # 단기예보 발표시각(KST)
PUBLISH_DELAY_MIN = 15                         # 발표 직후엔 자료가 아직 없을 수 있어 여유를 둔다(보수적)


class KmaError(Exception):
    def __init__(self, code: str, msg: str):
        super().__init__(f"{code}: {msg}")
        self.code, self.msg = code, msg


class KmaNoData(KmaError):
    """resultCode 03: 해당 발표분 자료가 아직 없음 → 이전 발표분으로 되돌린다"""


def choose_base(now: datetime, back: int = 0) -> tuple[str, str]:
    """now 시점에 쓸 수 있는 가장 최근 발표분 (base_date, base_time). back=1 이면 한 발표 이전."""
    cutoff = now.astimezone(KST) - timedelta(minutes=PUBLISH_DELAY_MIN)
    days = [cutoff.replace(hour=0, minute=0, second=0, microsecond=0) - timedelta(days=d) for d in (0, 1, 2)]
    cands = sorted((d + timedelta(hours=h) for d in days for h in BASE_HOURS), reverse=True)
    avail = [c for c in cands if c <= cutoff]
    base = avail[back]
    return base.strftime("%Y%m%d"), base.strftime("%H00")


def clean_key(key: str) -> str:
    """공공데이터포털은 Encoding/Decoding 두 가지 키를 준다. 인코딩된 키(%2B 등)를 붙여넣어도 이중 인코딩되지 않게 풀어 쓴다."""
    return unquote(key.strip())


def parse_response(text: str) -> list[dict]:
    """응답 본문 → item 목록. 인증 오류 등은 dataType=JSON 이어도 XML 로 오므로 둘 다 처리한다."""
    body = text.lstrip()
    if body.startswith("<"):                          # XML 오류
        def first(*tags):          # 앞에 적은 태그일수록 우선(returnAuthMsg 가 errMsg 보다 구체적이다)
            for t in tags:
                m = re.search(rf"<{t}>(.*?)</{t}>", body, re.S)
                if m and m.group(1).strip():
                    return m.group(1).strip()
            return None
        c = first("returnReasonCode", "resultCode") or "99"
        m = first("returnAuthMsg", "resultMsg", "errMsg") or "unknown XML error"
        raise KmaError(c, m)
    try:
        resp = json.loads(text)["response"]
        header = resp["header"]
    except (ValueError, KeyError, TypeError):
        raise KmaError("99", "unparseable response")
    code = str(header.get("resultCode", "99"))
    if code == "03":
        raise KmaNoData(code, header.get("resultMsg", "NO_DATA"))
    if code != "00":
        raise KmaError(code, header.get("resultMsg", ""))
    items = (resp.get("body") or {}).get("items") or {}
    return items.get("item", []) if isinstance(items, dict) else []


def pop_hours(items: list[dict]) -> list[dict]:
    """POP(강수확률, %) 만 뽑아 [{"time": ISO(+09:00), "pop": int}] 시간순으로."""
    out = {}
    for it in items:
        if it.get("category") != "POP":
            continue
        try:
            t = datetime.strptime(f"{it['fcstDate']}{it['fcstTime']}", "%Y%m%d%H%M").replace(tzinfo=KST)
            out[t] = max(0, min(100, int(float(it["fcstValue"]))))
        except (KeyError, ValueError):
            continue
    return [{"time": t.isoformat(timespec="minutes"), "pop": p} for t, p in sorted(out.items())]


def fetch(nx: int, ny: int, base_date: str, base_time: str, key: str, client: httpx.Client | None = None) -> list[dict]:
    params = {"serviceKey": clean_key(key), "pageNo": 1, "numOfRows": 1000, "dataType": "JSON",
              "base_date": base_date, "base_time": base_time, "nx": nx, "ny": ny}
    own = client is None
    client = client or httpx.Client(timeout=10.0)
    try:
        r = client.get(URL, params=params)
        r.raise_for_status()
        return pop_hours(parse_response(r.text))
    finally:
        if own:
            client.close()
