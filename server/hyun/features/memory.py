"""기억 — 호칭 · 일화 · 약속 · 사실(취향은 taste) → data/memory.json · [기억] 블록 · /hyun/memory(앱 정차 화면 '현비서가 아는 것' — 취향 줄 + 기억)."""
from core import store

SCHEMA = {
    "remember": {"type": "array", "items": {"type": "string"}, "description": "새로 배운 운전자 취향·사실(짧게), 없으면 빈 배열"},
}

RULES = """- remember엔 취향이 아닌 일화·약속·사실만 짧게. 민감 정보(건강·종교·정치·금융 계좌 등)는 어디에도 남기지 않는다"""


def block(msg, env):
    m = env["memory"]
    if m.get("name") or m.get("facts"):
        return f"[기억] 호칭: {m.get('name') or '없음'} · " + " / ".join(m.get("facts", [])[-20:])
    return None


def post(rep, msg, b, dry, rec):
    remember = [] if dry else [x for x in (b.get("remember") or []) if x.strip()]
    rec["remember"] = remember
    if remember or (b.get("name") and not dry):
        mem = store.load_memory()
        if b.get("name"):
            mem["name"] = b["name"]
        for x in remember:
            if x not in mem["facts"]:
                mem["facts"].append(x)
        store.save_memory(mem)


def _get_memory(h, q):
    from features import taste
    h.send_json({**store.load_memory(), "taste": taste.lines_for_app()})


GET = {"/hyun/memory": _get_memory}
