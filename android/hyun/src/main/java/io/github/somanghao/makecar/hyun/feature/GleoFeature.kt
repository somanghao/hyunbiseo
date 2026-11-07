package io.github.somanghao.makecar.hyun.feature

import android.content.Intent
import io.github.somanghao.makecar.hyun.core.Feature
import io.github.somanghao.makecar.hyun.core.Fmt
import io.github.somanghao.makecar.hyun.core.Hyun
import org.json.JSONObject

/**
 * Gleo Interface(assets/schema.json · 매니페스트 intent-filter) — 글레오가 부르면 7초 안에 APP_ACTION_RESPONSE(영어 message).
 *   ASK_HYUN {question} → 글레오엔 바로 success·beep, 답은 현비서 목소리로(두뇌 ~5초+가 7초 제한에 빠듯)
 *   HYUN_STATUS {item: all|trip|traffic} → 목적지·남은 시간·도착 + 경로 교통 영어 한두 문장
 *   HYUN_QUIET {mode: quiet|talk} → 조용히 10분 / 해제
 * 흉내: am start --user 10 -a ASK_HYUN --es ACTION_PARAM '{"question":"앞길 어때?"}' · 글레오는 자기가 부른 액션의 응답만 받는다(먼저 말하기 불가)
 */
class GleoFeature : Feature {
    override val id = "gleo"
    override fun enabled(car: Boolean) = car
    private lateinit var hub: Hyun
    override fun start(hub: Hyun) { this.hub = hub }

    override fun onIntent(i: Intent, first: Boolean) {
        if (i.action in ACTIONS) hub.main.postDelayed({ onGleo(i) }, if (first) 3000 else 0)
    }

    private fun onGleo(i: Intent) {
        val fn = i.action ?: return
        val p = params(i)
        hub.log("gleo|action|$fn|$p")
        when (fn) {
            "ASK_HYUN" -> {
                val q = p.optString("question").trim()
                if (q.isEmpty()) { reply(fn, "asking", "What would you like to ask Hyun?", "voice"); return }
                reply(fn, "success", "Hyun is answering this in its own voice.", "beep")
                hub.view.userLine = "글레오 → $q"
                hub.heard(q)
            }
            "HYUN_STATUS" -> {
                val item = p.optString("item", "all")
                val trip = hub.trip; val d = trip.dest
                val scout = hub.feature<ScoutFeature>()
                val tripEn = if (d == null) "There is no destination yet. You can tell Hyun where to go."
                    else "Heading to $d: ${Fmt.km(trip.remainM)} km and about ${hub.mins()} minutes left, arriving at ${Fmt.hhmm(System.currentTimeMillis() + (trip.remainS * 1000).toLong())}." +
                        (hub.feature<RouteFeature>()?.vias?.firstOrNull()?.let { " Stop on the way: ${it.name}." } ?: "")
                val traffic = scout?.english() ?: ""
                val msg = when (item) { "trip" -> tripEn; "traffic" -> if (d == null) tripEn else traffic; else -> if (d == null) tripEn else "$tripEn $traffic".trim() }
                reply(fn, "success", msg, "voice")
                hub.view.userLine = "글레오 → 현비서 상황($item)"; hub.render()
            }
            "HYUN_QUIET" -> {
                val on = p.optString("mode", "quiet") != "talk"
                hub.setQuiet(on)
                reply(fn, "success", if (on) "Hyun will stay quiet for 10 minutes, except safety alerts and answers." else "Hyun can talk again.", "voice")
                hub.view.userLine = "글레오 → ${if (on) "조용히 모드" else "말동무 모드"}"; hub.render()
            }
        }
    }

    /** 문서 예시는 JSON을 따옴표로 한 번 더 감싸 보낸다("\"{...}\"") · 파라미터가 개별 extra로 올 수도 있다(에이전트 앱 실측) */
    private fun params(i: Intent): JSONObject {
        val raw = i.getStringExtra("ACTION_PARAM")?.trim()
        val j = runCatching {
            val t = raw!!.let { if (it.length > 1 && it.startsWith("\"") && it.endsWith("\"")) it.substring(1, it.length - 1).replace("\\\"", "\"") else it }
            JSONObject(t)
        }.getOrDefault(JSONObject())
        for (k in listOf("question", "item", "mode")) i.getStringExtra(k)?.let { if (!j.has(k)) j.put(k, it) }
        return j
    }

    private fun reply(fn: String, status: String, message: String, mode: String) {
        val res = JSONObject().put("functionName", fn).put("status", status).put("message", message).put("mode", mode)
        runCatching { hub.act.sendBroadcast(Intent(RESPONSE).putExtra("packageName", hub.act.packageName).putExtra("response", res.toString())) }
            .onFailure { hub.log("gleo|response|FAIL|${it.message}") }
        hub.log("gleo|response|$res")
    }

    companion object {
        private val ACTIONS = setOf("ASK_HYUN", "HYUN_STATUS", "HYUN_QUIET")
        private const val RESPONSE = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
    }
}
