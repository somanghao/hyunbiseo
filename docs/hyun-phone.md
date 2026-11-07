# 짭비서 — 현비서의 폰 모드 (샤오미 실기)

> 사용자 결정 2026-10-31: "폰은 짭비서 — 운전 상황이 아니라 **대중교통·산책·업무중** 모드, 수다·재미 빼고". 같은 APK·같은 두뇌, 기기를 보고 런타임만 갈린다. 두뇌 운영·서버·차 쪽 → [[hyun-app]] · 캐릭터·말하기 규칙 정본 → `server/hyun/persona.md` · 따로 뗀 저장소 → https://github.com/somanghao/jjapbiseo (private)

## 차 vs 폰 — 무엇이 갈리나

`isCar = packageManager.hasSystemFeature(FEATURE_AUTOMOTIVE)` 하나로 갈린다(`core/Hyun.kt` — 기능마다 `enabled(car)`로 차/폰에서 켤지 정함 → [[hyun-architecture]]).

| | 차 = 현비서 | 폰 = 짭비서 |
|---|---|---|
| 귀·입 (`Voice.kt`) | `PleosVoice` — Pleos STT/TTS ON_DEVICE | `PhoneVoice` — `SpeechRecognizer`(API 33+는 온디바이스 폴백) + `TextToSpeech`(한국어 없으면 `com.google.android.tts`) |
| 눈 (`Sense.kt`) | `CarSense` — AAOS 속도·기어·DO (android.car 클래스가 폰엔 없어 **차에서만 생성**) | `PhoneSense` — GPS·NETWORK 위치, 속도 2.8m/s 넘으면 D, 주소 = Geocoder |
| 그 밖 | NaviHelper·Vehicle·`Scout` 정찰 | `Desk.kt` — 캘린더(18시간)·배터리·이어폰·진동 |
| 서버 | `http://10.0.2.2:8790` | `http://127.0.0.1:8790` + `adb reverse tcp:8790 tcp:8790` |
| 화면 | 어두운 가로(야간 눈부심) | **흰 세로** — 상태바·내비바 아이콘 어둡게(`lightBars`) |
| 이름 | 현비서 | 짭비서(`botName`, 작업 목록 이름도). 런처 라벨은 아직 "현비서" |

## 폰 모드 3개 (`phone/PhoneMode.kt` → `voice/Speaker.kt` 모드별 `Policy` · 처리 `feature/PhoneFeature.kt`)

| 모드 | P2 쿨다운 | 수명 P0·P1·P2 | 특이 |
|---|---|---|---|
| 대중교통 `transit` | 5분 | 2분·2분·10분 | **이어폰 없으면 자막+진동만**(P0만 소리) · 목적지 800m 전 하차 알림 P0 |
| 산책 `walk` | 3분 | 2분·2분·10분 | 걸은 거리 `walkKm`·`walkMin` |
| 업무중 `work`(기본) | 15분 | 2분·3분·30분 | 방해 금지 — 먼저 꺼내는 건 일정·리마인더뿐 |

- 폰 정책은 전부 **P3 불허**(`allowP3=false`) — 수다·재미를 먼저 꺼내지 않는다. 단계 뜻: P0 놓치면 안 되는 시각(리마인더·회의 10분 전·하차) · P1 시킨 일·대답 · P2 챙겨주기(일정·배터리·우산). 라벨 `LABEL_PHONE` = 시각/할 일/챙김
- 모드 저장 = SharedPreferences `hyun`/`mode`. 앱 머리줄 칩 또는 두뇌 `--mode`로 바뀌고, 사용자가 바꾸면 `mode` 이벤트(P2)
- 폰은 이벤트가 `near`(800m)·`arrived`·`hello`·`mode`·`battery_low`뿐 — `quiet`·`halfway` 없음

## 할 일 · 리마인더 · 일정

- 저장 = 서버 `server/hyun/data/tasks.json`(git 제외). 두뇌 `say.py --remind "+10|빨래"` / `"15:00|회의 자료"`(epoch ms로 변환) · `--todo "내용"` · `--done "글자 일부"` → 답에 `tasks: true` → 앱 `refreshTasks()`가 `GET /hyun/tasks`
- 앱이 리마인더마다 Handler를 걸어 시각에 `ring` → P0 "알림이에요. ○○, 지금이에요." + 진동 → `POST /hyun/tasks {fired}`. 10분 넘게 지난 건 조용히 처리
- `deskTick`(30초): 이어폰 상태 · 배터리 20% 이하·미충전 → `battery_low` · 캘린더 일정 1~10분 전 → P0 "N분 뒤 ○○, △△에서예요."
- ⚠️ **앱 프로세스가 살아 있을 때만 울린다** — 포그라운드 서비스 없음

## 다른 앱을 보면서 — 빠른 대화 (`QuickActivity.kt`)

- 투명 바닥 카드(테마 `@style/Quick`, 별도 task·최근 앱 제외). 자기 `Link`·`PhoneVoice`를 들고 `via=quick`으로 보낸다. 답을 받으면 말하고(글 모드는 이어폰 있을 때만) 6초 뒤 닫힘
- 진입 3곳: 홈 위젯 `QuickWidget`(4×1 — 🎤·⌨·이름) · 상주 알림(채널 `hyun_quick`, "🎤 말하기"·"글로 묻기" RemoteInput) · 앱 안 `글` 버튼(앱 안에선 `inputRow`가 키보드 위로 뜬다)

## 화면 (`HyunView.drawPhone` — 폰·세로일 때)

- 3단만: 머리줄(이름·두뇌 점·모드 칩) + "다음" 한 줄 → 캐릭터(가운데 `h*0.33`) + 사용자 말·우선순위 점·대답 → 아래 줄 `글 · 🎤 · 목록`(배지 수). 알림·할 일·기억은 `목록`을 눌렀을 때 덮개로만
- 색 토큰은 `lightTheme` 게터(`BG`·`CARD`·`FG`·`MUTED`·`ACCENT` …) — 흰 배경·청록 `0xFF0F9F92`. 차는 어두운 값 그대로
- 캐릭터(차·폰 공용) = 유리 구슬 + 기분 색 오로라 + 회전 테두리 빛 + 빛의 눈 → [[hyun-app]] §앱

## 실행 (샤오미 + PC)

```bash
export JAVA_HOME=~/.jdks/corretto-21.0.11            # PATH java(JDK 25)는 빌드 실패
cd android && ./gradlew :hyun:assembleDebug -q
ADB=~/AppData/Local/Android/Sdk/platform-tools/adb.exe
$ADB -s 5cd17aa7 install -r -g hyun/build/outputs/apk/debug/hyun-debug.apk
$ADB -s 5cd17aa7 reverse tcp:8790 tcp:8790           # USB 다시 꽂으면 다시
```

- 시연 인텐트(폰 추가분): `--es mode walk` · `--es remind "분|내용"` · `--es line "P3|문장"`(스케줄러 시험). 나머지 → [[hyun-app]] §앱
- 서버·두뇌 루프는 차와 같다 — `wait.py` 출력 머리에 `폰(짭비서·업무중)`, 대중교통+이어폰 없음이면 `·이어폰 없음=자막만`

## 실측 (2026-10-31, Redmi Note 13 Pro 5G · Android 15 · 시리얼 `5cd17aa7`)

| 항목 | 결과 |
|---|---|
| 음성 | ✅ 구글 STT 한국어·구글 TTS. 말 사이 쉼에 문장이 둘로 갈려(“3시간 후 / 이불 빨래”) → 무음 판정 1.8초/1.5초로 늘림(앞 답은 `waitingId`로 버림) |
| 위치·날씨 | ✅ GPS → "수원시 영통구 망포동", Open-Meteo |
| 두뇌 왕복 | ✅ 인사·질문·모드 전환·`[P#]` 표시. 시작 인사가 두뇌 연결 전에 나가 규칙 문장이 되던 것 → `healthSeen` 뒤 인사(최대 8초) |
| 리마인더 | ✅ 18:00 알림 울림 → "했어" → `--done`. 목록 덮개에 날짜(`내일 HH:mm`·`M/d(E)`) |
| 상주 알림·흰 화면 | ✅ 알림 게시 · 흰 배경·어두운 상태바 아이콘 |
| ⏳ 미시험 | 위젯 탭 · 알림 "글로 묻기" · 앱 안 `글` 입력 · 대중교통 자막 모드 · 캘린더 10분 전(폰에 일정 없음) |
