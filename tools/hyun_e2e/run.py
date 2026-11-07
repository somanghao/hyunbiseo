"""현비서 E2E 시험 — 시나리오(cases.json)를 에뮬 앱에 순서대로 걸고, 화면 녹화 · 스크린샷 · 실제 대화(들은 말 · 현비서 말 · 카드 · 이벤트)를 기록하고 판정한다.

    python -I -X utf8 tools/hyun_e2e/run.py --servers --video          # 전체(서버를 시험 데이터로 새로 띄우고, 녹화)
    python -I -X utf8 tools/hyun_e2e/run.py --servers --ids e02,e03    # 일부만
    python -I -X utf8 tools/hyun_e2e/run.py --report records/hyun-e2e/<폴더>   # 보고서만 다시

  --servers   현비서 서버(HYUN_DATA=server/hyun/data-e2e, 매번 새로 — 사용자 실제 취향·대화를 건드리지 않는다)와 받아 적기 서버를
              이 스크립트가 띄우고 끝나면 끈다. 안 주면 이미 떠 있는 서버를 쓴다(그 서버의 데이터에 기록됨)
  --keep-data 시험 데이터를 지우지 않고 이어서
  --video     케이스마다 adb screenrecord(1280x720) → ffmpeg로 이어 붙이고 실제 대화를 자막으로 입힘(video.mp4)

전제: 에뮬(Pleos_Connect_v2.1) 켜짐 · 현비서 앱 설치 · adb forward tcp:8088(차 상태 vconsole) · 시험 목소리 = Windows SAPI 한국어(tools/hyun_testaudio.py)
결과: records/hyun-e2e/<YYYYMMDD-HHMM>/ — report.html · transcript.json(케이스별 대화·판정·스크린샷·영상 위치) · logcat.txt · server-log.jsonl ·
      shots/*.jpg · video.mp4(git 제외) · raw/(케이스별 원본 영상, git 제외)
두뇌는 실제 Claude(내 계정)라 답은 매번 다르다 — 판정은 '했나'(로그 패턴)만 본다. 형식: cases.json의 steps(do/wait/shot) · expect(log/server 정규식)
"""
import argparse
import datetime as dt
import glob
import html
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
CASES = os.path.join(HERE, "cases.json")
RECORDS = os.path.join(ROOT, "records", "hyun-e2e")
DATA_E2E = os.path.join(ROOT, "server", "hyun", "data-e2e")
ADB = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
SERIAL = os.environ.get("HYUN_SERIAL", "emulator-5554")
PKG = "io.github.somanghao.makecar.hyun"
ACT = f"{PKG}/.HyunActivity"
SERVER = "http://127.0.0.1:8790"
STT_PY = os.path.expanduser(r"~/.cache/faster-whisper-venv/Scripts/python.exe")
FFMPEG = shutil.which("ffmpeg") or next(iter(glob.glob(os.path.expanduser(
    r"~/AppData/Local/Microsoft/WinGet/Packages/Gyan.FFmpeg*/ffmpeg-*/bin/ffmpeg.exe"))), None)
FFPROBE = os.path.join(os.path.dirname(FFMPEG), "ffprobe.exe") if FFMPEG else None
# 반반 화면 손잡이(2560x1440 에뮬 좌표) — 오른쪽 끝 ⋮를 가운데로 끌면 2칸, 가운데 ⋮를 오른쪽 끝으로 끌면 1칸
SPLIT_ON = (2547, 717, 1650, 717)
SPLIT_OFF = (1705, 717, 2552, 717)


def _tts():
    spec = importlib.util.spec_from_file_location("hyun_testaudio", os.path.join(ROOT, "tools", "hyun_testaudio.py"))
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


def adb(*a, timeout=30):
    return subprocess.run([ADB, "-s", SERIAL, *a], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)


def sh(cmd, timeout=30):
    return adb("shell", cmd, timeout=timeout)


def am(extras=""):
    return sh(f"am start --user 10 -n {ACT} {extras}")


def is_split():
    """현비서 창 폭이 앱 영역의 반이면 2칸(내비와 나란히) — dumpsys mBounds(11-07: 2칸 841px · 1칸 1706px)"""
    out = sh("dumpsys activity activities").stdout
    i = out.find(f"{PKG}/.HyunActivity")
    m = re.search(r"mBounds=Rect\((\d+), (\d+) - (\d+), (\d+)\)", out[i:i + 6000]) if i >= 0 else None
    return bool(m) and int(m.group(3)) - int(m.group(1)) < 1200


def watchdogs(t0, t1):
    """그 사이 에뮬 system_server가 워치독에 죽었나(GNSS HAL 멈춤 — 앱 탓 아님, QUICKMAP-pleos §3 #17) → 시각 목록"""
    out = adb("logcat", "-d", "-v", "epoch", "-b", "all", "-s", "Watchdog:W", timeout=60).stdout
    hits = []
    for ln in out.splitlines():
        m = re.match(r"^\s*(\d+\.\d+).*WATCHDOG KILLING SYSTEM PROCESS", ln)
        if m and t0 <= float(m.group(1)) <= t1:
            hits.append(dt.datetime.fromtimestamp(float(m.group(1))).strftime("%H:%M:%S"))
    return hits


def http_ok(url):
    try:
        with urllib.request.urlopen(url, timeout=2) as r:
            return r.status == 200
    except OSError:
        return False


class Logcat:
    """`adb logcat -v epoch -s HYUN:I`를 파일로 받아 꼬리를 읽는다 — 줄 = (epoch 초, 메시지)"""
    RX = re.compile(r"^\s*(\d+\.\d+)\s+\d+\s+\d+\s+\w\s+HYUN\s*:\s?(.*)$")

    def __init__(self, path):
        adb("logcat", "-c")
        self.path = path
        self.f = open(path, "w", encoding="utf-8")
        self.p = subprocess.Popen([ADB, "-s", SERIAL, "logcat", "-v", "epoch", "-s", "HYUN:I"], stdout=self.f, stderr=subprocess.DEVNULL)
        self.lines, self._pos = [], 0

    def poll(self):
        self.f.flush()
        with open(self.path, encoding="utf-8", errors="replace") as r:
            r.seek(self._pos)
            chunk = r.read()
            self._pos = r.tell()
        for ln in chunk.splitlines():
            m = self.RX.match(ln)
            if m:
                self.lines.append((float(m.group(1)), m.group(2)))
        return len(self.lines)

    def find(self, pat, since, until=None):
        rx = re.compile(pat)
        for i in range(since, until if until is not None else len(self.lines)):
            if rx.search(self.lines[i][1]):
                return i
        return None

    def wait(self, pat, since, timeout):
        end = time.time() + timeout
        while time.time() < end:
            self.poll()
            i = self.find(pat, since)
            if i is not None:
                return i
            time.sleep(0.4)
        return None

    def close(self):
        self.p.terminate()
        self.f.close()


class Servers:
    """현비서 서버(시험 데이터) + 받아 적기 서버를 띄우고 끈다"""
    def __init__(self, run_dir, keep):
        if http_ok(SERVER + "/hyun/health"):
            raise SystemExit("현비서 서버가 이미 떠 있다 — 끄고 다시(--servers는 시험 데이터로 새로 띄운다) 또는 --servers 없이")
        if not keep and os.path.isdir(DATA_E2E):
            shutil.rmtree(DATA_E2E)
        os.makedirs(DATA_E2E, exist_ok=True)
        env = {**os.environ, "HYUN_DATA": DATA_E2E}
        self.procs = [subprocess.Popen([sys.executable, "-I", "-X", "utf8", "-u", os.path.join(ROOT, "server", "hyun", "hyun_server.py")],
                                       env=env, stdout=open(os.path.join(run_dir, "server.out"), "w", encoding="utf-8"), stderr=subprocess.STDOUT)]
        if not http_ok("http://127.0.0.1:8791/stt/health"):
            self.procs.append(subprocess.Popen([STT_PY, "-I", "-X", "utf8", "-u", os.path.join(ROOT, "server", "hyun", "stt_server.py")],
                                               stdout=open(os.path.join(run_dir, "stt.out"), "w", encoding="utf-8"), stderr=subprocess.STDOUT))
        end = time.time() + 120
        while time.time() < end and not (http_ok(SERVER + "/hyun/health") and http_ok("http://127.0.0.1:8791/stt/health")):
            time.sleep(1)
        time.sleep(6)   # claude -p 예열

    def stop(self):
        for p in self.procs:
            p.terminate()


def data_dir():
    """서버가 쓰는 데이터 — --servers면 시험 데이터(HYUN_DATA), 아니면 떠 있는 서버의 기본(server/hyun/data)"""
    return os.environ.get("HYUN_DATA") or os.path.join(ROOT, "server", "hyun", "data")


def server_log_path():
    return os.path.join(data_dir(), "log.jsonl")


def server_lines(path, start):
    try:
        with open(path, "rb") as f:
            f.seek(start)
            return [json.loads(x) for x in f.read().decode("utf-8", "replace").splitlines() if x.strip()]
    except (OSError, ValueError):
        return []


def size(path):
    try:
        return os.path.getsize(path)
    except OSError:
        return 0


# ---------- 대화 기록: 로그 한 줄 → (누가, 무엇) ----------
def classify(msg):
    rules = [
        (r"^ear\|wake\+text\|([\d.]+)s\|(\d+)ms\|(.*)$", lambda m: ("귀", f"받아 적음 「{m[3]}」 · {m[2]}ms")),
        (r"^heard\|(.*)$", lambda m: ("운전자", m[1])),
        (r"^say\|P(\d)\|(.*)$", lambda m: ("현비서", m[2], int(m[1]))),
        (r"^card\|(.*)$", lambda m: ("카드", m[1])),
        (r"^event\|(.*)$", lambda m: ("이벤트", m[1])),
        (r"^(nav|via|route)\|(.*)$", lambda m: ("길", f"{m[1]} {m[2]}")),
        (r"^navi\|(routeStarted|waypointChanged|cancelled|waypointArrived)\|?(.*)$", lambda m: ("내비", f"{m[1]} {m[2][:120]}")),
        (r"^scout\|(demo|navi)\|(.*sev=[23].*)$", lambda m: ("정찰", m[2])),
        (r"^gleo\|(.*)$", lambda m: ("글레오", m[1])),
        (r"^(reask.*)$", lambda m: ("앱", m[1])),
        (r"^rest\|(.*)$", lambda m: ("휴게소", m[1])),
        (r"^reply\|\d+\|(\{.*)$", lambda m: ("두뇌", _reply_brief(m[1]))),
    ]
    for pat, f in rules:
        m = re.match(pat, msg)
        if m:
            return f(m)
    return None


def _reply_brief(js):
    try:
        j = json.loads(js)
        return f"답 도착 · {j.get('by', '?')}" + (f" · 카드 「{(j.get('card') or {}).get('title')}」" if j.get("card") else "")
    except ValueError:
        return "답 도착"


# ---------- 실행 ----------
def run_case(case, log, n_case, run_dir, tts, prefix, video, state):
    cid = case["id"]
    shots, steps_done = [], []
    srv_path = server_log_path()
    srv_start = size(srv_path)
    rec = None
    if video:
        sh(f"rm -f /sdcard/hyun_{cid}.mp4")
        rec = subprocess.Popen([ADB, "-s", SERIAL, "shell", "screenrecord", "--size", "1280x720", "--bit-rate", "2500000",
                                "--time-limit", "180", f"/sdcard/hyun_{cid}.mp4"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        time.sleep(1.2)
    t_start = time.time()
    start = log.poll()
    last = start
    last_say = False
    for k, st in enumerate(case["steps"]):
        mark = log.poll()
        note = ""
        if st.get("do") == "restart":
            sh(f"am force-stop {PKG}")
            time.sleep(1)
            am("--ez cancel true")
            log.wait(r"^start\|car", mark, 20)
        elif st.get("do") == "say":
            text = st["text"]
            if st.get("voice"):
                name = f"{cid}_{k}"
                path, sec = tts.make(name, f"{prefix} {text}", os.path.join(data_dir(), "testaudio"))
                am(f"--es earfile {name}")
                note = f"음성 {sec:.1f}초 「{prefix} {text}」"
            else:
                am("--es say '" + text.replace("'", "") + "'")
                note = f"글(마이크 없이) 「{text}」"
        elif st.get("do") == "intent":
            am(st["extras"])
            note = st["extras"]
        elif st.get("do") == "gleo":
            sh(f"am start --user 10 -a {st['action']} --es ACTION_PARAM '" + json.dumps(st.get("param") or {}, ensure_ascii=False) + "'")
            note = f"{st['action']} {st.get('param')}"
        elif st.get("do") == "vc":
            subprocess.run([sys.executable, "-I", os.path.join(ROOT, "tools", "vconsole.py"), *st["args"]], capture_output=True, timeout=30)
            note = " ".join(st["args"])
        elif st.get("do") == "split":
            if st["on"] != is_split():
                sh("input swipe %d %d %d %d 600" % (SPLIT_ON if st["on"] else SPLIT_OFF))
                time.sleep(2)
            note = "2칸(내비와 나란히)" if st["on"] else "1칸"
        elif st.get("wait") == "reply":
            # 방금 보낸 말(ask|id|talk)의 답을 기다린다 — 그 사이 끼어든 이벤트 답(정찰·출발)을 답으로 착각했다(11-07 e03)
            a_i = log.wait(r"^ask\|\d+\|talk\|", last, 12) if last_say else None
            rid = re.match(r"ask\|(\d+)\|", log.lines[a_i][1]).group(1) if a_i is not None else None
            i = log.wait(rf"^reply\|{rid}\|" if rid else r"^reply\|", a_i if a_i is not None else last, st.get("timeout", 45))
            if i is not None:
                s = log.wait(r"^say\|P", i, 8)
                if s is not None:
                    words = len(log.lines[s][1])
                    log.wait(r"^tts\|done", s, 4 + words / 5)
                note = "답 도착" if i is not None else "답 없음(시간 초과)"
        elif st.get("wait") == "log":
            i = log.wait(st["pattern"], start, st.get("timeout", 30))
            note = "확인" if i is not None else "시간 초과"
        elif st.get("wait") == "tts":
            s = log.find(r"^say\|P", last) or log.wait(r"^say\|P", last, 10)
            if s is not None:
                log.wait(r"^tts\|done", s, 4 + len(log.lines[s][1]) / 5)
        elif st.get("wait") == "sec":
            time.sleep(st["sec"])
        elif "shot" in st:
            time.sleep(0.5)
            fn = f"{cid}_{len(shots) + 1}"
            png = os.path.join(run_dir, "raw", fn + ".png")
            with open(png, "wb") as f:
                subprocess.run([ADB, "-s", SERIAL, "exec-out", "screencap", "-p"], stdout=f, timeout=20)
            jpg = os.path.join(run_dir, "shots", fn + ".jpg")
            if FFMPEG:
                subprocess.run([FFMPEG, "-y", "-loglevel", "error", "-i", png, "-vf", "scale=1280:-1", "-q:v", "4", jpg])
            shots.append({"file": f"shots/{fn}.jpg", "caption": st["shot"], "t": time.time()})
            note = st["shot"]
        log.poll()
        steps_done.append({"step": st, "note": note, "t": round(time.time() - t_start, 1)})
        if st.get("do"):   # 다음 기다림은 이 동작 뒤부터 본다
            last = mark
            last_say = st.get("do") == "say"
    time.sleep(1.5)
    end = log.poll()
    t_end = time.time()
    vid = None
    if rec:
        sh("pkill -INT screenrecord")
        try:
            rec.wait(timeout=15)
        except subprocess.TimeoutExpired:
            rec.kill()
        time.sleep(1.5)
        raw = os.path.join(run_dir, "raw", f"{cid}.mp4")
        adb("pull", f"/sdcard/hyun_{cid}.mp4", raw, timeout=120)
        dur = probe_duration(raw)
        if dur:
            vid = {"file": f"raw/{cid}.mp4", "dur": dur, "t0": t_end - dur}   # 끝 시각에서 길이를 빼 시작을 맞춘다
    # 대화 기록 · 판정
    lines = log.lines[start:end]
    talk = []
    for t, msg in lines:
        c = classify(msg)
        if c:
            talk.append({"t": round(t, 2), "who": c[0], "text": c[1], **({"prio": c[2]} if len(c) > 2 else {})})
    srv = server_lines(srv_path, srv_start)
    srv_text = "\n".join(json.dumps(x, ensure_ascii=False) for x in srv)
    checks = []
    for e in case.get("expect", []):
        if "log" in e:
            ok = any(re.search(e["log"], m) for _, m in lines)
        elif "server" in e:
            ok = bool(re.search(e["server"], srv_text))
        else:
            ok = False
        checks.append({"what": e.get("what", ""), "rule": e.get("log") or e.get("server"), "src": "log" if "log" in e else "server", "ok": ok})
    env = [f"에뮬 시스템 재시작(워치독) {h} — 앱 탓 아님(GNSS HAL 멈춤), 앱·내비 경로가 다시 시작됨" for h in watchdogs(t_start - 5, t_end)]
    heard = [x for x in talk if x["who"] == "운전자"]
    replies = [x for x in srv if x.get("dir") == "out"]
    lat = []
    for h in heard:   # 운전자 말 → 다음 현비서 말(맞장구 제외)까지
        nxt = next((x for x in talk if x["who"] == "현비서" and x["t"] > h["t"] and not re.match(r"^(음, 잠깐만요|생각해 볼게요|어디 보자|기사 좀 더)", x["text"])), None)
        if nxt:
            lat.append(round(nxt["t"] - h["t"], 1))
    return {"id": cid, "title": case["title"], "why": case.get("why", ""), "steps": steps_done, "talk": talk, "checks": checks,
            "pass": all(c["ok"] for c in checks), "env": env, "shots": shots, "video": vid, "t_start": t_start, "t_end": t_end,
            "secs": round(t_end - t_start, 1), "latency": lat, "engines": sorted({r.get("by", "") for r in replies if r.get("by")}),
            "server": srv}


def probe_duration(path):
    if not (FFPROBE and os.path.exists(path)):
        return None
    r = subprocess.run([FFPROBE, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path], capture_output=True, text=True)
    try:
        return float(r.stdout.strip())
    except ValueError:
        return None


# ---------- 영상: 이어 붙이고 실제 대화를 자막으로 ----------
def srt_time(s):
    s = max(0.0, s)
    return f"{int(s // 3600):02d}:{int(s % 3600 // 60):02d}:{int(s % 60):02d},{int(s * 1000 % 1000):03d}"


def build_video(run_dir, results):
    vids = [r for r in results if r.get("video")]
    if not (FFMPEG and vids):
        return None
    with open(os.path.join(run_dir, "raw", "list.txt"), "w", encoding="utf-8") as f:
        for r in vids:
            f.write(f"file '{os.path.basename(r['video']['file'])}'\n")
    subprocess.run([FFMPEG, "-y", "-loglevel", "error", "-f", "concat", "-safe", "0", "-i", "list.txt", "-c", "copy", "all.mp4"],
                   cwd=os.path.join(run_dir, "raw"))
    subs, off = [], 0.0
    for r in vids:
        v = r["video"]
        r["video"]["offset"] = round(off, 2)
        subs.append((off + 0.2, off + 3.2, f"[{r['id']}] {r['title']}"))
        items = [x for x in r["talk"] if x["who"] in ("운전자", "현비서", "카드", "이벤트", "글레오")]
        for i, x in enumerate(items):
            a = off + (x["t"] - v["t0"])
            b = off + (items[i + 1]["t"] - v["t0"]) if i + 1 < len(items) else a + 6
            label = {"운전자": "운전자", "현비서": "현비서", "카드": "화면 카드", "이벤트": "주행 이벤트", "글레오": "글레오"}[x["who"]]
            txt = x["text"] if len(x["text"]) <= 90 else x["text"][:88] + "…"
            subs.append((a, min(b, a + 9), f"{label}: {txt}"))
        off += v["dur"]
    with open(os.path.join(run_dir, "raw", "subs.srt"), "w", encoding="utf-8") as f:
        for n, (a, b, t) in enumerate(sorted(subs), 1):
            f.write(f"{n}\n{srt_time(a)} --> {srt_time(max(b, a + 1.2))}\n{t}\n\n")
    style = "FontName=Malgun Gothic,FontSize=15,Outline=1,Shadow=0,MarginV=14,BorderStyle=3,BackColour=&H80000000"
    subprocess.run([FFMPEG, "-y", "-loglevel", "error", "-i", "all.mp4", "-vf", f"subtitles=subs.srt:force_style='{style}'",
                    "-c:v", "libx264", "-crf", "27", "-preset", "veryfast", "-pix_fmt", "yuv420p", "../video.mp4"], cwd=os.path.join(run_dir, "raw"))
    out = os.path.join(run_dir, "video.mp4")
    return "video.mp4" if os.path.exists(out) else None


# ---------- 보고서 ----------
def report(run_dir):
    with open(os.path.join(run_dir, "transcript.json"), encoding="utf-8") as f:
        T = json.load(f)
    rs = T["cases"]
    npass = sum(r["pass"] for r in rs)
    esc = html.escape

    def talk_rows(r):
        v = r.get("video") or {}
        out = []
        for x in r["talk"]:
            at = (v.get("offset", 0) + x["t"] - v["t0"]) if v.get("t0") else None
            stamp = f'<a href="#" data-t="{at:.1f}" class="ts">{int(at // 60)}:{int(at % 60):02d}</a>' if at is not None and T.get("video") else \
                dt.datetime.fromtimestamp(x["t"]).strftime("%H:%M:%S")
            who = x["who"] + (f" P{x['prio']}" if "prio" in x else "")
            out.append(f'<tr class="w-{esc(x["who"])}"><td class="t">{stamp}</td><td class="who">{esc(who)}</td><td>{esc(x["text"])}</td></tr>')
        return "\n".join(out)

    cards = []
    for r in rs:
        checks = "".join(f'<li class="{"ok" if c["ok"] else "bad"}">{"✓" if c["ok"] else "✗"} {esc(c["what"])} <code>{esc(c["src"])}: {esc(c["rule"])}</code></li>' for c in r["checks"])
        shots = "".join(f'<figure><a href="{esc(s["file"])}" target="_blank"><img src="{esc(s["file"])}" loading="lazy" alt="{esc(s["caption"])}"></a><figcaption>{esc(s["caption"])}</figcaption></figure>' for s in r["shots"])
        steps = "".join(f'<li><span class="t">+{s["t"]}s</span> {esc(json.dumps(s["step"], ensure_ascii=False))}{" — " + esc(s["note"]) if s["note"] else ""}</li>' for s in r["steps"])
        jump = f'<a href="#" class="ts chip" data-t="{r["video"]["offset"]:.1f}">영상 {int(r["video"]["offset"] // 60)}:{int(r["video"]["offset"] % 60):02d}</a>' if T.get("video") and r.get("video") and "offset" in r["video"] else ""
        cards.append(f'''<section class="case" id="{r["id"]}"><h2><span class="badge {"ok" if r["pass"] else "bad"}">{"통과" if r["pass"] else "실패"}</span> {esc(r["id"])} · {esc(r["title"])} {jump}</h2>
<p class="why">{esc(r["why"])}</p>{"".join(f'<p class="bad">⚠ {esc(e)}</p>' for e in r.get("env") or [])}<p class="meta">{r["secs"]}초 · 응답 {", ".join(f"{x}s" for x in r["latency"]) or "—"} · 두뇌 {esc(", ".join(r["engines"]) or "—")}</p>
<div class="grid"><div><h3>판정</h3><ul class="checks">{checks}</ul><h3>실제 대화·동작</h3><table class="talk">{talk_rows(r)}</table></div>
<div><h3>화면</h3><div class="shots">{shots or "<p class=meta>없음</p>"}</div><details><summary>단계</summary><ol class="steps">{steps}</ol></details></div></div></section>''')
    summary = "".join(f'<tr><td><a href="#{r["id"]}">{esc(r["id"])}</a></td><td>{esc(r["title"])}</td><td class="{"ok" if r["pass"] else "bad"}">{"통과" if r["pass"] else "실패"} {sum(c["ok"] for c in r["checks"])}/{len(r["checks"])}</td><td>{r["secs"]}초</td><td>{", ".join(f"{x}s" for x in r["latency"]) or "—"}</td></tr>' for r in rs)
    video = f'<video id="v" src="{esc(T["video"])}" controls preload="metadata"></video><p class="meta">자막 = 앱 로그에서 뽑은 실제 대화(운전자 · 현비서 · 화면 카드 · 주행 이벤트) · 소리 없음(에뮬 화면 녹화)</p>' if T.get("video") else '<p class="meta">영상 없음(--video 없이 실행)</p>'
    page = f'''<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>현비서 E2E 기록 {esc(T["stamp"])}</title><style>
:root{{--bg:#f5f7f9;--card:#fff;--fg:#14202b;--mut:#62707d;--ln:#dde3e8;--ok:#13824f;--bad:#c2331f;--ac:#0b7c8f;--drv:#eaf3ff;--hyun:#e9f8f5}}
@media (prefers-color-scheme:dark){{:root:not([data-theme=light]){{--bg:#0c1116;--card:#141b22;--fg:#e6edf3;--mut:#8b98a5;--ln:#26313b;--ok:#3fcf8e;--bad:#ff6b5a;--ac:#4fd1c5;--drv:#15233a;--hyun:#12302b}}}}
body{{margin:0;background:var(--bg);color:var(--fg);font:15px/1.55 "Malgun Gothic",system-ui,sans-serif}}main{{max-width:1180px;margin:0 auto;padding:20px 16px 60px}}
h1{{font-size:24px;margin:4px 0}}h2{{font-size:18px;margin:0 0 6px}}h3{{font-size:14px;color:var(--mut);margin:14px 0 6px}}
.meta,.why{{color:var(--mut);margin:4px 0}}.case,.top{{background:var(--card);border:1px solid var(--ln);border-radius:14px;padding:16px;margin:16px 0}}
video{{width:100%;border-radius:10px;background:#000}}table{{border-collapse:collapse;width:100%}}td,th{{padding:6px 8px;border-bottom:1px solid var(--ln);vertical-align:top;text-align:left}}
.ok{{color:var(--ok);font-weight:700}}.bad{{color:var(--bad);font-weight:700}}.badge{{font-size:12px;padding:2px 8px;border-radius:99px;border:1px solid currentColor}}
.grid{{display:grid;grid-template-columns:1.25fr 1fr;gap:16px}}@media (max-width:820px){{.grid{{grid-template-columns:1fr}}}}
.talk td.t{{white-space:nowrap;color:var(--mut);width:56px}}.talk td.who{{white-space:nowrap;font-weight:700;width:78px}}.w-운전자{{background:var(--drv)}}.w-현비서{{background:var(--hyun)}}
.shots{{display:grid;grid-template-columns:1fr;gap:10px}}figure{{margin:0}}figure img{{width:100%;border-radius:8px;border:1px solid var(--ln)}}figcaption{{font-size:13px;color:var(--mut)}}
.checks{{padding-left:4px;list-style:none}}.checks li{{margin:3px 0}}code{{font:12px Consolas,"Cascadia Mono",monospace;color:var(--mut);word-break:break-all}}.chip{{font-size:12px;border:1px solid var(--ac);color:var(--ac);border-radius:99px;padding:1px 8px;text-decoration:none;margin-left:6px}}
a{{color:var(--ac)}}.steps{{font-size:13px;color:var(--mut)}}.steps .t{{color:var(--ac)}}
</style></head><body><main>
<div class="top"><h1>현비서 E2E 기록 · {esc(T["stamp"])}</h1><p class="meta">시나리오 {len(rs)}개 · 통과 {npass} · 실패 {len(rs) - npass} · 두뇌 = 내 Claude 계정(실제 답, 매번 다름) · 데이터 = {esc(T.get("data", "").replace(chr(92), "/"))} · 시나리오 정본 tools/hyun_e2e/cases.json</p>
{video}<table><tr><th>케이스</th><th>내용</th><th>판정</th><th>걸린 시간</th><th>응답(말→답)</th></tr>{summary}</table></div>
{"".join(cards)}
<p class="meta">파일: transcript.json(전체 기록) · logcat.txt(앱 로그) · server-log.jsonl(서버 대화 기록) · shots/ · video.mp4 · raw/(케이스별 원본 영상)</p>
</main><script>
document.querySelectorAll('a.ts').forEach(a=>a.addEventListener('click',e=>{{e.preventDefault();const v=document.getElementById('v');if(!v)return;v.currentTime=parseFloat(a.dataset.t);v.play();window.scrollTo({{top:0,behavior:'smooth'}})}}));
</script></body></html>'''
    with open(os.path.join(run_dir, "report.html"), "w", encoding="utf-8") as f:
        f.write(page)
    return os.path.join(run_dir, "report.html")


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("--ids", default="")
    ap.add_argument("--servers", action="store_true")
    ap.add_argument("--keep-data", action="store_true")
    ap.add_argument("--video", action="store_true")
    ap.add_argument("--report", default="")
    a = ap.parse_args()
    if a.report:
        print(report(a.report))
        return
    with open(CASES, encoding="utf-8") as f:
        spec = json.load(f)
    cases = [c for c in spec["cases"] if not a.ids or c["id"] in a.ids.split(",")]
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M")
    run_dir = os.path.join(RECORDS, stamp)
    for d in ("raw", "shots"):
        os.makedirs(os.path.join(run_dir, d), exist_ok=True)
    srv = Servers(run_dir, a.keep_data) if a.servers else None
    if srv:
        os.environ["HYUN_DATA"] = DATA_E2E
    log = Logcat(os.path.join(run_dir, "logcat.txt"))
    # 시험 중엔 에뮬 마이크(PC 마이크 연결)를 끈다 — 방 소리·스피커 소리가 대화 창에 들어가 엉뚱한 질문이 됐다(11-07 실측 "비서").
    # 시험 목소리는 귀에 바로 넣으므로(--es earfile) 마이크가 필요 없다. 끝나면 다시 켠다
    adb("emu", "avd", "hostmicoff")
    tts = _tts()
    state = {"split": False}
    results = []
    try:
        if not any(st.get("do") == "restart" for c in cases for st in c["steps"]):
            am("")   # 앱을 앞으로
            time.sleep(3)
        for n, c in enumerate(cases):
            print(f"▶ {c['id']} {c['title']}", flush=True)
            r = run_case(c, log, n, run_dir, tts, spec.get("voice_prefix", "현비서야"), a.video, state)
            results.append(r)
            print(f"  {'통과' if r['pass'] else '실패'} · {r['secs']}초 · " + " / ".join(("✓ " if x["ok"] else "✗ ") + x["what"] for x in r["checks"])
                  + "".join(f" · ⚠ {e}" for e in r.get("env") or []), flush=True)
    finally:
        log.close()
        adb("emu", "avd", "hostmicon")
        if srv:
            srv.stop()
    srv_all = server_log_path()
    if os.path.exists(srv_all):
        shutil.copy(srv_all, os.path.join(run_dir, "server-log.jsonl"))
    video = build_video(run_dir, results) if a.video else None
    T = {"stamp": stamp, "cases": results, "video": video, "data": os.path.relpath(data_dir(), ROOT),
         "spec": os.path.relpath(CASES, ROOT)}
    with open(os.path.join(run_dir, "transcript.json"), "w", encoding="utf-8") as f:
        json.dump(T, f, ensure_ascii=False, indent=1)
    print(report(run_dir))
    print(f"통과 {sum(r['pass'] for r in results)}/{len(results)}")


if __name__ == "__main__":
    main()
