# 현비서 하네스 · 고도화 에이전트 (2026-11-06)

> 사용자: "다양한 질문을 해보고 답변을 스스로 해보자 — 할 수 있는 것·없는 것, 가져올 수 있는 정보·API, 화면이 잘 보이는지(웹툰이면 그림), 하네스도 스스로 개선하는 구조"
> 코드 `tools/hyun_harness/harness.py`(맨 위 사용법) · 에이전트 `.claude/agents/hyun-harness.md` · 보고서 `dashboard/hyun/harness.html`(보고서 6번 탭 `report.html#harness`, 데이터 `harness-data.json`)

## 한 바퀴

```
questions.json ──run──▶ 서버 /hyun/in(ctx.harness=true) → /hyun/reply   answers.json
               ──judge─▶ claude -p + rubric.md → ok·partial·fail·cannot · needs · display_ideal · fix   verdicts.json
               ──shots─▶ 에뮬 앱 --ez harness true --es say → screencap → claude -p(Read로 PNG 봄) 가독성·형태   screens.json + shots/
               ──report▶ dashboard/hyun/harness-data.json(+ history 12회)
               ──evolve▶ claude -p + WebSearch → 새 질문(은행에 자동 추가)·은퇴·판정 규칙(rubric 끝에 자동 추가)
                          · API 후보·앱 백로그(backlog.json) · 하네스 개선안(proposals.md, 사람·에이전트가 검토 후 적용)
```

- 실행: `python -I -X utf8 tools/hyun_harness/harness.py all --shots --evolve` · 부분 `run --cat 스포츠` / `--ids q012` / `--limit N` · 결과 `tools/hyun_harness/runs/<시각>/`(git 제외)
- **시험 질문 격리**: `ctx.harness=true`면 서버가 취향(taste)·기억(remember)·자주 묻는 것·최근 대화 맥락에 넣지 않고 log에 `harness` 표시 · 앱 화면 시험은 인텐트 `--ez harness true`
- 질문은 하나씩 차례로(서버는 가장 최근 미답만 받고 앞의 것은 건너뜀 — 건너뛰면 한 번 다시)
- 질문 은행 32개(교통·날씨·영화·주식·웹툰·웹소설·스포츠·뉴스·음식·차·앱 제어·일정·취향·대화·안전·생활) — expect.display(text·list·image_list·chart·map) · expect.needs(feed·search·places·nav·car·memory·unsupported) · screen 표시 10개
- 판정·evolve·화면 판정은 이 PC의 `claude -p`(사용자 구독) — 질문 1개 3~28초, 판정 1회, 화면 판정 1회, evolve 1회

## 결과 (2026-11-06)

- **1회차**(32문항): 할 수 있음 12 · 부족 8 · 틀림 0 · 못 함 12, 화면 2/9 통과 · 평균 4~6초(검색 14~24초) · evolve: 질문 +9 · 은퇴 3 · 판정 규칙 +9 · API 후보 9 · 앱 백로그 11 · 하네스 개선안 9
- 1회차 약한 곳 → 고친 것: **이전 표가 다음 질문에 남음**(화면 4장 → 새 질문이면 앱이 카드 지움) · **웹툰 그림 없음**(서버 `enrich_card`가 표지 URL을 붙이고 앱 `imageGrid` 5×2 표지 격자) · 주간 날씨·뉴스·환율 '못 함'(통로 추가) · 차 배터리 혼동(규칙) · 날씨 모순(지금 맑음 + 하루 100% → "앞으로 12시간" 줄) · "세 개만"인데 10개(서버 `trim_card`가 말한 개수로 자름)
- 2·3회차(고친 문항만, 판정 기준이 evolve로 더 엄격해진 뒤): 강남역 가자·차 배터리 → ok · 주간 날씨·환율·뉴스 '못 함' → '부족'(답은 맞고 출처·길이 감점) · 웹툰 격자 화면 판정 통과 · 뉴스 세 개 → 카드 3개
- 하네스 자신: judge에 `say_len`(기계로 잰 글자 수)·`executed.nav/route`(앱이 실제 실행)·`card_images` · 화면 심사에 "캡처는 정차 상태, 목록은 정차 중에만" 전제 · proposals.md 3개 적용 표시
- 남은 빈칸(→ `tools/hyun_harness/backlog.json`): **그래프 카드**(주가 일봉·주간 기온 — 지금은 글자 목록) · 장소 API(카카오·충전소·오피넷 — 키) · 미세먼지(에어코리아 키) · 스포츠 결과 통로(KBO 등 공식 API 없음, 검색 변동 큼) · 넷플릭스·로또 · 자동 두뇌 알림(remind — 지금은 세션 두뇌만) · 문피아 · 집 주소 저장
