package io.github.somanghao.makecar.hyun.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * 현비서 '몽글' 아바타 — 그림 파일 없이 코드로 그리는 하늘색~파란 솜뭉치(짧고 동그란 몸) + 두 손에 든 빛나는 하트 등.
 * 어두운 바탕에서 하트 빛이 몸과 바닥을 은은하게 밝힌다. 표정은 얼굴(눈·입·볼)만 바꿔 그린다.
 *  - 듣기: 하트가 밝아지고 눈이 커짐 · 생각: 눈이 위로, 하트가 천천히 숨 쉼 · 말하기: 입이 열렸다 닫힘
 *  - 표정 이름은 기존 bot_<face> 와 같다(neutral · happy · thinking · driving · sleep · tired · alert …)
 */
object Puff {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    // 하늘색 → 파랑
    private const val SKY_HI = 0xFFEAF6FF.toInt()
    private const val SKY = 0xFFA9DAFF.toInt()
    private const val BLUE = 0xFF5FA8FF.toInt()
    private const val DEEP = 0xFF2F6FE0.toInt()
    private const val INK = 0xFF1B2A4A.toInt()
    private const val BLUSH = 0xFFFF9DB5.toInt()
    private const val LIGHT = 0xFFFFF4E0.toInt()     // 하트 빛(따뜻한 흰빛)
    private const val LIGHT_EDGE = 0xFFFFC7D9.toInt()

    private fun a(c: Int, al: Int) = (c and 0x00FFFFFF) or (al.coerceIn(0, 255) shl 24)

    /** cx·cy = 그림 칸 가운데, s = 칸 한 변. mode: 0 대기 · 1 듣기 · 2 생각 · 3 말하기 */
    fun draw(c: Canvas, cx: Float, cy: Float, s: Float, t: Float, face: String, mode: Int, alive: Boolean, dark: Boolean) {
        p.reset(); p.isAntiAlias = true
        val bob = if (alive) sin(t * 1.6f) * s * 0.008f else 0f
        val bw = s * 0.74f; val bh = s * 0.66f                       // 짧고 동그란 몸
        val bx = cx; val by = cy + s * 0.06f + bob
        val body = RectF(bx - bw / 2, by - bh / 2, bx + bw / 2, by + bh / 2)
        val heartY = by + bh * 0.2f
        val pulse = when (mode) {
            1 -> 1.25f
            2 -> 0.9f + 0.25f * (0.5f + 0.5f * sin(t * 2.4f))
            3 -> 1.05f + 0.15f * (0.5f + 0.5f * sin(t * 9f))
            else -> 1f + (if (alive) 0.06f * sin(t * 1.3f) else 0f)
        } * (if (face == "sleep") 0.6f else 1f)

        // 1) 하트 빛이 바닥과 주변을 밝힘(어두운 바탕에서 더 크게)
        val glowR = s * (if (dark) 0.75f else 0.55f) * pulse
        p.shader = RadialGradient(bx, heartY, glowR, a(LIGHT, if (dark) 120 else 70), a(SKY, 0), Shader.TileMode.CLAMP)
        c.drawCircle(bx, heartY, glowR, p)
        val feetY = body.bottom + s * 0.02f
        c.save(); c.scale(1f, 0.2f, bx, feetY)
        p.shader = RadialGradient(bx, feetY, s * 0.42f, a(if (dark) LIGHT else 0xFF000000.toInt(), if (dark) 90 else 40), 0, Shader.TileMode.CLAMP)
        c.drawCircle(bx, feetY, s * 0.42f, p); c.restore()
        p.shader = null

        // 2) 발
        for (side in listOf(-1, 1)) {
            val fx = bx + side * bw * 0.2f
            val fr = RectF(fx - bw * 0.15f, body.bottom - bh * 0.1f, fx + bw * 0.15f, body.bottom + bh * 0.06f)
            p.shader = LinearGradient(0f, fr.top, 0f, fr.bottom, SKY, BLUE, Shader.TileMode.CLAMP); c.drawOval(fr, p)
        }

        // 3) 몸 — 위왼쪽이 밝고 가장자리가 파란 둥근 덩어리 + 보송한 털 가장자리
        p.shader = RadialGradient(bx - bw * 0.18f, by - bh * 0.28f, max(bw, bh) * 0.85f, intArrayOf(SKY_HI, SKY, BLUE, DEEP), floatArrayOf(0f, 0.35f, 0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawOval(body, p)
        fuzz(c, body, s)
        // 하트 빛이 배를 비춤
        p.shader = RadialGradient(bx, heartY, bw * 0.45f * pulse, a(LIGHT, 150), a(LIGHT, 0), Shader.TileMode.CLAMP)
        c.drawOval(body, p); p.shader = null

        // 4) 얼굴
        drawFace(c, bx, by - bh * 0.12f, bw, t, face, mode, alive)

        // 5) 팔 + 빛나는 하트(팔 사이)
        heart(c, bx, heartY, s * 0.085f * (0.95f + 0.05f * pulse), pulse, face == "sleep")
        for (side in listOf(-1, 1)) {
            c.save()
            val ax = bx + side * bw * 0.2f; val ay = heartY + s * 0.01f
            c.rotate(side * -28f, ax, ay)
            val ar = RectF(ax - bw * 0.15f, ay - bh * 0.085f, ax + bw * 0.15f, ay + bh * 0.085f)
            p.shader = RadialGradient(ax - side * bw * 0.05f, ay, bw * 0.17f, intArrayOf(LIGHT, SKY, BLUE), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawOval(ar, p); p.shader = null
            c.restore()
        }
    }

    /** 보송한 가장자리 — 짧은 털 선을 둘레에 고르게(매 프레임 같은 자리) */
    private fun fuzz(c: Canvas, r: RectF, s: Float) {
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = max(1f, s * 0.006f)
        val rx = r.width() / 2; val ry = r.height() / 2; val cx = r.centerX(); val cy = r.centerY()
        for (i in 0 until 140) {
            val ang = i * (Math.PI * 2 / 140) + (i % 3) * 0.01
            val ca = cos(ang).toFloat(); val sa = sin(ang).toFloat()
            val len = s * (0.012f + 0.01f * ((i * 37) % 7) / 7f)
            val x0 = cx + ca * (rx - len * 0.6f); val y0 = cy + sa * (ry - len * 0.6f)
            val x1 = cx + ca * (rx + len); val y1 = cy + sa * (ry + len)
            p.color = a(if (sa < -0.2f) SKY_HI else SKY, 150)
            c.drawLine(x0, y0, x1, y1, p)
        }
        p.style = Paint.Style.FILL
    }

    private fun heart(c: Canvas, x: Float, y: Float, r: Float, pulse: Float, dim: Boolean) {
        // 빛 번짐
        p.shader = RadialGradient(x, y, r * 3.2f * pulse, intArrayOf(a(Color.WHITE, if (dim) 120 else 230), a(LIGHT, 140), a(LIGHT_EDGE, 0)), floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r * 3.2f * pulse, p); p.shader = null
        path.reset()
        path.moveTo(x, y + r * 0.95f)
        path.cubicTo(x - r * 1.5f, y + r * 0.1f, x - r * 1.05f, y - r * 1.15f, x, y - r * 0.4f)
        path.cubicTo(x + r * 1.05f, y - r * 1.15f, x + r * 1.5f, y + r * 0.1f, x, y + r * 0.95f)
        path.close()
        p.shader = RadialGradient(x, y - r * 0.2f, r * 1.3f, Color.WHITE, LIGHT_EDGE, Shader.TileMode.CLAMP)
        c.drawPath(path, p); p.shader = null
    }

    /** 표정 — 눈 · 입 · 볼. 6초마다 깜빡 */
    private fun drawFace(c: Canvas, x: Float, y: Float, w: Float, t: Float, face: String, mode: Int, alive: Boolean) {
        val ex = w * 0.17f; val er = w * 0.034f
        val blink = alive && (t % 6f) < 0.12f
        val look = when { mode == 2 || face == "thinking" -> -er * 0.6f; else -> 0f }   // 생각: 위를 봄
        val lookX = if (mode == 2 || face == "thinking") er * 0.5f else 0f
        // 볼
        p.color = a(BLUSH, 110)
        for (sd in listOf(-1, 1)) c.drawOval(RectF(x + sd * ex * 1.45f - er * 2f, y + er * 1.6f, x + sd * ex * 1.45f + er * 2f, y + er * 3.1f), p)
        p.color = INK; p.strokeCap = Paint.Cap.ROUND
        val closed = blink || face in setOf("sleep", "tired")
        val smiley = face in setOf("happy", "laugh", "cheer", "excited", "humming", "waving", "proud", "sparkle")
        for (sd in listOf(-1, 1)) {
            val px = x + sd * ex + lookX; val py = y + look
            when {
                face == "heart_eyes" || face == "love" -> { miniHeart(c, px, py, er * 1.4f) ; p.color = INK }
                closed -> { line(c, px - er, py + er * 0.2f, px + er, py + er * 0.2f, er * 0.5f) }
                smiley -> { arc(c, px, py + er * 0.6f, er * 1.1f, 200f, 140f, er * 0.5f) }       // ^ ^
                face in setOf("alert", "surprised") || mode == 1 -> { p.style = Paint.Style.FILL; c.drawCircle(px, py, er * 1.3f, p); shine(c, px, py, er * 1.3f) }
                else -> { p.style = Paint.Style.FILL; c.drawCircle(px, py, er, p); shine(c, px, py, er) }
            }
        }
        // 입
        val my = y + er * 2.6f
        p.color = 0xFFE0607E.toInt()
        when {
            mode == 3 -> { val o = 0.35f + 0.65f * (0.5f + 0.5f * sin(t * 14f)); p.style = Paint.Style.FILL
                c.drawOval(RectF(x - er * 0.9f, my - er * 0.3f, x + er * 0.9f, my + er * (0.3f + 1.1f * o)), p) }
            face in setOf("alert", "surprised") || mode == 1 -> { p.style = Paint.Style.FILL; c.drawOval(RectF(x - er * 0.55f, my - er * 0.2f, x + er * 0.55f, my + er * 0.9f), p) }
            face == "thinking" || mode == 2 -> line(c, x - er * 0.2f, my + er * 0.4f, x + er * 0.9f, my + er * 0.2f, er * 0.4f)
            face == "sleep" -> { p.style = Paint.Style.FILL; c.drawCircle(x, my + er * 0.3f, er * 0.4f, p)
                p.color = a(SKY_HI, 220); p.textSize = er * 3.2f; c.drawText("z", x + w * 0.3f, y - er * 2f - (t % 2f) * er, p) }
            face in setOf("tired", "bored", "worried") -> line(c, x - er * 0.8f, my + er * 0.4f, x + er * 0.8f, my + er * 0.4f, er * 0.4f)
            face == "driving" -> arc(c, x, my - er * 0.4f, er * 0.9f, 30f, 120f, er * 0.4f)
            smiley -> { p.style = Paint.Style.FILL; path.reset(); path.addArc(RectF(x - er * 1.4f, my - er * 1.2f, x + er * 1.4f, my + er * 1.4f), 0f, 180f); path.close(); c.drawPath(path, p) }
            else -> arc(c, x, my - er * 0.9f, er * 1.3f, 30f, 120f, er * 0.4f)   // 기본 미소
        }
        p.style = Paint.Style.FILL
    }

    private fun shine(c: Canvas, x: Float, y: Float, r: Float) { p.color = Color.WHITE; c.drawCircle(x - r * 0.35f, y - r * 0.35f, r * 0.32f, p); p.color = INK }
    private fun line(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, w: Float) { p.style = Paint.Style.STROKE; p.strokeWidth = w; c.drawLine(x0, y0, x1, y1, p); p.style = Paint.Style.FILL }
    private fun arc(c: Canvas, x: Float, y: Float, r: Float, start: Float, sweep: Float, w: Float) {
        p.style = Paint.Style.STROKE; p.strokeWidth = w; c.drawArc(RectF(x - r, y - r, x + r, y + r), start, sweep, false, p); p.style = Paint.Style.FILL
    }
    private fun miniHeart(c: Canvas, x: Float, y: Float, r: Float) {
        p.color = 0xFFFF5C8A.toInt(); p.style = Paint.Style.FILL
        path.reset(); path.moveTo(x, y + r * 0.9f)
        path.cubicTo(x - r * 1.4f, y, x - r, y - r * 1.1f, x, y - r * 0.35f)
        path.cubicTo(x + r, y - r * 1.1f, x + r * 1.4f, y, x, y + r * 0.9f); path.close(); c.drawPath(path, p)
    }
}
