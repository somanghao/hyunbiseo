"""최근 대화 — [최근 대화] 블록(운전자 · 현비서 8줄, 하네스 시험 대화 제외 — core.store.recent_text)."""


def block(msg, env):
    recent = env.get("recent") or []
    return "[최근 대화]\n" + "\n".join(recent[-8:]) if recent else None
