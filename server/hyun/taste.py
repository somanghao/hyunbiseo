"""현비서 취향 기록 — 운전자 취향을 칸별로 누적(말한 횟수·처음/마지막) + 자주 묻는 것(분류·시간대) + 취향 질문 간격.

저장: data/taste.json (git 제외)
  slots: {칸: {"like": [{v, n, first, last}], "dislike": [...]}}
  asks:  {분류: {n, last, hours: {"08": 3}}}     ← 나중에 "자주 묻는 시간에 먼저 알려주기"의 재료
  asked: {slot, at}                              ← 마지막 취향 질문(주행당 한 번만 묻게)
  brief: {order: [weather·news…], set_at, last, asked_at}   ← 시동 브리핑 순서("뭐부터 들을까요?"의 대답)
  refine: {offset, at, note, runs}               ← 취향 정리 봇(taste_refine.py)이 어디까지 읽었나
쓰는 곳: hyun_server(대화 들어올 때 note_ask·brief_mode · 답 올 때 learn/mark_asked/brief_save · /hyun/taste · /hyun/me)
        brain_common(두뇌 질문에 [취향]·[브리핑] 블록) · taste_refine(정리 결과 apply_refine)
오래 안 나온 취향은 약하게: 점수 = 횟수 × 0.5^(마지막으로 나온 뒤 일수/30) — 순서·밀려남·[취향] 블록에 쓴다(기록 자체는 지우지 않는다)
"""
import json
import os
import re
import threading
import time

HERE = os.path.dirname(os.path.abspath(__file__))
FILE = os.path.join(os.environ.get("HYUN_DATA") or os.path.join(HERE, "data"), "taste.json")   # HYUN_DATA = 시험용 따로
_lock = threading.Lock()

# 칸 — (이름, 물어볼 때 예시). 순서 = 아직 모를 때 묻는 순서
SLOTS = {
    "food": ("음식", "좋아하는 음식이나 자주 드시는 메뉴가 있어요?"),
    "music": ("음악", "운전할 때 어떤 노래 들으세요?"),
    "movie": ("영화·드라마", "요즘 재밌게 본 영화나 드라마 있어요?"),
    "content": ("웹툰·웹소설", "보시는 웹툰이나 웹소설 있어요?"),
    "stock": ("관심 종목", "눈여겨보는 종목 있어요?"),
    "sports": ("응원팀·운동", "응원하는 팀이나 즐기는 운동 있어요?"),
    "news": ("관심 뉴스", "어떤 소식을 먼저 들으면 좋을까요?"),
    "place": ("자주 가는 곳", "자주 가는 곳이 있으면 알려 주세요."),
    "life": ("생활", "요즘 일상은 어떠세요?"),
    "talk": ("말투", "제가 말을 짧게 할까요, 조금 더 수다스럽게 할까요?"),
}
CAP = 8            # 칸마다 좋아함·싫어함 각각 최대 개수(점수 낮은 것부터 밀려남)
ASK_GAP = 3 * 3600  # 취향 질문은 3시간에 한 번(≈ 주행당 한 번)
HALF_LIFE = 30      # 일 — 이만큼 안 나오면 점수가 절반
STALE_DAYS = 60     # 이보다 오래 안 나온 취향은 [취향] 블록에 (오래됨)

# 시동 브리핑 — 고를 수 있는 것(정보 통로 이름 + 교통). 순서를 모르면 시동 때 "뭐부터 들을까요?"
BRIEF_CATS = {"weather": "날씨", "news": "뉴스", "stock": "주식", "fx": "환율", "movie": "영화", "webtoon": "웹툰", "webnovel": "웹소설", "traffic": "교통"}
BRIEF_DEFAULT = ["weather", "news", "traffic"]   # "브리핑 해 줘"인데 순서를 아직 모를 때
BRIEF_GAP = 3 * 3600         # 시동 브리핑은 3시간에 한 번(그 안에 다시 켜면 평소 인사)
BRIEF_ASK_WINDOW = 180       # "뭐부터?"를 물은 뒤 이 초 안의 말은 순서 대답으로 본다

# 자주 묻는 것 — 분류 → 말 패턴
ASK_CATS = {
    "traffic": ("교통·길", r"교통|막혀|막히|정체|길\s*(어때|막)|도착|몇\s*분\s*걸|우회|빠른\s*길"),
    "weather": ("날씨", r"날씨|기온|비\s*(와|올)|눈\s*(와|올)|우산|추워|더워|미세"),
    "movie": ("영화", r"영화|박스\s*오피스|개봉|극장"),
    "stock": ("주식", r"주식|코스피|코스닥|주가|종목|증시|시세"),
    "content": ("웹툰·웹소설", r"웹툰|웹소설|만화|노벨"),
    "news": ("뉴스", r"뉴스|소식|무슨\s*일"),
    "music": ("음악", r"노래|음악|틀어"),
    "food": ("음식·맛집", r"맛집|뭐\s*먹|배고|점심|저녁\s*메뉴|밥\s*먹|휴게소"),
    "time": ("시간·일정", r"몇\s*시|일정|약속|알람|알려\s*줘"),
    "car": ("차 상태", r"배터리|충전|주유|기름|타이어"),
}


def _now():
    return time.strftime("%Y-%m-%d %H:%M")


def load():
    try:
        with open(FILE, encoding="utf-8") as f:
            t = json.load(f)
    except (OSError, ValueError):
        t = {}
    t.setdefault("slots", {})
    t.setdefault("asks", {})
    t.setdefault("asked", {})
    return t


def _save(t):
    os.makedirs(os.path.dirname(FILE), exist_ok=True)
    tmp = FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(t, f, ensure_ascii=False, indent=1)
    os.replace(tmp, FILE)


def _key(v):
    return re.sub(r"\s+", "", v).lower()


def _days(stamp):
    try:
        return max(0.0, (time.time() - time.mktime(time.strptime(stamp, "%Y-%m-%d %H:%M"))) / 86400)
    except (TypeError, ValueError):
        return 0.0


def score(x):
    """취향 세기 — 말한 횟수에 '오래 안 나옴'을 곱해 약하게"""
    return x.get("n", 1) * 0.5 ** (_days(x.get("last")) / HALF_LIFE)


def _cap(lst):
    lst.sort(key=score, reverse=True)
    del lst[CAP:]


def learn(items):
    """두뇌가 돌려준 taste 항목 [{slot, value, how: like|dislike|forget}]을 누적. 반환 = 실제로 바뀐 항목 설명"""
    done = []
    items = [i for i in (items or []) if isinstance(i, dict) and i.get("slot") in SLOTS and (i.get("value") or "").strip()]
    if not items:
        return done
    with _lock:
        t = load()
        for i in items:
            slot, v, how = i["slot"], i["value"].strip()[:40], i.get("how") or "like"
            s = t["slots"].setdefault(slot, {"like": [], "dislike": []})
            k = _key(v)
            if how == "forget":
                for side in ("like", "dislike"):
                    before = len(s[side])
                    s[side] = [x for x in s[side] if _key(x["v"]) != k]
                    if len(s[side]) != before:
                        done.append(f"{SLOTS[slot][0]} 잊음: {v}")
                continue
            side, other = ("dislike", "like") if how == "dislike" else ("like", "dislike")
            s[other] = [x for x in s[other] if _key(x["v"]) != k]   # 마음이 바뀌면 반대쪽에서 뺀다
            hit = next((x for x in s[side] if _key(x["v"]) == k), None)
            if hit:
                hit["n"] += 1
                hit["last"] = _now()
                hit["v"] = v
            else:
                s[side].append({"v": v, "n": 1, "first": _now(), "last": _now()})
            _cap(s[side])
            done.append(f"{SLOTS[slot][0]} {'싫어함' if side == 'dislike' else '좋아함'}: {v}")
        _save(t)
    return done


def forget(slot, value):
    return learn([{"slot": slot, "value": value, "how": "forget"}])


def note_ask(text):
    """운전자가 한 말의 분류를 세고 시간대를 남긴다(자주 묻는 것)"""
    cats = [c for c, (_, pat) in ASK_CATS.items() if re.search(pat, text or "")]
    if not cats:
        return cats
    hh = time.strftime("%H")
    with _lock:
        t = load()
        for c in cats:
            a = t["asks"].setdefault(c, {"n": 0, "last": "", "hours": {}})
            a["n"] += 1
            a["last"] = _now()
            a["hours"][hh] = a["hours"].get(hh, 0) + 1
        _save(t)
    return cats


def mark_asked(slot):
    if slot not in SLOTS:
        return
    with _lock:
        t = load()
        t["asked"] = {"slot": slot, "at": time.time()}
        _save(t)


def unknown(t=None):
    t = t or load()
    return [k for k in SLOTS if not (t["slots"].get(k, {}).get("like") or t["slots"].get(k, {}).get("dislike"))]


def _top_hour(hours):
    return max(hours.items(), key=lambda kv: kv[1])[0] if hours else ""


def block():
    """두뇌 질문에 넣을 [취향] 블록 — 짧게"""
    t = load()
    lines = []

    def tag(x):   # 이름(횟수) · 오래 안 나왔으면 (오래됨)
        return x["v"] + (f"({x['n']})" if x["n"] > 1 else "") + ("(오래됨)" if _days(x.get("last")) > STALE_DAYS else "")
    for k, (name, _) in SLOTS.items():
        s = t["slots"].get(k) or {}
        like = "·".join(tag(x) for x in sorted(s.get("like", []), key=score, reverse=True)[:5])
        dis = "·".join(tag(x) for x in sorted(s.get("dislike", []), key=score, reverse=True)[:3])
        if like or dis:
            lines.append(f"  {name}: " + " / ".join(p for p in [f"좋아함 {like}" if like else "", f"싫어함 {dis}" if dis else ""] if p))
    asks = sorted(t["asks"].items(), key=lambda kv: -kv[1]["n"])[:4]
    ask_txt = " · ".join(f"{ASK_CATS[c][0]} {a['n']}번" + (f"(주로 {_top_hour(a['hours'])}시)" if a.get("hours") else "") for c, a in asks if c in ASK_CATS)
    unk = [SLOTS[k][0] for k in unknown(t)]
    gap_ok = time.time() - (t["asked"].get("at") or 0) > ASK_GAP
    out = ["[취향 — 누적 기록, 괄호 숫자 = 말한 횟수, (오래됨) = 두 달 넘게 안 나옴 → 약하게]"]
    out += lines or ["  아직 기록 없음"]
    if ask_txt:
        out.append(f"  자주 묻는 것: {ask_txt}")
    order = [BRIEF_CATS[c] for c in (t.get("brief") or {}).get("order", []) if c in BRIEF_CATS]
    if order:
        out.append(f"  시동 브리핑 순서: {' → '.join(order)}")
    if unk:
        nxt = next(k for k in SLOTS if SLOTS[k][0] == unk[0])
        out.append(f"  아직 모름: {'·'.join(unk)}")
        out.append(f"  취향 질문: {'가능 — 대화가 끝나 한가하면 아직 모르는 칸 하나를 자연스럽게 한 번(예: ' + SLOTS[nxt][1] + ')' if gap_ok else '이번 주행엔 이미 물어봄 — 묻지 않는다'}")
    return "\n".join(out)


def summary():
    """앱·웹에 보여 줄 모양 — 칸 이름·좋아함·싫어함 + 자주 묻는 것"""
    t = load()
    slots = []
    for k, (name, q) in SLOTS.items():
        s = t["slots"].get(k) or {}
        slots.append({"slot": k, "name": name, "like": s.get("like", []), "dislike": s.get("dislike", []), "ask": q})
    asks = [{"cat": c, "name": ASK_CATS[c][0], **a, "top": _top_hour(a.get("hours") or {})}
            for c, a in sorted(t["asks"].items(), key=lambda kv: -kv[1]["n"]) if c in ASK_CATS]
    known = sum(1 for s in slots if s["like"] or s["dislike"])
    b = t.get("brief") or {}
    brief = {"order": [{"cat": c, "name": BRIEF_CATS[c]} for c in b.get("order", []) if c in BRIEF_CATS], "set_at": b.get("set_at", "")}
    return {"slots": slots, "asks": asks, "known": known, "total": len(SLOTS), "asked": t.get("asked") or {},
            "brief": brief, "refine": {k: v for k, v in (t.get("refine") or {}).items() if k != "offset"}}


def lines_for_app(n=6):
    """정차 화면 '현비서가 아는 나' 줄 — 센 순(횟수 × 오래 안 나옴)"""
    t = load()
    out = []
    for k, (name, _) in SLOTS.items():
        s = t["slots"].get(k) or {}
        like = sorted(s.get("like", []), key=score, reverse=True)
        dis = sorted(s.get("dislike", []), key=score, reverse=True)
        if like:
            out.append((score(like[0]), f"{name}: {'·'.join(x['v'] for x in like[:3])}"))
        if dis:
            out.append((score(dis[0]), f"{name} 싫어함: {'·'.join(x['v'] for x in dis[:2])}"))
    return [x for _, x in sorted(out, key=lambda p: -p[0])[:n]]


# ---------- 시동 브리핑 순서 ----------
def brief_mode(kind, text, ctx):
    """이 메시지가 브리핑과 관련 있나 — None 또는 {mode, order}. 서버가 메시지를 받을 때 정해 두면 두뇌 질문([브리핑] 블록)과 답 기록이 같은 판정을 쓴다.
    mode: ask(시동인데 순서 모름 → 묻기) · give(시동, 정한 순서로) · answer(방금 물은 것의 대답) · request("브리핑 해 줘")"""
    ctx = ctx or {}
    if ctx.get("device") == "phone" or ctx.get("harness"):
        return None
    b = load().get("brief") or {}
    order = [c for c in b.get("order", []) if c in BRIEF_CATS]
    now = time.time()
    if kind == "event" and text == "hello":
        if now - (b.get("last") or 0) < BRIEF_GAP:
            return None   # 3시간 안에 이미 브리핑 — 평소 인사
        return {"mode": "give", "order": order} if order else {"mode": "ask", "order": []}
    if kind == "talk":
        if now - (b.get("asked_at") or 0) < BRIEF_ASK_WINDOW:
            return {"mode": "answer", "order": order}
        if re.search(r"브리핑", text or ""):
            return {"mode": "request", "order": order or BRIEF_DEFAULT}
    return None


def brief_save(order=None, mode=None):
    """두뇌가 준 순서(brief_order) 저장 · 브리핑을 물었으면 asked_at, 했으면 last"""
    order = [c for c in (order or []) if c in BRIEF_CATS]
    if not order and not mode:
        return None
    with _lock:
        t = load()
        b = t.setdefault("brief", {})
        if order:
            b["order"] = list(dict.fromkeys(order))[:5]
            b["set_at"] = _now()
        if mode == "ask":
            b["asked_at"] = time.time()
        elif mode in ("give", "answer", "request"):
            b["last"] = time.time()
            b.pop("asked_at", None)
        _save(t)
        return b.get("order")


# ---------- 취향 정리 봇(taste_refine.py)이 쓰는 것 ----------
def refine_state():
    return load().get("refine") or {}


def refine_mark(**kw):
    with _lock:
        t = load()
        t.setdefault("refine", {}).update(kw)
        _save(t)


def apply_refine(ops):
    """정리 결과 적용 — ops: [{op: add|merge|rename|move|drop, slot, value, into, to_slot, how}]. 횟수·처음/마지막은 합쳐서 지킨다. 반환 = 바뀐 설명"""
    done = []

    def find(lst, v):
        return next((x for x in lst if _key(x["v"]) == _key(v)), None)

    with _lock:
        t = load()
        for o in ops or []:
            op, slot, v = o.get("op"), o.get("slot"), (o.get("value") or "").strip()[:40]
            if slot not in SLOTS or not v:
                continue
            s = t["slots"].setdefault(slot, {"like": [], "dislike": []})
            name = SLOTS[slot][0]
            if op == "add":
                side, other = ("dislike", "like") if o.get("how") == "dislike" else ("like", "dislike")
                if find(s[side], v):
                    continue
                s[other] = [x for x in s[other] if _key(x["v"]) != _key(v)]
                s[side].append({"v": v, "n": 1, "first": _now(), "last": _now(), "src": "refine"})
                _cap(s[side])
                done.append(f"추가 {name} {'싫어함' if side == 'dislike' else '좋아함'}: {v}")
                continue
            side = next((sd for sd in ("like", "dislike") if find(s[sd], v)), None)
            if not side:
                continue
            item = find(s[side], v)
            if op == "drop":
                s[side].remove(item)
                done.append(f"뺌 {name}: {v}")
            elif op in ("merge", "rename"):
                into = (o.get("into") or "").strip()[:40]
                if not into or _key(into) == _key(v):
                    continue
                s[side].remove(item)
                tgt = find(s[side], into)
                if tgt:
                    tgt["n"] += item["n"]
                    tgt["first"] = min(tgt.get("first") or item["first"], item.get("first") or tgt["first"])
                    tgt["last"] = max(tgt.get("last") or "", item.get("last") or "")
                else:
                    item["v"] = into
                    s[side].append(item)
                done.append(f"{'합침' if tgt else '고침'} {name}: {v} → {into}")
            elif op == "move":
                to = o.get("to_slot")
                if to not in SLOTS or to == slot:
                    continue
                s[side].remove(item)
                d = t["slots"].setdefault(to, {"like": [], "dislike": []})
                tgt = find(d[side], item["v"])
                if tgt:
                    tgt["n"] += item["n"]
                else:
                    d[side].append(item)
                    _cap(d[side])
                done.append(f"옮김 {v}: {name} → {SLOTS[to][0]}")
        if done:
            _save(t)
    return done


if __name__ == "__main__":
    import sys
    sys.stdout.reconfigure(encoding="utf-8")
    print(block())
