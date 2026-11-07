"""현비서 자동 두뇌 질문 만들기 — 내 Claude 계정(brain/cli)·API(brain/api)가 같이 쓴다.

  시스템 = persona.md(캐릭터·말하기 정본) + 기본 규칙 + 기능들의 RULES(등록 순서) [+ 검색 모드 규칙]
  답 형식 = 기본 필드(say · listen · mood · prio) + 기능들의 SCHEMA
  질문 = [맥락] + 기능들의 block(길 → 기억 → 취향 → 최근 대화 → 브리핑 → 정보 통로) + 마지막 줄(말 또는 이벤트, 기능 last_line이 바꿀 수 있음)
기능 목록은 features.ALL — 여기는 기능 이름을 모른다(가져오기는 함수 안에서 — 기능이 brain.cli를 쓸 때 순환하지 않게).
"""
import json

from core import config

BASE_SCHEMA = {
    "say": {"type": "string", "description": "현비서가 소리 내어 말할 한국어 문장"},
    "listen": {"type": "boolean", "description": "질문으로 끝나 바로 대답을 들어야 하면 true"},
    "mood": {"type": "string", "enum": ["calm", "happy", "fun", "care"]},
    "prio": {"type": "integer", "enum": [1, 2, 3]},
}

BASE_RULES = """

## 자동 두뇌로 답할 때 (서버가 덧붙임)

- 출력은 지정된 JSON 하나: say(말할 문장) · listen · mood · prio + 아래 기능 필드. 마크다운·목록 기호 금지(소리로 읽힌다)
- 주행 중(맥락 driving=true)이면 50자 안팎 한두 문장, 정차면 서너 문장까지. 순위는 3개까지만 읽는다
- 맥락 battery = 이 차(전기차) 배터리 %. 폰 배터리가 아니다
- 대답은 prio 1, 먼저 건네는 말은 이벤트 성격대로(인사·제안 2, 수다 3) — 주행 필수 이벤트(depart·near·arrived·traffic·rerouted·long_drive·no_dest·waypoint)는 prio 1
- **못 알아들은 말**: 운전자 말이 받아쓰기 오류처럼 뜻이 안 통하면(잘린 말·엉뚱한 단어 나열·앞뒤가 안 맞음) 짐작해서 엉뚱한 답을 하지 말고 "혹시 ○○라고 하셨어요?"나 "한 번만 다시 말해 줄래요?"로 한 번 되묻고 listen=true
"""

_schema = None


def reply_schema():
    """답 형식(JSON 스키마) — 기본 + 기능 SCHEMA, 전부 required(구조화 출력 strict)"""
    global _schema
    if _schema is None:
        import features
        props = dict(BASE_SCHEMA)
        for f in features.ALL:
            props.update(getattr(f, "SCHEMA", {}) or {})
        _schema = {"type": "object", "properties": props, "required": list(props), "additionalProperties": False}
    return _schema


def persona(search=False):
    import features
    from features import search as search_feature
    with open(config.PERSONA, encoding="utf-8") as f:
        text = f.read() + BASE_RULES
    text += "".join("\n" + f.RULES.strip("\n") for f in features.ALL if getattr(f, "RULES", ""))
    return text + "\n" + (search_feature.rules_for_search() if search else "")


def user_block(msg, memory, recent, with_feeds=True):
    import features
    c = msg.get("ctx") or {}
    env = {"ctx": c, "memory": memory or {}, "recent": recent or [], "feeds": with_feeds}
    lines = [f"[맥락] {json.dumps(c, ensure_ascii=False, sort_keys=True)}"]
    for f in features.ALL:
        fn = getattr(f, "block", None)
        if fn:
            b = fn(msg, env)
            if b:
                lines.append(b)
    last = next((x for x in (getattr(f, "last_line", lambda m: None)(msg) for f in features.ALL) if x), None)
    if last:
        lines.append(last)
    elif msg["kind"] == "talk":
        lines.append(f"[운전자가 방금 한 말] {msg['text']}")
    else:
        lines.append(f"[주행 이벤트] {msg['text']} — 이 순간 현비서가 먼저 건넬 한마디(필요 없으면 짧은 공감)")
    return "\n".join(lines)


def needs_search(text):
    """옛 이름(하네스·외부 도구 호환) — features.search.needs_search"""
    from features import search
    return search.needs_search(text)
