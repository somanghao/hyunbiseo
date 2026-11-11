"""폰 연동(차 현비서 ↔ 폰 현비서 앱 = 짭비서) — 같은 서버를 쓰는 두 앱을 잇는다.

  · 차에서 "폰으로 보내 줘" → 최근 찾은 장소(카카오 결과 — places._trips, 메모리)를 폰 앱에 전달 → 폰 알림 → 가게 카드([전화] [지도])
  · 폰 앱이 /hyun/push를 long-poll — 기다리는 동안이 '폰 연결됨'(차가 보낼 수 있는지 판단 · 못 보내면 그대로 말함)
  · /hyun/history — 차에서 나눈 이야기(로그의 운전자 말 · 현비서 답, 카드는 제목만) → 폰 '차에서 나눈 이야기'

카카오 장소 원문(이름·전화·주소·좌표)은 파일·로그에 남기지 않는다(약관 — QUICKMAP-hyun §3 #24): 전달 큐는 메모리에만 2분,
폰이 받은 뒤엔 버린다 · 로그엔 결과 종류(sent/nophone/none)만. 앱 짝 = feature/CarLinkFeature.kt(폰 모드) · 명세 = makecar-hy dashboard/hyun/specs-draft/phone-link.md
"""
import re
import threading
import time

from core import store
from features import places

SEND = re.compile(r"(폰|휴대폰|핸드폰|스마트폰|전화기|짭비서)\s*(으로|에|로|에다)?\s*(좀\s*)?(보내|전송|넘겨|쏴)")
ORD = {"첫": 0, "1": 0, "두": 1, "2": 1, "세": 2, "3": 2, "네": 3, "4": 3, "다섯": 4, "5": 4}
FRESH = 90         # 폰 앱이 이 초 안에 /hyun/push를 기다렸으면 '연결됨'
KEEP = 120         # 전달 큐에 두는 초(메모리) — 그 안에 폰이 받아 간다
PLACE_AGE = 3600   # 이보다 오래된 장소 결과는 보내지 않는다

# 낱말 하나로 끝나야 '응' — "어디가 맛있어?"의 '어'를 보내기로 알아듣지 않게(11-11 시험)
AFFIRM = re.compile(r"^(응|어|네|예|그래|좋아|보내|부탁해|ㅇㅇ|웅|그러자|그렇게)(?:$|\s|[.!~,]|요|줘)")
OFFER_FOR = 120    # 현비서가 '폰으로 보내 드릴까요?'라고 물은 뒤 이 초 안의 '응'은 보내기
_offered_at = 0.0
_cond = threading.Condition()
_queue = []        # [{id, at, kind: place, name, tel, addr, url, m, walk, cat, lat, lng, near}] — 메모리만
_seq = 0
phone_seen = 0.0   # 폰 앱이 마지막으로 /hyun/push를 기다린 시각


def phone_alive():
    return time.time() - phone_seen < FRESH


def _named(items, text):
    """말에 가게 이름이 들어 있으면 그 항목 — "어머니족발 폰으로 보내 줘" · 지점명은 빼고 앞 낱말로도 맞춘다"""
    t = re.sub(r"\s+", "", text or "")
    for it in items:
        name = re.sub(r"\s+", "", it.get("name", ""))
        head = re.sub(r"\s+", "", (it.get("name", "").split() or [""])[0])
        if name and (name in t or (len(head) >= 2 and head in t)):
            return it
    return None


def _latest_place(idx, text=""):
    """가장 최근에 찾은 장소 결과(1시간 안)에서 말한 이름 → idx번째(없으면 마지막) — (결과, 항목)"""
    with places._lock:
        trips = sorted(places._trips.values(), key=lambda t: t["at"], reverse=True)
    for t in trips:
        if time.time() - t["at"] > PLACE_AGE:
            break
        items = t.get("items") or []
        if items:
            return t, _named(items, text) or items[min(idx, len(items) - 1)]
    return None, None


def on_in(msg):
    """판정만(inbox 잠금 안 — 빠르게). 전달은 block/post에서"""
    if msg.get("kind") != "talk":
        return
    text = msg.get("text", "")
    # 현비서가 방금 "폰으로 보내 드릴까요?" 하고 물었고 운전자가 그렇다고 하면 그것도 보내기
    offered = time.time() - _offered_at < OFFER_FOR and AFFIRM.match(text.strip())
    if not (SEND.search(text) or offered):
        return
    m = re.search(r"(첫|두|세|네|다섯|[1-5])\s*(번째|번)", text)
    msg["phone_send"] = {"idx": ORD.get(m.group(1), 0) if m else 0, "text": text}


def _send(msg):
    """한 메시지에 한 번만 — 장소를 골라 큐에 넣고 결과를 msg['phone_send']['status']에(sent · nophone · none · dry)"""
    global _seq
    ps = msg.get("phone_send")
    if not ps or "status" in ps:
        return ps
    t, it = _latest_place(ps["idx"], ps.get("text", ""))
    if (msg.get("ctx") or {}).get("harness"):
        ps["status"] = "dry"   # 하네스 시험 — 보내지 않는다
    elif not it:
        ps["status"] = "none"
    elif not phone_alive():
        ps.update(status="nophone", name=it["name"])
    else:
        with _cond:
            # 번호 = 시각(ms) 기반 — 서버를 다시 켜도 폰이 기억한 after보다 커서 건너뛰지 않는다(1부터 다시 세면 놓쳤다 · 11-11 시험)
            _seq = max(_seq + 1, int(time.time() * 1000))
            _queue.append({"id": _seq, "at": time.time(), "kind": "place", "name": it["name"], "tel": it.get("tel", ""),
                           "addr": it.get("addr", ""), "url": it.get("url", ""), "m": it.get("m", 0), "walk": it.get("walk", 0),
                           "cat": it.get("kind", ""), "lat": it.get("lat"), "lng": it.get("lng"), "near": t.get("dest", "")})
            _cond.notify_all()
        ps.update(status="sent", name=it["name"], tel=bool(it.get("tel")))
    print(f"phonelink|send|{ps['status']}", flush=True)   # 콘솔에도 이름은 남기지 않는다
    return ps


RULES = """- **폰으로 보내기**([폰 보내기] 블록이 있을 때만): 서버가 이미 보냈거나 못 보낸 결과다 — 블록의 say 그대로 한 문장으로 말한다. card는 비우고(title="" items=[]) nav도 비운다"""


def block(msg, env):
    ps = _send(msg)
    if not ps:
        return None
    if ps["status"] == "sent":
        what = "전화번호랑 위치" if ps.get("tel") else "위치"
        return f'[폰 보내기] 보냈음 — {ps["name"]}의 {what}를 폰 현비서 앱으로 보냈다. say = "{ps["name"]} {what}, 폰으로 보냈어요." · listen=false · prio 2'
    if ps["status"] == "nophone":
        return '[폰 보내기] 못 보냄 — 폰 현비서 앱이 연결돼 있지 않다. say = "폰 현비서 앱이 꺼져 있어서 못 보냈어요. 폰에서 앱을 열어 주세요." · listen=false'
    if ps["status"] == "none":
        return "[폰 보내기] 보낼 장소가 없다(최근 1시간 안에 찾은 곳 없음) — 무엇을 보낼지 짧게 묻는다 · listen=true"
    return None


def post(rep, msg, b, dry, rec):
    global _offered_at
    say = rep.get("say") or ""
    if not dry and re.search(r"폰으로\s*보내\s*드릴까요|폰에\s*보내\s*드릴까요", say):
        _offered_at = time.time()   # 다음 "응"을 보내기로 알아듣게
    ps = _send(msg)   # 세션 두뇌(블록 없이 답하는 길)여도 보낸다
    if not ps:
        return
    _offered_at = 0.0
    rec["phone_send"] = ps.get("status")   # 로그엔 결과 종류만(가게 이름 X)
    rep["phone_sent"] = ps.get("status") == "sent"
    if not (rep.get("say") or "").strip() and ps.get("status") == "sent":
        rep["say"] = f'{ps["name"]} 정보를 폰으로 보냈어요.'


def _get_push(h, q):
    """폰 앱 long-poll — after(마지막으로 받은 id)보다 새 것이 생기면 바로, 아니면 timeout 뒤 빈 목록. 기다리는 동안 = 폰 연결됨"""
    global phone_seen
    after = int(q.get("after", 0) or 0)
    end = time.time() + min(float(q.get("timeout", 25) or 25), 50)
    with _cond:
        while True:
            phone_seen = time.time()
            _queue[:] = [x for x in _queue if time.time() - x["at"] < KEEP]
            items = [{k: v for k, v in x.items() if k != "at"} for x in _queue if x["id"] > after]
            left = end - time.time()
            if items or left <= 0:
                break
            _cond.wait(min(left, 10))
    h.send_json({"items": items, "seq": _seq, "car": _car_last()})


EVENT_LABEL = {"hello": "인사", "depart": "출발", "near": "곧 도착", "arrived": "도착", "half": "절반", "long_drive": "장시간 운전",
               "quiet": "조용한 틈", "restarea": "휴게소", "traffic": "교통", "no_dest": "목적지 묻기", "brief": "브리핑", "hvac": "공조"}


def _history(n):
    """로그 → [{t, src: car|phone, who: 운전자|현비서, q, a, card}] 시간순(오래된 것 먼저). 하네스 시험은 뺀다"""
    ins, out = {}, []
    for r in store.recent_log(800):
        if r.get("harness") or (r.get("ctx") or {}).get("harness"):
            continue
        if r.get("dir") == "in":
            ins[r.get("id")] = r
        elif r.get("dir") == "out" and r.get("say"):
            i = ins.get(r.get("id")) or {}
            src = "phone" if (i.get("ctx") or {}).get("device") == "phone" else "car"
            talk = i.get("kind") == "talk"
            ev = i.get("text", "")
            out.append({"t": (r.get("ts") or "")[:5], "ago": _ago(r.get("ts") or ""), "src": src, "who": "운전자" if talk else "현비서",
                        "q": i.get("text", "") if talk else EVENT_LABEL.get(ev, "먼저 말함"),
                        "a": r.get("say", ""), "card": (r.get("card") or {}).get("title", "")})
    return out[-n:]


def _ago(ts):
    """로그 시각(HH:MM:SS, 날짜 없음) → 지금부터 몇 분 전(자정을 넘었으면 하루 더한다)"""
    try:
        h, m = int(ts[:2]), int(ts[3:5])
    except ValueError:
        return None
    now = time.localtime()
    d = now.tm_hour * 60 + now.tm_min - (h * 60 + m)
    return d + 1440 if d < 0 else d


def _car_last():
    """차에서 마지막으로 나눈 말 — 폰 머리줄 '마지막 대화 N분 전'"""
    car = [x for x in _history(60) if x["src"] == "car"]
    return car[-1] if car else None


def _get_history(h, q):
    n = max(1, min(int(q.get("n", 30) or 30), 100))
    items = _history(n * 2)
    if q.get("src") in ("car", "phone"):
        items = [x for x in items if x["src"] == q["src"]]
    h.send_json({"items": items[-n:], "phone": phone_alive()})


GET = {"/hyun/push": _get_push, "/hyun/history": _get_history}
