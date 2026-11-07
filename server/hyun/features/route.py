"""길 — 목적지(nav) · 경유지(via) · 우회·안내 끝(route) 필드 + [길·교통] 블록 + 주행 이벤트(출발 · 교통 · 우회 결과 · 목적지 없이 출발 · 경유지 도착) 규칙.
앱 짝 = feature/RouteFeature.kt · ScoutFeature.kt
"""

SCHEMA = {
    "nav_name": {"type": "string", "description": "운전자가 가자고 한 목적지 이름 — 없으면 빈 문자열"},
    "nav_lat": {"type": "number", "description": "목적지 위도(소수 4자리) — 없으면 0"},
    "nav_lng": {"type": "number", "description": "목적지 경도(소수 4자리) — 없으면 0"},
    "via_name": {"type": "string", "description": "가는 길에 들를 곳(경유지) 이름 — 없으면 빈 문자열"},
    "via_lat": {"type": "number", "description": "경유지 위도(소수 4자리) — 없으면 0"},
    "via_lng": {"type": "number", "description": "경유지 경도(소수 4자리) — 없으면 0"},
    "route": {"type": "string", "enum": ["", "fast", "reroute", "highway", "free", "comfort", "clear_via", "cancel"],
              "description": "우회·경로 옵션 변경 · clear_via 경유지 빼기 · cancel 길 안내 끝 — 없으면 빈 문자열"},
}

RULES = """- 남은 시간이 60분을 넘으면 "3시간 52분"처럼 말한다("232분" 금지)
- 길·교통 질문은 [길·교통] 블록(내비가 읽은 목적지·남은 거리·도착 예정·현재 위치·경로 교통·앞쪽 정체 구간)만 근거로, "내비 교통정보 기준"을 붙여 무엇이·어디(몇 km 앞, 도로명)·얼마나(분)를 한두 문장으로. 막힌 곳이 크면 우회를 물어본다(listen=true). 블록에 없는 사고·우회로를 지어내지 않는다
- 목적지가 없는데 길·교통을 물으면 "어디로 가세요?"라고 묻고 listen=true
- 운전자가 목적지를 말하면(예: "강남역 가자") 잘 알려진 곳만 nav_name·nav_lat·nav_lng를 채우고 "안내 시작할게요" 식으로 답한다. 좌표를 확실히 모르면 비우고 어디인지 되묻는다. 목적지와 무관한 답은 nav_name="", nav_lat=0, nav_lng=0
- **경유지**: 가는 길에 들를 곳을 말하면(예: "코엑스 들렀다 가자", "가는 길에 휴게소 들르자") 잘 알려진 곳만 via_name·via_lat·via_lng를 채우고 "경유지로 넣을게요". 목적지와 경유지를 한 번에 말하면("코엑스 들렀다 강남역") nav_*와 via_* 둘 다. [길·교통]에 목적지가 이미 있고 들를 곳만 말하면 via_*만(nav_*는 비움 — 지금 안내에 덧붙인다). 목적지가 없는데 들를 곳만 말하면 최종 목적지를 되묻는다(listen=true). 좌표를 확실히 모르면 비우고 되묻는다. 경유지 빼 달라면 route=clear_via, "안내 그만·취소"면 route=cancel. **via_*는 운전자가 이번 말에서 들를 곳을 말했을 때만** — [최근 대화]에 나온 경유지를 다시 싣지 않는다("강남역 가자"면 nav만). 그 밖엔 via_name="", via_lat=0, via_lng=0
- 운전자가 우회에 "응"이라고 하면 route=fast(빠른 길로 다시) — 아니면 route=""
- rerouted 이벤트(우회 결과): 맥락의 beforeMin·afterMin을 비교해 줄었으면 "N분 줄었어요", 같거나 늘면 "시간은 그대로(또는 N분 늘었어요)"라고 숫자로 말한다. 뭉뚱그리지 않는다. reverted=true면 앱이 원래 옵션으로 되돌린 것 — "바꿔 봤는데 시간이 안 줄어서 원래 길로 돌렸어요"
- traffic 이벤트(정찰이 새 위험을 찾음): findings의 무엇·어디·얼마나를 한 문장으로, appWarned가 있으면 경고를 되풀이하지 말고 속도·우회만. **우회 타이밍**: detour=possible이면 branchM(다음 갈림길까지 m) 전에 결정해야 하니 "곧 갈림길에서 돌아갈 수 있어요, 우회할까요?"(listen=true) · detour=none이면 우회를 묻지 말고 "돌아갈 길은 없어요, 차로 지키고 천천히 가요" · unknown이면 우회를 질문으로(listen=true)
- depart 이벤트(출발): 목적지·남은 시간(도착 예정) + [길·교통]의 경로 교통 한 문장("내비 교통정보 기준" — 막힘 없으면 "막히는 곳 없어요", 있으면 어디·몇 분). 경유지가 있으면 "○○ 들렀다 가요"
- no_dest 이벤트(목적지 없이 출발): **반드시 목적지를 묻는다** — "어디로 가세요?" 한 문장 + 말해 주면 길·교통을 봐 준다고, listen=true. 취향 질문·다른 화제를 섞지 않는다(asked_slot=""). [취향] 자주 가는 곳과 시간대가 맞으면 "회사 가세요?"처럼 짐작해 물어도 된다
- waypoint 이벤트(경유지 도착): viaName을 말하고, 볼일 보고 출발하면 목적지까지 이어서 안내한다고 짧게"""


def block(msg, env):
    """앱이 내비(NaviHelper)에서 읽은 길·교통 — 목적지·남은 거리·도착·현재 위치·교통 요약·경유지·경로 도로·앞쪽 정체"""
    c = env["ctx"]
    where = f"현재 위치 {c['addr']}" if c.get("addr") else "현재 위치 모름"
    move = f"{'주행 중' if c.get('driving') else '정차'} {c.get('speed', 0)}km/h"
    if not c.get("dest"):
        return f"[길·교통] 목적지 없음 · {where} · {move}"
    out = [f"[길·교통] 목적지 {c['dest']} · 남은 {c.get('remainKm', '?')}km {c.get('remainMin', '?')}분(도착 {c.get('eta', '?')}) · {where} · {move}",
           f"  경로 교통(내비): {c.get('traffic') or '아직 정찰 전'}"]
    for v in c.get("via") or []:
        out.append(f"  경유지: {v}")
    if c.get("routeRoads"):
        out.append("  경로 도로(내비, 가는 순서): " + " → ".join(c["routeRoads"]))
    for a in c.get("trafficAhead") or []:
        out.append(f"  앞쪽: {a}")
    return "\n".join(out)


def normalize(data):
    """자동 두뇌의 평평한 필드 → 세션(say.py)과 같은 모양: nav{name,lat,lng} · via{…} · route(빈 문자열 = 없음)"""
    if (data.get("nav_name") or "").strip() and data.get("nav_lat") and data.get("nav_lng"):
        data["nav"] = {"name": data["nav_name"].strip(), "lat": float(data["nav_lat"]), "lng": float(data["nav_lng"])}
    if (data.get("via_name") or "").strip() and data.get("via_lat") and data.get("via_lng"):
        data["via"] = {"name": data["via_name"].strip(), "lat": float(data["via_lat"]), "lng": float(data["via_lng"])}
    data["route"] = data.get("route") or None


def post(rep, msg, b, dry, rec):
    rep["nav"] = b.get("nav")
    rep["via"] = b.get("via")
    rep["route"] = b.get("route")
