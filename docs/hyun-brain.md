# 현비서 두뇌 연결 · 상용화 — 세션 · 내 Claude 계정(클라우드 PC) · API · 정보 통로

> 현비서 앱 본체 → [[hyun-app]] · 하부 모듈 → [[hyun-modules]] · 말하기 규칙 정본 `server/hyun/persona.md`
> 사용자 결정 2026-11-06: **상용화 = ② 현대 클라우드 PC + 사용자 본인 Claude 로그인**(건당 요금 방지). ① 자기 PC를 늘 켜 둘 운전자는 거의 없다

## 두뇌 셋 (서버 `server/hyun/core/engine.py`가 고른다 — 서버 구조 → [[hyun-architecture]])

| 순서 | 두뇌 | 인증 | 비용 | 응답(실측 11-06) | 코드 |
|---|---|---|---|---|---|
| 1 | 떠 있는 Claude Code 세션(wait.py·say.py) | 사용자 구독 | 0 | 10~20초(사람이 붙어 답) | `wait.py`·`say.py` |
| 2 | **내 Claude 계정 = 클라우드 PC** — 서버가 `claude -p`(수정 없는 Claude Code) 자동 실행 | 그 PC의 `claude auth login`(Anthropic 공식 흐름) | 0(구독 한도 안) | **4.7~5.3초** · 서버 켠 뒤 첫 호출 ~28초 → 시작 때 미리 데움(4.8초) | `brain/cli.py` |
| 3 | Claude API(로그인 페이지에서 키 등록) | API 키 `data/claude.json` | 건당(아래) | 미측정(키 없음) | `brain/api.py` |

- 고르는 법: 세션이 `/hyun/wait`에 걸려 있으면 세션 · 세션이 없거나 6초 넘게 아무도 안 받으면 2 → 없으면 3. 가장 최근 메시지에 답하고 앞의 것은 건너뜀(세션과 같은 규칙)
- 공통 규칙 `brain/prompt.py`: persona.md + 기본 규칙 + 기능 모듈 RULES·SCHEMA·질문 블록(`server/hyun/features/`, 등록 순서) · 답 = 구조화 출력 `{say, listen, mood, prio, remember, nav_name, nav_lat, nav_lng, route}`(서버가 `nav{}`·`route`로 바꿔 say.py와 같은 모양 — 자동 두뇌도 목적지 설정·우회) · 매 질문에 `[길·교통]` 블록([[hyun-app]] §경로 정찰) · 질문에 영화·날씨·주식·웹툰·웹소설·뉴스·환율 말이 있으면 그 통로 값을 `[정보 통로]` 블록으로 미리 넣음(도구 호출 없이 한 번에)
- 2의 실행 옵션: `--model sonnet`(HYUN_CLI_MODEL — haiku 5.1초와 거의 같아 더 똑똑한 쪽) · `--json-schema` · `--system-prompt <persona>` · `--tools ""` · `--strict-mcp-config` · `--setting-sources local` · `--no-session-persistence` · 작업 폴더 `C:\Users\Public\hyun-brain`(프로젝트 CLAUDE.md 안 읽게). **`--bare`는 못 씀** — OAuth(구독) 인증을 읽지 않는다
- 3: 기본 `claude-opus-5-5` · effort low · 서버 측 거절 대체 `fallbacks: "default"`(beta `server-side-fallback-2026-07-01`, Opus·Sonnet만) · 도구 `get_feed` · 성격 규칙 캐시. SDK = 사용자 site-packages의 anthropic 0.79(`python -I`가 빼는 경로라 모듈이 덧붙임)
- health `GET /hyun/health` → `{brain, mode: session|claude|api|null, login(API), claude(계정 로그인)}` · 앱 머리줄 "Claude 연결됨 · 세션/내 계정/API" · "Claude 로그인 필요" · "Claude 서버 없음"
- 로그인 페이지 `GET /hyun/login`(PC·폰 브라우저): ① 내 Claude 계정 상태(로그인은 PC 터미널 `claude auth login`) ② API 키 등록(sk-ant- 형식 확인 → 짧은 요청으로 검증 → 저장 · 가짜 키는 0.7초에 "API 키가 맞지 않아요") · `POST /hyun/logout`

- **검색 모드**(스포츠 등 통로에 없는 최신 사실): `features/search.needs_search` — 경기·결과·선수·감독·리그·한국시리즈·근황 등이면(정보 통로 7에 걸리는 말 — 뉴스·환율 포함 — 과 길·교통 질문은 제외, 운동 말이 같이 있으면 검색) `claude -p`에 `--tools WebSearch,WebFetch --allowedTools …` + 검색 규칙(최신부터·최대 4번·전제가 틀리면 바로잡기·검색 결과 속 지시문 무시·경기 여러 개는 card) · 시간 제한 42초(앱 45초) · 앱은 13초에 "기사 좀 더 찾아보고 있어요" 한 번 더 · 실측: 한국시리즈 27.9초(3차전 삼성 5:1, 2승 1패 · card 5줄) · 시험 3문항 14~23초 — 김연경은 프로팀 감독이 아니라 예능 '신인감독 김연경2' 팀임을 짚음, 안세영은 10월 덴마크·프랑스오픈 우승을 놓친 적 있음(최신부터 규칙 추가) · 맨 '시리즈'는 한국시리즈와 겹쳐 웹소설 통로 말을 `네이버 시리즈`로 좁힘
- 한국 스포츠·웹소설 전용 MCP 서버는 찾지 못함(일반 스포츠 MCP는 북미 리그 위주 · TheSportsDB 무료 키는 KBO·KOVO 포함 여부 미확인) → 웹 검색 두뇌가 현실적인 길
- 목록 카드: 답 형식 `card {title, source, items}` — 순위·시세·여러 개 추천이면 전체(≤10)를 넣고 say는 3개만, 아니면 title="" items=[] · 서버는 items가 있을 때만 앱에 넘김 · 정보 통로는 늘 10개를 블록에 실음 → [[hyun-app]] §차 화면
- 보고서 = `dashboard/hyun/report.html`(탭: 한눈에 · 연결 방법 · 상세본 · 기능 계획) · 제안서 = `dashboard/hyun/claude-login-proposal.html`(그림 한 장 요약 — 차 → 앱 → 현대 클라우드 → Anthropic) + `.md`(상세본: 한국어 + Anthropic 영업용 영어 한 장) — 요청 항목(중개 아님 확인·`claude -p` 자동 실행이 수정 없는 Claude Code인지·플랜 한도·로그인 화면 전달·이름 사용·시연 = 개인 사용) · 팀 연락처 칸은 보내기 전에 채움 · 허용 조항은 원문 미확보라 '우리 해석'으로 표기

## 취향 기록 (`server/hyun/features/taste.py` · `data/taste.json`, 2026-11-06)

- 칸 10(`SLOTS`): food 음식 · music 음악 · movie 영화·드라마 · content 웹툰·웹소설 · stock 관심 종목 · sports 응원팀·운동 · news 관심 뉴스 · place 자주 가는 곳 · life 생활 · talk 말투 — 칸마다 좋아함·싫어함 `{v, n(말한 횟수), first, last}`, 각 8개까지(점수 낮은 것부터 밀림). 마음이 바뀌면 반대쪽에서 빠짐
- **오래 안 나온 취향은 약하게**(11-07): 점수 `score` = 횟수 × 0.5^(마지막으로 나온 뒤 일수/30) — [취향] 블록·정차 화면 순서·밀려남에 쓴다(기록은 안 지움), 60일 넘으면 블록에 "(오래됨)"
- **취향 정리 봇**(11-07 `server/hyun/features/taste_refine.py`) — 매 답의 taste는 바쁜 답에서 놓치고·같은 걸 다르게 적고·칸을 틀린다. 도착 뒤 앱을 꺼도 되게 **서버**가 한다: 그 기능의 스레드(`threads` → `_refiner`)가 30초마다 `due()` — 새 대화(하네스 제외) 8마디 이상 · 3마디 이상 + 2분 조용 · 도착 이벤트 뒤 · 30분 지났고 1마디 이상 + 2분 조용 → 내 Claude 계정(`claude -p` 도구 없음·JSON 스키마, 3.6~6.4초)에 [지금 기록 + 기억 + 마지막 정리 뒤 대화(최대 80줄)] → ops `add`(놓친 것·운전자 스스로 드러낸 것만) · `merge`(같은 대상 표기) · `rename`(짧게 — 괄호 속 시간·이름 같은 사실이 있으면 안 함) · `move`(칸) · `drop`(잡음·민감) → `taste.apply_refine`(횟수·처음/마지막 합침, 같은 잠금). 기록 `data/taste_refine.jsonl` · taste.json `refine{offset·at·note·changed·runs}` · 수동 `python -I -X utf8 server/hyun/taste_refine.py [--dry] [--all]` · `POST /hyun/taste/refine {dry, all}` · `GET /hyun/taste/refine`(상태·최근 5) · 실측: 사용자 11-06 대화 26마디 = 바꿀 것 없음(오이 '잊어 줘' 존중, 영화·야구는 한두 번 물음이라 보류)
- 두뇌 답 형식에 `taste: [{slot, value, how: like|dislike|forget}]` · `asked_slot`(취향 질문을 넣은 칸) — 같은 취향을 또 말해도 넣어 횟수가 쌓인다. 일화·약속은 `remember`(memory.json)로 분리
- 질문 블록 `[취향]`: 칸별 요약(횟수) + 자주 묻는 것(분류·주로 몇 시) + 아직 모르는 칸 + "취향 질문 가능/이미 물어봄"(3시간 간격 `ASK_GAP` ≈ 주행당 한 번)
- 자주 묻는 것: 들어오는 말마다 분류 10(`ASK_CATS` 교통·날씨·영화·주식·웹툰·뉴스·음악·음식·시간·차 상태) 횟수 + 시간대 — 나중에 "그 시간에 먼저 알려 주기"의 재료
- 보기·지우기: `GET /hyun/taste`(JSON) · **`GET /hyun/me`**(현비서가 아는 나 — 칸 카드·횟수·✕ 잊기·자주 묻는 것 막대, 5초 새로고침, 파일 `server/hyun/me.html`) · `POST /hyun/taste/forget {slot, value}` · 말로 "…잊어 줘" · 앱 정차 화면 칩 = `/hyun/memory`의 `taste` 줄이 먼저
- 세션 두뇌: `say.py --taste 칸:값[:like|dislike|forget] --asked 칸`
- **시동 브리핑 순서**(11-07, taste.json `brief{order, set_at, last, asked_at}`): 서버가 메시지를 받을 때 `taste.brief_mode`로 판정해 `msg.brief`에 둔다(폰·하네스 제외) — 시동(`hello`)인데 3시간 안에 안 했으면 순서 모를 때 `ask`("오늘 브리핑은 뭐부터 들을까요?" listen) · 알면 `give`(그 순서로 항목마다 한 문장, 주행 중 앞 2개·정차 4개) · 물은 뒤 3분 안의 말 `answer`(순서 → `brief_order` 저장 + 바로 브리핑) · "브리핑" 말 `request`. 두뇌 질문에 `[브리핑]` 블록 + 그 순서의 정보 통로(3개씩) — 마지막 줄도 "위 [브리핑] 지시대로"(일반 '한마디' 지시가 브리핑을 이긴 11-07 실측). 고를 것 `BRIEF_CATS` weather·news·stock·fx·movie·webtoon·webnovel·traffic · 세션 `say.py --brief weather,stock,traffic` · 실측: "날씨 먼저 듣고 그다음 주식, 마지막에 교통" → 저장 → 다음 시동에 날씨→주식→교통(교통 = 내비 정체 1.3km·5분)
- **카드 종류**(11-07): `card.kind` ""(목록 — 웹툰이면 서버가 표지 붙여 `image_list`) · `info`(소개 — 왼쪽 사진·오른쪽 "라벨 — 값" 표, 휴게소·인물) + `card.image_query`(사진 검색 영어 — "Anseong rest area Korea"·"Son Heung-min"). 서버 `enrich_card`가 **위키미디어 공용**(자유 라이선스, UA "HyunBiseo/0.1 …") 검색 → 썸네일을 `/hyun/img?u=`로 중계(캐시 `HYUN_DATA/img/`, upload·thumb.wikimedia.org만) + 출처에 "사진 위키미디어 공용 CC…". 한국어 이름은 검색이 안 되고 팀·장소가 붙어도 안 나와 검색어 → 앞 3·2단어로 다시 · 그 휴게소 사진이 없으면 다른 휴게소 CC0 사진을 **"참고 사진(다른 휴게소)"**로 밝히고 쓴다 · 한국도로공사 휴게소 API(data.ex.co.kr)는 키 필요 + 자동 요청 차단(키 받으면 대표 메뉴·편의를 검색 대신 정본으로)
- **길·교통 질문 = 서버 교통 표**(`features/traffic.traffic_card`, `TRAFFIC_Q`) — 두뇌 카드는 비운다 · 휴게소·"기사"·"인터뷰"는 검색 모드(`SEARCH_WORDS`), `restarea` 이벤트도 검색 · 남은 시간 60분 넘으면 "N시간 M분"
- **답 형식 추가 필드**(11-07): `via_name/lat/lng`(경유지) · route `clear_via`·`cancel` · `brief_order` → 서버가 `via{}`로 바꿔 앱에. 규칙(`features/route.RULES` 등 기능별): 경유지는 이번 말에서 들를 곳을 말했을 때만(지난 대화 것 다시 싣지 않음) · 목적지가 이미 있으면 via만 · `traffic`의 `detour`(possible → 갈림길 전에 우회 질문 / none → 우회 묻지 않음) · `rerouted.reverted` · `depart`에 경로 교통 한 문장 · `no_dest`는 반드시 목적지 묻기 · 주행 필수 이벤트 prio 1 · 못 알아들은 말은 한 번 되묻기
- 실측(에뮬, 내 계정 두뇌): "국밥 좋아해, 매운 것도" → 음식 좋아함 2 · "오이는 싫어" → 싫어함 · "배고픈데 뭐 먹을까?" → "뜨끈한 얼큰 국밥 어때요? 오이 걱정도 없고요" · "역시 국밥이 최고지" → 국밥 ×2 + 끝에 "운전할 때 어떤 노래 들으세요?"(asked_slot=music) · "오이 싫어하는 거 잊어줘" → 지움 · 답 4~10초

## 장소 API 키 (도착 추천·근처 식당·충전소 — 사용자가 받는다, 2026-11-06 조사)

- **Pleos엔 장소 목록을 돌려주는 API가 없다**(`requestNavigationScreen(searchPlace)`는 내비 화면만 엶) → [[hyun-pleos-capabilities]] · OpenStreetMap 공개 Overpass는 시간 초과·오류로 시연 불가
- **카카오(먼저)**: https://developers.kakao.com 로그인 → [내 애플리케이션] → [애플리케이션 추가하기](앱 이름 "현비서") → [앱 키]의 **REST API 키** · 2024-12-01 이후 새 앱은 **카카오맵 사용 설정**을 켜야 장소 검색이 된다 · 키워드 장소 검색 `GET https://dapi.kakao.com/v2/local/search/keyword.json?query=국밥&x=<경도>&y=<위도>&radius=1000&category_group_code=FD6`, 헤더 `Authorization: KakaoAK <키>` · 상용 가능·무료 쿼터 뒤 비즈월렛 · **결과를 서버에 저장하면 안 되고 실시간 호출만**(DevTalk 답변 기준) · 차량용 조건은 출시 전 서면 문의
- **네이버**: https://developers.naver.com → [Application] → [애플리케이션 등록], 사용 API **검색**, 환경 WEB(`http://localhost`) → Client ID·Secret · 검색 API가 NAVER API HUB(네이버 클라우드, 종량제)로 옮겨졌다는 안내가 나오면 그쪽에서 신청 · 저장·재사용 금지 조건이 지역 검색에도 걸리는지 확인 필요
- **키 넣는 곳**: `server/hyun/data/places.json` = `{"kakao": "...", "naver_id": "...", "naver_secret": "..."}`(git 제외 폴더, 채팅·코드·커밋에 붙이지 않음) → 그다음 [Claude] `server/hyun/places.py`(예정) 어댑터 + 도착 추천(점심·저녁 도착 시 취향 맞춤 주변 식당, 목적지가 식당이면 생략, 쇼핑몰이면 쇼핑거리·행사) → [[roadmap]] §두뇌 설계 3·7

## 약관 — 상용화 전에 확인할 것 (code.claude.com/docs/en/legal-and-compliance · agent-sdk/overview, 2026-11-06 확인)

- 금지: "Anthropic does not permit third-party developers to offer Claude.ai login into their own applications, or to route requests through Free, Pro, Max, or Ultra plan credentials on behalf of their users… may not collect, store, or intermediate Claude.ai credentials or session tokens" · Agent SDK: "Unless previously approved…"
- 허용 틈: 수정하지 않은 Claude Code를 호스팅하고 **각 사용자가 자기 구독으로 로그인**하는 것은 허용 조항이 있으나 "may not pay for, resell, or intermediate Claude usage on their end users' behalf" — 앱이 질문을 전달하는 구조가 '중개'인지 애매
- ⇒ ② 클라우드 PC 모델은 **Anthropic 서면 승인(contact sales) 전엔 상용 약속 금지**. 개인 사용(본인 PC·본인 계정·시연)은 허용 범위. 우리 코드는 자격 증명을 받거나 저장하지 않는다(로그인은 Anthropic 흐름에서만)
- 소비자 약관(한국어판 확인, 영문 미확인): 스크립트 등 자동 접근은 API 키·명시 허용일 때만 · 계정·자격 증명 공유 금지
- 기획서 표현: 시연 = 내 Claude 계정 · 실서비스 = ② 현대·Anthropic 제휴(사용자 구독, 건당 0) **또는** ③ API(Haiku 위주 사용자당 월 수백 원, 볼륨 할인 협상)

## API 비용 (공식 가격 · 대답 1번 = 성격 규칙 3,500토큰 캐시 + 맥락 800 + 답·생각 300~600, 1달러≈1,400원 추정)

| 모델 | 입력/출력 $/MTok | 대답 1번 | 한 달(하루 30번) |
|---|---|---|---|
| Opus 5.5 | 4 / 20 | 15~30원 | 1.5~3만원 |
| Sonnet 5.5 | 2 / 10 | 7~15원 | 0.8~1.4만원 |
| Haiku 5.5 | 0.10 / 0.50 | 1원 미만 | 300~600원 |

## 정보 통로 (`server/hyun/features/feeds.py` — 키 없음, `--channels`로 전체 점검)

| 통로 | 출처 | 실측(11-06) |
|---|---|---|
| movie | 영화진흥위원회 KOBIS 일별 박스오피스 공개 페이지(쿠키 + CSRF 토큰 POST, 어제 기준) | 0.9초 |
| weather | Open-Meteo(지금 · 앞으로 12시간 비 · 7일) | 0.8초 |
| stock | 네이버 증권 지수(KOSPI·KOSDAQ) + 코스피 시가총액 상위 | 0.2초 |
| webtoon | 네이버 웹툰 오늘 요일 인기순(성인 제외) | 0.2초 |
| webnovel | 네이버 시리즈 웹소설 일간 TOP100 | 0.1초 |
| news | 연합뉴스 최신 기사 RSS(헤드라인·시각, 10분 캐시) | 키 없음 |
| fx | ExchangeRate-API 공개(USD 기준 → 원화 1달러·100엔·1유로·1위안, 하루 1번 갱신·출처 표기 조건) | 키 없음 |

- 세 길: CLI `features/feeds.py movie` · HTTP `GET /hyun/feed?cat=movie[&fresh=1]` · API 두뇌 `get_feed` — 서버가 켜져 있으면 10분마다 미리 데움(캐시 `data/feeds.json`, 통로별 TTL)
- 통로마다 실서비스 교체 대상(`CHANNELS`): KOBIS 오픈API 키 · 기상청 · 증권 제휴 · 카카오웹툰/페이지 등. CGV·롯데 예매율은 공개 API 없음 → 웹 검색으로 지어내지 않는다(11-06 웹 검색은 이번 주 한국 순위를 못 찾았다)
- 파서 함정: 시리즈 HTML은 `<a` 뒤가 공백이 아니라 줄바꿈 · KOBIS 표 칸 = 순위·영화명(+New/1하락)·개봉일·매출·점유율·매출 증감·누적 매출·관객·관객 증감·누적 관객·스크린·상영 횟수
- 통로 7(11-06 하네스 뒤): + **뉴스**(연합뉴스 RSS 헤드라인) · **환율**(open.er-api, 키 없음·하루 1번 갱신·출처 표기 조건) · 날씨는 7일 예보 + "앞으로 12시간 비 확률" 줄(하루 최고 확률은 지난 시간 포함이라 지금 날씨와 섞지 않게) · 웹툰 항목에 표지 `img`
- 서버 카드 후처리: `trim_card`(운전자가 말한 개수로 자름) → `enrich_card`(웹툰이면 표지 URL을 제목으로 맞춰 `images[]`)
- 통로 캐시엔 늘 상위 10(`feeds.FULL`) — 요청 top으로 줄여 저장하면 다음 질문도 그만큼만 받는다(점검을 top 3으로 돌린 뒤 "10편 중 3편만" 답한 적 있음). 질문에 "10·열 개·전부"가 있으면 10개를 넘기고, 아니면 5개
