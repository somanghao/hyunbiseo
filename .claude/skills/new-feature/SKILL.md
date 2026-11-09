---
name: new-feature
description: 현비서에 새 기능(모듈)을 붙이는 절차 — 명세·화면 구성도 확인 → 앱 Feature → Registry → 서버 모듈 → E2E 케이스 → 캡처 → PR. "새 기능", "모듈 만들기", "쉼표·튜토리얼·온기·오늘 할 일·엔터·브리핑 구현" 같은 요청에 쓴다.
---

# 새 기능(모듈) 붙이기

0. **명세 먼저**: `docs/specs/<id>.md`(목적·심사 4축 · 트리거 · 현비서가 하는 말(P0~P3) · 쓰는 신호·SDK(실측/미시험) · 두뇌 답 필드 · 주행 규칙 · E2E 케이스) + 화면 구성도 `docs/specs/<id>.html`(1칸·반반 × 정차·주행). 내쉬 확인 전엔 구현하지 않는다. 쓰려는 SDK는 `docs/hyun-pleos-capabilities.md`에서 실측 여부 확인
1. 맡기(담당은 따로 없다): `gh pr list --state open`으로 같은 모듈 Draft PR이 없는지 보고 → `git switch -c feat/<id> main` → 빈 커밋이라도 푸시 → `gh pr create --draft --title "[<id>] 모듈 이름"` = "내가 하는 중" 표시
2. 앱 `android/hyun/src/main/java/io/github/somanghao/makecar/hyun/feature/<X>Feature.kt` — `class XFeature : Feature { override val id = "<id>"; override fun enabled(car: Boolean) = car … }`. 바로 할 말은 `onHeard`(true면 가로챔), 두뇌 답의 새 필드는 `onReply`, 1초마다 `tick`, 화면 값은 `render(v)`(`v.lines += HyunView.Line(…)` 또는 `ScreenData` 값)
3. `feature/Registry.kt`에 한 줄 — 순서를 생각해서(가로채는 기능은 명령들 뒤·길 앞)
4. 서버가 필요하면 `server/hyun/features/<x>.py`(필요한 것만: `RULES` · `SCHEMA`(빈 값 정의) · `block` · `post`) + `features/__init__.py` `ALL` 한 줄
5. 새 주행 이벤트면 `voice/Speaker.eventPrio`에 단계 먼저
6. 화면 요소가 있으면 `ui/parts/<X>Part.kt`(`.claude/rules/ui.md`) + `HyunView`에 부르는 한 줄 · 데이터는 `ui/ScreenData.kt`에 필드(화면은 읽기만)
7. 시험: `tools/hyun_e2e/cases.json`에 자기 번호 대역 케이스 → `python -I -X utf8 tools/hyun_e2e/run.py --servers --ids <케이스>` 통과
8. 캡처: 1칸·반반 × 정차·주행 × 기본·내쉬 스킨(`--es skin basic|nash` · `--es ui chat|info`) — 주행은 Virtual Console 또는 `tools/vconsole.py`
9. `cd android && ./gradlew :hyun:assembleDebug` 통과 확인 → 커밋(한국어 · 무엇·왜) → Draft PR을 Ready로(캡처·E2E 결과 첨부)
