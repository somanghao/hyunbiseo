package io.github.somanghao.makecar.hyun

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.CalendarContract
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 폰(짭비서)의 책상 — 오늘 일정(캘린더) · 배터리 · 이어폰 · 진동. 차에선 안 쓴다.
 * 캘린더는 READ_CALENDAR 권한이 있을 때만(없으면 빈 목록).
 */
class Desk(private val ctx: Context) {
    data class Event(val id: Long, val title: String, val start: Long, val end: Long, val place: String?, val allDay: Boolean) {
        fun line(): String = (if (allDay) "종일 " else SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(start)) + " ") + title +
            (place?.takeIf { it.isNotBlank() }?.let { "($it)" } ?: "")
    }

    /** 지금부터 [hours]시간 안의 일정(시작 순, 이미 끝난 건 제외) */
    fun upcoming(hours: Int = 18): List<Event> {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val now = System.currentTimeMillis()
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, now - 12 * 3_600_000L); ContentUris.appendId(b, now + hours * 3_600_000L)
        val out = mutableListOf<Event>()
        runCatching {
            ctx.contentResolver.query(b.build(), arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION,
                CalendarContract.Instances.ALL_DAY), null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
                while (c.moveToNext()) {
                    val e = Event(c.getLong(0), c.getString(1) ?: "(제목 없음)", c.getLong(2), c.getLong(3), c.getString(4), c.getInt(5) == 1)
                    if (e.end > now) out += e
                }
            }
        }.onFailure { Log.w("HYUN", "desk|calendar|${it.message}") }
        return out.take(8)
    }

    /** 배터리 %(충전 중이면 음수가 아니라 따로) */
    fun battery(): Pair<Int?, Boolean> {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null to false
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        return (if (level >= 0) level * 100 / scale else null) to charging
    }

    /** 이어폰(유선·블루투스·USB)이 꽂혀 있나 — 대중교통에서 소리로 말해도 되나 */
    fun headset(): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val kinds = mutableSetOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_USB_HEADSET)
        if (Build.VERSION.SDK_INT >= 31) kinds += AudioDeviceInfo.TYPE_BLE_HEADSET
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in kinds }
    }

    /** 진동 — strong = 놓치면 안 되는 알림(P0) */
    fun buzz(strong: Boolean) {
        val v = if (Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
        val pattern = if (strong) longArrayOf(0, 300, 150, 300, 150, 500) else longArrayOf(0, 80)
        runCatching { v.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    companion object {
        fun minutesUntil(ms: Long) = ((ms - System.currentTimeMillis()) / 60_000.0).let { kotlin.math.ceil(it).toInt() }
    }
}
