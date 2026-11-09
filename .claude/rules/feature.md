---
paths:
  - "android/hyun/src/main/java/io/github/somanghao/makecar/hyun/feature/**"
  - "android/hyun/src/main/java/io/github/somanghao/makecar/hyun/core/**"
  - "android/hyun/src/main/java/io/github/somanghao/makecar/hyun/voice/**"
  - "server/hyun/**"
---
# 기능(모듈)·코어·서버 규칙

- 모듈 하나 = 앱 `feature/<X>Feature.kt` + `feature/Registry.kt` 한 줄 + (필요하면) 서버 `server/hyun/features/<x>.py` + `features/__init__.py` `ALL` 한 줄 → `docs/hyun-architecture.md` §새 기능 붙이는 법
- **`Registry`·`ALL`은 순서가 뜻** — 말 가로채기(`onHeard`)·규칙 답은 앞 기능이 먼저. 충돌 나면 두 줄 다 남기고 순서만 정한다
- 새 주행 이벤트는 `voice/Speaker.eventPrio`에 우선순위부터(주행 필수 = P1). 대답은 쿨다운을 쓰지 않는다
- 서버 `SCHEMA` 새 필드는 **빈 값을 꼭 정의**(구조화 출력은 전부 required) · `on_in` 훅은 inbox 잠금 안에서 돈다 — 네트워크·느린 일 금지(`block`/`post`에서)
- 이벤트 답이 오는 사이 목적지가 바뀌면 그 답은 버린다(`Hyun.onReply`) — 새 이벤트도 이 규칙을 탄다
- 내비 명령은 `Navi.call` 이름을 `Navi.COMMANDS`에 넣는다(내비가 덮으면 반반 처리가 걸린다)
- 장소(카카오) 결과는 파일·로그에 남기지 않는다(약관) · 키는 `server/hyun/data/keys.json`(git 제외)
- 로그는 `<영역>|<내용>` 한 줄 — 시험기(`tools/hyun_e2e`)가 읽는다
- 시험: `tools/hyun_e2e/cases.json`에 자기 번호 대역(CLAUDE.md 표)으로 케이스 → `python -I -X utf8 tools/hyun_e2e/run.py --servers --ids <id>`
