"""Claude 로그인 페이지 — ① 내 Claude 계정(이 PC의 Claude Code · `claude auth login`, 상태만 보여 줌) ② API 키 등록(data/claude.json).
/hyun/login(PC·폰 브라우저 — 차 화면엔 주소만 안내) · /hyun/login/status · POST /hyun/login {key, model} · /hyun/logout
"""
import html
import time

from brain import cli as brain_cli
from core import engine, store


def _api():
    return engine.brain_api


def login_page():
    api = _api()
    st = api.status() if api else {"loggedIn": False}
    opts = "".join(f'<option value="{k}"{" selected" if k == (st.get("model") or api.DEFAULT_MODEL) else ""}>{html.escape(v)}</option>'
                   for k, v in (api.MODELS.items() if api else []))
    now = (f'로그인됨 · {html.escape(st["model"])} · 키 끝 {html.escape(st["keyTail"])} · {html.escape(st.get("at") or "")}'
           if st.get("loggedIn") else "로그인 안 됨")
    cs = brain_cli.status(force=True)
    cli_now = (f"로그인됨 · {html.escape(str(cs.get('authMethod')))} 계정 · 모델 {html.escape(str(cs.get('model')))} · 현비서가 이 계정으로 답해요"
               if cs.get("loggedIn") else f"로그인 안 됨 — 두뇌 PC 터미널에서 claude auth login {html.escape(cs.get('error', ''))}")
    return f"""<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>현비서 Claude 로그인</title><style>
:root{{--bg:#f4f6f8;--card:#fff;--fg:#15202b;--mut:#66727f;--ln:#d9dfe5;--ac:#0a7ea4;--ok:#138a52;--bad:#c4321f}}
@media (prefers-color-scheme:dark){{:root{{--bg:#0d1216;--card:#151c22;--fg:#e7edf2;--mut:#93a1ad;--ln:#2a353e;--ac:#38b6d8;--ok:#3ccf8e;--bad:#ff6b5a}}}}
body{{margin:0;background:var(--bg);color:var(--fg);font:15px/1.6 "Malgun Gothic",system-ui,sans-serif}}
main{{max-width:520px;margin:0 auto;padding:28px 16px}}.card{{background:var(--card);border:1px solid var(--ln);border-radius:14px;padding:18px 20px}}
h1{{font-size:22px;margin:0 0 4px}}p{{color:var(--mut);margin:6px 0 14px}}label{{display:block;font-size:13px;color:var(--mut);margin:12px 0 4px}}
input,select{{width:100%;box-sizing:border-box;font:inherit;padding:9px 11px;border-radius:9px;border:1px solid var(--ln);background:var(--bg);color:var(--fg)}}
button{{margin-top:16px;font:inherit;padding:9px 16px;border-radius:9px;border:0;background:var(--ac);color:#fff;cursor:pointer}}
button.sub{{background:transparent;color:var(--mut);border:1px solid var(--ln);margin-left:6px}}#msg{{margin-top:12px;font-weight:600}}
.st{{font-size:13px;padding:6px 10px;border-radius:8px;background:var(--bg);border:1px solid var(--ln);display:inline-block}}
</style></head><body><main><div class="card">
<h1>현비서 · Claude 로그인</h1>
<h2 style="font-size:16px;margin:14px 0 4px">① 내 Claude 계정 — 클라우드 PC (추천 · 건당 요금 없음)</h2>
<p>현비서 두뇌 PC의 Claude Code에 <b>본인 Claude 계정</b>으로 로그인하면, 현비서가 그 계정 안에서 답합니다. 로그인은 그 PC 터미널에서 <code>claude auth login</code>을 실행해 뜨는 <b>Anthropic 공식 로그인 화면</b>에서 직접 합니다 — 현비서는 계정 정보를 받거나 저장하지 않습니다.</p>
<div class="st" id="cst">{cli_now}</div>
<h2 style="font-size:16px;margin:22px 0 4px">② Anthropic API 키 (대안 · 건당 요금)</h2>
<p>①이 없을 때 쓰입니다. 키는 이 PC의 현비서 서버(<code>server/hyun/data/</code>)에만 저장되고 차나 화면에는 보내지 않습니다.</p>
<div class="st" id="st">{now}</div>
<label for="key">Anthropic API 키 (console.anthropic.com → API Keys)</label>
<input id="key" type="password" placeholder="sk-ant-..." autocomplete="off">
<label for="model">모델</label><select id="model">{opts}</select>
<button id="go">연결하고 저장</button><button class="sub" id="out">로그아웃</button>
<div id="msg"></div></div></main>
<script>
const $=s=>document.querySelector(s), msg=(t,ok)=>{{$('#msg').textContent=t;$('#msg').style.color=ok?'var(--ok)':'var(--bad)'}};
$('#go').onclick=async()=>{{msg('확인 중…',true);try{{const r=await(await fetch('/hyun/login',{{method:'POST',headers:{{'Content-Type':'application/json'}},body:JSON.stringify({{key:$('#key').value,model:$('#model').value}})}})).json();
msg(r.msg+(r.ms?` · ${{r.ms}}ms`:''),r.ok);if(r.ok){{$('#key').value='';$('#st').textContent=`로그인됨 · ${{r.model}} · 키 끝 ${{r.keyTail}}`}}}}catch(e){{msg('서버에 닿지 않아요',false)}}}};
$('#out').onclick=async()=>{{await fetch('/hyun/logout',{{method:'POST'}});$('#st').textContent='로그인 안 됨';msg('로그아웃했어요',true)}};
</script></body></html>"""


def _page(h, q):
    h.send_html(login_page())


def _status(h, q):
    api = _api()
    h.send_json({"api": api.status() if api else {"loggedIn": False, "error": "anthropic SDK 없음"},
                 "claude": brain_cli.status(force=bool(q.get("fresh")))})


def _login(h, b):
    """{key, model} — 짧은 요청으로 확인한 뒤 저장"""
    api = _api()
    if not api:
        return h.send_json({"ok": False, "msg": "서버에 anthropic SDK가 없어요(pip install anthropic)"})
    key, model = (b.get("key") or "").strip(), b.get("model") or api.DEFAULT_MODEL
    if not key.startswith("sk-ant-"):
        return h.send_json({"ok": False, "msg": "Anthropic API 키는 sk-ant- 로 시작해요"})
    ok, msg, ms = api.check(key, model)
    if ok:
        api.save_cred(key, model)
    store.append_log({"dir": "app", "ts": time.strftime("%H:%M:%S"), "login": ok, "model": model, "ms": ms})
    h.send_json({"ok": ok, "msg": msg, "ms": ms, **api.status()})


def _logout(h, b):
    if _api():
        _api().logout()
    h.send_json({"ok": True})


GET = {"/hyun/login": _page, "/hyun/login/status": _status}
POST = {"/hyun/login": _login, "/hyun/logout": _logout}
