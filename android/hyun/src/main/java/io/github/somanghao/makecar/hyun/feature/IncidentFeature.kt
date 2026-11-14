package io.github.somanghao.makecar.hyun.feature

import android.content.Intent
import android.os.SystemClock
import io.github.somanghao.makecar.hyun.core.Feature
import io.github.somanghao.makecar.hyun.core.Hyun
import io.github.somanghao.makecar.hyun.ui.HyunView
import io.github.somanghao.makecar.hyun.ui.Incident
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 사고 예고(차, 사용자 11-14) — 경로 안내 중 **내비 현재 위치 + 목적지**로 서버 `/hyun/incidents`(한국도로공사 실시간 돌발 ·
 * 서울 TOPIS · ITS 키가 있으면)를 주기적으로 물어, 내 경로 앞에 새로 잡힌 사고·고장·공사·통제를 먼저 말한다.
 * 가짜 금지 — 서버가 출처에서 받은 값만. 서버가 없거나 앞에 없으면 아무 말도 안 하고 화면도 비운다.
 *
 * - 말: 사고·장애물급(sev 3) = P0 안전, 고장·공사·통제(sev 2) = P1 · 같은 돌발은 한 번, 2km 안으로 다가오면 한 번 더 · 목록에서 빠지면 지운다
 * - 내비 정찰(Scout)의 앞쪽 정체 문장에 같은 도로가 있으면 "내비에도 그 근처 정체가 잡혀 있어요"를 붙여 한 번에
 * - 화면: 현비서 화면 = 오른쪽 위 카드(StageUi.incidentTile) · 반반 좁은 칸 = 빨간 한 줄 · 내비 전체 화면(가려짐) = 헤드업(Hyun.coveredTalk, tag "incident")
 * - 주기: 경로 정찰과 같은 생각 — 달리는 중 60초 · 정차 120초 · 새 경로 8초 뒤 바로
 * 시험: --ez incidentscan true(바로 묻기) · --ei incidentsec 30(주기 고정, 0 = 기본)
 */
class IncidentFeature : Feature {
    override val id = "incident"
    override fun enabled(car: Boolean) = car
    private lateinit var hub: Hyun

    data class Item(val id: String, val kind: String, val road: String, val dir: String, val place: String, val lanes: List<Int>,
                    val aheadKm: Double, val sev: Int, val src: String, val at: String, val status: String)

    private var items = listOf<Item>()             // 지금 내 경로 앞 돌발(가까운 순)
    private val told = mutableSetOf<String>()      // 이번 길에서 말한 돌발 id
    private val toldNear = mutableSetOf<String>()  // 2km 안 다시 알린 id
    private var lastDest: String? = null
    private var asking = false
    private var fixedSec = 0
    private var lastAsk = 0L

    override fun start(hub: Hyun) {
        this.hub = hub
        hub.main.postDelayed(loop, 10_000)
    }

    override fun onTripReset() { reset("길 끝") }

    private fun reset(why: String) {
        if (items.isNotEmpty() || told.isNotEmpty()) hub.log("incident|reset|$why")
        items = emptyList(); told.clear(); toldNear.clear()
    }

    private val loop = object : Runnable {
        override fun run() {
            val dest = hub.trip.dest
            if (dest != lastDest) { lastDest = dest; reset("새 목적지"); if (dest != null) { hub.main.postDelayed({ ask("새 경로") }, 8000) } }
            val every = if (fixedSec > 0) fixedSec else if (hub.speedMs < 1.4f) 120 else 60
            if (dest != null && SystemClock.elapsedRealtime() - lastAsk >= every * 1000L) ask("주기 ${every}s")
            hub.main.postDelayed(this, 5000)
        }
    }

    private fun ask(why: String) {
        if (asking) return
        val la = hub.lat; val lo = hub.lng
        val r = hub.feature<RouteFeature>()
        val dla = r?.destLat; val dlo = r?.destLng
        if (hub.trip.dest == null || la == null || lo == null || dla == null || dlo == null) { hub.log("incident|skip|위치·목적지 좌표 없음"); return }
        asking = true; lastAsk = SystemClock.elapsedRealtime()
        val roads = hub.feature<ScoutFeature>()?.routeRoads.orEmpty()   // 내비 경로 도로 — 있으면 같은 이름 도로 돌발만(직선 회랑의 남의 길 걸러 내기)
        hub.link.incidents(la, lo, dla, dlo, roads) { j -> hub.main.post { asking = false; got(j, "$why|도로 ${roads.size}") } }
    }

    private fun got(j: JSONObject?, why: String) {
        if (j == null) { hub.log("incident|server|없음 — 사고 예고 쉼"); return }   // 서버 없음 = 말하지 않는다(가짜 금지)
        val arr = j.optJSONArray("items") ?: JSONArray()
        val list = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optInt("sev") >= 2 && it.optDouble("ahead_km") >= 0.2 }.map { o ->
            Item(o.optString("id"), o.optString("kind"), o.optString("road"), o.optString("dir"), cleanPlace(o.optString("place")),
                (0 until (o.optJSONArray("lanes")?.length() ?: 0)).map { o.getJSONArray("lanes").getInt(it) },
                o.optDouble("ahead_km"), o.optInt("sev"), o.optString("src_label", o.optString("src")), o.optString("at_label"), o.optString("status"))
        }
        val gone = items.map { it.id }.toSet() - list.map { it.id }.toSet()
        if (gone.isNotEmpty()) hub.log("incident|gone|${gone.size}")
        items = list
        val srcs = j.optJSONObject("sources")?.let { s -> s.keys().asSequence().joinToString(",") { k -> "$k=${s.optJSONObject(k)?.optBoolean("ok")}" } } ?: ""
        hub.log("incident|scan|$why|${list.size}건|직선 ${j.optDouble("route_km")}km|$srcs" + (list.firstOrNull()?.let { "|${it.aheadKm}km ${it.road} ${it.place} ${it.kind}" } ?: ""))
        hub.render()
        val first = list.firstOrNull() ?: return
        when {
            first.id !in told -> { told += first.id; announce(first, near = false) }
            first.aheadKm <= NEAR_KM && first.id !in toldNear -> { toldNear += first.id; announce(first, near = true) }
        }
    }

    /** 말 — 출처 값만으로("약 11킬로미터 앞 영동선 인천방향 월곶분기점 3차로에 사고가 났어요. 미리 다른 차로로 가요.") */
    private fun announce(it: Item, near: Boolean) {
        val where = listOf(it.road, it.dir, it.place).filter { s -> s.isNotBlank() }.joinToString(" ").ifBlank { "앞길" }
        val lane = if (it.lanes.isNotEmpty()) "${it.lanes.joinToString(", ")}차로에 " else ""
        val what = when (it.kind) {
            "사고" -> "사고가 났어요"; "고장" -> "고장 차가 서 있어요"; "장애물", "낙하물" -> "${it.kind}이 있어요"
            "작업", "공사" -> "공사 중이에요"; "통제" -> "통제 중이에요"; "화재" -> "화재가 났어요"; "역주행" -> "역주행 차가 있어요"
            else -> "${it.kind} 정보가 있어요"
        }
        val tail = if (it.lanes.isNotEmpty() && it.kind in LANE_KINDS) " 미리 다른 차로로 가요." else " 속도 줄이고 조심해서 가요."
        val jam = hub.feature<ScoutFeature>()?.aheadJams?.firstOrNull { j -> it.road.isNotBlank() && j.contains(it.road.removeSuffix("선").take(2)) }
        val jamTail = if (jam != null) " 내비에도 그 근처 정체가 잡혀 있어요." else ""
        val text = if (near) "곧 ${dist(it.aheadKm)} 앞이에요. ${it.place.ifBlank { where }} ${lane}${it.kind} 지점이니${tail}"
                   else "${dist(it.aheadKm)} 앞 $where ${lane}${what}.$tail$jamTail"
        hub.log("incident|say|${if (near) "near" else "new"}|${it.id}|$text")
        hub.say(text.replace("  ", " "), "alert", false, if (it.sev >= 3) 0 else 1, tag = "incident")
    }

    /** 도로공사 지점 이름의 이정 표시를 뺀다 — "동탄분기점(382K)" → "동탄분기점" · "[공항] 30.8Km - 29.2Km" → "" */
    private fun cleanPlace(s: String) = s.replace(Regex("\\(.*?\\)|\\[.*?\\]"), "").replace(Regex("[\\d.]+\\s*[Kk]m\\s*-\\s*[\\d.]+\\s*[Kk]m"), "").replace(Regex("\\s+"), " ").trim()

    private fun dist(km: Double): String = when {
        km < 1.0 -> "${((km * 1000) / 100).roundToInt() * 100}미터"
        km < 3.0 -> "약 ${"%.1f".format(km).removeSuffix(".0")}킬로미터"
        else -> "약 ${km.roundToInt()}킬로미터"
    }

    override fun render(v: HyunView) {
        val it = items.firstOrNull()
        v.incident = if (it == null || hub.trip.dest == null) null else {
            val where = listOf(it.road, it.dir, it.place).filter { s -> s.isNotBlank() }.joinToString(" ")
            val lane = if (it.lanes.isNotEmpty()) " ${it.lanes.joinToString(",")}차로" else ""
            val km = if (it.aheadKm < 1) "${((it.aheadKm * 1000) / 100).roundToInt() * 100}m" else "${"%.1f".format(it.aheadKm).removeSuffix(".0")}km"
            Incident(head = "${if (it.kind == "사고") "사고" else it.kind} 예고 · 약 $km 앞", body = "$where$lane ${it.kind}".trim(),
                foot = listOf(it.src, it.at, it.status).filter { s -> s.isNotBlank() }.joinToString(" · "),
                line = "$km 앞 ${it.place.ifBlank { it.road }}$lane ${it.kind}".trim(), sev = it.sev)
        }
    }

    override fun ctx(c: JSONObject) {
        if (items.isEmpty() || hub.trip.dest == null) return
        c.put("incidentsAhead", JSONArray(items.take(3).map { "${it.aheadKm}km 앞 ${it.road} ${it.dir} ${it.place} ${it.lanes.joinToString(",")}차로 ${it.kind} (${it.src} ${it.at}, ${it.status})" }))
    }

    override fun onIntent(i: Intent, first: Boolean) {
        if (i.hasExtra("incidentsec")) fixedSec = i.getIntExtra("incidentsec", 0)
        if (i.getBooleanExtra("incidentscan", false)) hub.main.postDelayed({ lastAsk = 0L; ask("요청") }, if (first) 6000 else 0)
    }

    companion object {
        private const val NEAR_KM = 2.0
        private val LANE_KINDS = setOf("사고", "고장", "장애물", "낙하물", "작업", "공사")
    }
}
