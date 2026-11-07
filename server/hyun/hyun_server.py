"""현비서 중계 서버 — 앱(차·폰) ↔ 두뇌(Claude). 127.0.0.1:8790, 에뮬에선 http://10.0.2.2:8790

    python -I -X utf8 -u server/hyun/hyun_server.py          # HYUN_DATA=<폴더>면 그 데이터로(시험 — tools/hyun_e2e)

앱이 들은 말·주행 이벤트를 inbox에 쌓고, 두뇌가 답하면 앱이 long-poll로 받아 말한다.
두뇌 셋(우선순위 순): ① 떠 있는 Claude Code 세션(wait.py · say.py — 사람이 붙어 있는 시연)
② 내 Claude 계정(brain/cli — 이 PC의 Claude Code `claude -p`, 건당 요금 없음) ③ API 키(brain/api — /hyun/login에서 등록)
세션이 기다리고 있으면 세션이, 없거나 6초 넘게 아무도 안 받으면 ②, ②가 없으면 ③.

구조(기능 = 모듈 하나 + features/__init__.py ALL 한 줄 → .claude/docs/wiki/hyun-architecture.md):
  core/      설정(HYUN_DATA) · 저장(log.jsonl · memory.json) · inbox(말 ↔ 답 · 답 등록 파이프라인) · HTTP · 자동 두뇌 워커
  brain/     prompt(persona.md + 규칙 + 답 형식 + 질문 블록을 기능에서 모음) · cli(내 계정) · api(API 키)
  features/  길 · 교통 표 · 카드(사진) · 기억 · 취향(+정리 봇) · 최근 대화 · 브리핑 · 정보 통로 · 검색 · 휴게소 · 폰 · 로그인 · 귀 시험 녹음
따로 도는 도구: stt_server.py(받아 적기 8791) · say.py·wait.py(세션 두뇌) · taste_refine.py(취향 정리 수동)
"""
import os
import sys

# python -I(격리)는 스크립트 폴더를 sys.path에 넣지 않는다 — 우리 패키지(core · brain · features)를 찾게 직접 넣는다
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from core.server import main  # noqa: E402

if __name__ == "__main__":
    main()
