package io.github.somanghao.makecar.hyun.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** 화면·말·맥락에 같이 쓰는 숫자·시각 모양 */
object Fmt {
    /** m → km(소수 한 자리) */
    fun km(m: Double) = (m / 100).roundToInt() / 10.0

    /** 60분 넘으면 "3시간 52분"(화면·말 — 11-07 "233분"이라 읽음) */
    fun dur(m: Int) = if (m >= 60) "${m / 60}시간" + (if (m % 60 > 0) " ${m % 60}분" else "") else "${m}분"

    fun hhmm(ms: Long): String = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(ms))

    /** 오늘이면 "18:00", 내일이면 "내일 09:00", 그 뒤면 "11/2(월) 09:00" */
    fun whenLabel(ms: Long): String {
        val day = { t: Long -> SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(Date(t)) }
        val now = System.currentTimeMillis()
        return when (day(ms)) {
            day(now) -> hhmm(ms)
            day(now + 86_400_000L) -> "내일 ${hhmm(ms)}"
            else -> SimpleDateFormat("M/d(E) HH:mm", Locale.KOREA).format(Date(ms))
        }
    }
}
