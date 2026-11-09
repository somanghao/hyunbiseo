# hyunbiseo — 현비서 (현대차그룹 차량용 앱 공모전 2026 · 조수석 대화 비서)

**이 저장소가 앱·서버·시험 코드의 정본이다**(2026-11-09 결정). 팀은 여기서 브랜치·PR로 작업한다. 예외: `docs/*.md`(`docs/specs/` 빼고)는 작업 저장소 `makecar-hy`의 설계 문서 사본 — 고칠 점은 PR 설명에 적으면 내쉬가 원본에 반영한다. 마감 **11-15(일) 23:59 접수**(목표 11-14).

## 명령 (Windows · Git Bash 기준)

```bash
# 빌드 — JDK 21(JAVA_HOME), android/local.properties에 sdk.dir(각자)
cd android && ./gradlew :hyun:assembleDebug
adb -s emulator-5554 install -r -g android/hyun/build/outputs/apk/debug/hyun-debug.apk
adb -s emulator-5554 shell am start --user 10 -n io.github.somanghao.makecar.hyun/.HyunActivity   # 앱은 --user 10
# 서버(8790) — 두뇌 = 서버를 띄운 사람의 Claude 로그인(claude -p) · 사용량은 본인 몫 · 끝나면 끈다
python -I -X utf8 -u server/hyun/hyun_server.py
# E2E — 케이스 정본 tools/hyun_e2e/cases.json
python -I -X utf8 tools/hyun_e2e/run.py --servers --ids e20
```

- 시연 인텐트(마이크 없이): `--es say "웹툰 순위 알려줘"` · `--es ui chat|info` · `--es skin nash` · `--es dest 강남역 --ef lat 37.4979 --ef lng 127.0276` · 목록은 `core/Hyun.kt` `onIntent` 주석과 각 `feature/*.kt` 맨 위
- 에뮬·서버·시연 절차 = 스킬 `/run-hyun` · 새 기능 절차 = 스킬 `/new-feature`
- Python은 `-I -X utf8`(격리 + 한글 출력). 사용자 site-packages가 필요하면 그 모듈 안에서 `site.getusersitepackages()`를 붙인다

## 6개 기능(모듈) — 담당 · 브랜치 · 시험 번호

| 모듈 | id | 명세 · 화면 구성도 | 앱 파일(예정) | E2E | 담당 |
|---|---|---|---|---|---|
| 쉼표 | rest | `docs/specs/rest.md` · `.html` | `feature/RestFeature.kt` | e20~29 | (정하기) |
| 튜토리얼 | tutor | `docs/specs/tutor.md` · `tutor-tips.md` | `feature/TutorFeature.kt` · `assets/tips.json` | e30~39 | (정하기) |
| 온기(+캐릭터 꾸미기) | ongi | `docs/specs/ongi.md` | `feature/OngiFeature.kt` | e40~49 | (정하기) |
| 오늘 할 일 | today | `docs/specs/today.md` | `feature/TodayFeature.kt` | e50~59 | (정하기) |
| 엔터(끝말잇기·퀴즈) | play | `docs/specs/play.md` | `feature/PlayFeature.kt` | e60~69 | (정하기) |
| 브리핑(개인화 뉴스) | brief | `docs/specs/brief.md` | `feature/BriefFeature.kt` · `server/hyun/features/briefing.py` | e70~79 | (정하기) |

- 브랜치 `feat/<id>` ← `main` · PR은 작게 · 머지 전 `main`을 받아 빌드가 되는지 확인
- 충돌 나는 곳: `feature/Registry.kt`·`server/hyun/features/__init__.py`(`ALL`)는 **한 줄씩, 둘 다 남기고 순서만 정한다**(순서가 뜻 — 말 가로채기·규칙 답은 앞 기능 먼저) · `cases.json`은 자기 번호 대역만 · 화면은 `ui/parts/<Module>Part.kt`로 빼고 `HyunView`엔 호출 한 줄
- **완료 기준**: 명세·화면 구성도 확인(내쉬) → 구현 → 자기 E2E 케이스 통과 → 1칸·반반 × 정차·주행 × 기본·내쉬 스킨 캡처 → PR

## 절대 규칙

- **새 기능은 명세(`docs/specs/<id>.md`) + 화면 구성도(`.html`)를 먼저** — 내쉬 확인 뒤 구현한다
- **주행 안전**: 주행 중 동영상·게임 화면·키보드·복잡한 조작 금지, 음성·한눈 정보만. 주행 중엔 카드·스티커·움직임을 숨긴다. 안전 경고(P0)는 앱 규칙이 정하고 두뇌가 바꾸지 않는다
- **실차에서 되는 것만 약속** — 기능이 쓰는 Pleos SDK·신호는 `docs/hyun-pleos-capabilities.md`(실측·미시험·승인 필요)로 확인
- **화면(ui/)은 데이터(`ScreenData`)를 읽기만** — 누르기는 `HyunView.on*` 콜백 → 코어·기능이 바꾼다. 스킨은 구성·위치·모양만 다르다
- **비밀 금지**: 키는 `server/hyun/data/`(git 제외 — 예: `keys.json`의 `kakao`)에만. 코드·커밋·PR·로그에 키를 넣지 않는다. 카카오 장소 결과는 파일·로그에 남기지 않는다(약관)
- 커밋·코드 주석·화면 문구는 한국어(쉬운 말) · 로그는 `<영역>|<내용>` 한 줄(시험기가 읽는다)

## 화면 구조 — 스킨 · 정보형 / 대화형

화면 코드 = `android/hyun/src/main/java/io/github/somanghao/makecar/hyun/ui/`.

| 층 | 정본 | 규칙 |
|---|---|---|
| 데이터 | `ui/ScreenData.kt`(`ScreenData` + `ScreenModel`) | 코어·기능만 쓴다. 화면은 읽기만 |
| 배치 | `ui/HyunView.kt` — `drawChat`(대화형) · `drawCar`(정보형 1칸) · `drawCarNarrow`(반반) | 위치·폭은 스킨 `layout` 값 |
| UI | `ui/skin/Kit.kt` — 판 · 말풍선(만화 타원·외침·구름) · 배경 무늬 · 별 터짐 · 스티커 | 모양·색은 스킨 `colors`·`style` |
| 스킨 정의 | `android/hyun/src/main/assets/skins/<id>.json`(`basic` · `nash` 웹툰) + `skins/fonts/` | 새 스킨 = JSON 한 파일 — `assets/skins/README.md` |

- **대화형** = 큰 캐릭터 + 말풍선이 메인. **정보형** = 정보가 메인(내비 판 · 카드 · 최근 본 정보), 말풍선은 캐릭터 칸에만
- 반반(내비와 나란히)은 늘 **[내비 | 현비서]** — `drive/HomeWindow.kt`(Pleos 홈 비공식 방송 · 시연용, 실차는 정책 확인)

## 더 읽을 곳

`README.md`(실행·지금 되는 것) · `docs/hyun-architecture.md`(구조 · §새 기능 붙이는 법 · §화면 층 · §함정) · `docs/hyun-modules.md`(모듈 결정) · `docs/hyun-app.md`(화면·귀) · `docs/hyun-brain.md`(두뇌·정보 통로) · `docs/hyun-e2e.md`(시험·시연 영상) · 경로별 규칙 `.claude/rules/` · 개인 설정은 `CLAUDE.local.md`·`.claude/settings.local.json`(git 제외)
