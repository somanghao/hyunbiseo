"""짭비서(폰) — 모드(대중교통·산책·업무중) 바꾸기 · 리마인더 · 할 일 → data/tasks.json, 앱이 /hyun/tasks로 받아 예약(울리면 P0).
두뇌 필드(세션 say.py): mode · remind[{at(ms), text}] · todo[] · done[](글자 일부). 앱 짝 = feature/PhoneFeature.kt
"""
import time

from core import config, store

TASKS = config.data("tasks.json")   # {reminders:[{id, at(ms), text, done}], todos:[{id, text, done}], seq}


def load_tasks():
    return store.read_json(TASKS, {"reminders": [], "todos": [], "seq": 0})


def update_tasks(remind, todo, done):
    """두뇌가 준 리마인더(at ms·text)·할 일 추가, done(글자 일부)로 끝냄 표시. 바뀌었으면 True"""
    if not (remind or todo or done):
        return False
    t = load_tasks()
    for r in remind:
        t["seq"] += 1
        t["reminders"].append({"id": t["seq"], "at": int(r["at"]), "text": r["text"], "done": False})
    for x in todo:
        t["seq"] += 1
        t["todos"].append({"id": t["seq"], "text": x, "done": False})
    for d in done:
        for item in t["reminders"] + t["todos"]:
            if d and d in item["text"]:
                item["done"] = True
    now = time.time() * 1000   # 지난 지 하루 넘은 리마인더·끝낸 할 일은 정리
    t["reminders"] = [r for r in t["reminders"] if not (r["done"] and r["at"] < now - 86400_000)]
    t["todos"] = [x for x in t["todos"] if not x["done"]] + [x for x in t["todos"] if x["done"]][-5:]
    store.write_json(TASKS, t)
    return True


def post(rep, msg, b, dry, rec):
    rep["mode"] = b.get("mode")
    rep["tasks"] = update_tasks(b.get("remind") or [], b.get("todo") or [], b.get("done") or [])


def _get_tasks(h, q):
    h.send_json(load_tasks())


def _post_tasks(h, b):
    """앱 → 울린 리마인더 끝냄 표시 {fired: id}"""
    t = load_tasks()
    for r in t["reminders"]:
        if r["id"] == b.get("fired"):
            r["done"] = True
    store.write_json(TASKS, t)
    store.append_log({"dir": "app", "ts": time.strftime("%H:%M:%S"), "fired": b.get("fired")})
    h.send_json({"ok": True})


GET = {"/hyun/tasks": _get_tasks}
POST = {"/hyun/tasks": _post_tasks}
