# 현비서 E2E 시험 — 시나리오 · 영상 · 실제 대화 기록 (2026-11-07)

> 하네스([[hyun-harness]])가 '질문 → 두뇌 답'을 판정한다면, 이건 **에뮬 앱 전체**(귀·두뇌·내비·화면·말하기)를 사람이 쓰는 순서대로 돌리고 **영상 + 실제 대화 + 스크린샷**으로 남긴다. 사용자 요청 순서(11-07): 네비 모드(반반) → 교통상황 → 휴게소 소개 → 취향 질문 → 웹툰 순위 → 영화 순위 → 스포츠 기사 → 인물 기사.

## 실행

```bash
python -I -X utf8 tools/hyun_e2e/run.py --servers --video --voice  # 시연 영상(제출·PT) — 전체 13개 약 13분 + 영상 만들기 1분
python -I -X utf8 tools/hyun_e2e/run.py --servers --video          # 전체 — 서버를 시험 데이터로 새로 띄우고 녹화
python -I -X utf8 tools/hyun_e2e/run.py --servers --ids e02,e03    # 일부(개발 중) · --keep-data 이어서
python -I -X utf8 tools/hyun_e2e/run.py --report records/hyun-e2e/<폴더>   # 보고서만 다시
python -I -X utf8 tools/hyun_e2e/run.py --rebuild records/hyun-e2e/<폴더>  # 영상만 다시(슬라이드 글 고친 뒤 — raw/ 있어야, 약 1분)
```

- 전제: 에뮬 켜짐 · 현비서 앱 설치 · `adb forward tcp:8088 tcp:8088`(vconsole) · 현비서·받아 적기 서버는 **꺼 둔다**(`--servers`가 띄우고 끈다 — 이미 떠 있으면 멈춤)
- **시험 데이터는 따로** — `--servers`는 `HYUN_DATA=server/hyun/data-e2e`(매번 새로, git 제외)로 서버를 띄운다 → 사용자 실제 취향·대화·브리핑 순서를 건드리지 않는다(`hyun_server`·`taste`·`taste_refine`이 `HYUN_DATA`를 따른다)
- 시험 목소리: `tools/hyun_testaudio.py`(Windows SAPI 한국어 Heami → 16kHz PCM)로 "현비서야 …"를 만들어 `--es earfile` → 앱 귀(`Ear.feed`) → 받아 적기(Whisper) → 호출어 → 두뇌. 대화 창 안의 대답은 `--es say`(글) — **`--voice`면 글 대답(voice: false)도 목소리로**(시연 영상용 — 운전자 말이 들리고 앱도 실제로 귀로 듣는다, 11-08 13/13)
- 시험 중엔 에뮬 마이크(PC 마이크 연결)를 끈다(`emu avd hostmicoff`, 끝나면 on) — 방·스피커 소리가 대화 창에 들어가 엉뚱한 질문("비서")이 됐다
- 반반 화면: 현비서 창 폭으로 판정(`dumpsys` mBounds — 2칸 841px · 1칸 1706px) → 손잡이 ⋮ 끌기(`input swipe`)

## 시나리오 정본 `tools/hyun_e2e/cases.json`

- 케이스 = `id · title · why · steps · expect`. steps: `do`(restart · say{text, voice} · intent{extras} · gleo{action, param} · vc{args} · split{on}) / `wait`(reply · log{pattern, `from`: "last"면 직전 단계 뒤부터 — 같은 줄이 케이스에 두 번일 때} · tts · sec) / `shot`(캡션) · expect: `log`(앱 로그 정규식) · `server`(그 케이스 동안 서버 log.jsonl 줄 정규식)
- 두뇌는 실제 Claude(내 계정)라 답이 매번 다르다 → 판정은 **'했나'만**(경로 시작·카드 종류·사진·검색 모드·취향 기록 등). 말의 질은 영상·대화 기록을 사람이 본다
- 지금 13개: e00 시동 브리핑 · e01 네비 모드(말로 목적지 + 현비서 화면 지키기 `front|covered` + 1칸 내비 판 스크린샷 + 반반) · e02 교통 표 · e03 휴게소 소개(사진 · 표) · e04 취향 질문 · e05 웹툰 표지 · e06 영화 목록 · e07 야구 소식(검색) · e08 인물 기사(사진 · 표) · e09 주행 중 사고 정찰·휴게소 알림 · e10 글레오 상황 · e17 도착 추천 — 점심 곧 도착·내려서 카드 · e18 곧 도착 제안 '응' → 식당으로 경로(11-08 통과 `records/hyun-e2e/20261108-1613/`)
- 앱 로그 약속(러너가 읽음): `heard|` · `say|P<n>|` · `tts|done`(말 끝) · `card|제목|개수|kind=…|img=n|앞 3줄` · `event|` · `reply|<id>|{json}` · `ear|wake+text|` · `nav|`·`via|`·`route|`·`navi|routeStarted` · `scout|` · `gleo|` — 바꾸면 `run.py` `classify`·cases도

## 결과 `records/hyun-e2e/<YYYYMMDD-HHMM>/`

- `report.html` — 요약 표(판정·걸린 시간·응답 초) + 영상(자막 = 실제 대화, 시각 누르면 그 자리로) + 케이스별 판정·대화·스크린샷·단계
- `transcript.json`(전체 기록 — 케이스·대화 시각·판정·서버 답 원문) · `logcat.txt` · `server-log.jsonl` · `server.out` · `shots/*.jpg`
- `video.mp4`(git에 함께 — 13개 소리 포함 12분 37초 · 19MB) · `slides/*.png`(표지·케이스 슬라이드) · `raw/`(케이스별 원본 영상·에뮬 소리 `app-audio.wav`·운전자 목소리 `voice/*.pcm`·조각 영상 — **git 제외**). 영상 = §시연 영상 형식
- 기록 목록·판정 → 아래 §기록
- GitHub 사본: https://github.com/somanghao/hyunbiseo (private) — 앱·서버·시험·이 기록(영상 포함)·현비서 문서(보고서 `dashboard/hyun`·시안 `design_assets/hyun`은 제외). 갱신 = `tools/export_hyunbiseo.py <hyunbiseo 클론>`(git 추적 파일만 · 비밀 문자열 모양이 있으면 멈춤 · settings는 :hyun만) → 커밋·푸시. README 원본 `tools/hyun_export_README.md`(최신 기록·영상을 가리키게 같이 고친다). 클론은 따로 두지 않는다 — `gh repo clone somanghao/hyunbiseo <임시 폴더>` → 내보내기 → 커밋·푸시. 마지막 갱신 11-08 `11c949c`(시연 영상 13/13 기준)
- 보기: `python -I dashboard/serve.py` → http://localhost:8765/records/hyun-e2e/<폴더>/report.html (no-store 서버 — 영상·사진 상대 경로)

## 시연 영상 형식 (사용자 11-08 — 제출·PT 영상은 이 형식)

- **케이스마다 '설명 화면 → 실제 동작 화면'** — PPT를 영상으로 만든 것처럼, 먼저 **흰 화면 슬라이드**에 `케이스 N — 제목` · **상황**(차 상태: 정차/주행·목적지·시간대 등) · **내가 물을 말**("현비서야 강남역 가자")을 보이고, 바로 이어서 그 케이스의 실제 에뮬 화면(녹화 + 실제 대화 자막)
- 슬라이드 글의 정본 = `cases.json` 각 케이스 — 제목 `title` · 상황 `situation`(없으면 `why`) · 물을 말 = `say` 단계 `text`(목소리면 "현비서야 " + text) + 그 단계 `slide`(앞말 — "현비서가 물으면" 회색) · 말 없는 케이스는 `ask`(회색 메모)
- ✅ 구현(11-08, `run.py --video`) — 표지 8초(케이스 목록) → 케이스마다 슬라이드(5.5초 + 물을 말 하나에 0.8초) → 실제 화면(자막 + 소리). PIL로 흰 바탕 1280x720 맑은 고딕 → 조각마다 같은 형식(libx264 crf 26 · 30fps · AAC 48kHz)으로 인코딩 → concat `-c copy`. 같은 글을 `report.html` 케이스 머리에, 영상 바로가기는 슬라이드 자리로
- **소리** = 에뮬 프로세스 소리만(`tools/hyun_e2e/ProcAudio.cs` — Windows 프로세스 루프백, qemu 트리 · 현비서 목소리(Pleos TTS)·내비 안내) + 운전자 시험 목소리(귀에 넣은 그 PCM, 자리 = 앱 로그 `ear|feed|<이름>` 시각). 녹음기는 `run.py`가 처음에 .NET Framework `csc`로 빌드(`tools/hyun_e2e/.bin/`, git 제외). 에뮬 소리는 작게 잡혀(RMS 0.01) 큰 구간 기준으로 키운다(`app_gain`)
- **시각 맞추기** — logcat은 에뮬 시계, 영상·소리는 PC 시계. 시작 때 `date` 왕복으로 차이를 잰다(`clock` = 에뮬 − PC, 11-08 −1.12초 — 그전 영상 자막은 그만큼 일찍 떴다). 녹화 시작 = 멈춘 PC 시각(pkill 왕복 가운데) − 길이

## 기록

| 실행 | 결과 | 메모 |
|---|---|---|
| (지움) 0904 · 0906 · 0909 · 0912 · 0916 | 개발 중 부분 실행 | 반반 배치·교통 표·휴게소(다음 휴게소 '확인 못 함' → 경로 도로 맥락 추가로 '아마 기흥휴게소(추정)')·인물 사진 검색어 줄이기·주행 중 휴게소 답이 늦게 옴 → 케이스 고침 |
| (지움) 0919 · 0929 · 0939 | 11/11 · 9/11 · 11/11 | 0919: 끼어든 정찰 답을 질문 답으로 착각(→ `ask|id` 짝짓기) · 0929: **에뮬 워치독 재시작**(GNSS HAL 72초) → 앱·내비 경로 소실로 e05·e10 실패(→ 러너가 워치독을 '환경'으로 표시, e09가 목적지를 스스로 설정) · 0939: 새 긴 경로 정체를 줄줄이 알림(→ 정찰 90초 조용한 시작) |
| **`records/hyun-e2e/20261107-0949/`** | **11/11 통과 · 영상 7분 2초** | 최종 — 응답(말 → 답) 5.1~5.8초, 검색 모드 13초 · 휴게소 카드 "기흥휴게소 부산방향(추정)" 대표 메뉴 향천우동·라면·호두과자 · 300m 사고 = 갈림길 없음 "돌아갈 길은 없어요" · 워치독 없음 |
| `records/hyun-e2e/20261107-1112/` | 11/11 (영상 없음) | 확장 구조 리팩터 — 앱만 바꾼 상태 → [[hyun-architecture]] |
| (지움) 20261108-1553 · 1600 · 1605 · 1609 | 0/2 · 2/2 · 2/2 · 2/2 (e17·e18) | 1553: `places.search`가 서버 훅 이름 `search`와 겹쳐 두뇌 스레드가 죽음(→ `find_places` · 서버 시작 때 훅 인자 검사 `HOOK_ARGS` · 두뇌 스레드가 한 메시지 예외에 살아남게) · 1600: 시험기가 두 번째 routeStarted를 케이스 시작부터 찾아 경로 계산 중에 찍음(→ 대기 `"from": "last"`) · 취향 말('나 국밥 좋아해')에 장소 검색(→ 찾는 말만) · 시연 시계와 실제 시각이 섞임(→ 맥락 t·eta도 시연 시계) · 1605: 바꾸기 전 경로의 출발 답이 늦게 와 식당으로 바꾼 뒤 '코엑스까지 34분'(→ 목적지가 바뀐 뒤 온 이벤트 답은 버림) · 1609: 곧 도착 제안이 P1 쿨다운(출발 답 직후)에 묶여 운전자 대답이 앞섬(→ 제안을 들은 뒤 대답) |
| **`records/hyun-e2e/20261108-1613/`** | **2/2 · 영상 3분 10초** | 도착 추천 최종 — e17 곧 도착 '콩뿌리 콩나물국밥 143m, 바로 거기로 갈까요?' → '아니 내려서 볼게' → 도착 카드 5곳 · e18 '응 거기로 가자' → 아오내순대 코엑스몰점으로 경로(현비서 화면 지키기) · 서버 기록엔 장소 목록 없이 `kakao|모드|q=국밥|n=5` |
| **`records/hyun-e2e/20261108-1740/`** | **13/13 · 시연 영상 12분 37초(슬라이드 + 소리)** | 전체 기능 시연 첫 판 — `--video --voice`(글 대답 5개도 목소리 → 귀 → 받아 적기로 통과) · 응답 5.2~7.3초, 검색 모드 13초 · e09 300m 사고에 이번엔 '곧 갈림길에서 돌아갈 수 있는데, 우회할까요?'(경로가 매번 달라 갈림길 판정이 다름) · '비 오니 천천히' 덧붙임이 여러 번(두뇌 규칙 손볼 것) · 서버 오류 0 |
| **`records/hyun-e2e/20261107-1125/`** | **11/11 통과 · 영상 6분 58초** | 확장 구조 리팩터 최종(앱 + 서버) — 서버 오류 0 · 응답 시간 리팩터 전과 같은 범위 |
