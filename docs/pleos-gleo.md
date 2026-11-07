# Gleo Interface (Gleo AI 음성 연동) · LLM SDK 종료

> 원문: `reference/pleos-docs/pleos-only__app-integration-guide__*.md` (core-concepts · app-capability · deliver-schema · action_callback · examples · use-cases__radio-app). 문서 스스로 "개발 진행에 따라 계속 개선"이라 밝힘.

## 위치

- **LLM SDK는 2026-10-19 제공 종료**(에뮬레이터 v2.1.0·Playground v2.6.0 릴리스노트). Gleo AI 연동이 필요하면 **Gleo Interface**
- STT는 텍스트만 돌려줌 → "말로 앱 기능 실행"은 Gleo Interface의 영역
- 구조: **function calling**(OpenAI 방식 유사 + Android 확장). 사용자 발화 → Gleo AI(LLM)가 앱이 정의한 function 호출(Gleo Action) → 앱이 Callback으로 결과 반환 → Gleo가 최종 답변(음성/텍스트) 생성

## App Schema (`assets/schema.json`)

- 필드: `packageName` · `appDescription` · `invokeWord`(예시에 등장) · `version`(App Market 등록 버전) · `actionCalls`(**최대 3개**)
- `actionCalls[].function`: `name`(**대문자+밑줄**, 고유, 2~3단어 권장 예 `ADD_REMINDER`) · `description`(영어, 1~2문장, 예시 넣지 말 것, Gleo 조작 문구 금지) · `parameters`(`type: object`, properties 타입은 string·number·boolean·array 중 **하나만**, enum은 문자열만, null 불가) · `required` · `component`: `"activity"`(startActivity, UI 필요) | `"service"`(sendBroadcast, 백그라운드)
- 매니페스트에 function 이름과 같은 **intent-filter action** 선언:
```xml
<activity android:name=".YourActivity">
    <intent-filter>
        <action android:name="ADD_REMINDER" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```
- 등록: Gleo AI 앱이 부팅 시(그리고 앱 설치/삭제 시) `AssetManager`로 모든 앱의 `assets/schema.json`을 스캔해 DB 저장. 단 **스키마는 Pleos 측 가이드라인 검토·승인 후 통합 활성화**

## Callback Schema (앱 → Gleo)

- `functionName`(필수) · `status`: `success` | `asking`(추가 정보·확인 필요) | `fail` · `message`(필수, **영어 완전한 문장**, 고유명 등은 타 언어 허용) · `mode`: `voice`(기본) | `beep` | `silent` · `data`(JSON 객체 배열 **문자열**, 필요한 것만)
- Gleo는 **7초** 후 Action 타임아웃 취소

## 에뮬레이터 실측 (2026-10-28, 에이전트 앱 `CHECK_VEHICLE` — [[agent-app]])

- Gleo AI = `ai.umos.ambientai`(앱 이름 "Gleo AI", 런처 있음, 상시 실행) + `ai.pleos.gleoai.model.provider`. 로그 태그 `[GAA]`·`ami-sdk`
- **승인 없이 스키마가 로드된다** — 앱 설치 시 `[Schema] Loaded schema.json from <패키지>` → `onUpdateContextAppCapabilitySchema`. 스키마 없는 앱은 `has no schema.json`. 실차·앱마켓의 승인 절차는 별개(미확인)
- 액션 인텐트: action = function 이름, `ACTION_PARAM` = 따옴표로 한 번 더 감싼 JSON 문자열(`"{\"item\":\"all\"}"`)
- **Gleo는 자기가 부른 액션의 응답만 받는다** — 대화 중이 아닐 때 보낸 `APP_ACTION_RESPONSE`는 `status: Sleeping` → `Reject AppActionResponse`. ⇒ 앱이 먼저 말하는 경고·브리핑은 Gleo로 못 하고 자체 TTS로
- 미확인: 실제 음성("글레오" → 질문)으로 우리 액션이 선택되는지, Gleo가 영어 message를 한국어로 말해 주는지

## 글레오 내장 도구 (에뮬 APK `/system_ext/app/AmbientAiApp/` `assets/data/v3/Tool/` 폴더명 — 내용은 암호화 `AMIENC01`)

- **차 상태 조회**: `getDoorState`·`getTireState`·`getEnergyLevel`·`getChargePortState`·`getTelltaleState`·`getWindowState`·`getSeatState`·`getMirrorState`·`getHvacAndComfortState`·`getDrivingDistance`·`getDrivingTime`·`getEvEfficiency`·`getFuelEconomy`·`getVolume`
- **차 제어**(약 40개): 공조 전부(`setHvac*`·`setAutoThermalComfort`) · 창문·트렁크·사이드미러·스마트 글라스 · 조명(실내 밝기/색/모드·전조·안개·미등) · 시트 마사지 · EV 충전(스위치·충전구·목표%·전류) · 와이퍼 · 볼륨 · 하이패스 볼륨
- 그 밖: `gleo.navigate` · 기억 검색(`builtin.memory.searchMemory`) · 동영상 재생 · 앱 실행·설정 이동 · 서드파티 앱 액션 · 잡담(`cloud_call_chitchat_agent`) · `handleExternalDomain`·`unsupportedFeature`
- **날씨·뉴스·교통·사고 전용 도구는 이름에 없다**(외부 도메인은 `handleExternalDomain`이 처리하는지 미확인)
- ⇒ **"내 차 상태"는 글레오 영역**이다. 글레오는 시스템 권한이라 서드파티가 못 읽는 타이어·경고등도 읽는다. 서드파티 앱 가치는 글레오가 구조적으로 못 하는 것 — **다른 차·도로 정보**, **먼저 말하기**, **주행 기록·코칭** → [[agent-app]]

## 공모전 관점 (미확인 사항)
- AndroidX Media3·AAOS 표준 미디어 API를 따른 음악 앱은 Schema 없이도 일부 연동(`pleos-only__vehicle-app-developer-guide__music-audio-app-developement-guide.md`)
