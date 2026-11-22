"""OpenStreetMap(Overpass) 보행로 가져오기. 결과는 후보 제시용이며 자동으로 그래프에 합치지 않는다(명세 §7-4)."""
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

URL = "https://overpass-api.de/api/interpreter"
UA = "hiking-graph-pipeline/0.1"      # Overpass 는 User-Agent 가 없는 자동 요청을 406 으로 거부한다


def fetch(bbox, cache: Path | None = None, refresh: bool = False):
    """bbox=(south, west, north, east). 반환: [{id, name, highway, coords:[(lat, lon)]}]"""
    if cache and cache.exists() and not refresh:
        raw = json.loads(cache.read_text())
    else:
        s, w, n, e = bbox
        q = f'[out:json][timeout:60];way["highway"~"^(path|footway|track|steps|pedestrian)$"]({s},{w},{n},{e});out geom;'
        req = urllib.request.Request(URL, data=urllib.parse.urlencode({"data": q}).encode(),
                                     headers={"User-Agent": UA, "Accept": "application/json"})
        raw = None
        for attempt, wait in enumerate((0, 8, 20)):     # Overpass 는 과부하 때 502/504 를 자주 낸다
            time.sleep(wait)
            try:
                raw = json.loads(urllib.request.urlopen(req, timeout=90).read())
                break
            except urllib.error.HTTPError as ex:
                if ex.code not in (429, 502, 503, 504) or attempt == 2:
                    raise
        if cache:
            cache.parent.mkdir(parents=True, exist_ok=True)
            cache.write_text(json.dumps(raw))
    return parse(raw)


def parse(raw) -> list[dict]:
    out = []
    for el in raw.get("elements", []):
        if el.get("type") == "way" and len(el.get("geometry", [])) >= 2:
            out.append({"id": el["id"], "name": el.get("tags", {}).get("name", ""),
                        "highway": el.get("tags", {}).get("highway", ""),
                        "coords": [(p["lat"], p["lon"]) for p in el["geometry"]]})
    return out
