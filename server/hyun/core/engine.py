"""자동 두뇌 — 세션(wait.py)이 안 받는 메시지를 ② 내 Claude 계정(`claude -p`, brain/cli) → ③ API 키(brain/api) 순으로 받는다.
세션처럼 가장 최근 것에 답하고 앞의 것은 건너뛴다. 검색 모드(스포츠·기사·휴게소 …)는 기능 search 훅이 정한다.
"""
import time

import features
from brain import cli as brain_cli
from core import config, inbox, store

try:
    from brain import api as brain_api   # anthropic SDK가 없으면 API 두뇌만 빠진다
except ImportError as e:
    brain_api = None
    print(f"API 두뇌 없음: {e}", flush=True)


def api_ready():
    return bool(brain_api and brain_api.load_cred())


def cli_ready():
    return brain_cli.ready()


def auto_engine():
    return "claude" if cli_ready() else "api" if api_ready() else None


def login_state():
    return {"login": api_ready(), "claude": cli_ready()}


def brain_worker():
    while True:
        with inbox.cond:
            inbox.cond.wait(1.0)
            pending = [m for m in inbox.inbox if m["state"] == "pending" and not m.get("auto")]
            if not pending:
                continue
            m = pending[-1]
            age = time.time() - m.get("_t", time.time())
            if inbox.session_alive() and (inbox.session_waiting > 0 or age < config.API_TAKEOVER):
                continue   # 세션이 받을 차례
            engine = auto_engine()
            if not engine:
                continue
            m["auto"] = True
        try:   # 한 메시지에서 기능이 터져도 두뇌 스레드는 살린다(11-08: 훅 이름 충돌로 스레드가 죽어 이후 답이 전부 시간 초과)
            answer_one(m, engine)
        except Exception as e:
            import traceback
            traceback.print_exc()
            print(f"{engine}|{m['id']}|ERROR|{type(e).__name__} {e}", flush=True)
            store.append_log({"dir": "app", "ts": time.strftime("%H:%M:%S"), "auto_fail": f"{type(e).__name__} {e}", "engine": engine, "id": m["id"]})


def answer_one(m, engine):
    search = engine == "claude" and features.any_hook("search", m)   # 최신 사실이 필요하면 내 계정 두뇌에 웹 검색을 열어 준다
    recent = [] if (m.get("ctx") or {}).get("harness") else store.recent_text()
    if engine == "claude":
        data, err = brain_cli.answer(m, store.load_memory(), recent, search=search)
    else:
        data, err = brain_api.answer(m, store.load_memory(), recent)
    if search:
        engine = "claude+search"
    if data:
        features.hook("normalize", data)   # 자동 두뇌의 평평한 필드 → 세션(say.py)과 같은 모양(nav{} · via{} …)
        print(f"{engine}|{m['id']}|{data.get('_model')}|{data.get('_ms', '')}ms|{data.get('say')}", flush=True)
        inbox.apply_answer(m["id"], data, by=engine)
    else:
        print(f"{engine}|{m['id']}|FAIL|{err}", flush=True)
        store.append_log({"dir": "app", "ts": time.strftime("%H:%M:%S"), "auto_fail": err, "engine": engine, "id": m["id"]})


def cli_warmup():
    """첫 claude -p는 준비에 ~28초(실측) — 서버가 켜질 때 짧은 요청으로 미리 데운다(로그인돼 있을 때만)"""
    if not cli_ready():
        return
    t0 = time.time()
    _d, err = brain_cli.answer({"kind": "event", "text": "warmup", "ctx": {}}, {}, [])
    print(f"claude|warmup|{int((time.time() - t0) * 1000)}ms|{err or 'ok'}", flush=True)
