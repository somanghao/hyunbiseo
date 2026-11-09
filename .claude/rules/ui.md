---
paths:
  - "android/hyun/src/main/java/io/github/somanghao/makecar/hyun/ui/**"
---
# 화면 코드(ui/) 규칙

- **데이터(`ScreenData`)는 읽기만.** 누르기는 `HyunView`의 `on*` 콜백으로 코어·기능에 알린다(예: `onCardClose`). 화면에 보일 글·목록도 데이터에 둔다(예: `hints`)
- 위치·폭은 스킨 layout(`L.*`), 색·모양은 스킨 colors/style(`C.*` · `kit.panel` · `kit.balloon`) — 색 숫자를 새로 박지 않는다(새 색이 필요하면 `SkinColors`에 칸 + `basic.json`·`nash.json`에 값)
- **새 모듈의 화면 요소는 `ui/parts/<Module>Part.kt`**: `object RestPart { fun draw(c: Canvas, kit: Kit, d: ScreenData, box: RectF, u: Float) }`처럼 만들고 `HyunView`에는 부르는 한 줄만 — 여럿이 `HyunView.kt`를 동시에 고치면 충돌한다
- 기능 한 줄 알림(아이콘 + 글)은 그리기 없이 기능의 `render(v)`에서 `v.lines += HyunView.Line(아이콘, 글)`
- **주행 중(`driving`)**: 카드·스티커·별 터짐·움직임 숨김, 글자 수 제한 — 새 요소도 같은 규칙. 회전 안내(내비 판)를 가리지 않는다
- 확인: 1칸·반반 × 정차·주행 × 기본·내쉬 스킨 캡처(`adb exec-out screencap -p > shot.png`)
