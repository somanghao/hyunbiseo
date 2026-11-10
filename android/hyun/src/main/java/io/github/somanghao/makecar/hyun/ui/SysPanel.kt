package io.github.somanghao.makecar.hyun.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
 * 에뮬용 '시스템 칸' — 실제 Pleos 홈 왼쪽 칸 스크린샷(assets/pleos/home_left.webp, 댄 11-10 제공 · 사내 프로토타입 참고용)을 그대로 깐다(예전 코드 그림 흉내는 삭제). 실차 Pleos 홈은 왼쪽 약 1/3을 시스템 화면(내 차 · 기어 · 미디어)으로 고정하고 앱은 오른쪽 2/3에만 뜬다.
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

    /** 실제 Pleos 홈 왼쪽 칸 화면(댄 11-10 제공 스크린샷, 722×1036) — 사내 프로토타입용 참고 이미지. 그림은 그대로 쓰고 바뀌는 곳(벨트 칸)만 코드로 덮는다 */
    private val home: Bitmap? by lazy { runCatching { context.assets.open("pleos/home_left.webp").use { BitmapFactory.decodeStream(it) } }.getOrNull() }
    private val bp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fMed by lazy { runCatching { Typeface.createFromAsset(context.assets, "fonts/Pretendard-Medium.otf") }.getOrDefault(Typeface.DEFAULT) }
    private val fSemi by lazy { runCatching { Typeface.createFromAsset(context.assets, "fonts/Pretendard-SemiBold.otf") }.getOrDefault(bold) }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val l = paddingLeft.toFloat(); val t = paddingTop.toFloat(); val b = h - paddingBottom
        val cw = w - l; val ch = b - t
        if (cw <= 0 || ch <= 0) return
        p.shader = null; p.style = Paint.Style.FILL; p.color = 0xFFFFFFFF.toInt(); c.drawRect(0f, 0f, w, h, p)
        val bm = home ?: return
        // 꽉 채우기(빈 띠 없이) — 폭·높이 중 큰 배율, 아래(미디어 막대·점)를 맞추고 넘치는 건 위 흰 칸에서 잘린다
        val sc = maxOf(cw / bm.width, ch / bm.height); val dw = bm.width * sc; val dh = bm.height * sc
        val dx = l + (cw - dw) / 2; val dy = b - dh
        c.drawBitmap(bm, null, RectF(dx, dy, dx + dw, dy + dh), bp)
        // 벨트 칸(그림 위 0~186px 흰 띠) — 벨트를 안 맸을 때만 그림 그대로, 맸거나 모르면 Pleos 식 기어 · 속도로 덮는다
        val st = hyun.features.filterIsInstance<StageFeature>().firstOrNull()?.m?.status
        if (st?.belt?.v != false) {
            val band = RectF(l, t, w, dy + 186 * sc)
            p.color = 0xFFFFFFFF.toInt(); c.drawRect(band, p)
            p.shader = LinearGradient(0f, band.bottom - 30 * sc, 0f, band.bottom + 2 * sc, 0xFFFFFFFF.toInt(), 0xFFFDFDFD.toInt(), Shader.TileMode.CLAMP); c.drawRect(l, band.bottom - 30 * sc, w, band.bottom + 2 * sc, p); p.shader = null
            val g = hyun.gear; val cy = band.top + (band.height()) * 0.52f
            p.textAlign = Paint.Align.LEFT; p.typeface = fSemi; p.textSize = 34 * sc
            "PRND".forEachIndexed { i, ch1 -> p.color = if (ch1 == g) 0xFF16181C.toInt() else 0xFFC9CCD1.toInt(); c.drawText(ch1.toString(), l + 40 * sc + i * 42 * sc, cy + 12 * sc, p) }
            p.textAlign = Paint.Align.RIGHT; p.typeface = fSemi; p.textSize = 72 * sc; p.color = 0xFF16181C.toInt()
            c.drawText("${hyun.kmh()}", w - 120 * sc, cy + 26 * sc, p)
            p.typeface = fMed; p.textSize = 22 * sc; p.color = 0xFF8A9099.toInt(); c.drawText("km/h", w - 40 * sc, cy + 26 * sc, p)
            p.textAlign = Paint.Align.LEFT
        }
        p.color = 0x14000000; c.drawRect(w - 2f, 0f, w, h, p)   // 앱 칸과의 경계
    }

    companion object {
        const val DEFAULT_RATIO = 643f / 1920f   // 실차 Pleos 홈 시스템 칸 폭(1920 화면 기준 643px)
        fun pleosHome(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo("ai.umos.homescreen", 0); true }.getOrDefault(false)
    }
}
