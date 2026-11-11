"""말동무 — 직선 주행 중 한동안 말이 없으면(quiet 이벤트, 앱 CarFeatures) 취향에 맞춘 이야깃거리 하나를 먼저 건넨다.
주제는 돌아가며: 취향에 맞는 영화·웹툰 한 편 → 지금 지나는 곳 이야기 → 요즘 화제. 순위를 줄줄이 읽지 않고 하나만, 이유와 함께.
사실은 [정보 통로]·검색으로 확인한 것만(지어내기 금지). 회전 앞·교통 알림 중엔 앱 말하기 스케줄러가 미룬다(P3).
앱 짝 = feature/CarFeatures.kt(quiet 이벤트) · 시연: --es event quiet
"""
import json
import os

from core import config

TOPICS = ["pick", "place", "trend"]
NAMES = {"pick": "취향에 맞는 영화·웹툰 한 편", "place": "지금 지나는 곳 이야기", "trend": "요즘 화제 하나"}
_STATE = os.path.join(config.DATA, "chat.json")

RULES = """- **quiet 이벤트(말동무 — 직선 주행 중 한동안 말이 없음)**: 마지막 줄의 '이번 이야깃거리' 하나만, 두 문장 + 가벼운 질문 하나(listen=true · prio 3 · mood fun). 회전·교통·차 이야기는 꺼내지 않는다. [취향]을 이미 아는 티를 낸다("스릴러 좋아하시잖아요")
  · 영화·웹툰 한 편: [정보 통로] 영화·웹툰 순위에서 [취향]에 가장 맞는 한 편만 골라 순위·이유와 함께 — 순위를 줄줄이 읽지 않는다
  · 지금 지나는 곳 이야기: [길·교통] 현재 위치(동네·도로 이름)의 지명 유래나 재밌는 사실 하나 — 검색으로 확인한 것만. 확인 못 하면 영화·웹툰 한 편으로
  · 요즘 화제: 이번 주 화제(유행어·밈·화제작) 하나를 검색으로 확인해 뜻을 한 줄로 풀어 준다 — 확인 못 하면 영화·웹툰 한 편으로"""


def _next_topic():
    try:
        with open(_STATE, encoding="utf-8") as f:
            i = int(json.load(f).get("i", 0))
    except (OSError, ValueError):
        i = 0
    try:
        with open(_STATE, "w", encoding="utf-8") as f:
            json.dump({"i": i + 1}, f)
    except OSError:
        pass
    return TOPICS[i % len(TOPICS)]


def _quiet(msg):
    return msg.get("kind") == "event" and msg.get("text") == "quiet"


def on_in(msg):
    if _quiet(msg):
        msg["chat_topic"] = _next_topic()


def feed_cats(msg):
    return ["movie", "webtoon"] if _quiet(msg) else []


def search(msg):
    return _quiet(msg) and msg.get("chat_topic") in ("place", "trend")


def last_line(msg):
    if not _quiet(msg):
        return None
    t = msg.get("chat_topic") or "pick"
    return f"[주행 이벤트] quiet(직선 주행 중 한동안 조용함) — 이번 이야깃거리: {NAMES[t]}. 현비서가 먼저 건넬 한마디"
