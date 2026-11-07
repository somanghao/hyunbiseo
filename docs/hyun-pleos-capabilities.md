# 현비서가 쓸 수 있는 Pleos 기능·앱 지도 (2026-11-06 조사)

> 조사 = 문서·실측 기록 종합(읽기만). 판정 우선순위는 [[gotchas]] #1(실측·AAR > 문서). 상세 근거 → [[sdk-probe]] · [[pleos-gleo]] · [[pleos-platform]] · [[pleos-emulator]] · `reference/pleos-api-index.md` · `reference/pleos-docs/`
> 상태: **사용중**(현비서 코드가 부름) · **실측OK**(에뮬 응답, 다른 앱·프로브 포함) · **권한거부** · **에뮬미지원** · **미시험** · **승인필요**(Coming Soon·사전 승인) · **막힘** · **API없음** · (추정)

전제: 에뮬에선 SDK 5종이 신청과 무관하게 응답(`adb install -g`). 실차·앱마켓은 Vehicle 조회 `CAR_*`·제어 `CONTROL_CAR_*` 모두 사전 협의·승인된 것만 선언 가능. 현비서 매니페스트 = `CAR_ENERGY`·`CAR_INFO`뿐(`CAR_DOORS`·`CAR_SEATS`·`CONTROL_*`·`NAVI_CUSTOM_MAP` 없음, `assets/schema.json` 없음).

## ① Pleos AI (Gleo)

| 기능 | 현비서 활용 | 상태 |
|---|---|---|
| **Gleo Interface** — `assets/schema.json`(actionCalls ≤3) + intent-filter + `APP_ACTION_RESPONSE` 7초 안 | "현비서 지금 상황" · "현비서한테 물어봐" · "조용히/동승자 모드" — 글레오가 현비서 두뇌 답을 말함 | **현비서 3액션 구현(11-07)** ASK_HYUN·HYUN_STATUS·HYUN_QUIET — 글레오 스키마 로드 확인 · `am start -a` 흉내 응답 4ms · 실제 음성 E2E 미시험 · 실차 승인필요 → [[hyun-app]] §귀 |
| 글레오로 먼저 말하기(안 불렀는데 응답 보내기) | — | 막힘(Sleeping → Reject) → 먼저 말하기는 현비서 TTS |
| 글레오 내장 도구(차 상태 14·차 제어 ~40·navigate·앱 실행) | "그건 글레오한테" 넘기기(추정) | API없음(서드파티 호출 길 없음) |
| STT AAR `getAmbientAi()`·`getCallApi()` | — | `PrivilegedApi` — 시스템 앱 전용(추정) |
| Media3 `MediaSession` → 글레오 음성 제어 | 동요 노래방(추정) | 문서상 준비 중 |
| LLM SDK | — | 2026-10-19 제공 종료 |

## ② 내비 (NaviHelper 3.0.5 — 내비 탑재 차만, 탑재 확인 API 비공개)

| 기능 | 현비서 활용 | 상태 |
|---|---|---|
| 현재 위치·주소 · `requestRoute` · 목적지·`onDrivingInfo` · `trafficStatus` · `changeRouteOption`/`requestReRoute` | 위치·말로 목적지·남은 거리·경로 정찰·우회 | 사용중·실측OK |
| `onTBTInfo` · `onCautionPointInfo` · `onDestinationArrived` | 회전 앞 침묵·주의 구간·도착 | 사용중(실제 수신은 주행 시험 필요) |
| `addWaypoint`·`requestRouteWithWaypoints`·`removeWaypoint`·`onWayPointArrived`(경유지 ≤2) | 말로 경유지 넣기/빼기 · 쉼표 휴게소 경유 · 장소 할 일 | **사용중·실측OK(11-07)** — 도착 콜백은 에뮬 GPS 고정이라 미실측 → [[pleos-sdk-navihelper]] §실측 |
| `onCurrentRoadSpeedLimit` · `onCameraAlert` | 과속·구간 단속 한마디, 온기 | 미시험 |
| `getBookmarkInfo`(집·회사·충전소) · `getRecentDestinationInfo` | "집에 가자"를 좌표 없이 | 미시험 |
| `requestTrip`(도착 배터리 예상) · 충전 계획 | 충전 브리핑 | 미시험 |
| `requestNavigationScreen(searchPlace·nearbyChargingStations…)` | "근처 충전소 보여줘" — **내비 화면을 여는 것, 장소 목록을 돌려주진 않음** | 미시험 · `NAVI_CUSTOM_MAP` 권한 없음 |

→ 도착 추천(주변 음식점·쇼핑)에 쓸 **장소 검색 결과 API는 Pleos에 없다** — 지역 검색 API(카카오·네이버 등, 키 필요)로.

## ③ 차량 정보 읽기 (AAOS · Vehicle SDK 2.3.2)

| 기능 | 현비서 활용 | 상태 |
|---|---|---|
| AAOS 속도·기어 · `CarUxRestrictions` · `EvBattery` | 주행/정차·배터리 | 사용중·실측OK |
| 외기온·주차 브레이크·차종 | 진짜 정차·외기온 | 실측OK(외기온 에뮬 25℃ 고정) |
| `Door` 열림 | 하차 확인·내릴 때 우산 | 실측OK(에이전트 앱) |
| `Seat` 착석 | **동승자 모드 자동 전환** | 실측OK(조수석) |
| 브레이크·주행거리 | 온기 안전 km | 실측OK |
| 깜빡이·벨트·와이퍼·크루즈·조향각·주행 시간 | 온기 퀘스트·와이퍼로 비 감지 | 미시험(VC 주입 카드 있음) |
| 시동 `IGNITION_STATE` | 시동 감지 | 에뮬미지원 |
| 비상등 · 경고등(telltales) · 타이어 공기압 | — | 권한거부(서드파티 불가) |
| 속도(Vehicle SDK) | — | API없음 → AAOS로 |

## ④ 차량 제어 — 전부 승인필요(Vehicle Control Coming Soon, 신청 불가) · 문·시트·설정은 정차 중만

| 기능 | 현비서 활용 | 상태 |
|---|---|---|
| 앰비언트 `setAmbientLightBrightnessColor` | **기분 색 ↔ 실내 조명**, 경고 땐 경고색 | 실측OK(H1 앱 `H1Activity.kt`) |
| 공조 온도·풍량·외기·에어컨 | 졸릴 때 환기·온도 낮추기 | 미시험 |
| 시트 열선·통풍·이동 · 핸들 열선 | 쉼표 휴식 자세 | 미시험 |
| 창문·블라인드 · 문 잠금 · 와이퍼 · 하이패스 안내 볼륨 | "창문 조금 열게요" | 미시험(문서 간 setter 범위 불일치 → AAR `javap` 재확인) |
| 시스템 테마·사운드 테마 | — | 막힘(시스템 권한) |

## ⑤ 음성

| 기능 | 현비서 활용 | 상태 |
|---|---|---|
| TTS ON_DEVICE `speak` | 현비서의 입 | 사용중·실측OK(다른 앱이 앞에 있어도 발화) |
| `TextToSpeech.setSpeakerType(String)` | **캐릭터 목소리 고르기** | 미시험 — 기본 화자 `seoyoon`, 다른 값은 문서에 없음 |
| 오디오 포커스·덕킹 · 앞 묵음 150~200ms | 내비 안내와 겹칠 때·첫 음절 잘림 | 미시험 |
| TTS·STT 서버/하이브리드 모드(`registerApp` Client ID·Secret) | 서버 품질 음성·인식 | 미시험·승인필요 |
| STT ON_DEVICE | 귀 폴백 | 에뮬 실인식 ✗ |
| 앱 자체 귀(AudioRecord + Whisper) | 상시 듣기 | 사용중(Pleos 기능 아님) |

## ⑥ 위치 · ⑦ 다른 앱

- `FusedLocation`(실측OK·승인필요) · `addProximityAlert`(장소 근처 알림, 미시험)
- 다른 미디어 앱 제어 · 현비서 → 글레오 → 다른 앱: 근거 없음/API없음(시스템 권한 추정)
- 전화·문자·캘린더·주소록: 차에선 제한될 수 있음(문서) · 다른 앱 실행: 미시험 · 알림·Mini app·Global Search: 미시험, 운전과 무관한 알림 금지
- **내비와 같이 보기 = 앱 영역 2칸 분할**(11-07 에뮬 실측): 현비서 화면 오른쪽 끝 ⋮ 손잡이를 끌면 왼쪽 현비서 · 오른쪽 내비로 나란히 뜬다 — 현비서는 내비 목적지·남은 km·분·도착 시각·진행선을 그대로 보여 준다(내비 카드와 같은 23분). 반쪽 폭에선 왼쪽 열이 좁아 도착 시각·버튼 글자가 "…"로 잘림 → 분할용 좁은 배치 필요
- **내비 위에 떠 있는 아이콘(다른 앱 위에 그리기) = 막힘(추정)**: 에뮬에 '다른 앱 위에 표시' 허용 화면이 없다(`MANAGE_OVERLAY_PERMISSION` → No activity found) — 사용자가 켤 길이 없음. adb `appops`로는 켤 수 있으나 시연 흉내일 뿐, 심사·앱 정책 위험
- **Mini app**(Driving view 아래 작은 카드, AppWidget 기반): 홈(`ai.umos.homescreen`)이 위젯 호스트, 올라간 것은 전부 시스템(`ai.umos.*MiniAppProvider` — 미디어·TPMS·트립·빠른 제어). 현비서 `QuickWidget`도 제공자 목록엔 잡히지만 서드파티를 올리는 문서·화면은 못 찾음 → 42dot 문의 대상(되면 '내비 옆 현비서 카드'의 정식 길)
- 시연 도구: Virtual Console REST + `tools/vconsole.py`(실측OK)

## 11-15 전에 현비서를 가장 키울 5개 (검증됐거나 확인 싼 것)

1. ✅ **Gleo Interface 3액션**(11-07 구현 · 남은 것 = 실음성 E2E, 막히면 `am start` 흉내로 시연) — "Pleos AI 활용" 증거
2. **기분 색 ↔ 앰비언트** — H1 코드 재사용, 시연에서 가장 눈에 띔(기획서엔 "승인 시")
3. **실측된 차량 읽기를 `Sense`에** — 문(하차·우산) · 좌석(동승자 모드 자동) · 브레이크·주행거리(온기) — 매니페스트에 `CAR_DOORS`·`CAR_SEATS`·`CAR_DRIVE`
4. **NaviHelper `getBookmarkInfo`** — "집에 가자"(에뮬 호출 1번으로 검증) · ✅ `addWaypoint`·`requestRouteWithWaypoints`(말로 경유지, 11-07 실측)
5. **프로브 1회 확장** — 깜빡이·벨트·와이퍼·크루즈·조향각 + 카메라·제한속도·주의 구간 실수신 + TTS 덕킹

## 승인·문의 필요 (현대·42dot)

Gleo 스키마 검토(실차) · `CONTROL_CAR_*`(Coming Soon) · `CAR_*` 조회 권한(앱마켓 협의) · TTS·STT·FusedLocation 서버 모드 Client ID/Secret · 서드파티 불가 권한(telltales·`CAR_TIRES`·`CAR_EXTERIOR_LIGHTS`) · 내비 탑재 확인 API(partnership@pleos.ai) · "제휴 계약 필요" 항목. 공모전 시연(에뮬)은 승인과 무관, 기획서의 실차 동작은 "승인 시"로.
