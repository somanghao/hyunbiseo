"""현비서 상황별 소개 영상 — dan-emu 상황 화면(StageFeature)을 에뮬에서 상황 순서대로 띄우며 녹화하고,
상황마다 설명 슬라이드 + 실제 화면(내레이션 음성 · 자막)을 이어 붙여 영상 하나로 만든다.

    python -I -X utf8 tools/hyun_stage_tour.py --shots                 # 장면마다 스크린샷만(분석용) → <out>/shots/
    python -I -X utf8 tools/hyun_stage_tour.py                         # 영상 → <out>/stage-tour.mp4
    python -I -X utf8 tools/hyun_stage_tour.py --only ignition,drive   # 일부 상황만
    python -I -X utf8 tools/hyun_stage_tour.py --rebuild <out>         # 녹화는 그대로, 슬라이드·자막·소리만 다시

전제: 에뮬(Pleos_Connect_v2.1) 켜짐 · dan-emu 앱 설치 · ffmpeg · Pillow(사용자 site-packages) · 내레이션 = Windows SAPI 한국어(Heami)
조작: 앱 브로드캐스트 STAGE·SIM(`--es sit|screen|page|idea|nav|skin|turn|wiper …` — docs/hyun-app.md §상황별 추천 화면)
결과: records/stage-tour/<YYYYMMDD-HHMM>/ — stage-tour.mp4 · stage-tour.srt · slides/ · shots/ · raw/(원본 녹화·소리, git 제외 권장)
서버(hyun_server·stt_server)는 띄우지 않는다 — 뉴스·웹툰 등 서버 카드는 예시 값으로 보인다.
"""
import argparse
import datetime as dt
import glob
import json
import os
import shutil
import site
import subprocess
import sys
import tempfile
import time

if site.getusersitepackages() not in sys.path:   # -I는 사용자 site-packages를 뺀다 — Pillow
    sys.path.append(site.getusersitepackages())

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUTS = os.path.join(ROOT, "records", "stage-tour")
ADB = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk", "platform-tools", "adb.exe")
SERIAL = os.environ.get("HYUN_SERIAL", "emulator-5554")
PKG = "io.github.somanghao.makecar.hyun"
ACT = f"{PKG}/.HyunActivity"
STAGE, SIM = f"{PKG}.STAGE", f"{PKG}.SIM"
FFMPEG = shutil.which("ffmpeg") or next(iter(glob.glob(os.path.expanduser(
    r"~/AppData/Local/Microsoft/WinGet/Packages/Gyan.FFmpeg*/ffmpeg-*/bin/ffmpeg.exe"))), None)
FFPROBE = os.path.join(os.path.dirname(FFMPEG), "ffprobe.exe") if FFMPEG else None
FONTS = os.path.join(os.environ.get("WINDIR", r"C:\Windows"), "Fonts")
W, H, RATE = 1280, 720, 48000
VENC = ["-c:v", "libx264", "-preset", "medium", "-crf", "21", "-pix_fmt", "yuv420p", "-r", "30", "-video_track_timescale", "90000"]
AENC = ["-c:a", "aac", "-b:a", "160k", "-ar", str(RATE), "-ac", "2"]
FOOTER = "Pleos Connect Emulator v2.1 실제 화면 · 브랜치 dan-emu · 내레이션 = 합성 음성 · 서버 카드는 예시 값"

# ---------- 대본 — 상황마다 슬라이드(제목 · 언제 · 보여 주는 것 · 규칙) + 화면 박자(조작 → 말) ----------
# beat: do = [(STAGE|SIM, "인자")…] 를 보낸 뒤 say 를 읽고(자막도 같은 글), 최소 hold 초 머문다
SCRIPT = json.load(open(os.path.join(HERE, "hyun_stage_tour.json"), encoding="utf-8"))


def adb(*a, timeout=30):
    return subprocess.run([ADB, "-s", SERIAL, *a], capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=timeout)


def sh(cmd, timeout=30):
    return adb("shell", cmd, timeout=timeout)


def send(kind, args):
    action = STAGE if kind == "STAGE" else SIM
    return sh(f"am broadcast -a {action} {args}")


def run_do(do):
    for kind, args in do:
        if kind == "START":
            sh(f"am start --user 10 -n {ACT} {args}")
        elif kind == "SH":
            sh(args)
        elif kind == "EMU":   # 에뮬 콘솔 — avd hostmicoff: 앱이 제 안내 음성을 마이크로 다시 듣고 스스로 답하는 것을 막는다
            adb("emu", *args.split())
        elif kind == "WAIT":
            time.sleep(float(args))
        else:
            send(kind, args)
        time.sleep(0.35)


def shot(path):
    with open(path, "wb") as f:
        subprocess.run([ADB, "-s", SERIAL, "exec-out", "screencap", "-p"], stdout=f, timeout=30)


# ---------- 내레이션(SAPI) ----------
PS = r"""
Add-Type -AssemblyName System.Speech
$s = New-Object System.Speech.Synthesis.SpeechSynthesizer
$v = $s.GetInstalledVoices() | Where-Object { $_.VoiceInfo.Culture.Name -eq 'ko-KR' } | Select-Object -First 1
if ($v) { $s.SelectVoice($v.VoiceInfo.Name) }
$s.Rate = 1
$fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(24000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
$s.SetOutputToWaveFile($env:HYUN_WAV, $fmt)
$s.Speak([IO.File]::ReadAllText($env:HYUN_TXT, [Text.Encoding]::UTF8))
$s.Dispose()
"""


def tts(text, wav):
    if os.path.exists(wav):
        return duration(wav)
    tmp = tempfile.mkdtemp(prefix="tour-tts-")
    txt = os.path.join(tmp, "a.txt")
    with open(txt, "w", encoding="utf-8") as f:
        f.write(text)
    subprocess.run(["powershell", "-NoProfile", "-Command", PS], check=True, capture_output=True,
                   env={**os.environ, "HYUN_WAV": wav, "HYUN_TXT": txt})
    return duration(wav)


def duration(path):
    r = subprocess.run([FFPROBE, "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", path],
                       capture_output=True, text=True)
    try:
        return float(r.stdout.strip())
    except ValueError:
        return 0.0


def ff(*args):
    r = subprocess.run([FFMPEG, "-y", "-loglevel", "error", *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if r.returncode:
        print("  ffmpeg 오류: " + r.stderr[-600:], flush=True)
    return r.returncode == 0


# ---------- 그림(Pillow) ----------
def font(size, bold=False):
    from PIL import ImageFont
    return ImageFont.truetype(os.path.join(FONTS, "malgunbd.ttf" if bold else "malgun.ttf"), size)


def wrap(d, text, f, maxw):
    lines, cur = [], ""
    for w in text.split(" "):
        t = (cur + " " + w).strip()
        if d.textlength(t, font=f) <= maxw:
            cur = t
            continue
        if cur:
            lines.append(cur)
        cur = w
    if cur:
        lines.append(cur)
    return lines


INK, MUT, AC, SOFT = "#14202b", "#62707d", "#0b7c8f", "#eaf6f8"


def make_cover(path, sections):
    from PIL import Image, ImageDraw
    im = Image.new("RGB", (W, H), "white")
    d = ImageDraw.Draw(im)
    X = 84
    d.text((X, 58), SCRIPT["cover"]["kicker"], font=font(22), fill=MUT)
    d.text((X, 98), SCRIPT["cover"]["title"], font=font(50, True), fill=INK)
    for i, ln in enumerate(wrap(d, SCRIPT["cover"]["sub"], font(24), W - 2 * X)):
        d.text((X, 170 + i * 36), ln, font=font(24), fill=INK)
    y = 170 + 36 * len(wrap(d, SCRIPT["cover"]["sub"], font(24), W - 2 * X)) + 14
    d.rectangle((X, y, X + 110, y + 6), fill=AC)
    y += 30
    lh = min(48, 380 // max(1, len(sections)))
    for i, s in enumerate(sections):
        d.text((X, y + i * lh), f"{i + 1}", font=font(23, True), fill=AC)
        d.text((X + 42, y + i * lh), s["title"], font=font(23, True), fill=INK)
        d.text((X + 42 + d.textlength(s["title"], font=font(23, True)) + 18, y + i * lh + 2), s["when"], font=font(20), fill=MUT)
    d.text((X, 676), FOOTER, font=font(17), fill="#8a96a3")
    im.save(path)


def make_slide(path, n, total, s):
    from PIL import Image, ImageDraw
    im = Image.new("RGB", (W, H), "white")
    d = ImageDraw.Draw(im)
    X, R = 84, W - 84
    d.text((X, 58), f"현비서 상황별 소개 · {n} / {total}", font=font(22), fill=MUT)
    d.text((X, 100), s["title"], font=font(52, True), fill=INK)
    d.text((X + d.textlength(s["title"], font=font(52, True)) + 22, 124), s["when"], font=font(24), fill=AC)
    y = 184
    d.rectangle((X, y, X + 110, y + 6), fill=AC)
    y += 30
    for head, key in (("화면에 보이는 것", "shows"), ("이 상황의 규칙", "rules")):
        items = s.get(key) or []
        if not items:
            continue
        d.text((X, y), head, font=font(24, True), fill=AC)
        y += 40
        for it in items:
            lines = wrap(d, it, font(27), R - X - 34)
            d.ellipse((X + 4, y + 14, X + 14, y + 24), fill=AC)
            for ln in lines:
                d.text((X + 30, y), ln, font=font(27), fill=INK)
                y += 40
            y += 4
        y += 18
    d.text((X, 676), FOOTER, font=font(17), fill="#8a96a3")
    im.save(path)


def make_caption(path, label, text):
    """녹화 위 겹칠 투명 그림 — 왼쪽 위 상황 이름표 + 아래 자막 띠"""
    from PIL import Image, ImageDraw
    im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    fl = font(20, True)
    lw = d.textlength(label, font=fl)
    d.rounded_rectangle((18, 16, 18 + lw + 28, 52), 10, fill=(11, 124, 143, 230))
    d.text((32, 20), label, font=fl, fill="white")
    if text:   # 아래 Pleos 시스템 바 위에 — 앱 화면(독·마이크)을 덜 가리게
        f = font(26, True)
        lines = wrap(d, text, f, W - 260)
        bh = 16 + 36 * len(lines)
        y0 = H - 8 - bh
        tw = max(d.textlength(ln, font=f) for ln in lines)
        d.rounded_rectangle(((W - tw) / 2 - 28, y0, (W + tw) / 2 + 28, H - 8), 14, fill=(10, 16, 22, 215))
        for i, ln in enumerate(lines):
            lw2 = d.textlength(ln, font=f)
            d.text(((W - lw2) / 2, y0 + 6 + i * 36), ln, font=f, fill="white")
    im.save(path)


# ---------- 녹화 ----------
def record_section(s, raw_dir, voice_dir):
    """상황 하나 — 준비(녹화 밖) → screenrecord 시작 → 박자마다 조작·대기 → 멈춤 → (mp4, 박자 시각 목록)"""
    run_do(s.get("prep", []))
    time.sleep(1.2)
    beats = s["beats"]
    durs = [tts(b["say"], os.path.join(voice_dir, f"{s['id']}-{i:02d}.wav")) if b.get("say") else 0.0 for i, b in enumerate(beats)]
    remote = f"/sdcard/tour_{s['id']}.mp4"
    sh(f"rm -f {remote}")
    rec = subprocess.Popen([ADB, "-s", SERIAL, "shell", "screenrecord", "--size", f"{W}x{H}", "--bit-rate", "6000000", remote],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    t0 = time.time()
    time.sleep(1.0)
    marks = []
    for i, b in enumerate(beats):
        run_do(b.get("do", []))
        t = time.time() - t0
        hold = max(b.get("hold", 0), durs[i] + 0.9)
        marks.append({"t": round(t, 2), "hold": round(hold, 2), "say": b.get("say", ""), "cap": b.get("cap", b.get("say", "")),
                      "voice": f"{s['id']}-{i:02d}.wav" if b.get("say") else ""})
        time.sleep(hold)
    wall = time.time() - t0
    sh("pkill -INT screenrecord")
    try:
        rec.wait(timeout=15)
    except subprocess.TimeoutExpired:
        rec.kill()
    time.sleep(1.0)
    local = os.path.join(raw_dir, f"{s['id']}.mp4")
    adb("pull", remote, local, timeout=120)
    sh(f"rm -f {remote}")
    lag = max(0.0, min(1.5, wall - duration(local)))   # screenrecord 첫 프레임까지 걸린 시간 — 자막·소리를 그만큼 당긴다
    for m in marks:
        m["t"] = round(max(0.0, m["t"] - lag), 2)
    return local, marks, round(lag, 2)


# ---------- 조립 ----------
def still_clip(png, sec, out, wav=None, lead=0.5):
    if wav:
        return ff("-loop", "1", "-framerate", "30", "-t", f"{sec}", "-i", png, "-i", wav,
                  "-filter_complex", f"[0:v]scale={W}:{H},format=yuv420p[v];[1:a]aresample={RATE},adelay={int(lead * 1000)}:all=1,apad[a]",
                  "-map", "[v]", "-map", "[a]", *VENC, *AENC, "-t", f"{sec}", out)
    return ff("-loop", "1", "-framerate", "30", "-t", f"{sec}", "-i", png, "-f", "lavfi", "-t", f"{sec}", "-i", f"anullsrc=r={RATE}:cl=stereo",
              "-vf", f"scale={W}:{H},format=yuv420p", *VENC, *AENC, "-shortest", out)


def screen_clip(raw, marks, label, cap_dir, voice_dir, out, sid):
    end = marks[-1]["t"] + marks[-1]["hold"]
    ins, chain, amix = ["-i", raw], [], []
    v = "[0:v]fps=30,scale=1280:720:force_original_aspect_ratio=decrease,pad=1280:720:(ow-iw)/2:(oh-ih)/2,setsar=1,tpad=stop_mode=clone:stop_duration=30[v0]"
    chain.append(v)
    cur = "v0"
    k = 1
    for i, m in enumerate(marks):
        png = os.path.join(cap_dir, f"{sid}-{i:02d}.png")
        make_caption(png, label, m["cap"])
        ins += ["-i", png]
        a, b = m["t"], (marks[i + 1]["t"] if i + 1 < len(marks) else end)
        chain.append(f"[{cur}][{k}:v]overlay=0:0:enable='between(t,{a:.2f},{b:.2f})'[v{k}]")
        cur = f"v{k}"
        k += 1
    for i, m in enumerate(marks):
        if m["voice"]:
            ins += ["-i", os.path.join(voice_dir, m["voice"])]
            chain.append(f"[{k}:a]aresample={RATE},adelay={int((m['t'] + 0.25) * 1000)}:all=1[a{k}]")
            amix.append(f"[a{k}]")
            k += 1
    if amix:
        chain.append(f"{''.join(amix)}amix=inputs={len(amix)}:normalize=0,apad[aout]")
    else:
        ins += ["-f", "lavfi", "-i", f"anullsrc=r={RATE}:cl=stereo"]
        chain.append(f"[{k}:a]apad[aout]")
    return ff(*ins, "-filter_complex", ";".join(chain), "-map", f"[{cur}]", "-map", "[aout]", *VENC, *AENC, "-t", f"{end:.2f}", out)


def srt_time(x):
    h, r = divmod(x, 3600)
    m, s = divmod(r, 60)
    return f"{int(h):02d}:{int(m):02d}:{int(s):02d},{int((s - int(s)) * 1000):03d}"


def build(out_dir, sections, rec):
    slides, caps, voice, parts = (os.path.join(out_dir, p) for p in ("slides", "caps", "voice", "parts"))
    for p in (slides, caps, voice, parts):
        os.makedirs(p, exist_ok=True)
    seq, srt, clock = [], [], 0.0
    cover = os.path.join(slides, "00-cover.png")
    make_cover(cover, sections)
    cw = os.path.join(voice, "cover.wav")
    cd = tts(SCRIPT["cover"]["say"], cw)
    sec = round(cd + 1.6, 2)
    still_clip(cover, sec, os.path.join(parts, "00-cover.mp4"), cw)
    seq.append(os.path.join(parts, "00-cover.mp4"))
    srt.append((clock + 0.5, clock + 0.5 + cd, SCRIPT["cover"]["say"]))
    clock += sec
    for n, s in enumerate(sections, 1):
        png = os.path.join(slides, f"{n:02d}-{s['id']}.png")
        make_slide(png, n, len(sections), s)
        sw = os.path.join(voice, f"slide-{s['id']}.wav")
        sd = tts(s["say"], sw)
        sec = round(sd + 1.4, 2)
        sp = os.path.join(parts, f"{n:02d}a-{s['id']}.mp4")
        still_clip(png, sec, sp, sw)
        seq.append(sp)
        srt.append((clock + 0.5, clock + 0.5 + sd, s["say"]))
        clock += sec
        r = rec[s["id"]]
        cp = os.path.join(parts, f"{n:02d}b-{s['id']}.mp4")
        screen_clip(os.path.join(out_dir, "raw", f"{s['id']}.mp4"), r["marks"], f"{n}. {s['title']}", caps, voice, cp, s["id"])
        seq.append(cp)
        for i, m in enumerate(r["marks"]):
            if m["cap"]:
                b = r["marks"][i + 1]["t"] if i + 1 < len(r["marks"]) else m["t"] + m["hold"]
                srt.append((clock + m["t"], clock + b, m["cap"]))
        clock += duration(cp)
    outro = os.path.join(slides, "99-outro.png")
    make_cover(outro, sections)
    ow = os.path.join(voice, "outro.wav")
    od = tts(SCRIPT["outro"]["say"], ow)
    still_clip(outro, round(od + 2.0, 2), os.path.join(parts, "99-outro.mp4"), ow)
    seq.append(os.path.join(parts, "99-outro.mp4"))
    srt.append((clock + 0.5, clock + 0.5 + od, SCRIPT["outro"]["say"]))
    lst = os.path.join(out_dir, "parts.txt")
    with open(lst, "w", encoding="utf-8") as f:
        f.writelines(f"file '{p.replace(os.sep, '/')}'\n" for p in seq)
    final = os.path.join(out_dir, "stage-tour.mp4")
    ok = ff("-f", "concat", "-safe", "0", "-i", lst, "-c", "copy", "-movflags", "+faststart", final)
    with open(os.path.join(out_dir, "stage-tour.srt"), "w", encoding="utf-8") as f:
        for i, (a, b, t) in enumerate(srt, 1):
            f.write(f"{i}\n{srt_time(a)} --> {srt_time(b)}\n{t}\n\n")
    return final if ok else None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--shots", action="store_true", help="장면마다 스크린샷만")
    ap.add_argument("--only", default="", help="상황 id 쉼표 목록")
    ap.add_argument("--rebuild", default="", help="기존 출력 폴더 — 녹화는 그대로 두고 조립만 다시")
    a = ap.parse_args()
    sections = [s for s in SCRIPT["sections"] if not a.only or s["id"] in a.only.split(",")]
    if a.rebuild:
        out = a.rebuild
        rec = json.load(open(os.path.join(out, "marks.json"), encoding="utf-8"))
        print("→", build(out, sections, rec))
        return
    out = os.path.join(OUTS, dt.datetime.now().strftime("%Y%m%d-%H%M") + ("-shots" if a.shots else ""))
    raw, shots, voice = (os.path.join(out, p) for p in ("raw", "shots", "voice"))
    for p in (raw, shots, voice):
        os.makedirs(p, exist_ok=True)
    run_do(SCRIPT.get("setup", []))
    if a.shots:
        for s in sections:
            run_do(s.get("prep", []))
            time.sleep(1.2)
            for i, b in enumerate(s["beats"]):
                run_do(b.get("do", []))
                time.sleep(max(1.5, b.get("shotwait", 1.5)))
                p = os.path.join(shots, f"{s['id']}-{i:02d}.png")
                shot(p)
                print(p, "·", b.get("cap", b.get("say", ""))[:60], flush=True)
        return
    rec = {}
    try:
        for s in sections:
            print(f"[녹화] {s['title']}", flush=True)
            local, marks, lag = record_section(s, raw, voice)
            rec[s["id"]] = {"raw": local, "marks": marks, "lag": lag}
            print(f"   {len(marks)}박자 · 지연 {lag}초 · {duration(local):.1f}초", flush=True)
            with open(os.path.join(out, "marks.json"), "w", encoding="utf-8") as f:
                json.dump(rec, f, ensure_ascii=False, indent=1)
    finally:
        run_do(SCRIPT.get("teardown", []))
    print("→", build(out, sections, rec))


if __name__ == "__main__":
    main()
