package io.github.somanghao.makecar.hyunphone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 자동 동기화(기본) — 앱을 열면 기록(/hyun/history)으로 피드를 채우고, 그 뒤엔 /hyun/push long-poll로 차의 새 대화를 바로 받는다.
 *  · kind=talk: 차에서 묻고 답할 때마다(현비서가 먼저 한 말 포함) — 피드에 한 줄(장소 카드면 장소 목록 함께)
 *  · kind=place: 차에서 "폰으로 보내 줘"(명시 전송) — 알림까지(누르면 가게 상세)
 * 받은 장소는 이 폰의 메모리·알림에만(서버도 카카오 장소 원문을 파일에 안 남김 — 약관).
 */
object Sync {
    data class Entry(val mid: Long, val t: String, val who: String, val q: String, val a: String, val card: String,
                     val places: List<JSONObject>?, val placesGone: Boolean, val live: Boolean,
                     val cardx: JSONObject? = null) {   // cardx = 장소가 아닌 카드(콘텐츠 목록 · 소개 · 교통 표) — 정보 탭
        val hasInfo get() = places != null || placesGone || cardx != null
    }

    interface Listener { fun onSync() }

    val feed = mutableListOf<Entry>()          // 시간순(오래된 것 먼저)
    @Volatile var online: Boolean? = null       // null 모름 · false 서버 없음
    @Volatile var carAgo: Int? = null
    @Volatile var lastEvent = ""                // "talk" · "place" — 캐릭터 표정
    @Volatile var lastEventAt = 0L
    var listener: Listener? = null
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private lateinit var app: Context

    fun start(c: Context) {
        app = c.applicationContext
        if (running) return
        running = true
        Thread({ loop() }, "hyunphone-sync").apply { isDaemon = true }.start()
    }

    /** 기록으로 피드 채우기 — 앱을 열 때·돌아올 때(닫혀 있던 동안의 대화) */
    fun refresh() = Thread {
        val r = Server.history(40)
        main.post {
            if (r == null) { online = false; listener?.onSync(); return@post }
            online = true
            val items = r.optJSONArray("items") ?: JSONArray()
            val got = (0 until items.length()).map { entry(items.getJSONObject(it), live = false) }
            val known = feed.associateBy { it.mid }.toMutableMap()
            for (e in got) known[e.mid] = known[e.mid]?.let { old -> if (old.places != null && e.places == null && !e.placesGone) old else e.copy(live = old.live) } ?: e
            feed.clear(); feed += known.values.sortedBy { it.mid }
            got.lastOrNull()?.let { carAgo = items.getJSONObject(items.length() - 1).optInt("ago") }
            listener?.onSync()
        }
    }.start()

    private fun loop() {
        var after = 0L   // 서버 전달 번호 = 시각(ms) 기반
        var first = true
        while (running) {
            val r = Server.push(after, if (first) 1 else 25)
            first = false
            if (r == null) { online = false; main.post { listener?.onSync() }; Thread.sleep(4000); continue }
            if (online != true) refresh()
            online = true
            r.optJSONObject("car")?.let { if (!it.isNull("ago")) carAgo = it.optInt("ago") }
            val items = r.optJSONArray("items")
            if (items == null || items.length() == 0) { after = maxOf(after, r.optLong("seq", after)); main.post { listener?.onSync() }; continue }
            for (i in 0 until items.length()) {
                val it = items.getJSONObject(i)
                after = maxOf(after, it.optLong("id"))
                when (it.optString("kind")) {
                    "talk" -> main.post { addLive(entry(it, live = true)) }
                    "place" -> main.post { sent(it) }
                }
            }
        }
    }

    private fun addLive(e: Entry) {
        val i = feed.indexOfFirst { it.mid == e.mid }
        if (i >= 0) feed[i] = e else feed += e
        feed.sortBy { it.mid }
        carAgo = 0; lastEvent = if (e.places != null) "place" else "talk"; lastEventAt = System.currentTimeMillis()
        Log.i("HYUNPHONE", "sync|talk|mid=${e.mid}|places=${e.places?.size ?: 0}")   // 가게 이름·전화는 로그에 남기지 않는다
        listener?.onSync()
    }

    private fun sent(p: JSONObject) {
        lastEvent = "place"; lastEventAt = System.currentTimeMillis()
        Log.i("HYUNPHONE", "sync|place|sent")
        notify(app, p)
        listener?.onSync()
    }

    private fun entry(o: JSONObject, live: Boolean): Entry {
        val pl = o.optJSONArray("places")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        return Entry(o.optLong("mid"), o.optString("t"), o.optString("who"), o.optString("q"), o.optString("a"), o.optString("card"),
            pl, o.optBoolean("places_gone"), live, o.optJSONObject("cardx"))
    }

    private const val CHANNEL = "hyunphone_sent"
    private const val NOTI_ID = 5210

    fun notify(c: Context, p: JSONObject) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "차에서 보낸 장소", NotificationManager.IMPORTANCE_HIGH))
        // 알림 → [정보] 탭의 그 가게 카드로(찾으면 그 줄을 강조, 없으면 가게 상세)
        val tap = PendingIntent.getActivity(c, NOTI_ID, Intent(c, MainActivity::class.java).apply {
            putExtra("focus_place", p.toString()); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val sub = listOfNotNull(if (p.optString("tel").isNotBlank()) "전화·위치" else "위치", p.optString("cat").takeIf { it.isNotBlank() },
            p.optString("near").takeIf { it.isNotBlank() }?.let { "$it 근처 ${p.optInt("m")}m" }).joinToString(" · ")
        val n = Notification.Builder(c, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle("현비서")
            .setContentText("차에서 보냄 — ${p.optString("name")} · $sub")
            .setStyle(Notification.BigTextStyle().bigText("차에서 보냄 — ${p.optString("name")}\n$sub\n${p.optString("addr")}"))
            .setContentIntent(tap).setAutoCancel(true)
            .setCategory(Notification.CATEGORY_RECOMMENDATION)
            .build()
        runCatching { nm.notify(NOTI_ID, n) }
    }
}
