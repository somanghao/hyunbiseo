"""현비서 E2E 시험 — 시나리오(cases.json)를 에뮬 앱에 순서대로 걸고, 화면 녹화 · 스크린샷 · 실제 대화(들은 말 · 현비서 말 · 카드 · 이벤트)를 기록하고 판정한다.

    python -I -X utf8 tools/hyun_e2e/run.py --servers --video          # 전체(서버를 시험 데이터로 새로 띄우고, 녹화)
    python -I -X utf8 tools/hyun_e2e/run.py --servers --ids e02,e03    # 일부만
    python -I -X utf8 tools/hyun_e2e/run.py --servers --video --voice  # 시연 영상(제출·PT) — 모든 말을 시험 목소리로
    python -I -X utf8 tools/hyun_e2e/run.py --report records/hyun-e2e/<폴더>   # 보고서만 다시
    python -I -X utf8 tools/hyun_e2e/run.py --rebuild records/hyun-e2e/<폴더>  # 영상만 다시(슬라이드 글 고친 뒤 — raw/가 있어야)

  --servers   현비서 서버(HYUN_DATA=server/hyun/data-e2e, 매번 새로 — 사용자 실제 취향·대화를 건드리지 않는다)와 받아 적기 서버를
              이 스크립트가 띄우고 끝나면 끈다. 안 주면 이미 떠 있는 서버를 쓴다(그 서버의 데이터에 기록됨)
  --keep-data 시험 데이터를 지우지 않고 이어서
  --video     시연 영상 형식(사용자 11-08): 맨 앞 표지 → 케이스마다 흰 설명 슬라이드(케이스 N · 상황 · 내가 물을 말 — cases.json의
              situation · ask · 말 단계 text/slide) → 그 케이스의 실제 화면(adb screenrecord 1280x720 + 실제 대화 자막 + 소리) → video.mp4
              소리 = 에뮬 프로세스 소리만(ProcAudio.cs — 현비서 목소리 · 내비 안내, 다른 앱 소리는 안 섞임) + 운전자 시험 목소리(귀에 넣은 그 소리)
  --voice     글로 넣던 말(voice: false)도 시험 목소리로 — 영상에서 운전자 말이 들리고, 앱도 귀 → 받아 적기 → 호출어로 실제로 듣는다

전제: 에뮬(Pleos_Connect_v2.1) 켜짐 · 현비서 앱 설치 · adb forward tcp:8088(차 상태 vconsole) · 시험 목소리 = Windows SAPI 한국어(tools/hyun_testaudio.py)
      · 영상 = ffmpeg · 슬라이드·소리 섞기 = 사용자 site-packages의 Pillow·numpy · 소리 녹음기 = .NET Framework csc로 처음에 빌드(.bin/, git 제외)
결과: records/hyun-e2e/<YYYYMMDD-HHMM>/ — report.html · transcript.json(케이스별 대화·판정·스크린샷·영상 위치) · logcat.txt · server-log.jsonl ·
      shots/*.jpg · slides/*.png · video.mp4(커밋 — 수 MB) · raw/(케이스별 원본 영상·소리, git 제외)
시각: logcat은 에뮬 시계, 영상·소리는 PC 시계 — 시작 때 차이를 재서(clock = 에뮬 − PC, 11-08 실측 −1.1초) 자막·운전자 목소리 자리를 맞춘다
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
import site
import subprocess
import sys
import time
import urllib.request
import wave

if site.getusersitepackages() not in sys.path:   # -I는 사용자 site-packages를 뺀다(gotchas #7) — 슬라이드(Pillow)·소리 섞기(numpy)
    sys.path.append(site.getusersitepackages())

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
PROC_AUDIO_SRC = os.path.join(HERE, "ProcAudio.cs")
PROC_AUDIO = os.path.join(HERE, ".bin", "ProcAudio.exe")
CSC = os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Microsoft.NET", "Framework64", "v4.0.30319", "csc.exe")
FONTS = os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Fonts")
RATE = 48000
INTRO_SEC = 8
FOOTER = "Pleos Connect Emulator v2.1 실제 화면 · 두뇌 = 실제 Claude 답(매번 다름) · 운전자 목소리 = 시험용 합성 음성"


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


def emu_offset():
    """에뮬 시계 − PC 시계(초). logcat 시각은 에뮬 시계라 영상·소리(PC 시계)에 맞출 때 뺀다 — 11-08 실측 −1.1초(그전 영상 자막이 그만큼 일찍 떴다)"""
    xs = []
    for _ in range(5):
        a = time.time()
        out = sh("date +%s.%N", timeout=10).stdout.strip()
        b = time.time()
        try:
            xs.append((b - a, float(out) - (a + b) / 2))
        except ValueError:
            pass
    return round(min(xs)[1], 3) if xs else 0.0   # 왕복이 가장 짧은 것


class AppAudio:
    """에뮬(qemu) 프로세스 소리만 녹음한다 — 현비서 목소리(Pleos TTS) · 내비 안내. PC 전체를 녹음하면 다른 앱 소리가 섞인다(11-08 실측) → ProcAudio.cs"""
    def __init__(self, path):
        self.path, self.t0, self.p = path, None, None
        try:
            if not os.path.exists(PROC_AUDIO) or os.path.getmtime(PROC_AUDIO) < os.path.getmtime(PROC_AUDIO_SRC):
                os.makedirs(os.path.dirname(PROC_AUDIO), exist_ok=True)
                subprocess.run([CSC, "/nologo", "/optimize", "/platform:x64", "/out:" + PROC_AUDIO, PROC_AUDIO_SRC], capture_output=True, timeout=180)
            out = subprocess.run(["tasklist", "/FI", "IMAGENAME eq qemu-system-x86_64.exe", "/FO", "CSV", "/NH"], capture_output=True, text=True).stdout
            m = re.search(r'"qemu-system-x86_64\.exe","(\d+)"', out)
            if not (m and os.path.exists(PROC_AUDIO)):
                print("  ⚠ 소리 녹음 못 함(에뮬 프로세스 또는 녹음기 없음) — 영상은 소리 없이", flush=True)
                return
            self.p = subprocess.Popen([PROC_AUDIO, m.group(1), path, "7200"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            first = self.p.stdout.readline().split()
            if len(first) == 2 and first[0] == "START":
                self.t0 = float(first[1])
            else:
                _, err = self.p.communicate(timeout=10)
                print(f"  ⚠ 소리 녹음 시작 실패 {first} {err[:200]}", flush=True)
                self.p = None
        except (OSError, subprocess.SubprocessError) as e:
            print(f"  ⚠ 소리 녹음 못 함 {e}", flush=True)
            self.p = None

    def stop(self):
        """→ {"file": raw/app-audio.wav 기준 이름, "t0": 0번 샘플의 PC 시각} 또는 None"""
        if not self.p:
            return None
        try:
            self.p.stdin.write("q\n")
            self.p.stdin.flush()
            self.p.communicate(timeout=60)
        except (OSError, subprocess.SubprocessError):
            self.p.kill()
            return None
        return {"file": os.path.basename(self.path), "t0": self.t0} if os.path.exists(self.path) else None


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
def run_case(case, log, n_case, run_dir, tts, prefix, video, state, force_voice=False):
    cid = case["id"]
    shots, steps_done, voices = [], [], []
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
            if st.get("voice") or force_voice:
                name = f"{cid}_{k}"
                path, sec = tts.make(name, f"{prefix} {text}", os.path.join(data_dir(), "testaudio"))
                if video:   # 영상에 넣을 운전자 목소리 — 귀에 넣은 그 소리(16kHz mono PCM). 자리는 앱 로그 ear|feed|<이름> 시각
                    os.makedirs(os.path.join(run_dir, "raw", "voice"), exist_ok=True)
                    shutil.copy(path, os.path.join(run_dir, "raw", "voice", name + ".pcm"))
                    voices.append({"name": name, "pcm": f"raw/voice/{name}.pcm"})
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
            # 기본은 케이스 시작부터 찾는다 · "from": "last"면 직전 단계 뒤부터(같은 줄이 케이스 안에 두 번 — 두 번째 경로 시작 등, 11-08 e18)
            i = log.wait(st["pattern"], last if st.get("from") == "last" else start, st.get("timeout", 30))
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
        a0 = time.time()
        sh("pkill -INT screenrecord")
        t_stop = (a0 + time.time()) / 2   # 녹화가 멈춘 PC 시각(adb 왕복의 가운데)
        try:
            rec.wait(timeout=15)
        except subprocess.TimeoutExpired:
            rec.kill()
        time.sleep(1.5)
        raw = os.path.join(run_dir, "raw", f"{cid}.mp4")
        adb("pull", f"/sdcard/hyun_{cid}.mp4", raw, timeout=120)
        dur = probe_duration(raw)
        if dur:
            vid = {"file": f"raw/{cid}.mp4", "dur": dur, "t0": t_stop - dur}   # 멈춘 시각에서 길이를 빼 시작을 맞춘다(PC 시계)
    # 대화 기록 · 판정
    lines = log.lines[start:end]
    for vc in voices:   # 운전자 목소리 자리 = 앱이 귀에 넣기 시작한 시각(에뮬 시계)
        vc["t"] = next((t for t, m in lines if m.startswith(f"ear|feed|{vc['name']}|")), None)
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
            "server": srv, "voices": voices, "voiced": force_voice}


def probe_duration(path):
    if not (FFPROBE and os.path.exists(path)):
        return None
    r = subprocess.run([FFPROBE, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path], capture_output=True, text=True)
    try:
        return float(r.stdout.strip())
    except ValueError:
        return None


# ---------- 영상: 표지 → (케이스 슬라이드 → 실제 화면 + 자막 + 소리) × N ----------
VENC = ["-c:v", "libx264", "-preset", "veryfast", "-crf", "26", "-pix_fmt", "yuv420p", "-r", "30", "-video_track_timescale", "90000",
        "-c:a", "aac", "-b:a", "128k", "-ar", str(RATE), "-ac", "2"]
SUB_STYLE = "FontName=Malgun Gothic,FontSize=15,Outline=1,Shadow=0,MarginV=14,BorderStyle=3,BackColour=&H80000000"
WHO = {"운전자": "운전자", "현비서": "현비서", "카드": "화면 카드", "이벤트": "주행 이벤트", "글레오": "글레오"}


def srt_time(s):
    s = max(0.0, s)
    return f"{int(s // 3600):02d}:{int(s % 3600 // 60):02d}:{int(s % 60):02d},{int(s * 1000 % 1000):03d}"


def slide_text(case, prefix, voiced):
    """슬라이드 글 — (상황, [(앞말, 물을 말)]). 정본 = cases.json: situation(없으면 why) · ask(말 없는 케이스의 회색 메모) · 말 단계 text(+ slide 앞말)"""
    asks = [("", a) for a in case.get("ask", [])]
    for st in case["steps"]:
        if st.get("do") == "say":
            asks.append((st.get("slide", ""), f"{prefix} {st['text']}" if (st.get("voice") or voiced) else st["text"]))
    return case.get("situation") or case.get("why", ""), asks


def _font(size, bold=False):
    from PIL import ImageFont
    return ImageFont.truetype(os.path.join(FONTS, "malgunbd.ttf" if bold else "malgun.ttf"), size)


def _wrap(d, text, font, maxw):
    """띄어쓰기 기준 줄바꿈(긴 낱말은 글자 단위)"""
    lines, cur = [], ""
    for w in text.split(" "):
        t = (cur + " " + w).strip()
        if d.textlength(t, font=font) <= maxw:
            cur = t
            continue
        if cur:
            lines.append(cur)
        cur = ""
        for ch in w:
            if d.textlength(cur + ch, font=font) > maxw:
                lines.append(cur)
                cur = ""
            cur += ch
    if cur:
        lines.append(cur)
    return lines


def make_slide(path, kicker, title, situation, asks):
    """흰 바탕 1280x720 — 케이스 N · 제목 / 상황 / 내가 물을 말(사용자 11-08 형식)"""
    from PIL import Image, ImageDraw
    W, X, R = 1280, 84, 1196
    im = Image.new("RGB", (W, 720), "white")
    d = ImageDraw.Draw(im)
    ink, mut, ac, bub = "#14202b", "#62707d", "#0b7c8f", "#eaf3ff"
    y = 58
    d.text((X, y), kicker, font=_font(22), fill=mut)
    y += 42
    f = _font(44, True)
    for ln in _wrap(d, title, f, R - X):
        d.text((X, y), ln, font=f, fill=ink)
        y += 58
    d.rectangle((X, y + 6, X + 110, y + 12), fill=ac)
    y += 36
    d.text((X, y), "상황", font=_font(24, True), fill=ac)
    y += 38
    f = _font(29)
    for ln in _wrap(d, situation, f, R - X):
        d.text((X, y), ln, font=f, fill=ink)
        y += 42
    y += 22
    d.text((X, y), "내가 물을 말", font=_font(24, True), fill=ac)
    y += 42
    fq, fl = _font(31, True), _font(22)
    for lead, q in asks:
        if q.startswith("("):   # 말 없는 케이스 — 회색 메모
            for ln in _wrap(d, q, fl, R - X):
                d.text((X, y), ln, font=fl, fill=mut)
                y += 32
            y += 6
            continue
        if lead:
            d.text((X, y), lead, font=fl, fill=mut)
            y += 32
        txt = f"“{q}”"
        w = min(d.textlength(txt, font=fq), R - X - 40)
        d.rounded_rectangle((X, y, X + w + 40, y + 54), 16, fill=bub)
        d.text((X + 20, y + 6), txt, font=fq, fill=ink)
        y += 68
    d.text((X, 670), FOOTER, font=_font(17), fill="#8a96a3")
    im.save(path)


def make_intro(path, items, stamp):
    from PIL import Image, ImageDraw
    im = Image.new("RGB", (1280, 720), "white")
    d = ImageDraw.Draw(im)
    X = 84
    d.text((X, 58), "현대자동차그룹 차량용 앱 개발 공모전 · 기능 시연", font=_font(22), fill="#62707d")
    d.text((X, 98), "현비서 — 조수석 대화 비서", font=_font(50, True), fill="#14202b")
    d.text((X, 168), "말 한마디로 길·교통·휴게소·소식·식당까지 — Pleos Connect에서 실제로 동작하는 화면", font=_font(24), fill="#14202b")
    d.rectangle((X, 214, X + 110, 220), fill="#0b7c8f")
    f, fb = _font(21), _font(21, True)
    lh = min(46, 390 // max(1, len(items)))
    for i, t in enumerate(items):   # 한 줄에 하나 — 길면 말줄임
        y = 240 + i * lh
        d.text((X, y), f"{i + 1}", font=fb, fill="#0b7c8f")
        while d.textlength(t, font=f) > 1060 and len(t) > 4:
            t = t[:-2] + "…"
        d.text((X + 40, y), t, font=f, fill="#14202b")
    d.text((X, 646), f"케이스마다 설명 → 실제 화면(자막 = 앱 로그의 실제 대화) · {stamp[:4]}-{stamp[4:6]}-{stamp[6:8]} 기록", font=_font(18), fill="#62707d")
    d.text((X, 676), FOOTER, font=_font(17), fill="#8a96a3")
    im.save(path)


def _ff(*args, cwd=None):
    r = subprocess.run([FFMPEG, "-y", "-loglevel", "error", *args], cwd=cwd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode:
        print("  ffmpeg 오류: " + r.stderr[-400:], flush=True)
    return r.returncode == 0


def clip_still(png, sec, out):
    return _ff("-loop", "1", "-framerate", "30", "-t", f"{sec}", "-i", png, "-f", "lavfi", "-t", f"{sec}", "-i", f"anullsrc=r={RATE}:cl=stereo",
               "-vf", "scale=1280:720,format=yuv420p", *VENC, "-shortest", out)


def app_gain(path):
    """에뮬 소리 전체에서 큰 소리 기준으로 키운다(Pleos TTS가 작게 잡힘 — 11-08 실측 RMS 0.01)"""
    import numpy as np
    peaks = []
    with wave.open(path) as w:
        while True:
            b = w.readframes(RATE * 5)
            if not b:
                break
            peaks.append(np.abs(np.frombuffer(b, "<i2")).max() / 32768)
    loud = sorted(p for p in peaks if p > 0.002)
    if not loud:
        return 1.0
    ref = loud[int(len(loud) * 0.9)]   # 큰 구간의 90% 지점 — 튀는 한 번에 끌려가지 않게
    return min(30.0, 0.8 / ref)


def case_audio(run_dir, r, audio, gain, clk, out):
    """케이스 영상 길이만큼: 에뮬 소리(같은 PC 시각 구간) + 운전자 시험 목소리(ear|feed 시각) → wav 48kHz 스테레오"""
    import numpy as np
    v = r["video"]
    n = int(v["dur"] * RATE)
    mix = np.zeros((n, 2), np.float32)
    if audio:
        with wave.open(os.path.join(run_dir, "raw", audio["file"])) as w:
            i0 = int(round((v["t0"] - audio["t0"]) * RATE))
            src = max(i0, 0)
            if src < w.getnframes():
                w.setpos(src)
                seg = np.frombuffer(w.readframes(n - (src - i0)), "<i2").reshape(-1, 2).astype(np.float32) / 32768 * gain
                mix[src - i0:src - i0 + len(seg)] += seg
    for vc in r.get("voices") or []:
        p = os.path.join(run_dir, vc["pcm"])
        if vc.get("t") is None or not os.path.exists(p):
            continue
        pcm = np.frombuffer(open(p, "rb").read(), "<i2").astype(np.float32) / 32768
        x = np.interp(np.arange(len(pcm) * 3) / 3, np.arange(len(pcm)), pcm)   # 16k → 48k
        x *= 0.7 / max(1e-4, float(np.abs(x).max()))
        at = int(round((vc["t"] - clk - v["t0"]) * RATE))
        a, b = max(at, 0), min(at + len(x), n)
        if a < b:
            mix[a:b] += x[a - at:b - at, None]
    with wave.open(out, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes((np.clip(mix, -1, 1) * 32767).astype("<i2").tobytes())


def case_srt(r, clk, out):
    v = r["video"]
    items = [x for x in r["talk"] if x["who"] in WHO]
    subs = []
    for i, x in enumerate(items):
        a = x["t"] - clk - v["t0"]
        b = items[i + 1]["t"] - clk - v["t0"] if i + 1 < len(items) else a + 6
        txt = x["text"] if len(x["text"]) <= 90 else x["text"][:88] + "…"
        subs.append((a, min(b, a + 9), f"{WHO[x['who']]}: {txt}"))
    with open(out, "w", encoding="utf-8") as f:
        for k, (a, b, t) in enumerate(sorted(subs), 1):
            f.write(f"{k}\n{srt_time(a)} --> {srt_time(max(b, a + 1.2))}\n{t}\n\n")


def build_video(run_dir, results, spec, stamp, audio=None, clk=0.0, voiced=False):
    """표지 → 케이스마다 슬라이드(상황 · 내가 물을 말) → 실제 화면(자막 · 소리). 조각을 같은 형식으로 인코딩해 이어 붙인다(-c copy)"""
    vids = [r for r in results if r.get("video")]
    if not (FFMPEG and vids):
        return None
    raw, sdir = os.path.join(run_dir, "raw"), os.path.join(run_dir, "slides")
    os.makedirs(sdir, exist_ok=True)
    by_id = {c["id"]: c for c in spec["cases"]}
    prefix = spec.get("voice_prefix", "현비서야")
    gain = app_gain(os.path.join(raw, audio["file"])) if audio else 1.0
    parts = []
    make_intro(os.path.join(sdir, "00-intro.png"), [r["title"] for r in vids], stamp)
    clip_still(os.path.join(sdir, "00-intro.png"), INTRO_SEC, os.path.join(raw, "p_intro.mp4"))
    parts.append(("p_intro.mp4", None, None))
    for n, r in enumerate(vids, 1):
        case = by_id.get(r["id"], {"steps": [], "why": r.get("why", "")})
        sit, asks = slide_text(case, prefix, voiced or r.get("voiced"))
        r["slide"] = {"n": n, "file": f"slides/{n:02d}-{r['id']}.png", "situation": sit, "asks": [list(a) for a in asks]}
        png = os.path.join(run_dir, r["slide"]["file"])
        make_slide(png, f"현비서 기능 시연 · {n} / {len(vids)}", f"케이스 {n}. {r['title']}", sit, asks)
        sec = min(9.0, 5.5 + 0.8 * len(asks))
        clip_still(png, sec, os.path.join(raw, f"p_s{r['id']}.mp4"))
        parts.append((f"p_s{r['id']}.mp4", r, "slide"))
        case_audio(run_dir, r, audio, gain, clk, os.path.join(raw, f"a_{r['id']}.wav"))
        case_srt(r, clk, os.path.join(raw, f"a_{r['id']}.srt"))
        _ff("-i", os.path.basename(r["video"]["file"]), "-i", f"a_{r['id']}.wav",
            "-vf", f"fps=30,scale=1280:720,format=yuv420p,subtitles=a_{r['id']}.srt:force_style='{SUB_STYLE}'",
            "-map", "0:v:0", "-map", "1:a:0", *VENC, "-t", f"{r['video']['dur']:.3f}", f"p_c{r['id']}.mp4", cwd=raw)
        parts.append((f"p_c{r['id']}.mp4", r, "case"))
    off = 0.0
    with open(os.path.join(raw, "list.txt"), "w", encoding="utf-8") as f:
        for fn, r, kind in parts:
            dur = probe_duration(os.path.join(raw, fn))
            if not dur:
                continue
            f.write(f"file '{fn}'\n")
            if kind == "slide":
                r["slide"]["offset"] = round(off, 2)
            elif kind == "case":
                r["video"]["offset"] = round(off, 2)
            off += dur
    _ff("-f", "concat", "-safe", "0", "-i", "list.txt", "-c", "copy", "-movflags", "+faststart", "../video.mp4", cwd=raw)
    out = os.path.join(run_dir, "video.mp4")
    return "video.mp4" if os.path.exists(out) else None


# ---------- 보고서 ----------
def report(run_dir):
    with open(os.path.join(run_dir, "transcript.json"), encoding="utf-8") as f:
        T = json.load(f)
    rs = T["cases"]
    npass = sum(r["pass"] for r in rs)
    esc = html.escape

    clk = T.get("clock", 0.0)   # 에뮬 시계 − PC 시계(옛 기록엔 없음 → 0)

    def talk_rows(r):
        v = r.get("video") or {}
        out = []
        for x in r["talk"]:
            at = (v.get("offset", 0) + x["t"] - clk - v["t0"]) if v.get("t0") else None
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
        jo = (r.get("slide") or {}).get("offset", (r.get("video") or {}).get("offset"))
        jump = f'<a href="#" class="ts chip" data-t="{jo:.1f}">영상 {int(jo // 60)}:{int(jo % 60):02d}</a>' if T.get("video") and jo is not None else ""
        sl = r.get("slide")
        slide = (f'<div class="slide"><span class="n">케이스 {sl["n"]}</span> <b>상황</b> {esc(sl["situation"])}<br><b>물을 말</b> '
                 + " · ".join((f'<span class="lead">{esc(a)}</span> ' if a else "") + (esc(q) if q.startswith("(") else f"“{esc(q)}”") for a, q in sl["asks"])
                 + f' <a href="{esc(sl["file"])}" target="_blank">슬라이드</a></div>') if sl else ""
        cards.append(f'''<section class="case" id="{r["id"]}"><h2><span class="badge {"ok" if r["pass"] else "bad"}">{"통과" if r["pass"] else "실패"}</span> {esc(r["id"])} · {esc(r["title"])} {jump}</h2>
{slide}<p class="why">{esc(r["why"])}</p>{"".join(f'<p class="bad">⚠ {esc(e)}</p>' for e in r.get("env") or [])}<p class="meta">{r["secs"]}초 · 응답 {", ".join(f"{x}s" for x in r["latency"]) or "—"} · 두뇌 {esc(", ".join(r["engines"]) or "—")}</p>
<div class="grid"><div><h3>판정</h3><ul class="checks">{checks}</ul><h3>실제 대화·동작</h3><table class="talk">{talk_rows(r)}</table></div>
<div><h3>화면</h3><div class="shots">{shots or "<p class=meta>없음</p>"}</div><details><summary>단계</summary><ol class="steps">{steps}</ol></details></div></div></section>''')
    summary = "".join(f'<tr><td><a href="#{r["id"]}">{esc(r["id"])}</a></td><td>{esc(r["title"])}</td><td class="{"ok" if r["pass"] else "bad"}">{"통과" if r["pass"] else "실패"} {sum(c["ok"] for c in r["checks"])}/{len(r["checks"])}</td><td>{r["secs"]}초</td><td>{", ".join(f"{x}s" for x in r["latency"]) or "—"}</td></tr>' for r in rs)
    sound = ("소리 = 에뮬 소리(현비서 목소리 · 내비 안내 — 에뮬 프로세스만) + 운전자 시험 목소리(Windows 한국어 합성 — 앱 귀에 넣은 그 소리)"
             if T.get("audio") else "소리 없음(에뮬 화면 녹화)")
    form = " · 표지 → 케이스마다 설명 슬라이드(상황 · 물을 말) → 실제 화면" if any(r.get("slide") for r in rs) else ""
    video = f'<video id="v" src="{esc(T["video"])}" controls preload="metadata"></video><p class="meta">자막 = 앱 로그에서 뽑은 실제 대화(운전자 · 현비서 · 화면 카드 · 주행 이벤트) · {sound}{form}</p>' if T.get("video") else '<p class="meta">영상 없음(--video 없이 실행)</p>'
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
.slide{{border-left:4px solid var(--ac);padding:6px 10px;margin:6px 0;background:var(--hyun);border-radius:6px}}.slide .n{{font-size:12px;color:var(--ac);font-weight:700}}.slide .lead{{color:var(--mut);font-size:13px}}
</style></head><body><main>
<div class="top"><h1>현비서 E2E 기록 · {esc(T["stamp"])}</h1><p class="meta">시나리오 {len(rs)}개 · 통과 {npass} · 실패 {len(rs) - npass} · 두뇌 = 내 Claude 계정(실제 답, 매번 다름) · 데이터 = {esc(T.get("data", "").replace(chr(92), "/"))} · 시나리오 정본 tools/hyun_e2e/cases.json</p>
{video}<table><tr><th>케이스</th><th>내용</th><th>판정</th><th>걸린 시간</th><th>응답(말→답)</th></tr>{summary}</table></div>
{"".join(cards)}
<p class="meta">파일: transcript.json(전체 기록) · logcat.txt(앱 로그) · server-log.jsonl(서버 대화 기록) · shots/ · slides/ · video.mp4 · raw/(케이스별 원본 영상·소리, git 제외)</p>
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
    ap.add_argument("--voice", action="store_true")
    ap.add_argument("--report", default="")
    ap.add_argument("--rebuild", default="")
    a = ap.parse_args()
    if a.report:
        print(report(a.report))
        return
    with open(CASES, encoding="utf-8") as f:
        spec = json.load(f)
    if a.rebuild:   # 영상만 다시 — 슬라이드 글(cases.json)·섞기를 고친 뒤. raw/(케이스 원본 영상·소리·목소리)가 있어야 한다
        run_dir = os.path.abspath(a.rebuild)
        with open(os.path.join(run_dir, "transcript.json"), encoding="utf-8") as f:
            T = json.load(f)
        T["video"] = build_video(run_dir, T["cases"], spec, T["stamp"], T.get("audio"), T.get("clock", 0.0), T.get("voiced", False))
        with open(os.path.join(run_dir, "transcript.json"), "w", encoding="utf-8") as f:
            json.dump(T, f, ensure_ascii=False, indent=1)
        print(report(run_dir), T["video"])
        return
    cases = [c for c in spec["cases"] if not a.ids or c["id"] in a.ids.split(",")]
    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M")
    run_dir = os.path.join(RECORDS, stamp)
    for d in ("raw", "shots"):
        os.makedirs(os.path.join(run_dir, d), exist_ok=True)
    srv = Servers(run_dir, a.keep_data) if a.servers else None
    if srv:
        os.environ["HYUN_DATA"] = DATA_E2E
    clk = emu_offset()
    print(f"에뮬 시계 {clk:+.2f}초(에뮬 − PC)", flush=True)
    log = Logcat(os.path.join(run_dir, "logcat.txt"))
    # 시험 중엔 에뮬 마이크(PC 마이크 연결)를 끈다 — 방 소리·스피커 소리가 대화 창에 들어가 엉뚱한 질문이 됐다(11-07 실측 "비서").
    # 시험 목소리는 귀에 바로 넣으므로(--es earfile) 마이크가 필요 없다. 끝나면 다시 켠다
    adb("emu", "avd", "hostmicoff")
    tts = _tts()
    state = {"split": False}
    results = []
    rec_audio = AppAudio(os.path.join(run_dir, "raw", "app-audio.wav")) if a.video else None
    audio = None
    try:
        if not any(st.get("do") == "restart" for c in cases for st in c["steps"]):
            am("")   # 앱을 앞으로
            time.sleep(3)
        for n, c in enumerate(cases):
            print(f"▶ {c['id']} {c['title']}", flush=True)
            r = run_case(c, log, n, run_dir, tts, spec.get("voice_prefix", "현비서야"), a.video, state, a.voice)
            results.append(r)
            print(f"  {'통과' if r['pass'] else '실패'} · {r['secs']}초 · " + " / ".join(("✓ " if x["ok"] else "✗ ") + x["what"] for x in r["checks"])
                  + "".join(f" · ⚠ {e}" for e in r.get("env") or []), flush=True)
    finally:
        if rec_audio:
            audio = rec_audio.stop()
        log.close()
        adb("emu", "avd", "hostmicon")
        if srv:
            srv.stop()
    srv_all = server_log_path()
    if os.path.exists(srv_all):
        shutil.copy(srv_all, os.path.join(run_dir, "server-log.jsonl"))
    video = build_video(run_dir, results, spec, stamp, audio, clk, a.voice) if a.video else None
    T = {"stamp": stamp, "cases": results, "video": video, "data": os.path.relpath(data_dir(), ROOT),
         "spec": os.path.relpath(CASES, ROOT), "clock": clk, "audio": audio, "voiced": a.voice}
    with open(os.path.join(run_dir, "transcript.json"), "w", encoding="utf-8") as f:
        json.dump(T, f, ensure_ascii=False, indent=1)
    print(report(run_dir))
    print(f"통과 {sum(r['pass'] for r in results)}/{len(results)}")


if __name__ == "__main__":
    main()
