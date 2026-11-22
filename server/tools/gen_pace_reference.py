"""AC7: Python 기준 모델이 계산한 남은 시간을 testdata/pace_reference.json 에 저장한다.
Kotlin(guidance-core)의 PaceParityTest 가 같은 입력으로 계산한 값과 비교한다(오차 1분 이내).
사용: server/.venv/bin/python -I server/tools/gen_pace_reference.py"""
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "server"))
from app.pace import GRADE_EDGES, CoursePace  # noqa: E402

pkg = json.loads((REPO / "testdata" / "suraksan_package.json").read_text())
poly = pkg["course"]["polyline"]
model = {"type": "tobler_personal", "factor": 1.12,
         "grade_bins": {"edges": GRADE_EDGES, "factors": [1.5, 1.35, 1.2, 1.0, 1.15, 1.35, 1.6]}}
base, personal = CoursePace(poly), CoursePace(poly, model)
summit = base.cum[pkg["course"]["summit_index"]]
pairs = [(0, summit), (summit, base.length), (0, base.length), (1000, 3000), (3500, 4300), (5000, 8000), (6000, 6100), (4400, 8700)]
cases = [{"from": round(a, 3), "to": round(b, 3), "default_s": round(base.eta(a, b), 3), "personal_s": round(personal.eta(a, b), 3)} for a, b in pairs]
out = REPO / "testdata" / "pace_reference.json"
out.write_text(json.dumps({"model": model, "cases": cases}, indent=1) + "\n")
for c in cases:
    print(f"{c['from']:8.0f} → {c['to']:8.0f}  표준 {c['default_s']/60:6.1f}분  개인 {c['personal_s']/60:6.1f}분")
