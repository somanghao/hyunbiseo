---
paths:
  - "android/hyun/src/main/assets/skins/**"
  - "android/hyun/src/main/java/io/github/somanghao/makecar/hyun/ui/skin/**"
---
# 스킨 규칙

- 스킨 = JSON 한 파일 `assets/skins/<id>.json`(id = 파일 이름) — 칸 설명 정본 `assets/skins/README.md`. 빈칸은 기본 스킨 값, 깨지면 그 스킨만 빠지고 logcat `skin|bad|…`
- 스킨은 **구성·위치·모양·글꼴만** 바꾼다 — 데이터와 주행 안전 규칙은 못 바꾼다
- 글꼴은 상업 사용 가능한 무료 글꼴만(OFL 등) + 라이선스 파일을 `skins/fonts/`에 같이
- 안전 빨강(`prio[0]`)·회전 칸(`tbt*`)은 눈에 띄게 · 밝은 바탕은 낮용(밤 눈부심 — 어두운 판도 같이 만들면 좋다)
- 새 모양이 필요하면 `ui/skin/Skin.kt` `SkinStyle`에 칸 + `ui/skin/Kit.kt`에서 그 칸을 읽는다(배치 코드는 그대로)
