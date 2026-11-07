"""현비서 API 두뇌 — Claude Code 세션이 없을 때 Claude API가 같은 자리에서 답한다(실서비스 두뇌의 모양).

로그인 = API 키 등록: 차 화면/음성이 안내하는 PC·폰 브라우저 페이지(features/login — GET /hyun/login)에서 키를 넣으면
data/claude.json(git 제외)에 저장하고, 서버가 세션이 비어 있을 때 이 모듈로 답한다.
(Anthropic은 일반 Claude 계정으로 다른 앱에 로그인하는 OAuth를 제공하지 않는다 — 그래서 '로그인' = API 키)

- 모델 기본 claude-opus-5-5, effort low(음성 응답 지연 우선) · 로그인 페이지에서 바꿀 수 있다
- 성격·말하기 규칙 = persona.md(시스템, 캐시) · 맥락·기억·최근 대화 = 사용자 메시지(매번 바뀜)
- 도구 1개 get_feed(category) — features/feeds 통로(영화·날씨·주식·웹툰·웹소설·뉴스·환율). 지어내지 않고 출처를 말한다
- 답 = 구조화 출력 {say, listen, mood, prio, remember} — say.py와 같은 필드
"""
import json
import os
import site
import sys
import time

# python -I(격리)는 사용자 site-packages를 빼는데 anthropic SDK가 거기(pip --user) 깔려 있다 — 맨 뒤에 붙여 찾게 한다
_user_site = site.getusersitepackages()
if os.path.isdir(_user_site) and _user_site not in sys.path:
    sys.path.append(_user_site)

import anthropic  # noqa: E402

from brain import prompt as common  # noqa: E402
from core import config  # noqa: E402

CRED = config.data("claude.json")
DEFAULT_MODEL = "claude-opus-5-5"
MODELS = {  # 로그인 페이지 선택지 — 음성은 지연이 중요해 effort를 낮게 둔다
    "claude-opus-5-5": "Claude Opus 5.5 (기본 · 가장 똑똑함)",
    "claude-sonnet-5-5": "Claude Sonnet 5.5 (빠름)",
    "claude-haiku-5-5": "Claude Haiku 5.5 (가장 빠름 · 저렴)",
}
FALLBACK_OK = {"claude-opus-5-5", "claude-sonnet-5-5"}   # 서버 측 거절 대체(fallbacks: "default")를 받는 모델

def _tools():
    """도구 get_feed — 정보 통로(features/feeds). 기능 목록을 가져오는 순서와 엇갈리지 않게 부를 때 만든다"""
    from features import feeds
    return [{
        "name": "get_feed",
        "description": "Fetch live Korean popularity rankings, weather, news or exchange rates from the assistant's data channels: "
                       "movie (KOBIS daily box office), weather (current and today), stock (KOSPI/KOSDAQ index and top market cap), "
                       "webtoon (Naver Webtoon today's popular), webnovel (Naver Series daily top), news (headlines), fx (exchange rates). "
                       "Returns lines with source and time.",
        "strict": True,
        "input_schema": {
            "type": "object",
            "properties": {"category": {"type": "string", "enum": list(feeds.FETCH)}},
            "required": ["category"],
            "additionalProperties": False,
        },
    }], feeds


def load_cred():
    try:
        with open(CRED, encoding="utf-8") as f:
            c = json.load(f)
        return c if c.get("key") else None
    except (OSError, ValueError):
        return None


def save_cred(key, model):
    os.makedirs(config.DATA, exist_ok=True)
    with open(CRED + ".tmp", "w", encoding="utf-8") as f:
        json.dump({"key": key, "model": model if model in MODELS else DEFAULT_MODEL, "at": time.strftime("%Y-%m-%d %H:%M")}, f)
    os.replace(CRED + ".tmp", CRED)


def logout():
    try:
        os.remove(CRED)
    except OSError:
        pass


def status():
    c = load_cred()
    return {"loggedIn": bool(c), "model": c.get("model") if c else None, "keyTail": c["key"][-4:] if c else None, "at": c.get("at") if c else None}


def _client(key):
    return anthropic.Anthropic(api_key=key, timeout=30.0, max_retries=1)


def _create(client, model, **kw):
    extra = {}
    if model in FALLBACK_OK:   # 안전 분류기가 거절하면 서버가 대체 모델로 다시 돌린다(기본 켬)
        extra = {"extra_headers": {"anthropic-beta": "server-side-fallback-2026-07-01"}, "extra_body": {"fallbacks": "default"}}
    return client.messages.create(model=model, **kw, **extra)


def check(key, model):
    """키 확인 — 짧은 요청 하나. (ok, 메시지, ms)"""
    t0 = time.time()
    try:
        r = _create(_client(key), model, max_tokens=64, output_config={"effort": "low"},
                    messages=[{"role": "user", "content": "한 단어로만 답해: 연결"}])
        txt = next((b.text for b in r.content if b.type == "text"), "")
        return True, f"연결됨 ({r.model}) — {txt.strip()[:20]}", int((time.time() - t0) * 1000)
    except anthropic.AuthenticationError:
        return False, "API 키가 맞지 않아요", int((time.time() - t0) * 1000)
    except anthropic.PermissionDeniedError:
        return False, "이 키로는 이 모델을 쓸 권한이 없어요", int((time.time() - t0) * 1000)
    except anthropic.NotFoundError:
        return False, f"모델을 찾을 수 없어요({model})", int((time.time() - t0) * 1000)
    except anthropic.RateLimitError:
        return False, "요청 한도에 걸렸어요 — 잠시 뒤에", int((time.time() - t0) * 1000)
    except anthropic.APIStatusError as e:
        return False, f"API 오류 {e.status_code}", int((time.time() - t0) * 1000)
    except anthropic.APIConnectionError:
        return False, "Anthropic 서버에 연결할 수 없어요(인터넷 확인)", int((time.time() - t0) * 1000)


def answer(msg, memory, recent):
    """메시지 하나에 대한 답(dict: say·listen·mood·prio·remember) — 실패하면 None과 이유"""
    cred = load_cred()
    if not cred:
        return None, "로그인 안 됨"
    model = cred.get("model") or DEFAULT_MODEL
    client = _client(cred["key"])
    tools, feeds = _tools()
    messages = [{"role": "user", "content": common.user_block(msg, memory, recent)}]
    system = [{"type": "text", "text": common.persona(), "cache_control": {"type": "ephemeral"}}]   # 성격 규칙은 고정 — 캐시
    try:
        for _ in range(4):   # get_feed 최대 몇 번
            r = _create(client, model, max_tokens=2000, system=system, tools=tools, messages=messages,
                        output_config={"effort": "low", "format": {"type": "json_schema", "schema": common.reply_schema()}})
            if r.stop_reason == "refusal":
                return None, "거절됨"
            if r.stop_reason == "tool_use":
                messages.append({"role": "assistant", "content": r.content})
                results = []
                for b in r.content:
                    if b.type == "tool_use":
                        cat = (b.input or {}).get("category", "")
                        out = feeds.text(cat) if cat in feeds.FETCH else f"모르는 카테고리: {cat}"
                        results.append({"type": "tool_result", "tool_use_id": b.id, "content": out, "is_error": out.startswith(f"[{feeds.NAMES.get(cat, '')}] 가져오기 실패")})
                messages.append({"role": "user", "content": results})
                continue
            txt = next((b.text for b in r.content if b.type == "text"), "")
            data = json.loads(txt)
            data["_model"] = r.model
            return data, None
        return None, "도구를 너무 많이 불렀음"
    except (anthropic.APIStatusError, anthropic.APIConnectionError) as e:
        return None, f"{type(e).__name__}"
    except (ValueError, StopIteration) as e:
        return None, f"답 형식 오류 {type(e).__name__}"
