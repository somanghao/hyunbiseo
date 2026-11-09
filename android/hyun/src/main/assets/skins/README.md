# 현비서 스킨 만들기 (칼리 · 댄 · 누구나)

차 화면의 겉모습 한 벌 = **JSON 파일 하나**. 코드를 몰라도 만들 수 있다. 화면에 보일 내용(데이터)은 스킨이 건드리지 않는다.

| 층 | 무엇 | 어디서 바꾸나 |
|---|---|---|
| 데이터 | 목적지·회전·말·표·기분 | 스킨 아님 — `ui/ScreenData.kt`(코어·기능이 채움) |
| 배치 | 캐릭터 왼쪽/오른쪽 · 칸 폭 | 스킨 `layout` |
| UI | 색 · 판 모양 · 테두리 · 그림자 · 배경 무늬 · 말풍선 꼬리 · 별 터짐 · 효과음 스티커 | 스킨 `colors` · `style` |
| 글꼴 | 이름표 · 본문 · 굵게 | 스킨 `fonts` (+ `fonts/`에 글꼴 파일) |

## 새 스킨 만드는 순서

1. `basic.json`을 복사해 `kali.json`(또는 `dan.json`)으로 이름을 바꾼다 — 파일 이름이 곧 스킨 id
2. 맨 위 `id`(파일 이름과 같게) · `name`(화면·말로 부를 이름, 예: "칼리 네온") · `author` 를 바꾼다
3. 바꾸고 싶은 칸만 고친다 — **지운 칸은 기본 스킨 값**이 들어간다(일부만 바꿔도 된다)
4. 빌드·설치 → 화면 왼쪽 아래 **팔레트 버튼**(점 셋)을 누를 때마다 다음 스킨 · 말 "칼리 네온 스킨으로" · 시연 `adb shell am start --user 10 -n io.github.somanghao.makecar.hyun/.HyunActivity --es skin kali`
5. JSON이 깨지면 그 스킨만 빠지고 logcat에 `skin|bad|kali.json|이유`가 찍힌다

## 칸 설명

**colors** — `#RRGGBB` 또는 `#AARRGGBB`(앞 두 자리 = 투명도)
- `bg` 바탕 · `card` 판 · `card2` 옅은 판(표 칩·그림 자리) · `fg` 글자 · `sub` 둘째 글자 · `muted` 옅은 글자 · `faint` 아주 옅은 글자 · `line` 선
- `accent` 강조(켜진 버튼 등) · `onAccent` 강조 위 글자 · `ok` 연결·듣는 중 · `warn` 알림 · `track` 진행선 바탕 · `divider` 표 줄 사이 선
- `tbtBg`·`tbtFg`·`tbtSub`·`tbtMuted` 내비 회전 칸(검은 칸) 바탕·글자 · `near` 300m 안 회전 거리 강조
- `bubbleMe`·`bubbleMeText` 내 말풍선 · `bubbleHyun` 현비서 말풍선
- `moods` 기분 색 두 개씩(calm·happy·fun·care·alert) — 캐릭터 빛 · 강조 · 진행선 · `prio` 말 우선순위 4색(P0 안전 = 빨강 유지 권장)

**fonts** — `asset:skins/fonts/파일.ttf`(이 폴더의 `fonts/`에 넣기) · 시스템 이름 `sans-serif` · `sans-serif-medium` · `sans-serif:bold` · `default-bold`
- `title` 이름표("현비서") · `body` 본문 · `bold` 굵은 글 · `medium` · `light`
- 글꼴 파일은 **상업 사용 가능한 무료 글꼴만**(OFL 등) — 라이선스 파일을 같이 넣는다(예: `fonts/OFL-Jua.txt`)

**layout**
- `charSide` `"left"` | `"right"` — 정보형의 캐릭터 칸 · 대화형의 캐릭터 · 반반 화면의 캐릭터 줄 위치(말풍선은 캐릭터 쪽이 현비서)
- `answerAt` `"main"`(정보형 답을 정보 칸에 크게) | `"character"`(답은 캐릭터 칸 말풍선, 정보 칸은 정보만 — 카드 · 최근 본 정보 · 물어볼 거리)
- `infoCharW` 정보형 캐릭터 칸 폭(화면 폭 비율 0.15~0.4) · `chatCharW` 대화형 캐릭터 폭(0.25~0.6) · `bubbleMaxW` 말풍선 최대 폭(말풍선 칸 비율)

**style** — 길이는 화면 짧은 변 비율(0.005 ≈ 5px/1000px)
- `radius` 모서리 배수(1 = 기본, 0 = 각진 판) · `border`·`borderColor` 잉크 테두리(0 = 없음) · `shadow` [x, y]·`shadowColor` 딱딱한 그림자([0, 0] = 없음)
- `pattern` `none` | `halftone`(망점) | `grid`(모눈) · `patternColor`
- `bubbleShape` `round`(둥근 상자) | `comic`(만화 말풍선 — 타원 · 안전 경고는 뾰족한 외침 · 생각 중은 구름, 꼬리가 캐릭터 입 쪽) · `bubbleTail` 말풍선 꼬리(round일 때) · `burst`·`burstColor` 캐릭터 뒤 별 터짐(정차 중만)
- `stickers` 상태별 효과음 딱지 — 키 `listening`·`thinking`·`speaking` + 기분 `happy`·`fun`·`care`·`calm` (빈 칸이면 안 띄움, 주행 중엔 안 띄움) · `stickerColor`·`stickerText`
- `titleTilt` 이름표 기울기(도, 음수 = 왼쪽으로)

## 주의

- **주행 중 안전 규칙은 스킨이 못 바꾼다** — 주행 중엔 별 터짐·스티커·움직임을 끄고 글자 수를 줄이는 것은 배치 코드가 지킨다. 회전 칸(`tbt*`)과 안전 빨강(`prio[0]`)은 눈에 잘 띄게 둔다
- 밤에 눈부시지 않게 — 밝은 바탕 스킨(예: 내쉬 웹툰)은 낮용. 어두운 판도 하나 같이 만들면 좋다
- 캐릭터 그림(사람 비서·구슬)은 스킨과 따로 고른다(⇄ 버튼)

## 코드로 더 깊게(선택)

- 새 모양(예: 네온 빛 테두리)이 필요하면 `ui/skin/Skin.kt`의 `SkinStyle`에 칸을 더하고 `ui/skin/Kit.kt`(판·말풍선·배경 그리기)에서 그 칸을 읽는다 — 배치 코드는 그대로
- 배치 자체를 바꾸려면 `ui/HyunView.kt`의 `drawCar`(정보형) · `drawChat`(대화형) · `drawCarNarrow`(반반) — 위치는 `L.*`(스킨 layout) 값으로 정한다
