"""현비서 중계 서버 — 에뮬 앱 ↔ Claude Code 세션(두뇌).

앱이 들은 말·주행 이벤트를 inbox에 쌓고, 세션이 long-poll(`wait.py`)로 감지해 답(`say.py`)을 돌려준다.
앱은 답을 long-poll로 받아 TTS로 말한다. 단일 inbox · 단일 소비자(세션 1개) 구조 — polling-strategy 스킬 참고.

실행: python -I server/hyun/hyun_server.py   (127.0.0.1:8790, 에뮬에선 http://10.0.2.2:8790)
기록: server/hyun/data/ (git 제외) — log.jsonl(대화 전부) · memory.json(현비서가 배운 것) · claude.json(로그인한 API 키) · feeds.json(통로 캐시)

두뇌 셋(우선순위 순): ① 떠 있는 Claude Code 세션(wait.py·say.py — 사람이 붙어 있는 시연)
② 클라우드 PC 두뇌(brain_cli.py) — 이 PC의 Claude Code에 사용자 본인 Claude 계정으로 로그인돼 있으면 `claude -p`로 자동 답(건당 요금 없음, 상용화 모델)
③ Claude API(로그인 페이지에서 API 키 등록, brain_api.py — 건당 요금)
세션이 기다리고 있으면 세션이 받고, 세션이 없거나 6초 넘게 아무도 안 받으면 ②, ②가 없으면 ③이 답한다.
정보 통로(feeds.py)는 GET /hyun/feed?cat=… 로 열려 있고, 서버가 켜져 있는 동안 10분마다 미리 데워 둔다.
로그인 페이지: http://127.0.0.1:8790/hyun/login (PC·폰 브라우저 — 차 화면엔 주소만 안내)
"""
import html
import importlib.util
import json
import re
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import urllib.request
from urllib.parse import parse_qs, quote, urlencode, urlparse

_HERE = os.path.dirname(os.path.abspath(__file__))


def _load(name, file):   # python -I는 스크립트 폴더를 sys.path에 넣지 않는다 — 파일 경로로 불러온다
    spec = importlib.util.spec_from_file_location(name, os.path.join(_HERE, file))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


feeds = _load("hyun_feeds", "feeds.py")
taste = _load("hyun_taste", "taste.py")                 # 취향 기록 — data/taste.json (칸별 누적 · 자주 묻는 것)
brain_cli = _load("hyun_brain_cli", "brain_cli.py")      # 클라우드 PC 두뇌 — 사용자 본인 Claude 계정의 Claude Code(claude -p)
refine = _load("hyun_taste_refine", "taste_refine.py")   # 취향 정리 봇 — 중간중간 대화를 다시 읽어 취향 기록을 다듬는다
refine.taste = taste                                      # 같은 잠금(taste._lock)으로 쓰게 — 답마다 하는 learn()과 겹치지 않게
try:
    brain_api = _load("hyun_brain_api", "brain_api.py")   # anthropic SDK가 없으면 API 두뇌만 빠진다
except ImportError as e:
    brain_api = None
    print(f"API 두뇌 없음: {e}", flush=True)

PORT = int(os.environ.get("HYUN_PORT", "8790"))
DATA = os.environ.get("HYUN_DATA") or os.path.join(os.path.dirname(os.path.abspath(__file__)), "data")   # 시험은 따로(HYUN_DATA — tools/hyun_e2e)
LOG = os.path.join(DATA, "log.jsonl")
MEMORY = os.path.join(DATA, "memory.json")
TASKS = os.path.join(DATA, "tasks.json")   # 폰(짭비서) 리마인더·할 일 — {reminders:[{id, at(ms), text, done}], todos:[{id, text, done}]}

cond = threading.Condition()   # 새 메시지(세션 쪽) · 새 답(앱 쪽) 모두 이 하나로 깨운다
inbox = []                     # {id, ts, kind, text, ctx, state: pending|answered|skipped}
replies = {}                   # id → {say, listen, mood, nav, route, prio(1~3 — 앱 말하기 스케줄러 단계), mode(폰 모드), tasks(바뀜), ts}
seq = 0
brain_seen = 0.0               # 세션이 마지막으로 wait 한 시각 — 앱이 "두뇌 연결" 표시에 쓴다
session_waiting = 0            # 지금 /hyun/wait에 걸려 있는 세션 수 — 0이면 API 두뇌가 대신 받을 수 있다
SESSION_FRESH = 60             # 세션이 이 초 안에 wait 했으면 '세션 연결'
API_TAKEOVER = 6               # 메시지가 이 초 동안 아무도 안 받으면 API가 받는다


def session_alive():
    return time.time() - brain_seen < SESSION_FRESH


def api_ready():
    return bool(brain_api and brain_api.load_cred())


def cli_ready():
    return brain_cli.ready()


def auto_engine():
    return "claude" if cli_ready() else "api" if api_ready() else None


def load_memory():
    try:
        with open(MEMORY, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {"name": "", "facts": []}


def save_memory(m):
    os.makedirs(DATA, exist_ok=True)
    tmp = MEMORY + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(m, f, ensure_ascii=False, indent=1)
    os.replace(tmp, MEMORY)


def load_tasks():
    try:
        with open(TASKS, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {"reminders": [], "todos": [], "seq": 0}


def save_tasks(t):
    os.makedirs(DATA, exist_ok=True)
    tmp = TASKS + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(t, f, ensure_ascii=False, indent=1)
    os.replace(tmp, TASKS)


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
    save_tasks(t)
    return True


def append_log(rec):
    os.makedirs(DATA, exist_ok=True)
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")


def recent_log(n):
    try:
        with open(LOG, encoding="utf-8") as f:
            lines = f.readlines()[-n:]
        return [json.loads(x) for x in lines]
    except OSError:
        return []


NUM_KO = {"한": 1, "하나": 1, "두": 2, "둘": 2, "세": 3, "셋": 3, "네": 4, "넷": 4, "다섯": 5, "여섯": 6, "일곱": 7, "여덟": 8, "아홉": 9, "열": 10}


def trim_card(card, text):
    """운전자가 개수를 말하면("세 개만", "5개") 카드도 그만큼 — 두뇌가 놓칠 때가 있어 서버가 맞춘다(하네스 q036, 11-06)"""
    if not card or not card.get("items") or not text:
        return card
    m = re.search(r"(\d{1,2}|한|하나|두|둘|세|셋|네|넷|다섯|여섯|일곱|여덟|아홉|열)\s*(개|편|위|가지|곳|줄)", text)
    if not m:
        return card
    n = int(m.group(1)) if m.group(1).isdigit() else NUM_KO[m.group(1)]
    if 0 < n < len(card["items"]):
        card = dict(card, items=card["items"][:n], **({"images": card["images"][:n]} if card.get("images") else {}))
    return card


def enrich_card(card):
    """카드에 그림 붙이기 — 웹툰이면 정보 통로의 표지 URL을 제목으로 맞춰 images[]에 · 소개 카드(info)면 위키미디어 공용 사진(두뇌는 URL을 다루지 않는다)"""
    if not card or not card.get("items"):
        return None
    if card.get("kind") == "info":
        q = card.get("image_query") or ""
        # 검색어에 팀·장소가 붙으면 안 나온다("Son Heung-min LAFC" 없음 → "Son Heung-min" 있음, 11-07) — 앞 3·2단어로 다시
        words = q.split()
        hit, other = None, False
        for cand in dict.fromkeys([q, " ".join(words[:3]), " ".join(words[:2])]):
            if cand and (hit := commons_image(cand)):
                break
        if not hit and "휴게소" in card.get("title", ""):   # 그 휴게소 사진이 없으면 다른 휴게소 사진을 '참고'로 밝히고 쓴다
            hit, other = commons_image("Korea expressway rest area"), True
        if hit:   # 앱은 서버를 거쳐 받는다(/hyun/img — UA·캐시)
            cap = f"{'참고 사진(다른 휴게소) ' if other else '사진 '}위키미디어 공용 {hit[1] or '자유 라이선스'}"
            card = dict(card, images=["/hyun/img?u=" + quote(hit[0], safe="")], source=(card.get("source", "") + " · " + cap).lstrip(" ·"))
        return card
    if "웹툰" in (card.get("title", "") + card.get("source", "")):
        pool = (feeds.get("webtoon").get("items") or [])
        norm = lambda x: re.sub(r"\s+", "", x or "")
        imgs = []
        for it in card["items"]:
            hit = next((w for w in pool if norm(w["title"]) and norm(w["title"]) in norm(it)), None)
            imgs.append((hit or {}).get("img", ""))
        if any(imgs):
            card = dict(card, images=imgs, kind="image_list")
    return card


TRAFFIC_Q = re.compile(r"교통|막혀|막히|정체|길\s*(어때|막)|앞길|도로\s*상황|몇\s*분\s*걸")


def traffic_card(c):
    """길·교통 질문의 표 — 앱이 내비에서 읽은 [길·교통](trafficAhead)으로 서버가 만든다(두뇌가 숫자를 옮기다 틀리지 않게)"""
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


IMG_UA = "HyunBiseo/0.1 (makecar-hy contest prototype; https://github.com/somanghao)"   # 위키미디어 정책 — 클라이언트를 밝히는 UA
_img_cache = {}


def commons_image(q):
    """위키미디어 공용(자유 라이선스)에서 사진 한 장 — (썸네일 URL, 라이선스, 파일 이름) 또는 None. 한국어 이름은 검색이 안 돼 영어 검색어로"""
    q = (q or "").strip()
    if not q:
        return None
    if q in _img_cache:
        return _img_cache[q]
    params = {"action": "query", "generator": "search", "gsrnamespace": 6, "gsrsearch": f"{q} filetype:bitmap", "gsrlimit": 6,
              "prop": "imageinfo", "iiprop": "url|extmetadata", "iiextmetadatafilter": "LicenseShortName", "iiurlwidth": 640, "format": "json"}
    hit = None
    try:
        req = urllib.request.Request("https://commons.wikimedia.org/w/api.php?" + urlencode(params), headers={"User-Agent": IMG_UA})
        with urllib.request.urlopen(req, timeout=6) as r:
            pages = sorted((json.loads(r.read()).get("query") or {}).get("pages", {}).values(), key=lambda x: x.get("index", 99))
        for pg in pages:
            ii = (pg.get("imageinfo") or [{}])[0]
            if ii.get("thumburl", "").lower().split("?")[0].endswith((".jpg", ".jpeg", ".png")):
                lic = ((ii.get("extmetadata") or {}).get("LicenseShortName") or {}).get("value", "")
                hit = (ii["thumburl"], lic, pg.get("title", "").removeprefix("File:"))
                break
    except (OSError, ValueError) as e:
        print(f"img|FAIL|{q}|{type(e).__name__}", flush=True)
    _img_cache[q] = hit
    return hit


def apply_answer(rid, b, by):
    """두뇌(세션·API)의 답을 등록 — 앱 long-poll을 깨우고, 그 id보다 앞선 미답은 지나간 것으로, 배운 것은 기억에"""
    with cond:
        _m = next((x for x in inbox if x["id"] == rid), None)
    src_text = (_m or {}).get("text", "")
    card = b.get("card")
    if (_m or {}).get("kind") == "talk" and TRAFFIC_Q.search(src_text) and not ((card or {}).get("items")):
        card = traffic_card((_m or {}).get("ctx") or {})   # 길·교통 질문 = 서버가 내비 값으로 표
    rep = {"say": b.get("say", ""), "listen": bool(b.get("listen")), "mood": b.get("mood", ""),
           "nav": b.get("nav"), "via": b.get("via"), "route": b.get("route"), "prio": b.get("prio"), "mode": b.get("mode"),
           "card": enrich_card(trim_card(card, src_text)),   # 목록 카드(정차 화면) — 말한 개수만큼 · 웹툰이면 표지 그림까지
           "ts": time.strftime("%H:%M:%S"), "by": by}
    rep["tasks"] = update_tasks(b.get("remind") or [], b.get("todo") or [], b.get("done") or [])
    with cond:
        replies[rid] = rep
        for m in inbox:
            if m["state"] == "pending" and m["id"] <= rid:
                m["state"] = "answered" if m["id"] == rid else "skipped"
        cond.notify_all()
    with cond:
        src = next((x for x in inbox if x["id"] == rid), None)
    dry = bool(src and (src.get("ctx") or {}).get("harness"))   # 하네스 시험 질문 — 취향·기억에 쓰지 않는다
    remember = [] if dry else [x for x in (b.get("remember") or []) if x.strip()]
    if remember or (b.get("name") and not dry):
        mem = load_memory()
        if b.get("name"):
            mem["name"] = b["name"]
        for x in remember:
            if x not in mem["facts"]:
                mem["facts"].append(x)
        save_memory(mem)
    learned = [] if dry else taste.learn(b.get("taste"))   # 취향 누적(칸·횟수)
    if b.get("asked_slot") and not dry:
        taste.mark_asked(b["asked_slot"])
    if learned:
        print(f"taste|{rid}|" + " · ".join(learned), flush=True)
    # 시동 브리핑 — 두뇌가 순서를 받았으면 저장, 이 메시지가 브리핑을 물었/했으면 시각 기록
    brief = None if dry else taste.brief_save(b.get("brief_order"), ((src or {}).get("brief") or {}).get("mode") if rep["say"] else None)
    if b.get("brief_order") and not dry:
        print(f"brief|{rid}|순서 {' → '.join(b['brief_order'])}", flush=True)
    append_log({"dir": "out", "id": rid, **rep, "remember": remember, "taste": learned, "asked_slot": b.get("asked_slot") or "",
                **({"brief_order": brief} if b.get("brief_order") and brief else {}), **({"harness": True} if dry else {})})


class Handler(BaseHTTPRequestHandler):
    # HTTP/1.1 keep-alive — 1.0(응답마다 연결 닫기)일 때 에뮬 앱이 "unexpected end of stream"을 자주 받았다(nc는 정상)
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):  # 콘솔 소음 줄이기(대화는 log.jsonl에)
        pass

    def send_json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n).decode("utf-8") or "{}") if n else {}

    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        if u.path == "/hyun/health":   # brain: 누구든 답할 수 있나 · mode: session|api|None · login: API 로그인 여부
            mode = "session" if session_alive() else auto_engine()
            return self.send_json({"ok": True, "brain": mode is not None, "mode": mode, "login": api_ready(), "claude": cli_ready()})
        if u.path == "/hyun/feed":     # 정보 통로 — cat=movie|weather|stock|webtoon|webnovel|channels
            cat = q.get("cat", "")
            if cat == "channels":
                return self.send_json({"channels": feeds.CHANNELS, "check": feeds.channels().splitlines() if q.get("check") else None})
            if cat not in feeds.FETCH:
                return self.send_json({"error": f"cat은 {list(feeds.FETCH)} 중 하나"}, 400)
            top = int(q.get("top", 5))
            r = feeds.get(cat, fresh=bool(q.get("fresh")), top=top)
            return self.send_json({"text": feeds.text(cat, top=top), **{k: v for k, v in r.items() if k != "_at"}})
        if u.path == "/hyun/login":    # Claude 로그인 페이지(API 키 등록)
            return self.send_html(login_page())
        if u.path == "/hyun/login/status":
            return self.send_json({"api": brain_api.status() if brain_api else {"loggedIn": False, "error": "anthropic SDK 없음"},
                                   "claude": brain_cli.status(force=bool(q.get("fresh")))})
        if u.path == "/hyun/memory":   # 앱 정차 화면 — 취향 줄(많이 말한 순) + 기억
            return self.send_json({**load_memory(), "taste": taste.lines_for_app()})
        if u.path == "/hyun/taste":    # 취향 기록 전체(칸 · 좋아함/싫어함 · 횟수 · 자주 묻는 것)
            return self.send_json(taste.summary())
        if u.path == "/hyun/me":       # '현비서가 아는 나' 페이지(PC·폰 브라우저)
            with open(os.path.join(_HERE, "me.html"), encoding="utf-8") as f:
                return self.send_html(f.read())
        if u.path == "/hyun/tasks":
            return self.send_json(load_tasks())
        if u.path == "/hyun/taste/refine":   # 취향 정리 봇 상태 + 최근 기록 5개
            try:
                with open(refine.OUT, encoding="utf-8") as f:
                    last = [json.loads(x) for x in f.readlines()[-5:]]
            except (OSError, ValueError):
                last = []
            return self.send_json({"state": {k: v for k, v in taste.refine_state().items() if k != "offset"}, "due": refine.due(), "recent": last})
        if u.path == "/hyun/img":           # 카드 사진 중계(위키미디어 공용만) — 앱은 UA·캐시 걱정 없이 여기서 받는다
            src = q.get("u", "")
            if not src.startswith(("https://upload.wikimedia.org/", "https://thumb.wikimedia.org/")):
                return self.send_json({"error": "commons only"}, 400)
            path = os.path.join(DATA, "img", re.sub(r"[^A-Za-z0-9._-]", "_", urlparse(src).path[-90:]))
            if not os.path.exists(path):
                try:
                    with urllib.request.urlopen(urllib.request.Request(src, headers={"User-Agent": IMG_UA}), timeout=10) as r:
                        body = r.read()
                    os.makedirs(os.path.dirname(path), exist_ok=True)
                    with open(path, "wb") as f:
                        f.write(body)
                except OSError as e:
                    return self.send_json({"error": type(e).__name__}, 502)
            with open(path, "rb") as f:
                body = f.read()
            self.send_response(200)
            self.send_header("Content-Type", "image/png" if path.lower().endswith(".png") else "image/jpeg")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            return self.wfile.write(body)
        if u.path == "/hyun/testaudio":      # 귀 시험 녹음(16kHz mono PCM) — 앱 --es earfile <이름>
            name = q.get("name", "")
            path = os.path.join(DATA, "testaudio", name + ".pcm")
            if not re.fullmatch(r"[A-Za-z0-9_-]{1,40}", name) or not os.path.exists(path):
                return self.send_json({"error": "no such audio"}, 404)
            with open(path, "rb") as f:
                body = f.read()
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            return self.wfile.write(body)
        if u.path == "/hyun/log":
            return self.send_json(recent_log(int(q.get("n", 40))))
        if u.path == "/hyun/wait":   # 세션(두뇌) long-poll: 답 안 한 메시지가 생길 때까지 잡아 둔다
            return self.wait_inbox(float(q.get("timeout", 50)))
        if u.path == "/hyun/reply":  # 앱 long-poll: 내 메시지(id)에 답이 올 때까지
            return self.wait_reply(int(q.get("id", 0)), float(q.get("timeout", 25)))
        self.send_json({"error": "not found"}, 404)

    def do_POST(self):
        global seq
        u = urlparse(self.path)
        b = self.body()
        if u.path == "/hyun/in":     # 앱 → 들은 말(talk) 또는 주행 이벤트(event)
            cid = b.get("cid")
            with cond:
                dup = next((m for m in inbox if cid and m.get("cid") == cid), None)
                if dup:              # 앱이 응답을 못 받아 다시 보낸 것 — 같은 id를 돌려준다
                    return self.send_json({"id": dup["id"]})
                seq += 1
                msg = {"id": seq, "ts": time.strftime("%H:%M:%S"), "kind": b.get("kind", "talk"),
                       "text": (b.get("text") or "").strip(), "ctx": b.get("ctx") or {}, "state": "pending", "cid": cid, "_t": time.time()}
                # 시동 브리핑과 관련 있나(시동 · "뭐부터?"의 대답 · "브리핑 해 줘") — 두뇌 질문의 [브리핑] 블록과 답 기록이 같이 쓴다
                msg["brief"] = taste.brief_mode(msg["kind"], msg["text"], msg["ctx"])
                inbox.append(msg)
                del inbox[:-50]
                cond.notify_all()
            append_log({"dir": "in", **{k: msg[k] for k in ("id", "ts", "kind", "text", "ctx")}, **({"brief": msg["brief"]["mode"]} if msg["brief"] else {})})
            if msg["kind"] == "talk" and not msg["ctx"].get("harness"):
                taste.note_ask(msg["text"])   # 자주 묻는 것(분류·시간대) — 하네스 시험 질문은 세지 않는다
            if msg["kind"] == "event" and msg["text"] == "arrived" and not msg["ctx"].get("harness"):
                refine_flag.set()             # 도착 = 취향 정리 차례(앱을 꺼도 서버가 한다)
            return self.send_json({"id": msg["id"]})
        if u.path == "/hyun/taste/refine":   # 취향 정리 지금 {dry?, all?}
            r = refine.run(dry=bool(b.get("dry")), all_=bool(b.get("all")), why="요청")
            return self.send_json({**r, **taste.summary()})
        if u.path == "/hyun/taste/forget":   # {slot, value} — '현비서가 아는 나'에서 지우기
            return self.send_json({"done": taste.forget(b.get("slot", ""), b.get("value", "")), **taste.summary()})
        if u.path == "/hyun/tasks":  # 앱 → 울린 리마인더 끝냄 표시 {fired: id}
            t = load_tasks()
            for r in t["reminders"]:
                if r["id"] == b.get("fired"):
                    r["done"] = True
            save_tasks(t)
            append_log({"dir": "app", "ts": time.strftime("%H:%M:%S"), "fired": b.get("fired")})
            return self.send_json({"ok": True})
        if u.path == "/hyun/answer":  # 세션 → 답. 그 id보다 앞선 미답 메시지는 지나간 것으로 본다
            apply_answer(int(b.get("id", 0)), b, by="session")
            return self.send_json({"ok": True})
        if u.path == "/hyun/login":   # {key, model} — 짧은 요청으로 확인한 뒤 저장
            if not brain_api:
                return self.send_json({"ok": False, "msg": "서버에 anthropic SDK가 없어요(pip install anthropic)"})
            key, model = (b.get("key") or "").strip(), b.get("model") or brain_api.DEFAULT_MODEL
            if not key.startswith("sk-ant-"):
                return self.send_json({"ok": False, "msg": "Anthropic API 키는 sk-ant- 로 시작해요"})
            ok, msg, ms = brain_api.check(key, model)
            if ok:
                brain_api.save_cred(key, model)
            append_log({"dir": "app", "ts": time.strftime("%H:%M:%S"), "login": ok, "model": model, "ms": ms})
            return self.send_json({"ok": ok, "msg": msg, "ms": ms, **brain_api.status()})
        if u.path == "/hyun/logout":
            if brain_api:
                brain_api.logout()
            return self.send_json({"ok": True})
        self.send_json({"error": "not found"}, 404)

    def send_html(self, text):
        body = text.encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def wait_inbox(self, timeout):
        global brain_seen, session_waiting
        end = time.time() + min(timeout, 110)
        with cond:
            session_waiting += 1
            try:
                while True:
                    brain_seen = time.time()
                    pending = [m for m in inbox if m["state"] == "pending"]
                    left = end - time.time()
                    if pending or left <= 0:
                        break
                    cond.wait(min(left, 20))   # 20초마다 깨어나 brain_seen 갱신(앱의 "두뇌 연결" 표시)
            finally:
                session_waiting -= 1
        self.send_json({"pending": pending})

    def wait_reply(self, rid, timeout):
        end = time.time() + min(timeout, 60)
        skipped = False
        with cond:
            while rid not in replies:
                skipped = any(m["id"] == rid and m["state"] == "skipped" for m in inbox)
                left = end - time.time()
                if skipped or left <= 0:
                    break
                cond.wait(left)
            rep = replies.get(rid)
        if rep:
            return self.send_json({"id": rid, **rep})
        # 두뇌가 더 새 메시지에 답하며 건너뛴 것 — 앱은 대신 말하지 않는다
        self.send_json({"id": rid, "skipped": True} if skipped else {"id": rid, "pending": True})


def _recent_text(n=8):
    out = []
    for r in recent_log(40):
        if r.get("harness") or (r.get("ctx") or {}).get("harness"):
            continue   # 하네스 시험 대화는 실제 대화 맥락에 넣지 않는다
        if r.get("dir") == "in" and r.get("kind") == "talk":
            out.append(f"운전자: {r.get('text', '')}")
        elif r.get("dir") == "out" and r.get("say"):
            out.append(f"현비서: {r['say']}")
    return out[-n:]


def brain_worker():
    """세션이 안 받는 메시지를 자동 두뇌가 받는다 — ② 내 Claude 계정(claude -p) 우선, 없으면 ③ API. 세션처럼 가장 최근 것에 답하고 앞의 것은 건너뜀"""
    while True:
        with cond:
            cond.wait(1.0)
            pending = [m for m in inbox if m["state"] == "pending" and not m.get("auto")]
            if not pending:
                continue
            m = pending[-1]
            age = time.time() - m.get("_t", time.time())
            if session_alive() and (session_waiting > 0 or age < API_TAKEOVER):
                continue   # 세션이 받을 차례
            engine = auto_engine()
            if not engine:
                continue
            m["auto"] = True
        # 스포츠·뉴스처럼 최신 사실이 필요한 말이면 내 계정 두뇌에 웹 검색을 열어 준다
        search = engine == "claude" and ((m["kind"] == "talk" and brain_cli.common.needs_search(m["text"])) or (m["kind"] == "event" and m["text"] == "restarea"))
        if engine == "claude":
            recent = [] if (m.get("ctx") or {}).get("harness") else _recent_text()
            data, err = brain_cli.answer(m, load_memory(), recent, search=search)
        else:
            data, err = brain_api.answer(m, load_memory(), _recent_text())
        if search:
            engine = "claude+search"
        if data:
            # 자동 두뇌의 평평한 필드 → say.py와 같은 모양(nav{name,lat,lng} · route)
            if (data.get("nav_name") or "").strip() and data.get("nav_lat") and data.get("nav_lng"):
                data["nav"] = {"name": data["nav_name"].strip(), "lat": float(data["nav_lat"]), "lng": float(data["nav_lng"])}
            if (data.get("via_name") or "").strip() and data.get("via_lat") and data.get("via_lng"):
                data["via"] = {"name": data["via_name"].strip(), "lat": float(data["via_lat"]), "lng": float(data["via_lng"])}
            data["route"] = data.get("route") or None
            print(f"{engine}|{m['id']}|{data.get('_model')}|{data.get('_ms', '')}ms|{data.get('say')}", flush=True)
            apply_answer(m["id"], data, by=engine)
        else:
            print(f"{engine}|{m['id']}|FAIL|{err}", flush=True)
            append_log({"dir": "app", "ts": time.strftime("%H:%M:%S"), "auto_fail": err, "engine": engine, "id": m["id"]})


def cli_warmup():
    """첫 claude -p는 준비에 ~28초(실측) — 서버가 켜질 때 짧은 요청으로 미리 데운다(로그인돼 있을 때만)"""
    if not cli_ready():
        return
    t0 = time.time()
    _d, err = brain_cli.answer({"kind": "event", "text": "warmup", "ctx": {}}, {}, [])
    print(f"claude|warmup|{int((time.time() - t0) * 1000)}ms|{err or 'ok'}", flush=True)


refine_flag = threading.Event()   # 도착 이벤트가 오면 켠다 — 취향 정리 봇이 다음 차례에 바로 돈다


def taste_refiner():
    """취향 정리 봇 — 30초마다 due()를 보고(새 대화 8마디 · 3마디+2분 조용 · 도착 뒤 · 30분+조용) 내 Claude 계정으로 정리한다"""
    time.sleep(20)
    while True:
        try:
            arrived = refine_flag.is_set()
            if brain_cli.ready() and refine.due(arrived=arrived):
                refine_flag.clear()
                r = refine.run(why="도착" if arrived else "주기")
                print(f"refine|{r.get('talks')}마디|{r.get('ms', '')}ms|{r.get('note') or r.get('err')}|" + " · ".join(r.get("done") or []), flush=True)
        except Exception as e:   # 봇이 죽어도 서버는 산다
            print(f"refine|FAIL|{type(e).__name__} {e}", flush=True)
        time.sleep(30)


def feed_warmer():
    """정보 통로를 미리 데워 둔다 — 두뇌가 물을 때 캐시에서 즉시(통로별 TTL이 지난 것만 새로)"""
    while True:
        for c in feeds.FETCH:
            feeds.get(c)
        time.sleep(600)


def login_page():
    st = brain_api.status() if brain_api else {"loggedIn": False}
    opts = "".join(f'<option value="{k}"{" selected" if k == (st.get("model") or brain_api.DEFAULT_MODEL) else ""}>{html.escape(v)}</option>'
                   for k, v in (brain_api.MODELS.items() if brain_api else []))
    now = (f'로그인됨 · {html.escape(st["model"])} · 키 끝 {html.escape(st["keyTail"])} · {html.escape(st.get("at") or "")}'
           if st.get("loggedIn") else "로그인 안 됨")
    cs = brain_cli.status(force=True)
    cli_now = (f"로그인됨 · {html.escape(str(cs.get('authMethod')))} 계정 · 모델 {html.escape(str(cs.get('model')))} · 현비서가 이 계정으로 답해요"
               if cs.get("loggedIn") else f"로그인 안 됨 — 두뇌 PC 터미널에서 claude auth login {html.escape(cs.get('error', ''))}")
    return f"""<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>현비서 Claude 로그인</title><style>
:root{{--bg:#f4f6f8;--card:#fff;--fg:#15202b;--mut:#66727f;--ln:#d9dfe5;--ac:#0a7ea4;--ok:#138a52;--bad:#c4321f}}
@media (prefers-color-scheme:dark){{:root{{--bg:#0d1216;--card:#151c22;--fg:#e7edf2;--mut:#93a1ad;--ln:#2a353e;--ac:#38b6d8;--ok:#3ccf8e;--bad:#ff6b5a}}}}
body{{margin:0;background:var(--bg);color:var(--fg);font:15px/1.6 "Malgun Gothic",system-ui,sans-serif}}
main{{max-width:520px;margin:0 auto;padding:28px 16px}}.card{{background:var(--card);border:1px solid var(--ln);border-radius:14px;padding:18px 20px}}
h1{{font-size:22px;margin:0 0 4px}}p{{color:var(--mut);margin:6px 0 14px}}label{{display:block;font-size:13px;color:var(--mut);margin:12px 0 4px}}
input,select{{width:100%;box-sizing:border-box;font:inherit;padding:9px 11px;border-radius:9px;border:1px solid var(--ln);background:var(--bg);color:var(--fg)}}
button{{margin-top:16px;font:inherit;padding:9px 16px;border-radius:9px;border:0;background:var(--ac);color:#fff;cursor:pointer}}
button.sub{{background:transparent;color:var(--mut);border:1px solid var(--ln);margin-left:6px}}#msg{{margin-top:12px;font-weight:600}}
.st{{font-size:13px;padding:6px 10px;border-radius:8px;background:var(--bg);border:1px solid var(--ln);display:inline-block}}
</style></head><body><main><div class="card">
<h1>현비서 · Claude 로그인</h1>
<h2 style="font-size:16px;margin:14px 0 4px">① 내 Claude 계정 — 클라우드 PC (추천 · 건당 요금 없음)</h2>
<p>현비서 두뇌 PC의 Claude Code에 <b>본인 Claude 계정</b>으로 로그인하면, 현비서가 그 계정 안에서 답합니다. 로그인은 그 PC 터미널에서 <code>claude auth login</code>을 실행해 뜨는 <b>Anthropic 공식 로그인 화면</b>에서 직접 합니다 — 현비서는 계정 정보를 받거나 저장하지 않습니다.</p>
<div class="st" id="cst">{cli_now}</div>
<h2 style="font-size:16px;margin:22px 0 4px">② Anthropic API 키 (대안 · 건당 요금)</h2>
<p>①이 없을 때 쓰입니다. 키는 이 PC의 현비서 서버(<code>server/hyun/data/</code>)에만 저장되고 차나 화면에는 보내지 않습니다.</p>
<div class="st" id="st">{now}</div>
<label for="key">Anthropic API 키 (console.anthropic.com → API Keys)</label>
<input id="key" type="password" placeholder="sk-ant-..." autocomplete="off">
<label for="model">모델</label><select id="model">{opts}</select>
<button id="go">연결하고 저장</button><button class="sub" id="out">로그아웃</button>
<div id="msg"></div></div></main>
<script>
const $=s=>document.querySelector(s), msg=(t,ok)=>{{$('#msg').textContent=t;$('#msg').style.color=ok?'var(--ok)':'var(--bad)'}};
$('#go').onclick=async()=>{{msg('확인 중…',true);try{{const r=await(await fetch('/hyun/login',{{method:'POST',headers:{{'Content-Type':'application/json'}},body:JSON.stringify({{key:$('#key').value,model:$('#model').value}})}})).json();
msg(r.msg+(r.ms?` · ${{r.ms}}ms`:''),r.ok);if(r.ok){{$('#key').value='';$('#st').textContent=`로그인됨 · ${{r.model}} · 키 끝 ${{r.keyTail}}`}}}}catch(e){{msg('서버에 닿지 않아요',false)}}}};
$('#out').onclick=async()=>{{await fetch('/hyun/logout',{{method:'POST'}});$('#st').textContent='로그인 안 됨';msg('로그아웃했어요',true)}};
</script></body></html>"""


if __name__ == "__main__":
    os.makedirs(DATA, exist_ok=True)
    threading.Thread(target=brain_worker, daemon=True, name="auto-brain").start()
    threading.Thread(target=cli_warmup, daemon=True, name="claude-warmup").start()
    threading.Thread(target=feed_warmer, daemon=True, name="feed-warmer").start()
    threading.Thread(target=taste_refiner, daemon=True, name="taste-refiner").start()
    print(f"hyun server: http://127.0.0.1:{PORT}/hyun/health  (emulator: http://10.0.2.2:{PORT}) · "
          f"login http://127.0.0.1:{PORT}/hyun/login · 내 Claude 계정 {'로그인됨' if cli_ready() else '없음'} · API {'로그인됨' if api_ready() else '없음'}", flush=True)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
