"""화면 카드 — 목록(순위·여러 개 추천) · 표지 격자(웹툰) · 소개(kind=info — 왼쪽 사진 · 오른쪽 "라벨 — 값" 표: 휴게소 · 인물).
서버가 손질한다: 운전자가 말한 개수만큼 자르기 · 웹툰이면 정보 통로 표지 URL · 소개 카드면 위키미디어 공용 사진(/hyun/img로 중계·캐시).
앱 짝 = ui/HyunView(listCard · imageGrid · infoCard) · core/Hyun.showCard
"""
import json
import os
import re
import urllib.request
from urllib.parse import quote, urlencode, urlparse

from core import config

SCHEMA = {
    "card": {"type": "object", "description": "화면 카드 — 목록(kind \"\": 순위·여러 개 추천)이나 소개(kind \"info\": 왼쪽 사진·오른쪽 표 — 휴게소·인물 하나), 아니면 title=\"\" items=[]",
             "properties": {
                 "kind": {"type": "string", "enum": ["", "info"], "description": "\"\" 목록 · info 소개(사진 + 라벨 — 값 표)"},
                 "title": {"type": "string", "description": "짧은 제목(예: 웹소설 인기 TOP 10 · 안성휴게소 부산방향)"},
                 "source": {"type": "string", "description": "출처·기준(예: 네이버 시리즈 · 11-06 일간)"},
                 "items": {"type": "array", "items": {"type": "string"}, "description": "최대 10개, 한 줄씩 짧게(목록 예: 화산귀환 — 비가 · 소개 예: 대표 메뉴 — 안성국밥 9,000원)"},
                 "image_query": {"type": "string", "description": "kind=info일 때 사진을 찾을 영어 검색어(예: Anseong rest area Korea · Son Heung-min) — 아니면 빈 문자열"}},
             "required": ["kind", "title", "source", "items", "image_query"], "additionalProperties": False},
}

RULES = """- 운전자가 개수를 말하면("세 개만") card도 그 개수만, say도 그 개수만
- **목록 카드**: 순위·시세·여러 개 추천처럼 목록인 답이면 card에 전체(최대 10, 운전자가 말한 개수만큼)를 한 줄씩 짧게(제목 — 작가/값) 넣고 title·source(출처·기준)를 채운다. say는 앞 3개만 읽고, 정차면 "나머지는 화면에 띄웠어요", 주행 중이면 "정차하면 화면에서 보세요"로 끝낸다. 목록이 아닌 답은 card.title="" · items=[]
- **소개 카드(card.kind=info)** — 휴게소·인물처럼 '하나'를 소개할 때: title = 이름, source = 출처·기준, items = "라벨 — 값" 4~6줄(예: "위치 — 경부고속도로 부산방향 · 약 12km 앞", "대표 메뉴 — 안성국밥 9,000원", "취향 추천 — 국밥 좋아하시니 안성국밥", "편의 — 주유 · 전기차 충전 · 수유실"), 휴게소면 위치·대표 메뉴·편의·취향 추천 네 줄은 꼭(검색으로 못 찾은 칸은 "확인 못 함") — "확실도" 같은 메타 줄은 쓰지 않고, 추정이면 제목에 "(추정)", image_query = 사진 검색용 영어(휴게소 "Anseong rest area Korea", 인물은 영문 이름). 사진 URL은 쓰지 않는다(서버가 붙인다). say는 핵심 두 문장. 목록 카드는 kind="" image_query="\""""

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
        from features import feeds
        pool = (feeds.get("webtoon").get("items") or [])
        norm = lambda x: re.sub(r"\s+", "", x or "")
        imgs = []
        for it in card["items"]:
            hit = next((w for w in pool if norm(w["title"]) and norm(w["title"]) in norm(it)), None)
            imgs.append((hit or {}).get("img", ""))
        if any(imgs):
            card = dict(card, images=imgs, kind="image_list")
    return card


def post(rep, msg, b, dry, rec):
    card = rep.get("card") or b.get("card")   # 교통 표(traffic 기능)가 먼저 채웠으면 그것
    rep["card"] = enrich_card(trim_card(card, msg.get("text", "")))   # 말한 개수만큼 · 웹툰 표지 · 소개 사진


def _img(h, q):
    """카드 사진 중계(위키미디어 공용만) — 앱은 UA·캐시 걱정 없이 여기서 받는다"""
    src = q.get("u", "")
    if not src.startswith(("https://upload.wikimedia.org/", "https://thumb.wikimedia.org/")):
        return h.send_json({"error": "commons only"}, 400)
    path = config.data("img", re.sub(r"[^A-Za-z0-9._-]", "_", urlparse(src).path[-90:]))
    if not os.path.exists(path):
        try:
            with urllib.request.urlopen(urllib.request.Request(src, headers={"User-Agent": IMG_UA}), timeout=10) as r:
                body = r.read()
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "wb") as f:
                f.write(body)
        except OSError as e:
            return h.send_json({"error": type(e).__name__}, 502)
    with open(path, "rb") as f:
        h.send_bytes(f.read(), "image/png" if path.lower().endswith(".png") else "image/jpeg")


GET = {"/hyun/img": _img}
