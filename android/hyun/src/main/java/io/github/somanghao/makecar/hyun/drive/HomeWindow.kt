package io.github.somanghao.makecar.hyun.drive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import io.github.somanghao.makecar.hyun.HyunActivity

/**
 * Pleos 홈 창 배치 — **문서에 없는 홈 내부 방송**(홈 바이너리 분해 · 에뮬 11-09 실측). 내비가 현비서를 덮으면 반반으로 옮기는 데 쓴다(사용자 11-09).
 * ⚠️ 내비 가이드의 "비공식 API 사용 금지"와 부딪힘 — 시연용, 실차는 제휴·정식 API화 시 → wiki/hyun-pleos-capabilities.md §⑧
 */
object HomeWindow {
    private const val HOME = "ai.umos.homescreen"
    const val ONE = 1   // 1칸(반반의 한쪽)
    const val TWO = 2   // 앱 영역 전체
    val NAVI = ComponentName("ai.umos.maps.android.navigation.app", "ai.umos.maps.android.navigation.app.MainActivity")

    /** 보이는 창(comp)을 1칸/전체로 — 1칸으로 줄이면 빈 칸엔 '가장 최근에 숨은 앱'이 나온다. 숨은 창엔 안 먹는다 */
    fun slots(ctx: Context, comp: ComponentName, o: Int) = ctx.sendBroadcast(
        Intent("ai.umos.homescreen.intent.action.REQUEST_TO_ENFORCE_APP_ORIENTATION").setPackage(HOME)
            .putExtra("componentName", comp.flattenToShortString()).putExtra("orientation", o))

    /** pkg 창 숨김 → 그 자리에 최근에 숨은 앱이 다시 나온다(MANAGE_TASK) */
    fun hide(ctx: Context, pkg: String) = ctx.sendBroadcast(
        Intent("ai.umos.intent.action.HIDE_APP").setPackage(HOME).putExtra("packageName", pkg))

    /** 지금 보이는 창들(1개 = 1칸 · 2개 = 반반) */
    fun visible(ctx: Context): List<String> = runCatching {
        ctx.contentResolver.call(Uri.parse("content://ai.umos.homescreen.provider"), "getTaskStatus", null, null)
            ?.getStringArray("status")?.toList()
    }.getOrNull() ?: emptyList()
}

/**
 * 헤드업 알림 — 현비서가 가려진 동안(내비 전체 화면 등) 한 말을 내비 위 작은 카드로(정식 길, 사용자 11-09).
 * 버튼: [반반으로](내비를 1칸 → 빈 칸에 현비서) · [현비서 보기](앞으로). 10초 뒤 저절로 사라지고, 현비서가 보이면 지운다
 */
object HeadsUp {
    private const val ID = 4201
    private const val CH = "hyun_headsup"

    fun show(ctx: Context, text: String, alert: Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(
            NotificationChannel(CH, "현비서 — 내비 위 알림", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "현비서가 가려졌을 때 한 말(길·교통·안전)"; setSound(null, null); enableVibration(false)
            })
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(ctx, 1, Intent(ctx, HyunActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), flags)
        val split = PendingIntent.getBroadcast(ctx, 2, Intent(ctx, SplitReceiver::class.java), flags)
        val n = Notification.Builder(ctx, CH)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (alert) "현비서 · 안전" else "현비서")
            .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setContentIntent(open).setAutoCancel(true).setTimeoutAfter(10_000).setOnlyAlertOnce(false)
            .addAction(Notification.Action.Builder(null, "반반으로", split).build())
            .addAction(Notification.Action.Builder(null, "현비서 보기", open).build())
            .build()
        runCatching { nm.notify(ID, n) }.onFailure { Log.i("HYUN", "hun|FAIL|${it.message}") }
    }

    fun cancel(ctx: Context) { ctx.getSystemService(NotificationManager::class.java)?.cancel(ID) }
}

/** 헤드업 알림 [반반으로] — 내비를 1칸으로 줄이면 빈 칸에 현비서(가장 최근에 숨은 앱)가 나온다 */
class SplitReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        Log.i("HYUN", "hun|split")
        HomeWindow.slots(ctx, HomeWindow.NAVI, HomeWindow.ONE)
        HeadsUp.cancel(ctx)
    }
}
