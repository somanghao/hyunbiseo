"""현비서 '클라우드 PC' 두뇌 — 사용자 본인 Claude 계정으로 로그인된 Claude Code(수정 없음)를 헤드리스(`claude -p`)로 실행해 답한다.

상용화 모델(사용자 결정 2026-11-06): 현대가 사용자마다 클라우드 PC를 두고, 사용자가 그 PC의 Claude Code에 **Anthropic 공식 로그인 흐름으로 직접** 로그인한다.
현비서 앱은 그 PC에 말을 보내고, 답은 사용자 본인 구독 안에서 나온다(건당 API 요금 없음). 지금은 이 PC가 그 클라우드 PC 역할.

약관 메모(code.claude.com/docs/en/legal-and-compliance, 2026-11-06 확인):
- 수정하지 않은 Claude Code를 호스팅하고 각 사용자가 자기 구독으로 로그인하는 것은 허용 조항이 있으나,
  "pay for, resell, or intermediate Claude usage on their end users' behalf" 금지 — 앱이 질문을 전달하는 구조가 '중개'인지 애매 → 상용화 전 Anthropic 서면 승인(contact sales) 필요
- 우리 코드는 자격 증명을 받거나 저장하지 않는다 — 로그인은 PC 터미널의 `claude auth login`(Anthropic 흐름)으로만
- 개인 사용(본인 PC·본인 계정·시연)은 허용 범위

실측(2026-11-06, 이 PC): 구조화 답 포함 5.1초(haiku) · 5.3초(sonnet) — 세션 수동 답(10~20초)보다 빠름
"""
import json
import os
import shutil
import subprocess
import tempfile
import time

from brain import prompt as common

CLAUDE = shutil.which("claude")
MODEL = os.environ.get("HYUN_CLI_MODEL", "sonnet")   # 음성 지연: sonnet 5.3초 ≈ haiku 5.1초 → 더 똑똑한 쪽
# 빈 작업 폴더 — 프로젝트 CLAUDE.md·설정을 읽지 않게(시작 시간·토큰 절약)
CWD = os.environ.get("HYUN_BRAIN_CWD") or os.path.join(os.environ.get("PUBLIC", tempfile.gettempdir()), "hyun-brain")
TIMEOUT = 40
SEARCH_TIMEOUT = 70   # 앱이 말 대답을 75초까지 기다린다(11-11: 45초에선 넷플릭스 순위·경기 일정 검색이 자주 잘림)
_status = {"at": 0.0, "v": None}


def status(force=False):
    """이 PC의 Claude Code 로그인 상태 — {loggedIn, authMethod} (60초 캐시). 자격 증명 자체는 읽지 않는다"""
    if not CLAUDE:
        return {"loggedIn": False, "error": "claude CLI 없음"}
    if not force and _status["v"] and time.time() - _status["at"] < 60:
        return _status["v"]
    try:
        r = subprocess.run([CLAUDE, "auth", "status"], capture_output=True, text=True, encoding="utf-8", timeout=15)
        j = json.loads(r.stdout or "{}")
        v = {"loggedIn": bool(j.get("loggedIn")), "authMethod": j.get("authMethod"), "model": MODEL}
    except (OSError, ValueError, subprocess.TimeoutExpired) as e:
        v = {"loggedIn": False, "error": type(e).__name__}
    _status.update(at=time.time(), v=v)
    return v


def ready():
    s = status()
    return bool(s.get("loggedIn"))


def answer(msg, memory, recent, search=False):
    """(답 dict, 실패 이유) — 답은 say·listen·mood·prio·remember (+ _ms, _model)
    search=True: 스포츠·뉴스처럼 최신 사실이 필요한 질문 — 웹 검색 도구를 열어 준다(11-06 실측 14~23초, 앱은 45초까지 기다림)"""
    if not CLAUDE:
        return None, "claude CLI 없음"
    os.makedirs(CWD, exist_ok=True)
    tools = ["--tools", "WebSearch,WebFetch", "--allowedTools", "WebSearch,WebFetch"] if search else ["--tools", ""]
    limit = SEARCH_TIMEOUT if search else TIMEOUT
    args = [CLAUDE, "-p", common.user_block(msg, memory, recent),
            "--model", MODEL, "--output-format", "json", "--json-schema", json.dumps(common.reply_schema(), ensure_ascii=False),
            "--system-prompt", common.persona(search), *tools,
            "--strict-mcp-config", "--setting-sources", "local", "--no-session-persistence"]
    t0 = time.time()
    try:
        r = subprocess.run(args, capture_output=True, text=True, encoding="utf-8", timeout=limit, cwd=CWD)
    except subprocess.TimeoutExpired:
        return None, f"시간 초과 {limit}초"
    except OSError as e:
        return None, f"실행 실패 {e}"
    try:
        j = json.loads(r.stdout)
    except ValueError:
        return None, f"출력 해석 실패(rc={r.returncode}) {r.stderr.strip()[:120]}"
    if j.get("is_error"):
        return None, f"오류 {str(j.get('result'))[:120]}"
    data = j.get("structured_output")
    if not isinstance(data, dict):
        try:
            data = json.loads(j.get("result") or "")
        except ValueError:
            return None, "답 형식 오류"
    data["_ms"] = int((time.time() - t0) * 1000)
    data["_model"] = MODEL
    return data, None
