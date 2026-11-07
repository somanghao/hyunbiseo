package io.github.somanghao.makecar.hyun.feature

import android.content.Intent
import io.github.somanghao.makecar.hyun.core.Feature
import io.github.somanghao.makecar.hyun.core.Fmt
import io.github.somanghao.makecar.hyun.core.Hyun
import io.github.somanghao.makecar.hyun.drive.Trip
import io.github.somanghao.makecar.hyun.phone.Desk
import io.github.somanghao.makecar.hyun.phone.PhoneMode
import io.github.somanghao.makecar.hyun.phone.QuickActivity
import io.github.somanghao.makecar.hyun.phone.QuickWidget
import io.github.somanghao.makecar.hyun.ui.HyunView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * 짭비서(폰) — 운전이 아닌 때: 모드(대중교통 · 산책 · 업무중)가 말하기 정책·출력을 바꾼다(대중교통 + 이어폰 없음 = 자막·진동만).
 * 30초마다 이어폰 · 배터리 · 오늘 일정(10분 전 = P0) · 서버의 리마인더·할 일(울리면 P0) · 산책 거리 · 목적지 800m 전 하차 알림.
 * 두뇌 답 필드: mode · (remind·todo·done은 서버가 tasks에 넣고, 앱은 다시 받아 예약)
 * 시연 인텐트: --es mode transit|walk|work · --es remind "1|빨래 꺼내기"(서버 없이 1분 뒤)
 */
class PhoneFeature : Feature {
    override val id = "phone"
    override fun enabled(car: Boolean) = !car
    private lateinit var hub: Hyun
    private lateinit var desk: Desk
    var mode = PhoneMode.WORK
        private set
    private var headset = true
    private var events = listOf<Desk.Event>()
    private val warnedEvents = mutableSetOf<Long>()   // 10분 전 알림을 한 일정
    private var reminders = listOf<Triple<Int, Long, String>>()   // id · 울릴 시각(ms) · 내용 (끝나지 않은 것)
    private val armed = mutableMapOf<Int, Runnable>()
    private var todos = listOf<String>()
    private var batteryLow = false
    private var phoneSoc: Int? = null
    private var walkM = 0.0
    private var walkSince = 0L
    private var lastFix: Pair<Double, Double>? = null

    override fun start(hub: Hyun) {
        this.hub = hub
        desk = Desk(hub.act)
        mode = PhoneMode.of(hub.prefs.getString("mode", null)) ?: PhoneMode.WORK
        hub.view.modes = PhoneMode.entries.map { it.label }; hub.view.modeIdx = mode.ordinal
        hub.view.onMode = { i -> setMode(PhoneMode.entries[i], byUser = true) }
        hub.buzz = { p0 -> desk.buzz(p0) }
        applyMode()
        hub.main.post(deskTick)
        refreshTasks()
        QuickActivity.postNotification(hub.act)   // 알림창에서 말하기·글로 묻기
        QuickWidget.refresh(hub.act)
    }

    override fun onPermissions() {
        hub.main.removeCallbacks(deskTick); hub.main.post(deskTick)   // 캘린더 권한이 막 생겼으면 바로 일정 읽기
        QuickActivity.postNotification(hub.act)
    }

    /** 말하기 정책·자막 모드를 코어에 — 모드·이어폰이 바뀔 때마다 */
    private fun applyMode() {
        hub.policyOverride = mode.policy
        hub.silent = mode == PhoneMode.TRANSIT && !headset
    }

    private fun setMode(m: PhoneMode, byUser: Boolean) {
        if (m == mode && !byUser) return
        val changed = m != mode
        mode = m
        hub.prefs.edit().putString("mode", m.key).apply()
        if (m == PhoneMode.WALK && changed) { walkM = 0.0; walkSince = System.currentTimeMillis(); lastFix = null }
        hub.log("mode|${m.key}|byUser=$byUser")
        headset = desk.headset()
        applyMode()
        hub.render()
        if (byUser && changed) hub.fire("mode")   // 두뇌가 그 모드에 맞는 한마디(일정·우산·하차 등) — P2
    }

    /** 30초마다: 이어폰 · 배터리 · 오늘 일정(회의 10분 전 = P0) */
    private val deskTick = object : Runnable {
        override fun run() {
            headset = desk.headset(); applyMode()
            val (lv, charging) = desk.battery()
            phoneSoc = lv
            if (lv != null && lv <= 20 && !charging && !batteryLow) { batteryLow = true; hub.fire("battery_low") }
            if (lv != null && (lv > 30 || charging)) batteryLow = false
            events = desk.upcoming()
            for (e in events) {
                if (e.allDay || e.id in warnedEvents) continue
                val m = Desk.minutesUntil(e.start)
                if (m in 1..10) {
                    warnedEvents += e.id
                    hub.say("${m}분 뒤 ${e.title}" + (e.place?.takeIf { it.isNotBlank() }?.let { ", ${it}에서" } ?: "") + "예요.", "alert", false, 0, tag = "일정 10분 전")
                }
            }
            hub.render()
            hub.main.postDelayed(this, 30_000)
        }
    }

    /** 서버의 리마인더·할 일을 받아, 아직 안 건 리마인더를 예약한다(앱이 켜져 있는 동안 — 울리면 P0) */
    private fun refreshTasks() = hub.link.tasks { t ->
        t ?: return@tasks
        val now = System.currentTimeMillis()
        val rs = t.optJSONArray("reminders")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
        val open = rs.filter { !it.optBoolean("done") }.map { Triple(it.getInt("id"), it.getLong("at"), it.getString("text")) }
        val td = (t.optJSONArray("todos")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList())
            .filter { !it.optBoolean("done") }.map { it.getString("text") }
        hub.main.post {
            reminders = open.sortedBy { it.second }; todos = td
            for ((id, at, text) in open) {
                if (id in armed) continue
                if (at < now - 10 * 60_000L) { hub.link.fired(id); continue }   // 10분 넘게 지난 건 조용히 끝냄
                val r = Runnable { ring(id, text) }
                armed[id] = r
                hub.main.postDelayed(r, (at - now).coerceAtLeast(0))
                hub.log("remind|arm|$id|${Fmt.hhmm(at)}|$text")
            }
            hub.render()
        }
    }

    private fun ring(id: Int, text: String) {
        hub.log("remind|ring|$id|$text")
        armed.remove(id)
        reminders = reminders.filter { it.first != id }
        hub.link.fired(id)
        hub.say("알림이에요. $text, 지금이에요.", "alert", false, 0, tag = "리마인더")
    }

    /** 폰 목적지 — 800m 전 대중교통이면 하차 알림(P0), 아니면 곧 도착 · 150m면 도착 */
    override fun tick() {
        val trip = hub.trip; val f = trip.fired
        if (trip.dest == null || hub.lat == null) return
        when {
            "near" !in f && trip.totalM >= 1200 && trip.remainM <= 800 -> {
                f += "near"
                val d = trip.dest
                if (mode == PhoneMode.TRANSIT) hub.say("곧 ${d}예요. 내릴 준비 하세요.", "alert", false, 0, tag = "하차")
                else if (hub.brainOn) hub.ask("event", "near") else hub.say("곧 ${d}에 도착해요.", "", false, 1, tag = "near·규칙")
            }
            "arrived" !in f && trip.remainM <= 150 -> hub.fire("arrived")
        }
    }

    /** 산책 거리 — 3m 이하 흔들림·200m 넘는 튐은 버린다 */
    override fun onLocation(lat: Double, lng: Double, addr: String?, first: Boolean) {
        if (mode != PhoneMode.WALK) return
        lastFix?.let { (a, b) -> Trip.meters(a, b, lat, lng).takeIf { it in 3.0..200.0 }?.let { walkM += it } }
        lastFix = lat to lng
    }

    override fun onReply(kind: String, text: String, r: JSONObject) {
        r.optString("mode").takeIf { it.isNotBlank() && it != "null" }?.let { m -> PhoneMode.of(m)?.let { setMode(it, byUser = false) } }
        refreshTasks()
    }

    override fun onIntent(i: Intent, first: Boolean) {
        i.getStringExtra("mode")?.let { m -> PhoneMode.of(m)?.let { hub.main.postDelayed({ setMode(it, byUser = true) }, if (first) 4000 else 0) } }
        i.getStringExtra("remind")?.let { r ->   // "분|내용" — 서버 없이 리마인더 시험
            hub.main.postDelayed({ hub.say("알림이에요. ${r.substringAfter("|")}, 지금이에요.", "alert", false, 0, tag = "remind·demo") },
                ((r.substringBefore("|").toDoubleOrNull() ?: 0.0) * 60_000).toLong())
        }
    }

    override fun ctx(c: JSONObject) {
        c.put("mode", mode.key); c.put("headset", headset)
        if (events.isNotEmpty()) c.put("schedule", JSONArray(events.map { it.line() }))
        if (reminders.isNotEmpty()) c.put("reminders", JSONArray(reminders.map { (_, at, t) -> "${Fmt.hhmm(at)} $t" }))
        if (todos.isNotEmpty()) c.put("todos", JSONArray(todos))
        if (mode == PhoneMode.WALK && walkSince > 0) { c.put("walkKm", (walkM / 100).roundToInt() / 10.0); c.put("walkMin", ((System.currentTimeMillis() - walkSince) / 60_000).toInt()) }
        phoneSoc?.let { c.put("soc", it) }
    }

    override fun render(v: HyunView) {
        v.silent = hub.silent; v.modeIdx = mode.ordinal
        val now = System.currentTimeMillis()
        val ev = events.firstOrNull { !it.allDay && it.start > now }?.let { it.line() to it.start }
        val rem = reminders.minByOrNull { it.second }?.let { "${Fmt.whenLabel(it.second)} ${it.third}" to it.second }
        v.nextUp = listOfNotNull(ev, rem).minByOrNull { it.second }?.let { (line, at) ->
            val m = Desk.minutesUntil(at)
            line + when { m in 0..59 -> " · ${m}분 뒤"; m in 60..719 -> " · ${m / 60}시간${if (m % 60 > 0) " ${m % 60}분" else ""} 뒤"; else -> "" }
        } ?: ""
        v.tasks = reminders.map { (_, at, t) -> "${Fmt.whenLabel(at)} $t" } + todos.map { "□ $it" }
        v.reminderItems = reminders.map { (_, at, t) -> "${Fmt.whenLabel(at)}  $t" } + events.map { "일정 ${it.line()}" }
        v.todoItems = todos.map { "□  $it" }
        v.nextMore = (reminders.size + events.count { !it.allDay && it.start > now } + todos.size - (if (v.nextUp.isBlank()) 0 else 1)).coerceAtLeast(0)
    }

    override fun offline(text: String): String? = if (text.contains("배터리") && phoneSoc != null) "배터리는 ${phoneSoc}퍼센트예요." else null

    override fun fallback(ev: String, extra: JSONObject?): String? = when (ev) {
        "hello" -> "안녕하세요, ${hub.botName}예요. ${mode.label} 모드예요. 무엇을 도와드릴까요?"
        "mode" -> "${mode.label} 모드예요." + (events.firstOrNull { !it.allDay }?.let { " 다음 일정은 ${it.line()}이에요." } ?: "")
        "battery_low" -> "폰 배터리가 ${phoneSoc}퍼센트예요. 충전해 두세요."
        else -> null
    }
}
