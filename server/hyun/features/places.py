"""도착 추천 · 주변 장소 — 식사 시간에 도착하는 길이면 곧 도착(near)에 한마디, 도착(arrived)하면 목적지 근처 취향 맞춤 식당 카드.
"근처 맛집"·"점심 어디서 먹지"처럼 물어도 같은 검색. 고르면 두뇌가 nav(그 식당 좌표)로 — 앱 RouteFeature.goTo 그대로.
명세 = dashboard/hyun/specs/arrive.md (결정 11-08) · 앱 짝 = feature/ArriveFeature.kt(한 줄 · 시연 시계) + RouteFeature(맥락 destLat·destLng)

카카오 로컬(키 = data/places.json — 시험 데이터(HYUN_DATA)를 써도 진짜 data에서 읽는다):
  - **실시간 호출만** — 결과를 파일에 쓰지 않는다(카카오 약관 — 장소 결과 서버 저장 금지). 그 주행 동안 메모리에만(_trips)
  - 로그엔 장소 목록 대신 개수만(카드 nostore → inbox.apply_answer가 items를 지움)
판정은 서버가 한다(두뇌는 말만): 식사 시간(점심 11:00~13:30 · 저녁 17:30~20:00, 도착 예정 기준) · 목적지가 식당·카페면 안 함 ·
같은 목적지는 하루 한 번(곧 도착 → 도착은 같은 결과를 다시 씀).
"""
import json
import math
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

from core import config

KEYS = f"{config.SHARED_DATA}/places.json"
MEALS = (("점심", 11 * 60, 13 * 60 + 30), ("저녁", 17 * 60 + 30, 20 * 60))
RADIUS = 500          # 목적지에서 m(걸어서 7분 안팎) — 3곳 못 채우면 1000
SIZE = 5
WALK = 67             # m/분(시속 4km)
NEAR_CAR = 500        # 정차 중 고른 곳이 이보다 가까우면 걸어서 안내, 멀면 차로(nav)
FOOD = ("FD6", "CE7")  # 음식점 · 카페
# 장소를 찾는 말만 — "나 국밥 좋아해"(취향 말하기)엔 켜지지 않게 음식 이름은 넣지 않는다(11-08 E2E: 취향 말에 엉뚱한 목적지 근처 카드)
# 경유 요청 — "가는 길에 가까운 세탁소 들렀다가 집으로 가자" · "주유소 들르자" → 그 장소를 지금 위치 근처에서 찾아 경유지 후보로(11-10:
# 식사·카페만 찾아서 세탁소는 두뇌가 "위치를 몰라요"로 되물었다)
VIA = re.compile(r"([가-힣A-Za-z0-9]{2,12})\s*(?:에|을|를|좀)?\s*(?:들렀다|들러|들려|들르|경유)")


def _via_query(text):
    m = VIA.search(text or "")
    if not m:
        return ""
    w = re.sub(r"^(가까운|근처|근처의)", "", m.group(1))
    if len(w) < 2 or re.search(r"(러|고|서|게|면|길에)$", w):   # "찾으러 들렀다" 같은 말 — 장소 이름이 아니다
        return ""
    return w


TALK = re.compile(r"맛집|식당|먹을\s*(데|곳|만한)|(어디서|뭐)\s*먹|배고|카페|커피\s*(마실|어디|한\s*잔)|(점심|저녁|밥)\s*(어디|뭐|추천|먹으러|먹을)|근처\s*(밥|먹)")
# 운전자가 음식을 콕 집어 물음 — "도착지 근처 맛난 족발집 알려 줘" → "족발"로(취향 음식 대신, 11-11 어필 영상)
FOODQ = re.compile(r"([가-힣]{2,6}?)\s*(?:집|맛집|가게)\s*(?:좀\s*|하나\s*)?(?:알려|추천|찾아|어디|있어|있나)")
_NOT_FOOD = {"근처", "주변", "가까운", "도착지", "목적지", "맛있는", "맛난", "유명한", "좋은", "괜찮은", "동네", "우리", "저희", "단골"}


def _food_query(text):
    m = FOODQ.search(text or "")
    w = m.group(1) if m else ""
    return "" if not w or w in _NOT_FOOD else w


PICK_MIN = 10 * 60    # 추천 뒤 이 안의 말은 고르기일 수 있다("응" · "두 번째로 가자") — [주변 장소]를 다시 싣는다

_lock = threading.Lock()
_trips = {}           # 목적지 키 → {at, day, meal, arrive, query, why, items, dest, food, told}


def _key():
    try:
        with open(KEYS, encoding="utf-8") as f:
            return (json.load(f).get("kakao") or "").strip()
    except (OSError, ValueError):
        return ""


def kakao(params, path="keyword"):
    """카카오 로컬 검색 — documents 목록(실패면 None). path = keyword | category"""
    k = _key()
    if not k:
        return None
    url = f"https://dapi.kakao.com/v2/local/search/{path}.json?" + urllib.parse.urlencode(params)
    try:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"Authorization": "KakaoAK " + k}), timeout=4) as r:
            return json.loads(r.read().decode("utf-8")).get("documents", [])
    except (OSError, ValueError) as e:
        print(f"places|kakao|FAIL|{type(e).__name__} {e}", flush=True)
        return None


def _min(hhmm):
    m = re.match(r"(\d{1,2}):(\d{2})", hhmm or "")
    return int(m[1]) * 60 + int(m[2]) if m else None


def meal_at(c, event):
    """(식사 이름, 도착 'HH:MM') — 곧 도착은 지금 + 남은 분, 도착·말은 지금. 시연 시계 ctx.clock이 있으면 그 시각"""
    now = _min(c.get("clock") or c.get("t"))
    if now is None:
        return None, ""
    at = now + (int(c.get("remainMin") or 0) if event == "near" else 0)
    at %= 24 * 60
    hhmm = f"{at // 60:02d}:{at % 60:02d}"
    for name, a, b in MEALS:
        if a <= at <= b:
            return name, hhmm
    return None, hhmm


def _bearing(la1, lo1, la2, lo2):
    y = math.sin(math.radians(lo2 - lo1)) * math.cos(math.radians(la2))
    x = math.cos(math.radians(la1)) * math.sin(math.radians(la2)) - math.sin(math.radians(la1)) * math.cos(math.radians(la2)) * math.cos(math.radians(lo2 - lo1))
    d = (math.degrees(math.atan2(y, x)) + 360) % 360
    return ["북", "북동", "동", "남동", "남", "남서", "서", "북서"][int((d + 22.5) // 45) % 8]


def _food_likes():
    from features import taste
    likes = (taste.load()["slots"].get("food") or {}).get("like") or []
    return [x["v"] for x in sorted(likes, key=taste.score, reverse=True)][:2]


def _is_food(dest, la, lo):
    """목적지가 식당·카페인가 — 목적지 이름으로 그 자리(200m)를 찾아 첫 결과의 분류"""
    docs = kakao({"query": dest, "x": lo, "y": la, "radius": 200, "size": 1})
    return bool(docs) and docs[0].get("category_group_code") in FOOD


def find_places(la, lo, likes, cafe=False, need=3):   # ⚠ search는 기능 훅 이름(검색 모드) — 다른 뜻으로 쓰지 않는다(11-08 두뇌 스레드가 죽음)
    """취향 음식 → 반경 500m → 1km → 음식점 전체 순으로 3곳 넘게 찾는다. 반환 (검색어, 이유, 장소들)"""
    cat = "CE7" if cafe else "FD6"
    tries = [(q, r) for q in ([] if cafe else likes) for r in (RADIUS, 1000)]
    for q, r in tries:
        docs = kakao({"query": q, "x": lo, "y": la, "radius": r, "category_group_code": cat, "size": 15, "sort": "distance"}) or []
        # 키워드 검색은 느슨하다(국밥 → 베트남음식·순대도, 11-08 실측) — 이름·분류에 검색어가 든 곳을 앞으로
        hit = [d for d in docs if q in (d.get("place_name", "") + d.get("category_name", ""))]
        docs = hit + [d for d in docs if d not in hit]
        if len(hit) >= need:   # 운전자가 콕 집은 음식(need=1)이면 한 곳이라도 맞으면 그걸로 — 아무 음식점으로 넘어가지 않게
            return q, (f"{q} 취향" if need >= 3 else ""), (docs if need >= 3 else hit)
    docs = kakao({"category_group_code": cat, "x": lo, "y": la, "radius": RADIUS, "size": SIZE, "sort": "distance"}, "category")
    return ("카페" if cafe else "음식점"), "", docs or []


def _items(docs, la, lo):
    out = []
    for d in docs[:SIZE]:
        try:
            dy, dx = float(d["y"]), float(d["x"])
        except (KeyError, ValueError):
            continue
        m = int(d.get("distance") or 0)
        out.append({"name": d.get("place_name", ""), "m": m, "walk": max(1, math.ceil(m / WALK)), "dir": _bearing(la, lo, dy, dx),
                    "kind": (d.get("category_name") or "").split(" > ")[-1], "lat": round(dy, 5), "lng": round(dx, 5),
                    # 폰으로 보내기(phonelink)용 — 메모리(_trips)에만 두고 카드·로그엔 쓰지 않는다(약관 — 서버 저장 금지)
                    "tel": d.get("phone") or "", "addr": d.get("road_address_name") or d.get("address_name") or "",
                    "url": d.get("place_url") or ""})
    return out


def _dest(c):
    try:
        la, lo = float(c.get("destLat") or 0), float(c.get("destLng") or 0)
    except (TypeError, ValueError):
        return None
    return (c.get("dest") or "목적지", la, lo) if la and lo else None


def on_in(msg):
    """판정만(빠르게 — inbox 잠금 안). 카카오 호출은 block/post에서(_ensure)"""
    c, kind, text = msg.get("ctx") or {}, msg.get("kind"), msg.get("text", "")
    d = _dest(c)
    if kind == "event" and text in ("near", "arrived") and d:
        meal, at = meal_at(c, text)
        if meal:
            msg["places_want"] = {"mode": text, "meal": meal, "arrive": at, "dest": d}
    elif kind == "talk":
        here = d or ((c.get("addr") or "현재 위치", float(c["lat"]), float(c["lng"])) if c.get("lat") and c.get("lng") else None)
        now = (c.get("addr") or "현재 위치", float(c["lat"]), float(c["lng"])) if c.get("lat") and c.get("lng") else here
        vq = _via_query(text)
        if now and vq:
            msg["places_want"] = {"mode": "via", "query": vq, "meal": "", "arrive": "", "dest": now}
        elif here and (TALK.search(text) or _food_query(text)):
            meal, at = meal_at(c, "talk")
            msg["places_want"] = {"mode": "ask", "meal": meal or "", "arrive": at, "dest": here, "cafe": bool(re.search(r"카페|커피", text)),
                                  "food": _food_query(text)}
        elif d:
            t = _trips.get(_tkey(d))
            if t and time.time() - t["at"] < PICK_MIN and t.get("items"):
                msg["places"] = {**t, "mode": "pick"}   # 고르기일 수 있는 말 — 이미 찾은 것을 다시 싣는다(호출 없음)


def _tkey(d):
    return f"{d[0]}|{d[1]:.4f}|{d[2]:.4f}"


def _ensure(msg):
    """places_want → 카카오 검색(필요하면) → msg['places']. 같은 목적지는 하루 한 번(곧 도착에 찾은 것을 도착에 다시 씀)"""
    want = msg.pop("places_want", None)
    if not want or msg.get("places") is not None:
        return msg.get("places")
    name, la, lo = want["dest"]
    if want["mode"] == "via":   # 경유 요청 — 그 이름으로 지금 위치 근처(5km) 가까운 순
        docs = kakao({"query": want["query"], "x": lo, "y": la, "sort": "distance", "radius": 5000, "size": SIZE}) if _key() else None
        items = _items(docs or [], la, lo)
        print(f"places|kakao|via|q={want['query']}|n={len(items)}", flush=True)
        t = {"at": time.time(), "day": time.strftime("%Y-%m-%d"), "meal": "", "arrive": "", "query": want["query"], "why": "", "items": items,
             "dest": name, "lat": la, "lng": lo, "mode": "via", "told": False, "nokey": not _key()}
        msg["places"] = t
        return t
    k = _tkey(want["dest"])
    day = time.strftime("%Y-%m-%d")
    with _lock:
        t = _trips.get(k)
    if want["mode"] in ("near", "arrived"):
        if not _key():
            msg["places"] = None   # 키가 없으면 먼저 권하지 않는다(물었을 때만 '찾지 못함')
            return None
        if t and t["day"] == day and t.get("told") and want["mode"] == "near":
            msg["places"] = None   # 오늘 이 목적지는 이미 권했다
            return None
        if t and t["day"] == day and t.get("items") is not None and want["mode"] == "arrived":
            t = {**t, "at": time.time(), "mode": "arrived"}
            with _lock:
                _trips[k] = {**t, "told": True}
            msg["places"] = t
            return t
        if _is_food(name, la, lo):
            print(f"places|skip|목적지가 식당·카페|{name}", flush=True)
            msg["places"] = None
            return None
    likes = [want["food"]] if want.get("food") else _food_likes()
    q, why, docs = find_places(la, lo, likes, cafe=want.get("cafe", False), need=1 if want.get("food") else 3)
    items = _items(docs, la, lo)
    print(f"places|kakao|{want['mode']}|q={q}|n={len(items)}", flush=True)
    t = {"at": time.time(), "day": day, "meal": want["meal"], "arrive": want["arrive"], "query": q, "why": why, "items": items,
         "dest": name, "lat": la, "lng": lo, "mode": want["mode"], "told": want["mode"] in ("near", "arrived"), "nokey": not _key()}
    with _lock:
        _trips[k] = t
        for old in [x for x, v in _trips.items() if time.time() - v["at"] > 3 * 3600]:
            del _trips[old]
    msg["places"] = t
    return t


SCHEMA = {"place_notes": {"type": "array", "description": "[주변 장소] 블록이 있을 때만 — 검색으로 찾은 가게 평판 한 줄(평점·리뷰 수·특징, 찾은 것만), 없으면 []",
                           "items": {"type": "object", "properties": {"name": {"type": "string"}, "note": {"type": "string"}},
                                     "required": ["name", "note"], "additionalProperties": False}}}

RULES = """- **주변 장소·도착 추천**([주변 장소] 블록이 있을 때만): 장소 이름·거리는 블록에 있는 것만 — 지어내지 않는다. 식사 시간·식당 목적지 판단은 서버가 이미 했다. 카드는 서버가 만든다 — card는 비운다(title="" items=[])
  · near(곧 도착): 도착하면 몇 시 무슨 식사인지 + 1번 장소 이름·목적지에서 거리를 말하고 "바로 거기로 갈까요? 다른 곳은 내리면 보여 드릴게요" — listen=true, prio 1
  · arrived(도착): "도착했어요" + 1번 이름·거리(걸어서 N분) + "화면에 N곳 띄웠어요". [취향] 음식이 없으면 "좋아하는 음식 알려 주시면 다음엔 맞춰 볼게요"
  · 고르기: 운전자가 곧 도착 제안에 "응·그래·가자"면 1번, "두 번째로"면 2번 — **주행 중이면 nav_name·nav_lat·nav_lng에 그 장소**("○○로 안내할게요"). **정차 중이면** 목적지에서 500m 안은 nav를 비우고 "걸어서 N분, ○쪽이에요", 500m 넘거나 "차로 가자"면 nav
  · via(경유 요청): 운전자가 가는 길에 들를 곳을 말했다 — 1번(가장 가까운 곳)을 via_name·via_lat·via_lng에 넣고 "○○(걸어서/차로 거리) 들렀다 가게 경유지로 넣었어요". 목적지도 말했으면 nav도 채운다([맥락] favs의 이름 — 집·회사 — 은 그 좌표)
  · 장소를 못 찾았으면(블록에 '찾지 못함') 솔직히 말한다
  · place_notes: 검색으로 가게 평판을 찾았으면 [{name: 블록의 가게 이름, note: "평점 3.4 · 리뷰 5개 · 밤 9시 반 마감 후기"}] — 찾은 것만, 못 찾은 가게는 넣지 않는다(없으면 [])"""


def block(msg, env):
    p = _ensure(msg)
    if not p:
        return None
    if p.get("nokey"):
        return "[주변 장소] 찾지 못함 — 지역 검색 키 없음"
    if not p.get("items"):
        return f"[주변 장소] {p['dest']} 근처 {p['query']} — 찾지 못함"
    mode = {"near": "곧 도착(near)", "arrived": "도착(arrived)", "ask": "운전자가 물음", "pick": "추천 뒤 운전자 말(고르기일 수 있음)", "via": "경유 요청(via)"}[p["mode"]]
    head = f"[주변 장소] {mode} · {p['dest']} 근처 · " + (f"{p['meal']}(도착 {p['arrive']}) · " if p.get("meal") else "") + \
           (f"{p['why']}" if p.get("why") else "가까운 순" + ("" if p["query"] == "카페" else " — 취향 음식 모름")) + " · 카카오 로컬 실시간"
    frm = "지금 위치에서" if p["mode"] == "via" else "목적지에서"
    rows = [f"  {i}. {x['name']} · {frm} {x['m']}m({x['dir']}쪽, 걸어서 {x['walk']}분) · {x['kind']} · 좌표 {x['lat']},{x['lng']}"
            for i, x in enumerate(p["items"], 1)]
    return "\n".join([head, *rows])


def post(rep, msg, b, dry, rec):
    p = msg.get("places")
    if p and p.get("items"):   # 두뇌가 검색으로 찾은 가게 평판 한 줄 → 항목에(메모리 — 폰 [정보] 탭 가게 줄)
        notes = {re.sub(r"\s+", "", n.get("name", "")): n.get("note", "") for n in (b.get("place_notes") or []) if n.get("note")}
        for x in p["items"]:
            k = re.sub(r"\s+", "", x.get("name", ""))
            hit = notes.get(k) or next((v for nk, v in notes.items() if nk and (nk in k or k in nk)), None)
            if hit:
                x["note"] = hit
    if p:
        rec["places"] = f"kakao|{p['mode']}|q={p.get('query', '')}|n={len(p.get('items') or [])}"   # 로그엔 검색 사실·개수만(이름 X)
    if not p or not p.get("items") or p.get("mode") == "pick":
        return
    title = f"{p['dest']} 근처 " + (p["meal"] or p["query"]) + (f" · {p['why']}" if p.get("why") else "")
    rep["card"] = {"kind": "", "title": title.strip(), "source": "카카오 로컬 · 실시간", "nostore": True,
                   "items": [f"{x['name']} — {x['m']}m · {x['kind']} · 걸어서 {x['walk']}분" for x in p["items"]],
                   # 가게마다 [전화](차 화면 목록 카드 · ACTION_DIAL) — nostore라 로그엔 안 남는다(inbox가 제목·개수만 남김)
                   "tels": [x.get("tel", "") for x in p["items"]]}
    if p["mode"] == "near":
        rep["arrive_line"] = f"도착 {p['arrive']} {p['meal']} · {p['query']} {len(p['items'])}곳 찾아 둠"
