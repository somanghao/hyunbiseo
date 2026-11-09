# hyunbiseo — 현비서 (Claude Code로 작업하는 개발자용 요약)

이 저장소는 작업 저장소에서 `tools/export_hyunbiseo.py`로 떼어 낸 **사본**이다 — 큰 변경은 원본에서 하고 다시 내보낸다. 실행·구조 전체는 `README.md`, 설계·실측 문서는 `docs/`.

## 화면 구조 — 스킨 · 정보형 / 대화형

앱 화면 코드 = `android/hyun/src/main/java/io/github/somanghao/makecar/hyun/ui/`. **스킨은 보여 주는 구성·위치·모양만 다르고 데이터는 그대로다.**

| 층 | 정본 | 규칙 |
|---|---|---|
| 데이터 | `ui/ScreenData.kt`(`ScreenData` + `ScreenModel`) | 코어·기능만 쓴다. 화면은 **읽기만** — 누르기는 `HyunView.on*` 콜백 → 코어가 바꾼다 |
| 배치 | `ui/HyunView.kt` — `drawChat`(대화형) · `drawCar`(정보형 1칸) · `drawCarNarrow`(반반) | 위치·폭은 스킨 `layout` 값(캐릭터 왼쪽/오른쪽 등) |
| UI | `ui/skin/Kit.kt` — 판 · 말풍선(만화 타원·외침·구름) · 배경 무늬 · 별 터짐 · 스티커 | 모양·색은 스킨 `colors`·`style` |
| 스킨 정의 | `android/hyun/src/main/assets/skins/<id>.json`(`basic` 어두운 기본 · `nash` 웹툰) + 글꼴 `skins/fonts/` | 새 스킨 = JSON 한 파일(코드 없이) — 칸 설명 `android/hyun/src/main/assets/skins/README.md` |

- **대화형** = 큰 캐릭터 + 말풍선이 메인(스킨 `style.bubbleShape: comic`이면 만화 말풍선). 표(카드)는 "정보형에서 보기" 칩만
- **정보형** = 정보가 메인 — 내비 판 · 카드(목록 · 표지 격자 · 사진 소개) · 카드 없으면 최근 본 정보 · 물어볼 거리. 말풍선은 캐릭터 칸에만(스킨 `layout.answerAt: character` · 기본 스킨은 `main` = 답을 정보 칸에 글자로)
- 바꾸기: 왼쪽 아래 [대화형 | 정보형] · ⇄ 캐릭터 · 팔레트 스킨 · 말("대화형으로" · "내쉬 스킨으로") · 시연 `--es ui chat|info` · `--es skin nash`
- 반반(내비와 나란히)은 늘 **[내비 | 현비서]** — `drive/HomeWindow.kt`(Pleos 홈 비공식 방송 · 시연용, 실차는 정책 확인)
- 주행 중 안전 규칙(별 터짐·스티커·움직임 끄기 · 글자 수 제한)은 배치 코드가 지킨다 — 스킨이 못 바꾼다
- 상세 → `docs/hyun-architecture.md` §화면 층 · `docs/hyun-app.md` §차 화면 · 새 기능 붙이는 법 `docs/hyun-architecture.md` §새 기능 붙이는 법

## 빌드

- JDK 21(`JAVA_HOME`) · `cd android && ./gradlew :hyun:assembleDebug` · 에뮬 Pleos Connect v2.1 · 앱은 `--user 10`으로 실행(README §실행)
