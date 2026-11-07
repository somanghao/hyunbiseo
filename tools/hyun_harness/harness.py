"""현비서 하네스 — 질문을 던지고, 답을 받고, 판정하고, 화면을 보고, 보고서를 만들고, 스스로 고친다.

  python -I -X utf8 tools/hyun_harness/harness.py run   [--cat 스포츠] [--ids q001,q015] [--limit 10]
  python -I -X utf8 tools/hyun_harness/harness.py judge [--run <폴더>]
  python -I -X utf8 tools/hyun_harness/harness.py shots [--run <폴더>]      # 에뮬 앱에 말로 묻고 화면 캡처 → 화면 판정
  python -I -X utf8 tools/hyun_harness/harness.py report [--run <폴더>]     # dashboard/hyun/harness-data.json
  python -I -X utf8 tools/hyun_harness/harness.py evolve [--run <폴더>]     # 질문 은행·판정 기준·API 후보·하네스 개선안
  python -I -X utf8 tools/hyun_harness/harness.py all [--shots] [--evolve] [--limit N]

전제: 현비서 서버(server/hyun/hyun_server.py, 8790)가 켜져 있고 두뇌(내 Claude 계정)가 붙어 있다.
시험 질문은 ctx.harness=true — 서버가 취향·기억·자주 묻는 것·최근 대화에 넣지 않는다.
판정·evolve는 이 PC의 claude -p(사용자 구독)를 쓴다.
"""
import argparse
import datetime as dt
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.request
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
RUNS = os.path.join(HERE, "runs")
BANK = os.path.join(HERE, "questions.json")
RUBRIC = os.path.join(HERE, "rubric.md")
BACKLOG = os.path.join(HERE, "backlog.json")
PROPOSALS = os.path.join(HERE, "proposals.md")
PAGE_DATA = os.path.join(ROOT, "dashboard", "hyun", "harness-data.json")
SERVER = os.environ.get("HYUN_SERVER", "http://127.0.0.1:8790")
CLAUDE = shutil.which("claude")
BRAIN_CWD = os.path.join(os.environ.get("PUBLIC", r"C:\Users\Public"), "hyun-brain")
ADB = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
SERIAL = os.environ.get("HYUN_SERIAL", "emulator-5554")
PKG = "io.github.somanghao.makecar.hyun"
BASE_CTX = {"driving": False, "speed": 0, "gear": "P", "addr": "성남시 수정구 시흥동", "battery": 75, "harness": True}

sys.stdout.reconfigure(encoding="utf-8")


# ---------- 공통 ----------
def jload(p, default=None):
    try:
        with open(p, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def jsave(p, obj):
    os.makedirs(os.path.dirname(p), exist_ok=True)
    tmp = p + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)
    os.replace(tmp, p)


def http(method, path, body=None, timeout=35):
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(SERVER + path, data=data, method=method, headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode("utf-8"))


def latest_run():
    if not os.path.isdir(RUNS):
        return None
    ds = sorted(d for d in os.listdir(RUNS) if os.path.isdir(os.path.join(RUNS, d)))
    return os.path.join(RUNS, ds[-1]) if ds else None


def claude_json(prompt, schema, system, tools="", allowed="", add_dirs=(), timeout=600):
    """claude -p 구조화 출력 한 번 — (dict, 오류)"""
    if not CLAUDE:
        return None, "claude CLI 없음"
    os.makedirs(BRAIN_CWD, exist_ok=True)
    args = [CLAUDE, "-p", prompt, "--model", os.environ.get("HYUN_HARNESS_MODEL", "sonnet"), "--output-format", "json",
            "--json-schema", json.dumps(schema, ensure_ascii=False), "--system-prompt", system,
            "--tools", tools, "--strict-mcp-config", "--setting-sources", "local", "--no-session-persistence"]
    if allowed:
        args += ["--allowedTools", allowed]
    for d in add_dirs:
        args += ["--add-dir", d]
    t0 = time.time()
    try:
        r = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", timeout=timeout, cwd=BRAIN_CWD)
    except subprocess.TimeoutExpired:
        return None, f"시간 초과 {timeout}초"
    try:
        j = json.loads(r.stdout)
    except ValueError:
        return None, f"출력 해석 실패 {r.stderr[:200]}"
    data = j.get("structured_output")
    if not isinstance(data, dict):
        try:
            data = json.loads(j.get("result") or "")
        except ValueError:
            return None, f"형식 오류 {str(j.get('result'))[:200]}"
    data["_sec"] = round(time.time() - t0, 1)
    return data, None


def bank_items(a):
    items = [i for i in jload(BANK, {"items": []})["items"] if not i.get("retired")]
    if getattr(a, "cat", None):
        items = [i for i in items if i["cat"] == a.cat]
    if getattr(a, "ids", None):
        want = set(a.ids.split(","))
        items = [i for i in items if i["id"] in want]
    if getattr(a, "limit", None):
        items = items[: a.limit]
    return items


# ---------- run: 서버에 묻고 답 받기 ----------
def ask(item):
    ctx = {**BASE_CTX, "time": dt.datetime.now().strftime("%H:%M"), **(item.get("ctx") or {})}
    t0 = time.time()
    for attempt in range(2):
        rid = http("POST", "/hyun/in", {"kind": "talk", "text": item["q"], "ctx": ctx, "cid": str(uuid.uuid4())})["id"]
        end = time.time() + 70
        while time.time() < end:
            left = int(min(25, max(1, end - time.time())))
            try:
                r = http("GET", f"/hyun/reply?id={rid}&timeout={left}", timeout=left + 10)
            except OSError:
                time.sleep(1)
                continue
            if r.get("pending"):
                continue
            if r.get("skipped") and attempt == 0:
                break   # 앱 이벤트가 끼어들어 건너뛰어짐 — 한 번 다시
            return {"rid": rid, "ms": int((time.time() - t0) * 1000), **{k: r.get(k) for k in ("say", "card", "by", "listen", "mood", "nav", "route", "skipped")}}
        else:
            return {"rid": rid, "ms": int((time.time() - t0) * 1000), "say": None, "error": "시간 초과 70초"}
    return {"ms": int((time.time() - t0) * 1000), "say": None, "error": "두 번 건너뛰어짐"}


def cmd_run(a):
    items = bank_items(a)
    run = os.path.join(RUNS, dt.datetime.now().strftime("%Y%m%d-%H%M%S"))
    os.makedirs(run, exist_ok=True)
    try:
        h = http("GET", "/hyun/health")
    except OSError as e:
        sys.exit(f"서버 없음({SERVER}) — server/hyun/hyun_server.py를 먼저 켜세요: {e}")
    print(f"run {run} · 질문 {len(items)} · 두뇌 {h.get('mode')}", flush=True)
    out = []
    for i, it in enumerate(items, 1):
        r = ask(it)
        out.append({**it, "answer": r})
        card = r.get("card") or {}
        print(f"[{i}/{len(items)}] {it['id']} {it['cat']} · {r['ms'] / 1000:.1f}s · {r.get('by')} · card {len(card.get('items') or [])} · {(r.get('say') or r.get('error') or '')[:70]}", flush=True)
        jsave(os.path.join(run, "answers.json"), {"at": dt.datetime.now().isoformat(timespec="seconds"), "health": h, "items": out})
    return run


# ---------- judge: 답 판정 ----------
VERDICT_SCHEMA = {"type": "object", "properties": {"verdicts": {"type": "array", "items": {"type": "object", "properties": {
    "id": {"type": "string"},
    "status": {"type": "string", "enum": ["ok", "partial", "fail", "cannot"]},
    "reason": {"type": "string"},
    "needs": {"type": "string", "enum": ["feed", "search", "places", "nav", "car", "memory", "time", "none", "unsupported"]},
    "display_ideal": {"type": "string", "enum": ["text", "list", "image_list", "chart", "map"]},
    "display_ok": {"type": "boolean"},
    "fix": {"type": "string"}},
    "required": ["id", "status", "reason", "needs", "display_ideal", "display_ok", "fix"], "additionalProperties": False}}},
    "required": ["verdicts"], "additionalProperties": False}


def cmd_judge(a):
    run = a.run or latest_run()
    ans = jload(os.path.join(run, "answers.json"))
    rubric = open(RUBRIC, encoding="utf-8").read()
    rows = []
    for it in ans["items"]:
        r = it["answer"]
        rows.append({"id": it["id"], "cat": it["cat"], "q": it["q"], "ctx": {k: v for k, v in (it.get("ctx") or {}).items()},
                     "expect": it.get("expect"), "say": r.get("say") or r.get("error"), "say_len": len(r.get("say") or ""),
                     "card": {k: v for k, v in (r.get("card") or {}).items() if k != "images"} or None, "card_images": sum(1 for x in ((r.get("card") or {}).get("images") or []) if x),
                     "executed": {"nav": r.get("nav"), "route": r.get("route")}, "by": r.get("by"), "sec": round(r["ms"] / 1000, 1)})
    prompt = (f"오늘 = {dt.date.today()}. 아래 기준으로 현비서 대답 {len(rows)}개를 하나씩 판정하라. "
              f"executed.nav·route가 있으면 앱이 그 목적지 설정·우회를 실제로 실행한다(거짓 약속 아님). say_len은 기계로 잰 글자 수, card_images는 목록 카드에 붙은 그림 수다. 사실 여부가 의심되면 partial/fail로 보고 reason에 적는다.\n\n"
              f"{rubric}\n\n## 대답들\n{json.dumps(rows, ensure_ascii=False, indent=1)}")
    data, err = claude_json(prompt, VERDICT_SCHEMA, "너는 차량 음성 비서 품질 심사관이다. 엄격하고 구체적으로, 한국어로 판정한다.")
    if err:
        sys.exit(f"judge 실패: {err}")
    jsave(os.path.join(run, "verdicts.json"), data)
    from collections import Counter
    print("judge", dict(Counter(v["status"] for v in data["verdicts"])), f"{data['_sec']}s", flush=True)
    return run


# ---------- shots: 에뮬 앱 화면 ----------
SCREEN_SCHEMA = {"type": "object", "properties": {"screens": {"type": "array", "items": {"type": "object", "properties": {
    "id": {"type": "string"}, "readable": {"type": "boolean"}, "fits_content": {"type": "boolean"},
    "issues": {"type": "array", "items": {"type": "string"}}, "suggestion": {"type": "string"}},
    "required": ["id", "readable", "fits_content", "issues", "suggestion"], "additionalProperties": False}}},
    "required": ["screens"], "additionalProperties": False}


def adb(*args, out=None):
    cmd = [ADB, "-s", SERIAL, *args]
    if out:
        with open(out, "wb") as f:
            return subprocess.run(cmd, stdout=f, timeout=30).returncode
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", timeout=30).stdout


def wait_reply_for(text, after_ts, limit=60):
    end = time.time() + limit
    while time.time() < end:
        time.sleep(2)
        try:
            log = http("GET", "/hyun/log?n=30")
        except OSError:
            continue
        ins = [r for r in log if r.get("dir") == "in" and r.get("text") == text and r.get("ts", "") >= after_ts]
        if ins:
            rid = ins[-1]["id"]
            if any(r.get("dir") == "out" and r.get("id") == rid for r in log):
                return True
    return False


def cmd_shots(a):
    run = a.run or latest_run()
    items = [i for i in bank_items(a) if i.get("screen")] if not a.all_screens else bank_items(a)
    shots = os.path.join(run, "shots")
    os.makedirs(shots, exist_ok=True)
    adb("shell", "am", "start", "--user", "10", "-n", f"{PKG}/.HyunActivity")
    time.sleep(3)
    done = []
    for it in items:
        ts = time.strftime("%H:%M:%S")
        adb("shell", f"am start --user 10 -n {PKG}/.HyunActivity --ez harness true --es say '{it['q']}'")
        ok = wait_reply_for(it["q"], ts)
        time.sleep(6)   # 말·화면 그리기
        png = os.path.join(shots, f"{it['id']}.png")
        adb("exec-out", "screencap", "-p", out=png)
        done.append({"id": it["id"], "q": it["q"], "png": png, "replied": ok, "expect": it.get("expect")})
        print(f"shot {it['id']} replied={ok}", flush=True)
    # 화면 판정 — claude가 PNG를 직접 본다
    listing = "\n".join(f"- {d['id']} · 질문 \"{d['q']}\" · 기대 화면 {d['expect'].get('display')} · 파일 {d['png']}" for d in done)
    prompt = (f"차량 인포테인먼트 화면 캡처 {len(done)}장을 Read 도구로 하나씩 열어 보고 판정하라. 오른쪽 넓은 칸이 현비서 앱 영역(왼쪽 회색 패널은 차량 시스템 UI라 제외). 이 캡처는 모두 정차(P) 상태다 — 목록 카드는 정차 중에만 뜨고 주행 중엔 숨겨지므로, 목록 개수는 주행 기준이 아니라 정차 중 한눈에 읽히는지로 본다.\n"
              f"- readable: 글자 크기·대비·잘림 없이 1~2초 안에 읽히는가\n- fits_content: 기대 화면(목록이면 표, 웹툰이면 표지 그림, 주가면 그래프)에 맞게 보이는가\n"
              f"- issues: 구체 문제(잘린 글자, 빈 공간, 그림 없음, 겹침 등) · suggestion: 화면을 어떻게 바꿀지 한 줄\n\n{listing}")
    data, err = claude_json(prompt, SCREEN_SCHEMA, "너는 차량 HMI 화면 심사관이다. 운전자 주의 분산과 가독성을 기준으로 엄격히 본다.",
                            tools="Read", allowed="Read", add_dirs=(shots,), timeout=900)
    if err:
        print(f"화면 판정 실패: {err}", flush=True)
        data = {"screens": []}
    data["shots"] = done
    jsave(os.path.join(run, "screens.json"), data)
    print("screens", sum(1 for s in data["screens"] if s["readable"] and s["fits_content"]), "/", len(data["screens"]), "통과", flush=True)
    return run


# ---------- report: 대시보드 데이터 ----------
def cmd_report(a):
    run = a.run or latest_run()
    ans = jload(os.path.join(run, "answers.json"), {"items": []})
    ver = {v["id"]: v for v in (jload(os.path.join(run, "verdicts.json"), {}) or {}).get("verdicts", [])}
    scr = jload(os.path.join(run, "screens.json"), {}) or {}
    sv = {s["id"]: s for s in scr.get("screens", [])}
    rel = os.path.relpath(run, ROOT).replace("\\", "/")
    rows = []
    for it in ans["items"]:
        r = it["answer"]
        rows.append({"id": it["id"], "cat": it["cat"], "q": it["q"], "say": r.get("say") or r.get("error"), "card": r.get("card"),
                     "by": r.get("by"), "sec": round(r.get("ms", 0) / 1000, 1), "expect": it.get("expect"),
                     "verdict": ver.get(it["id"]), "screen": sv.get(it["id"]),
                     "shot": f"/{rel}/shots/{it['id']}.png" if os.path.exists(os.path.join(run, "shots", f"{it['id']}.png")) else None})
    data = {"run": os.path.basename(run), "at": ans.get("at"), "brain": (ans.get("health") or {}).get("mode"), "rows": rows,
            "backlog": jload(BACKLOG, {"api": [], "app": []}), "evolve": jload(os.path.join(run, "evolve.json")),
            "history": history()}
    jsave(PAGE_DATA, data)
    print("report →", os.path.relpath(PAGE_DATA, ROOT), flush=True)


def history():
    out = []
    for d in sorted(os.listdir(RUNS)) if os.path.isdir(RUNS) else []:
        v = jload(os.path.join(RUNS, d, "verdicts.json"))
        if v:
            from collections import Counter
            c = Counter(x["status"] for x in v["verdicts"])
            out.append({"run": d, **c, "n": len(v["verdicts"])})
    return out[-12:]


# ---------- evolve: 스스로 개선 ----------
EVOLVE_SCHEMA = {"type": "object", "properties": {
    "new_questions": {"type": "array", "items": {"type": "object", "properties": {
        "cat": {"type": "string"}, "q": {"type": "string"}, "display": {"type": "string", "enum": ["text", "list", "image_list", "chart", "map"]},
        "needs": {"type": "string", "enum": ["feed", "search", "places", "nav", "car", "memory", "time", "none", "unsupported"]},
        "screen": {"type": "boolean"}, "why": {"type": "string"}}, "required": ["cat", "q", "display", "needs", "screen", "why"], "additionalProperties": False}},
    "retire": {"type": "array", "items": {"type": "string"}},
    "rubric_add": {"type": "array", "items": {"type": "string"}},
    "api_candidates": {"type": "array", "items": {"type": "object", "properties": {
        "need": {"type": "string"}, "name": {"type": "string"}, "url": {"type": "string"}, "key": {"type": "string"},
        "commercial": {"type": "string"}, "notes": {"type": "string"}}, "required": ["need", "name", "url", "key", "commercial", "notes"], "additionalProperties": False}},
    "app_backlog": {"type": "array", "items": {"type": "object", "properties": {
        "title": {"type": "string"}, "why": {"type": "string"}, "where": {"type": "string"}, "effort": {"type": "string", "enum": ["S", "M", "L"]}},
        "required": ["title", "why", "where", "effort"], "additionalProperties": False}},
    "harness_proposals": {"type": "array", "items": {"type": "string"}}},
    "required": ["new_questions", "retire", "rubric_add", "api_candidates", "app_backlog", "harness_proposals"], "additionalProperties": False}


def cmd_evolve(a):
    run = a.run or latest_run()
    ans = jload(os.path.join(run, "answers.json"), {"items": []})
    ver = (jload(os.path.join(run, "verdicts.json"), {}) or {}).get("verdicts", [])
    scr = (jload(os.path.join(run, "screens.json"), {}) or {}).get("screens", [])
    bank = jload(BANK)
    weak = [v for v in ver if v["status"] != "ok" or not v["display_ok"]]
    from collections import Counter
    cats = Counter(i["cat"] for i in bank["items"] if not i.get("retired"))
    backlog = jload(BACKLOG, {"api": [], "app": []})
    prompt = (f"오늘 = {dt.date.today()}. 너는 차 안 음성 비서 '현비서'와 그 시험 하네스를 함께 키우는 개선 담당이다.\n"
              f"현비서 구조: 앱(Pleos Connect 차량, 화면 = 왼쪽 구슬·질문·답 / 오른쪽 목록 카드 최대 10줄 텍스트, 그림·그래프는 아직 없음) → 서버 → claude -p 두뇌. "
              f"정보 통로(키 없음): 영화 KOBIS·날씨 Open-Meteo·주식 네이버·웹툰 네이버·웹소설 네이버 시리즈. 스포츠·뉴스는 웹 검색 모드. 지역 장소 API(카카오·네이버)는 키 대기 중.\n\n"
              f"## 이번 판정에서 약한 곳 ({len(weak)}/{len(ver)})\n{json.dumps(weak, ensure_ascii=False, indent=1)}\n\n"
              f"## 화면 판정\n{json.dumps(scr, ensure_ascii=False, indent=1)}\n\n"
              f"## 질문 은행 분류별 개수\n{dict(cats)}\n\n## 이미 있는 백로그 제목\n{[x.get('title') or x.get('name') for x in backlog['api'] + backlog['app']]}\n\n"
              "할 일:\n1. new_questions: 빈 분류·약한 곳을 더 캐는 새 질문 4~8개(실제 운전자 말투, 중복 금지)\n"
              "2. retire: 늘 ok라 정보가 없는 질문 id(있으면)\n3. rubric_add: 판정 기준에 더할 규칙(이번에 애매했던 것)\n"
              "4. api_candidates: needs가 feed·places·search인 빈칸을 메울 실제 API·공개 데이터를 WebSearch로 찾아 — 이름·URL·키 필요 여부·상업 이용 조건·메모(확인 못 한 건 '미확인')\n"
              "5. app_backlog: 앱·서버에 더할 기능(예: 목록 카드에 표지 그림, 주가 그래프) — 어디(파일)·크기 S/M/L\n"
              "6. harness_proposals: 이 하네스 자체를 더 좋게 할 개선안(판정 정확도·속도·화면 검사)")
    data, err = claude_json(prompt, EVOLVE_SCHEMA, "너는 신중한 제품 개선 엔지니어다. 확인한 사실과 추정을 구분하고, 한국어로 쓴다.",
                            tools="WebSearch,WebFetch", allowed="WebSearch,WebFetch", timeout=900)
    if err:
        sys.exit(f"evolve 실패: {err}")
    jsave(os.path.join(run, "evolve.json"), data)
    # 1) 질문 은행 — 새 질문 덧붙이기, 은퇴 표시(코드가 아닌 데이터라 자동 적용)
    have = {i["q"] for i in bank["items"]}
    n = len(bank["items"])
    added = 0
    for q in data["new_questions"]:
        if q["q"] in have:
            continue
        n += 1
        bank["items"].append({"id": f"q{n:03d}", "cat": q["cat"], "q": q["q"], "expect": {"display": q["display"], "needs": q["needs"]},
                              "screen": q["screen"], "source": f"evolve {dt.date.today()}", "why": q["why"]})
        added += 1
    for rid in data["retire"]:
        for i in bank["items"]:
            if i["id"] == rid:
                i["retired"] = str(dt.date.today())
    bank["version"] = bank.get("version", 1) + 1
    jsave(BANK, bank)
    # 2) 판정 기준 — 맨 아래에 날짜와 함께
    if data["rubric_add"]:
        with open(RUBRIC, "a", encoding="utf-8", newline="\n") as f:
            f.write(f"\n## 자동 추가 규칙 (evolve {dt.date.today()})\n" + "".join(f"- {r}\n" for r in data["rubric_add"]))
    # 3) 백로그 — API 후보·앱 기능(제목으로 중복 제거)
    names = {x["name"] for x in backlog["api"]}
    backlog["api"] += [dict(x, at=str(dt.date.today())) for x in data["api_candidates"] if x["name"] not in names]
    titles = {x["title"] for x in backlog["app"]}
    backlog["app"] += [dict(x, at=str(dt.date.today()), status="todo") for x in data["app_backlog"] if x["title"] not in titles]
    jsave(BACKLOG, backlog)
    # 4) 하네스 개선안 — 코드는 사람·에이전트가 검토 후 적용
    with open(PROPOSALS, "a", encoding="utf-8", newline="\n") as f:
        f.write(f"\n## {dt.date.today()} · run {os.path.basename(run)}\n" + "".join(f"- [ ] {p}\n" for p in data["harness_proposals"]))
    print(f"evolve: 질문 +{added} · 은퇴 {len(data['retire'])} · 기준 +{len(data['rubric_add'])} · API 후보 {len(data['api_candidates'])} · 앱 백로그 {len(data['app_backlog'])} · 하네스 개선안 {len(data['harness_proposals'])} · {data['_sec']}s", flush=True)
    return run


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["run", "judge", "shots", "report", "evolve", "all"])
    ap.add_argument("--run")
    ap.add_argument("--cat")
    ap.add_argument("--ids")
    ap.add_argument("--limit", type=int)
    ap.add_argument("--shots", action="store_true")
    ap.add_argument("--all-screens", action="store_true")
    ap.add_argument("--evolve", action="store_true")
    a = ap.parse_args()
    if a.cmd == "run":
        cmd_run(a)
    elif a.cmd == "judge":
        cmd_judge(a)
    elif a.cmd == "shots":
        cmd_shots(a)
    elif a.cmd == "report":
        cmd_report(a)
    elif a.cmd == "evolve":
        cmd_evolve(a)
    else:
        a.run = cmd_run(a)
        cmd_judge(a)
        if a.shots:
            cmd_shots(a)
        cmd_report(a)
        if a.evolve:
            cmd_evolve(a)
            cmd_report(a)


if __name__ == "__main__":
    main()
