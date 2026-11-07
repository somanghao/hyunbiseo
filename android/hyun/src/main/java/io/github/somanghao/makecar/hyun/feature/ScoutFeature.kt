package io.github.somanghao.makecar.hyun.feature

import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Intent
import android.os.SystemClock
import io.github.somanghao.makecar.hyun.core.Feature
import io.github.somanghao.makecar.hyun.core.Hyun
import io.github.somanghao.makecar.hyun.drive.Scout
import io.github.somanghao.makecar.hyun.ui.HyunView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil

/**
 * 경로 정찰(최우선, 차) — 내비가 주는 내 경로 교통을 상황별 주기로 읽어 새 위험만 넘긴다(판정 = [Scout]).
 * 가까운 사고·역주행은 앱이 먼저 경고(P0) → 두뇌가 우회를 제안 — **우회 가능한 마지막 갈림길 전에만**(detour), 바꿨는데 시간이 안 줄면 원래 옵션으로.
 * 휴게소 진입 안내가 3km 안이면 한 번 소개(restarea 이벤트 — 서버가 검색 모드).
 * 두뇌 답 필드: route = fast | highway | free | comfort | reroute (cancel·clear_via는 RouteFeature)
 * 시연 인텐트: --es traffic "accident|1200|설명" · --ei etaup 8 · --ez scan true · --ei scoutsec 30(0 = 적응 주기) · --es restarea "기흥휴게소|2500"
 */
class ScoutFeature : Feature {
    override val id = "scout"
    override fun enabled(car: Boolean) = car
    private lateinit var hub: Hyun
    private val scout = Scout()
    val summary get() = scout.summary              // 경로 교통 한 줄(출발 규칙 문장·글레오가 본다)
    fun english() = scout.english()

    private var scoutSec = 60                     // --ei scoutsec로 고정했을 때만(scoutFixed) — 평소엔 nextScanSec()가 상황별로
    private var scoutFixed = false
    private var scoutWhy = ""
    private var beforeReroute: Double? = null     // 우회 요청 직전 남은 시간(초) — 결과 비교용
    private var routeOption: RouteOption? = null  // 지금 경로 옵션(onRouteStateInfo · onRouteOptionChanged)
    private var prevOption: RouteOption? = null   // 우회 직전 옵션 — 바꿔도 시간이 안 줄면 이걸로 되돌린다
    private var rerouteHow = ""
    private var tbts = listOf<Pair<String, Double>>()   // 다음 안내들(종류 · 내 차 기준 누적 m)
    private var tbtAt = 0L
    private val restTold = mutableSetOf<String>()       // 이번 길에서 소개한 휴게소

    override fun start(hub: Hyun) {
        this.hub = hub
        hub.navi?.listen(naviL)
        hub.main.postDelayed(scan, 5000)
    }

    override fun onTripReset() { scout.reset(); restTold.clear() }

    // ---------- 주기 정찰 ----------
    private val scan = object : Runnable {
        override fun run() {
            if (hub.trip.dest != null) hub.navi?.call("scan") { getRouteStateInfo(); getTBTInfo() }
            hub.main.postDelayed(this, nextScanSec() * 1000L)
        }
    }

    /** 적응형 정찰 주기 — 정차 120초 · 고속(70km/h+)·분기 2km 앞·위험 3km 앞 25초 · 시내 저속(40km/h 미만) 90초 · 그 밖 60초. --ei scoutsec이면 고정 */
    private fun nextScanSec(): Int {
        if (scoutFixed || hub.trip.dest == null) return scoutSec
        val k = hub.kmh()
        val branch = nextBranchM()
        val jam = scout.nearestM
        val (sec, why) = when {
            hub.speedMs < 1.4f -> 120 to "정차"
            k >= 70 -> 25 to "고속 ${k}km/h"
            branch != null && branch <= 2000 -> 25 to "분기 ${branch.toInt()}m 앞"
            jam != null && jam <= 3000 -> 25 to "위험 ${jam}m 앞"
            k < 40 -> 90 to "시내 저속 ${k}km/h"
            else -> 60 to "보통 ${k}km/h"
        }
        val tag = why.substringBefore(" ")
        if (tag != scoutWhy) { scoutWhy = tag; hub.log("scout|every|${sec}s|$why") }
        return sec
    }

    // ---------- 우회 타이밍 게이트 ----------
    /** 다음 안내들 — 받은 뒤 달린 만큼 뺀다. 60초 넘게 묵은 안내면 모름(null) */
    private fun upcoming(): List<Pair<String, Double>>? {
        val age = SystemClock.elapsedRealtime() - tbtAt
        if (tbts.isEmpty() || age > 60_000) return null
        val moved = hub.speedMs * age / 1000.0
        return tbts.map { it.first to (it.second - moved) }.filter { it.second > 0 }
    }
    private fun nextBranchM(): Double? = upcoming()?.firstOrNull { it.first !in NOT_BRANCH && it.second > 30 }?.second

    /** possible = 분기가 위험보다 150m 넘게 앞 · none = 안내 목록이 위험까지 덮는데 그 앞에 분기 없음 · unknown = 안내 없음·묵음·목록이 짧음 */
    private fun detour(aheadM: Int?): Pair<String, Int?> {
        val list = upcoming()
        if (aheadM == null || list.isNullOrEmpty()) return "unknown" to null
        val b = list.firstOrNull { it.first !in NOT_BRANCH && it.second > 30 }
        return when {
            b != null && b.second + 150 < aheadM -> "possible" to b.second.toInt()
            list.maxOf { it.second } >= aheadM -> "none" to b?.second?.toInt()
            else -> "unknown" to b?.second?.toInt()
        }
    }

    /** 새로 찾은 위험 — TBT가 10초 넘게 묵었으면 새로 받고 0.8초 뒤 판정(내비는 바뀔 때만 onTBTInfo를 준다) */
    private fun report(fs: List<Scout.Finding>) {
        if (fs.isEmpty()) return
        if (hub.navi != null && hub.trip.dest != null && SystemClock.elapsedRealtime() - tbtAt > 10_000) {
            hub.navi?.call("getTBTInfo") { getTBTInfo() }
            hub.main.postDelayed({ reportNow(fs) }, 800)
        } else reportNow(fs)
    }

    /** 가까운 사고·역주행은 앱이 먼저 경고(P0), 알릴 만한 건 두뇌에게(우회 제안 — 분기가 남았을 때만), 사소한 건 맥락 요약에만 */
    private fun reportNow(fs: List<Scout.Finding>) {
        val dt = fs.map { detour(it.aheadM) }
        fs.forEachIndexed { k, f -> hub.log("scout|${f.src}|${f.kind}|sev=${f.severity}|ahead=${f.aheadM}|detour=${dt[k].first}@${dt[k].second}|${f.text}") }
        val ui = fs.indexOfFirst { it.severity >= 3 && (it.aheadM ?: Int.MAX_VALUE) <= 1500 }
        val urgent = fs.getOrNull(ui)
        urgent?.let { u ->
            val where = u.aheadM?.let { m -> if (m < 1000) "${(m / 100) * 100}미터 앞 " else "${Scout.km(m).removeSuffix("km")}킬로미터 앞 " } ?: "앞에 "
            val tail = if (dt[ui].first == "none") " 돌아갈 길은 없어요. 차로 지키고 천천히 가요." else " 속도 줄이고 조심하세요."
            hub.say("$where${u.kind} 정보예요.$tail", "alert", false, 0, tag = "scout")
        }
        if (urgent == null && fs.none { it.severity >= 2 }) return
        val arr = JSONArray(fs.mapIndexed { k, f -> f.json().put("detour", dt[k].first).put("branchM", dt[k].second ?: JSONObject.NULL) })
        val extra = JSONObject().put("findings", arr).put("appWarned", urgent?.text ?: JSONObject.NULL)
        if (hub.brainOn) hub.ask("event", "traffic", extra)
        else if (urgent == null) fs.withIndex().maxBy { it.value.severity }.let { (k, f) ->
            val tail = when (dt[k].first) { "none" -> " 돌아갈 길은 없으니 천천히 가요."; "possible" -> " 다음 갈림길에서 돌아갈 수 있어요."; else -> "" }
            hub.say("앞길에 ${f.kind} 정보가 있어요. ${f.text.substringAfter(" · ")}$tail", "care", false, 1, tag = "scout")
        }
    }

    // ---------- 우회 · 되돌리기 ----------
    override fun onReply(kind: String, text: String, r: JSONObject) {
        r.optString("route").takeIf { it in OPTIONS }?.let(::reroute)
    }

    /** 우회 — 바꾸기 전 남은 시간·옵션을 기억했다가 6초 뒤 비교(1분도 안 줄면 되돌림) */
    private fun reroute(how: String) {
        val trip = hub.trip
        beforeReroute = trip.remainS
        prevOption = routeOption ?: RouteOption.RECOMMENDED; rerouteHow = how
        hub.log("route|$how|before=${trip.remainS.toInt()}s|option=$prevOption")
        val opt = optionOf(how)
        hub.navi?.call("route") { if (opt != null) changeRouteOption(opt) else requestReRoute() }
        hub.main.postDelayed(::afterReroute, 6000)
    }

    private fun optionOf(how: String) = when (how) {
        "fast" -> RouteOption.SHORT_TIME; "highway" -> RouteOption.HIGHWAY; "free" -> RouteOption.FREE; "comfort" -> RouteOption.COMFORTABLE
        else -> null   // reroute = 같은 옵션으로 다시 탐색
    }

    private fun afterReroute() {
        val trip = hub.trip
        val before = beforeReroute ?: return
        beforeReroute = null
        if (before <= 0 || trip.dest == null) { hub.log("route|after|no-trip"); return }   // 경로가 없을 때(에뮬 시스템 재시작 뒤) 0분→0분을 보고하지 않는다
        val extra = JSONObject().put("beforeMin", ceil(before / 60).toInt()).put("afterMin", hub.mins())
        val prev = prevOption; val asked = optionOf(rerouteHow)
        if (prev != null && asked != null && prev != asked && trip.remainS >= before - 60) {
            hub.log("route|revert|$asked→$prev|${ceil(before / 60).toInt()}→${hub.mins()}분")
            hub.navi?.call("revert") { changeRouteOption(prev) }
            extra.put("reverted", true)
        }
        hub.log("route|after|$extra")
        if (hub.brainOn) hub.ask("event", "rerouted", extra)
        else hub.say(if (extra.optBoolean("reverted")) "바꿔 봤는데 시간이 안 줄어서 원래 길로 돌렸어요. 남은 시간은 ${ceil(before / 60).toInt()}분이에요."
                     else "경로를 다시 잡았어요. 남은 시간은 ${hub.mins()}분이에요.", "calm", false, 1, tag = "rerouted")
    }

    // ---------- 휴게소 ----------
    /** 휴게소가 3km 안 — 이번 길에서 한 번만 소개(두뇌가 검색으로 대표 메뉴·편의 + 취향 추천, 정차하면 사진 카드) */
    private fun restArea(name: String, aheadM: Int) {
        if (!restTold.add(name)) return
        hub.log("rest|$name|${aheadM}m")
        hub.fire("restarea", JSONObject().put("restName", name).put("aheadM", aheadM))
    }

    // ---------- 내비 콜백(main) ----------
    private val naviL = object : NaviHelperEventListener {
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            // 새 경로가 실제로 섰다 = 90초 조용한 시작부터 — 옛 경로의 늦은 정찰이 '첫 정찰'을 써 버려 새 긴 경로의 정체를 줄줄이 알렸다(11-07)
            scout.reset()
            hub.main.postDelayed({ hub.navi?.call("scan") { getRouteStateInfo() } }, 3000)   // 새 경로 = 바로 정찰
        }
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) {
            scout.reset()   // 경유지가 들어간 새 경로
            hub.main.postDelayed({ hub.navi?.call("scan") { getRouteStateInfo() } }, 3000)
        }
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            routeStateInfo.routeOption?.let { routeOption = it }
            report(scout.scan(routeStateInfo, hub.lat, hub.lng, hub.trip.totalM - hub.trip.remainM))
        }
        override fun onCautionPointInfo(cautionPoints: List<CautionPoint>) { report(scout.caution(cautionPoints)) }
        override fun onTBTInfo(tbtInfos: List<TBTInfo>) {
            // 거리는 '앞 안내에서부터'(구간)다 — 에뮬 실측 TURN_RIGHT@700;TURN_RIGHT@50 · 내비 화면 "80m → 390m"(11-07). 내 차 기준 누적으로
            var acc = 0.0
            val list = tbtInfos.map { acc += it.distance; it.type.name to acc }
            tbts = list; tbtAt = SystemClock.elapsedRealtime()
            tbtInfos.zip(list).firstOrNull { (t, d) -> t.type.name in REST_TURNS && d.second <= REST_AHEAD_M }
                ?.let { (t, d) -> restArea(t.description.ifBlank { "휴게소" }, d.second.toInt()) }
        }
        override fun onRouteOptionChanged(routeOption: RouteOption) { this@ScoutFeature.routeOption = routeOption; scout.resetEta() }
        override fun onDrivingInfo(drivingInfo: DrivingInfo) {
            drivingInfo.destination?.let { d -> scout.eta(d.duration.toDouble())?.let { report(listOf(it)) } }
        }
    }

    // ---------- 시연 · 맥락 · 화면 · 규칙 문장 ----------
    override fun onIntent(i: Intent, first: Boolean) {
        if (i.hasExtra("scoutsec")) { scoutSec = i.getIntExtra("scoutsec", scoutSec); scoutFixed = scoutSec > 0; if (!scoutFixed) scoutSec = 60 }   // 0 = 적응 주기로
        if (i.getBooleanExtra("scan", false)) hub.navi?.call("scan") { getRouteStateInfo() }
        i.getStringExtra("traffic")?.let { t ->   // "종류|앞 거리(m)|설명" — 에뮬 내비엔 실시간 사고가 없어서
            val p = t.split("|")
            hub.main.postDelayed({ report(listOf(scout.demo(p[0], p.getOrNull(1)?.toIntOrNull(), p.getOrNull(2) ?: ""))) }, if (first) 6000 else 0)
        }
        if (i.hasExtra("etaup")) hub.main.postDelayed({ report(listOf(scout.demoEta(i.getIntExtra("etaup", 8), hub.trip.remainS))) }, if (first) 6000 else 0)
        i.getStringExtra("restarea")?.split("|")?.let { p ->   // "기흥휴게소|2500" — 에뮬 GPS가 안 움직여 TBT로 오지 않을 때
            hub.main.postDelayed({ restTold.remove(p[0]); restArea(p[0], p.getOrNull(1)?.toIntOrNull() ?: 2000) }, if (first) 5000 else 0)
        }
    }

    override fun ctx(c: JSONObject) {
        if (hub.trip.dest == null) return
        scout.summary.takeIf { it.isNotEmpty() }?.let { c.put("traffic", it) }
        scout.ahead.takeIf { it.isNotEmpty() }?.let { c.put("trafficAhead", JSONArray(it)) }
        scout.roads.takeIf { it.isNotEmpty() }?.let { c.put("routeRoads", JSONArray(it)) }
    }

    override fun render(v: HyunView) {
        v.trafficLine = if (hub.trip.dest == null || scout.summary.isBlank()) "" else listOfNotNull("내비 교통 " + scout.summary, scout.ahead.firstOrNull()).joinToString(" · ")
    }

    override fun fallback(ev: String, extra: JSONObject?): String? = when (ev) {
        "restarea" -> extra?.let { "${Scout.km(it.optInt("aheadM")).replace("km", "킬로미터").replace("m", "미터")} 앞에 ${it.optString("restName")}가 있어요. 쉬어 갈까요?" }
        else -> null
    }

    companion object {
        private val OPTIONS = setOf("fast", "highway", "free", "comfort", "reroute")
        // 우회가 갈라질 수 없는 안내 — 직진·도착·경유지·터널·요금소·휴게소(들어가도 같은 길로 나온다)
        private val NOT_BRANCH = setOf("TURN_UNKNOWN", "TURN_STRAIGHT", "TURN_ARRIVE", "TURN_WAY", "TURN_TUNNEL", "TURN_TOLL", "TURN_ENTER_SERVICEAREA", "TURN_RESTAREA")
        private val REST_TURNS = setOf("TURN_ENTER_SERVICEAREA", "TURN_RESTAREA")
        private const val REST_AHEAD_M = 3000.0
    }
}
