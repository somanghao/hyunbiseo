"""두뇌(Claude Code 세션) 쪽 대기 — 답 안 한 메시지가 생길 때까지 블록하고, 생기면 한 줄씩 찍고 끝난다.

세션은 이걸 run_in_background로 띄워 두고(대기 중 토큰 0), 끝나면(=말이 들어오면) 답한 뒤 다시 띄운다.
  python -I server/hyun/wait.py
답과 다음 대기를 한 번에: python -I server/hyun/say.py <id> "답" [--listen] ; python -I server/hyun/wait.py
"""
import json
import sys
import time
import urllib.error
import urllib.request

BASE = "http://127.0.0.1:8790"
# 앱 기본 단계(Speaker.eventPrio와 같게) — 두뇌가 --prio로 바꿀 수 있다. 성격·말하기 규칙 정본 = persona.md
EVENT_PRIO = {"depart": 1, "near": 1, "arrived": 1, "traffic": 1, "rerouted": 1, "long_drive": 1, "no_dest": 1, "waypoint": 1, "hello": 2, "halfway": 2, "restarea": 2,
              "mode": 2, "battery_low": 2, "quiet": 3}
MODE = {"transit": "대중교통", "walk": "산책", "work": "업무중"}


def fmt_ctx(c):
    if not c:
        return ""
    parts = []
    if c.get("device") == "phone":
        parts.append(f"폰({c.get('bot', '현비서')}·{MODE.get(c.get('mode'), c.get('mode'))}"
                     + ("·이어폰 없음=자막만" if c.get("mode") == "transit" and not c.get("headset", True) else "") + ")")
    if "gear" in c or "speed" in c:
        parts.append(f"{c.get('gear', '?')} {c.get('speed', 0)}km/h" + (" 주행규제" if c.get("driving") else ""))
    if c.get("dest"):
        parts.append(f"→{c['dest']} {c.get('remainKm', '?')}km {c.get('remainMin', '?')}분(도착 {c.get('eta', '?')})"
                     + (f"/총 {c['totalKm']}km" if c.get("totalKm") else ""))
    if c.get("driveMin") is not None:
        parts.append(f"운전 {c['driveMin']}분")
    if c.get("quietMin") is not None:
        parts.append(f"말없이 {c['quietMin']}분")
    if c.get("soc") is not None:
        parts.append(f"배터리 {c['soc']}%")
    if c.get("addr"):
        parts.append(c["addr"])
    if c.get("tempC") is not None:
        parts.append(f"{c['tempC']}℃ 비{c.get('rainPct', '?')}%")
    if c.get("t"):
        parts.append(c["t"])
    if c.get("schedule"):
        parts.append("일정: " + " / ".join(c["schedule"]))
    if c.get("reminders"):
        parts.append("⏰ " + " / ".join(c["reminders"]))
    if c.get("todos"):
        parts.append("할 일: " + " / ".join(c["todos"]))
    if c.get("walkKm") is not None:
        parts.append(f"산책 {c['walkKm']}km {c.get('walkMin', 0)}분")
    if c.get("traffic"):
        parts.append(f"교통: {c['traffic']}")
    if c.get("beforeMin") is not None:
        parts.append(f"우회 전 {c['beforeMin']}분 → 후 {c.get('afterMin')}분")
    known = {"gear", "speed", "driving", "dest", "remainKm", "remainMin", "eta", "totalKm", "driveMin", "quietMin",
             "soc", "addr", "tempC", "rainPct", "t", "traffic", "findings", "appWarned", "beforeMin", "afterMin", "device", "bot", "mode", "headset",
             "schedule", "reminders", "todos", "walkKm", "walkMin"}
    parts += [f"{k}={v}" for k, v in c.items() if k not in known]
    return " · ".join(parts)


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    down_since = None
    while True:
        try:
            with urllib.request.urlopen(f"{BASE}/hyun/wait?timeout=100", timeout=120) as r:
                pending = json.loads(r.read().decode("utf-8"))["pending"]
            down_since = None
        except (urllib.error.URLError, OSError, ValueError) as e:
            down_since = down_since or time.time()
            if time.time() - down_since > 60:
                print(f"SERVER DOWN — {e}. 서버를 다시 띄우고(python -I server/hyun/hyun_server.py) wait.py를 다시 실행")
                return
            time.sleep(3)
            continue
        if pending:
            for m in pending:
                kind = "말" if m["kind"] == "talk" else f"이벤트:{m['text']}"
                text = f" 「{m['text']}」" if m["kind"] == "talk" else ""
                prio = "대답(P1 자리)" if m["kind"] == "talk" else f"P{EVENT_PRIO.get(m['text'], 2)}"
                print(f"#{m['id']} {m['ts']} {kind}{text}  [{prio}]")
                ctx = fmt_ctx(m.get("ctx"))
                if ctx:
                    print(f"   {ctx}")
                for f in (m.get("ctx") or {}).get("findings") or []:
                    tag = " [시연 주입]" if f.get("src") == "demo" else ""
                    det = {"possible": f" · 우회 가능(갈림길 {f.get('branchM')}m 앞)", "none": " · 우회로 없음"}.get(f.get("detour"), "")
                    print(f"   ⚠ {f.get('text')} (위험도 {f.get('sev')}){det}{tag}")
                if m.get("brief"):
                    order = ",".join(m["brief"].get("order") or []) or "모름"
                    print(f"   [브리핑 {m['brief']['mode']}] 순서 {order} — persona.md §답 쓰는 법 '브리핑'")
                for v in (m.get("ctx") or {}).get("via") or []:
                    print(f"   경유지: {v}")
                if (m.get("ctx") or {}).get("reverted"):
                    print("   (앱이 원래 경로 옵션으로 되돌림)")
                if (m.get("ctx") or {}).get("appWarned"):
                    print(f"   (앱이 이미 경고함: {m['ctx']['appWarned']})")
            return


if __name__ == "__main__":
    main()
