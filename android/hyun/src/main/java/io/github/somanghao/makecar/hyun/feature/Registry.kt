package io.github.somanghao.makecar.hyun.feature

import io.github.somanghao.makecar.hyun.core.Feature

/**
 * 현비서 기능 목록 — **새 기능은 `Feature`를 구현한 파일 하나 + 여기 한 줄**(서버 쪽 짝은 `server/hyun/features/` + 그 `__init__` 한 줄).
 * 순서 = 훅 순서: 말 가로채기(onHeard)·규칙 답(offline·fallback)은 앞 기능이 먼저 — 명령(조용히 · 듣기 모드 · 캐릭터)을 맨 앞에.
 * 차/폰 어디서 켤지는 각 기능의 enabled(car).
 *
 * 다음에 붙일 하부 모듈(wiki/hyun-modules.md): 쉼표 · 튜토리얼(초보) · 온기(안전 km 포인트) · 캐릭터 만들기 · 오늘 할 일 · 엔터(끝말잇기 · 동요) · 브리핑
 */
object Registry {
    fun all(): List<Feature> = listOf(
        QuietFeature(),        // "조용히 해 줘" · "이제 말해도 돼"
        ListenModeFeature(),   // "상시 모드로" · "버튼 모드로"(차)
        CharacterFeature(),    // 캐릭터 3종 · "캐릭터 바꿔 줘"(차)
        GleoFeature(),         // Gleo Interface — ASK_HYUN · HYUN_STATUS · HYUN_QUIET(차)
        RouteFeature(),        // 목적지 · 경유지 · 안내 끝 · 주행 이벤트(출발 · 절반 · 곧 도착 · 도착 · 목적지 없이 출발)
        ScoutFeature(),        // 경로 정찰 · 우회 타이밍 · 되돌리기 · 적응 주기 · 휴게소 알림(차)
        WeatherFeature(),      // 날씨(위치 → Open-Meteo, 키 없음)
        CarBatteryFeature(),   // 차 배터리(Vehicle SDK) · 20% 아래 알림(차)
        DriveCareFeature(),    // 장시간 운전 · 한동안 조용하면 먼저 말 걸기(차) — 쉼표 모듈의 자리
        PhoneFeature(),        // 짭비서 — 폰 모드 · 일정 · 리마인더 · 할 일 · 산책 · 하차 알림(폰)
    )
}
