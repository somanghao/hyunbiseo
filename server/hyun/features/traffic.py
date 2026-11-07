"""교통 표 — 길·교통 질문엔 서버가 앱 맥락(trafficAhead · 경유지)으로 목록 카드 "앞길 교통 · ○○까지 N분(도착)"를 만든다
(두뇌가 숫자를 옮기다 틀리지 않게). 두뇌 카드는 비우게 한다. 앱 짝 = ScoutFeature(정찰 값을 맥락에)
"""
import re

RULES = """- **교통 카드는 서버가 만든다** — 길·교통 질문엔 card를 비운다(title="" items=[]), say만"""

TRAFFIC_Q = re.compile(r"교통|막혀|막히|정체|길\s*(어때|막)|앞길|도로\s*상황|몇\s*분\s*걸")


def traffic_card(c):
    if not c.get("dest"):
        return None
    items = []
    for a in c.get("trafficAhead") or []:   # "약 400m 앞 정체 · 대왕판교로 40m · 평균 9km/h · 약 1분"
        head, _, rest = a.partition(" · ")
        items.append(f"{head} — {rest}" if rest else a)
    for v in c.get("via") or []:
        items.append(f"경유지 — {v}")
    if not items:
        items = [f"경로 — {c.get('traffic') or '아직 정찰 전'}"]
    return {"kind": "", "title": f"앞길 교통 · {c['dest']}까지 {c.get('remainMin', '?')}분(도착 {c.get('eta', '?')})",
            "source": f"내비 교통정보 · {c.get('t', '')} · {c.get('traffic') or ''}".rstrip(" ·"), "items": items[:10]}


def post(rep, msg, b, dry, rec):
    if msg.get("kind") == "talk" and TRAFFIC_Q.search(msg.get("text", "")) and not ((b.get("card") or {}).get("items")):
        card = traffic_card(msg.get("ctx") or {})
        if card:
            rep["card"] = card   # cards.post가 이어서 손질한다
