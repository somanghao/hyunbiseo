"""파일 기록 — 대화 로그(log.jsonl) · 기억(memory.json) · 할 일(tasks.json). 쓰기는 임시 파일 → 바꾸기(중간에 끊겨도 안 깨지게)."""
import json
import os

from core import config

LOG = config.data("log.jsonl")
MEMORY = config.data("memory.json")


def read_json(path, default):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return default


def write_json(path, obj):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)
    os.replace(tmp, path)


def load_memory():
    return read_json(MEMORY, {"name": "", "facts": []})


def save_memory(m):
    write_json(MEMORY, m)


def append_log(rec):
    os.makedirs(os.path.dirname(LOG), exist_ok=True)
    with open(LOG, "a", encoding="utf-8") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")


def recent_log(n):
    try:
        with open(LOG, encoding="utf-8") as f:
            return [json.loads(x) for x in f.readlines()[-n:]]
    except (OSError, ValueError):
        return []


def recent_text(n=8):
    """최근 대화(운전자 · 현비서) n줄 — 하네스 시험 대화는 뺀다"""
    out = []
    for r in recent_log(40):
        if r.get("harness") or (r.get("ctx") or {}).get("harness"):
            continue
        if r.get("dir") == "in" and r.get("kind") == "talk":
            out.append(f"운전자: {r.get('text', '')}")
        elif r.get("dir") == "out" and r.get("say"):
            out.append(f"현비서: {r['say']}")
    return out[-n:]
