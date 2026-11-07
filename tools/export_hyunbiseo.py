"""현비서만 떼어 내보내기 — private 저장소 https://github.com/somanghao/hyunbiseo 의 내용을 만든다(원본은 이 저장소, 저쪽은 사본).

    python -I -X utf8 tools/export_hyunbiseo.py <대상 폴더>      # 대상 = hyunbiseo 클론(그 안의 .git은 건드리지 않는다)

담는 것(git이 추적하는 파일만 — server/hyun/data·data-e2e·영상 원본 raw·빌드 산출물은 애초에 없다):
  android/ — 현비서 모듈(:hyun) + Gradle 래퍼 · settings는 :hyun만 포함하게 바꿔 쓴다
  server/hyun/ — 중계 서버 · 두뇌(내 Claude 계정·API) · 정보 통로 · 취향 · 받아 적기 서버
  tools/ — E2E 시험(hyun_e2e) · 하네스(hyun_harness) · 시험 목소리(hyun_testaudio.py) · 차 상태 주입(vconsole.py)
  records/hyun-e2e/ — 시험 기록(보고서 · 영상 · 대화 · 스크린샷)
  docs/ — 현비서 문서(.claude/docs/wiki/hyun-*.md 등 사본)
내보내기 전에 비밀 문자열(API 키·토큰 모양)을 훑어 하나라도 있으면 멈춘다.
"""
import os
import re
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TRACKED_DIRS = ["android/hyun", "android/gradle", "server/hyun", "tools/hyun_e2e", "tools/hyun_harness", "records/hyun-e2e"]
FILES = ["android/build.gradle.kts", "android/gradle.properties", "android/gradlew", "android/gradlew.bat",
         "tools/hyun_testaudio.py", "tools/vconsole.py", "tools/export_hyunbiseo.py"]
DOCS = ["hyun-app.md", "hyun-brain.md", "hyun-e2e.md", "hyun-harness.md", "hyun-modules.md", "hyun-phone.md",
        "hyun-pleos-capabilities.md", "pleos-sdk-navihelper.md", "pleos-emulator.md", "pleos-gleo.md"]
SECRET = re.compile(r"sk-ant-[A-Za-z0-9_-]{10,}|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|xox[abp]-[A-Za-z0-9-]{10,}|AKIA[0-9A-Z]{16}"
                    r"|[MN][A-Za-z\d]{23}\.[\w-]{6}\.[\w-]{27}")   # 앞 = Anthropic·GitHub·Slack·AWS, 끝 = Discord 봇 토큰 모양

GITIGNORE = """# 빌드
android/.gradle/
android/build/
android/*/build/
android/local.properties
*.apk
# 현비서 서버 기록(대화·취향·키) — 저장소에 올리지 않는다
server/hyun/data/
server/hyun/data-e2e/
__pycache__/
# 시험 원본(케이스별 영상·PNG — 크다). 이어 붙인 video.mp4·보고서·스크린샷은 올린다
records/**/raw/
tools/hyun_harness/runs/
"""

SETTINGS = """pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/") }   // Pleos SDK
    }
}

rootProject.name = "hyunbiseo"
include(":hyun")
"""


def tracked(prefix):
    out = subprocess.run(["git", "-C", ROOT, "ls-files", "-z", prefix], capture_output=True, check=True).stdout.decode("utf-8")
    return [p for p in out.split("\0") if p]


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    dst = os.path.abspath(sys.argv[1])
    os.makedirs(dst, exist_ok=True)
    # 대상의 .git만 남기고 비운다(지난 내보내기에서 지워진 파일도 따라 지워지게)
    for name in os.listdir(dst):
        if name == ".git":
            continue
        p = os.path.join(dst, name)
        shutil.rmtree(p) if os.path.isdir(p) else os.remove(p)
    files = [f for d in TRACKED_DIRS for f in tracked(d)] + [f for f in FILES if tracked(f)]
    bad = []
    for f in files:
        src = os.path.join(ROOT, f)
        try:
            with open(src, encoding="utf-8") as fh:
                if SECRET.search(fh.read()):
                    bad.append(f)
        except (UnicodeDecodeError, OSError):
            pass   # 그림·영상·jar
    if bad:
        sys.exit("비밀 문자열 모양이 있어 멈춤: " + ", ".join(bad))
    for f in files:
        out = os.path.join(dst, f)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        shutil.copy2(os.path.join(ROOT, f), out)
    with open(os.path.join(dst, "android", "settings.gradle.kts"), "w", encoding="utf-8", newline="\n") as fh:
        fh.write(SETTINGS)
    os.makedirs(os.path.join(dst, "docs"), exist_ok=True)
    for d in DOCS:
        src = os.path.join(ROOT, ".claude", "docs", "wiki", d)
        if os.path.exists(src):
            shutil.copy2(src, os.path.join(dst, "docs", d))
    with open(os.path.join(dst, ".gitignore"), "w", encoding="utf-8", newline="\n") as fh:
        fh.write(GITIGNORE)
    shutil.copy2(os.path.join(ROOT, "tools", "hyun_export_README.md"), os.path.join(dst, "README.md"))
    print(f"{len(files)}개 파일 + 문서 {len(DOCS)} · README → {dst}")


if __name__ == "__main__":
    main()
