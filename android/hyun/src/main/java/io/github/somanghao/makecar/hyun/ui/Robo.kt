package io.github.somanghao.makecar.hyun.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.max
import kotlin.math.sin

/**
 * 현비서 3D풍 로봇 아바타 — 그림 파일 없이 코드로(구 음영 · 반사광 · 빛나는 귀 링 · 안테나).
 * 흰 유광 둥근 몸 + 하늘색 아랫배 · 흰 얼굴판 + 큰 파란 유리 눈 · 이마에 H 엠블럼.
 * 표정은 눈·입만 바꾼다(기존 bot_<face> 이름 그대로). mode: 0 대기 · 1 듣기 · 2 생각 · 3 말하기
 */
object Robo {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private const val WHITE_HI = 0xFFFFFFFF.toInt()
    private const val WHITE_MID = 0xFFEEF2F7.toInt()
    private const val WHITE_EDGE = 0xFFB9C6D6.toInt()
    private const val SKY_HI = 0xFFCBE8FB.toInt()
    private const val SKY = 0xFF8CC8F2.toInt()
    private const val SKY_EDGE = 0xFF4E95D6.toInt()
    private const val GLOW = 0xFF35B6FF.toInt()
    private const val EYE_TOP = 0xFF06142B.toInt()
    private const val EYE_BOT = 0xFF1E8FFF.toInt()
    private const val INK = 0xFF13213A.toInt()
    private const val BRAND = 0xFF1F4E9C.toInt()   // H 엠블럼

    private fun a(c: Int, al: Int) = (c and 0x00FFFFFF) or (al.coerceIn(0, 255) shl 24)

    fun draw(c: Canvas, cx: Float, cy0: Float, s: Float, t: Float, face: String, mode: Int, alive: Boolean, dark: Boolean) {
        p.reset(); p.isAntiAlias = true
        val cy = cy0 + (if (alive) sin(t * 1.7f) * s * 0.01f else 0f)   // 둥실
        val bw = s * 0.74f; val bh = s * 0.68f
        val body = RectF(cx - bw / 2, cy - bh / 2 + s * 0.0f, cx + bw / 2, cy + bh / 2)
        val glowK = when (mode) { 1 -> 1f; 3 -> 0.6f + 0.4f * (0.5f + 0.5f * sin(t * 8f)); 2 -> 0.4f + 0.4f * (0.5f + 0.5f * sin(t * 3f)); else -> 0.45f }

        // 발(몸 뒤)
        for (sd in listOf(-1, 1)) {
            val fx = cx + sd * bw * 0.2f; val ft = body.bottom - bh * 0.12f
            val fr = RectF(fx - bw * 0.13f, ft, fx + bw * 0.13f, ft + bh * 0.2f)
            p.shader = LinearGradient(0f, fr.top, 0f, fr.bottom, intArrayOf(WHITE_HI, WHITE_MID, SKY, SKY_EDGE), floatArrayOf(0f, 0.4f, 0.65f, 1f), Shader.TileMode.CLAMP)
            c.drawRoundRect(fr, bw * 0.12f, bw * 0.12f, p)
            p.shader = null; p.color = a(GLOW, (150 * glowK + 60).toInt()); c.drawRoundRect(RectF(fr.left + bw * 0.04f, fr.bottom - bh * 0.035f, fr.right - bw * 0.04f, fr.bottom - bh * 0.015f), 4f, 4f, p)
        }
        // 팔(몸 뒤로 살짝)
        for (sd in listOf(-1, 1)) {
            c.save(); val ax = cx + sd * bw * 0.47f; val ay = cy + bh * 0.12f
            c.rotate(sd * 28f, ax, ay)
            val ar = RectF(ax - bw * 0.07f, ay - bh * 0.14f, ax + bw * 0.07f, ay + bh * 0.14f)
            p.shader = LinearGradient(ar.left, 0f, ar.right, 0f, if (sd < 0) WHITE_HI else WHITE_MID, if (sd < 0) WHITE_EDGE else WHITE_EDGE, Shader.TileMode.CLAMP)
            c.drawRoundRect(ar, bw * 0.07f, bw * 0.07f, p); c.restore()
        }

        // 몸 — 흰 유광 구(왼쪽 위 빛)
        p.shader = RadialGradient(cx - bw * 0.2f, cy - bh * 0.3f, max(bw, bh) * 0.95f, intArrayOf(WHITE_HI, WHITE_MID, 0xFFD6DFEA.toInt(), WHITE_EDGE), floatArrayOf(0f, 0.45f, 0.78f, 1f), Shader.TileMode.CLAMP)
        c.drawOval(body, p)
        // 하늘색 아랫배(몸 모양으로 잘라서, 경계는 둥글게)
        c.save(); path.reset(); path.addOval(body, Path.Direction.CW); c.clipPath(path)
        val band = RectF(body.left - bw * 0.1f, cy + bh * 0.1f, body.right + bw * 0.1f, body.bottom + bh * 0.6f)
        p.shader = RadialGradient(cx - bw * 0.15f, cy + bh * 0.15f, bw * 0.75f, intArrayOf(SKY_HI, SKY, SKY_EDGE), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawOval(band, p)
        // 경계 홈(얇은 그림자)
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = max(1f, s * 0.006f); p.color = a(0xFF3C6E9E.toInt(), 70); c.drawOval(band, p); p.style = Paint.Style.FILL
        c.restore()
        // 배꼽 버튼
        val btY = cy + bh * 0.3f
        p.shader = RadialGradient(cx - s * 0.008f, btY - s * 0.008f, s * 0.03f, WHITE_HI, WHITE_EDGE, Shader.TileMode.CLAMP); c.drawCircle(cx, btY, s * 0.026f, p)
        // 몸 가장자리 반사광(오른쪽 아래 림 라이트)
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.008f; p.color = a(Color.WHITE, 120)
        c.drawArc(RectF(body.left + s * 0.01f, body.top + s * 0.01f, body.right - s * 0.01f, body.bottom - s * 0.01f), 10f, 60f, false, p); p.style = Paint.Style.FILL

        // 귀 — 흰 원판 + 빛나는 파란 링
        for (sd in listOf(-1, 1)) {
            val ex = cx + sd * bw * 0.49f; val ey = cy - bh * 0.06f; val er = s * 0.085f
            p.shader = RadialGradient(ex, ey, er * 2.2f, a(GLOW, (110 * glowK).toInt()), a(GLOW, 0), Shader.TileMode.CLAMP); c.drawCircle(ex, ey, er * 2.2f, p)
            val ear = RectF(ex - er * 0.62f, ey - er, ex + er * 0.62f, ey + er)
            p.shader = LinearGradient(ear.left, 0f, ear.right, 0f, if (sd < 0) WHITE_HI else WHITE_MID, WHITE_EDGE, Shader.TileMode.CLAMP); c.drawOval(ear, p)
            p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.014f; p.color = a(GLOW, (170 + 85 * glowK).toInt())
            c.drawOval(RectF(ear.left + er * 0.16f, ear.top + er * 0.2f, ear.right - er * 0.16f, ear.bottom - er * 0.2f), p); p.style = Paint.Style.FILL
        }

        // 안테나 + 빛 구슬
        val ax0 = cx + bw * 0.12f; val ay0 = body.top + bh * 0.04f; val ax1 = cx + bw * 0.2f; val ay1 = body.top - bh * 0.2f
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = s * 0.012f; p.color = 0xFFC3CEDA.toInt(); c.drawLine(ax0, ay0, ax1, ay1, p); p.style = Paint.Style.FILL
        p.shader = RadialGradient(ax1, ay1, s * 0.07f, a(GLOW, (140 * glowK + 50).toInt()), a(GLOW, 0), Shader.TileMode.CLAMP); c.drawCircle(ax1, ay1, s * 0.07f, p)
        p.shader = RadialGradient(ax1 - s * 0.006f, ay1 - s * 0.006f, s * 0.026f, Color.WHITE, GLOW, Shader.TileMode.CLAMP); c.drawCircle(ax1, ay1, s * 0.022f, p)

        // H 엠블럼(이마 — 모자 자리)
        emblem(c, cx, body.top + bh * 0.13f, s * 0.12f)

        // 얼굴판 — 홈(그림자) + 흰 판
        val fp = RectF(cx - bw * 0.36f, cy - bh * 0.26f, cx + bw * 0.36f, cy + bh * 0.14f)
        val rr = fp.height() * 0.42f
        p.shader = null; p.color = a(0xFF8796AA.toInt(), 90); c.drawRoundRect(RectF(fp.left - s * 0.008f, fp.top - s * 0.006f, fp.right + s * 0.008f, fp.bottom + s * 0.012f), rr, rr, p)
        p.shader = LinearGradient(0f, fp.top, 0f, fp.bottom, WHITE_HI, 0xFFE9EEF4.toInt(), Shader.TileMode.CLAMP); c.drawRoundRect(fp, rr, rr, p)
        p.shader = null

        drawFace(c, cx, fp.centerY() + bh * 0.0f, bw, s, t, face, mode, alive, glowK)
    }

    /** 이마의 H — 기울어진 타원 테 안에 비스듬한 H */
    private fun emblem(c: Canvas, x: Float, y: Float, w: Float) {
        val h = w * 0.62f
        val r = RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom, 0xFF3A6CC0.toInt(), BRAND, Shader.TileMode.CLAMP)
        p.style = Paint.Style.STROKE; p.strokeWidth = w * 0.07f; c.drawOval(r, p)
        p.style = Paint.Style.FILL
        val sk = w * 0.12f   // 기울기
        val sw = w * 0.11f   // 기둥 굵기
        val top = y - h * 0.3f; val bot = y + h * 0.3f
        fun bar(xc: Float) { path.reset(); path.moveTo(xc - sw / 2 + sk / 2, top); path.lineTo(xc + sw / 2 + sk / 2, top); path.lineTo(xc + sw / 2 - sk / 2, bot); path.lineTo(xc - sw / 2 - sk / 2, bot); path.close(); c.drawPath(path, p) }
        bar(x - w * 0.18f); bar(x + w * 0.18f)
        path.reset(); val cb = w * 0.05f
        path.moveTo(x - w * 0.18f + sw / 2, y - cb * 0.2f - sk * 0.1f); path.lineTo(x + w * 0.18f - sw / 2, y - cb * 1.0f - sk * 0.1f)
        path.lineTo(x + w * 0.18f - sw / 2, y + cb * 0.6f - sk * 0.1f); path.lineTo(x - w * 0.18f + sw / 2, y + cb * 1.4f - sk * 0.1f); path.close(); c.drawPath(path, p)
        p.shader = null
    }

    /** 표정 — 큰 유리 눈(위 짙은 남색 → 아래 밝은 파랑 + 반사광 둘) · 입 */
    private fun drawFace(c: Canvas, x: Float, y: Float, bw: Float, s: Float, t: Float, face: String, mode: Int, alive: Boolean, glowK: Float) {
        val ex = bw * 0.15f; val erx = bw * 0.085f; val ery = bw * 0.095f
        val eyY = y - ery * 0.15f
        val blink = alive && (t % 5.5f) < 0.12f
        val closed = blink || face in setOf("sleep", "tired")
        val smiley = face in setOf("happy", "laugh", "cheer", "excited", "humming", "waving", "proud", "sparkle", "thumbs_up")
        val big = face in setOf("alert", "surprised") || mode == 1
        val up = mode == 2 || face == "thinking"
        p.strokeCap = Paint.Cap.ROUND
        for (sd in listOf(-1, 1)) {
            val px = x + sd * ex; val k = if (big) 1.12f else 1f
            when {
                face == "heart_eyes" || face == "love" -> heart(c, px, eyY, erx * 1.1f)
                closed -> { p.color = INK; p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.014f
                    c.drawArc(RectF(px - erx * 0.8f, eyY - ery * 0.4f, px + erx * 0.8f, eyY + ery * 0.5f), 20f, 140f, false, p); p.style = Paint.Style.FILL }
                smiley -> { p.color = EYE_BOT; p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.02f
                    c.drawArc(RectF(px - erx * 0.8f, eyY - ery * 0.5f, px + erx * 0.8f, eyY + ery * 0.9f), 200f, 140f, false, p); p.style = Paint.Style.FILL }
                else -> {
                    val r = RectF(px - erx * k, eyY - ery * k, px + erx * k, eyY + ery * k)
                    if (face == "driving") { r.top += ery * 0.35f }   // 집중 — 살짝 가늘게
                    p.shader = LinearGradient(0f, r.top, 0f, r.bottom, intArrayOf(EYE_TOP, 0xFF0B3A7A.toInt(), EYE_BOT), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
                    c.drawOval(r, p); p.shader = null
                    // 눈 안쪽 빛(듣기·말하기에 밝아짐)
                    p.shader = RadialGradient(r.centerX(), r.bottom - r.height() * 0.2f, r.width() * 0.5f, a(GLOW, (120 * glowK + 60).toInt()), a(GLOW, 0), Shader.TileMode.CLAMP); c.drawOval(r, p); p.shader = null
                    val lx = if (up) erx * 0.15f else 0f; val ly = if (up) -ery * 0.15f else 0f
                    p.color = Color.WHITE
                    c.drawCircle(r.centerX() - erx * 0.32f + lx, r.centerY() - ery * 0.34f + ly, erx * 0.3f, p)
                    c.drawCircle(r.centerX() + erx * 0.35f + lx, r.centerY() + ery * 0.3f + ly, erx * 0.13f, p)
                }
            }
        }
        // 입
        val my = y + ery * 1.25f; val mw = bw * 0.05f
        p.color = INK
        when {
            mode == 3 -> { val o = 0.3f + 0.7f * (0.5f + 0.5f * sin(t * 13f)); c.drawRoundRect(RectF(x - mw, my - mw * 0.3f, x + mw, my + mw * (0.3f + 0.9f * o)), mw, mw, p) }
            big -> c.drawOval(RectF(x - mw * 0.55f, my - mw * 0.3f, x + mw * 0.55f, my + mw * 0.8f), p)
            up -> { p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.01f; c.drawLine(x - mw * 0.3f, my + mw * 0.2f, x + mw * 0.9f, my, p); p.style = Paint.Style.FILL }
            face == "sleep" -> { c.drawCircle(x, my + mw * 0.2f, mw * 0.35f, p); p.color = a(GLOW, 220); p.textSize = s * 0.06f; c.drawText("z", x + bw * 0.42f, y - bw * 0.3f - (t % 2f) * s * 0.02f, p) }
            face in setOf("tired", "bored", "worried") -> { p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.01f; c.drawLine(x - mw * 0.7f, my + mw * 0.3f, x + mw * 0.7f, my + mw * 0.3f, p); p.style = Paint.Style.FILL }
            smiley -> { path.reset(); path.addArc(RectF(x - mw * 1.2f, my - mw, x + mw * 1.2f, my + mw * 1.2f), 0f, 180f); path.close(); c.drawPath(path, p) }
            else -> { p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.011f; c.drawArc(RectF(x - mw, my - mw * 1.1f, x + mw, my + mw * 0.6f), 30f, 120f, false, p); p.style = Paint.Style.FILL }
        }
        p.style = Paint.Style.FILL
    }

    private fun heart(c: Canvas, x: Float, y: Float, r: Float) {
        p.shader = RadialGradient(x - r * 0.3f, y - r * 0.4f, r * 1.4f, 0xFFFF9EC0.toInt(), 0xFFFF4F86.toInt(), Shader.TileMode.CLAMP)
        path.reset(); path.moveTo(x, y + r * 0.9f)
        path.cubicTo(x - r * 1.4f, y, x - r, y - r * 1.1f, x, y - r * 0.35f)
        path.cubicTo(x + r, y - r * 1.1f, x + r * 1.4f, y, x, y + r * 0.9f); path.close(); c.drawPath(path, p); p.shader = null
    }
}
