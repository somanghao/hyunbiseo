package io.github.somanghao.makecar.hyun.voice

import io.github.somanghao.makecar.hyun.HyunActivity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 마이크 포그라운드 서비스(차) — 운전자가 내비·음악 화면으로 가도 상시 귀(Ear)가 계속 듣게 한다.
 * Android 9+는 뒤로 간 앱의 마이크를 무음으로 만든다. 앱이 앞에 있을 때 microphone 형식 포그라운드 서비스를 켜 두면
 * 뒤로 가도 "사용 중" 접근이 유지된다. 귀(Ear) 자체는 HyunActivity가 그대로 들고 있고, 이 서비스는 그 자격과 알림만 맡는다.
 * 켜기·끄기 = HyunActivity.ensureEar(귀를 켤 때 켜고, 놓을 때 끈다). 앱이 뒤에 있을 때 새로 켜는 건 막히므로(API 34) 실패는 로그만.
 */
class EarService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, "현비서 듣기", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, HyunActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        @Suppress("DEPRECATION")
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else Notification.Builder(this))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("현비서가 듣고 있어요")
            .setContentText("\"현비서야\" 하고 부르세요 — 다른 화면에서도 들어요")
            .setContentIntent(open).setOngoing(true).build()
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 30) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(ID, n)
        }
        Log.i("HYUN", "earService|start|${ok.exceptionOrNull()?.let { "FAIL ${it.javaClass.simpleName} ${it.message}" } ?: "ok"}")
        if (ok.isFailure) { on = false; stopSelf() }
        return START_NOT_STICKY
    }

    override fun onDestroy() { Log.i("HYUN", "earService|stop"); super.onDestroy() }

    companion object {
        private const val CH = "hyun_ear"
        private const val ID = 21
        @Volatile var on = false
            private set

        /** 켠다 — 앱이 앞에 있을 때만 성공한다(뒤에서 새로 켜면 API 34가 막는다). 반환 = 지금 켜져 있나 */
        fun start(c: Context): Boolean {
            if (on) return true
            on = runCatching {
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(Intent(c, EarService::class.java)) else c.startService(Intent(c, EarService::class.java))
            }.onFailure { Log.i("HYUN", "earService|start|FAIL ${it.javaClass.simpleName} ${it.message}") }.isSuccess
            return on
        }

        fun stop(c: Context) {
            if (!on) return
            on = false
            runCatching { c.stopService(Intent(c, EarService::class.java)) }
        }
    }
}
