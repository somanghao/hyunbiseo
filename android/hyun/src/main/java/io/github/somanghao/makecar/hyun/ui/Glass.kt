package io.github.somanghao.makecar.hyun.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 컨셉 — 포인트로 사는 "다른 앱 같은" 테마 한 벌: 배경 그림(정적 그림 + 천천히 움직이는 빛 + 무늬) · 유리 색 · 카드 모양 · 글꼴 · 캐릭터 표정·소품 · 말투.
 * 댄 11-08 "단순한 색상 변경이 아니라 다른 앱 느낌" · 디자인 = 글래스모피즘(정차·주차·시동은 풍성하게, 주행 중엔 불투명·정지)
 */
class Concept(
    val id: String, val name: String, val tagline: String, val price: Int,
    val bg: IntArray, val blobs: IntArray, val accent: Int, val accent2: Int,
    val fg: Int, val muted: Int, val faint: Int, val glass: Int, val glassA: Int, val solid: Int,
    val shape: Shape, val radius: Float, val heading: Typeface, val body: Typeface, val motif: Motif,
    val idleFace: String, val sticker: String, val greet: String, val pointWord: String = "포인트",
    /** 유리 대신 칠한 카드(기본 Pleos 컨셉 — 시스템 화면과 이질감 없게) · 밝은 바탕(글자 어둡게) · 밤 버전 */
    val solidCards: Boolean = false, val light: Boolean = false, val nightOf: String? = null,
    /** 캐릭터 방 그림(assets/rooms/<id>.webp) — 없으면 코드로 그린 방 */
    val room: String = id,
) {
    enum class Shape { ROUND, CHAMFER, PIXEL }
    /** 그림 위 메시지 카드를 어둡게(밤·네온 장면) */
    val darkCard get() = id in setOf("glass", "camp", "neon", "arcade")
    enum class Motif { NONE, AURORA, WOOD, STARS, GRID }

    companion object {
        private val SANS_B = Typeface.create("sans-serif", Typeface.BOLD)
        private val SANS = Typeface.create("sans-serif", Typeface.NORMAL)
        /** 기본 — Pleos 시스템 화면과 같은 언어: 밝은 회색 그라데이션 · 흰 카드 · 얇은 선 · 절제된 청록 하나(밤엔 깊은 회청색) */
        val PLEOS = Concept("pleos", "Pleos 기본", "차 시스템 화면과 한 몸처럼 — 밝은 카드 · 얇은 선 · 차분한 청록", 0,
            intArrayOf(0xFFF7F8FA.toInt(), 0xFFEEF1F5.toInt(), 0xFFE4E8EE.toInt()),
            intArrayOf(0xFFD9E6F2.toInt(), 0xFFE6EEF6.toInt(), 0xFFDDEFEF.toInt(), 0xFFE9E4F4.toInt()),
            0xFF0E9F94.toInt(), 0xFF2F80ED.toInt(), 0xFF161A21.toInt(), 0xFF5B6472.toInt(), 0xFF97A0AD.toInt(),
            0xFFFFFFFF.toInt(), 0xFF, 0xFFFFFFFF.toInt(), Shape.ROUND, 2.4f, SANS_B, SANS, Motif.NONE, "neutral", "", "",
            solidCards = true, light = true)
        val PLEOS_NIGHT = Concept("pleos", "Pleos 기본", "차 시스템 화면과 한 몸처럼 — 밤에는 깊은 회청색", 0,
            intArrayOf(0xFF151A21.toInt(), 0xFF181E26.toInt(), 0xFF1C232C.toInt()),
            intArrayOf(0xFF1F2A36.toInt(), 0xFF1D2733.toInt(), 0xFF1B2A2C.toInt(), 0xFF231F33.toInt()),
            0xFF2CC5B8.toInt(), 0xFF5B9BFF.toInt(), 0xFFEEF2F6.toInt(), 0xFFA3ACBA.toInt(), 0xFF6B7482.toInt(),
            0xFF222933.toInt(), 0xFF, 0xFF222933.toInt(), Shape.ROUND, 2.4f, SANS_B, SANS, Motif.NONE, "neutral", "", "",
            solidCards = true, light = false)
        val ALL = listOf(
            PLEOS,
            Concept("glass", "오로라 글래스", "투명한 유리 너머 오로라가 흐르는 컨셉", 100,
                intArrayOf(0xFF0A0F24.toInt(), 0xFF1A1442.toInt(), 0xFF07283A.toInt()),
                intArrayOf(0xFF7C5CFF.toInt(), 0xFF00C9B7.toInt(), 0xFFFF5FA2.toInt(), 0xFF3B82F6.toInt()),
                0xFF7DF0FF.toInt(), 0xFFB79CFF.toInt(), 0xFFF5F7FF.toInt(), 0xFFC3C9E0.toInt(), 0xFF8890AE.toInt(),
                0xFFFFFFFF.toInt(), 0x1C, 0xFF141A30.toInt(), Shape.ROUND, 3.2f, SANS_B, SANS, Motif.AURORA, "neutral", "", ""),
            Concept("cafe", "카페 우드", "나무 결 · 따뜻한 조명 · 손글씨 같은 세리프 — 차가 단골 카페가 돼요", 150,
                intArrayOf(0xFF1E120B.toInt(), 0xFF3B2416.toInt(), 0xFF5A3A22.toInt()),
                intArrayOf(0xFFFFB45E.toInt(), 0xFFFF7E4F.toInt(), 0xFFFFD79A.toInt(), 0xFFE3A15C.toInt()),
                0xFFFFC27A.toInt(), 0xFFFFE2B0.toInt(), 0xFFFFF6EA.toInt(), 0xFFEBD3B8.toInt(), 0xFFB79A7E.toInt(),
                0xFFFFEBD0.toInt(), 0x24, 0xFF2C1C12.toInt(), Shape.ROUND, 2.0f, Typeface.create(Typeface.SERIF, Typeface.BOLD), Typeface.create(Typeface.SERIF, Typeface.NORMAL),
                Motif.WOOD, "happy", "☕", "오늘의 블렌드처럼 따뜻하게. "),
            Concept("space", "나이트 크루즈", "별이 흐르는 우주 정거장 관제실 — 각진 패널과 시안 빛", 300,
                intArrayOf(0xFF02030A.toInt(), 0xFF080C26.toInt(), 0xFF140A30.toInt()),
                intArrayOf(0xFF3A2BFF.toInt(), 0xFF00E1FF.toInt(), 0xFFB14BFF.toInt(), 0xFF1A6BFF.toInt()),
                0xFF7DF9FF.toInt(), 0xFFC38BFF.toInt(), 0xFFEAF8FF.toInt(), 0xFF96AACB.toInt(), 0xFF5D6E8C.toInt(),
                0xFF9EE7FF.toInt(), 0x16, 0xFF0A1024.toInt(), Shape.CHAMFER, 2.6f, Typeface.create("sans-serif-condensed", Typeface.BOLD), Typeface.create("sans-serif-condensed", Typeface.NORMAL),
                Motif.STARS, "sparkle", "🪐", "관제탑 현비서, 교신 시작. ", "크레딧"),
            Concept("arcade", "레트로 아케이드", "네온 그리드 · 도트 테두리 · 8비트 감성 — 운전이 게임이 돼요", 450,
                intArrayOf(0xFF0E0019.toInt(), 0xFF26003A.toInt(), 0xFF3A0050.toInt()),
                intArrayOf(0xFFFF2E97.toInt(), 0xFF00F0FF.toInt(), 0xFFFFE14D.toInt(), 0xFF8A2BFF.toInt()),
                0xFFFF4FD8.toInt(), 0xFF00F0FF.toInt(), 0xFFFFFFFF.toInt(), 0xFFD9B8F5.toInt(), 0xFF9C7BBE.toInt(),
                0xFFFF4FD8.toInt(), 0x18, 0xFF1A0428.toInt(), Shape.PIXEL, 0.6f, Typeface.create(Typeface.MONOSPACE, Typeface.BOLD), Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL),
                Motif.GRID, "excited", "🕹️", "PLAYER 1, 준비 완료! ", "코인"),
            Concept("camp", "숲 캠핑", "모닥불 · 텐트 · 소나무 숲 — 차가 주말 캠핑장이 돼요", 200,
                intArrayOf(0xFF14251C.toInt(), 0xFF1F3A2A.toInt(), 0xFF2C4A33.toInt()),
                intArrayOf(0xFFFFB25C.toInt(), 0xFF6FCF97.toInt(), 0xFFFFD58A.toInt(), 0xFF3E8E5E.toInt()),
                0xFFFFB25C.toInt(), 0xFF8FE3B0.toInt(), 0xFFF4FFF6.toInt(), 0xFFC4DCCB.toInt(), 0xFF7E9A86.toInt(),
                0xFFEFFFF2.toInt(), 0x1E, 0xFF1A2B21.toInt(), Shape.ROUND, 2.6f, SANS_B, SANS, Motif.AURORA, "happy", "🏕️", "캠핑 온 기분으로! "),
            Concept("ocean", "바다 서핑", "청량한 파도 · 서핑보드 · 햇살 — 드라이브가 휴가가 돼요", 250,
                intArrayOf(0xFF0B3B5C.toInt(), 0xFF0E5C86.toInt(), 0xFF1C8DB5.toInt()),
                intArrayOf(0xFF5EE7F5.toInt(), 0xFFFFE38A.toInt(), 0xFF3FA9F5.toInt(), 0xFFFFFFFF.toInt()),
                0xFF4FE3F0.toInt(), 0xFFFFE38A.toInt(), 0xFFFFFFFF.toInt(), 0xFFCDEBF7.toInt(), 0xFF8CBBD1.toInt(),
                0xFFE8FBFF.toInt(), 0x22, 0xFF0D3550.toInt(), Shape.ROUND, 3.0f, SANS_B, SANS, Motif.AURORA, "laugh", "🏄", "파도 타러 가요! "),
            Concept("hanok", "한옥 사랑방", "창호지 빛 · 나무 마루 · 단청 포인트 — 차분한 우리 집 사랑방", 350,
                intArrayOf(0xFF2B2018.toInt(), 0xFF4A3526.toInt(), 0xFF6B4C34.toInt()),
                intArrayOf(0xFFFFE2B8.toInt(), 0xFFE0573E.toInt(), 0xFF3C8D7A.toInt(), 0xFFFFF1D6.toInt()),
                0xFFE8664A.toInt(), 0xFF3C8D7A.toInt(), 0xFFFFF8EE.toInt(), 0xFFE6D3BC.toInt(), 0xFFA88F76.toInt(),
                0xFFFFF4E2.toInt(), 0x26, 0xFF33251A.toInt(), Shape.ROUND, 1.6f, Typeface.create(Typeface.SERIF, Typeface.BOLD), Typeface.create(Typeface.SERIF, Typeface.NORMAL),
                Motif.WOOD, "blush", "🏮", "어서 오세요, 사랑방이에요. "),
            Concept("neon", "네온 시티", "비 내리는 밤거리 · 분홍·보라 네온 간판 — 도시의 밤 드라이브", 400,
                intArrayOf(0xFF07061A.toInt(), 0xFF140C33.toInt(), 0xFF22104A.toInt()),
                intArrayOf(0xFFFF3EA5.toInt(), 0xFF7A5CFF.toInt(), 0xFF00E0FF.toInt(), 0xFFFF7A45.toInt()),
                0xFFFF5CB8.toInt(), 0xFF7DE2FF.toInt(), 0xFFF7F2FF.toInt(), 0xFFC4B8E8.toInt(), 0xFF7E73A8.toInt(),
                0xFFE9DDFF.toInt(), 0x16, 0xFF150E2E.toInt(), Shape.CHAMFER, 2.0f, Typeface.create("sans-serif-condensed", Typeface.BOLD), Typeface.create("sans-serif-condensed", Typeface.NORMAL),
                Motif.STARS, "cool", "🌃", "오늘 밤 도시는 우리 거예요. "),
        ).sortedBy { it.price }
        fun of(id: String?, night: Boolean = false): Concept {
            val c = ALL.firstOrNull { it.id == id } ?: PLEOS
            return if (c === PLEOS && night) PLEOS_NIGHT else c
        }
    }
}

/**
 * 글래스 화가 — 배경(정적 그림을 작은 비트맵으로 한 번 + 흐린 판 한 번) · 움직이는 빛 · 무늬 · 유리 카드.
 * 유리 = 카드 모양으로 잘라 흐린 배경을 다시 그리고(투과) + 옅은 색 + 위쪽 하이라이트 + 1px 빛 테두리 + 부드러운 그림자.
 * 프레임마다 블러하지 않는다(흐린 판은 컨셉·크기가 바뀔 때만 다시 만든다).
 */
class Glass {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private var key = ""
    private var sharp: Bitmap? = null
    private var blur: Bitmap? = null
    private var W = 1f; private var H = 1f
    var c: Concept = Concept.ALL[0]
    var still = false                         // 주행 중 — 빛을 멈추고 카드를 불투명하게
    private var t = 0f
    private val stars by lazy { val r = java.util.Random(7); List(140) { floatArrayOf(r.nextFloat(), r.nextFloat(), 0.4f + r.nextFloat() * 1.4f, r.nextFloat() * 6.28f) } }

    fun prepare(w: Float, h: Float, concept: Concept, time: Float) {
        c = concept; W = w; H = h; if (!still) t = time
        val k = "${concept.id}|${w.toInt()}|${h.toInt()}"
        if (k == key) return
        key = k
        val sw = max(64, (w / 4).toInt()); val sh = max(36, (h / 4).toInt())
        val s = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val cv = Canvas(s)
        val q = Paint(Paint.ANTI_ALIAS_FLAG)
        q.shader = LinearGradient(0f, 0f, sw * 0.4f, sh.toFloat(), concept.bg, null, Shader.TileMode.CLAMP)
        cv.drawRect(0f, 0f, sw.toFloat(), sh.toFloat(), q)
        // 정적 빛 덩어리(배경의 큰 색 흐름)
        val spots = listOf(0.12f to 0.25f, 0.85f to 0.15f, 0.7f to 0.9f, 0.3f to 0.85f)
        spots.forEachIndexed { i, (x, y) ->
            val col = concept.blobs[i % concept.blobs.size]; val r = sw * 0.45f
            q.shader = RadialGradient(sw * x, sh * y, r, a(col, if (concept.motif == Concept.Motif.NONE) 90 else 120), a(col, 0), Shader.TileMode.CLAMP); cv.drawCircle(sw * x, sh * y, r, q)
        }
        q.shader = null
        sharp = s
        // 흐린 판 — 더 줄였다가(1/16) 상자 블러 3번
        val bw = max(24, sw / 4); val bh = max(14, sh / 4)
        val b = Bitmap.createScaledBitmap(s, bw, bh, true).copy(Bitmap.Config.ARGB_8888, true)
        boxBlur(b, 2); boxBlur(b, 2); boxBlur(b, 2)
        blur = b
    }

    /** 바탕 전체 — 정적 그림 + 움직이는 빛 + 컨셉 무늬 */
    fun background(cv: Canvas) {
        sharp?.let { cv.drawBitmap(it, null, RectF(0f, 0f, W, H), bp) }
        if (c.motif == Concept.Motif.NONE) return
        blobs(cv, 1f, 1f)
        motif(cv)
    }

    private fun blobs(cv: Canvas, scale: Float, alpha: Float) {
        val m = if (still) 0f else t
        for (i in 0 until 3) {
            val col = c.blobs[(i + 1) % c.blobs.size]
            val x = W * (0.25f + 0.5f * (0.5f + 0.5f * sin(m * 0.11f + i * 2.1f)))
            val y = H * (0.3f + 0.4f * (0.5f + 0.5f * cos(m * 0.09f + i * 1.7f)))
            val r = min(W, H) * (0.42f + 0.06f * i) * scale
            p.shader = RadialGradient(x, y, r, a(col, (70 * alpha).toInt()), a(col, 0), Shader.TileMode.CLAMP)
            cv.drawCircle(x, y, r, p)
        }
        p.shader = null
    }

    private fun motif(cv: Canvas) {
        when (c.motif) {
            Concept.Motif.STARS -> {
                for (s in stars) {
                    val tw = if (still) 0.8f else 0.55f + 0.45f * sin(t * 1.6f + s[3])
                    p.color = a(Color.WHITE, (200 * tw).toInt()); cv.drawCircle(s[0] * W, s[1] * H, s[2] * H / 900f * 1.6f, p)
                }
                // 행성 고리
                val px = W * 0.9f; val py = H * 1.05f; val pr = H * 0.42f
                p.shader = RadialGradient(px - pr * 0.3f, py - pr * 0.4f, pr * 1.2f, 0xFF3C2C8C.toInt(), 0xFF0A0A20.toInt(), Shader.TileMode.CLAMP); cv.drawCircle(px, py, pr, p); p.shader = null
                p.style = Paint.Style.STROKE; p.strokeWidth = H * 0.006f; p.color = a(c.accent, 90)
                cv.drawOval(RectF(px - pr * 1.6f, py - pr * 0.42f, px + pr * 1.6f, py + pr * 0.1f), p); p.style = Paint.Style.FILL
            }
            Concept.Motif.WOOD -> {
                // 아래쪽 나무 탁자 + 결 · 위쪽 따뜻한 전구 보케
                val top = H * 0.78f
                p.shader = LinearGradient(0f, top, 0f, H, 0xFF6B4226.toInt(), 0xFF3A2414.toInt(), Shader.TileMode.CLAMP); cv.drawRect(0f, top, W, H, p); p.shader = null
                p.style = Paint.Style.STROKE; p.strokeWidth = H * 0.0018f
                for (i in 0 until 14) {
                    val y = top + (H - top) * (i + 0.5f) / 14
                    p.color = a(0xFF2A160B.toInt(), 90)
                    path.reset(); path.moveTo(0f, y)
                    var x = 0f; while (x < W) { path.quadTo(x + W * 0.05f, y + sin(x * 0.01f + i) * H * 0.004f, x + W * 0.1f, y); x += W * 0.1f }
                    cv.drawPath(path, p)
                }
                p.style = Paint.Style.FILL
                for (i in 0 until 7) {
                    val x = W * (0.08f + i * 0.14f); val y = H * (0.07f + 0.04f * sin(i * 1.3f))
                    val fl = if (still) 1f else 0.85f + 0.15f * sin(t * 2f + i)
                    p.shader = RadialGradient(x, y, H * 0.07f, a(0xFFFFC27A.toInt(), (150 * fl).toInt()), a(0xFFFFC27A.toInt(), 0), Shader.TileMode.CLAMP); cv.drawCircle(x, y, H * 0.07f, p)
                    p.shader = null; p.color = a(0xFFFFE6BF.toInt(), 230); cv.drawCircle(x, y, H * 0.006f, p)
                }
            }
            Concept.Motif.GRID -> {
                // 네온 해 + 원근 격자 바닥 + 주사선
                val hz = H * 0.62f
                val sx = W * 0.5f; val sr = H * 0.26f
                p.shader = LinearGradient(0f, hz - sr, 0f, hz, 0xFFFFE14D.toInt(), 0xFFFF2E97.toInt(), Shader.TileMode.CLAMP)
                cv.save(); cv.clipRect(0f, 0f, W, hz); cv.drawCircle(sx, hz, sr, p); cv.restore(); p.shader = null
                p.color = c.bg[1]
                for (i in 0 until 5) { val y = hz - sr * (0.1f + i * 0.17f); cv.drawRect(sx - sr, y, sx + sr, y + sr * 0.035f * (1 + i * 0.3f), p) }
                p.color = 0xFF14001F.toInt(); cv.drawRect(0f, hz, W, H, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = H * 0.0022f; p.color = a(c.accent, 200)
                val shift = if (still) 0f else (t * 0.35f) % 1f
                for (i in 0 until 12) { val k = (i + shift) / 12f; val y = hz + (H - hz) * k * k; cv.drawLine(0f, y, W, y, p) }
                for (i in -14..14) { cv.drawLine(sx + i * W * 0.012f, hz, sx + i * W * 0.11f, H, p) }
                p.style = Paint.Style.FILL
                p.color = 0x14000000
                var y = 0f; while (y < H) { cv.drawRect(0f, y, W, y + 1.5f, p); y += 5f }
            }
            Concept.Motif.NONE -> {}
            Concept.Motif.AURORA -> {
                // 오로라 띠 두 줄
                for (i in 0 until 2) {
                    val col = c.blobs[i]
                    path.reset(); path.moveTo(0f, H * (0.35f + i * 0.12f))
                    var x = 0f
                    while (x <= W) { path.lineTo(x, H * (0.35f + i * 0.12f) + sin(x / W * 6f + (if (still) 0f else t * 0.3f) + i) * H * 0.05f); x += W / 40 }
                    path.lineTo(W, 0f); path.lineTo(0f, 0f); path.close()
                    p.shader = LinearGradient(0f, H * 0.1f, 0f, H * (0.42f + i * 0.12f), a(col, 0), a(col, 46), Shader.TileMode.CLAMP)
                    cv.drawPath(path, p); p.shader = null
                }
            }
        }
    }

    /** 카드 모양 경로 — 둥근 · 모서리 깎은(우주) · 도트(아케이드) */
    fun shape(r: RectF, rad: Float): Path {
        path.reset()
        when (c.shape) {
            Concept.Shape.ROUND -> path.addRoundRect(r, rad, rad, Path.Direction.CW)
            Concept.Shape.CHAMFER -> {
                val k = rad * 1.2f
                path.moveTo(r.left + k, r.top); path.lineTo(r.right - k * 0.4f, r.top); path.lineTo(r.right, r.top + k * 0.4f)
                path.lineTo(r.right, r.bottom - k); path.lineTo(r.right - k, r.bottom); path.lineTo(r.left + k * 0.4f, r.bottom)
                path.lineTo(r.left, r.bottom - k * 0.4f); path.lineTo(r.left, r.top + k); path.close()
            }
            Concept.Shape.PIXEL -> {
                val k = max(4f, rad)
                path.moveTo(r.left + k, r.top); path.lineTo(r.right - k, r.top); path.lineTo(r.right - k, r.top + k); path.lineTo(r.right, r.top + k)
                path.lineTo(r.right, r.bottom - k); path.lineTo(r.right - k, r.bottom - k); path.lineTo(r.right - k, r.bottom); path.lineTo(r.left + k, r.bottom)
                path.lineTo(r.left + k, r.bottom - k); path.lineTo(r.left, r.bottom - k); path.lineTo(r.left, r.top + k); path.lineTo(r.left + k, r.top + k); path.close()
            }
        }
        return path
    }

    /**
     * 유리 카드. strength 0..1(1 = 기본, 높이면 더 진하게) · tint = 카드에 살짝 섞을 색(없으면 컨셉 유리색).
     * 주행 중(still)엔 불투명한 판(가독성 · 움직임 없음)
     */
    fun card(cv: Canvas, r: RectF, rad: Float, tint: Int? = null, strength: Float = 1f, S: Float) {
        val sp = Path(shape(r, rad))
        if (c.solidCards) {   // Pleos 기본 — 칠한 카드 + 부드러운 그림자 + 1px 선
            p.shader = null; p.style = Paint.Style.FILL
            p.color = c.solid; p.setShadowLayer(S * 1.6f, 0f, S * 0.5f, if (c.light) 0x1F1B2A3D else 0x55000000); cv.drawPath(sp, p); p.clearShadowLayer()
            tint?.let { p.color = a(it, if (c.light) 16 else 22); cv.drawPath(sp, p) }
            p.style = Paint.Style.STROKE; p.strokeWidth = max(1f, S * 0.1f); p.color = if (c.light) 0x1A0F1A2A else 0x1FFFFFFF
            cv.drawPath(sp, p); p.style = Paint.Style.FILL
            return
        }
        // 그림자
        p.shader = null; p.style = Paint.Style.FILL
        if (c.shape == Concept.Shape.PIXEL) { p.color = 0x99000000.toInt(); cv.save(); cv.translate(S * 0.6f, S * 0.6f); cv.drawPath(sp, p); cv.restore() }
        else { p.color = 0x01000000; p.setShadowLayer(S * 2.4f, 0f, S * 0.9f, 0x66000000); cv.drawPath(sp, p); p.clearShadowLayer() }
        cv.save(); cv.clipPath(sp)
        if (still) {
            p.color = c.solid; cv.drawRect(r, p)
            tint?.let { p.color = a(it, 26); cv.drawRect(r, p) }
        } else {
            blur?.let { cv.drawBitmap(it, null, RectF(0f, 0f, W, H), bp) }
            blobs(cv, 1.25f, 0.8f)
            p.color = a(c.glass, (c.glassA * strength).toInt().coerceAtMost(255)); cv.drawRect(r, p)
            tint?.let { p.color = a(it, 30); cv.drawRect(r, p) }
            // 글자 대비용 어두운 막(밝은 배경 위에서도 4.5:1)
            p.color = 0x33000000; cv.drawRect(r, p)
            // 위쪽 빛
            p.shader = LinearGradient(0f, r.top, 0f, r.top + min(r.height(), S * 14f), a(Color.WHITE, 34), a(Color.WHITE, 0), Shader.TileMode.CLAMP)
            cv.drawRect(r, p); p.shader = null
        }
        cv.restore()
        // 빛 테두리
        p.style = Paint.Style.STROKE
        p.strokeWidth = if (c.shape == Concept.Shape.PIXEL) S * 0.3f else S * 0.13f
        p.shader = if (c.shape == Concept.Shape.PIXEL) null else LinearGradient(r.left, r.top, r.right, r.bottom, a(Color.WHITE, if (still) 40 else 110), a(Color.WHITE, 14), Shader.TileMode.CLAMP)
        p.color = if (c.shape == Concept.Shape.PIXEL) a(tint ?: c.accent, 220) else Color.WHITE
        if (c.shape == Concept.Shape.CHAMFER) { p.shader = null; p.color = a(tint ?: c.accent, if (still) 90 else 150) }
        cv.drawPath(sp, p)
        p.shader = null; p.style = Paint.Style.FILL
    }

    private val thumbs = HashMap<String, Bitmap>()
    /** 상점 썸네일 — 그 컨셉의 바탕 그림(정지)을 한 번 그려 둔다 */
    fun preview(cv: Canvas, r: RectF, concept: Concept, S: Float) {
        val k = "${concept.id}|${r.width().toInt()}|${r.height().toInt()}"
        val bm = thumbs.getOrPut(k) {
            val g = Glass(); g.still = true
            val w = r.width().toInt().coerceAtLeast(8); val h = r.height().toInt().coerceAtLeast(8)
            g.prepare(w * 2f, h * 2f, concept, 0f)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b -> val bc = Canvas(b); bc.scale(0.5f, 0.5f); g.background(bc) }
        }
        cv.save(); cv.clipPath(Path(shape(r, S * 2f))); cv.drawBitmap(bm, null, r, bp); cv.restore()
    }

    companion object {
        fun a(col: Int, al: Int) = (col and 0x00FFFFFF) or (al.coerceIn(0, 255) shl 24)

        /** 작은 비트맵 상자 블러(가로·세로) — 제자리 */
        fun boxBlur(b: Bitmap, r: Int) {
            val w = b.width; val h = b.height
            val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
            val out = IntArray(w * h)
            fun pass(src: IntArray, dst: IntArray, horiz: Boolean) {
                val n = if (horiz) w else h; val m = if (horiz) h else w
                for (j in 0 until m) for (i in 0 until n) {
                    var ra = 0; var rr = 0; var rg = 0; var rb = 0; var cnt = 0
                    for (d in -r..r) {
                        val k = (i + d).coerceIn(0, n - 1)
                        val v = if (horiz) src[j * w + k] else src[k * w + j]
                        ra += v ushr 24; rr += (v shr 16) and 0xFF; rg += (v shr 8) and 0xFF; rb += v and 0xFF; cnt++
                    }
                    val idx = if (horiz) j * w + i else i * w + j
                    dst[idx] = ((ra / cnt) shl 24) or ((rr / cnt) shl 16) or ((rg / cnt) shl 8) or (rb / cnt)
                }
            }
            pass(px, out, true); pass(out, px, false)
            b.setPixels(px, 0, w, 0, 0, w, h)
        }
    }
}
