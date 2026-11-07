"""현비서 취향 정리 봇 — 대화 중간중간 최근 대화를 다시 읽어 취향 기록(data/taste.json)을 다듬는다.

왜: 매 답마다 두뇌가 taste를 넣지만 바쁜 답에선 놓치고, 같은 걸 다르게 적고(LG·LG 트윈스), 칸을 틀린다.
도착 뒤 앱을 꺼도 되게 **서버가** 돈다(앱 이벤트에 기대지 않음) — hyun_server의 taste_refiner 스레드가 30초마다 due()를 보고 run().

언제(due): 새 대화 8마디 이상 · 3마디 이상이고 2분 조용 · 도착 이벤트 뒤 · 30분 지났고 1마디 이상이고 2분 조용
무엇을: 지금 취향 기록 + 기억 + 마지막 정리 뒤 새 대화 → 내 Claude 계정(`claude -p`, 도구 없음, JSON 스키마) →
       ops(add 놓친 취향 · merge 같은 것 합치기 · rename 짧은 명사구로 · move 칸 고치기 · drop 잡음·민감 정보) → taste.apply_refine
기록: data/taste_refine.jsonl(언제·몇 마디·ops·바뀐 것·걸린 ms) · taste.json refine{offset·at·note·runs}

수동: python -I -X utf8 server/hyun/taste_refine.py [--dry] [--all]   (--dry 적용 안 함 · --all 처음부터 다시 읽기)
"""
import importlib.util
import json
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.environ.get("HYUN_DATA") or os.path.join(HERE, "data")
LOG = os.path.join(DATA, "log.jsonl")
MEMORY = os.path.join(DATA, "memory.json")
OUT = os.path.join(DATA, "taste_refine.jsonl")


def _load(name, file):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, file))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


taste = _load("hyun_taste_r", "taste.py")
brain_cli = _load("hyun_brain_cli_r", "brain_cli.py")

MAX_LINES = 80          # 한 번에 읽는 대화 줄(처음 돌 땐 최근 것만)
IDLE_S = 120
EVERY_S = 30 * 60
TIMEOUT = 75

SCHEMA = {
    "type": "object",
    "properties": {
        "ops": {"type": "array", "items": {"type": "object", "properties": {
            "op": {"type": "string", "enum": ["add", "merge", "rename", "move", "drop"]},
            "slot": {"type": "string", "enum": list(taste.SLOTS)},
            "value": {"type": "string", "description": "대상 값 — add면 새 값(짧은 명사구), 나머지는 기록에 있는 값 그대로"},
            "into": {"type": "string", "description": "merge·rename의 결과 값, 아니면 빈 문자열"},
            "to_slot": {"type": "string", "enum": [""] + list(taste.SLOTS), "description": "move의 새 칸, 아니면 빈 문자열"},
            "how": {"type": "string", "enum": ["like", "dislike"], "description": "add일 때 좋아함/싫어함(나머지는 like)"},
            "why": {"type": "string", "description": "근거 한 줄(대화 인용 짧게)"}},
            "required": ["op", "slot", "value", "into", "to_slot", "how", "why"], "additionalProperties": False}},
        "note": {"type": "string", "description": "이번 정리 한 줄 요약(한국어)"},
    },
    "required": ["ops", "note"],
    "additionalProperties": False,
}

SYSTEM = """너는 차량 비서 '현비서'의 취향 정리 담당이다. 운전자와 현비서가 나눈 대화를 다시 읽고, 운전자 취향 기록을 다듬는 작업 목록(ops)만 JSON으로 낸다.

칸: food 음식 · music 음악 · movie 영화·드라마 · content 웹툰·웹소설 · stock 관심 종목 · sports 응원팀·운동 · news 관심 뉴스 · place 자주 가는 곳 · life 생활(반려동물·가족·일과) · talk 말투(짧게/수다스럽게 등)

할 일
1. add — 대화에서 운전자가 **스스로** 드러낸 취향 중 기록에 없는 것(좋아함/싫어함이 분명한 것만). 값은 짧은 명사구(예: 국밥, 매운 음식, 아이유, LG 트윈스)
   - 현비서가 한 말·추천은 취향이 아니다. 운전자가 한 번 물어본 것(예: "삼성전자 주가 어때?")은 취향이 아니다 — 같은 것을 두 번 넘게 물었으면 관심(stock·news 등)으로 add
   - 기억 줄에 취향이 문장으로 들어 있으면(예: "국밥을 좋아함") 기록에 없을 때 add
2. merge — 같은 대상을 다르게 적은 값만 합친다(띄어쓰기·한글/영문·줄임말: "LG"·"엘지 트윈스"·"LG 트윈스"). 다른 대상(국밥·순대국)은 합치지 않는다. into = 더 정확한 이름
3. rename — 값이 문장처럼 길거나 어색하면 짧은 명사구로(예: "매운 거 좋아함" → "매운 음식"). **뜻을 잃으면 하지 않는다** — 괄호 속 시간·이름·횟수 같은 사실(예: "강아지 키움(저녁 6시 밥)")은 현비서가 챙겨 줄 재료라 그대로 둔다
4. move — 칸이 틀린 값(예: 음식 칸의 "아이유" → music)
5. drop — 취향이 아닌 잡음("그거", "모름", 한때의 상태 "피곤함")과 민감 정보(건강·종교·정치·금융 계좌)

원칙: 확실하지 않으면 하지 않는다 — ops가 빈 배열이어도 된다. 기록에 있는 값을 가리킬 땐 value를 기록에 적힌 그대로. 같은 값에 ops 두 개를 내지 않는다.
note = 한국어 한 줄(예: "놓친 취향 2개 추가, LG 표기 합침")."""


def _read_new(offset, all_=False):
    """마지막 정리 뒤 새 대화 줄 — (줄들, 새 offset, 운전자 말 수). 하네스 시험 대화는 뺀다"""
    try:
        size = os.path.getsize(LOG)
    except OSError:
        return [], 0, 0
    if all_ or offset > size:
        offset = 0
    with open(LOG, "rb") as f:
        f.seek(offset)
        raw = f.read()
    lines, talks = [], 0
    for ln in raw.decode("utf-8", "replace").splitlines():
        try:
            r = json.loads(ln)
        except ValueError:
            continue
        if r.get("harness") or (r.get("ctx") or {}).get("harness"):
            continue
        if r.get("dir") == "in" and r.get("kind") == "talk" and r.get("text"):
            lines.append(f"운전자: {r['text']}")
            talks += 1
        elif r.get("dir") == "in" and r.get("kind") == "event" and r.get("text") in ("arrived", "depart"):
            lines.append(f"(주행 이벤트 {r['text']})")
        elif r.get("dir") == "out" and r.get("say"):
            lines.append(f"현비서: {r['say']}")
    return lines[-MAX_LINES:], offset + len(raw), talks


def _record():
    t = taste.load()
    out = []
    for k, (name, _) in taste.SLOTS.items():
        s = t["slots"].get(k) or {}
        for side, label in (("like", "좋아함"), ("dislike", "싫어함")):
            items = [f"{x['v']}({x['n']}회, 마지막 {x.get('last', '?')})" for x in s.get(side, [])]
            if items:
                out.append(f"  {k} {name} {label}: " + " · ".join(items))
    return "\n".join(out) or "  (비어 있음)"


def due(arrived=False):
    st = taste.refine_state()
    _l, _o, talks = _read_new(st.get("offset", 0))
    if talks == 0:
        return False
    try:
        idle = time.time() - os.path.getmtime(LOG)
    except OSError:
        idle = 0
    since = time.time() - (st.get("ts") or 0)
    return (arrived or talks >= 8 or (talks >= 3 and idle >= IDLE_S) or (since >= EVERY_S and idle >= IDLE_S))


def run(dry=False, all_=False, why=""):
    """한 번 정리 — {ran, talks, ops, done, note, ms, err}"""
    st = taste.refine_state()
    lines, end, talks = _read_new(st.get("offset", 0), all_)
    if not lines:
        return {"ran": False, "talks": 0, "err": "새 대화 없음"}
    if not brain_cli.CLAUDE or not brain_cli.ready():
        return {"ran": False, "talks": talks, "err": "내 Claude 계정 두뇌 없음"}
    try:
        with open(MEMORY, encoding="utf-8") as f:
            facts = json.load(f).get("facts", [])
    except (OSError, ValueError):
        facts = []
    prompt = (f"[지금 취향 기록]\n{_record()}\n\n[기억(일화·사실)]\n  " + (" / ".join(facts[-30:]) or "(없음)") +
              f"\n\n[마지막 정리 뒤 새 대화 — {talks}마디]\n" + "\n".join(lines))
    os.makedirs(brain_cli.CWD, exist_ok=True)
    args = [brain_cli.CLAUDE, "-p", prompt, "--model", brain_cli.MODEL, "--output-format", "json",
            "--json-schema", json.dumps(SCHEMA, ensure_ascii=False), "--system-prompt", SYSTEM, "--tools", "",
            "--strict-mcp-config", "--setting-sources", "local", "--no-session-persistence"]
    t0 = time.time()
    try:
        r = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", timeout=TIMEOUT, cwd=brain_cli.CWD)
        j = json.loads(r.stdout)
        data = j.get("structured_output") if isinstance(j.get("structured_output"), dict) else json.loads(j.get("result") or "")
    except subprocess.TimeoutExpired:
        return {"ran": False, "talks": talks, "err": f"시간 초과 {TIMEOUT}초"}
    except (OSError, ValueError, TypeError) as e:
        return {"ran": False, "talks": talks, "err": f"{type(e).__name__}"}
    ms = int((time.time() - t0) * 1000)
    ops = data.get("ops") or []
    done = [] if dry else taste.apply_refine(ops)
    note = data.get("note", "")
    res = {"ran": True, "talks": talks, "ops": ops, "done": done, "note": note, "ms": ms, "why": why, "dry": dry}
    if not dry:
        taste.refine_mark(offset=end, ts=time.time(), at=time.strftime("%Y-%m-%d %H:%M"), note=note,
                          changed=len(done), runs=(st.get("runs") or 0) + 1)
        os.makedirs(DATA, exist_ok=True)
        with open(OUT, "a", encoding="utf-8") as f:
            f.write(json.dumps({"at": time.strftime("%Y-%m-%d %H:%M:%S"), **res}, ensure_ascii=False) + "\n")
    return res


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    r = run(dry="--dry" in sys.argv, all_="--all" in sys.argv, why="수동")
    print(json.dumps(r, ensure_ascii=False, indent=1))
    print(taste.block())
