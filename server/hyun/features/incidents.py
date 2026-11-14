"""앞길 돌발 — 내비 현재 위치 + 목적지로 경로 회랑 안의 실제 사고·고장·공사·통제를 외부 공공 데이터에서 가져와 가까운 순으로.

    python -I -X utf8 server/hyun/features/incidents.py <lat> <lng> <dlat> <dlng> [--w 3] [--jam] [--json]
    HTTP: GET /hyun/incidents?lat=&lng=&dlat=&dlng=[&w=3][&jam=1][&roads=경부,영동]

출처(2026-11-14 실측)
| 출처 | 범위 | 키 | 실측 |
|---|---|---|---|
| 한국도로공사 data.ex.co.kr burstInfo/realTimeSms | 전국 고속도로 돌발(사고·고장·작업·장애물·기상·정체) · 막힌 차로 번호(laneYn1~6) · 좌표 · 처리 상태 | `EX_KEY`(없으면 공개 시험 키 test — 실시간 값이 온다) | 오늘 108건, 마지막 13:46(호출 13:47) |
| 서울 TOPIS openapi.seoul.go.kr AccInfo | 서울 도시 도로 돌발(공사·사고·행사 등) · GRS80 TM 좌표 | `TOPIS_KEY`(없으면 sample — 한 번에 5건까지만) | 실제 값(공사 2027-03까지 등) |
| 국가교통정보센터 ITS openapi.its.go.kr eventInfo | 전국 국도·고속도로 돌발 · 막힌 차로 | `ITS_KEY` 필요 — test 키는 2020년 고정 샘플이고 영역도 무시 | 키 없으면 건너뜀 |
| 경찰청 UTIC | 전국 돌발 | 키 필요(test 거부) | 미사용 |

회랑 = 현재 위치 → 목적지 직선을 좌우 w km(기본 3) 넓힌 띠. 내비 경로 모양이 아니라 직선이라 굽은 길은 빠질 수 있다 —
앱이 경로 도로(Scout.roads)를 넘기면 `roads=`로 고속도로 이름을 맞춰 더 걸러 낸다. 가짜 금지: 값은 출처 응답 그대로, 못 받으면 빈 목록.
"""
import json
import math
import os
import re
import sys
import threading
import time
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone

KST = timezone(timedelta(hours=9))
UA = {"User-Agent": "Mozilla/5.0 (compatible; hyun-incidents/0.1)"}
TTL = 60          # 출처별 캐시(초) — 앱 정찰 주기(25~120초)보다 짧게
_cache = {}
_lock = threading.Lock()

SEV = {"사고": 3, "화재": 3, "장애물": 3, "낙하물": 3, "역주행": 3, "고장": 2, "작업": 2, "공사": 2, "통제": 2, "행사": 1,
       "강우": 1, "강설": 1, "안개": 1, "결빙": 2, "차량증가/정체": 1, "정체": 1}


def _get(url, timeout=12):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=timeout) as r:
        return r.read().decode("utf-8", "replace")


def _cached(name, fn):
    with _lock:
        hit = _cache.get(name)
        if hit and time.time() - hit[0] < TTL:
            return hit[1]
    try:
        val = fn()
        status = {"ok": True, "n": len(val)}
    except Exception as e:   # 출처 하나가 죽어도 나머지는 낸다
        val, status = [], {"ok": False, "error": f"{type(e).__name__}: {e}"[:160]}
    with _lock:
        _cache[name] = (time.time(), (val, status))
    return val, status


# ---------- 한국도로공사 — 고속도로 실시간 돌발 ----------
def fetch_ex():
    key = os.environ.get("EX_KEY") or "test"
    out, now = [], datetime.now(KST)
    pages = 1
    pg = 0
    while pg < pages and pg < 20:   # 쪽마다 30건이 다 안 올 때가 있다 — 전체 건수(count)로 쪽 수를 정한다(11-14: 30건 미만에서 멈춰 125/216만 받음)
        pg += 1
        j = json.loads(_get(f"https://data.ex.co.kr/openapi/burstInfo/realTimeSms?key={key}&type=json&numOfRows=30&pageNo={pg}"))
        pages = max(pages, math.ceil(int(j.get("count") or 0) / 30))
        rows = j.get("realTimeSMSList") or []
        for x in rows:
            try:
                lat, lng = float(x.get("latitude")), float(x.get("altitude"))   # 응답 칸 이름이 altitude지만 값은 경도
            except (TypeError, ValueError):
                continue
            try:
                at = datetime.strptime(f"{x['accDate']} {x['accHour']}", "%Y.%m.%d %H:%M:%S").replace(tzinfo=KST)
            except (KeyError, ValueError):
                continue
            kind = x.get("accType") or "돌발"
            if now - at > timedelta(hours=36) and kind not in ("작업", "공사"):   # 오래된 '진행' 표시(홍보 등)는 버린다
                continue
            lanes = [i for i in range(1, 7) if x.get(f"laneYn{i}") == "Y"]
            road = (x.get("roadNM") or "").strip()
            out.append({"src": "도로공사", "kind": kind, "road": road, "dir": (x.get("startEndTypeCode") or "").strip(),
                        "place": (x.get("accPointNM") or "").strip(), "lanes": lanes, "shoulder": x.get("shldroadYn") == "Y",
                        "status": x.get("accProcessNM") or "", "at": at.strftime("%m-%d %H:%M"), "lat": lat, "lng": lng,
                        "text": re.sub(r"\s+", " ", (x.get("smsText") or "")).strip()})
        if not rows:
            break
    return out


# ---------- 서울 TOPIS — 도시 도로 돌발 (GRS80 TM 중부원점 EPSG:5181) ----------
def _tm5181_to_wgs84(x, y):
    a, f = 6378137.0, 1 / 298.257222101
    e2 = f * (2 - f); ep2 = e2 / (1 - e2)
    k0, lat0, lon0, fe, fn = 1.0, math.radians(38), math.radians(127), 200000.0, 500000.0
    def mer(phi):
        return a * ((1 - e2 / 4 - 3 * e2 ** 2 / 64 - 5 * e2 ** 3 / 256) * phi - (3 * e2 / 8 + 3 * e2 ** 2 / 32 + 45 * e2 ** 3 / 1024) * math.sin(2 * phi)
                    + (15 * e2 ** 2 / 256 + 45 * e2 ** 3 / 1024) * math.sin(4 * phi) - (35 * e2 ** 3 / 3072) * math.sin(6 * phi))
    m = mer(lat0) + (y - fn) / k0
    mu = m / (a * (1 - e2 / 4 - 3 * e2 ** 2 / 64 - 5 * e2 ** 3 / 256))
    e1 = (1 - math.sqrt(1 - e2)) / (1 + math.sqrt(1 - e2))
    phi1 = mu + (3 * e1 / 2 - 27 * e1 ** 3 / 32) * math.sin(2 * mu) + (21 * e1 ** 2 / 16 - 55 * e1 ** 4 / 32) * math.sin(4 * mu) \
        + (151 * e1 ** 3 / 96) * math.sin(6 * mu) + (1097 * e1 ** 4 / 512) * math.sin(8 * mu)
    c1 = ep2 * math.cos(phi1) ** 2; t1 = math.tan(phi1) ** 2
    n1 = a / math.sqrt(1 - e2 * math.sin(phi1) ** 2); r1 = a * (1 - e2) / (1 - e2 * math.sin(phi1) ** 2) ** 1.5
    d = (x - fe) / (n1 * k0)
    lat = phi1 - (n1 * math.tan(phi1) / r1) * (d ** 2 / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 ** 2 - 9 * ep2) * d ** 4 / 24
                                               + (61 + 90 * t1 + 298 * c1 + 45 * t1 ** 2 - 252 * ep2 - 3 * c1 ** 2) * d ** 6 / 720)
    lon = lon0 + (d - (1 + 2 * t1 + c1) * d ** 3 / 6 + (5 - 2 * c1 + 28 * t1 - 3 * c1 ** 2 + 8 * ep2 + 24 * t1 ** 2) * d ** 5 / 120) / math.cos(phi1)
    return math.degrees(lat), math.degrees(lon)


def _xml_rows(xml, tag="row"):
    from xml.etree import ElementTree as ET
    root = ET.fromstring(xml)
    return [{c.tag: (c.text or "").strip() for c in r} for r in root.iter(tag)]


def fetch_topis():
    key = os.environ.get("TOPIS_KEY") or "sample"
    n = 5 if key == "sample" else 300            # 샘플 키는 한 번에 5건까지(ERROR-335)
    codes = {}
    try:
        codes = {r.get("acc_type"): r.get("acc_type_nm") for r in _xml_rows(_get(f"http://openapi.seoul.go.kr:8088/{key}/xml/AccMainCode/1/{min(n, 20)}/"))}
    except Exception:
        pass
    out = []
    for r in _xml_rows(_get(f"http://openapi.seoul.go.kr:8088/{key}/xml/AccInfo/1/{n}/")):
        try:
            lat, lng = _tm5181_to_wgs84(float(r["grs80tm_x"]), float(r["grs80tm_y"]))
        except (KeyError, ValueError):
            continue
        kind = codes.get(r.get("acc_type")) or {"A01": "사고", "A04": "공사"}.get(r.get("acc_type"), "돌발")
        d, t = r.get("occr_date", ""), r.get("occr_time", "")
        info = re.sub(r"\s+", " ", r.get("acc_info", "")).strip()
        place = re.split(r"\s*-\s*일시|\s*\(|,", re.sub(r"^\[[^\]]*\]\s*", "", info))[0][:30]
        out.append({"src": "서울 TOPIS", "kind": kind, "road": "", "dir": "", "place": place, "lanes": [], "shoulder": False,
                    "status": f"해제 예정 {r.get('exp_clr_date', '')[:4]}-{r.get('exp_clr_date', '')[4:6]}-{r.get('exp_clr_date', '')[6:8]}".rstrip("-"),
                    "at": f"{d[4:6]}-{d[6:8]} {t[:2]}:{t[2:4]}" if len(d) == 8 else d, "lat": lat, "lng": lng,
                    "text": re.sub(r"\s+", " ", r.get("acc_info", "")).strip(), "limited": key == "sample"})
    return out


# ---------- 국가교통정보센터 ITS — 키가 있을 때만 ----------
def fetch_its(box):
    key = os.environ.get("ITS_KEY")
    if not key:
        raise RuntimeError("ITS_KEY 없음 — test 키는 2020년 고정 샘플이라 쓰지 않는다")
    minx, miny, maxx, maxy = box
    u = (f"https://openapi.its.go.kr:9443/eventInfo?apiKey={urllib.parse.quote(key)}&type=all&eventType=all"
         f"&minX={minx:.4f}&maxX={maxx:.4f}&minY={miny:.4f}&maxY={maxy:.4f}&getType=json")
    j = json.loads(_get(u))
    out = []
    for x in (j.get("body") or {}).get("items") or []:
        try:
            lat, lng = float(x["coordY"]), float(x["coordX"])
        except (KeyError, ValueError):
            continue
        s = x.get("startDate", "")
        out.append({"src": "ITS", "kind": x.get("eventType") or "돌발", "road": x.get("roadName", ""), "dir": x.get("roadDrcType", ""),
                    "place": "", "lanes": [int(n) for n in re.findall(r"(\d)\s*차로", x.get("lanesBlocked") or "")], "shoulder": False,
                    "status": x.get("lanesBlockType", ""), "at": f"{s[4:6]}-{s[6:8]} {s[8:10]}:{s[10:12]}" if len(s) >= 12 else s,
                    "lat": lat, "lng": lng, "text": x.get("message", "")})
    return out


# ---------- 회랑 걸러 내기 ----------
def _km(lat0):
    return 111.32 * math.cos(math.radians(lat0)), 110.57


# 고속도로 '○○방향'의 ○○ → 대략 좌표(반대편 걸러 내기용). 표에 없으면 방향을 모르는 것으로 두고 남긴다
DIR_CITY = {"서울": (37.56, 126.98), "부산": (35.18, 129.08), "인천": (37.46, 126.70), "강릉": (37.75, 128.88), "대전": (36.35, 127.38),
            "대구": (35.87, 128.60), "광주": (35.16, 126.85), "목포": (34.81, 126.39), "순천": (34.95, 127.49), "춘천": (37.88, 127.73),
            "일산": (37.66, 126.77), "구리": (37.59, 127.13), "판교": (37.39, 127.11), "퇴계원": (37.65, 127.14), "평촌": (37.39, 126.96),
            "성남": (37.42, 127.13), "통영": (34.85, 128.43), "양양": (38.08, 128.62), "원주": (37.34, 127.92), "천안": (36.81, 127.15),
            "당진": (36.89, 126.63), "평택": (36.99, 127.11), "서산": (36.78, 126.45), "남해": (34.84, 127.89), "마산": (35.21, 128.58),
            "창원": (35.23, 128.68), "울산": (35.54, 129.31), "포항": (36.02, 129.34), "청주": (36.64, 127.49), "세종": (36.48, 127.29),
            "안산": (37.32, 126.83), "시흥": (37.38, 126.80), "수원": (37.26, 127.03), "용인": (37.24, 127.18), "하남": (37.54, 127.21),
            "김포": (37.62, 126.72), "양평": (37.49, 127.49), "광명": (37.48, 126.86), "의정부": (37.74, 127.03), "여주": (37.30, 127.64)}


def _bearing(lat1, lng1, lat2, lng2):
    kx, ky = _km((lat1 + lat2) / 2)
    return math.degrees(math.atan2((lng2 - lng1) * kx, (lat2 - lat1) * ky)) % 360


def _opposite(it, route_brg):
    """'○○방향'이 우리 진행 방향과 반대면 True(모르면 False — 남긴다)"""
    m = re.search(r"([가-힣]+)방향", it.get("dir") or "")
    if not m or "양방향" in (it.get("dir") or ""):
        return False
    city = next((c for c in DIR_CITY if m.group(1).endswith(c) or m.group(1).startswith(c)), None)
    if not city:
        return False
    b = _bearing(it["lat"], it["lng"], *DIR_CITY[city])
    diff = abs((b - route_brg + 180) % 360 - 180)
    return diff > 110


def corridor(items, lat, lng, dlat, dlng, w=3.0, roads=None, jam=False):
    kx, ky = _km((lat + dlat) / 2)
    bx, by = (dlng - lng) * kx, (dlat - lat) * ky
    L = math.hypot(bx, by) or 1e-9
    road_keys = [re.sub(r"(고속도로|고속국도|선)$", "", r.strip()) for r in (roads or []) if r.strip()]
    brg = _bearing(lat, lng, dlat, dlng)
    out = []
    for it in items:
        if not jam and SEV.get(it["kind"], 2) <= 1 and it["kind"] not in ("강설", "결빙"):
            continue
        if _opposite(it, brg):
            continue
        px, py = (it["lng"] - lng) * kx, (it["lat"] - lat) * ky
        along = (px * bx + py * by) / L
        side = abs(px * by - py * bx) / L
        if along < -0.5 or along > L + 0.5 or side > w:
            continue
        if road_keys and it["road"] and not any(k and k in it["road"] for k in road_keys):
            continue
        out.append({**it, "ahead_km": round(along, 1), "side_km": round(side, 1), "sev": SEV.get(it["kind"], 2)})
    out.sort(key=lambda r: (r["ahead_km"], -r["sev"]))
    return out, round(L, 1)


def say_line(r):
    """앱·두뇌가 쓸 한 줄 — 출처 값만(지어내지 않는다)"""
    where = " ".join(x for x in (r["road"], r["dir"], r["place"]) if x) or (r["text"][:24] if r["text"] else "앞길")
    lane = f"{','.join(map(str, r['lanes']))}차로 " if r["lanes"] else ("갓길 " if r.get("shoulder") else "")
    tail = ""
    if r["lanes"] and r["kind"] in ("사고", "고장", "장애물", "낙하물", "작업", "공사"):
        tail = " — 미리 다른 차로로"
    return f"약 {r['ahead_km']:g}km 앞 {where} {lane}{r['kind']}{tail}".replace("  ", " ")


def ahead(lat, lng, dlat, dlng, w=3.0, roads=None, jam=False):
    pad = w / 111.0 + 0.02
    box = (min(lng, dlng) - pad, min(lat, dlat) - pad, max(lng, dlng) + pad, max(lat, dlat) + pad)
    srcs = {}
    allv = []
    for name, fn in (("도로공사", fetch_ex), ("서울 TOPIS", fetch_topis), ("ITS", lambda: fetch_its(box))):
        v, st = _cached(name if name != "ITS" else f"ITS{box}", fn)
        srcs[name] = st
        allv += v
    items, L = corridor(allv, lat, lng, dlat, dlng, w=w, roads=roads, jam=jam)
    for r in items:
        r["say"] = say_line(r)
    return {"asof": datetime.now(KST).strftime("%Y-%m-%d %H:%M:%S"), "route_km": L, "width_km": w, "sources": srcs, "items": items}


# ---------- HTTP ----------
def _incidents(h, q):
    try:
        lat, lng, dlat, dlng = (float(q[k]) for k in ("lat", "lng", "dlat", "dlng"))
    except (KeyError, ValueError):
        return h.send_json({"error": "lat · lng · dlat · dlng 가 필요하다(내비 현재 위치 · 목적지)"}, 400)
    roads = [r for r in (q.get("roads") or "").split(",") if r]
    h.send_json(ahead(lat, lng, dlat, dlng, w=float(q.get("w", 3)), roads=roads, jam=q.get("jam") in ("1", "true")))


GET = {"/hyun/incidents": _incidents}


if __name__ == "__main__":
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    if len(args) < 4:
        sys.exit(__doc__)
    w = float(sys.argv[sys.argv.index("--w") + 1]) if "--w" in sys.argv else 3.0
    r = ahead(*map(float, args[:4]), w=w, jam="--jam" in sys.argv)
    if "--json" in sys.argv:
        print(json.dumps(r, ensure_ascii=False, indent=1))
    else:
        print(f"{r['asof']} · 직선 {r['route_km']}km · 폭 {r['width_km']}km · 출처 {r['sources']}")
        for it in r["items"]:
            print(f"  [{it['src']} {it['at']}] {it['say']} · {it['status']} · {it['text'][:50]}")
