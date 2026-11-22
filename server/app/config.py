"""설정: 환경변수 또는 server/.env (git 제외)."""
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent          # server/
REPO = ROOT.parent


def _load_env_file() -> None:
    f = ROOT / ".env"
    if not f.exists():
        return
    for line in f.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip())


_load_env_file()

TOKEN = os.environ.get("HIKING_TOKEN", "")
DB_PATH = Path(os.environ.get("HIKING_DB", ROOT / "data" / "hiking.sqlite"))
# 시드 코스 패키지(Phase 1 testdata). 서버 시작 시 DB 에 upsert 한다.
GRAPH_DIR = REPO / "testdata" / "graphs"      # pipeline 이 만든 <산>.graph.json / .report.json
SEED_PACKAGES = [REPO / "testdata" / "suraksan_package.json"]
COOKIE_NAME = "hiking_token"
