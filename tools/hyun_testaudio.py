"""현비서 귀 시험 녹음 만들기 — Windows 한국어 음성(SAPI · Microsoft Heami)으로 말을 합성해 16kHz mono PCM으로 저장한다.

앱은 `--es earfile <이름>`으로 서버(/hyun/testaudio)에서 받아 상시 귀(Ear.feed)에 넣는다 → 받아 적기 서버(Whisper) → 호출어 → 두뇌.
마이크 없이 '귀 → 호출어 → 대답' 전 구간을 주행 중에도 되풀이해 시험하려는 것(실제 마이크 경로는 사람이 말해서 따로 본다).

    python -I -X utf8 tools/hyun_testaudio.py hey_gangnam "현비서야 강남역 가자"
    → server/hyun/data/testaudio/hey_gangnam.pcm (git 제외)
"""
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "server", "hyun", "data", "testaudio")

PS = r"""
Add-Type -AssemblyName System.Speech
$s = New-Object System.Speech.Synthesis.SpeechSynthesizer
$v = $s.GetInstalledVoices() | Where-Object { $_.VoiceInfo.Culture.Name -eq 'ko-KR' } | Select-Object -First 1
if ($v) { $s.SelectVoice($v.VoiceInfo.Name) }
$s.Rate = 0
$fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
$s.SetOutputToWaveFile($env:HYUN_WAV, $fmt)
$s.Speak([IO.File]::ReadAllText($env:HYUN_TXT, [Text.Encoding]::UTF8))
$s.Dispose()
"""


def make(name, text, out_dir=None):
    """→ (경로, 초). out_dir 기본 = server/hyun/data/testaudio(시험 데이터를 따로 쓰면 HYUN_DATA/testaudio)"""
    out_dir = out_dir or (os.path.join(os.environ["HYUN_DATA"], "testaudio") if os.environ.get("HYUN_DATA") else OUT)
    os.makedirs(out_dir, exist_ok=True)
    tmp = tempfile.mkdtemp(prefix="hyun-tts-")
    wav, txt = os.path.join(tmp, "a.wav"), os.path.join(tmp, "a.txt")
    with open(txt, "w", encoding="utf-8") as f:
        f.write(text)
    env = {**os.environ, "HYUN_WAV": wav, "HYUN_TXT": txt}
    subprocess.run(["powershell", "-NoProfile", "-Command", PS], check=True, env=env, capture_output=True)
    with open(wav, "rb") as f:
        b = f.read()
    i = b.find(b"data")
    pcm = b[i + 8:] if i >= 0 else b[44:]
    pad = b"\x00\x00" * 4800   # 앞뒤 0.3초 조용 — 귀의 말 시작·끝 판정처럼
    out = os.path.join(out_dir, name + ".pcm")
    with open(out, "wb") as f:
        f.write(pad + pcm + pad)
    return out, len(pcm) / 32000


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    path, sec = make(sys.argv[1], " ".join(sys.argv[2:]))
    print(f"{path} · {sec:.1f}s")
