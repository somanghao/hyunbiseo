package io.github.somanghao.makecar.hyun.ui.skin

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 화면 **UI** 층 — 그리기 도구. 배치(HyunView의 draw*)는 "여기에 판·말풍선·글자"만 정하고, 모양은 스킨(colors·style·fonts)이 정한다.
 * 같은 panel()이 기본 스킨에선 둥근 어두운 판, 내쉬 스킨에선 잉크 테두리 + 딱딱한 그림자 판이 된다.
 * 새 모양이 필요하면 SkinStyle에 칸을 더하고 여기서 그 칸을 읽는다(배치 코드는 그대로).
 */
class Kit {
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    var skin: Skin = Skins.BUILTIN
    private val st get() = skin.style
    private val path = Path()
    private val tailPath = Path()

    // ---------- 글자 ----------
    fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, face: Typeface = skin.fonts.body,
             align: Paint.Align = Paint.Align.LEFT, maxW: Float = Float.MAX_VALUE) {
        p.style = Paint.Style.FILL; p.color = color; p.textSize = size; p.typeface = face; p.textAlign = align; p.shader = null
        var line = s
        if (p.measureText(line) > maxW) {
            while (line.isNotEmpty() && p.measureText("$line…") > maxW) line = line.dropLast(1)
            line += "…"
        }
        c.drawText(line, x, y, p)
        p.textAlign = Paint.Align.LEFT
    }

    /** 줄 나누기만(그리지 않음) — 글자 단위, 공백에서 끊을 수 있으면 공백에서(한국어는 어절이 길다) */
    fun lines(s: String, size: Float, maxW: Float, face: Typeface): List<String> {
        p.textSize = size; p.typeface = face
        val out = mutableListOf<String>()
        var rest = s.trim()
        while (rest.isNotEmpty()) {
            var n = p.breakText(rest, true, maxW, null).coerceAtLeast(1)
            if (n < rest.length) rest.lastIndexOf(' ', n).takeIf { it > n / 2 }?.let { n = it + 1 }
            out += rest.substring(0, n).trim(); rest = rest.substring(n).trim()
        }
        return out
    }

    /** 여러 줄 — 반환 = 마지막 줄 다음 줄의 y. center면 x가 가운데 */
    fun wrap(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, maxW: Float, maxLines: Int, face: Typeface = skin.fonts.body,
             center: Boolean = false): Float {
        val all = lines(s, size, maxW, face)
        val shown = all.take(maxLines)
        shown.forEachIndexed { i, line ->
            val last = i == maxLines - 1 && all.size > maxLines
            text(c, if (last) "$line…" else line, x, y + i * size * 1.42f, size, color, face, if (center) Paint.Align.CENTER else Paint.Align.LEFT, maxW = maxW)
        }
        return y + shown.size * size * 1.42f
    }

    /** 이름표 글씨 — 스킨 제목 글꼴 · 기울기(titleTilt). 반환 = 오른쪽 끝 x */
    fun title(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int): Float {
        p.textSize = size; p.typeface = skin.fonts.title
        val w = p.measureText(s)
        if (st.titleTilt != 0f) { c.save(); c.rotate(st.titleTilt, x + w / 2, y - size * 0.35f) }
        text(c, s, x, y, size, color, skin.fonts.title)
        if (st.titleTilt != 0f) c.restore()
        return x + w
    }

    /** 색 배지 하나 — 반환 = 오른쪽 끝 x */
    fun pill(c: Canvas, s: String, x: Float, base: Float, size: Float, color: Int): Float {
        p.textSize = size; p.typeface = skin.fonts.bold
        val tw = p.measureText(s); val ph = size * 1.5f; val padX = size * 0.6f
        p.style = Paint.Style.FILL; p.shader = null; p.color = (color and 0x00FFFFFF) or 0x33000000
        c.drawRoundRect(RectF(x, base - size * 1.12f, x + tw + padX * 2, base - size * 1.12f + ph), ph / 2, ph / 2, p)
        text(c, s, x + padX, base - size * 0.08f, size, color, skin.fonts.bold)
        return x + tw + padX * 2
    }

    // ---------- 판 · 모양 ----------
    /** 판(카드·띠·버튼) — 그림자 → 채우기 → 테두리(스킨 style). rad = 기본 모서리(스킨 radius를 곱함). deco=false면 채우기만(판 안쪽 칸) */
    fun panel(c: Canvas, r: RectF, rad: Float, fill: Int, u: Float, deco: Boolean = true) {
        path.reset(); val rr = rad * st.radius
        path.addRoundRect(r, rr, rr, Path.Direction.CW)
        shape(c, path, fill, u, deco)
    }

    /** 잉크 테두리만(사진·표지 위) — 스킨 border가 0이면 아무것도 안 그림 */
    fun frame(c: Canvas, r: RectF, rad: Float, u: Float) {
        if (st.border <= 0f) return
        val rr = rad * st.radius
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = st.border * u; p.color = st.borderColor
        c.drawRoundRect(r, rr, rr, p); p.style = Paint.Style.FILL
    }

    fun circle(c: Canvas, cx: Float, cy: Float, r: Float, fill: Int, u: Float) {
        path.reset(); path.addCircle(cx, cy, r, Path.Direction.CW); shape(c, path, fill, u, true)
    }

    /** 아무 모양 — 그림자(딱딱한 오프셋) · 채우기 · 잉크 테두리 */
    fun shape(c: Canvas, s: Path, fill: Int, u: Float, deco: Boolean = true) {
        p.shader = null; p.style = Paint.Style.FILL
        if (deco && (st.shadowX != 0f || st.shadowY != 0f)) {
            c.save(); c.translate(st.shadowX * u, st.shadowY * u); p.color = st.shadowColor; c.drawPath(s, p); c.restore()
        }
        p.color = fill; c.drawPath(s, p)
        if (deco && st.border > 0f) {
            p.style = Paint.Style.STROKE; p.strokeWidth = st.border * u; p.strokeJoin = Paint.Join.ROUND; p.color = st.borderColor
            c.drawPath(s, p); p.style = Paint.Style.FILL
        }
    }

    /** 말풍선 — 꼬리(tail -1 왼쪽 · 1 오른쪽 · 0 없음, 스킨 bubbleTail이 꺼져 있으면 없음)를 몸과 한 모양으로 그린다 */
    fun bubble(c: Canvas, r: RectF, rad: Float, fill: Int, u: Float, tail: Int, tailY: Float) {
        path.reset(); val rr = rad * st.radius
        path.addRoundRect(r, rr, rr, Path.Direction.CW)
        if (st.bubbleTail && tail != 0) {
            val tw = u * 0.03f; val th = u * 0.026f
            val ex = if (tail < 0) r.left else r.right
            tailPath.reset()
            tailPath.moveTo(ex - tail * u * 0.02f, tailY - th * 0.5f)
            tailPath.lineTo(ex + tail * tw, tailY - th * 0.15f)
            tailPath.lineTo(ex - tail * u * 0.02f, tailY + th * 0.6f)
            tailPath.close()
            path.op(tailPath, Path.Op.UNION)
        }
        shape(c, path, fill, u, true)
    }

    /**
     * 만화 말풍선 — 몸 kind 0 = 타원(말) · 1 = 뾰족한 외침(안전 경고) · 2 = 구름(생각). 꼬리는 몸 가장자리에서 (tx, ty) 쪽으로
     * (말·외침 = 쐐기, 생각 = 작아지는 동그라미 셋). 글은 타원 안쪽(폭의 약 74%·높이 70%)에 넣는다 — 크기는 부르는 쪽이 정한다
     */
    fun balloon(c: Canvas, r: RectF, fill: Int, u: Float, tx: Float, ty: Float, kind: Int) {
        val cx = r.centerX(); val cy = r.centerY(); val a = r.width() / 2; val b = r.height() / 2
        val th = kotlin.math.atan2((ty - cy) / b, (tx - cx) / a)   // 타원 좌표에서 꼬리 방향
        path.reset()
        when (kind) {
            1 -> {   // 외침 — 타원을 따라 가시
                val n = 22
                for (i in 0 until n * 2) {
                    val ang = i * PI.toFloat() / n
                    val k = if (i % 2 == 0) 1.08f else 0.9f
                    val x = cx + cos(ang) * a * k; val y = cy + sin(ang) * b * k
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
            }
            2 -> {   // 생각 — 타원 + 둘레 구름 방울
                path.addOval(RectF(cx - a * 0.9f, cy - b * 0.86f, cx + a * 0.9f, cy + b * 0.86f), Path.Direction.CW)
                val n = 9; val rr = min(a, b) * 0.36f
                for (i in 0 until n) {
                    val ang = i * 2 * PI.toFloat() / n + 0.3f
                    tailPath.reset(); tailPath.addCircle(cx + cos(ang) * (a - rr * 0.55f), cy + sin(ang) * (b - rr * 0.55f), rr, Path.Direction.CW)
                    path.op(tailPath, Path.Op.UNION)
                }
            }
            else -> path.addOval(r, Path.Direction.CW)
        }
        if (kind == 2) {   // 생각 꼬리 — 작아지는 동그라미 셋
            shape(c, path, fill, u, true)
            val ex = cx + cos(th) * a * 1.05f; val ey = cy + sin(th) * b * 1.05f
            val dx = tx - ex; val dy = ty - ey; val d = max(1f, kotlin.math.hypot(dx, dy)); val step = min(d / 4f, u * 0.03f)
            for (i in 1..3) {
                tailPath.reset(); tailPath.addCircle(ex + dx / d * step * i * 1.2f, ey + dy / d * step * i * 1.2f, u * (0.016f - i * 0.004f), Path.Direction.CW)
                shape(c, tailPath, fill, u, true)
            }
            return
        }
        // 말·외침 꼬리 — 쐐기(몸 안쪽 두 점 → 꼬리 끝)
        val w = 0.2f
        val b1x = cx + cos(th - w) * a * 0.8f; val b1y = cy + sin(th - w) * b * 0.8f
        val b2x = cx + cos(th + w) * a * 0.8f; val b2y = cy + sin(th + w) * b * 0.8f
        val ex = cx + cos(th) * a; val ey = cy + sin(th) * b
        val dx = tx - ex; val dy = ty - ey; val d = max(1f, kotlin.math.hypot(dx, dy))
        val len = min(d * 0.85f, u * 0.065f)
        val tipX = ex + dx / d * len; val tipY = ey + dy / d * len
        tailPath.reset(); tailPath.moveTo(b1x, b1y)
        tailPath.quadTo((b1x + tipX) / 2 + dy / d * u * 0.008f, (b1y + tipY) / 2 - dx / d * u * 0.008f, tipX, tipY)
        tailPath.lineTo(b2x, b2y); tailPath.close()
        path.op(tailPath, Path.Op.UNION)
        shape(c, path, fill, u, true)
    }

    // ---------- 배경 · 만화 효과 ----------
    private var tile: Pair<String, BitmapShader>? = null

    /** 배경 — 바탕색 + 무늬(halftone 망점 · grid 모눈 · none) */
    fun background(c: Canvas, w: Float, h: Float, u: Float) {
        c.drawColor(skin.colors.bg)
        if (st.pattern == "none" || st.pattern.isBlank()) return
        val size = (u * 0.024f).roundToInt().coerceAtLeast(6)
        val key = "${st.pattern}|$size|${st.patternColor}"
        val sh = tile?.takeIf { it.first == key }?.second ?: run {
            val bm = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val bc = Canvas(bm); val bp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = st.patternColor }
            if (st.pattern == "grid") { bp.strokeWidth = max(1f, size * 0.06f); bc.drawLine(0f, 0f, size.toFloat(), 0f, bp); bc.drawLine(0f, 0f, 0f, size.toFloat(), bp) }
            else { bc.drawCircle(size * 0.25f, size * 0.25f, size * 0.16f, bp); bc.drawCircle(size * 0.75f, size * 0.75f, size * 0.16f, bp) }
            BitmapShader(bm, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also { tile = key to it }
        }
        p.style = Paint.Style.FILL; p.shader = sh; c.drawRect(0f, 0f, w, h, p); p.shader = null
    }

    /** 별 터짐(만화 집중 효과) — 캐릭터 뒤. spin = 회전 각(주행 중 대기엔 0) */
    fun burst(c: Canvas, cx: Float, cy: Float, r: Float, u: Float, spin: Float) {
        if (!st.burst) return
        path.reset()
        val n = 14
        for (i in 0 until n * 2) {
            val a = (i * PI / n + spin * PI / 180).toFloat()
            val rr = if (i % 2 == 0) r else r * 0.8f
            val x = cx + cos(a) * rr; val y = cy + sin(a) * rr
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        shape(c, path, st.burstColor, u, true)
    }

    /** 효과음 스티커("쫑긋!") — 기울인 둥근 딱지. 스킨 stickers에 그 상태 글이 있을 때만 */
    fun sticker(c: Canvas, s: String, cx: Float, cy: Float, size: Float, angle: Float, u: Float) {
        if (s.isBlank()) return
        p.textSize = size; p.typeface = skin.fonts.title
        val tw = p.measureText(s); val padX = size * 0.55f; val ph = size * 1.55f
        c.save(); c.rotate(angle, cx, cy)
        panel(c, RectF(cx - tw / 2 - padX, cy - ph / 2, cx + tw / 2 + padX, cy + ph / 2), ph * 0.3f, st.stickerColor, u)
        text(c, s, cx, cy + size * 0.36f, size, st.stickerText, skin.fonts.title, Paint.Align.CENTER)
        c.restore()
    }
}
