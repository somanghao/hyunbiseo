"""취향 정리 수동 실행 — 본체는 features/taste_refine.py(서버가 켜져 있으면 그 스레드가 알아서 돈다).

    python -I -X utf8 server/hyun/taste_refine.py [--dry] [--all]   (--dry 적용 안 함 · --all 처음부터 다시 읽기)
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from features import taste, taste_refine  # noqa: E402

if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    r = taste_refine.run(dry="--dry" in sys.argv, all_="--all" in sys.argv, why="수동")
    print(json.dumps(r, ensure_ascii=False, indent=1))
    print(taste.taste_block())
