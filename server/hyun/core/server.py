"""서버 시작 — 자동 두뇌 워커 · 예열 · 기능 스레드(정보 통로 예열 · 취향 정리 봇 …) · HTTP."""
import os
import threading
from http.server import ThreadingHTTPServer

import features
from core import config, engine
from core.http import Handler


def main():
    os.makedirs(config.DATA, exist_ok=True)
    threading.Thread(target=engine.brain_worker, daemon=True, name="auto-brain").start()
    threading.Thread(target=engine.cli_warmup, daemon=True, name="claude-warmup").start()
    for f in features.ALL:
        for t in (getattr(f, "threads", lambda: [])()):
            threading.Thread(target=t, daemon=True, name=f"{f.__name__.split('.')[-1]}-{t.__name__}").start()
    print(f"hyun server: http://127.0.0.1:{config.PORT}/hyun/health  (emulator: http://10.0.2.2:{config.PORT}) · "
          f"login http://127.0.0.1:{config.PORT}/hyun/login · 내 Claude 계정 {'로그인됨' if engine.cli_ready() else '없음'} · "
          f"API {'로그인됨' if engine.api_ready() else '없음'} · 기능 {', '.join(f.__name__.split('.')[-1] for f in features.ALL)}"
          + (f" · 데이터 {config.DATA}" if os.environ.get("HYUN_DATA") else ""), flush=True)
    ThreadingHTTPServer(("127.0.0.1", config.PORT), Handler).serve_forever()
