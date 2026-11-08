package io.github.somanghao.makecar.hyun.feature

import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Intent
import android.os.SystemClock
import io.github.somanghao.makecar.hyun.core.Feature
import io.github.somanghao.makecar.hyun.core.Fmt
import io.github.somanghao.makecar.hyun.core.Hyun
import io.github.somanghao.makecar.hyun.drive.Trip
import io.github.somanghao.makecar.hyun.ui.HyunView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/**
 * 길 — 말로 목적지 · 경유지(최대 2) 넣기/빼기 · 안내 끝 + 주행 이벤트(출발 · 절반 · 곧 도착 · 도착 · 목적지 없이 출발 · 경유지 도착).
 * 차 = NaviHelper(경로·남은 거리·시간은 내비 값) · 폰 = 목적지 좌표까지 직선거리 × 1.3 어림.
 * 두뇌 답 필드: nav{name,lat,lng} · via{…} · route = cancel | clear_via (우회 옵션은 ScoutFeature)
 * 시연 인텐트: --es dest 강남역 --ef lat 37.4979 --ef lng 127.0276 · --es via "코엑스|37.5116|127.0595" · --ez clearvia true · --ez cancel true
 * 실측 함정(11-07): 경유지가 있으면 onRouteStarted = 다음 구간 · 새 경로 전에 onRouteCancelled가 먼저(12초 안에 새 경로가 안 서면 '내비에서 끔')
 */
class RouteFeature : Feature {
    override val id = "route"
    private lateinit var hub: Hyun
    private val trip: Trip get() = hub.trip

    data class Via(val name: String, val remainM: Int, val remainS: Int)
    var vias = listOf<Via>()
        private set
    var destLat: Double? = null                   // 목적지 좌표 — 폰은 GPS 직선거리 어림 · 차는 맥락(도착 추천이 목적지 근처를 찾는다)
        private set
    var destLng: Double? = null
        private set
    private var noDestS = 0                       // 목적지 없이 달린 초 — NO_DEST_S면 주행당 한 번 "어디로 가세요?"
    private var noDestAsked = false
    @Volatile private var lastRouteAt = 0L        // 마지막으로 새 경로가 선 때 — 내비에서 안내를 끈 것과 새 경로 전 취소를 가른다
    private var lastAskAt = 0L                    // 마지막으로 경로를 요청한 때 — 그 뒤의 '취소 12초' 판정은 옛 취소라 새 목적지를 지우지 않는다

    override fun start(hub: Hyun) {
        this.hub = hub
        hub.navi?.listen(naviL)
    }

    // ---------- 1초: 주행 이벤트(차) ----------
    override fun tick() {
        if (!hub.isCar) return   // 폰의 하차·도착은 PhoneFeature(모드에 따라 다르다)
        val f = trip.fired
        // 출발했는데 목적지가 없다 — 20초 넘게 달리면 주행당 한 번 "어디로 가세요?"(5분 넘게 섰다 다시 출발하면 새 주행)
        if (trip.driveS < 1) { noDestAsked = false; noDestS = 0 }
        if (trip.dest == null && hub.speedMs > hub.moveMs) noDestS++ else if (trip.dest != null) noDestS = 0
        if (!noDestAsked && noDestS >= NO_DEST_S) { noDestAsked = true; hub.fire("no_dest") }
        if (trip.dest == null) return
        when {
            // 출발 = 정찰 먼저(경로 교통 요약이 출발 한마디에 실리게) → 2.5초 뒤 이벤트
            "depart" !in f && hub.speedMs > hub.moveMs -> {
                f += "depart"; hub.navi?.call("scan") { getRouteStateInfo() }; hub.main.postDelayed({ hub.fire("depart") }, 2500)
            }
            "arrived" !in f && "depart" in f && trip.remainM <= 150 && hub.speedMs < 2f -> hub.fire("arrived")
            "near" !in f && "depart" in f && trip.totalM >= 2500 && trip.remainM <= 1000 -> hub.fire("near")
            "halfway" !in f && "depart" in f && trip.totalM >= 3000 && trip.progress >= 0.5 -> hub.fire("halfway")
        }
    }

    // ---------- 두뇌 답 ----------
    override fun onReply(kind: String, text: String, r: JSONObject) {
        // 목적지·경유지 — 둘 다 오면 경유지 넣은 경로 한 번에, 경유지만 오면 지금 안내에 덧붙인다
        val nav = r.optJSONObject("nav"); val via = r.optJSONObject("via")
        // 두뇌가 지금 목적지를 다시 실어 보내도(경유지만 말했는데) 경로를 새로 잡지 않고 지금 안내에 경유지만 넣는다
        if (nav != null && via != null && trip.dest != null && nav.optString("name") == trip.dest) addVia(via.getString("name"), via.getDouble("lat"), via.getDouble("lng"))
        else if (nav != null) goTo(nav.getString("name"), nav.getDouble("lat"), nav.getDouble("lng"),
            via?.let { Triple(it.getString("name"), it.getDouble("lat"), it.getDouble("lng")) })
        else via?.let { addVia(it.getString("name"), it.getDouble("lat"), it.getDouble("lng")) }
        when (r.optString("route")) {
            "cancel" -> cancelTrip("두뇌")
            "clear_via" -> clearVias()
        }
    }

    override fun onIntent(i: Intent, first: Boolean) {
        if (i.getBooleanExtra("cancel", false)) { cancelTrip("demo"); if (first) hub.main.postDelayed({ hub.navi?.call("cancelRoute") { cancelRoute() } }, 2500) }   // 처음엔 내비 연결 뒤에 한 번 더
        i.getStringExtra("dest")?.let { d -> hub.main.postDelayed({ goTo(d, i.getFloatExtra("lat", 37.4979f).toDouble(), i.getFloatExtra("lng", 127.0276f).toDouble()) }, if (first) 2500 else 0) }
        i.getStringExtra("via")?.split("|")?.takeIf { it.size >= 3 }?.let { p ->
            hub.main.postDelayed({ addVia(p[0], p[1].toDouble(), p[2].toDouble()) }, if (first) 4000 else 0)
        }
        if (i.getBooleanExtra("clearvia", false)) clearVias()
    }

    // ---------- 목적지 · 경유지 · 끝 ----------
    /** 목적지(차 = NaviHelper · 폰 = 직선거리 어림) — 경유지를 같이 주면 경유지 넣은 경로 한 번에 */
    fun goTo(name: String, dLat: Double, dLng: Double, via: Triple<String, Double, Double>? = null) {
        val here = hub.lat?.let { a -> hub.lng?.let { b -> Trip.meters(a, b, dLat, dLng) } }
        val est = (here ?: 10_000.0) * 1.3   // 도로 거리 어림 — 내비가 경로를 잡으면 그 값으로 바뀐다
        trip.setDestination(name, est, 0.0, estimate = true)
        vias = emptyList(); noDestS = 0
        hub.tripReset()
        destLat = dLat; destLng = dLng; lastAskAt = SystemClock.elapsedRealtime()
        hub.log("nav|$name|$dLat,$dLng|est=${est.toInt()}m${via?.let { "|via=${it.first}" } ?: ""}")
        if (hub.isCar) hub.navi?.call("requestRoute") {
            val dest = RouteInfo(latitude = dLat, longitude = dLng, displayLongitude = null, displayLatitude = null,
                poiName = name, poiId = "", poiSubId = "0", address = name)
            if (via == null) requestRoute(dest)
            else {
                requestRouteWithWaypoints(RouteWithWaypointsRequest(dest, listOf(RequestWaypointInfo(latitude = via.second, longitude = via.third,
                    poiName = via.first, waypointIndex = WaypointIndex.FIRST, poiId = "", address = via.first, poiSubId = "0"))))
                vias = listOf(Via(via.first, 0, 0))
                hub.main.postDelayed({ hub.navi?.call("getWaypointInfo") { getWaypointInfo() } }, 3000)
            }
        }
        hub.render()
    }

    /** 경유지 — 길 안내 중이면 addWaypoint(빈 자리, 최대 2). 목적지가 아직 없으면 그곳을 목적지로 */
    fun addVia(name: String, la: Double, lo: Double) {
        if (trip.dest == null) { hub.log("via|no-dest → 목적지로|$name"); goTo(name, la, lo); return }
        if (vias.any { it.name.replace(" ", "") == name.replace(" ", "") }) { hub.log("via|already|$name"); return }   // 두뇌가 다시 실어 보냄
        if (vias.size >= 2) { hub.log("via|full|$name"); hub.say("경유지는 두 곳까지만 넣을 수 있어요.", "calm", false, 1, reply = true, tag = "경유지"); return }
        val idx = if (vias.isEmpty()) WaypointIndex.FIRST else WaypointIndex.SECOND
        hub.log("via|add|$name|$la,$lo|$idx|dest=${trip.dest}")
        if (hub.isCar) hub.navi?.call("addWaypoint") {
            addWaypoint(RequestWaypointInfo(latitude = la, longitude = lo, poiName = name, waypointIndex = idx, poiId = "", address = name, poiSubId = "0"))
        }
        vias = vias + Via(name, 0, 0)   // 내비가 남은 거리를 주면 onWayPointInfo가 덮어쓴다
        hub.main.postDelayed({ hub.navi?.call("getWaypointInfo") { getWaypointInfo() } }, 3000)
        hub.render()
    }

    fun clearVias() {
        hub.log("via|clear|${vias.map { it.name }}")
        hub.navi?.call("removeWaypoint") { removeWaypoint(WaypointIndex.ALL) }
        vias = emptyList(); hub.render()
    }

    /** 길 안내 끝 — 목적지·경유지·경로에 묶인 상태를 비우고 내비 안내도 끈다 */
    fun cancelTrip(by: String) {
        hub.log("nav|cancel|$by|${trip.dest}")
        trip.clear(); vias = emptyList(); destLat = null; destLng = null
        hub.tripReset()
        hub.navi?.call("cancelRoute") { cancelRoute() }
        hub.render()
    }

    // ---------- 내비 콜백(main) ----------
    private val naviL = object : NaviHelperEventListener {
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            trip.navi(destinationInfo.name, destinationInfo.remainDistance.toDouble(), destinationInfo.remainTime.toDouble())
            // 내비에서 정한 목적지(앱을 켜기 전·내비 화면에서)도 좌표를 안다 — 안내가 없으면 0(11-08 실측 latitude=0.0)
            if (destinationInfo.latitude != 0.0 && destinationInfo.longitude != 0.0) { destLat = destinationInfo.latitude; destLng = destinationInfo.longitude }
        }
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            lastRouteAt = SystemClock.elapsedRealtime()
            // 경유지가 있으면 이 값은 '다음 구간'(경유지까지)이다 — 목적지 이름·거리로 덮지 않는다(11-07 실측: name=코엑스). 목적지 값은 onDrivingInfo가 준다
            if (vias.any { it.name == routeStartInfo.name }) vias = vias.map { if (it.name == routeStartInfo.name) Via(it.name, routeStartInfo.distance, routeStartInfo.duration) else it }
            else trip.navi(routeStartInfo.name, routeStartInfo.distance.toDouble(), routeStartInfo.duration.toDouble())
        }
        override fun onDrivingInfo(drivingInfo: DrivingInfo) {
            drivingInfo.destination?.let { d -> trip.navi(d.name, d.distance.toDouble(), d.duration.toDouble()) }
            drivingInfo.waypoints?.takeIf { it.isNotEmpty() }?.let { ws -> vias = ws.map { Via(it.name, it.distance, it.duration) } }
        }
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) {
            lastRouteAt = SystemClock.elapsedRealtime()   // 경유지를 넣어도 먼저 cancelled가 온다 — 이건 새 경로다(11-07 실측)
            waypointChangedInfo.routeStartInfo?.let { r -> trip.navi(r.name, r.distance.toDouble(), r.duration.toDouble()) }
            hub.navi?.call("getWaypointInfo") { getWaypointInfo() }
        }
        override fun onWayPointInfo(waypointInfos: List<WaypointInfo>) {
            vias = waypointInfos.map { Via(it.name, it.remainDistance, it.remainTime) }; hub.render()
        }
        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {
            hub.fire("waypoint", JSONObject().put("viaName", waypointArrivedInfo.name))
        }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            if ("arrived" !in trip.fired) hub.fire("arrived")
        }
        override fun onRouteCancelled() {
            // 새 경로를 잡을 때도 먼저 cancelled가 온다(경유지 경로 실측) — 12초 안에 새 경로가 안 서면 운전자가 내비에서 끈 것으로 보고 비운다
            // 단 그 사이 우리가 새 경로를 요청했으면(취소 → 곧바로 "강남역 가자") 옛 취소다 — 11-08 실측: 길이 서기 전(6초)에 새 목적지를 지웠다
            val at = SystemClock.elapsedRealtime()
            hub.main.postDelayed({
                if (lastRouteAt < at && lastAskAt < at && trip.dest != null) { hub.log("nav|cancelled-by-navi"); trip.clear(); vias = emptyList(); hub.tripReset(); hub.render() }
            }, 12_000)
        }
    }

    // ---------- 폰: 직선거리 어림 ----------
    override fun onLocation(lat: Double, lng: Double, addr: String?, first: Boolean) {
        val dl = destLat; val dn = destLng
        if (!hub.isCar && dl != null && dn != null && trip.dest != null) trip.navi(trip.dest, Trip.meters(lat, lng, dl, dn) * 1.3, 0.0)
    }

    // ---------- 맥락 · 화면 · 규칙 문장 ----------
    override fun ctx(c: JSONObject) {
        val d = trip.dest ?: return
        val now = System.currentTimeMillis()
        c.put("dest", d); c.put("remainKm", Fmt.km(trip.remainM)); c.put("remainMin", hub.mins())
        c.put("eta", Fmt.hhmm(now + (trip.remainS * 1000).toLong())); c.put("totalKm", Fmt.km(trip.totalM))
        destLat?.let { la -> destLng?.let { lo -> c.put("destLat", Math.round(la * 1e5) / 1e5); c.put("destLng", Math.round(lo * 1e5) / 1e5) } }
        if (vias.isNotEmpty()) c.put("via", JSONArray(vias.map { v ->
            v.name + (if (v.remainM > 0) " · ${Fmt.km(v.remainM.toDouble())}km · ${ceil(v.remainS / 60.0).toInt()}분" else "")
        }))
    }

    override fun render(v: HyunView) {
        v.dest = trip.dest
        v.via = vias.firstOrNull()?.let { x -> "${x.name} 거쳐" + (if (x.remainM > 0) " · ${Fmt.km(x.remainM.toDouble())}km ${ceil(x.remainS / 60.0).toInt()}분" else "") } ?: ""
        if (trip.dest != null) {
            v.remain = "${Fmt.km(trip.remainM)}km · ${Fmt.dur(hub.mins())}"
            v.eta = "도착 ${Fmt.hhmm(System.currentTimeMillis() + (trip.remainS * 1000).toLong())}"
            v.progress = trip.progress
        }
    }

    override fun offline(text: String): String? {
        val t = text.replace(" ", ""); val d = trip.dest ?: return null
        return if (t.contains("남았") || t.contains("도착") || t.contains("얼마나")) "${d}까지 ${Fmt.km(trip.remainM)}킬로미터, ${Fmt.dur(hub.mins())} 남았어요." else null
    }

    override fun fallback(ev: String, extra: JSONObject?): String? {
        val d = trip.dest
        return when (ev) {
            "depart" -> d?.let {
                val summary = hub.feature<ScoutFeature>()?.summary.orEmpty()
                val rain = hub.feature<WeatherFeature>()?.rainPct ?: 0
                "${it}까지 ${Fmt.km(trip.remainM)}킬로미터, ${Fmt.dur(hub.mins())}쯤 걸려요." +
                    when { summary.isBlank() -> ""; summary.startsWith("내비") -> " 막히는 곳은 없어요."; else -> " 내비 기준 ${summary}예요." } +
                    if (rain >= 50) " 비 소식이 있으니 천천히 가요." else " 편하게 가요."
            }
            "no_dest" -> "어디로 가세요? 목적지를 말해 주시면 길 안내하고 교통도 봐 드릴게요."
            "waypoint" -> "경유지 ${extra?.optString("viaName")?.ifBlank { null } ?: vias.firstOrNull()?.name ?: ""}에 왔어요. 볼일 보고 출발하면 ${d ?: "목적지"}까지 이어서 안내할게요."
            "halfway" -> d?.let { "벌써 절반 왔어요. ${Fmt.dur(hub.mins())} 남았어요." }
            "near" -> "곧 도착이에요. 내릴 준비 하세요."
            "arrived" -> "도착했어요. 오늘도 수고 많으셨어요."
            else -> null
        }
    }

    companion object {
        private const val NO_DEST_S = 20          // 목적지 없이 이만큼(초) 달리면 "어디로 가세요?"
    }
}
