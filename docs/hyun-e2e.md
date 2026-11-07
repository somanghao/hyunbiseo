# 현비서 E2E 시험 — 시나리오 · 영상 · 실제 대화 기록 (2026-11-07)

> 하네스([[hyun-harness]])가 '질문 → 두뇌 답'을 판정한다면, 이건 **에뮬 앱 전체**(귀·두뇌·내비·화면·말하기)를 사람이 쓰는 순서대로 돌리고 **영상 + 실제 대화 + 스크린샷**으로 남긴다. 사용자 요청 순서(11-07): 네비 모드(반반) → 교통상황 → 휴게소 소개 → 취향 질문 → 웹툰 순위 → 영화 순위 → 스포츠 기사 → 인물 기사.

## 실행

```bash
python -I -X utf8 tools/hyun_e2e/run.py --servers --video          # 전체 — 서버를 시험 데이터로 새로 띄우고 녹화(약 10분)
python -I -X utf8 tools/hyun_e2e/run.py --servers --ids e02,e03    # 일부(개발 중) · --keep-data 이어서
python -I -X utf8 tools/hyun_e2e/run.py --report records/hyun-e2e/<폴더>   # 보고서만 다시
```

- 전제: 에뮬 켜짐 · 현비서 앱 설치 · `adb forward tcp:8088 tcp:8088`(vconsole) · 현비서·받아 적기 서버는 **꺼 둔다**(`--servers`가 띄우고 끈다 — 이미 떠 있으면 멈춤)
- **시험 데이터는 따로** — `--servers`는 `HYUN_DATA=server/hyun/data-e2e`(매번 새로, git 제외)로 서버를 띄운다 → 사용자 실제 취향·대화·브리핑 순서를 건드리지 않는다(`hyun_server`·`taste`·`taste_refine`이 `HYUN_DATA`를 따른다)
- 시험 목소리: `tools/hyun_testaudio.py`(Windows SAPI 한국어 Heami → 16kHz PCM)로 "현비서야 …"를 만들어 `--es earfile` → 앱 귀(`Ear.feed`) → 받아 적기(Whisper) → 호출어 → 두뇌. 대화 창 안의 대답은 `--es say`(글)
- 시험 중엔 에뮬 마이크(PC 마이크 연결)를 끈다(`emu avd hostmicoff`, 끝나면 on) — 방·스피커 소리가 대화 창에 들어가 엉뚱한 질문("비서")이 됐다
- 반반 화면: 현비서 창 폭으로 판정(`dumpsys` mBounds — 2칸 841px · 1칸 1706px) → 손잡이 ⋮ 끌기(`input swipe`)

## 시나리오 정본 `tools/hyun_e2e/cases.json`

- 케이스 = `id · title · why · steps · expect`. steps: `do`(restart · say{text, voice} · intent{extras} · gleo{action, param} · vc{args} · split{on}) / `wait`(reply · log{pattern} · tts · sec) / `shot`(캡션) · expect: `log`(앱 로그 정규식) · `server`(그 케이스 동안 서버 log.jsonl 줄 정규식)
- 두뇌는 실제 Claude(내 계정)라 답이 매번 다르다 → 판정은 **'했나'만**(경로 시작·카드 종류·사진·검색 모드·취향 기록 등). 말의 질은 영상·대화 기록을 사람이 본다
- 지금 11개: e00 시동 브리핑 · e01 네비 모드(말로 목적지 + 반반) · e02 교통 표 · e03 휴게소 소개(사진 · 표) · e04 취향 질문 · e05 웹툰 표지 · e06 영화 목록 · e07 야구 소식(검색) · e08 인물 기사(사진 · 표) · e09 주행 중 사고 정찰·휴게소 알림 · e10 글레오 상황
- 앱 로그 약속(러너가 읽음): `heard|` · `say|P<n>|` · `tts|done`(말 끝) · `card|제목|개수|kind=…|img=n|앞 3줄` · `event|` · `reply|<id>|{json}` · `ear|wake+text|` · `nav|`·`via|`·`route|`·`navi|routeStarted` · `scout|` · `gleo|` — 바꾸면 `run.py` `classify`·cases도

## 결과 `records/hyun-e2e/<YYYYMMDD-HHMM>/`

- `report.html` — 요약 표(판정·걸린 시간·응답 초) + 영상(자막 = 실제 대화, 시각 누르면 그 자리로) + 케이스별 판정·대화·스크린샷·단계
- `transcript.json`(전체 기록 — 케이스·대화 시각·판정·서버 답 원문) · `logcat.txt` · `server-log.jsonl` · `server.out` · `shots/*.jpg`
- `video.mp4`(7분 · 3.5MB — git에 함께) · `raw/`(케이스별 원본 영상 136MB·자막 srt·원본 PNG — **git 제외**). 영상 = `adb screenrecord` 1280x720(케이스마다 ≤180초) → ffmpeg 이어 붙이기 + 자막 굽기(맑은 고딕). **소리 없음**(화면 녹화) — 자막이 말 대신
- 기록 목록·판정 → 아래 §기록
- 보기: `python -I dashboard/serve.py` → http://localhost:8765/records/hyun-e2e/<폴더>/report.html (no-store 서버 — 영상·사진 상대 경로)

## 기록

| 실행 | 결과 | 메모 |
|---|---|---|
| (지움) 0904 · 0906 · 0909 · 0912 · 0916 | 개발 중 부분 실행 | 반반 배치·교통 표·휴게소(다음 휴게소 '확인 못 함' → 경로 도로 맥락 추가로 '아마 기흥휴게소(추정)')·인물 사진 검색어 줄이기·주행 중 휴게소 답이 늦게 옴 → 케이스 고침 |
| (지움) 0919 · 0929 · 0939 | 11/11 · 9/11 · 11/11 | 0919: 끼어든 정찰 답을 질문 답으로 착각(→ `ask|id` 짝짓기) · 0929: **에뮬 워치독 재시작**(GNSS HAL 72초) → 앱·내비 경로 소실로 e05·e10 실패(→ 러너가 워치독을 '환경'으로 표시, e09가 목적지를 스스로 설정) · 0939: 새 긴 경로 정체를 줄줄이 알림(→ 정찰 90초 조용한 시작) |
| **`records/hyun-e2e/20261107-0949/`** | **11/11 통과 · 영상 7분 2초** | 최종 — 응답(말 → 답) 5.1~5.8초, 검색 모드 13초 · 휴게소 카드 "기흥휴게소 부산방향(추정)" 대표 메뉴 향천우동·라면·호두과자 · 300m 사고 = 갈림길 없음 "돌아갈 길은 없어요" · 워치독 없음 |
