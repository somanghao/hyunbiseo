"""현비서 정보 창고 — 인기순위·날씨를 공개 데이터에서 모은다(API 키 없음). 두뇌(Claude)가 질문·브리핑 때 읽는다.

    python -I server/hyun/features/feeds.py [movie|weather|stock|webtoon|webnovel|news|fx|all] [--top N] [--json] [--fresh]
    python -I server/hyun/features/feeds.py --channels      # 모든 통로 연결 점검(상태·걸린 시간)
    HTTP: GET /hyun/feed?cat=movie[&fresh=1][&top=5] (앱·API 두뇌용) · 서버가 켜져 있는 동안 10분마다 미리 데운다
기능 훅: RULES(값만 · 출처) · block([정보 통로] — 질문 말 + 다른 기능의 feed_cats) · GET /hyun/feed · threads(예열)

| 카테고리 | 출처 | 캐시 |
|---|---|---|
| movie    | 영화진흥위원회 통합전산망(KOBIS) 일별 박스오피스 공개 페이지 — 어제 기준 | 1시간 |
| weather  | Open-Meteo(현재·오늘 최고/최저·강수 확률) — 위치 = 인자 또는 마지막 맥락(기본 성남 수정구) | 20분 |
| stock    | 네이버 증권 — 코스피·코스닥 지수 + 코스피 시가총액 상위 | 10분 |
| webtoon  | 네이버 웹툰 — 오늘 요일 인기순 | 1시간 |
| webnovel | 네이버 시리즈 — 웹소설 일간 TOP100 | 1시간 |

규칙: 출처·기준 시각을 함께 준다(지어내지 않기 — persona.md). 실패하면 그 카테고리만 "가져오기 실패"로 두고 나머지는 계속.
실서비스는 제휴 API(KOBIS 오픈API 키·증권 시세 제휴 등)로 같은 자리를 바꾼다.
"""
import html
import http.cookiejar
import datetime as dt
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

KST = timezone(timedelta(hours=9))
DATA = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data")   # server/hyun/data — 캐시는 시험 데이터(HYUN_DATA)와 상관없이 같이 쓴다
CACHE = os.path.join(DATA, "feeds.json")
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130 Safari/537.36"
TTL = {"movie": 3600, "weather": 1200, "stock": 600, "webtoon": 3600, "webnovel": 3600, "news": 600, "fx": 3600}
NAMES = {"movie": "영화 박스오피스", "weather": "날씨", "stock": "주식", "webtoon": "웹툰 인기", "webnovel": "웹소설 인기", "news": "뉴스 헤드라인", "fx": "환율"}
DEFAULT_PLACE = (37.4127, 127.0947, "성남 수정구")
FULL = 10   # 캐시에 담는 순위 수


def _get(url, data=None, opener=None, enc="utf-8", timeout=8):
    req = urllib.request.Request(url, data=data, headers={"User-Agent": UA, "Accept-Language": "ko-KR,ko;q=0.9"})
    with (opener or urllib.request.build_opener()).open(req, timeout=timeout) as r:
        return r.read().decode(enc, "replace")


def _strip(s):
    return html.unescape(re.sub(r"<[^>]+>", "", s)).strip()


def now_kst():
    return datetime.now(KST)


# ---------- 영화: KOBIS 일별 박스오피스(공개 페이지 — 세션 쿠키 + CSRF 토큰 필요) ----------
def fetch_movie(top=5, **_):
    day = (now_kst() - timedelta(days=1)).strftime("%Y-%m-%d")
    jar = http.cookiejar.CookieJar()
    op = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    url = "https://www.kobis.or.kr/kobis/business/stat/boxs/findDailyBoxOfficeList.do"
    page = _get(url, opener=op)
    tok = re.search(r'name="CSRFToken" value="([^"]+)"', page)
    form = {"CSRFToken": tok.group(1) if tok else "", "loadEnd": "0", "searchType": "search", "sSearchFrom": day, "sSearchTo": day,
            "sMultiMovieYn": "", "sRepNationCd": "", "sWideAreaCd": ""}
    res = _get(url, data=urllib.parse.urlencode(form).encode(), opener=op)
    body = res[res.find("<tbody"):res.find("</tbody>")]
    items = []
    for tr in re.findall(r"<tr[^>]*>([\s\S]*?)</tr>", body):
        tds = [_strip(td) for td in re.findall(r"<td[^>]*>([\s\S]*?)</td>", tr)]
        if len(tds) < 8 or not tds[0].isdigit():
            continue
        # 칸: 순위 · 영화명(+순위 변동 New/1하락) · 개봉일 · 매출액 · 점유율 · 매출 증감 · 누적 매출 · 관객수 · 관객 증감 · 누적 관객 · 스크린 · 상영 횟수
        title = re.sub(r"\s+(New|\d+\s*(상승|하락)|-)$", "", re.sub(r"\s+", " ", tds[1])).strip()
        items.append({"rank": int(tds[0]), "title": title, "open": tds[2], "share": tds[4], "audience": tds[7], "total": tds[9],
                      "screens": tds[10] if len(tds) > 10 else ""})
        if len(items) >= top:
            break
    if not items:
        raise RuntimeError("표를 못 읽음(페이지 구조 변경?)")
    return {"asof": day + " 하루", "source": "영화진흥위원회 통합전산망(KOBIS)", "items": items,
            "lines": [f"{m['rank']}. {m['title']} — 하루 {m['audience']}명(점유율 {m['share']}) · 누적 {m['total']}명 · 개봉 {m['open']}" for m in items]}


# ---------- 날씨: Open-Meteo ----------
def _place_from_log():
    try:
        with open(os.path.join(DATA, "log.jsonl"), encoding="utf-8") as f:
            for line in reversed(f.readlines()[-200:]):
                c = json.loads(line).get("ctx") or {}
                if c.get("addr"):
                    return c["addr"]
    except (OSError, ValueError):
        pass
    return None


def fetch_weather(lat=None, lng=None, place=None, **_):
    la, lo, nm = (lat, lng, place or "지정 위치") if lat is not None else DEFAULT_PLACE
    if lat is None:
        nm = _place_from_log() or nm
    q = urllib.parse.urlencode({"latitude": la, "longitude": lo, "timezone": "Asia/Seoul", "forecast_days": 7,
                                "current": "temperature_2m,apparent_temperature,precipitation,weather_code,wind_speed_10m",
                                "daily": "temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code",
                                "hourly": "precipitation_probability"})
    j = json.loads(_get("https://api.open-meteo.com/v1/forecast?" + q))
    c, d = j["current"], j["daily"]
    sky = _wmo(c.get("weather_code"))
    r = lambda x: f"{round(x) + 0:d}"   # -0 → 0
    line = (f"{nm} 지금 {r(c['temperature_2m'])}도(체감 {r(c['apparent_temperature'])}도) {sky} · 오늘 {r(d['temperature_2m_min'][0])}~{r(d['temperature_2m_max'][0])}도 · "
            f"오늘 하루(지난 시간 포함) 비 확률 최고 {d['precipitation_probability_max'][0]}% · 바람 {c['wind_speed_10m']:.0f}km/h")
    # 앞으로 12시간 — "퇴근길에 비 와?"는 이 줄로(하네스: 지금 맑음 + 하루 100%를 섞어 모순, 11-06)
    hh = j.get("hourly") or {}
    ts_, pp = hh.get("time") or [], hh.get("precipitation_probability") or []
    k = next((i for i, x in enumerate(ts_) if x[:13] >= c["time"][:13]), 0)
    nxt = [(x, p or 0) for x, p in zip(ts_[k:k + 12], pp[k:k + 12])]
    if nxt:
        top_t, top_p = max(nxt, key=lambda z: z[1])
        line += (f" · 앞으로 12시간 비 확률 최고 {top_p}%({top_t[11:13]}시)" if top_p else " · 앞으로 12시간 비 소식 없음")
    # 주간(7일) — "이번 주 비 오는 날 있어?"(하네스 q005가 '못 함'이던 것)
    week = []
    for i, day in enumerate(d["time"][:7]):
        wd = "월화수목금토일"[dt.date.fromisoformat(day).weekday()]
        week.append(f"{day[5:]}({wd}) {r(d['temperature_2m_min'][i])}~{r(d['temperature_2m_max'][i])}도 · 비 {d['precipitation_probability_max'][i]}% · {_wmo(d['weather_code'][i])}")
    return {"asof": c["time"].replace("T", " "), "source": "Open-Meteo", "items": [{"place": nm, **c}],
            "lines": [line, "주간:"] + ["  " + x for x in week]}


def _wmo(code):
    c = code or 0
    return ("맑음" if c == 0 else "대체로 맑음" if c in (1, 2) else "흐림" if c == 3 else "안개" if c in (45, 48) else
            "이슬비" if 51 <= c <= 57 else "비" if 61 <= c <= 67 or 80 <= c <= 82 else "눈" if 71 <= c <= 77 or c in (85, 86) else
            "뇌우" if c >= 95 else "흐림")


# ---------- 주식: 네이버 증권 ----------
def fetch_stock(top=5, **_):
    lines, items = [], []
    for code, name in (("KOSPI", "코스피"), ("KOSDAQ", "코스닥")):
        j = json.loads(_get(f"https://m.stock.naver.com/api/index/{code}/basic"))
        sign = "+" if j.get("compareToPreviousPrice", {}).get("code") in ("1", "2") else "-" if j.get("compareToPreviousPrice", {}).get("code") in ("4", "5") else ""
        lines.append(f"{name} {j['closePrice']} ({sign}{j['fluctuationsRatio'].lstrip('-')}%)")
        items.append({"index": name, "price": j["closePrice"], "ratio": j["fluctuationsRatio"], "status": j.get("marketStatus")})
    j = json.loads(_get(f"https://m.stock.naver.com/api/stocks/marketValue/KOSPI?page=1&pageSize={top}"))
    for i, s in enumerate(j["stocks"][:top], 1):
        lines.append(f"{i}. {s['stockName']} {s['closePrice']}원 ({s['fluctuationsRatio']}%)")
        items.append({"rank": i, "name": s["stockName"], "price": s["closePrice"], "ratio": s["fluctuationsRatio"]})
    st = items[0].get("status")
    return {"asof": now_kst().strftime("%Y-%m-%d %H:%M") + (" · 장 마감" if st == "CLOSE" else " · 장중" if st == "OPEN" else ""),
            "source": "네이버 증권(지수 · 코스피 시가총액 상위)", "items": items, "lines": lines}


# ---------- 웹툰: 네이버 웹툰 오늘 요일 인기순 ----------
WEEK = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"]
WEEK_KO = ["월", "화", "수", "목", "금", "토", "일"]


def fetch_webtoon(top=5, **_):
    j = json.loads(_get("https://comic.naver.com/api/webtoon/titlelist/weekday?order=user"))
    wd = now_kst().weekday()
    lst = [t for t in j["titleListMap"][WEEK[wd]] if not t.get("adult")][:top]
    items = [{"rank": i, "title": t["titleName"], "author": t["author"], "star": round(t.get("starScore", 0), 2), "img": t.get("thumbnailUrl", "")}
             for i, t in enumerate(lst, 1)]   # img = 표지(목록 카드 그림용, 서버가 card에 붙인다)
    return {"asof": f"{now_kst():%Y-%m-%d} {WEEK_KO[wd]}요 웹툰", "source": "네이버 웹툰(인기순, 성인 제외)", "items": items,
            "lines": [f"{m['rank']}. {m['title']} — {m['author']} · 별점 {m['star']}" for m in items]}


# ---------- 웹소설: 네이버 시리즈 일간 TOP100 ----------
def fetch_webnovel(top=5, **_):
    p = _get("https://series.naver.com/novel/top100List.series?rankingTypeCode=DAILY&categoryCode=ALL")
    p = p[p.find("comic_top_lst"):]
    items = []
    for li in re.findall(r"<li>([\s\S]*?)</li>", p):
        t = re.search(r"<h3>[\s\S]*?<a\s[^>]*>([\s\S]*?)</a>", li)   # <a 뒤가 공백이 아니라 줄바꿈인 마크업(실측)
        if not t:
            continue
        au = re.search(r'class="author">([^<]+)<', li)
        sc = re.search(r'score_num">([\d.]+)<', li)
        ep = re.search(r"총\d+화/[^<\s]+", li)
        items.append({"rank": len(items) + 1, "title": _strip(t.group(1)), "author": au.group(1).strip() if au else "",
                      "score": sc.group(1) if sc else "", "episodes": ep.group(0) if ep else "",
                      "info": " · ".join(x for x in (au.group(1).strip() if au else "", f"평점 {sc.group(1)}" if sc else "", ep.group(0) if ep else "") if x)})
        if len(items) >= top:
            break
    if not items:
        raise RuntimeError("목록을 못 읽음(페이지 구조 변경?)")
    return {"asof": f"{now_kst():%Y-%m-%d} 일간", "source": "네이버 시리즈 웹소설 TOP100", "items": items,
            "lines": [f"{m['rank']}. {m['title']}" + (f" — {m['info']}" if m["info"] else "") for m in items]}


# ---------- 뉴스: 연합뉴스 최신 기사 RSS ----------
def fetch_news(top=5, **_):
    x = _get("https://www.yna.co.kr/rss/news.xml")
    items = []
    for it in re.findall(r"<item>([\s\S]*?)</item>", x):
        t = re.search(r"<title>(?:<!\[CDATA\[)?([\s\S]*?)(?:\]\]>)?</title>", it)
        d = re.search(r"<pubDate>([^<]+)</pubDate>", it)
        if t:
            when = ""
            if d:
                try:
                    when = dt.datetime.strptime(d.group(1).strip()[:25], "%a, %d %b %Y %H:%M:%S").strftime("%H:%M")
                except ValueError:
                    when = ""
            items.append({"rank": len(items) + 1, "title": _strip(t.group(1)), "time": when})
        if len(items) >= top:
            break
    if not items:
        raise RuntimeError("RSS 비어 있음")
    return {"asof": now_kst().strftime("%Y-%m-%d %H:%M"), "source": "연합뉴스 최신 기사 RSS", "items": items,
            "lines": [f"{m['rank']}. {m['title']}" + (f" ({m['time']})" if m["time"] else "") for m in items]}


# ---------- 환율: ExchangeRate-API 공개(키 없음, 하루 1번 갱신, 출처 표기 조건) ----------
def fetch_fx(**_):
    j = json.loads(_get("https://open.er-api.com/v6/latest/USD"))
    k = j["rates"]["KRW"]
    rows = [("미국 달러", "1달러", k), ("일본 엔", "100엔", k / j["rates"]["JPY"] * 100), ("유로", "1유로", k / j["rates"]["EUR"]),
            ("중국 위안", "1위안", k / j["rates"]["CNY"])]
    items = [{"name": n, "unit": u, "krw": round(v, 2)} for n, u, v in rows]
    return {"asof": j.get("time_last_update_utc", "")[:16], "source": "ExchangeRate-API(공개, 하루 1번 갱신 — 실시간 아님)", "items": items,
            "lines": [f"{m['unit']} = {m['krw']:,.2f}원" for m in items]}


# 통로 — 어디서 무엇을, 키가 필요한지. 실서비스로 바꿀 때는 여기 한 줄씩(제휴 API·키) 갈아 끼운다
CHANNELS = {
    "movie": {"url": "https://www.kobis.or.kr/kobis/business/stat/boxs/findDailyBoxOfficeList.do", "key": "없음(공개 페이지 · 쿠키+CSRF)",
              "upgrade": "KOBIS 오픈API searchDailyBoxOfficeList(키 무료 발급) · CGV/롯데 예매율은 공개 API 없음"},
    "weather": {"url": "https://api.open-meteo.com/v1/forecast", "key": "없음", "upgrade": "기상청 단기예보(공공데이터포털 키)"},
    "stock": {"url": "https://m.stock.naver.com/api/index/{KOSPI|KOSDAQ}/basic · /api/stocks/marketValue/KOSPI", "key": "없음(공개 웹 API)",
              "upgrade": "증권사·시세 제휴 API(관심 종목 실시간)"},
    "webtoon": {"url": "https://comic.naver.com/api/webtoon/titlelist/weekday?order=user", "key": "없음(공개 웹 API)", "upgrade": "카카오웹툰·레진 추가"},
    "webnovel": {"url": "https://series.naver.com/novel/top100List.series?rankingTypeCode=DAILY", "key": "없음(공개 페이지)",
                 "upgrade": "카카오페이지·문피아·노벨피아 추가"},
    "news": {"url": "https://www.yna.co.kr/rss/news.xml", "key": "없음(RSS)", "upgrade": "뉴스 제휴(전문·분야별) · 방송사 RSS 추가"},
    "fx": {"url": "https://open.er-api.com/v6/latest/USD", "key": "없음(출처 표기)", "upgrade": "한국수출입은행 현재환율 API(키) — 영업일 고시"},
}


def channels():
    """모든 통로를 새로 열어 보고 상태·걸린 시간을 표로"""
    out = []
    for c in CHANNELS:
        t0 = time.time()
        r = get(c, fresh=True, top=3)
        ms = int((time.time() - t0) * 1000)
        ok = "error" not in r
        out.append(f"{'OK ' if ok else 'FAIL'} {c:8} {ms:5}ms · {NAMES[c]} · 키 {CHANNELS[c]['key']}" + ("" if ok else f" · {r['error']}"))
    return "\n".join(out)


FETCH = {"movie": fetch_movie, "weather": fetch_weather, "stock": fetch_stock, "webtoon": fetch_webtoon, "webnovel": fetch_webnovel,
         "news": fetch_news, "fx": fetch_fx}


def _load():
    try:
        with open(CACHE, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def get(cat, fresh=False, **kw):
    """캐시가 살아 있으면 캐시, 아니면 새로 — 실패하면 {"error": ...}"""
    cache = _load()
    hit = cache.get(cat)
    if hit and not fresh and not kw.get("lat") and time.time() - hit.get("_at", 0) < TTL[cat]:
        return hit
    try:
        # 캐시엔 늘 상위 FULL개 — 요청한 top으로 줄여 저장하면 다음 요청이 그만큼만 받는다(11-06: 점검을 top=3으로 돌린 뒤 "10편 중 3편만" 답함)
        r = FETCH[cat](**{**kw, "top": max(int(kw.get("top") or 0), FULL)})
        r["_at"] = time.time()
        r["name"] = NAMES[cat]
        cache[cat] = r
        os.makedirs(DATA, exist_ok=True)
        with open(CACHE + ".tmp", "w", encoding="utf-8") as f:
            json.dump(cache, f, ensure_ascii=False)
        os.replace(CACHE + ".tmp", CACHE)
        return r
    except Exception as e:  # noqa: BLE001 — 카테고리 하나가 실패해도 나머지는 계속
        if hit:
            hit = dict(hit, stale=f"새로 가져오기 실패({type(e).__name__}) — 이전 값")
            return hit
        return {"name": NAMES[cat], "error": f"{type(e).__name__}: {e}"}


def text(cat, top=5, fresh=False, **kw):
    r = get(cat, fresh=fresh, top=top, **kw)
    if "error" in r:
        return f"[{r['name']}] 가져오기 실패 — {r['error']}"
    head = f"[{r['name']}] {r['asof']} · 출처 {r['source']}" + (f" · {r['stale']}" if r.get("stale") else "")
    return head + "\n" + "\n".join("  " + l for l in r["lines"][:top + 2])


# ---------- 기능 훅 ----------
# 질문에 이 말이 있으면 그 통로를 미리 열어 블록으로 넣는다(도구 호출 없이 한 번에 — 지연 줄이기)
FEED_WORDS = {
    "movie": r"영화|극장|박스|개봉|관객|CGV|메가박스|롯데시네마",
    "weather": r"날씨|기온|비\s*와|비\s*올|눈\s*와|우산|추워|더워|미세",
    "stock": r"주식|코스피|코스닥|주가|종목|증시|삼성전자|하이닉스|시세",
    "webtoon": r"웹툰|만화",
    "webnovel": r"웹소설|소설|네이버\s*시리즈|노벨",   # 맨 "시리즈"는 한국시리즈·월드시리즈와 겹친다
    "news": r"뉴스|헤드라인|속보|무슨\s*일",
    "fx": r"환율|달러|엔화|유로|위안",
}

RULES = """- 영화·날씨·주식·웹툰·웹소설 순위나 시세는 [정보 통로] 블록(또는 get_feed 도구)의 값만 말하고, 출처와 기준(예: "영화진흥위원회 어제 기준")을 짧게 붙인다. 블록에 없는 사실을 물으면 모른다고 솔직히 말한다 — 추천은 있는 것 중에서 고르고, 없는 건 말하지 않는다("자료에 없어서 확인 못 했어요" 같은 말 금지)
- 날씨는 "지금"과 "앞으로 12시간" 줄이 기준 — "오늘 하루 최고 확률"은 이미 지난 시간도 포함이라 지금 날씨와 섞어 말하지 않는다
- 주간 날씨("이번 주")는 정보 통로 주간 줄로 card에 요일별 한 줄(예: "11-07(토) 3~11도 · 비 20% · 맑음"), say는 비 오는 날 위주 두 문장
- 뉴스는 헤드라인 card(최대 10) + say는 세 개만, 출처 "연합뉴스" · 환율은 "하루 1번 갱신 기준"이라고 붙인다"""


def wanted_feeds(text):
    t = text or ""
    cats = [c for c, pat in FEED_WORDS.items() if re.search(pat, t, re.I)]
    if not cats and re.search(r"순위|인기|요즘 뭐|뭐가 핫|트렌드", t):
        cats = list(FEED_WORDS)
    return cats


def block(msg, env):
    if not env.get("feeds", True):
        return None
    import features
    cats = list(dict.fromkeys((wanted_feeds(msg["text"]) if msg["kind"] == "talk" else []) + features.collect("feed_cats", msg)))
    if not cats:
        return None
    top = 3 if msg.get("brief") and not wanted_feeds(msg.get("text", "")) else 10   # 목록 카드에 10위까지 · 브리핑만이면 3개(지연 줄이기)
    return "[정보 통로]\n" + "\n\n".join(text(cat, top=top) for cat in cats)


def _feed(h, q):
    """cat=movie|weather|stock|webtoon|webnovel|news|fx|channels"""
    cat = q.get("cat", "")
    if cat == "channels":
        return h.send_json({"channels": CHANNELS, "check": channels().splitlines() if q.get("check") else None})
    if cat not in FETCH:
        return h.send_json({"error": f"cat은 {list(FETCH)} 중 하나"}, 400)
    top = int(q.get("top", 5))
    r = get(cat, fresh=bool(q.get("fresh")), top=top)
    h.send_json({"text": text(cat, top=top), **{k: v for k, v in r.items() if k != "_at"}})


GET = {"/hyun/feed": _feed}


def _warmer():
    """정보 통로를 미리 데워 둔다 — 두뇌가 물을 때 캐시에서 즉시(통로별 TTL이 지난 것만 새로)"""
    while True:
        for c in FETCH:
            get(c)
        time.sleep(600)


def threads():
    return [_warmer]


def main(argv):
    if "--channels" in argv:
        print(channels())
        return
    cats = [a for a in argv if a in FETCH or a == "all"] or ["all"]
    if "all" in cats:
        cats = list(FETCH)
    top = int(argv[argv.index("--top") + 1]) if "--top" in argv else 5
    fresh = "--fresh" in argv
    if "--json" in argv:
        print(json.dumps({c: get(c, fresh=fresh, top=top) for c in cats}, ensure_ascii=False, indent=1))
    else:
        print("\n\n".join(text(c, top=top, fresh=fresh) for c in cats))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main(sys.argv[1:])
