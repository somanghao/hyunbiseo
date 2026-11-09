---
name: run-hyun
description: 현비서를 에뮬레이터에서 띄워 확인하는 절차 — 에뮬 · 현비서 서버(두뇌) · 받아 적기 서버 · 앱 설치·실행 · 시연 인텐트 · 화면 캡처. "앱 띄워", "에뮬 실행", "시연해 봐", "화면 캡처" 같은 요청에 쓴다.
disable-model-invocation: false
---

# 현비서 띄우기·확인

1. 에뮬(AVD `Pleos_Connect_v2.1` — Pleos Connect Emulator v2.1, `docs/pleos-emulator.md`): `"$ANDROID_HOME/emulator/emulator" -avd Pleos_Connect_v2.1 -no-snapshot -no-boot-anim -gpu host -scale 0.5`(백그라운드) → `adb -s emulator-5554 shell getprop sys.boot_completed`가 1
2. 빌드·설치: `cd android && ./gradlew :hyun:assembleDebug` → `adb -s emulator-5554 install -r -g android/hyun/build/outputs/apk/debug/hyun-debug.apk` → `adb -s emulator-5554 shell am start --user 10 -n io.github.somanghao.makecar.hyun/.HyunActivity`
3. 서버(필요할 때만 — **두뇌 = 띄운 사람의 Claude 로그인, 사용량 본인 몫**): `python -I -X utf8 -u server/hyun/hyun_server.py`(백그라운드) → `curl -s 127.0.0.1:8790/hyun/health`가 `"mode": "claude"`. 끝나면 끈다
4. 말 없이 시험: `adb -s emulator-5554 shell "am start --user 10 -n io.github.somanghao.makecar.hyun/.HyunActivity --es say '웹툰 순위 알려줘' --ez harness true"`(harness = 취향·기억에 안 섞임) · 화면 `--es ui chat|info` · 스킨 `--es skin nash` · 목적지 `--es dest 코엑스 --ef lat 37.5116 --ef lng 127.0595` · 안내 끝 `--ez cancel true`
5. 반반: `adb shell input swipe 2547 717 1650 717 600`(2칸) · `input swipe 1705 717 2552 717 600`(1칸) — 앱이 [내비 | 현비서]로 맞춘다
6. 로그: `adb -s emulator-5554 logcat -d | grep HYUN` · 캡처: `adb -s emulator-5554 exec-out screencap -p > shot.png`
7. 답을 기다릴 땐 맞장구(`say|P1|생각해 볼게요`)가 아니라 `reply|` 줄을 기다린다
