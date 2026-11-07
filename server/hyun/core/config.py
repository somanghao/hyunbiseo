"""경로 · 포트 · 상수 — 데이터 폴더는 HYUN_DATA로 바꿀 수 있다(시험 = server/hyun/data-e2e, tools/hyun_e2e)."""
import os

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))   # server/hyun
DATA = os.environ.get("HYUN_DATA") or os.path.join(HERE, "data")     # 기록(git 제외) — log.jsonl · memory.json · taste.json · tasks.json · claude.json …
SHARED_DATA = os.path.join(HERE, "data")                              # 시험 데이터를 따로 써도 같이 쓰는 것(정보 통로 캐시 feeds.json)
PERSONA = os.path.join(HERE, "persona.md")
PORT = int(os.environ.get("HYUN_PORT", "8790"))

SESSION_FRESH = 60    # 세션(wait.py)이 이 초 안에 wait 했으면 '세션 연결'
API_TAKEOVER = 6      # 메시지가 이 초 동안 아무도 안 받으면 자동 두뇌가 받는다


def data(*parts):
    return os.path.join(DATA, *parts)
