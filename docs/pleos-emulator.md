# Pleos Connect Emulator · Virtual Console · 로컬 개발 PC

> 원문: `reference/pleos-docs/connect__guide__getting-started__application-development__{setup-connect-sdk-emulator,configure-pleos-connect-emulator,use-vehicle-emulator}.md` · `pleos-only__app-test-guide__{virtual-console-guide,form-factor-test-guide}.md` · `pleos-only__vehicle-app-developer-guide__adb-command-reference-guide.md`
> 2차 PT 시연은 "에뮬레이터 화면 또는 기기" → 이 환경이 곧 시연 무대 ([[contest-judging]])

## 설치 (Pleos Connect Emulator v2.1.0, 2026-10-19 정식)

1. 구버전 Pleos 에뮬레이터 있으면 삭제
2. Android Studio > Tools > **SDK Manager** > **SDK Update Sites** > `+`
   - Name `Pleos Connect System Image`
   - **Windows/Linux**: `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`
   - Mac(arm64): `https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-arm64-sys-img.xml`
3. SDK Platforms > **API 34**의 Pleos Connect system image 체크(Show Package Details) → 설치 → Android Studio 재시작
4. Device Manager > Create device > Form Factor **Automotive** > `Pleos Connect v2.1.0`
5. Advanced: **Default boot = Cold** · RAM **4 GB** · system image `Pleos Connect v2.1.0 APIs`(API 34) — 샘플앱 문서의 AVD 이름 `Pleos_Connect_v2.1`(Android 14)

- ⚠️ **Quick boot면 VHAL 사용 불가** → 반드시 Cold boot
- GPU 필수. 최소 RTX 3060 Laptop 6GB / RX 6600M 8GB, 권장 RTX 4060·5060 Laptop 8GB+
- 기본 언어 한국어(차량 설정 > 일반 설정 > 언어/Language)
- STT 테스트: 에뮬레이터 Extended Controls > Microphone에서 호스트 오디오 입력 허용
- Bluetooth 동글 연동 미지원(제공 종료)
- 2.1.0부터 CRN 입력 불필요
- 에뮬레이터 코덱·`Build.DEVICE`는 실차와 다름 ([[pleos-platform]])

## Virtual Console (차량 상태 시뮬레이터, v2.1.0+)

```bash
adb forward tcp:8088 tcp:8088
# 브라우저: http://127.0.0.1:8088/
```

- 안 열리면: 에뮬레이터 실행 여부 → 이미지 버전 → `adb devices` → forward 재실행 → 8088 포트 점유 확인
- 테마(기어 P일 때): Highway / Forest, Highway는 1·3인칭 시점 — 시연 영상에 쓸 만함

| Domain | 조작 가능한 카드 (범위) |
|---|---|
| Vehicle | Notification 1 표시/숨김 |
| Motion | **Gear P/R/N/D** · Brake Pedal 0~100% · Odometer 0~1,000,000km · Steering Angle −540~540° · Steering Wheel Heat |
| ADAS | Cruise Control State(Not ready/Ready/Longitudinal/Lateral/Full autonomous) · Cruise Target Speed 5.556~27.778 m/s |
| Convenience | ETCS Power/Scenario(카드·요금소) · Door Position/Lock · Trunk/Frunk · 실내등·독서등 · Ambient 밝기/색(Cyan·Blue·Purple·White) · 외부등·상향등·비상등·제동등 · Turn Signal · Window Position/Lock · **Seat Occupancy** · Wiper · Glove Box |
| Safety | Telltales(주차브레이크·안전벨트·ABS·TPMS 등) Off/On/Blink |
| Energy | Remaining Range · Battery Energy 0~200kWh · **Battery SOC 0~100%** · Capacity · Charge Current Limit 10~48A · Charge % Limit 50~100 · Charge Switch · Charge Port |
| Climate | HVAC Power · AC · Auto · Dual · 내기순환 · Fan Speed 0~8 · Fan Direction · Defroster · Target Temp 16~30°C · Seat 열선/통풍 · Air Cleaner |

- 문서 카드 목록엔 차량 속도가 없지만 **속도는 `PUT /vehicle/controls`(Highway 테마의 가속/브레이크 페달)로 주입된다** → 아래 REST API
- **주행 규제(`CarUxRestrictions`)는 기어로 재현** — 기어 D → `DO=true active=80`(NO_VIDEO 16 + NO_SETUP 64, 속도 0이어도), P → `DO=false active=0` (2026-10-27 프로브 실측)

## Virtual Console 내부 REST API (페이지 스크립트에서 확인 · 문서 미기재)

- 읽기: `GET /vehicle/state`(전 도메인 현재값 + `availability`·`browser` 상태) · `GET /vehicle/catalog`(63개 기능 — 문서 카드보다 많음: 타이어 공기압·시트 위치·파워시스템·에스코트등 등)
- 쓰기: `PUT /vehicle/gear?gear=Drive` · `PUT /vehicle/controls?speed=&accelerator=&brake=&steering=`(**속도 0~240km/h 정수만** — 소수는 400 `invalid_speed` · Highway 테마의 가속/브레이크 페달) · `/vehicle/body`·`/lights`·`/seat`·`/ev-battery`·`/telltales/simulator` 등. **파라미터 정본 = 카탈로그의 `write_endpoint`·`control.params`** (예: 비상등 `/vehicle/lights?feature=hazard&value=true` — `hazard-lights`는 400 `invalid_light_feature` · SOC `/vehicle/ev-battery?field=soc&value=42` · 좌석 `/vehicle/seat?kind=occupied&area=driver&value=true`)
- **활성 조건 = heartbeat 하나**: `POST /browser/session?client=<id>`가 마지막으로 온 뒤 **3초 안**에만 쓰기 200, 아니면 **503 `emulator_inactive`**(페이지 배지 `DDS OFFLINE` = `gear.bridge_ready=false` = `browser.active=false`, 모두 같은 신호). 첫 heartbeat에 에뮬레이터 안 `ai.pleos.hardware.vehicle_api_dds_emulator`가 DDS 노드·브리지를 켠다(~0.4초, logcat `-s VehicleApiDdsEmu`의 `web-activated` 이벤트로 확인). 페이지는 `setInterval(1000)`으로 heartbeat를 보낸다
- **VC 쓰기 → 앱 수신 end-to-end OK**(2026-10-27, 프로브 앱 구독): 속도 30·60·0 → AAOS `PERF_VEHICLE_SPEED` 8.33·16.67·0(m/s) · SOC 42 → SDK `ev.SOC.updated` 42 · 좌석 driver empty → SDK `seat.occupancy` false · 기어 P → SDK `GEAR_PARK` + AAOS `GEAR_SELECTION` 4 + UX restrictions 해제. (좌석 occupied 재설정은 5초 안 이벤트 미관측 — 재확인 필요)
- 스크립트 클라이언트 `tools/vconsole.py`(heartbeat 대행·활성화 대기 + `gear`·`speed`·`put`·`scenario crash`) — 브라우저 탭 없이 시연 자동화에 쓴다. 상태는 heartbeat를 끊기 **전**에 찍는다(끊고 3초 뒤엔 `emulator_active=false`가 정상)
- 에어백 전개는 카탈로그에 없다(안전 도메인은 telltale 경고등뿐) → 사고 감지 시연은 앱 데모 트리거 필요
- 503이 다시 나면(첫 세션의 "브라우저를 열어도 503"은 원인 미확정): ① `curl /vehicle/state`의 `browser.active` — false면 heartbeat가 서버에 안 닿는 것 ② `adb logcat -s VehicleApiDdsEmu`에 `web-activated`가 찍히는지 ③ `adb shell dumpsys dropbox`의 SYSTEM_TOMBSTONE — 그 부팅엔 Pleos SDV 서비스(`vehicle_ext`·`adas`·`vehicle_diagnostic`·`placesense`·`profile`)가 부팅 직후·+10분 등 반복 SIGABRT했고 다음 부팅(쓰기 정상)엔 없었다(인과 미확인) ④ VC 탭이 백그라운드면 Chrome 타이머 스로틀로 heartbeat가 3초를 넘길 수 있다는 가설(미검증 — Playwright는 탭을 항상 visible로 둬서 재현 불가). 어느 경우든 `vconsole.py`는 자체 heartbeat라 탭 상태와 무관
- **하이웨이 뷰의 주변 차량은 장식** — 페이지에 하드코딩된 `trafficObjects` 4대(z·lane·color 고정)를 그릴 뿐 인지(perception) 데이터가 아니고, 앱이 읽을 API도 없다. 사고 차량 배치·전방 경고 같은 기능도 없다 → "전방 Xm 사고" 상황은 우리 서버·대시보드가 만들어야 한다

## 로컬 개발 PC (2026-10-27)

- Android Studio 설치(`C:\Program Files\Android\Android Studio`), SDK `%LOCALAPPDATA%\Android\Sdk`, emulator 35.2.10, WHPX 가속 OK. **cmdline-tools(sdkmanager·avdmanager) 없음** → 이미지·AVD는 수동 설치
- **Pleos 이미지 설치됨**: `system-images/android-34/pleos_car_emulator/x86_64/`(XML의 zip 4.09GB, SHA-1 `b4f858d5…` 검증, 풀면 ~19.6GB · 2560×1440 mdpi · Android 14). 설치 = 42dot SDK 라이선스 동의(2.2조)
- **AVD `Pleos_Connect_v2.1`** 수동 작성(`~/.android/avd/Pleos_Connect_v2.1.avd/config.ini`: RAM 4096 · `fastboot.forceColdBoot=yes` · gpu host). 실행: `emulator -avd Pleos_Connect_v2.1 -no-snapshot -no-boot-anim -gpu host -scale 0.5` → serial `emulator-5554`, 콜드부팅 ~1분
- GPU RTX 2080 Ti · RAM 64GB · 디스크 여유 ~150GB
- JDK: PATH `java` = **OpenJDK 25**(빌드 불가) → **`~/.jdks/corretto-21.0.11`**을 `JAVA_HOME`으로. Gradle은 캐시된 **8.14.3**(`~/.gradle/wrapper/dists`)
- 실기기 `5cd17aa7`(unauthorized)도 adb에 붙어 있음 → 항상 `-s emulator-5554`
- 네트워크: 에뮬레이터 → PC 호스트 **`10.0.2.2` 접속 OK**(대시보드 서버 8765 HTTP 200) · 외부 인터넷은 **미확인**(ping·`example.com` HTTP 무응답 — 에뮬레이터 DNS/네트워크 설정 점검 필요) · **GMS 없음**

## 시스템 재시작(워치독) — 2026-10-30 실측

- 현비서 주행 시연 중(VC `Drive`·60km/h, 내비 경로 안내 + 앱 STT 요청 직후) **system_server가 워치독에 죽고 다시 떴다** — `Watchdog: Blocked in handler on foreground thread (android.fg) for 68s`, 막힌 곳 `GnssNative.native_stop_nmea_message_collection`(위치 서비스 GNSS HAL), `DeadSystemException`, 드롭박스 `SYSTEM_TOMBSTONE` 2건. 우리 앱 코드 경로가 아니라 에뮬 GNSS HAL 쪽 정지
- 결과: 모든 앱 프로세스 재시작(현비서 `hello`가 다시 옴) · **내장 내비 경로 안내가 사라짐**(`getDestinationInfo` 응답 없음) · 그 사이 `am start`는 "Activity class does not exist"(Error type 3)
- **재발 2026-11-07 09:32**(현비서 E2E 시험 중, 정차·내비 경로 안내·화면 녹화·받아 적기 주입): `Blocked … (android.fg) for 72s` · 막힌 곳 `GnssNative.native_stop_sv_status_collection`(10-30은 `stop_nmea` — 같은 GNSS HAL의 다른 멈춤) → 앱·내비 경로 재시작, 그 뒤 케이스 2개 실패. E2E 러너가 `logcat -b all -s Watchdog:W`로 잡아 보고서에 '에뮬 재시작(앱 탓 아님)'으로 표시 → [[hyun-e2e]]
- 대처: 앱은 재시작에 견디게(목적지는 내비에서 다시 읽고, 경로가 없으면 우회 결과를 보고하지 않음), 시연 중이면 목적지를 다시 설정. 재발 빈도·트리거는 미확정 — 다시 나면 `adb logcat | grep Watchdog`와 `dumpsys dropbox --print`로 같은 지점인지 본다

## 마이크(호스트 오디오) — 2026-11-06 실측

- 에뮬은 **Windows 기본 녹음 장치**를 쓴다 — 기본(콘솔)과 **통신용 기본**이 다를 수 있으니 둘 다 실제 마이크로(통신용이 꺼진 헤드셋이면 무음). 확인·변경 = Core Audio(IPolicyConfig) 스크립트, PC 쪽 음량은 `ffmpeg -f dshow -i audio="…" -af volumedetect`
- 에뮬이 켜진 뒤 마이크를 꽂거나 기본 장치를 바꾸면 **콜드부팅해야** 새 장치를 잡는다(`hostmicoff/on`만으로는 한 번만 들어오고 다시 무음)
- 들어온 소리가 작다: 앱 원본 녹음 최대 -38dBFS·평균 -59dB(PC 마이크 볼륨 85→100%로 올림) — Whisper는 그 소리도 정확히 받아 적지만 Pleos ON_DEVICE STT는 말 시작(EPD)을 못 잡아 늘 "조용함"으로 끝났다
- Pleos STT 첫 요청은 모델 로딩으로 녹음 시작까지 2.8초 — 앱 무음 타이머는 엔진 `onReady`부터 센다
- `SpeechToText.sendAudio`/`completeAudioSend`(녹음 주입)를 ON_DEVICE에서 부르면 `ai.pleos.playground.caas`가 `SpeechToText.recognize` 네이티브 SIGSEGV로 죽고 재시작(글레오 연결도 끊겼다 재접속) — 쓰지 않는다
- 에뮬은 user 빌드라 `tinycap` 녹음 불가 → 앱 진단 녹음(`--ez rawrec true`)으로 들어 본다
