"""현비서의 귀(시연용 받아 적기) — 앱이 잘라 보낸 말소리(16kHz·16bit·mono PCM)를 faster-whisper로 받아 적는다.

    ~/.cache/faster-whisper-venv/Scripts/python.exe -I server/hyun/stt_server.py [port]     # 기본 8791, run_in_background
    POST /stt   (body = PCM)  → {"text": "...", "ms": 처리 ms, "sec": 길이}
    GET  /stt/health          → {"ok": true, "model": ...}

왜 따로: Pleos STT(ON_DEVICE)는 에뮬 마이크가 작게 들어오면 말 시작을 못 잡고(2026-11-06 실측 — 같은 소리를 Whisper는 정확히 받아 적음),
앱이 버튼 없이 상시로 듣게 하려면 앱이 마이크를 직접 들어야 한다. 실서비스에선 기기 안 소형 모델·클라우드 STT가 이 자리.
venv: faster-whisper + nvidia-cublas-cu12 + nvidia-cudnn-cu12(9) — GPU(CUDA 12). 모델은 HYUN_STT_MODEL(기본 large-v3).
"""
import glob
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

_venv = os.path.dirname(os.path.dirname(sys.executable))
for _d in glob.glob(os.path.join(_venv, "Lib", "site-packages", "nvidia", "*", "bin")):   # cuBLAS·cuDNN DLL
    os.add_dll_directory(_d)
    os.environ["PATH"] = _d + os.pathsep + os.environ["PATH"]

import numpy as np  # noqa: E402
from faster_whisper import WhisperModel  # noqa: E402

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8791
MODEL = os.environ.get("HYUN_STT_MODEL", "large-v3")
DEVICE = os.environ.get("HYUN_STT_DEVICE", "cuda")
model = WhisperModel(MODEL, device=DEVICE, compute_type="float16" if DEVICE == "cuda" else "int8",
                     cpu_threads=int(os.environ.get("HYUN_STT_THREADS", "0")))
# 정확도 조절(에뮬 컨테이너용, 기본값 = 원래 동작): 빔 크기 · 우선 단어 · 앞 문맥(앱이 자주 듣는 말)
BEAM = int(os.environ.get("HYUN_STT_BEAM", "1"))
# 앱이 자주 듣는 말 — 우선 단어(hotwords) + 앞 문맥(initial_prompt). small 모델 실측(11-08, say Yuna 22문장): 13/22 → 19/22 ("노래방"이 "놀이방"으로 적히던 것 해결)
DOMAIN_WORDS = "현비서, 노래방, 쉼표, 브리핑, 끝말잇기, 퀴즈, 가는 김에, 휴게소, 웹툰, 내비, 경유지, 목적지, 우회, 정체, 앞길, 강남역, 아이유"
DOMAIN_PROMPT = "현비서야, 노래방 가자. 쉼표 모드 켜 줘. 오늘 브리핑 들려줘. 끝말잇기 하자. 퀴즈 내 줘. 가는 김에 들러. 목적지로 가자. 앞길 어때? 노래 틀어 줘."
HOTWORDS = os.environ.get("HYUN_STT_HOTWORDS", DOMAIN_WORDS)
PROMPT = os.environ.get("HYUN_STT_PROMPT", DOMAIN_PROMPT) or None
lock = threading.Lock()   # GPU 하나 — 한 번에 하나씩

# Whisper가 조용한 소리·잡음에서 지어내는 상투 문장(유튜브 자막 학습 흔적) — 버린다
JUNK = ("시청해", "구독", "좋아요", "자막", "MBC", "KBS", "SBS", "뉴스였습니다", "다음 영상")


def transcribe(pcm: bytes) -> dict:
    a = np.frombuffer(pcm, np.int16).astype(np.float32) / 32768.0
    t0 = time.time()
    with lock:
        # 시간 상한: 온도 재시도 없음(temperature 0) · 최대 64토큰 — 잡음 섞인 긴 구간에서 반복 디코딩으로 6~15초 걸린 실측(11-06)
        segs, _ = model.transcribe(a, language="ko", beam_size=BEAM, vad_filter=False, temperature=0.0, initial_prompt=PROMPT,
                                   condition_on_previous_text=False, no_speech_threshold=0.6,
                                   without_timestamps=True, max_new_tokens=64,
                                   hotwords=HOTWORDS or None)   # 호출어를 우선 단어로 — 없으면 "변비서"로 적었다(11-06 실측)
        segs = [s for s in segs if s.no_speech_prob < 0.6]
    text = " ".join(s.text.strip() for s in segs).strip()
    if any(j in text for j in JUNK) or text in ("감사합니다.", "감사합니다", "네.", "아."):
        text = ""
    return {"text": text, "ms": int((time.time() - t0) * 1000), "sec": round(len(a) / 16000, 2)}


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if self.path.startswith("/stt/health"):
            self._json({"ok": True, "model": MODEL})
        else:
            self.send_error(404)

    def do_POST(self):
        if not self.path.startswith("/stt"):
            self.send_error(404)
            return
        n = int(self.headers.get("Content-Length", 0))
        r = transcribe(self.rfile.read(n))
        print(f"stt|{r['sec']}s|{r['ms']}ms|{r['text'] or '(없음)'}", flush=True)
        self._json(r)

    def _json(self, o):
        b = json.dumps(o, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    transcribe(b"\0\0" * 8000)   # 첫 호출 지연(커널 준비)을 미리 치른다
    print(f"stt server http://127.0.0.1:{PORT} model={MODEL} device={DEVICE}", flush=True)
    # HYUN_STT_HOST=0.0.0.0 — 격리 컨테이너 안에서 돌릴 때만(포트는 호스트 127.0.0.1 에만 게시). 기본은 127.0.0.1
    ThreadingHTTPServer((os.environ.get("HYUN_STT_HOST", "127.0.0.1"), PORT), Handler).serve_forever()
