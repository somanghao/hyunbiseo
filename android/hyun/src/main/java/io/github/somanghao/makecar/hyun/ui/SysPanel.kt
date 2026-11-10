package io.github.somanghao.makecar.hyun.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import io.github.somanghao.makecar.hyun.core.Hyun
import io.github.somanghao.makecar.hyun.feature.StageFeature

/**
 * 에뮬용 '시스템 칸' 흉내 — 실차 Pleos 홈은 왼쪽 약 1/3을 시스템 화면(내 차 · 기어 · 미디어)으로 고정하고 앱은 오른쪽 2/3에만 뜬다.
 * 표준 AAOS 에뮬엔 그 틀이 없어 앱이 가로 전체를 쓰므로, Pleos 홈이 없을 때만 이 판을 왼쪽에 깔아 실차 비율로 보여 준다(HyunActivity).
 * 값 = 현비서가 읽는 실제 신호(속도·기어 = Hyun, 배터리·주행 가능 = StageFeature 의 CarStatus) · 끄기 `--ei sideL 0`
 */
class SysPanel(ctx: Context, private val hyun: Hyun) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    private val tick = object : Runnable { override fun run() { invalidate(); postDelayed(this, 1000) } }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    private val night get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val l = paddingLeft.toFloat(); val t = paddingTop.toFloat(); val b = h - paddingBottom
        val cw = w - l; val ch = b - t
        if (cw <= 0 || ch <= 0) return
        val S = minOf(cw / 60f, ch / 90f)                 // 단위 — 643×904 기준 약 10px
        val bg0 = if (night) 0xFF161B22.toInt() else 0xFFE9EDF2.toInt()
        val bg1 = if (night) 0xFF0E1217.toInt() else 0xFFD9DFE6.toInt()
        val fg = if (night) 0xFFE6EAF0.toInt() else 0xFF1B2330.toInt()
        val sub = if (night) 0xFF8A94A3.toInt() else 0xFF6B7584.toInt()
        val card = if (night) 0xFF1E252E.toInt() else 0xFFF7F9FB.toInt()
        val line = if (night) 0x22FFFFFF else 0x14000000
        val teal = 0xFF00A6A6.toInt()

        p.shader = LinearGradient(0f, t, 0f, b, bg0, bg1, Shader.TileMode.CLAMP); p.style = Paint.Style.FILL
        c.drawRect(0f, 0f, w, h, p); p.shader = null
        p.color = line; c.drawRect(w - 2f, 0f, w, h, p)   // 앱 칸과의 경계

        val x0 = l + 4f * S; val x1 = w - 4f * S
        // 기어 P R N D
        var y = t + 7f * S
        val gears = "PRND"; val g = hyun.gear
        p.typeface = bold; p.textSize = 3.4f * S; p.textAlign = Paint.Align.LEFT
        gears.forEachIndexed { i, ch1 ->
            p.color = if (ch1 == g) fg else sub and 0x66FFFFFF
            c.drawText(ch1.toString(), x0 + i * 5f * S, y, p)
        }
        // 속도
        p.textAlign = Paint.Align.RIGHT; p.color = fg; p.textSize = 7f * S
        c.drawText("${hyun.kmh()}", x1 - 7f * S, y + 1.6f * S, p)
        p.typeface = Typeface.DEFAULT; p.textSize = 2.4f * S; p.color = sub
        c.drawText("km/h", x1, y + 1.6f * S, p)

        // 내 차(옆모습) — 실차는 3D 차가 서는 자리
        val carW = (x1 - x0) * 0.92f; val cx = (x0 + x1) / 2f; val base = t + ch * 0.50f
        drawCar(c, cx - carW / 2, base, carW, fg, sub, night)

        // 배터리 · 주행 가능
        val st = hyun.features.filterIsInstance<StageFeature>().firstOrNull()?.m?.status
        val soc = st?.soc?.v; val range = st?.rangeKm?.v
        y = base + 10f * S
        p.typeface = Typeface.DEFAULT; p.textAlign = Paint.Align.LEFT; p.textSize = 2.4f * S; p.color = sub
        c.drawText(st?.energyLabel?.v ?: "배터리", x0, y, p)
        p.textAlign = Paint.Align.RIGHT
        c.drawText(range?.let { "주행 가능 ${it}km" } ?: "주행 가능 —", x1, y, p)
        y += 2.2f * S
        val bar = RectF(x0, y, x1, y + 1.4f * S)
        p.color = line or 0x10000000; c.drawRoundRect(bar, bar.height() / 2, bar.height() / 2, p)
        val f = ((soc ?: 0).coerceIn(0, 100)) / 100f
        if (f > 0f) { p.color = teal; c.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * f, bar.bottom), bar.height() / 2, bar.height() / 2, p) }
        p.textAlign = Paint.Align.LEFT; p.typeface = bold; p.color = fg; p.textSize = 4.2f * S
        c.drawText(soc?.let { "$it%" } ?: "—", x0, y + 7.4f * S, p)

        // 미디어 카드
        val mc = RectF(x0, b - 22f * S, x1, b - 4f * S)
        p.color = card; c.drawRoundRect(mc, 2.4f * S, 2.4f * S, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = line; c.drawRoundRect(mc, 2.4f * S, 2.4f * S, p); p.style = Paint.Style.FILL
        val art = RectF(mc.left + 2.4f * S, mc.top + 2.4f * S, mc.left + 2.4f * S + (mc.height() - 4.8f * S), mc.bottom - 2.4f * S)
        p.color = line or 0x0A000000; c.drawRoundRect(art, 1.6f * S, 1.6f * S, p)
        p.color = sub; p.textSize = 3f * S; p.textAlign = Paint.Align.CENTER; c.drawText("♪", art.centerX(), art.centerY() + 1f * S, p)
        p.textAlign = Paint.Align.LEFT; p.typeface = bold; p.color = fg; p.textSize = 2.6f * S
        c.drawText("미디어", art.right + 2.6f * S, art.centerY() - 0.6f * S, p)
        p.typeface = Typeface.DEFAULT; p.color = sub; p.textSize = 2.2f * S
        c.drawText("재생 중인 항목 없음", art.right + 2.6f * S, art.centerY() + 2.8f * S, p)

        // 출처 표시(옅게) — 실제 시스템 화면이 아님
        p.textSize = 1.7f * S; p.color = sub and 0x88FFFFFF.toInt()
        c.drawText("에뮬 · 시스템 칸 자리(실차는 Pleos 홈)", x0, b - 1.2f * S, p)
    }

    /** 단순한 해치백 옆모습 — 폭 w, 바퀴 바닥 = base */
    private fun drawCar(c: Canvas, x: Float, base: Float, w: Float, fg: Int, sub: Int, night: Boolean) {
        val u = w / 100f
        // 바닥 그림자
        p.color = if (night) 0x55000000 else 0x22000000
        c.drawOval(RectF(x + 6 * u, base - 1.6f * u, x + 94 * u, base + 2.4f * u), p)
        val body = Path().apply {
            moveTo(x + 4 * u, base - 8 * u)
            lineTo(x + 4 * u, base - 15 * u)
            quadTo(x + 6 * u, base - 19 * u, x + 16 * u, base - 20 * u)
            lineTo(x + 30 * u, base - 22 * u)
            quadTo(x + 40 * u, base - 32 * u, x + 50 * u, base - 33 * u)
            lineTo(x + 70 * u, base - 33 * u)
            quadTo(x + 80 * u, base - 32 * u, x + 88 * u, base - 22 * u)
            quadTo(x + 96 * u, base - 20 * u, x + 96 * u, base - 13 * u)
            lineTo(x + 96 * u, base - 8 * u)
            close()
        }
        p.shader = LinearGradient(0f, base - 33 * u, 0f, base - 8 * u,
            if (night) 0xFF3A4452.toInt() else 0xFFFFFFFF.toInt(), if (night) 0xFF262D37.toInt() else 0xFFC9D0D9.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(body, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 0.5f * u; p.color = sub and 0x66FFFFFF; c.drawPath(body, p); p.style = Paint.Style.FILL
        // 창
        val glass = Path().apply {
            moveTo(x + 34 * u, base - 22 * u); quadTo(x + 42 * u, base - 30 * u, x + 50 * u, base - 30.5f * u)
            lineTo(x + 69 * u, base - 30.5f * u); quadTo(x + 77 * u, base - 29.5f * u, x + 83 * u, base - 22 * u); close()
        }
        p.color = if (night) 0xFF11161C.toInt() else 0xFF2B3542.toInt(); c.drawPath(glass, p)
        p.color = if (night) 0xFF3A4452.toInt() else 0xFFC9D0D9.toInt(); c.drawRect(x + 58 * u, base - 31 * u, x + 59.6f * u, base - 22 * u, p)
        // 등
        p.color = 0xFFE24B4B.toInt(); c.drawRoundRect(RectF(x + 4 * u, base - 17 * u, x + 7 * u, base - 13.5f * u), u, u, p)
        p.color = if (night) 0xFFBFE9FF.toInt() else 0xFF7FD3FF.toInt(); c.drawRoundRect(RectF(x + 92 * u, base - 18 * u, x + 96 * u, base - 15 * u), u, u, p)
        // 바퀴
        for (wx in floatArrayOf(24f, 77f)) {
            p.color = if (night) 0xFF0A0D11.toInt() else 0xFF1E2530.toInt(); c.drawCircle(x + wx * u, base - 8 * u, 8 * u, p)
            p.color = if (night) 0xFF5A6575.toInt() else 0xFFAEB6C1.toInt(); c.drawCircle(x + wx * u, base - 8 * u, 4.4f * u, p)
            p.color = fg and 0x33FFFFFF; c.drawCircle(x + wx * u, base - 8 * u, 1.4f * u, p)
        }
    }

    companion object {
        const val DEFAULT_RATIO = 643f / 1920f   // 실차 Pleos 홈 시스템 칸 폭(1920 화면 기준 643px)
        fun pleosHome(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo("ai.umos.homescreen", 0); true }.getOrDefault(false)
    }
}
