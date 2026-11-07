"""두뇌(Claude Code 세션) 쪽 답 보내기 — 앱이 받아 TTS로 말한다.

  python -I server/hyun/say.py <id> "답" [--listen] [--mood happy] [--remember "배운 것"]... [--name "부를 이름"]
                                         [--nav "강남역|37.4979|127.0276"]
  --listen   답한 뒤 앱이 마이크를 바로 연다(질문으로 끝날 때 — 손 안 대고 대화)
  --remember 대화에서 알게 된 운전자 취향·사실 → data/memory.json(다음 대화에 쓴다, 앱 정차 화면에 표시)
  --nav      목적지 설정(이름|위도|경도) → 앱이 NaviHelper.requestRoute
  --prio     말하기 단계 1 주행 필수 · 2 편의 제안 · 3 관계·재미(앱 스케줄러가 끊기·보류·버림을 정한다, P0은 앱 규칙만)
             생략하면 앱 기본값(대답 = P1 자리 · 이벤트별 — persona.md §우선순위)
  --mode     폰 모드 바꾸기: transit(대중교통) · walk(산책) · work(업무중)
  --remind   리마인더(폰 P0): "HH:MM|내용"(오늘, 지났으면 내일) 또는 "+분|내용" — 여러 번 가능
  --todo     할 일 추가 "내용" · --done "글자 일부" 끝냄(할 일·리마인더)
  --route    우회: reroute(재탐색) · fast(최단 시간) · highway · free · comfort → 앱이 바꾸고 6초 뒤 전후 시간을 rerouted 이벤트로 보낸다(안 줄면 앱이 되돌림)
             clear_via 경유지 빼기 · cancel 길 안내 끝
  --via      경유지(이름|위도|경도) — 안내 중이면 addWaypoint, --nav와 같이 주면 경유지 넣은 경로 한 번에
  --brief    시동 브리핑 순서 "weather,news,stock" (weather·news·stock·fx·movie·webtoon·webnovel·traffic)
"""
import argparse
import datetime as dt
import json
import sys
import urllib.request

BASE = "http://127.0.0.1:8790"


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("id", type=int)
    ap.add_argument("say")
    ap.add_argument("--listen", action="store_true")
    ap.add_argument("--mood", default="")
    ap.add_argument("--remember", action="append", default=[])
    ap.add_argument("--name", default="")
    ap.add_argument("--nav", default="")
    ap.add_argument("--prio", type=int, choices=[1, 2, 3])
    ap.add_argument("--mode", default="", choices=["", "transit", "walk", "work"])
    ap.add_argument("--remind", action="append", default=[])
    ap.add_argument("--todo", action="append", default=[])
    ap.add_argument("--done", action="append", default=[])
    ap.add_argument("--route", default="", choices=["", "reroute", "fast", "highway", "free", "comfort", "clear_via", "cancel"])
    ap.add_argument("--via", default="")
    ap.add_argument("--brief", default="")
    ap.add_argument("--taste", action="append", default=[], help="취향 '칸:값[:like|dislike|forget]' — 칸 = food·music·movie·content·stock·sports·news·place·life·talk")
    ap.add_argument("--asked", default="", help="이번 말에 취향 질문을 넣었으면 그 칸")
    a = ap.parse_args()
    taste = []
    for t in a.taste:
        p = t.split(":")
        taste.append({"slot": p[0], "value": p[1], "how": p[2] if len(p) > 2 else "like"})
    nav = None
    if a.nav:
        name, lat, lng = a.nav.split("|")
        nav = {"name": name, "lat": float(lat), "lng": float(lng)}
    via = None
    if a.via:
        name, lat, lng = a.via.split("|")
        via = {"name": name, "lat": float(lat), "lng": float(lng)}
    remind = []
    for r in a.remind:
        when, text = r.split("|", 1)
        now = dt.datetime.now()
        if when.startswith("+"):
            at = now + dt.timedelta(minutes=float(when[1:]))
        else:
            h, m = when.split(":")
            at = now.replace(hour=int(h), minute=int(m), second=0, microsecond=0)
            if at <= now:
                at += dt.timedelta(days=1)
        remind.append({"at": int(at.timestamp() * 1000), "text": text})
    body = {"id": a.id, "say": a.say, "listen": a.listen, "mood": a.mood, "remember": a.remember,
            "name": a.name, "nav": nav, "via": via, "brief_order": [x for x in a.brief.split(",") if x], "route": a.route or None, "prio": a.prio,
            "mode": a.mode or None, "remind": remind, "todo": a.todo, "done": a.done, "taste": taste, "asked_slot": a.asked}
    req = urllib.request.Request(f"{BASE}/hyun/answer", data=json.dumps(body, ensure_ascii=False).encode("utf-8"),
                                 headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(req, timeout=10) as r:
        r.read()
    print(f"→ #{a.id} {a.say}" + (" [듣기]" if a.listen else "") + (f" [기억 +{len(a.remember)}]" if a.remember else "") + (f" [우회 {a.route}]" if a.route else "") + (f" [경유 {via['name']}]" if via else "") + (f" [브리핑 {a.brief}]" if a.brief else "") + (f" [P{a.prio}]" if a.prio else "")
          + (f" [모드 {a.mode}]" if a.mode else "") + "".join(f" [⏰ {dt.datetime.fromtimestamp(r['at'] / 1000):%H:%M} {r['text']}]" for r in remind)
          + "".join(f" [할 일 +{x}]" for x in a.todo) + "".join(f" [끝 {x}]" for x in a.done))


if __name__ == "__main__":
    main()
