"""현비서 서버 기능 — 기능 모듈 하나 = 앱 기능(Feature) 하나의 서버 짝. **새 기능 = 모듈 파일 하나 + 아래 ALL 한 줄.**

모듈에 둘 수 있는 것(전부 선택 — 필요한 것만):
  RULES: str                          자동 두뇌 규칙 조각 — 시스템 프롬프트(persona.md + 기본 규칙) 뒤에 등록 순서로 이어 붙음
  SCHEMA: dict                        자동 두뇌 답 형식 필드 {이름: JSON 스키마} — 전부 required로 들어감
  block(msg, env) -> str | None       질문 블록 — env = {ctx, memory, recent}, 등록 순서로 이어 붙음
  feed_cats(msg) -> list[str]         같이 실을 정보 통로(feeds 기능이 모아 [정보 통로] 블록으로)
  last_line(msg) -> str | None        질문 마지막 줄을 바꿈(브리핑 — 일반 '한마디' 지시 대신)
  search(msg) -> bool                 검색 모드(WebSearch·WebFetch)가 필요하면 True
  on_in(msg)                          앱 메시지가 들어올 때 — 판정을 msg에 적어 둠(브리핑 판정 등)
  normalize(data)                     자동 두뇌의 평평한 필드 → 앱이 받는 모양(nav_* → nav{})
  post(rep, msg, b, dry, rec)         답 등록 — rep(앱에 갈 답) 채우기·저장, rec(로그에만), dry = 하네스 시험(저장하지 않음)
  GET / POST: {경로: fn(h, q | body)} HTTP 라우트(h = core.http.Handler — send_json · send_html · send_bytes)
  threads() -> [callable]             서버가 띄울 백그라운드 일(예열 · 정리 봇)

순서가 뜻이 있다 — 질문 블록: 길 → 기억 → 취향 → 최근 대화 → 브리핑 → 정보 통로 · 답 등록: 길 → 교통 표 → 카드(손질·사진) → 기억 → 취향 → 브리핑 → 폰.
앱 쪽 짝 = android/hyun/…/feature/ (Registry.kt). 설계 → .claude/docs/wiki/hyun-architecture.md
"""
from features import (briefing, cards, feeds, history, login, memory, phone, restarea, route, search, taste, taste_refine,
                      testaudio, traffic)

ALL = [
    route,          # 목적지 · 경유지 · 우회 · 안내 끝 · [길·교통] 블록 · 주행 이벤트 규칙
    traffic,        # 길·교통 질문 → 내비 값으로 교통 표(카드)
    cards,          # 카드 형식(목록 · 표지 격자 · 소개) · 개수 맞추기 · 사진(위키미디어 공용) · /hyun/img
    memory,         # 기억(호칭 · 일화) · [기억] 블록 · /hyun/memory
    taste,          # 취향 누적 · 취향 질문 · [취향] 블록 · /hyun/taste · /hyun/me
    history,        # [최근 대화] 블록
    briefing,       # 시동 브리핑 순서 · [브리핑] 블록
    feeds,          # 정보 통로 7(영화 · 날씨 · 주식 · 웹툰 · 웹소설 · 뉴스 · 환율) · [정보 통로] 블록 · /hyun/feed
    search,         # 검색 모드(스포츠 · 기사 · 근황) · 인물 기사 카드
    restarea,       # 휴게소 소개(검색 모드 + 소개 카드)
    phone,          # 짭비서 — 폰 모드 · 리마인더 · 할 일 · /hyun/tasks
    taste_refine,   # 취향 정리 봇(서버 스레드) · /hyun/taste/refine
    login,          # Claude 로그인 페이지(API 키) · /hyun/login
    testaudio,      # 귀 시험 녹음 · /hyun/testaudio
]


def hook(name, *args):
    """모든 기능의 같은 이름 훅을 차례로"""
    for f in ALL:
        fn = getattr(f, name, None)
        if fn:
            fn(*args)


def any_hook(name, *args):
    return any(bool(getattr(f, name)(*args)) for f in ALL if hasattr(f, name))


def collect(name, *args):
    """훅이 돌려준 목록을 이어 붙인다(feed_cats 등)"""
    out = []
    for f in ALL:
        fn = getattr(f, name, None)
        if fn:
            out += fn(*args) or []
    return out


def routes(method):
    table = {}
    for f in ALL:
        table.update(getattr(f, method, {}) or {})
    return table
