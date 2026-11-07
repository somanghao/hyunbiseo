# 현비서 — 조수석 대화형 비서 (두뇌 = 내 Claude 계정 자동 · 세션은 선택 → [[hyun-brain]])

> 사용자 제안 2026-10-30: "에뮬로 나랑 대화하면서 목적지까지 조수석에서 보조·재밌는 대화상대 — 안전하고 재미있게" → "API 말고 현재 세션이 대신, 자주 대화해서 학습형 대화상대로". 형제 앱: 출발 전 점검 [[agent-app]] · 컨셉 [[idea-agent]] · 음성 [[pleos-sdk-speech]] · 내비 [[pleos-sdk-navihelper]]
> **2026-11-06 팀 회의로 메인 확정 — 하부 모듈 7(쉼표·튜토리얼·온기·캐릭터·오늘 할 일·엔터·브리핑) → [[hyun-modules]]**
> **폰 모드 = 짭비서**(같은 APK, 샤오미 실기 동작 — 대중교통·산책·업무중·리마인더·위젯·흰 화면) → [[hyun-phone]]

## 성장 설계 (정본 = 대시보드 `#hyun` 탭 · `dashboard/hyun/plan.html`)

- **캐릭터**: 옆자리 친구 같은 비서, 존댓말 친근체 · 기분 색 5(calm·happy·fun·care·alert) · 모드 5(출근·퇴근·장거리·동승자·심야) · **관계 성장 4단계**(처음 만남 → 알아가는 중 → 단골 → 찐친, 밈은 3단계부터, 반말은 허락 시만)
- **말하기 우선순위**: P0 안전 경고(앱 규칙, 두뇌 X, 즉시 끊음) > P1 주행 필수 > P2 편의 제안 > P3 관계·재미 — 타이밍 게이트(회전 300m 전 침묵 = `turnBusy()` 구현됨 11-06 · 운전자 발화 중 대기 · 쿨다운 P1 30초/P2 3분/P3 10분 · 수명 지나면 버림). **구현 = `Speaker.kt`**(아래 §말하기 스케줄러) — 회전 300m 전 침묵(TBT 게이트)만 구현됨
- **정보 소스 순서**: 차 → 경로 → 운전자 → 관계 → 환경 → 문화(트렌드). 제외: 운전자 카메라(API 없음)·커뮤니티 크롤링
- **운전자 상태**: 수동 신호(연속 주행·시간대·급제동·정체·응답 지연·발화 길이·RMS·"졸려") + 능동 체크인(질문·끝말잇기 반응 속도) → 졸음·스트레스·기분·인지 부하 → 말투·빈도·휴식 제안. 진단이 아니라 추정, 묻는 말투로
- **트렌드·밈**: 매일 07시 수집 → 거르기 → 카드(뜻·훅·수명·위험도) → 개인화 → 1대화 1카드 → 반응 학습. 낡은 밈은 수명으로 은퇴
- **기억 6층**: 프로필 · 루틴 · 에피소드 · 후속 질문 · 선호 피드백 · 관계 지표 — 도착 때 두뇌가 기억 정리
- 결정 대기: 반말 단계 · 트렌드 소스 범위 (메인·두뇌·캐릭터 비주얼은 11-06 정해짐 → [[roadmap]] §결정 대기)

## 구조

```
에뮬 앱 android/hyun (귀 STT · 입 TTS · 눈 = 속도·기어·DO·목적지·배터리·주소·날씨 · 안전 규칙·폴백)
   │ POST /hyun/in {kind: talk|event, text, ctx}          ▲ GET /hyun/reply?id= (long-poll)
   ▼                                                      │
PC 서버 server/hyun/hyun_server.py (127.0.0.1:8790, 에뮬에선 10.0.2.2) — inbox · 답 · data/(log.jsonl · memory.json · tasks.json, git 제외)
   ▲ GET /hyun/wait (long-poll)                           │ POST /hyun/answer
   │                                                      ▼
Claude Code 세션(두뇌): wait.py를 run_in_background로 띄워 두고(대기 중 토큰 0) → 말이 오면 say.py로 답 → 다시 wait.py
```

- **두뇌 셋**(세션 → 내 Claude 계정 `claude -p` → API) · 정보 통로 · 로그인 · 약관·비용 → [[hyun-brain]]
- 감지 방식 근거 = polling-strategy 스킬(단일 inbox·단일 소비자 → long-poll + bg blocking). 실측 응답 지연: 말/이벤트 → 답 도착 **약 10~13초**(세션 한 턴)
- 앱은 `/hyun/health`로 두뇌 상태 표시(두뇌 연결 · 자리 비움 · 서버 없음). 두뇌가 없거나 늦으면(말 45초·이벤트 30초) **규칙 문장으로 대신** — 남은 거리·시간·배터리·시각은 앱이 직접 답한다(`offlineAnswer`·`fallbackEvent`)
- 학습: `--remember`로 쌓은 사실은 `server/hyun/data/memory.json` → 앱 정차 화면 "현비서가 기억하는 것" 칩. 다음 세션은 이 파일 + `log.jsonl` 끝부분을 읽고 이어간다

## 차 화면 — 조용하게 (사용자 2026-11-06 "불필요한 정보 빼기")

- 머리줄 = 이름 + 점 + "Claude"(문제일 때만 "로그인 필요"·"서버 없음") · 오른쪽은 알릴 것(조용히 모드·회전 앞)만 — 기어·속도는 계기판 몫
- 목적지가 없으면 위 막대를 그리지 않는다("어디로 갈까요?"는 말로) · 구슬 아래 "대기" 글자 없음(듣는 중·생각 중만) · 우선순위 배지·줄 선 말·두뇌 방식은 개발용 `--ez debug true` · 안전(P0)만 늘 붉은 "안전" 배지 · 마이크 옆 안내 글은 듣는 중("말씀하세요")만
- **차 가로 = 2단**(`HyunView.drawCar`, 사용자 11-06 "캐릭터 작게·목록 10개 한눈에·표가 맨 위부터·내 질문·답변은 캐릭터 밑"): 왼쪽 24% = 이름·연결 점 → (조용히 모드 등 알림) → (목적지 두 줄 + 진행선) → 구슬(작게) → 내 질문(작게) → 현비서 답(표가 있을 때) → 마이크 → [상시 듣기|눌러서 말하기] · 오른쪽 76% = **표가 화면 맨 위부터 끝까지** · 표가 없으면 현비서 답을 오른쪽 위에 크게(주행 중 3줄·정차 7줄) + 맨 아래 "현비서가 아는 것" 칩
- **캐릭터 3종**(`HyunView.character`, 앱 설정 `character`, 기본 human): 사람 비서(`assets/chars/sec_*` — 정차는 상반신 포즈: 평소 portrait · 생각 think · 듣기 tablet · 말하기 guide/기쁨 clap · 걱정 rest, 주행 중엔 SD 얼굴 5종) · 젤리 구슬(`orb_*` 3D 에셋 8표정) · 빛 구슬(코드로 그림) — 마이크 오른쪽 ⇄ 버튼 또는 말("캐릭터 바꿔줘"·"비서로"·"구슬로") · 그림은 뒤에서 절반 크기로 읽음 · 원본 = `design_assets/hyun/`(README 매핑)
- **캐릭터 그림 교체**(design_assets → 앱): `android/hyun/src/main/assets/chars/`에 짧은 이름으로 복사 — `human/sec_a_main_portrait.png`→`sec_portrait.png` · `human/sec_a_pose_<think|tablet|guide|clap|rest|greet>_1024.png`→`sec_pose_<…>.png` · `human/sec_a_sd_face_<calm|happy|fun|care|think>_512.png`→`sec_sd_<…>.png` · `faces_dark/hyun_face_<calm|happy|fun|care|alert|listen|think|speak>_dark_1024.png`→`orb_<…>.png` → 다시 빌드(앱은 절반 크기로 읽음 · 이름 목록 = `HyunView.charImages`)
- **그림 목록**: card에 `images[]`가 있으면(웹툰) 5×2 표지 격자 — 순위 배지(1~3위 기분 색·나머지 흰색) · 제목 두 줄 · 표지는 앱이 URL로 받아 캐시
- 새 질문이 들어오면 이전 카드를 지운다(하네스 화면 판정)
- **반반 화면 = 네비 모드**(11-07 `HyunView.drawCarNarrow`): 앱 영역 2칸(현비서 창 폭 < 높이×1.15 — 2칸 841px)이면 세로로 쌓는다 — 머리줄(이름·연결·알림) → **길 카드**(목적지 · 남은 km·시간(60분 넘으면 "3시간 52분") · 도착 · 진행선 · 경유지 · 내비 교통 한 줄 `trafficLine`) → 캐릭터 + 내 말 · 현비서 답(칸에 맞게 글자 줄임) → (정차) 카드가 남은 자리 전부 → 아래 가운데 마이크·캐릭터·듣기 모드. 2칸은 운전자가 ⋮를 끌어서만 된다(앱이 `FLAG_ACTIVITY_LAUNCH_ADJACENT`로 내비를 옆에 띄우기 = 안 됨, 11-07 에뮬)
- **소개 카드**(`card.kind = "info"`, `HyunView.infoCard`): 왼쪽 사진(40%, 꽉 차게 자름) · 오른쪽 제목 + "라벨 — 값" 표(라벨에 "추천"이 있으면 기분 색) · 맨 아래 출처·사진 라이선스 — 휴게소·인물 기사. 사진 = 서버가 위키미디어 공용에서 찾아 `/hyun/img?u=`로 중계(앱은 서버 주소를 붙여 받음) → [[hyun-brain]] §카드
- **교통 표**: 길·교통 질문엔 서버가 앱 맥락(`trafficAhead` 6개·경유지)으로 목록 카드 "앞길 교통 · ○○까지 N분(도착)"를 만든다(두뇌가 숫자를 옮기다 틀리지 않게)
- **목록 카드**(정차만, 주행 중엔 숨김): 두뇌 답 `card {title, source, items≤10}` → 한 칸 번호 목록(1~3위 강조, "제목 — 작가"면 작가 옅게) · 누르면 닫힘 · 3분 뒤 닫힘(`CARD_MS`) · 말은 앞 3개만 · 실측: 웹소설 TOP 10, 한국시리즈 현황 5줄

## 귀 — 상시 듣기 (2026-11-06, 차)

- **Pleos STT(ON_DEVICE)로는 에뮬에서 못 알아듣는다**: 에뮬 가상 마이크가 최대 -38dBFS로 작게 들어와 말 시작을 못 잡음(같은 녹음을 Whisper는 정확히 "지금 몇 시야?") · 첫 요청 준비 2.8초 · `sendAudio` 녹음 주입은 시스템 음성 서비스를 SIGSEGV로 죽임(그래서 `PleosVoice.feed` 막아 둠) → [QUICKMAP-pleos](../QUICKMAP-pleos.md) §3 #18
- **앱 귀 `Ear.kt`**: AudioRecord(VOICE_RECOGNITION 16kHz) 상시 → 에너지 VAD(잡음 바닥 ×3, 3프레임 시작·0.7초 조용하면 끝·최대 12초·앞 0.3초 포함) → 음량 정규화(최대 진폭 → 0.8, 최대 30배) → PC **받아 적기 서버** `server/hyun/stt_server.py`(faster-whisper large-v3 GPU, 8791, 0.3~0.5초, `hotwords="현비서"`·temperature 0·max 64토큰·유튜브 상투문 버림). 현비서가 말하는 동안 귀는 멈춤(+0.3초)
- **호출어** "현비서야 …" — 구간 어디서든 마지막 호출어 뒤 말만 대화로(현/형/혁/헌/변/편/연/견/션/선/련/헨/핸/행 + 비/빈/피 + 서/사/소/써). 호출어만이면 "네, 말씀하세요"(listen) → 대화 창 10초. 호출어 없는 말은 버리고 앱 로그엔 글자 수만(받아 적기 서버 콘솔엔 진단용 전문이 찍힘 — 상용 전 끌 것)
- **듣기 모드 전환**(마이크 왼쪽 두 칸 버튼 · 음성 "상시 모드"/"버튼 모드", 앱 설정에 저장): 상시 듣기 = 귀 늘 켬 · 눌러서 말하기 = 버튼 누를 때만 귀를 켜고 창이 닫히면 마이크를 놓음(상단바 마이크 표시 꺼짐)
- 받아 적기 서버가 없으면 예전대로 Pleos STT(버튼) · 앱 health 10초마다 `/stt/health`
- 대화 보강: 두뇌 답이 3.5초 넘으면 맞장구 한 번("음, 잠깐만요") · "조용히 해 줘" → 10분 P2·P3 끔, "이제 말해도 돼" 해제(앱이 바로, ctx `quiet`) · 진단 `stt|level|max=` 로그(0이면 소리가 앱까지 안 옴)
- **다른 화면에서도 듣기**(11-07): 귀를 켤 때 마이크 포그라운드 서비스 `EarService.kt`(type microphone, 알림 "현비서가 듣고 있어요")도 켠다 — Android 9+는 뒤로 간 앱 마이크를 무음으로 만든다. 새로 켜기는 앱이 앞에 있을 때만 되므로(API 34) `onResume`의 `fg`일 때만 시도, 귀를 놓으면 끔. 실측: 설정 화면으로 덮어도 procState 4(포그라운드 서비스)·녹음 세션 `silenced:false` · 눌러서 말하기 모드면 대화 창 동안만 켜짐. ⚠️ 듣기 모드는 앱 설정에 남는다 — 11-06 끝에 눌러서 말하기(`earAlways=false`)로 둬서 11-07 시작 때 귀가 꺼져 있었다("상시 모드로 해 줘"로 복구)
- **한 번 다시 묻기**(11-07): 대화 창에서 말소리는 있었는데 받아쓰기가 빈 글자(잡음·유튜브 상투문 걸러짐) → "잘 못 들었어요. 한 번만 다시 말해 주세요"(listen) → 또 빈 글자면 조용히 닫음(`reask`, 창마다 한 번 — 제대로 들으면 초기화). Pleos STT는 `onHearStart`가 있었을 때만. 글자는 왔는데 뜻이 안 통하면(잡음을 "비서실"로 받아 적음) 두뇌가 "혹시 …라고 하셨어요?"로 되묻는다(`EXTRA_RULES`)
- **귀 시험(마이크 없이)**: `python -I -X utf8 tools/hyun_testaudio.py <이름> "현비서야 강남역 가자"`(Windows SAPI 한국어 Heami → 16kHz PCM → `server/hyun/data/testaudio/`, git 제외) → 앱 `--es earfile <이름>`이 서버 `/hyun/testaudio`에서 받아 `Ear.feed` → 받아 적기 → 호출어 → 두뇌. 실측 받아쓰기 0.46~0.78초. 실제 마이크 경로는 사람이 말해서 따로 본다
- **글레오로 부르기**(Gleo Interface, 11-07): `assets/schema.json` 3액션 + `HyunActivity` intent-filter — `ASK_HYUN {question}`(글레오엔 바로 success·mode beep, 답은 현비서 목소리 — 두뇌 5초가 7초 제한에 빠듯해서) · `HYUN_STATUS {item: all|trip|traffic}`(영어 한두 문장: 목적지·남은 km·분·도착 + `Scout.english()`) · `HYUN_QUIET {mode: quiet|talk}`(조용히 10분). 글레오가 설치 때 `[Schema] Loaded schema.json from …hyun` 확인 · 흉내 `am start --user 10 -a ASK_HYUN --es ACTION_PARAM '{"question":"앞길 어때?"}'`(응답 4ms) · 실제 목소리 E2E 미시험
- 미니 마이크 실측(11-06): "현 비서야 지금 몇 시야?" 2.6초 → 받아 적기 0.46초 → 규칙 답 · 호출어 → "네, 말씀하세요" → "영화 인기순 3편 말해봐" → 두뇌 · 실행 `~/.cache/faster-whisper-venv/Scripts/python.exe -I -X utf8 -u server/hyun/stt_server.py`(venv = faster-whisper + nvidia-cublas-cu12 + cudnn 9)

## 띄우기 런북 (사용자가 "띄워" 할 때만 — auto-memory `hyun-brain-loop-on-request`)

```bash
# 0) 에뮬(Pleos_Connect_v2.1) — 마이크를 바꿨으면 콜드부팅 후 hostmicon → [[pleos-emulator]] §마이크
"$LOCALAPPDATA/Android/Sdk/emulator/emulator.exe" -avd Pleos_Connect_v2.1 -no-snapshot -no-boot-anim -gpu host -scale 0.5   # run_in_background
adb -s emulator-5554 emu avd hostmicon
# 1) 현비서 서버(8790) — 켜지는 순간 내 Claude 계정 두뇌가 자동으로 답한다(구독 사용량) · 시작 때 claude -p 예열(~4초) · 정보 통로 10분 예열
python -I -X utf8 -u server/hyun/hyun_server.py                       # run_in_background
# 2) 받아 적기 서버(8791, GPU) — 상시 귀용 · 콘솔에 받아 적은 전문이 찍힌다(진단용, 상용 전 끌 것)
~/.cache/faster-whisper-venv/Scripts/python.exe -I -X utf8 -u server/hyun/stt_server.py   # run_in_background
# 3) 앱 설치·실행(빌드는 android/에서 JAVA_HOME=~/.jdks/corretto-21.0.11 ./gradlew :hyun:assembleDebug)
adb -s emulator-5554 install -r -g android/hyun/build/outputs/apk/debug/hyun-debug.apk
adb -s emulator-5554 shell am start --user 10 -n io.github.somanghao.makecar.hyun/.HyunActivity
# 4) 대시보드(8765) — 보고서 dashboard/hyun/report.html
python -I dashboard/serve.py                                            # run_in_background
```

- 확인: `curl -s 127.0.0.1:8790/hyun/health`(mode=claude) · `curl -s 127.0.0.1:8791/stt/health` · 폰(짭비서)은 `adb reverse tcp:8790 tcp:8790` → [[hyun-phone]]
- 끝낼 때: 현비서 서버·받아 적기 서버를 끈다(켜 두면 앱 이벤트에도 구독 사용량 · GPU 점유)
- 사람이 직접 답하는 세션 두뇌가 필요할 때만 아래 wait/say 루프

## 두뇌 운영 (세션이 하는 일)

```bash
python -I -X utf8 -u server/hyun/hyun_server.py         # run_in_background — 서버(세션이 없으면 내 Claude 계정 두뇌가 자동으로 답함 → [[hyun-brain]])
python -I server/hyun/wait.py                           # run_in_background — 말이 오면 끝나며 출력
python -I server/hyun/say.py <id> "답" [--listen] [--mood calm|happy|fun|care|alert] [--prio 1|2|3] [--remember "사실"] [--name "호칭"] [--nav "이름|위도|경도"] [--route reroute|fast|highway|free|comfort] ; python -I server/hyun/wait.py   # 답 + 다음 대기를 한 번에(run_in_background)
# 폰(짭비서) 추가: --mode transit|walk|work · --remind "+분|내용" / "HH:MM|내용" · --todo "내용" · --done "글자 일부" → [[hyun-phone]]
```

- `wait.py`는 메시지마다 단계(`[P1]`·`[P2]` · 대답은 `[대답(P1 자리)]`)를 붙여 보여준다 — 이벤트 단계 표 `EVENT_PRIO`

- 출력 예: `#3 18:47:11 이벤트:depart` + `D 60km/h 주행규제 · →강남역 13km 39분(도착 19:26) · 운전 0분 · 배터리 100% · 수정구 시흥동 · 10℃ 비50%`
- ⚠️ 루프가 도는 동안 세션 턴을 오래 잡지 않는다 — 앱은 이벤트 30초·말 45초만 기다리고 규칙 문장으로 대신한다(확인용 `sleep`·긴 문서 작성 중 폴백이 나간 실측). 이미 지난 메시지는 `say.py <id> ""`로 비운다
- 여러 개가 쌓였으면 **가장 최근 id에 답한다** — 서버가 그보다 앞선 미답 메시지를 건너뜀(skipped)으로 처리, 앱은 그 답을 대신 말하지 않는다
- 새 세션에서 재개: 서버·wait 두 개를 띄우기 전에 `memory.json`과 `log.jsonl` 마지막 20줄을 읽어 호칭·취향·지난 대화를 잇는다

## 경로 정찰 — 최우선 기능 (사용자 결정 2026-10-30)

- **정보원 1순위 = 내비 `NaviHelper.getRouteStateInfo()` → `trafficStatus`** — 내 경로 위 이벤트(사고·재난·도로 파손·공사·행사, 좌표·설명·시작/해제 시각) + 정체 구간(도로명·시작/끝 교차로·거리·평균 속도·지연, `offset` = 경로 시작 기준 m) + 요약(총 정체 m·초·평균 속도). 주의 구간 `onCautionPointInfo`(역주행·미끄러움·도로 파손·사고 잦은 곳 등, 남은 m). 도착 지연 = 내비 남은 시간이 **바뀔 때만** 비교(+5분)
- 주기(**적응형**, 11-07 `nextScanSec`): 정차 120초 · 고속 70km/h+ / 다음 분기 2km 안 / 위험(위험도 2+) 3km 안 25초 · 시내 저속(40 미만) 90초 · 그 밖 60초 + 새 경로·경유지 변경 3초 뒤 + 안내 중 앱 켜면 1.2초 뒤(시동 브리핑 '교통'보다 먼저). `--ei scoutsec N` 고정(0 = 적응). 정찰마다 `getTBTInfo`도. 판정 `Scout.kt`, 처리 `HyunActivity.report`→`reportNow`
- 처리: **사고·재난·역주행 1.5km 이내 → 앱이 즉시 경고(P0, 두뇌 X)** → 그 뒤 두뇌에 `traffic` 이벤트(`findings`·`appWarned`). 위험도 2(공사·파손·심한 정체 500m/3분 이상·도착 +5분) → 두뇌가 알리고 우회 제안. 위험도 1(짧은 정체·서행) → 맥락 `traffic` 요약에만. **새 경로가 선 뒤 90초(`Scout.CALM_MS`)는 사고·재난만 바로**, 나머지는 '본 것'으로 적고 요약으로 출발 한마디에 — 경로가 설 때(`onRouteStarted`·`onWaypointChanged`)와 목적지를 정할 때 `Scout.reset()`. ('첫 정찰 한 번'으로 셌더니 옛 경로의 늦은 정찰 답이 그 한 번을 써 버려 새 긴 경로의 정체를 줄줄이 알렸다 — 11-07 대전역 3번 실측) · 안내 중(`ROUTE_GUIDING`) 상태만 정찰
- **두뇌 맥락 `[길·교통]` 블록**(`brain_common.road_block`): 목적지·남은 km·분·도착 예정·현재 위치(주소)·주행/정차·속도 + 경로 교통 요약(`Scout.summary`) + 앞쪽 정체·사고 가까운 순 3개(`Scout.ahead` → ctx `trafficAhead`, 도로명·거리·평균 속도) — "교통 어때?"에 도로명까지 답한다. 목적지가 없으면 "어디로 가세요?"
- 우회: 두뇌가 `say.py --route fast|reroute|highway|free|comfort`(자동 두뇌는 답 JSON의 `route` · 목적지는 `nav_name`·`nav_lat`·`nav_lng` → 서버가 `nav{}`로 바꿔 같은 길로) → 앱이 `changeRouteOption`/`requestReRoute` → 6초 뒤 `rerouted` 이벤트(`beforeMin`·`afterMin`)로 결과 보고
- 두뇌 말하기: 무엇이·어디(몇 km 앞, 도로명)·얼마나(분)를 한 문장 → 우회는 질문으로(`--listen`), "응"이면 `--route`. 앱이 이미 경고했으면(`appWarned`) 경고를 되풀이하지 말고 우회·속도만. 출처는 "내비 교통정보 기준" — 맥락에 없는 사고·우회로를 지어내지 않는다
- 시연 주입(에뮬 내비엔 정체는 오지만 사고 이벤트는 없었다): `--es traffic "accident|1200|강남대로 신논현역 부근 2차로 사고"` · `--ei etaup 8` · `--ez scan true`(즉시 정찰) — 로그·두뇌 출력에 `[시연 주입]` 표시
- 보강(키 필요, 미구현): 공공 돌발 — 국가교통정보센터 ITS·경찰청 UTIC·서울 TOPIS(`AccInfo` 공개 sample 키는 503으로 실패, 2026-10-30) → 서버 정찰 스레드로 경로 주변만 걸러 같은 `traffic` 이벤트로
- **우회 타이밍 게이트**(v2, 11-07 `detour`): TBT 목록(거리 = 앞 안내에서부터의 **구간** → 앱이 누적, 받은 뒤 달린 만큼 뺌, 60초 넘으면 모름)에서 첫 '분기'(직진·도착·경유지·터널·요금소·휴게소 제외 = `NOT_BRANCH`)를 위험 앞 거리와 비교 → `possible`(분기 + 150m < 위험 — "N m 앞 갈림길에서 돌아갈 수 있어요, 우회할까요?") · `none`(목록이 위험까지 덮는데 그 앞 분기 없음 — 우회 묻지 않고 "돌아갈 길은 없어요, 차로 지키고 천천히", 앱 P0 문장에도) · `unknown`. finding JSON에 `detour`·`branchM`. TBT가 10초 넘게 묵었으면 `getTBTInfo` 후 0.8초 뒤 판정(내비는 바뀔 때만 준다). 실측: 사고 1.2km = possible@300 · 300m = none@689
- **우회 되돌리기**(v2): 바꾸기 전 옵션(`routeOption` ← `onRouteStateInfo`, 없으면 RECOMMENDED)을 기억 → 6초 뒤 남은 시간이 1분도 안 줄면 그 옵션으로 `changeRouteOption` + `rerouted` `reverted:true`("바꿔 봤는데 시간이 안 줄어서 원래 길로 돌렸어요"). 재탐색(reroute)은 되돌리지 않음. 실측 fast 21→21분 → RECOMMENDED. 대안 경로 미리보기 API는 없음(바이너리)
- **경로 도로**(11-07 `Scout.roads` → ctx `routeRoads` → [길·교통] "경로 도로(내비, 가는 순서): 달래내로 → 경부고속도로 → …"): 정체 구간 목록이 경로 전체를 덮어 어느 고속도로·방향인지 보인다 — "다음 휴게소" 판단 재료
- **휴게소 소개**(11-07): ① 말("다음 휴게소 소개해 줘") → 검색 모드 + 경로 도로 → 소개 카드(위치·대표 메뉴·편의·취향 추천, 추정이면 제목 "(추정)") ② TBT에 휴게소 진입(`TURN_ENTER_SERVICEAREA`·`TURN_RESTAREA`)이 3km 안 → `restarea` 이벤트(P2, 길마다 한 번, 서버가 검색 모드) · 주행 중엔 말만(카드는 정차 때) · 시연 `--es restarea "기흥휴게소|2500"` · 실측: 시흥동→대전역에서 "아마 기흥휴게소(부산방향)" 카드 + 참고 사진
- **경로가 바뀌면 도착 지연 기준 초기화**(`Scout.resetEta` — 새 경로·경유지 변경·옵션 변경): 경유지를 넣자 길어진 22→53분을 "+33분 지연"으로 알렸다(11-07)
- **경유지**(11-07): 두뇌 답 `via_name/lat/lng`(세션 `say.py --via`) → 안내 중이면 `addWaypoint`(빈 자리 FIRST→SECOND, 최대 2, 같은 이름 중복 무시) · 목적지와 같이 오면 `requestRouteWithWaypoints` · 목적지 없으면 그곳을 목적지로 · route `clear_via`(`removeWaypoint(ALL)`)·`cancel`(`cancelRoute`) · 남은 거리 = `onWayPointInfo`·`onDrivingInfo.waypoints` → ctx `via[]` → [길·교통] "경유지:" · 도착 `onWayPointArrived` → `waypoint` 이벤트(P1). 화면 띠 "코엑스 거쳐 강남역"
- **목적지 없이 출발**: 20초 넘게 달리면 주행당 한 번 `no_dest`(P1, listen) · **출발** = 정찰 먼저 → 2.5초 뒤 `depart`(교통 요약 포함). 주행 필수 이벤트(P1)는 두뇌가 prio를 낮춰도 P1(`onReply`)
- 남은 것(정본 → [[roadmap]] §진행 중): 실주행 TBT 확인 · 공공 돌발 · 지도 뷰

## 현비서 말하기 규칙 (두뇌가 지킨다)

> **정본 = `server/hyun/persona.md`** — 성격·금기·기분 색·P0~P3 단계와 `--prio`·주행 중/정차 길이·지어내지 않기·안전 우선·폰 모드 규칙. 두뇌는 루프를 시작할 때 이 파일을 읽는다. 아래는 persona.md에 없는 보충만.

- 목적지 좌표(`--nav`)가 애매하면 되묻는다. 정차 땐 화면 이야기도 된다. 졸림엔 휴식·환기가 1순위, 끝말잇기는 잠 깨는 보조일 뿐
- **놀이**: 끝말잇기·스무고개·넌센스 퀴즈·초성 퀴즈(음성으로) — 상대가 원할 때, 주행 중엔 쉬운 것만
- **이벤트**: `hello`(앱 시작) · `depart`(출발) · `halfway`(절반) · `near`(1km 전) · `arrived`(도착) · `long_drive`(연속 90분, 이후 30분마다) · `quiet`(주행 중 12분 조용 — 기억을 꺼내 가볍게 한마디, 매번 말 걸지 않아도 됨: 답 없이 넘기려면 짧은 공감 한마디) · `no_dest`(목적지 없이 20초 주행 — 어디로 가는지 묻기) · `waypoint`(경유지 도착, `viaName`)
- **학습(`--remember`)**: 호칭·취향(음식·음악·응원팀)·자주 가는 곳·말투 피드백("길게 말하지 마")을 짧은 구절로. 민감 정보(계좌·주민번호·건강 상세)는 저장하지 않는다

## 앱 (`android/hyun/`, 패키지 `io.github.somanghao.makecar.hyun`, 라벨 "현비서")

| 파일 | 역할 |
|---|---|
| `HyunActivity.kt` | 흐름 전부 — `isCar`로 차/폰 갈림 · NaviHelper(목적지 설정 `requestRoute`·`onRouteStarted`·`onDrivingInfo`·`onDestinationArrived`·현재 위치 주소) · Vehicle 배터리 · Open-Meteo · 이벤트 판정(1초) · 말하기 `say`→`Speaker.offer`→`pump`→`speakNow` · 경로 정찰 주기(`scan`·`nextScanSec`)·`report`/`reportNow`·`detour`·`reroute`·`afterReroute`(되돌리기) · 경유지 `addVia`·`goTo(via)`·`cancelTrip` · 글레오 `onGleo` · 다시 묻기 `reask` · 귀 시험 `feedEar` · 폰 모드·리마인더(`setMode`·`deskTick`·`refreshTasks`·`ring`) · 폴백 · 시연 인텐트 |
| `Speaker.kt` | 말하기 스케줄러 — `offer`/`next(Now)`, 정책 `Policy`(PARKED·DRIVE·TRANSIT·WALK·WORK = 단계별 쿨다운·수명·P3 허용) · `PhoneMode` · 이벤트 단계 `eventPrio` |
| `Voice.kt` · `Sense.kt` | 귀·입 / 눈 인터페이스 — 차 `PleosVoice`·`CarSense`(AAOS 속도·기어·DO), 폰 `PhoneVoice`·`PhoneSense` → [[hyun-phone]] |
| `Ear.kt` | 상시 귀(차) — 마이크 직접·VAD·정규화·받아 적기 서버 POST · `ping` · 시험 주입 `feed` · 듣기 모드는 `HyunActivity.ensureEar` → §귀 |
| `EarService.kt` | 마이크 포그라운드 서비스(차) — 뒤로 가도 귀가 듣게, `start`(앱이 앞일 때만)·`stop` · 알림만 맡고 귀는 Activity가 든다 |
| `assets/schema.json` | Gleo Interface 3액션(ASK_HYUN·HYUN_STATUS·HYUN_QUIET) — 처리 `HyunActivity.onGleo` |
| `Desk.kt` · `QuickActivity.kt` | 폰 전용 — 캘린더·배터리·이어폰·진동 / 빠른 대화 창·홈 위젯 `QuickWidget`·상주 알림 → [[hyun-phone]] |
| `Scout.kt` | 경로 정찰 판정 — `trafficStatus` 이벤트·정체 → 위험도 1~3 `Finding`(이미 말한 키 제외, 첫 정찰은 3만) · 요약 문장 · 가장 가까운 위험 `nearestM` · 글레오용 `english()` · 주의 구간 1.5km · 도착 +5분(`resetEta`) · 시연 주입(`demo`·`demoEta`) |
| `Link.kt` | 서버 HTTP(보내기·답 long-poll·health·memory·할 일 `tasks`/`fired`) |
| `Trip.kt` | 남은 거리 = 내비 값 기준 + 속도 적분(에뮬은 GPS가 안 움직여 내비 값이 고정) · 연속 주행 시간 |
| `HyunView.kt` | 차(가로·어두움): 목적지 띠·자막·정차 시 기억 칩·큰 마이크 / 폰(세로·흰색): `drawPhone` 3단 + `목록` 덮개 · 공용 캐릭터 `character()` — 유리 구슬·기분 색 오로라(`palette()`)·초승달 하이라이트·회전 테두리 빛(말할 땐 우선순위 색)·듣기 링(목소리 크기)·생각 혜성·`eyes()` 기분별 눈+말할 때 파형 입 |

### 말하기 스케줄러 (`Speaker.kt`)

- 줄(`Line`)마다 단계 `prio`, 대답은 `rank = min(prio, 1)`로 P1 자리. 대기열에서 (rank, 들어온 순)이 가장 앞선 것부터
- 대답은 쿨다운을 쓰지 않고, 같은 자리(rank)에선 대답이 먼저(2026-11-06 수정 — 쿨다운 걸린 안내가 운전자 대답을 막던 결함) · 회전 300m 앞 침묵 = `HyunActivity.turnBusy()`(직진·도착 제외, 같은 안내 45초 넘으면 풂)
- 끊기: 말하는 중엔 더 높은 단계가 끊되 P0이거나 지금 말이 P2 이하일 때만 · 듣는 중엔 P0만 · 생각 중엔 P2·P3 대기 · 수명 지난 줄은 버림 · 대답 아닌 말은 단계별 쿨다운
- 정책: 차는 정차 `PARKED`(쿨다운 없음) / 주행 `DRIVE`(P1 30초·P2 3분·P3 10분), 폰은 모드별(→ [[hyun-phone]]). P3 불허 정책이면 대답 아닌 P3는 들어오자마자 버림 · 같은 P0 문장 중복 제거

- 평문 HTTP는 `res/xml/network_security_config.xml`에서 10.0.2.2·127.0.0.1·localhost만 허용
- ⚠️ `adb shell am start --es say 강남역 가자`처럼 값에 공백이 있으면 **기기 셸이 잘라 첫 단어만** 간다(11-07: "날씨 먼저 …"가 "날씨"로) → `adb shell "am start … --es say '날씨 먼저 듣고'"`처럼 통째로 따옴표
- 11-07 추가: `--es via "코엑스|37.5116|127.0595"` · `--ez clearvia true` · `--es event waypoint|no_dest` · `--es earfile <이름>`(귀 시험) · `--ei scoutsec 0`(적응 주기로) · 글레오 흉내 `-a ASK_HYUN|HYUN_STATUS|HYUN_QUIET --es ACTION_PARAM '{…}'` · `--ez cancel true`는 처음 켤 때 내비 연결 뒤 한 번 더 취소
- 시연 인텐트: `--es say "강남역 가자"`(마이크 없이 말) · `--es dest 강남역 --ef lat 37.4979 --ef lng 127.0276` · `--es event halfway` · `--ei longmin 3 --ei quietmin 2` · `--ez cancel true` · `--ez listen true`(손 안 대고 대화 창) · `--ei turnm 250 [--es turntype TURN_LEFT]`(회전 앞 침묵 시험 — 속도로 다가감) · `--ez rawrec true`(다음 마이크 = 7초 원본 녹음 `filesDir/rec.pcm`, 소리 경로 진단) · `--es stt <url>` · ~~`--es sttfile`~~(Pleos 녹음 주입 — 서비스 크래시로 막음)
- 실행: `gradlew :hyun:assembleDebug` → `adb -s emulator-5554 install -r -g android/hyun/build/outputs/apk/debug/hyun-debug.apk` → `adb -s emulator-5554 emu avd hostmicon`(PC 마이크) → 주행 `python -I tools/vconsole.py gear Drive` · `speed 60`

## 실측 (2026-10-30, 에뮬레이터 v2.1.0)

| 항목 | 결과 |
|---|---|
| 서버 연결 | ✅ 에뮬 → `10.0.2.2:8790`. ⚠️ 서버가 HTTP/1.0(응답마다 연결 닫기)일 땐 앱이 `ProtocolException unexpected end of stream`을 자주 받아(서버는 처리 완료 — 같은 말이 두 번 들어감, 두뇌 상태가 "서버 없음"으로 깜빡여 이벤트가 폴백으로 샘) → **서버 HTTP/1.1 keep-alive + 앱 1회 재시도 + `cid` 중복 제거 + health 2회 연속 실패 때만 "서버 없음"**으로 해결(오류 0). 에뮬 셸 `nc`로는 처음부터 20/20 정상이라 앱 HTTP 클라이언트 쪽 상호작용 |
| 두뇌 왕복 | ✅ hello·depart 이벤트, "종혁이라고 불러줘. 강남역 가자" → 호칭 기억 + 목적지 설정. 이벤트 → 답 도착 약 10~13초 |
| NaviHelper 목적지 | ✅ `requestRoute(RouteInfo(lat,lng,…))` → 내장 내비가 경로 안내 시작(앱 영역을 내비가 차지 — 현비서는 뒤에서 계속 말함), `onRouteStarted(duration=2340, distance=13000)` · `onDrivingInfo`(남은 값) — **단위 m·초** |
| 주행 | ✅ VC `gear Drive`·`speed 60` → DO=true · 주행 화면(구슬·자막·목적지만) · 남은 거리 적분 감소 |
| TTS → 자동 듣기 | ✅ `listen` 답 뒤 STT 자동 시작 · ⚠️ STT는 말소리가 없으면 끝없이 `ready`로 기다린다 → 조용하면 8초·말 시작 뒤 20초에 접고, 마이크만 열려 있으면 먼저 말하기가 이긴다 |
| 강남역 한 바퀴 | ✅ hello → 목적지 → depart → halfway → near → quiet → arrived(VC `speed 0`·`gear Park`) — 도착 때 에피소드 기억 1줄. 에뮬 GPS가 안 움직여 내비 남은 거리는 13km 고정, 앱 적분으로 진행 |
| 경로 교통(내비) | ✅ 판교 → 강남역: `trafficStatus` 요약 5.2km·600초·평균 21km/h, 정체·서행 16구간(실제 도로명 — 달래내로·경부고속도로 대왕판교IC·서운로·서초대로), 사고 이벤트 0건 → 출발 브리핑에 요약 |
| 사고 → 우회 | ✅ 주입(1.2km) → 앱 즉시 경고(P0) → 두뇌 우회 제안(`--listen`) → "응 돌아가자" → `--route fast` 전송 · ⏳ 전후 남은 시간 비교 — 직후 에뮬 system_server 워치독 재시작으로 경로 소실([[pleos-emulator]] §시스템 재시작), 경로가 없으면 앱은 `rerouted`를 보내지 않는다 |
| STT 실인식(PC 마이크) | Pleos ON_DEVICE ✗(작은 입력 · 2026-11-06) → **앱 귀 + Whisper ✅**(미니 마이크 · 호출어·대화 창·두뇌 왕복) — §귀 |
| 두뇌 자동(내 Claude 계정) | ✅ `claude -p` sonnet 5.2초 · hello·영화 순위(영진위 어제 기준 출처 포함) — [[hyun-brain]] |
| 교통정보(코어 ④, 내 계정 두뇌) | ✅ 2026-11-06 "강남역 가자" → 두뇌가 좌표 설정(37.4979,127.0276) → 내비 13km·23분·통행료 700원, 정체 15구간 · "지금 교통 어때?" → "내비 교통정보 기준, 전체 1.6km 정체로 약 6분 · 700m 앞 달래내로 · 11km 앞 서초IC… 도착 21시 11분쯤, 빠른 길로 다시 볼까요?"(6.4초) · "응 빠른 길로" → `route fast` → 전후 23→23분 보고 · 사고 주입 1.2km → 앱 P0 경고 → 두뇌는 경고 되풀이 없이 우회만 물음 |
| 회전 앞 침묵 | ✅ 시연 주입 250m → 216m에서 게이트 on → 지나서 off(P2 대기) · 실제 `onTBTInfo` 거리 갱신은 주행 시험 필요 |
