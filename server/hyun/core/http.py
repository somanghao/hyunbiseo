"""HTTP — 코어 라우트(health · in · reply · wait · answer · log) + 기능 라우트(features의 GET·POST 표). 127.0.0.1 전용."""
import json
import time
from http.server import BaseHTTPRequestHandler
from urllib.parse import parse_qs, urlparse

import features
from core import engine, inbox, store


class Handler(BaseHTTPRequestHandler):
    # HTTP/1.1 keep-alive — 1.0(응답마다 연결 닫기)일 때 에뮬 앱이 "unexpected end of stream"을 자주 받았다(nc는 정상)
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):   # 콘솔 소음 줄이기(대화는 log.jsonl에)
        pass

    # ---------- 응답 도우미(기능 라우트도 쓴다) ----------
    def send_bytes(self, body, ctype, code=200):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_json(self, obj, code=200):
        self.send_bytes(json.dumps(obj, ensure_ascii=False).encode("utf-8"), "application/json; charset=utf-8", code)

    def send_html(self, text):
        self.send_bytes(text.encode("utf-8"), "text/html; charset=utf-8")

    def body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n).decode("utf-8") or "{}") if n else {}

    # ---------- GET ----------
    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        if u.path == "/hyun/health":   # brain: 누구든 답할 수 있나 · mode: session|claude|api|None
            mode = "session" if inbox.session_alive() else engine.auto_engine()
            return self.send_json({"ok": True, "brain": mode is not None, "mode": mode, **engine.login_state()})
        if u.path == "/hyun/log":
            return self.send_json(store.recent_log(int(q.get("n", 40))))
        if u.path == "/hyun/wait":     # 세션(두뇌) long-poll: 답 안 한 메시지가 생길 때까지
            return self.wait_inbox(float(q.get("timeout", 50)))
        if u.path == "/hyun/reply":    # 앱 long-poll: 내 메시지(id)에 답이 올 때까지
            return self.wait_reply(int(q.get("id", 0)), float(q.get("timeout", 25)))
        route = features.routes("GET").get(u.path)
        if route:
            return route(self, q)
        self.send_json({"error": "not found"}, 404)

    # ---------- POST ----------
    def do_POST(self):
        u = urlparse(self.path)
        b = self.body()
        if u.path == "/hyun/in":       # 앱 → 들은 말(talk) 또는 주행 이벤트(event)
            rid, _msg = inbox.add_message(b.get("kind", "talk"), b.get("text"), b.get("ctx"), b.get("cid"))
            return self.send_json({"id": rid})
        if u.path == "/hyun/answer":   # 세션 → 답. 그 id보다 앞선 미답 메시지는 지나간 것으로 본다
            inbox.apply_answer(int(b.get("id", 0)), b, by="session")
            return self.send_json({"ok": True})
        route = features.routes("POST").get(u.path)
        if route:
            return route(self, b)
        self.send_json({"error": "not found"}, 404)

    # ---------- long-poll ----------
    def wait_inbox(self, timeout):
        end = time.time() + min(timeout, 110)
        with inbox.cond:
            inbox.session_waiting += 1
            try:
                while True:
                    inbox.brain_seen = time.time()
                    pending = [m for m in inbox.inbox if m["state"] == "pending"]
                    left = end - time.time()
                    if pending or left <= 0:
                        break
                    inbox.cond.wait(min(left, 20))   # 20초마다 깨어나 brain_seen 갱신(앱의 "두뇌 연결" 표시)
            finally:
                inbox.session_waiting -= 1
        self.send_json({"pending": [{k: v for k, v in m.items() if k != "_t"} for m in pending]})

    def wait_reply(self, rid, timeout):
        end = time.time() + min(timeout, 60)
        skipped = False
        with inbox.cond:
            while rid not in inbox.replies:
                skipped = any(m["id"] == rid and m["state"] == "skipped" for m in inbox.inbox)
                left = end - time.time()
                if skipped or left <= 0:
                    break
                inbox.cond.wait(left)
            rep = inbox.replies.get(rid)
        if rep:
            return self.send_json({"id": rid, **rep})
        # 두뇌가 더 새 메시지에 답하며 건너뛴 것 — 앱은 대신 말하지 않는다
        self.send_json({"id": rid, "skipped": True} if skipped else {"id": rid, "pending": True})
