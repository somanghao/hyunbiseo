"""귀 시험 녹음 — /hyun/testaudio?name=<이름> → data/testaudio/<이름>.pcm(16kHz mono). 앱 --es earfile <이름>이 받아 상시 귀에 넣는다.
녹음 만들기 = tools/hyun_testaudio.py(Windows SAPI 한국어). E2E 시험(tools/hyun_e2e)이 쓴다.
"""
import os
import re

from core import config


def _get(h, q):
    name = q.get("name", "")
    path = config.data("testaudio", name + ".pcm")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,40}", name) or not os.path.exists(path):
        return h.send_json({"error": "no such audio"}, 404)
    with open(path, "rb") as f:
        h.send_bytes(f.read(), "application/octet-stream")


GET = {"/hyun/testaudio": _get}
