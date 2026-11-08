"""inbox — 앱이 보낸 말·이벤트를 쌓고, 두뇌(세션 wait.py·자동 두뇌)의 답을 앱 long-poll로 넘긴다. 단일 inbox · 단일 소비자.

답 등록(apply_answer) = 기본 답(say·listen·mood·prio) + 기능들의 post 훅(nav·via·카드·취향·브리핑·할 일 …)이 각자 필드를 채우고 저장한다.
"""
import threading
import time

import features
from core import config, store

cond = threading.Condition()   # 새 메시지(세션 쪽) · 새 답(앱 쪽) 모두 이 하나로 깨운다
inbox = []                     # {id, ts, kind, text, ctx, state: pending|answered|skipped, cid, _t, auto?, + 기능이 적은 판정(brief …)}
replies = {}                   # id → 앱에 갈 답
_seq = 0
brain_seen = 0.0               # 세션이 마지막으로 wait 한 시각 — 앱의 "두뇌 연결" 표시
session_waiting = 0            # 지금 /hyun/wait에 걸려 있는 세션 수 — 0이면 자동 두뇌가 대신 받을 수 있다


def session_alive():
    return time.time() - brain_seen < config.SESSION_FRESH


def add_message(kind, text, ctx, cid):
    """앱 → 들은 말(talk)·주행 이벤트(event). 응답을 못 받아 다시 보낸 것(같은 cid)이면 같은 id. → (id, msg)"""
    global _seq
    with cond:
        dup = next((m for m in inbox if cid and m.get("cid") == cid), None)
        if dup:
            return dup["id"], None
        _seq += 1
        msg = {"id": _seq, "ts": time.strftime("%H:%M:%S"), "kind": kind or "talk", "text": (text or "").strip(),
               "ctx": ctx or {}, "state": "pending", "cid": cid, "_t": time.time()}
        features.hook("on_in", msg)   # 기능 판정을 메시지에 적어 둔다(브리핑 판정 · 자주 묻는 것 · 도착 플래그 …)
        inbox.append(msg)
        del inbox[:-50]
        cond.notify_all()
    rec = {"dir": "in", **{k: msg[k] for k in ("id", "ts", "kind", "text", "ctx")}}
    if msg.get("brief"):
        rec["brief"] = msg["brief"]["mode"]
    store.append_log(rec)
    return msg["id"], msg


def find(rid):
    with cond:
        return next((x for x in inbox if x["id"] == rid), None)


def apply_answer(rid, b, by):
    """두뇌(세션·자동)의 답 등록 — 기능 post 훅이 필드를 채우고(카드 손질·취향·기억·할 일 …), 앱 long-poll을 깨우고, 앞선 미답은 지나간 것으로"""
    msg = find(rid) or {"id": rid, "kind": "", "text": "", "ctx": {}}
    dry = bool((msg.get("ctx") or {}).get("harness"))   # 하네스 시험 질문 — 취향·기억에 쓰지 않는다
    rep = {"say": b.get("say", ""), "listen": bool(b.get("listen")), "mood": b.get("mood", ""), "prio": b.get("prio"),
           "ts": time.strftime("%H:%M:%S"), "by": by}
    rec = {}                                             # 로그에만 남길 것(기능이 채움 — remember · taste · asked_slot · brief_order)
    for f in features.ALL:
        post = getattr(f, "post", None)
        if post:
            post(rep, msg, b, dry, rec)
    with cond:
        replies[rid] = rep
        for m in inbox:
            if m["state"] == "pending" and m["id"] <= rid:
                m["state"] = "answered" if m["id"] == rid else "skipped"
        cond.notify_all()
    logged = rep
    if (rep.get("card") or {}).get("nostore"):   # 저장하면 안 되는 결과(카카오 장소 — 약관)는 로그에 개수만
        logged = {**rep, "card": {"title": rep["card"].get("title"), "source": rep["card"].get("source"), "n": len(rep["card"].get("items") or [])}}
    store.append_log({"dir": "out", "id": rid, **logged, **rec, **({"harness": True} if dry else {})})
