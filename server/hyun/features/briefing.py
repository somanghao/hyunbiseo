"""시동 브리핑 순서 — 시동(hello)인데 3시간 안에 안 했으면: 순서를 모를 때 "뭐부터 들을까요?"(ask) · 알면 그 순서로(give) ·
물은 뒤 3분 안의 말 = 순서 대답(answer — brief_order 저장 + 바로 브리핑) · "브리핑 해 줘"(request).
판정은 메시지가 들어올 때 msg["brief"]에 적어 두고([브리핑] 블록 · 답 기록이 같이 쓴다), 데이터는 taste.json의 brief(taste 기능).
"""
from features import feeds, taste

SCHEMA = {
    "brief_order": {"type": "array", "items": {"type": "string", "enum": list(taste.BRIEF_CATS)},
                    "description": "운전자가 시동 브리핑 순서를 말했으면 그 순서, 아니면 빈 배열"},
}

RULES = """- **브리핑**: [브리핑] 블록이 있으면 그 지시를 따른다. 운전자가 브리핑 순서를 말하면(예: "날씨 먼저, 그다음 주식") brief_order에 그 순서(weather 날씨 · news 뉴스 · stock 주식 · fx 환율 · movie 영화 · webtoon 웹툰 · webnovel 웹소설 · traffic 교통), 아니면 []"""


def on_in(msg):
    msg["brief"] = taste.brief_mode(msg["kind"], msg["text"], msg["ctx"])


def _plan(msg):
    """(블록 문장, 실을 정보 통로)"""
    b = msg.get("brief")
    if not b:
        return None, []
    c = msg.get("ctx") or {}
    names = taste.BRIEF_CATS
    order = [x for x in b.get("order") or [] if x in names]
    seq = " → ".join(names[x] for x in order)
    many = 2 if c.get("driving") else 4
    mode = b.get("mode")
    if mode == "ask":
        return ("[브리핑] 시동 — 운전자의 브리핑 순서를 아직 모른다. 짧게 인사한 뒤 \"오늘 브리핑은 뭐부터 들을까요? 날씨, 뉴스, 주식, 교통 같은 것 중에 순서대로 말해 주세요\"라고 묻고 listen=true. "
                "브리핑 내용은 아직 말하지 않는다. brief_order=[]"), []
    if mode == "give":
        txt = (f"[브리핑] 시동 브리핑 — 운전자가 정한 순서: {seq}. 짧은 인사 뒤 이 순서대로 항목마다 한 문장(앞 {many}개까지), 아래 [정보 통로]·[길·교통] 값만. "
               "교통은 목적지가 없으면 '어디로 가세요?'로 대신하고 listen=true. 순위·목록이라도 card는 쓰지 않는다")
    elif mode == "answer":
        txt = ("[브리핑] 방금 시동 때 \"뭐부터 들을까요?\"라고 물었다 — 운전자의 이 말은 그 대답일 가능성이 크다. 순서를 말했으면 brief_order에 넣고 "
               f"\"다음부터 이 순서로 할게요\"라고 짧게 확인한 뒤 그 순서대로 앞 {many}개 항목을 한 문장씩 브리핑한다(아래 [정보 통로] 값만). 순서가 아닌 다른 말이면 그 말에 답한다")
        order = [x for x in feeds.wanted_feeds(msg.get("text", "")) if x in names] or order
    else:   # request
        txt = (f"[브리핑] 운전자가 브리핑을 청했다 — 순서 {seq}. 항목마다 한 문장(앞 {many}개까지), 아래 [정보 통로]·[길·교통] 값만. "
               "운전자가 이번에 순서를 바꿔 말하면 brief_order에 새 순서")
    return txt, [x for x in order if x in feeds.FETCH]


def block(msg, env):
    return _plan(msg)[0]


def feed_cats(msg):
    return _plan(msg)[1]


def last_line(msg):
    # 시동 브리핑 — '한마디'로 끝내지 않게(11-07 실측: 정한 순서가 있는데 "22분 남았어요"만 말함)
    if msg.get("brief") and msg["kind"] != "talk":
        return f"[주행 이벤트] {msg['text']}(시동) — 이번 말은 위 [브리핑] 지시대로 한다"
    return None


def post(rep, msg, b, dry, rec):
    if dry:
        return
    mode = (msg.get("brief") or {}).get("mode") if rep.get("say") else None
    saved = taste.brief_save(b.get("brief_order"), mode)
    if b.get("brief_order") and saved:
        rec["brief_order"] = saved
        print(f"brief|{msg.get('id')}|순서 {' → '.join(b['brief_order'])}", flush=True)
