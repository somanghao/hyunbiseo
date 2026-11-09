# NaviHelper SDK v3.0.5

> 원문: `reference/pleos-docs/pleos-only__connect-sdk-pleos__NaviHelper__*.md` · 전체 이름 `reference/pleos-api-index.md` §NaviHelper. 공통 → [[pleos-sdk-overview]]

- 3rd party 앱 → NaviHelper → Pleos Connect **Navi Service**(내장 Navigation 앱)에 요청. 결과·주행 변화는 `NaviHelperEventListener` 콜백. 실제 전달 이벤트는 Navigation 앱 구현·상태에 따라 다름
- **내비 탑재 차량에서만** 동작. 신청 없이 사용 가능(문서 기준)
- 의존성 `implementation("ai.pleos.playground:NaviHelper:3.0.5")` (repo는 [[pleos-sdk-overview]])
- 순서: `NaviHelper(applicationContext)` → `initialize(onConnected, onFailed)`(권장) → `addListener()` → 요청/조회 → `removeListener()` + `release()`

```kotlin
val naviHelper = NaviHelper(applicationContext)
naviHelper.initialize(
    onConnected = { /* 리스너 등록, API 호출 */ },
    onFailed = { throwable -> /* 초기 연결 재시도 모두 실패 */ }
)
```

## API (`ai.pleos.playground.navi.helper.NaviHelper`)

| 구분 | 메서드 | 권한 |
|---|---|---|
| 정보 조회 | `getBookmarkInfo` · `getChargerOperatorInfo` · `getCurrentLocationInfo` · `getDestinationInfo` · `getRecentDestinationInfo` · `getRouteStateInfo` · `getTBTInfo` · `getWaypointInfo` | NAVI_ROUTE |
| 트립 | `requestTrip` | NAVI_ROUTE |
| 경로 변경 | `requestRoute` · `requestRouteWithWaypoints` · `cancelRoute` · `requestReRoute` · `addWaypoint` · `removeWaypoint` · `changeRouteOption` | NAVI_ROUTE_SEARCH |
| 화면 | `requestNavigationScreen` | NAVI_CUSTOM_MAP |

## 이벤트 (`NaviHelperEventListener`)

목적지/경유지: `onDestinationChanged` · `onDestinationArrived` · `onDestinationInfo` · `onWaypointChanged` · `onWayPointArrived` · `onWayPointInfo` · `onBookMarkChanged` · `onBookmarkInfo` · `onRecentDestinationInfo`
경로: `onRouteStarted` · `onRoutingCompleted`(구 `onRouteEnded`) · `onReRouteEnded` · `onRouteCancelled` · `onRouteOptionChanged` · `onRouteStateInfo` · `onNavigationStatus(NavigationStatus)`
주행 중 정보: `onTBTInfo` · `onDrivingInfo` · `onTripInfo` · `onCurrentLocationInfo` · **`onCurrentRoadSpeedLimit`** · **`onCameraAlert`** · **`onCautionPointInfo`** · `onChargerOperatorInfo` · `onError`

## 데이터·상수 (기획에 쓸 만한 것)

- 모델: `DestinationInfo`·`WaypointInfo`(remainDistance/remainTime) · `RouteInfo` · `TBTInfo` · `DrivingInfo`/`DrivingState`(과속 여부 `isOverSpeed`·구간 평균 등) · `TrafficInfo`/`TrafficCongestion*`/`TrafficEventInfo` · `TripInfo`/`TripSummary` · `ChargingPlan`/`ChargerOperator`(EV 충전) · `CameraAlert` · `CautionPoint`
- `CameraType`: 고정·이동·후면·스쿨존·신호·구간단속 시작/종료/구간속도 알림 등
- `CautionPointType`: 사고다발·급경사·낙석·버스전용차로·끼어들기 단속 등
- `BookmarkType`: DEFAULT · HOME · OFFICE · EV_CHARGING_STATION
- `RouteOption`, `TurnType`, `NaviMode`, `NaviErrorCode`
- ⚠️ 3.0.5부터 좌표 생성자 인자 순서가 **(latitude, longitude)** — 구 예제·블로그 코드(lng, lat) 주의

## 실측 (에뮬 v2.1.0, 2026-11-07 — 현비서 `android/hyun`)

- **경유지 OK**: `addWaypoint(RequestWaypointInfo(latitude, longitude, poiName, waypointIndex = FIRST|SECOND, poiId, address, poiSubId))`(바이너리 시그니처, poiProvider 기본값) → `onRouteCancelled` 먼저 → ~7초 뒤 `onWaypointChanged(ADD, FIRST, routeStartInfo)` → `onWayPointInfo`. `requestRouteWithWaypoints(RouteWithWaypointsRequest(RouteInfo, listOf(RequestWaypointInfo)))` → cancelled → ~7초 뒤 `onRouteStarted`. 시스템 DrivingInfo = 목적지 강남역 20km 53분 · waypoints [코엑스 14km 29분], 내비 화면에 경유 점
- ⚠️ 경유지가 있으면 **`onRouteStarted`·`WaypointChangedInfo.routeStartInfo`의 name·거리·시간 = 다음 구간(경유지까지)** — 목적지로 받으면 목적지 이름이 경유지로 바뀐다. 목적지 값은 `onDrivingInfo.destination`
- ⚠️ 새 경로·경유지 추가 전에 **`onRouteCancelled`가 먼저 온다** — 이것만 보고 '운전자가 안내를 끔'으로 판정하면 안 됨(현비서: 12초 안에 routeStarted·waypointChanged가 없을 때만)
- ⚠️ **`TBTInfo.distance`는 앞 안내에서부터의 구간 거리**(문서 미기재): 목록 `TURN_RIGHT@700;TURN_RIGHT@50;TURN_ENTER_IC_C@110;TURN_TOLL@40`(8개) · 내비 화면 "80m → 390m" → 내 차 기준은 누적합. 에뮬은 GPS가 안 움직여 `onTBTInfo`가 바뀔 때만 온다 → 필요하면 `getTBTInfo()`
- ⚠️ **명령(`requestRoute`·`cancelRoute`·경유지·옵션)을 받으면 내비 앱(`ai.umos.maps.android.navigation.app/.MainActivity`)이 스스로 맨 앞으로 나온다**(11-08 실측 0.5초 — `BAL_ALLOW_SAW_PERMISSION`). 부른 앱(1칸)은 가려진다 · 반반 화면이면 내비는 옆 칸. 우리 앱이 뒤에서 다시 앞으로 오는 건 막힌다(`BAL_BLOCK`) → 명령 직전 보이지 않는 화면으로 10초 유예를 만들어 되돌림 → [[hyun-app]] §차 화면 '현비서 화면 지키기' · `RouteInfo.isMobileRequest = true`(문서 없음 — navi-data 3.0.4 바이너리, 생성자 11번째 인자)여도 똑같이 뜬다(11-09 실측) — 안 띄우는 SDK 옵션은 없음
- ⚠️ **새 경로를 요청하면 옛 경로 TBT가 한 번 더 온다**(11-08 — `nav 코엑스` 0.3초 뒤 강남역 경로 `TURN_RIGHT@700:대왕판교IC 방면`), 새 경로가 선 뒤엔 TBT를 안 보낼 때가 있다 → 새 경로가 설 때까지(`onRouteStarted`·`onWaypointChanged`·`onReRouteEnded`) 받은 TBT는 버리고, 서면 `getTBTInfo()`. `TBTInfo.description` = "대왕판교IC 방면"·"대왕판교톨게이트"(방면·시설 이름)
- `TurnType`엔 분기 종류가 풍부 — `TURN_ENTER_IC_*`·`TURN_EXIT_IC_*`·`TURN_TOLL`·`TURN_TUNNEL`·`TURN_ENTER_SERVICEAREA`·`TURN_RESTAREA`·`TURN_WAY`(경유지)·`TURN_DEVIDE_MERGE` 등 43종
- `changeRouteOption` 뒤 에뮬은 `onRouteOptionChanged`를 안 줬다 → 지금 옵션은 `RouteStateInfo.routeOption`(첫 값 RECOMMENDED). `RouteOption` = RECOMMENDED·SHORT_DISTANCE·SHORT_TIME·FREE·HIGHWAY·COMFORTABLE·SCHOOL_ZONE. 바꾸기 전 대안 경로 미리보기 API는 없음
