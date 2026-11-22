"""그래프 파이프라인 진입점 (-I 격리 모드에서도 pipeline 패키지를 찾도록 경로를 넣는다)."""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from pipeline.cli import main  # noqa: E402

main()
