package io.github.somanghao.makecar.hyun.drive

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 내비(NaviHelper) 한 연결을 여러 기능이 같이 쓴다 — SDK 리스너는 하나, 콜백을 등록한 기능 모두에게 **main 스레드에서** 나눠 준다.
 * 기능은 `NaviHelperEventListener`의 필요한 메서드만 override해서 [listen](모든 메서드에 기본 구현이 있다 — SDK 3.0.5 바이너리).
 * 요청은 [call]로(실패는 로그만). 로그 줄(`navi|…`)은 여기서 한 번만 — 시험 기록(tools/hyun_e2e)이 이 줄을 읽는다.
 * 실측 함정(11-07): 새 경로 전에 onRouteCancelled가 먼저 온다 · 경유지가 있으면 onRouteStarted = 다음 구간 · TBT 거리는 구간 거리
 */
class Navi(ctx: Context, private val main: Handler, private val log: (String) -> Unit) {
    val helper = NaviHelper(ctx.applicationContext)
    private val listeners = CopyOnWriteArrayList<NaviHelperEventListener>()
    @Volatile var connected = false
        private set

    fun listen(l: NaviHelperEventListener) { listeners += l }

    fun start(onConnected: () -> Unit) {
        runCatching {
            helper.initialize(
                onConnected = { connected = true; log("navi|connected"); helper.addListener(fan); main.post(onConnected) },
                onFailed = { t -> log("navi|init|FAIL|${t.message}") },
            )
        }.onFailure { log("navi|init|${it.message}") }
    }

    /** 마지막으로 내비에 '명령'(경로·경유지·취소·옵션)을 보낸 때 — 이 요청들은 내비 화면을 맨 앞으로 띄운다(11-08 실측: requestRoute 1초 안).
     *  코어가 이 시각 직후 가려지면 현비서를 다시 앞으로 → [io.github.somanghao.makecar.hyun.core.Hyun.onStop] */
    @Volatile var lastCommandAt = 0L
        private set
    /** 명령 직전에 부른다(main) — 코어가 '보이지 않는 한순간 화면'으로 10초 유예를 만든다 */
    var beforeCommand: (String) -> Unit = {}

    /** 내비 요청 — 예외는 로그만(`navi|<what>|FAIL|…`). 명령 이름은 [COMMANDS] — 새 명령을 부르면 거기에도 */
    fun call(what: String, f: NaviHelper.() -> Unit) {
        if (what in COMMANDS) { runCatching { beforeCommand(what) }; lastCommandAt = SystemClock.elapsedRealtime() }
        runCatching { helper.f() }.onFailure { log("navi|$what|FAIL|${it.javaClass.simpleName} ${it.message}") }
    }

    fun release() { runCatching { helper.release() } }

    private fun each(f: (NaviHelperEventListener) -> Unit) = main.post { listeners.forEach(f) }

    private var lastDrivingLog = 0L
    private var lastTbtLog = 0L
    private var lastTbt = ""

    private val fan = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) { log("navi|location|${info.latitude},${info.longitude}|${info.address}"); each { it.onCurrentLocationInfo(info) } }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) { log("navi|destination|$destinationInfo"); each { it.onDestinationInfo(destinationInfo) } }
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) { log("navi|routeStarted|$routeStartInfo"); each { it.onRouteStarted(routeStartInfo) } }
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            val ts = routeStateInfo.trafficStatus
            log("navi|routeState|${routeStateInfo.naviMode}|${routeStateInfo.routeOption}|${ts?.summary}|jams=${ts?.congestionItemList?.size}|events=${ts?.trafficEventList?.size}|${ts?.trafficEventList?.take(3)}")
            each { it.onRouteStateInfo(routeStateInfo) }
        }
        override fun onCautionPointInfo(cautionPoints: List<CautionPoint>) {
            if (cautionPoints.isNotEmpty()) log("navi|caution|${cautionPoints.take(5)}")
            each { it.onCautionPointInfo(cautionPoints) }
        }
        override fun onTBTInfo(tbtInfos: List<TBTInfo>) {
            val now = SystemClock.elapsedRealtime()
            val line = "${tbtInfos.size}|${tbtInfos.take(4).joinToString(";") { "${it.type.name}@${it.distance.toInt()}:${it.description}" }}"
            if (line != lastTbt || now - lastTbtLog > 15_000) { lastTbtLog = now; lastTbt = line; log("navi|tbt|$line") }   // 바뀌면 바로 · 같으면 15초마다
            each { it.onTBTInfo(tbtInfos) }
        }
        override fun onReRouteEnded() { log("navi|reRouteEnded"); each { it.onReRouteEnded() } }
        override fun onRouteOptionChanged(routeOption: RouteOption) { log("navi|routeOption|$routeOption"); each { it.onRouteOptionChanged(routeOption) } }
        override fun onDrivingInfo(drivingInfo: DrivingInfo) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastDrivingLog > 10_000) { lastDrivingLog = now; log("navi|driving|$drivingInfo") }
            each { it.onDrivingInfo(drivingInfo) }
        }
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) {
            log("navi|waypointChanged|${waypointChangedInfo.cmdType}|${waypointChangedInfo.index}|${waypointChangedInfo.routeStartInfo}")
            each { it.onWaypointChanged(waypointChangedInfo) }
        }
        override fun onWayPointInfo(waypointInfos: List<WaypointInfo>) {
            log("navi|waypoints|${waypointInfos.joinToString(";") { "${it.name}@${it.remainDistance}m/${it.remainTime}s" }}")
            each { it.onWayPointInfo(waypointInfos) }
        }
        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) { log("navi|waypointArrived|$waypointArrivedInfo"); each { it.onWayPointArrived(waypointArrivedInfo) } }
        override fun onTripInfo(tripInfo: TripInfo) { log("navi|trip|$tripInfo"); each { it.onTripInfo(tripInfo) } }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) { log("navi|arrived|$destinationArrivedInfo"); each { it.onDestinationArrived(destinationArrivedInfo) } }
        override fun onRouteCancelled() { log("navi|cancelled"); each { it.onRouteCancelled() } }
        override fun onDestinationChanged() { log("navi|destinationChanged"); call("getDestinationInfo") { getDestinationInfo() }; each { it.onDestinationChanged() } }
        override fun onError(errorCode: NaviErrorCode) { log("navi|error|$errorCode"); each { it.onError(errorCode) } }
    }

    companion object {
        /** 내비 화면을 앞으로 띄우는 요청들(call 이름) — 읽기(get·scan·getTBTInfo·getWaypointInfo)는 안 띄운다 */
        val COMMANDS = setOf("requestRoute", "cancelRoute", "addWaypoint", "removeWaypoint", "route", "revert")
    }
}
