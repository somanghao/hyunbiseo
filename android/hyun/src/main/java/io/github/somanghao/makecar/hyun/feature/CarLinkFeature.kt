package io.github.somanghao.makecar.hyun.feature

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import io.github.somanghao.makecar.hyun.core.Feature
import io.github.somanghao.makecar.hyun.core.Hyun
import io.github.somanghao.makecar.hyun.phone.CarLinkActivity
import io.github.somanghao.makecar.hyun.ui.HyunView
import org.json.JSONObject

/**
 * 폰 연동(폰 모드) — 차 현비서와 같은 서버를 쓰는 폰 현비서 앱이 차와 이어진다.
 *  · 서버 /hyun/push를 계속 long-poll(기다리는 동안 = 서버가 보는 '폰 연결됨') → 차가 "폰으로 보내 줘" 한 장소가 오면 알림 → 누르면 가게 카드
 *  · 머리줄 아래 한 줄 "차 현비서와 연결됨 · 마지막 대화 N분 전"(누르면 차에서 나눈 이야기 · 보낸 장소 — [CarLinkActivity])
 * 받은 장소는 이 폰의 메모리·알림에만 둔다(카카오 약관 — 서버 저장 금지와 같은 결). 서버 짝 = server/hyun/features/phonelink.py
 */
class CarLinkFeature : Feature {
    override val id = "carlink"
    override fun enabled(car: Boolean) = !car
    private lateinit var hub: Hyun
    @Volatile private var running = false
    @Volatile private var online: Boolean? = null   // null = 아직 모름 · false = 서버 없음
    @Volatile private var carAgo: Int? = null

    override fun start(hub: Hyun) {
        this.hub = hub
        base = hub.link.base
        hub.view.onCarLink = { open(hub.act, null) }
        running = true
        Thread({ loop() }, "carlink-push").apply { isDaemon = true }.start()
    }

    override fun stop() { running = false }

    private fun loop() {
        var after = 0L   // 서버 전달 번호 = 시각(ms) 기반 — Int를 넘는다
        var first = true
        while (running) {
            val r = hub.link.pushBlocking(after, if (first) 1 else 25)
            first = false
            if (r == null) { online = false; hub.main.post { hub.render() }; Thread.sleep(5000); continue }
            online = true
            carAgo = r.optJSONObject("car")?.let { if (it.isNull("ago")) null else it.optInt("ago") }
            val items = r.optJSONArray("items")
            if (items != null) for (i in 0 until items.length()) {
                val it = items.getJSONObject(i)
                after = maxOf(after, it.optLong("id"))
                if (it.optString("kind") == "place") hub.main.post { onPlace(it) }
            }
            if (items == null || items.length() == 0) after = maxOf(after, r.optLong("seq", after))
            hub.main.post { hub.render() }
        }
    }

    private fun onPlace(p: JSONObject) {
        last = p
        hub.log("carlink|place|got")   // 이름·전화는 로그에 남기지 않는다
        notify(hub.act, p)
        hub.say("차에서 ${p.optString("name")} 정보를 보냈어요. 알림을 눌러 보세요.", "happy", false, 2, tag = "폰 연동")
        hub.render()
    }

    override fun render(v: HyunView) {
        v.carLine = when (online) {
            true -> "차 현비서와 연결됨" + (carAgo?.let { " · 마지막 대화 " + if (it <= 0) "방금" else "${it}분 전" } ?: "")
            false -> "차 현비서 · 서버 연결 안 됨"
            null -> "차 현비서 · 연결 중…"
        }
    }

    companion object {
        private const val CHANNEL = "hyun_carlink"
        private const val NOTI_ID = 4210
        /** 마지막으로 받은 장소 — 메모리에만(앱을 끄면 사라짐) */
        @Volatile var last: JSONObject? = null
        /** 서버 주소(앱이 --es server로 바꿨을 수 있다) — 연동 화면이 같은 서버에 묻는다 */
        @Volatile var base = "http://127.0.0.1:8790"

        fun open(ctx: Context, place: JSONObject?) {
            ctx.startActivity(Intent(ctx, CarLinkActivity::class.java).apply {
                (place ?: last)?.let { putExtra("place", it.toString()) }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }

        fun notify(ctx: Context, p: JSONObject) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "현비서 — 차에서 보낸 것", NotificationManager.IMPORTANCE_HIGH))
            val tap = PendingIntent.getActivity(ctx, NOTI_ID, Intent(ctx, CarLinkActivity::class.java).apply {
                putExtra("place", p.toString()); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val sub = listOf(p.optString("cat"), p.optString("near").takeIf { it.isNotBlank() }?.let { "$it 근처 ${p.optInt("m")}m" })
                .filter { !it.isNullOrBlank() }.joinToString(" · ")
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_map)
                .setContentTitle("현비서 · 차에서 보냄 — ${p.optString("name")}")
                .setContentText((if (p.optString("tel").isNotBlank()) "전화 · 위치 · " else "위치 · ") + sub)
                .setContentIntent(tap).setAutoCancel(true)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .build()
            runCatching { nm.notify(NOTI_ID, n) }
        }
    }
}
