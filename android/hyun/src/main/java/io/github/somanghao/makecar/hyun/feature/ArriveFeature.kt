package io.github.somanghao.makecar.hyun.feature

import android.content.Intent
import android.os.SystemClock
import io.github.somanghao.makecar.hyun.core.Feature
import io.github.somanghao.makecar.hyun.core.Fmt
import io.github.somanghao.makecar.hyun.core.Hyun
import io.github.somanghao.makecar.hyun.ui.HyunView
import org.json.JSONObject

/**
 * 도착 추천(차) — 식사 시간에 도착하는 길이면 곧 도착(near)에 한마디 + 주행 중 한 줄, 도착(arrived)하면 목적지 근처 식당 카드.
 * 판정·검색·카드는 서버 `features/places.py`(카카오 로컬 실시간) — 앱은 이벤트(기존 near·arrived)와 맥락만 보탠다.
 * 고르기는 두뇌 답 `nav` → RouteFeature.goTo 그대로. 명세 = dashboard/hyun/specs/arrive.md
 * 맥락: `destLat`·`destLng`(RouteFeature) · 현재 위치 `lat`·`lng`("근처 맛집" — 목적지가 없을 때) · 시연 시계 `clock`
 * 시연 인텐트: --es clock 11:50(앱 시각을 그 시각으로 — 서버의 식사 시간 판정용, "off"면 끔) · --es event near / arrived(코어) · --es arriveline "…"(화면 점검)
 */
class ArriveFeature : Feature {
    override val id = "arrive"
    override fun enabled(car: Boolean) = car
    private lateinit var hub: Hyun
    private var clockOffsetMs: Long? = null       // 시연 시계 — 진짜 시각과의 차이
    private var line = ""                         // 서버가 준 한 줄 "도착 12:10 점심 · 국밥 5곳 찾아 둠"
    private var lineAt = 0L

    override fun start(hub: Hyun) { this.hub = hub }

    override fun onIntent(i: Intent, first: Boolean) {
        i.getStringExtra("arriveline")?.let { line = it; lineAt = SystemClock.elapsedRealtime(); hub.render() }   // 화면 점검 — 서버 없이 한 줄
        i.getStringExtra("clock")?.let { c ->
            val m = Regex("""(\d{1,2}):(\d{2})""").find(c)
            if (m == null) { clockOffsetMs = null; hub.log("arrive|clock|off"); return@let }
            val cal = java.util.Calendar.getInstance()
            val now = cal.timeInMillis
            cal.set(java.util.Calendar.HOUR_OF_DAY, m.groupValues[1].toInt()); cal.set(java.util.Calendar.MINUTE, m.groupValues[2].toInt())
            clockOffsetMs = cal.timeInMillis - now
            hub.log("arrive|clock|${clock()}")
        }
    }

    private fun clock() = clockOffsetMs?.let { Fmt.hhmm(System.currentTimeMillis() + it) }

    override fun ctx(c: JSONObject) {
        // 시연 시계면 맥락의 지금(t)·도착(eta)도 그 시각으로 — 11-08 E2E: '점심(12:31 도착)'과 '4시 42분 도착'이 섞여 말함
        clockOffsetMs?.let { off ->
            val now = System.currentTimeMillis() + off
            c.put("clock", Fmt.hhmm(now)); c.put("t", Fmt.hhmm(now))
            if (c.has("eta")) c.put("eta", Fmt.hhmm(now + (hub.trip.remainS * 1000).toLong()))
        }
        if (!c.has("destLat")) hub.lat?.let { la -> hub.lng?.let { lo -> c.put("lat", Math.round(la * 1e4) / 1e4); c.put("lng", Math.round(lo * 1e4) / 1e4) } }
    }

    override fun onReply(kind: String, text: String, r: JSONObject) {
        val l = r.optString("arrive_line")
        if (l.isNotBlank() && l != "null") { line = l; lineAt = SystemClock.elapsedRealtime(); hub.log("arrive|line|$l") }
        if (kind == "event" && text == "arrived") line = ""
    }

    override fun onTripReset() { line = "" }

    override fun render(v: HyunView) {
        if (line.isNotBlank() && (hub.trip.dest == null || SystemClock.elapsedRealtime() - lineAt > LINE_MS)) line = ""
        if (line.isNotBlank()) v.lines += HyunView.Line("🍽", line)
    }

    companion object {
        private const val LINE_MS = 30 * 60_000L   // 한 줄은 도착하거나 30분이 지나면 내린다
    }
}
