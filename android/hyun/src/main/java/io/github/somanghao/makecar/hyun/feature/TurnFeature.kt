package io.github.somanghao.makecar.hyun.feature

import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.os.SystemClock
import io.github.somanghao.makecar.hyun.core.Feature
import io.github.somanghao.makecar.hyun.core.Hyun
import io.github.somanghao.makecar.hyun.ui.HyunView

/**
 * 회전 안내(TBT) 화면 — 내비가 준 다음 안내들을 현비서 화면에 그대로 보인다(사용자 11-08 "현비서 화면에서 내비가 보여야 — 좌회전·우회전").
 * 1칸(가로) = 캐릭터 오른쪽 정보창 위 내비 판(다음 회전 크게 · 그다음 · 이어지는 안내 · 목적지·남은 거리·도착·교통) · 반반 = 길 카드 맨 위 한 줄.
 * 거리는 내비 값 그대로 — 첫 안내만 내 차 기준, 그다음부터는 '앞 안내에서부터' 구간 거리(11-07 실측) → 화면(HyunView)이 누적한다.
 * 에뮬은 GPS가 안 움직여 onTBTInfo가 바뀔 때만 온다 → 필요할 때 getTBTInfo로 묻는다.
 * 실측 함정(11-08): 새 경로를 요청하면 내비가 **옛 경로 안내를 한 번 더** 보내고, 새 경로가 선 뒤엔 안 보낼 때가 있다
 *   → 새 경로가 설 때까지(onRouteStarted · onWaypointChanged · onReRouteEnded) 받은 안내는 버리고, 서면 새로 묻는다.
 * 짝: 내비 명령 때 내비 앱이 현비서를 덮으면 코어가 다시 앞으로(core/Hyun.keepFront).
 */
class TurnFeature : Feature {
    override val id = "turn"
    override fun enabled(car: Boolean) = car
    private lateinit var hub: Hyun
    private var turns = listOf<HyunView.Turn>()
    private var waitSince = 0L                    // 새 경로를 기다리는 중(0 = 아님) — 그동안 온 안내는 옛 경로 것
    private var askedAt = 0L

    override fun start(hub: Hyun) {
        this.hub = hub
        hub.navi?.listen(naviL)
    }

    /** 목적지가 새로 정해짐(→ 새 경로를 기다림) 또는 안내 끝 */
    override fun onTripReset() {
        turns = emptyList()
        waitSince = if (hub.trip.dest != null) SystemClock.elapsedRealtime() else 0L
    }

    // 목적지는 있는데 안내를 아직 못 받았으면 5초마다 묻는다 — 앱을 켤 때 코어의 getTBTInfo(1.2초 뒤)가 목적지 정보보다 먼저 가면 빈손(11-08 실측)
    override fun tick() {
        val now = SystemClock.elapsedRealtime()
        if (waitSince != 0L && now - waitSince > WAIT_MAX_MS) waitSince = 0L   // 경로가 섰다는 소식을 못 받아도 묶여 있지 않게
        if (hub.trip.dest != null && turns.isEmpty() && waitSince == 0L && now - askedAt > 5_000) ask()
    }

    override fun render(v: HyunView) { v.turns = if (hub.trip.dest != null) turns else emptyList() }

    private fun ask() { askedAt = SystemClock.elapsedRealtime(); hub.navi?.call("getTBTInfo") { getTBTInfo() } }

    /** 새 경로가 섰다 — 기다림을 풀고 새 안내를 묻는다 */
    private fun routeReady() { waitSince = 0L; hub.main.postDelayed(::ask, 600) }

    private val naviL = object : NaviHelperEventListener {
        override fun onTBTInfo(tbtInfos: List<TBTInfo>) {
            if (waitSince != 0L) return   // 새 경로가 서기 전 = 옛 경로 안내
            turns = tbtInfos.map { HyunView.Turn(it.type.name, it.distance.toInt(), it.description.orEmpty()) }
            hub.render()
        }
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = routeReady()
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) = routeReady()
        override fun onReRouteEnded() = routeReady()
    }

    companion object {
        private const val WAIT_MAX_MS = 20_000L   // 새 경로는 보통 6~8초 안에 선다(11-07·08 실측)
    }
}
