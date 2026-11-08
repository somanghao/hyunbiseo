# 현비서 구조 — 기능 플러그인 (앱 Feature + 서버 feature 모듈)

> 2026-11-07 리팩터 정본. 하부 모듈 7(쉼표·튜토리얼·온기·캐릭터·오늘 할 일·엔터·브리핑)을 계속 붙이려고 **기능 하나 = 앱 파일 하나 + 서버 모듈 하나 + 등록 두 줄**로 만들었다. 코어(앱 `core/Hyun` · 서버 `core/`·`brain/`)는 기능 이름을 모른다.
> 리팩터 전후 동작이 같은지: 앱만 바꾼 상태 E2E 11/11(`records/hyun-e2e/20261107-1112/`) · 서버 답 형식·규칙 줄·질문 블록을 옛 코드와 비교해 같음(날씨 캐시 값만 다름) · 앱+서버 E2E 11/11(`records/hyun-e2e/20261107-1125/`, 영상) → [[hyun-e2e]].

## 한 번에 보기

```
앱 android/hyun/…/hyun/                    서버 server/hyun/
 HyunActivity   껍데기(화면·권한·생명주기)     hyun_server.py  진입(sys.path + core.server.main)
 core/  Hyun    대화·말하기·두뇌·1초 루프      core/   config · store · inbox · http · engine · server
        Feature 기능 훅 · Fmt 시간·거리 글자    brain/  prompt(규칙·답 형식·질문 모으기) · cli(내 계정) · api(API 키)
 feature/       기능들 + Registry(목록)  ⇄   features/  기능 모듈들 + __init__.ALL(목록)
 voice/ drive/ brain/ ui/ phone/  부품                    persona.md(성격 정본) · say.py·wait.py(세션 두뇌) · stt_server.py
```

말 하나의 길: 앱 귀 → `Hyun.heard` → 기능 `onHeard`(명령이면 여기서 끝) → `Link` POST `/hyun/in` → 서버 `inbox.add_message`(기능 `on_in`) → 두뇌(세션 또는 `core/engine` → `brain/prompt`가 기능의 RULES·SCHEMA·block을 모음) → `inbox.apply_answer`(기능 `post` — 저장·카드 손질) → 앱 `/hyun/reply` → `Hyun.onReply`(기능 `onReply`) → 카드 → `say`.

## 앱 (`android/hyun/src/main/java/io/github/somanghao/makecar/hyun/`)

| 패키지 | 파일 | 맡는 것 |
|---|---|---|
| (루트) | `HyunActivity.kt` | 화면 만들기·인셋·폰 글자 입력·생명주기(onStop 포함)를 `Hyun`에 넘김. 기능 코드는 없다 |
| | `FrontActivity.kt` | 보이지 않는 한순간 화면 — 내비 명령 직전에 띄워 10초 유예(현비서 화면 지키기 `Hyun.primeFront`·`keepFront` → [[hyun-app]] §차 화면) |
| `core/` | `Hyun.kt` | 대화 엔진 — 듣기(귀·호출어·다시 묻기) · 말하기(`say`→`Speaker`→`pump`→`speakNow`, 회전 앞 침묵 `turnBusy`) · 두뇌 묻기(`ask`)·답 나누기(`onReply`) · 1초 `tick` · 맥락 `ctx()` · 화면 `render()` · 시연 인텐트(코어 몫: server·line·say·rawrec·debug·listen·turnm·sttfile·earfile) · 공용 상태(`isCar`·`navi`·`trip`·`speedMs`·`lat/lng/addr`·`driving`·`policyOverride`·`silent`) |
| | `Feature.kt` | 기능 훅(아래 표) — 전부 기본 구현, 필요한 것만 override |
| | `Fmt.kt` | `km`·`dur`("3시간 52분")·`hhmm`·`whenLabel` |
| `feature/` | `Registry.kt` | **기능 목록 — 순서 = 훅 순서** |
| | `CommandFeatures.kt` | `QuietFeature`("조용히") · `ListenModeFeature`(상시/버튼, 차) · `CharacterFeature`(캐릭터 3종, 차) |
| | `GleoFeature.kt` | 글레오 3액션 ASK_HYUN·HYUN_STATUS·HYUN_QUIET(차) |
| | `TurnFeature.kt` | 회전 안내(TBT) 화면 값 → 내비 판·반반 회전 줄(차) |
| | `ArriveFeature.kt` | 도착 추천 — 서버가 준 한 줄(`arrive_line`) · 시연 시계 `--es clock` · 맥락 현재 좌표(차) — 판정·검색·카드는 서버 `places` |
| | `RouteFeature.kt` | 목적지·경유지(`goTo`·`addVia`·`cancelTrip`) · 주행 이벤트 depart·halfway·near·arrived·no_dest·waypoint |
| | `ScoutFeature.kt` | 경로 정찰(`nextScanSec`·`report`·`reroute`·`afterReroute`) · 휴게소 알림 `restArea` · 글레오용 `english()`(차) |
| | `CarFeatures.kt` | `WeatherFeature`(Open-Meteo) · `CarBatteryFeature`(Vehicle SDK, 차) · `DriveCareFeature`(장시간·조용함, 차) |
| | `PhoneFeature.kt` | 짭비서 — 모드·일정·리마인더·할 일·산책·하차(폰) → [[hyun-phone]] |
| `drive/` | `Navi.kt` | NaviHelper 하나 + **모든 내비 콜백을 로그(`navi|…`)로 남기고 기능들에 나눔**(`listen`) · `call`(실패 로그 · 명령 이름 `COMMANDS`면 `beforeCommand` → 코어가 현비서 화면 지키기) — 새 내비 명령을 부르면 `COMMANDS`에도 |
| | `Scout.kt` · `Trip.kt` · `Sense.kt` | 정찰 판정(시작 90초 조용 `calmUntil`) · 남은 거리·연속 주행 · 차/폰 눈(속도·기어·DO) |
| `voice/` | `Voice.kt` · `Ear.kt` · `EarService.kt` · `Speaker.kt` | 귀·입 인터페이스 · 상시 귀 · 마이크 포그라운드 서비스 · 말하기 스케줄러(`eventPrio`) → [[hyun-app]] §귀·§말하기 |
| `brain/` | `Link.kt` | 서버 HTTP |
| `ui/` | `HyunView.kt` | 차·폰 화면 · 카드(목록·표지 격자·소개) · 캐릭터 |
| `phone/` | `Desk.kt` · `QuickActivity.kt` · `PhoneMode.kt` | 폰 전용 부품 |

### 앱 기능 훅 (`core/Feature.kt`)

| 훅 | 언제 | 쓰는 예 |
|---|---|---|
| `enabled(car)` | 시작 때 한 번 — false면 아예 안 켬 | 차 전용 `car`, 폰 전용 `!car` |
| `start(hub)` / `stop()` | onCreate / onDestroy | 내비 듣기 `hub.navi?.listen(…)`, 버튼 연결 |
| `tick()` | 1초마다 | 주행 이벤트 판정 |
| `onIntent(i, first)` | 시연·글레오 인텐트 | `--es dest`, `--es scan` |
| `onHeard(text)` → Boolean | 두뇌보다 먼저, **true면 가로챔** | "조용히 해 줘" |
| `onReply(kind, text, r)` | 두뇌 답이 오면 | `r.nav` → 길 안내 |
| `ctx(c)` / `render(v)` | 두뇌 맥락 / 화면 | `dest`·`trafficAhead` / 목적지 띠 |
| `onLocation` · `onTripReset` · `onPermissions` | 위치 · 새 목적지·안내 끝 · 권한 | 정찰 상태 비우기 |
| `offline(text)` / `fallback(ev, extra)` → String? | 두뇌 없음·늦음 — **처음 null 아닌 것** | 남은 거리 · "출발해요" |

다른 기능이 필요하면 `hub.feature<RouteFeature>()`(없으면 null — 빼도 돌게). 내비는 `NaviHelperEventListener` 메서드가 전부 기본 구현이라 필요한 것만 override한다.

## 서버 (`server/hyun/`)

| 패키지 | 맡는 것 |
|---|---|
| `core/config.py` | 경로·포트 — `HYUN_DATA`(시험 데이터) · `HYUN_PORT` · `SHARED_DATA`(정보 통로 캐시는 공용) |
| `core/store.py` | `log.jsonl`·`memory.json` 읽기·쓰기 · 최근 대화 |
| `core/inbox.py` | 말 ↔ 답 — `add_message`(기능 `on_in`) · `apply_answer`(기본 필드 + 기능 `post`) |
| `core/http.py` | 코어 라우트(health·in·answer·wait·reply·log) + 기능 라우트(`GET`·`POST` 표) |
| `core/engine.py` | 자동 두뇌 워커 — 세션이 6초 안 받으면 내 계정(cli) → API 키, 검색 모드 = 기능 `search` |
| `core/server.py` | 스레드(워커·예열·기능 `threads`) + HTTP 서버 |
| `brain/prompt.py` | 시스템 = persona.md + 기본 규칙 + 기능 RULES · 답 형식 = 기본(say·listen·mood·prio) + 기능 SCHEMA · 질문 = [맥락] + 기능 block + 마지막 줄(기능 `last_line`) |
| `features/__init__.py` | **`ALL` = 기능 목록(순서 = 규칙·블록·후처리 순서)** · `hook`·`any_hook`·`collect`·`routes` |

| 기능 모듈 | 짝(앱) | 넣는 것 |
|---|---|---|
| `route` | RouteFeature·ScoutFeature | SCHEMA nav_*·via_*·route · [길·교통] · 주행 이벤트 규칙 · `normalize`(nav_* → nav{}) |
| `traffic` | ScoutFeature | 교통 질문 → 서버가 교통 표 카드(`traffic_card`) |
| `places` | ArriveFeature · RouteFeature(맥락 `destLat`·`destLng`) | 도착 추천·주변 장소 — `on_in`은 판정만(잠금 안 — 네트워크 금지) · 카카오 호출은 `block`/`post`의 `_ensure` · [주변 장소] 블록 · 서버가 카드(`nostore`) + `arrive_line` · `rec.places`(로그엔 검색어·개수) |
| `cards` | HyunView | SCHEMA card · 카드 규칙 · 개수 맞추기 `trim_card` · 웹툰 표지·소개 사진 `enrich_card` · `/hyun/img` |
| `memory` · `taste` · `history` | 정차 화면 칩 | 기억 · 취향(SCHEMA taste·asked_slot, `/hyun/taste`·`/hyun/me`) · [최근 대화] |
| `briefing` | — | 시동 브리핑 판정 `on_in` · [브리핑] · `last_line` · SCHEMA brief_order |
| `feeds` · `search` · `restarea` | — | 정보 통로 7 + [정보 통로] + `/hyun/feed` + 예열 · 검색 모드 · 휴게소 |
| `phone` · `taste_refine` · `login` · `testaudio` | PhoneFeature 등 | `/hyun/tasks` · 정리 봇 스레드 · 로그인 페이지 · 귀 시험 녹음 |

서버 훅 전체 목록·뜻은 `features/__init__.py` 머리말이 정본(RULES · SCHEMA · block · feed_cats · last_line · search · on_in · normalize · post · GET/POST · threads).

## 새 기능 붙이는 법 (예: 엔터 "끝말잇기")

0. **기능 명세 + 화면 구성도 쌍을 먼저**(사용자 11-08 — 앞으로의 모든 추가 기능) — 둘 다 만들고 사용자 확인을 받은 뒤 아래 1~7로 구현. 명세 `dashboard/hyun/specs/<id>.md` = 목적·심사 4축 · 언제 켜지나(트리거·이벤트·말) · 현비서가 하는 말(예시·우선순위 P0~P3) · 쓰는 신호·SDK(실측/미시험) · 두뇌 답 필드·서버 훅 · 주행 규칙(주행 중 보이는 것·숨기는 것) · E2E 케이스. 화면 구성도 `dashboard/hyun/specs/<id>.html`(목록 `dashboard/hyun/specs/index.html` · 모듈 6 초안 11-08) = 1칸·반반 × 정차·주행 4장(실제 비율 — 1칸 1706×1210 · 반반 841×1210 앱 영역), 기존 요소(캐릭터·내비 판·마이크 줄)와 새 요소의 자리 · 시연 영상의 케이스 설명 슬라이드에도 씀
1. **앱** `feature/WordChainFeature.kt` — `class WordChainFeature : Feature { override val id = "wordchain"; override fun enabled(car: Boolean) = car … }`. 앱이 바로 할 말은 `onHeard`(true로 가로챔), 두뇌 답의 새 필드는 `onReply`, 상태 표시는 `render`
2. `feature/Registry.kt`에 한 줄 — **가로채는 기능은 명령들 뒤·길 앞**처럼 순서를 생각해서
3. **서버** `server/hyun/features/wordchain.py`(예시 · 예정) — 필요한 것만: `RULES`(두뇌에게 놀이 규칙) · `SCHEMA`(새 답 필드, **빈 값을 꼭 정의** — 구조화 출력이 전부 required) · `block`(진행 상태) · `post`(저장·`rep`에 필드)
4. `features/__init__.py` `ALL`에 한 줄
5. 새 이벤트를 쓰면 `voice/Speaker.eventPrio`에 단계부터(주행 필수면 P1 — [QUICKMAP-hyun](../QUICKMAP-hyun.md) §3 #17)
6. **화면**: 기능 한 줄은 `render(v)`에서 `v.lines += HyunView.Line(아이콘, 글)`(1칸 = 내비 판 바로 아래 · 반반 = 길 카드 아래 · 코어가 render마다 비움 — 11-08 도착 추천이 첫 사용). 그 밖엔 기능은 `render(v)`로 `HyunView` 값만 채운다 — 그리기 훅은 없어서 새 화면 요소(판·게임판·점수판)는 `ui/HyunView.kt`에 그리는 코드를 직접 넣는다(예: `TurnFeature` 값 → `HyunView.naviPanel`·`naviStrip`, 11-08)
7. 시험: `tools/hyun_e2e/cases.json`에 케이스 → `python -I -X utf8 tools/hyun_e2e/run.py --servers --ids <id>` → 전체 `--servers --video`

## 함정

- **Kotlin 속성 초기화식 안의 익명 객체(`object : Runnable`)는 같은 이름의 생성자 인자를 잡는다** — `Hyun(…, features: List<Feature>)` + `val features = features.filter{…}`에서 tick이 거르기 전 목록을 돌아 폰 전용 기능이 차에서 돌다 죽었다(`UninitializedPropertyAccessException`). 인자 이름을 `all`로 바꿔 끊었다
- **`python -I`는 스크립트 폴더를 `sys.path`에 안 넣는다** — 진입 파일(`hyun_server.py`·`taste_refine.py`)이 `sys.path.insert(0, 자기 폴더)` 해야 `core`·`brain`·`features`를 찾는다. 기능끼리·두뇌와의 import는 **함수 안에서**(`brain.prompt` ↔ `features` 순환 방지)
- **서버 기능 모듈 안에서 `SCHEMA`·`RULES`·`block`·`post`·`search` 같은 훅 이름을 다른 뜻으로 쓰지 않는다** — 정리 봇의 출력 스키마가 `SCHEMA`라 두뇌 답 형식에 섞였다 → `REFINE_SCHEMA`로 · 장소 모듈의 `search(la, lo, …)`를 서버가 검색 모드 훅으로 불러 두뇌 스레드가 죽었다(11-08 E2E 0/2) → `find_places` + **서버 시작 때 훅 인자 검사**(`features/__init__.HOOK_ARGS` — 어긋나면 TypeError로 안 켜짐) + 두뇌 스레드는 한 메시지 예외에 살아남는다(`engine.answer_one`)
- **Git Bash `curl -d '{"text":"한글"}'`은 CP949로 보낸다** → 서버 `UnicodeDecodeError`. 서버 시험은 Python `urllib`(UTF-8)이나 `--data-binary @파일`로
