package io.github.somanghao.makecar.hyun

import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.TrafficCongestionType
import ai.pleos.playground.navi.constants.TrafficEventType
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.RouteStateInfo
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 경로 정찰 — 내비(NaviHelper)가 주는 "내 경로의" 교통 상황을 주기적으로 읽어 새로 생긴 위험만 골라낸다.
 * 정보원: RouteStateInfo.trafficStatus(사고·재난·도로 파손·공사·행사 이벤트 + 정체 구간 + 요약) · 주의 구간(CautionPoint) · 도착 예정 변화.
 * 판정(무엇이 새롭고 얼마나 급한가)은 규칙이 하고, 말과 우회 제안은 두뇌가 한다. 가까운 사고·역주행은 앱이 먼저 경고한다.
 */
class Scout {
    /** severity 3 = 사고·재난·역주행(가까우면 앱이 즉시 경고) · 2 = 두뇌가 알리고 우회 제안 · 1 = 맥락 요약에만 */
    data class Finding(val key: String, val kind: String, val severity: Int, val aheadM: Int?, val text: String, val src: String = "navi") {
        fun json(): JSONObject = JSONObject().put("kind", kind).put("sev", severity).put("aheadM", aheadM ?: JSONObject.NULL).put("text", text).put("src", src)
    }

    private val told = mutableSetOf<String>()   // 이번 목적지에서 이미 넘긴 것
    private var scanned = false                  // 이 경로를 한 번이라도 정찰했나(글레오 영어 요약용)
    // 새 경로가 선 뒤 90초 = 조용한 시작 — 사고·재난(3)만 바로, 나머지는 '본 것'으로만 적고 요약(출발 한마디)에.
    // '첫 정찰 한 번'으로 셌더니 옛 경로의 늦은 정찰 답이 그 한 번을 써 버려 새 긴 경로의 정체를 줄줄이 알렸다(11-07 대전역 3번 실측)
    private var calmUntil = 0L
    var summary = ""                             // 지금 경로 교통 한 줄 — 매 맥락에 실어 "앞길 어때?"에 답하게 한다
    var ahead = listOf<String>()                 // 앞쪽 정체·사고 구간(가까운 순 3개, 도로명·거리·평균 속도)
        private set
    var roads = listOf<String>()                 // 경로 도로 이름(경로 순서, 겹침 없이 8개) — 정체 구간 목록이 경로 전체를 덮어 '어느 고속도로로 가나'가 보인다(휴게소 찾기)
        private set
    var nearestM: Int? = null                    // 가장 가까운 알릴 만한 위험(정체·사고·공사)까지 m — 적응형 정찰 주기에 쓴다
        private set
    // 글레오(영어 message)에 넘길 숫자 요약 — 정체 총 m·분, 돌발 이벤트 수, 가장 가까운 것(종류·m·도로명)
    private var jamM = 0
    private var jamMin = 0
    private var eventN = 0
    private var near: Finding? = null
    private var lastRawS = -1.0                  // 내비가 준 남은 시간(직전 값) — 값이 바뀔 때만 도착 예정을 비교(에뮬은 고정값)
    private var baseEtaAt = 0L

    fun reset() { calmUntil = System.currentTimeMillis() + CALM_MS; told.clear(); scanned = false; summary = ""; ahead = emptyList(); roads = emptyList(); nearestM = null; jamM = 0; jamMin = 0; eventN = 0; near = null; lastRawS = -1.0; baseEtaAt = 0L }

    /** 글레오 HYUN_STATUS용 영어 한두 문장(고유명은 한국어 그대로) */
    fun english(): String {
        if (!scanned) return "Route traffic has not been checked yet."
        val base = if (jamM <= 0 && eventN == 0) "The navigation reports no congestion or incidents on the route."
        else listOfNotNull(
            jamM.takeIf { it > 0 }?.let { "${km(it)} of congestion, about $jamMin minutes of delay" },
            eventN.takeIf { it > 0 }?.let { "$it traffic incident${if (it > 1) "s" else ""}" },
        ).joinToString(" and ", prefix = "The navigation reports ", postfix = " on the route.")
        val n = near ?: return base
        val kind = KIND_EN[n.kind] ?: "a traffic issue"
        return base + " The nearest is $kind" + (n.aheadM?.let { " in ${km(it)}" } ?: "") + "."
    }

    /** 경로 교통 — 새로 생긴 이벤트·심한 정체만 돌려준다(요약은 매번 갱신). [traveledM] = 출발 뒤 달린 거리(정체 offset은 경로 시작 기준) */
    fun scan(state: RouteStateInfo, lat: Double?, lng: Double?, traveledM: Double): List<Finding> {
        val ts = state.trafficStatus ?: return emptyList()
        // 안내 중인 경로만 — 목적지를 바꾸면 옛 경로가 끊긴 상태(SAFE_GUIDING)가 먼저 와서 '첫 정찰'을 써 버리고,
        // 새 경로의 첫 정찰이 두 번째로 취급돼 긴 경로의 정체를 줄줄이 알렸다(11-07 대전역 실측)
        if (state.naviMode?.name != "ROUTE_GUIDING") return emptyList()
        val found = mutableListOf<Finding>()
        val events = ts.trafficEventList.orEmpty()
        for (e in events) {
            val (kind, sev) = eventKind(e.eventType)
            val ahead = if (lat != null && lng != null) Trip.meters(lat, lng, e.latitude, e.longitude).roundToInt() else null
            val text = listOfNotNull(kind, ahead?.let { "약 ${km(it)}" }, e.description.takeIf { it.isNotBlank() },
                e.endTime.takeIf { it.isNotBlank() }?.let { "해제 예정 $it" }).joinToString(" · ")
            found += Finding("ev:${e.eventType}@${"%.4f,%.4f".format(e.latitude, e.longitude)}", kind, sev, ahead, text)
        }
        val jams = ts.congestionItemList.orEmpty().filter { it.trafficCongestionType == TrafficCongestionType.HEAVY || it.trafficCongestionType == TrafficCongestionType.SLOW }
        for (j in jams) {
            val ahead = (j.offset - traveledM).roundToInt()
            if (ahead + j.distance < 0) continue   // 이미 지나온 구간
            val heavy = j.trafficCongestionType == TrafficCongestionType.HEAVY
            // 따로 알릴 정체 = 심한 정체이면서 500m 이상 또는 3분 이상. 짧은 건 요약에만(80m·1분 정체를 하나하나 말하면 소음 — 에뮬 실측)
            val big = heavy && (j.distance >= 500 || j.trafficCongestionTime >= 180)
            val place = listOf(j.roadName, listOf(j.startCrossName, j.endCrossName).filter { it.isNotBlank() }.joinToString("→")).filter { it.isNotBlank() }.joinToString(" ")
            val text = "${if (heavy) "정체" else "서행"} · ${place.ifBlank { "경로 위" }} ${km(j.distance.roundToInt())} · 평균 ${j.avrSpeed.roundToInt()}km/h" +
                (if (j.trafficCongestionTime > 0) " · 약 ${(j.trafficCongestionTime / 60.0).roundToInt()}분" else "")
            found += Finding("jam:${j.roadName}@${j.startCrossName}", if (heavy) "정체" else "서행", if (big) 2 else 1, ahead.coerceAtLeast(0), text)
        }
        roads = ts.congestionItemList.orEmpty().sortedBy { it.offset }.map { it.roadName }.filter { it.isNotBlank() }.distinct().take(8)
        val s = ts.summary
        val counts = found.filter { it.key.startsWith("ev:") }.groupingBy { it.kind }.eachCount().entries.joinToString(" · ") { "${it.key} ${it.value}" }
        val jam = if (s != null && s.totalCongestionDist > 0) "정체 ${km(s.totalCongestionDist.roundToInt())}(약 ${(s.totalCongestionTime / 60.0).roundToInt()}분, 평균 ${s.totalCongestionAvrSpeed.roundToInt()}km/h)" else ""
        summary = listOf(counts, jam).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "내비 교통정보상 막힘·사고 없음" }
        // 앞쪽 정체·사고 3개(가까운 순) — "교통 어때?"에 어느 도로가 막히는지까지 답하게 두뇌 맥락에 싣는다
        val sorted = found.filter { (it.aheadM ?: 0) >= 0 }.sortedBy { it.aheadM ?: Int.MAX_VALUE }
        ahead = sorted.take(6).map { f -> (f.aheadM?.let { "약 ${km(it)} 앞 " } ?: "") + f.text }
        near = sorted.firstOrNull { it.severity >= 2 } ?: sorted.firstOrNull()
        nearestM = sorted.firstOrNull { it.severity >= 2 }?.aheadM
        jamM = s?.totalCongestionDist?.roundToInt() ?: 0
        jamMin = s?.let { (it.totalCongestionTime / 60.0).roundToInt() } ?: 0
        eventN = events.size
        val fresh = found.filter { told.add(it.key) }
        scanned = true
        return if (System.currentTimeMillis() < calmUntil) fresh.filter { it.severity >= 3 } else fresh
    }

    /** 주의 구간 — 1.5km 안에 들어온 위험 구간만(같은 지점은 한 번) */
    fun caution(points: List<CautionPoint>): List<Finding> = points.mapNotNull { c ->
        val (kind, sev) = cautionKind(c.type) ?: return@mapNotNull null
        if (c.remainDistance > 1500) return@mapNotNull null
        Finding("cp:${c.id}", kind, sev, c.remainDistance, "$kind · ${km(c.remainDistance)} 앞")
    }.filter { told.add(it.key) }

    /** 경로 자체가 바뀌었다(경유지 추가·우회·새 경로) — 길어진 만큼을 '지연'으로 보지 않게 도착 예정 기준을 새로 잡는다(11-07 실측: 경유지 넣자 "+33분 지연") */
    fun resetEta() { lastRawS = -1.0; baseEtaAt = 0L }

    /** 내비가 준 남은 시간이 바뀔 때만 도착 예정을 비교 — 처음보다 5분 이상 늦어지면 한 번 알리고 기준을 옮긴다 */
    fun eta(rawRemainS: Double): Finding? {
        if (rawRemainS <= 0 || rawRemainS == lastRawS) return null
        lastRawS = rawRemainS
        val etaAt = System.currentTimeMillis() + (rawRemainS * 1000).toLong()
        if (baseEtaAt == 0L || etaAt < baseEtaAt) { baseEtaAt = etaAt; return null }
        val late = ((etaAt - baseEtaAt) / 60_000).toInt()
        if (late < 5) return null
        val f = Finding("eta:$etaAt", "도착 지연", 2, null, "도착 예정 ${hhmm(baseEtaAt)} → ${hhmm(etaAt)} (+${late}분)")
        baseEtaAt = etaAt
        return f
    }

    /** 시연 주입 — 에뮬 내비엔 실시간 교통이 없어서. src=demo로 표시해 두뇌·로그가 구분한다 */
    fun demo(kindCode: String, aheadM: Int?, desc: String): Finding {
        val kind = when (kindCode) { "accident" -> "사고"; "disaster" -> "재난"; "damage" -> "도로 파손"; "repair" -> "공사"; "jam" -> "정체"; "wrongway" -> "역주행"; else -> kindCode }
        val sev = when (kindCode) { "accident", "disaster", "wrongway" -> 3; "damage", "jam" -> 2; else -> 1 }
        val text = listOfNotNull(kind, aheadM?.let { "약 ${km(it)}" }, desc.takeIf { it.isNotBlank() }).joinToString(" · ")
        return Finding("demo:${System.nanoTime()}", kind, sev, aheadM, text, "demo").also { told.add(it.key) }
    }

    fun demoEta(late: Int, remainS: Double): Finding {
        val now = System.currentTimeMillis() + (remainS * 1000).toLong()
        return Finding("demo:${System.nanoTime()}", "도착 지연", 2, null, "도착 예정 ${hhmm(now)} → ${hhmm(now + late * 60_000L)} (+${late}분)", "demo")
    }

    private fun eventKind(t: TrafficEventType) = when (t) {
        TrafficEventType.ACCIDENT -> "사고" to 3
        TrafficEventType.DISASTER -> "재난" to 3
        TrafficEventType.ROAD_DAMAGE -> "도로 파손" to 2
        TrafficEventType.REPAIRING -> "공사" to 2
        TrafficEventType.EVENT -> "행사·통제" to 1
        else -> "기타 돌발" to 1
    }

    private fun cautionKind(t: CautionPointType) = when (t) {
        CautionPointType.WRONG_WAY_DRIVING_ALERT -> "역주행 차량" to 3
        CautionPointType.SLIPPERY_ROAD_ALERT -> "미끄러운 길" to 2
        CautionPointType.ROAD_DAMAGE_ALERT -> "도로 파손" to 2
        CautionPointType.ACCIDENT_PRONE_AREA -> "사고 잦은 곳" to 2
        CautionPointType.FOG_AREA -> "안개 잦은 곳" to 1
        CautionPointType.FALLING_ROCK_AREA -> "낙석 주의" to 1
        CautionPointType.DOWNHILL_CAUTION_AREA -> "급경사 내리막" to 1
        CautionPointType.HIGHWAY_CONSTRUCTION -> "고속도로 공사" to 2
        else -> null   // 과속방지턱·단속 카메라 등은 내비가 이미 말한다
    }

    companion object {
        private const val CALM_MS = 90_000L
        private val KIND_EN = mapOf("사고" to "an accident", "재난" to "a disaster area", "도로 파손" to "road damage", "공사" to "road work",
            "행사·통제" to "a road closure for an event", "정체" to "heavy congestion", "서행" to "slow traffic", "역주행 차량" to "a wrong-way driver",
            "도착 지연" to "a delay in arrival time")
        fun km(m: Int) = if (m < 1000) "${(m / 10.0).roundToInt() * 10}m" else "${(m / 100.0).roundToInt() / 10.0}km"
        private fun hhmm(ms: Long) = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(ms))
    }
}
