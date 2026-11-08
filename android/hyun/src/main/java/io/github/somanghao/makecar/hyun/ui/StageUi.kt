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
import android.os.SystemClock
import io.github.somanghao.makecar.hyun.drive.CarCheck
import io.github.somanghao.makecar.hyun.feature.Sit
import io.github.somanghao.makecar.hyun.feature.StageModel
import io.github.somanghao.makecar.hyun.feature.Tile
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 차 가로 화면 — 캐릭터 컴패니언. 디자인 정본 = docs/hyun-design.md(레퍼런스 조사 스펙 11-08).
 *  - 장면 하나에 주인공 하나: 화면 전체 = 컨셉 배경 그림(assets/chars/room_<컨셉>.webp, 오른쪽 40%는 비어 있음),
 *    왼쪽 7칸(~58%) = 캐릭터(바닥선 하단 1/3, 키 50~60%) · 오른쪽 5칸 = 메시지 카드 1장(상황 칩 + 제목 1줄 + 본문 ≤2줄 + 주 버튼 1 + 보조 글 버튼)
 *  - 상태는 색·빛·표정: 눈빛 후광(듣기 시안 · 생각 펄스 · 경고 앰버) · 숨쉬기 1.00↔1.015/4초 · 깜빡임 4~7초
 *  - 메시지: 캐릭터 반응 먼저 → 120ms 뒤 카드가 12px 올라오며 나타남(320ms) · 주행 중엔 큰 움직임 없음, 카드는 제목+아이콘만
 *  - Pretendard(OFL, assets/fonts) · 라이트/야간 토큰 · 카드 반경 28 · 우상단 알약 하나(포인트·테마)
 *  - 테마 인트로(시동)·마무리(시동 끔) · 해금 1.2초 문 열림 · 잠긴 테마 10초 미리보기
 */
class StageUi(private val v: HyunView, private val m: StageModel, private val act: (String) -> Unit) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glass = Glass()
    private val hits = mutableListOf<Pair<RectF, String>>()
    private val path = Path()
    private var S = 10f          // 화면 높이/100 (예전 코드 호환)
    private var U = 1f           // 스펙 px → 화면 px (높이 1000 기준)
    private var taps = 0; private var tapAt = 0L
    private val BLOCKING = setOf("onboard", "deco", "settings")

    // ---------- 글꼴(Pretendard) ----------
    private val fonts = HashMap<String, Typeface>()
    private fun font(w: String): Typeface = fonts.getOrPut(w) {
        runCatching { Typeface.createFromAsset(v.context.assets, "fonts/Pretendard-$w.otf") }.getOrElse { Typeface.create("sans-serif", if (w == "Bold" || w == "SemiBold") Typeface.BOLD else Typeface.NORMAL) }
    }
    private val bold get() = font("Bold")
    private val semi get() = font("SemiBold")
    private val medium get() = font("Medium")
    private val body get() = font("Regular")

    // ---------- 색 토큰(스펙) — 밝은 카드 / 어두운 카드 ----------
    private var k: Concept = Concept.ALL[0]
    private var darkUi = false
    private val BG get() = if (darkUi) 0xFF0E1116.toInt() else 0xFFF4F6F9.toInt()
    private val SURF get() = if (darkUi) 0xFF171B22.toInt() else 0xFFFFFFFF.toInt()
    private val SURF2 get() = if (darkUi) 0xFF1F2530.toInt() else 0xFFEEF1F5.toInt()
    private val FG get() = if (darkUi) 0xFFF2F4F7.toInt() else 0xFF111827.toInt()
    private val MUTED get() = if (darkUi) 0xFF9AA3B2.toInt() else 0xFF5B6472.toInt()
    private val FAINT get() = if (darkUi) 0xFF6B7482.toInt() else 0xFF8C95A3.toInt()
    private val LINE get() = if (darkUi) 0xFF2A313C.toInt() else 0xFFE3E7ED.toInt()
    private val ACC get() = if (k.id == "pleos") (if (darkUi) 0xFF4C8DFF.toInt() else 0xFF1E6BFF.toInt()) else k.accent
    private val ACC_SOFT get() = if (darkUi) Glass.a(ACC, 46) else Glass.a(ACC, 28)
    private val GOOD = 0xFF12B886.toInt()
    private val WARN = 0xFFF59F00.toInt()
    private val RED = 0xFFE5484D.toInt()
    private val INFO get() = ACC
    private val GLOW get() = if (darkUi) 0xFF6FD3FF.toInt() else 0xFF4FC3FF.toInt()
    // 예전 코드(온보딩·설정)가 쓰는 이름
    private val CARD get() = SURF

    private fun sitColor(s: Sit) = when (s) {
        Sit.IGNITION -> WARN; Sit.READY -> ACC; Sit.DRIVE -> ACC; Sit.STOP -> 0xFFF07C2A.toInt(); Sit.PARK -> 0xFF7C5CFF.toInt(); Sit.OFF -> MUTED
    }
    private fun tone(t: String) = when (t) { "warn" -> WARN; "good" -> GOOD; "muted" -> MUTED; else -> ACC }

    fun animating() = m.sit == Sit.DRIVE && ((m.status?.turn?.v ?: 0) != 0 || SystemClock.elapsedRealtime() - m.carEventAt < 6500)

    // ---------- 배경 그림 · 흐린 판(카드 뒤) ----------
    private var artKey = ""
    private var art: Bitmap? = null
    private var artBlur: Bitmap? = null
    private val bp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val artRect = RectF()

    private fun loadArt(con: Concept) {
        val name = "room_${con.room}${if (con.id == "pleos" && darkUi) "_night" else ""}.webp"
        if (name == artKey && art != null) return
        val a = v.asset(name) ?: return
        artKey = name; art = a
        artBlur = runCatching {
            val small = Bitmap.createScaledBitmap(a, max(16, a.width / 12), max(9, a.height / 12), true).copy(Bitmap.Config.ARGB_8888, true)
            Glass.boxBlur(small, 2); Glass.boxBlur(small, 2); small
        }.getOrNull()
    }

    private fun drawArt(c: Canvas, w: Float, h: Float, bmp: Bitmap) {
        val sc = max(w / bmp.width, h / bmp.height)
        val bw = bmp.width * sc; val bh = bmp.height * sc
        artRect.set((w - bw) * 0.35f, (h - bh) / 2, (w - bw) * 0.35f + bw, (h - bh) / 2 + bh)
        c.drawBitmap(bmp, null, artRect, bp)
    }

    // ======================================================================
    fun draw(c: Canvas, w: Float, h: Float, t: Float): Boolean {
        hits.clear()
        S = h / 100f; U = h / 1000f
        m.night = (v.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        k = m.look()
        darkUi = if (k.id == "pleos") (m.nightOverride ?: m.night) else !k.light && k.darkCard
        loadArt(k)
        val a = art
        if (a != null && artKey.startsWith("room_${k.room}")) drawArt(c, w, h, a) else ambient(c, RectF(0f, 0f, w, h), k, darkUi)
        when (m.overlay) {
            "onboard" -> { scrim(c, w, h, 0.55f); onboard(c, w, h, t); return true }
            "deco" -> { shop(c, w, h, t); return true }
        }
        val driving = m.sit == Sit.DRIVE
        val now = SystemClock.elapsedRealtime()
        // 테마 인트로(시동 직후 1.6초) · 마무리(시동 끔)
        val intro = (m.sit == Sit.IGNITION && now - m.sitAt < 1600)
        if (m.sit == Sit.OFF) scrim(c, w, h, ((now - m.sitAt) / 1500f).coerceIn(0f, 0.35f))
        // 캐릭터
        val chH = h * (if (driving) 0.62f else 0.56f)
        val cx = w * (if (driving) 0.33f else 0.29f); val feet = h * 0.87f
        val react = m.reactFace.takeIf { now - m.reactAt < 1200 }
        val face = when { intro -> "waving"; m.sit == Sit.OFF && now - m.sitAt > 2500 -> "sleep"; react != null && v.mode == HyunView.Mode.READY -> react; driving -> "driving"; else -> v.botFace() }
        character(c, cx, feet, chH, t, face, alive = !driving)
        topRight(c, w)
        if (intro) {   // 인트로 — 컨셉 이름이 떴다 사라진다
            val x = ((now - m.sitAt) / 1600f)
            val al = (if (x < 0.25f) x / 0.25f else if (x > 0.75f) (1 - x) / 0.25f else 1f).coerceIn(0f, 1f)
            c.saveLayerAlpha(0f, 0f, w, h, (al * 255).toInt())
            text(c, "현비서", w * 0.72f, h * 0.46f, 72 * U, if (darkUi || !k.light) Color.WHITE else FG, bold, Paint.Align.CENTER)
            text(c, k.name, w * 0.72f, h * 0.46f + 48 * U, 26 * U, if (darkUi || !k.light) 0xDDFFFFFF.toInt() else MUTED, medium, Paint.Align.CENTER)
            c.restore()
        } else {
            val card = v.card
            val sheetOn = m.overlay in setOf("brief", "todo", "car", "log")
            if (driving) driveCard(c, RectF(w * 0.6f, 96 * U, w - 48 * U, h - 48 * U))
            else {
                badges(c)
                val bottom = h - 48 * U - 64 * U - 20 * U
                val full = RectF(w * 0.6f, 96 * U, w - 48 * U, bottom)
                when {
                    sheetOn -> sheet(c, full, m.overlay!!)
                    card != null -> { glassCard(c, full, 28 * U); val lt = v.lightTheme; v.lightTheme = !darkUi; v.listCard(c, card, full.left + 24 * U, full.top + 24 * U, full.right - 24 * U, full.bottom - 24 * U, h); v.lightTheme = lt }
                    else -> {
                        val nh = 170 * U
                        messageCard(c, RectF(full.left, full.top, full.right, full.bottom - nh - 20 * U))
                        nextUp(c, RectF(full.left, full.bottom - nh, full.right, full.bottom))
                    }
                }
                dock(c, w, h)
            }
            micButton(c, w, h)
        }
        if (m.unlockAt > 0 && now - m.unlockAt < 1200) doors(c, w, h, (now - m.unlockAt) / 1200f)
        when (m.overlay) { "settings" -> settings(c, w, h); "debug" -> debug(c, w, h) }
        toast(c, w, h)
        return true
    }

    fun touch(x: Float, y: Float): Boolean {
        val hit = hits.lastOrNull { (r, _) -> RectF(r).apply { inset(-S * 0.6f, -S * 0.6f) }.contains(x, y) } ?: return m.overlay in BLOCKING
        val a = hit.second
        if (a == "sitpill") {
            val now = SystemClock.elapsedRealtime()
            taps = if (now - tapAt < 1500) taps + 1 else 1; tapAt = now
            if (taps >= 3) { taps = 0; act("debug") }
            return true
        }
        if (a != "noop") act(a); return true
    }

    private fun scrim(c: Canvas, w: Float, h: Float, a: Float) { p.shader = null; p.color = Glass.a(Color.BLACK, (a * 255).toInt()); c.drawRect(0f, 0f, w, h, p) }

    /** 카드 — 그림 위면 뒤를 흐리게 비친 흰 72%(어두운 컨셉은 면 82%) · 라이트 그림자 0 8 24 · 1px 선 */
    private fun glassCard(c: Canvas, r: RectF, rad: Float, tint: Int? = null) {
        val sp = Path().apply { addRoundRect(r, rad, rad, Path.Direction.CW) }
        p.shader = null; p.style = Paint.Style.FILL
        if (!darkUi) { p.color = SURF; p.setShadowLayer(24 * U, 0f, 8 * U, 0x14111827); c.drawPath(sp, p); p.clearShadowLayer() }
        val blur = artBlur
        c.save(); c.clipPath(sp)
        if (blur != null && art != null) {
            c.drawBitmap(blur, null, artRect, bp)
            p.color = if (darkUi) Glass.a(SURF, 210) else Glass.a(Color.WHITE, 184); c.drawRect(r, p)
        } else { p.color = SURF; c.drawRect(r, p) }
        tint?.let { p.color = Glass.a(it, if (darkUi) 30 else 18); c.drawRect(r, p) }
        c.restore()
        p.style = Paint.Style.STROKE; p.strokeWidth = max(1f, U); p.color = if (blur != null && !darkUi) 0x99FFFFFF.toInt() else LINE; c.drawPath(sp, p); p.style = Paint.Style.FILL
    }

    /** 바탕 — 그림 없이 절제된 그라데이션 + 큰 부드러운 빛 둘(무늬·도형 없음). 기본은 Pleos 밝은 회색(밤엔 깊은 회청) */
    private fun ambient(c: Canvas, r: RectF, con: Concept, night: Boolean) {
        p.shader = null; p.style = Paint.Style.FILL
        if (con.id == "pleos") {
            val a = if (night) 0xFF12161C.toInt() else 0xFFF6F7F9.toInt(); val b = if (night) 0xFF0C0F14.toInt() else 0xFFEDF0F4.toInt()
            p.color = Color.WHITE; p.shader = LinearGradient(r.left, r.top, r.left, r.bottom, a, b, Shader.TileMode.CLAMP); c.drawRect(r, p)
            val acc = if (night) 0xFF4C8DFF.toInt() else 0xFF1E6BFF.toInt()
            p.shader = RadialGradient(r.left + r.width() * 0.29f, r.top + r.height() * 0.55f, r.height() * 0.6f, Glass.a(acc, if (night) 30 else 14), Glass.a(acc, 0), Shader.TileMode.CLAMP); c.drawRect(r, p)
            p.shader = null; return
        }
        p.color = Color.WHITE
        p.shader = LinearGradient(r.left, r.top, r.right, r.bottom, con.bg, null, Shader.TileMode.CLAMP); c.drawRect(r, p)
        p.shader = RadialGradient(r.left + r.width() * 0.28f, r.top + r.height() * 0.62f, r.height() * 0.75f, Glass.a(con.blobs[0], 70), Glass.a(con.blobs[0], 0), Shader.TileMode.CLAMP); c.drawRect(r, p)
        p.shader = RadialGradient(r.left + r.width() * 0.85f, r.top + r.height() * 0.1f, r.height() * 0.8f, Glass.a(con.blobs[1], 50), Glass.a(con.blobs[1], 0), Shader.TileMode.CLAMP); c.drawRect(r, p)
        p.shader = LinearGradient(r.left, r.bottom - r.height() * 0.3f, r.left, r.bottom, 0x00000000, 0x33000000, Shader.TileMode.CLAMP); c.drawRect(r, p)
        p.shader = null
    }

    // ======================================================================
    // 캐릭터 — 바닥 그림자 · 눈빛 후광(상태 색) · 숨쉬기 · 소품(그림)
    // ======================================================================
    private fun character(c: Canvas, cx: Float, feet: Float, size: Float, t: Float, face: String, alive: Boolean) {
        val breathe = if (alive) 1f + 0.0075f * (1f + sin(t * (2 * Math.PI.toFloat() / 4f))) else 1f
        val s = size * breathe
        val cy = feet - s * 0.5f
        // 바닥 그림자
        p.color = Color.WHITE
        c.save(); c.scale(1f, 0.18f, cx, feet - s * 0.06f)
        p.shader = RadialGradient(cx, feet - s * 0.06f, s * 0.3f, 0x30000000, 0x00000000, Shader.TileMode.CLAMP)
        c.drawCircle(cx, feet - s * 0.06f, s * 0.3f, p); p.shader = null; c.restore()
        // 눈빛 후광 — 듣기 시안 · 생각 펄스 · 말하기 은은 · 경고 앰버
        val (gc, ga) = when {
            v.mode == HyunView.Mode.LISTENING -> GLOW to (if (darkUi) 150 else 110)
            v.mode == HyunView.Mode.THINKING -> GLOW to (60 + (50 * (0.5f + 0.5f * sin(t * 5f))).toInt())
            face == "alert" -> WARN to 120
            v.mode == HyunView.Mode.SPEAKING -> ACC to 70
            else -> GLOW to (if (darkUi) 55 else 30)
        }
        p.shader = RadialGradient(cx, cy, s * 0.62f, Glass.a(gc, ga), Glass.a(gc, 0), Shader.TileMode.CLAMP); c.drawCircle(cx, cy, s * 0.62f, p); p.shader = null
        v.asset("bot_$face.webp")?.let { bm -> p.isFilterBitmap = true; p.color = Color.WHITE; c.drawBitmap(bm, null, RectF(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2), p) }
        // 날씨 소품(그림) — 우산·목도리·차 한 잔·선글라스
        val spots = listOf(-0.52f to -0.12f, 0.5f to 0.22f, -0.5f to 0.3f, 0.48f to -0.2f)
        m.weatherStickers.mapNotNull { PROPS[it] }.distinct().take(4).forEachIndexed { i, prop ->
            val (sx, sy) = spots[i]
            v.asset("prop_$prop.webp")?.let { bm -> val ps = s * 0.26f; val wob = if (alive) sin(t * 1.6f + i) * 3f else 0f
                c.save(); c.rotate(wob + (if (i % 2 == 0) -8f else 8f), cx + s * sx, cy + s * sy); p.color = Color.WHITE
                c.drawBitmap(bm, null, RectF(cx + s * sx - ps / 2, cy + s * sy - ps / 2, cx + s * sx + ps / 2, cy + s * sy + ps / 2), p); c.restore() }
        }
        // 방 소품(꾸미기) — 캐릭터 둘레 바닥에
        val dspots = mapOf("plant" to (-0.95f to 0.0f), "bear" to (0.78f to 0.08f), "cat" to (-0.72f to 0.1f), "lamp" to (1.05f to -0.12f), "balloon" to (-0.62f to -0.62f), "frame" to (0.85f to -0.6f))
        for (d in StageModel.DECOR) if (d.id in m.decor) dspots[d.id]?.let { (fx, fy) ->
            v.asset("prop_${d.id}.webp")?.let { bm -> val ps = s * (if (d.id == "lamp" || d.id == "plant") 0.42f else 0.3f); val bx = cx + s * fx; val by = feet + s * fy - ps / 2
                val bob = if (alive && d.id == "balloon") sin(t * 1.2f) * S else 0f
                p.color = Color.WHITE; c.drawBitmap(bm, null, RectF(bx - ps / 2, by - ps / 2 + bob, bx + ps / 2, by + ps / 2 + bob), p) }
        }
    }

    // ======================================================================
    // 우상단 알약 하나 — ★ 포인트 · 테마 (+ 작은 설정)
    // ======================================================================
    private fun topRight(c: Canvas, w: Float) {
        val h = 48 * U; val cy = 48 * U
        val g = RectF(w - 48 * U - h, cy - h / 2, w - 48 * U, cy + h / 2)
        glassCard(c, g, h / 2); icon(c, "gear", g.centerX(), cy, 22 * U, MUTED); hits += g to "settings"
        val lab = "${m.points}  ·  테마"
        p.textSize = 22 * U; p.typeface = semi
        val wd = p.measureText(lab) + 64 * U
        val r = RectF(g.left - 12 * U - wd, cy - h / 2, g.left - 12 * U, cy + h / 2)
        glassCard(c, r, h / 2)
        icon(c, "star", r.left + 26 * U, cy, 22 * U, WARN)
        text(c, lab, r.left + 44 * U, cy + 8 * U, 22 * U, FG, semi)
        hits += r to "deco"
        // 두뇌 연결 — 작은 점
        p.color = when (v.brain) { true -> GOOD; false -> WARN; null -> FAINT }; c.drawCircle(r.left - 16 * U, cy, 5 * U, p)
    }

    // ======================================================================
    // 메시지 카드 — 상황 칩 + 제목 1줄 + 본문 ≤2줄 + 주 버튼 1 + 보조 글 버튼
    // ======================================================================
    private fun messageCard(c: Canvas, zone: RectF) {
        val list = m.tiles
        val tl = list.getOrNull(m.heroIdx.coerceIn(0, max(0, list.size - 1)))
        val pad = 40 * U
        val iw = zone.width() - pad * 2
        val title = tl?.value?.let { firstSentence(it) } ?: m.headline
        val bodyText = tl?.value?.let { restOf(it) }?.ifBlank { null } ?: tl?.sub
        val caption = if (bodyText == tl?.sub) null else tl?.sub
        val ledger = tl?.lines.orEmpty()
        // 높이 계산
        val titleSize = 44 * U; val bodySize = 28 * U
        val titleLines = min(2, lineCount(title, iw, titleSize, bold))
        val bodyLines = bodyText?.let { min(2, lineCount(it, iw, bodySize, body)) } ?: 0
        var hh = pad + 40 * U + 24 * U + titleLines * titleSize * 1.25f + (if (bodyLines > 0) 16 * U + bodyLines * bodySize * 1.45f else 0f) +
            (if (caption != null) 12 * U + 30 * U else 0f) + (if (ledger.isNotEmpty()) 12 * U + min(3, ledger.size) * 34 * U else 0f) + 32 * U + 64 * U + pad
        hh = min(hh, zone.height())
        val top = zone.centerY() - hh / 2
        val now = SystemClock.elapsedRealtime()
        // 반응 먼저 → 120ms 뒤 12px 올라오며 페이드(320ms, cubic-bezier(.2,.8,.2,1) 근사)
        val start = max(m.sitAt, m.reactAt) + 120
        val x = ((now - start) / 320f).coerceIn(0f, 1f)
        val e = 1f - (1f - x) * (1f - x) * (1f - x)
        val r = RectF(zone.left, top + (1 - e) * 12 * U, zone.right, top + hh + (1 - e) * 12 * U)
        if (x < 1f) c.saveLayerAlpha(r.left - 40 * U, r.top - 40 * U, r.right + 40 * U, r.bottom + 40 * U, (e * 255).toInt()) else c.save()
        glassCard(c, r, 28 * U)
        var y = r.top + pad
        // 상황 칩
        val chip = "${m.sit.label} · ${m.kicker.substringAfter(" · ")}" + (tl?.label?.let { "  ·  $it" } ?: "")
        p.textSize = 22 * U; p.typeface = semi; p.letterSpacing = 0.02f
        val cw = min(p.measureText(chip) + 32 * U, iw)
        val cr = RectF(r.left + pad, y, r.left + pad + cw, y + 40 * U)
        p.letterSpacing = 0f
        p.color = ACC_SOFT; c.drawRoundRect(cr, 20 * U, 20 * U, p)
        text(c, chip, cr.left + 16 * U, cr.centerY() + 8 * U, 22 * U, ACC, semi, maxW = cw - 32 * U)
        hits += cr to "sitpill"
        if (tl?.tag?.isNotBlank() == true) text(c, tl.tag, r.right - pad, cr.centerY() + 7 * U, 20 * U, FAINT, medium, Paint.Align.RIGHT)
        y = cr.bottom + 24 * U
        // 제목
        y = wrap(c, title, r.left + pad, y + titleSize * 0.92f, titleSize, FG, iw, 2, bold, lh = 1.25f) - titleSize * 0.92f + titleSize * 0.25f
        if (bodyText != null && bodyLines > 0) y = wrap(c, bodyText, r.left + pad, y + 16 * U + bodySize * 0.95f, bodySize, MUTED, iw, 2, body, lh = 1.45f) - bodySize * 0.95f + bodySize * 0.45f
        if (caption != null) { text(c, caption, r.left + pad, y + 12 * U + 22 * U, 20 * U, FAINT, medium, maxW = iw); y += 12 * U + 30 * U }
        if (ledger.isNotEmpty()) {
            y += 12 * U
            for (en in ledger.take(3)) {
                icon(c, en.icon, r.left + pad + 12 * U, y + 14 * U, 22 * U, if (en.delta >= 0) GOOD else RED)
                text(c, en.why, r.left + pad + 36 * U, y + 22 * U, 22 * U, MUTED, medium, maxW = iw - 120 * U)
                text(c, if (en.delta >= 0) "+${en.delta}" else "−${-en.delta}", r.right - pad, y + 22 * U, 24 * U, if (en.delta >= 0) GOOD else RED, bold, Paint.Align.RIGHT)
                y += 34 * U
            }
        }
        // 버튼 — 주 버튼 1 + 보조 글 버튼(다른 이야기)
        val by = r.bottom - pad - 64 * U
        val primary = tl?.action?.let { a -> (if (a == "deco") "테마 보러 가기" else if (a.startsWith("say:")) "그렇게 해 줘" else "열기") to a }
            ?: ("말로 하기" to "mic")
        p.textSize = 26 * U; p.typeface = semi
        val bw = max(200 * U, p.measureText(primary.first) + 64 * U)
        button(c, primary.first, RectF(r.left + pad, by, r.left + pad + bw, by + 64 * U), true, primary.second)
        if (list.size > 1) {
            val lab = "다른 이야기  ${m.heroIdx + 1}/${list.size}"
            text(c, lab, r.right - pad, by + 40 * U, 24 * U, ACC, semi, Paint.Align.RIGHT)
            p.textSize = 24 * U; p.typeface = semi
            hits += RectF(r.right - pad - p.measureText(lab) - 12 * U, by, r.right - pad + 8 * U, by + 64 * U) to "hero:${(m.heroIdx + 1) % list.size}"
        }
        c.restore()
    }

    private fun firstSentence(s: String): String {
        val i = Regex("[.!?](\\s|$)").find(s)?.range?.first ?: return s
        return s.substring(0, i + 1).trim()
    }
    private fun restOf(s: String): String { val f = firstSentence(s); return s.removePrefix(f).trim() }

    // ======================================================================
    // 다음 이야기 카드 3장 · 아래 독(브리핑 · 할 일 · 내 차 · 테마 · 기록) · 캐릭터 존 배지
    // ======================================================================
    private fun nextUp(c: Canvas, r: RectF) {
        val g = 16 * U; val cw = (r.width() - g * 2) / 3
        val st = m.status
        val soc = st?.soc?.v
        val items = listOf(
            Triple("news", "관심 뉴스", m.newsList.firstOrNull() ?: "헤드라인을 불러오는 중") to "screen:brief",
            Triple("bag", "가는 김에", m.todos.firstOrNull() ?: "할 일이 없어요") to "screen:todo",
            Triple("star", "이번 주 안전운전", "${m.weekPoints} / ${m.weekGoal}점") to "screen:log",
        ).let { if (m.sit == Sit.PARK || m.sit == Sit.OFF) it.take(2) + (Triple("battery", "내 차", (soc?.let { "배터리 $it% · " } ?: "") + "주행 가능 ${st?.rangeKm?.v ?: "-"}km") to "screen:car") else it }
        items.forEachIndexed { i, (tr, action) ->
            val (ic, label, value) = tr
            val b = RectF(r.left + i * (cw + g), r.top, r.left + i * (cw + g) + cw, r.bottom)
            spring(c, i + 1) {
                glassCard(c, b, 22 * U)
                icon(c, ic, b.left + 30 * U, b.top + 32 * U, 24 * U, if (ic == "star") WARN else ACC)
                text(c, label, b.left + 52 * U, b.top + 40 * U, 20 * U, MUTED, semi, maxW = cw - 70 * U)
                if (ic == "star") {   // 진행 바
                    val pr = (m.weekPoints.toFloat() / m.weekGoal).coerceIn(0f, 1f)
                    text(c, value, b.left + 22 * U, b.top + 84 * U, 26 * U, FG, bold)
                    val bar = RectF(b.left + 22 * U, b.bottom - 34 * U, b.right - 22 * U, b.bottom - 24 * U)
                    p.color = SURF2; c.drawRoundRect(bar, 5 * U, 5 * U, p)
                    p.color = WARN; c.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * pr, bar.bottom), 5 * U, 5 * U, p)
                } else wrap(c, value, b.left + 22 * U, b.top + 80 * U, 22 * U, FG, cw - 44 * U, 2, medium, lh = 1.35f)
            }
            hits += b to action
        }
    }

    private val DOCK = listOf("brief" to "브리핑", "todo" to "할 일", "car" to "내 차", "deco" to "테마", "log" to "기록")
    private fun dock(c: Canvas, w: Float, h: Float) {
        val dh = 64 * U; val iw = 128 * U
        val r = RectF(w * 0.6f, h - 48 * U - dh, w * 0.6f + iw * DOCK.size, h - 48 * U)
        glassCard(c, r, dh / 2)
        DOCK.forEachIndexed { i, (key, lab) ->
            val b = RectF(r.left + i * iw, r.top, r.left + (i + 1) * iw, r.bottom)
            val on = m.overlay == key
            if (on) { p.color = ACC_SOFT; c.drawRoundRect(RectF(b.left + 6 * U, b.top + 6 * U, b.right - 6 * U, b.bottom - 6 * U), dh / 2, dh / 2, p) }
            val ic = when (key) { "brief" -> "news"; "todo" -> "bag"; "car" -> "battery"; "deco" -> "star"; else -> "chart" }
            icon(c, ic, b.left + 34 * U, b.centerY(), 22 * U, if (on) ACC else MUTED)
            text(c, lab, b.left + 54 * U, b.centerY() + 8 * U, 22 * U, if (on) ACC else FG, semi, maxW = iw - 60 * U)
            hits += b to (if (key == "deco") "deco" else "screen:$key")
        }
    }

    /** 캐릭터 존 배지 — 날씨 · 오늘 모은 별(작게) */
    private fun badges(c: Canvas) {
        var x = 48 * U; val cy = 48 * U; val bh = 44 * U
        fun badge(ic: String, s: String, col: Int) {
            p.textSize = 22 * U; p.typeface = semi
            val r = RectF(x, cy - bh / 2, x + p.measureText(s) + 64 * U, cy + bh / 2)
            glassCard(c, r, bh / 2); icon(c, ic, r.left + 24 * U, cy, 22 * U, col); text(c, s, r.left + 44 * U, cy + 8 * U, 22 * U, FG, semi)
            x = r.right + 10 * U
        }
        if (m.weatherShort.isNotBlank()) badge("cloud", m.weatherShort, ACC)
        badge("star", "오늘 ${if (m.todayPoints >= 0) "+" else ""}${m.todayPoints}", WARN)
        if (m.skill == "novice") badge("flag", "초보 모드", WARN)
    }

    /** 다른 화면 — 오른쪽 큰 카드 한 장(목록). 브리핑 · 할 일 · 내 차 · 기록 */
    private fun sheet(c: Canvas, zone: RectF, which: String) {
        glassCard(c, zone, 28 * U)
        val pad = 40 * U; val x0 = zone.left + pad; val x1 = zone.right - pad
        val (title, sub) = when (which) {
            "brief" -> "오늘의 브리핑" to (m.weatherLine.ifBlank { "날씨를 불러오는 중" })
            "todo" -> "할 일 · 가는 김에" to "가는 길에 들를 수 있는 것부터 — 말로 \"가는 김에 들르자\""
            "car" -> "내 차" to "Pleos 차량 SDK → AAOS 차량 API → 앱 측정 순으로 읽어요"
            else -> "안전운전 기록" to "이번 주 ${m.weekPoints} / ${m.weekGoal}점 · 모은 별 ${m.points}"
        }
        text(c, title, x0, zone.top + pad + 36 * U, 40 * U, FG, bold)
        text(c, sub, x0, zone.top + pad + 76 * U, 22 * U, MUTED, medium, maxW = x1 - x0 - 120 * U)
        button(c, "닫기", RectF(x1 - 110 * U, zone.top + pad, x1, zone.top + pad + 52 * U), false, "screen:$which")
        var y = zone.top + pad + 116 * U
        val rowH = 64 * U
        fun row(ic: String, a: String, b: String, col: Int = ACC, bcol: Int = FG) {
            if (y + rowH > zone.bottom - pad) return
            p.color = LINE; c.drawRect(x0, y, x1, y + max(1f, U), p)
            icon(c, ic, x0 + 16 * U, y + rowH / 2, 24 * U, col)
            text(c, a, x0 + 48 * U, y + rowH / 2 + 9 * U, 24 * U, FG, medium, maxW = (x1 - x0) * (if (b.isBlank()) 0.92f else 0.6f))
            if (b.isNotBlank()) text(c, b, x1, y + rowH / 2 + 9 * U, 24 * U, bcol, semi, Paint.Align.RIGHT, maxW = (x1 - x0) * 0.36f)
            y += rowH
        }
        when (which) {
            "brief" -> { if (m.newsList.isEmpty()) row("news", "헤드라인을 불러오는 중이에요", "")
                m.newsList.forEachIndexed { i, n -> row("news", "${i + 1}. $n", "") }
                text(c, m.newsSource, x0, zone.bottom - pad + 8 * U, 18 * U, FAINT, medium, maxW = x1 - x0) }
            "todo" -> { m.todos.forEachIndexed { i, t -> row(if (i == 0) "pin" else "bag", t, if (i == 0) "가는 길" else "", if (i == 0) ACC else MUTED, ACC) }
                text(c, "예시 할 일 — 폰 짭비서·서버 할 일과 이어질 자리", x0, zone.bottom - pad + 8 * U, 18 * U, FAINT, medium) }
            "car" -> { val st = m.status
                fun src(v: io.github.somanghao.makecar.hyun.drive.CarStatus.V<*>?) = when (v?.v) { null -> "예시"; else -> when (v.from) { io.github.somanghao.makecar.hyun.drive.CarStatus.From.PLEOS -> "Pleos"; io.github.somanghao.makecar.hyun.drive.CarStatus.From.AAOS -> "AAOS"; io.github.somanghao.makecar.hyun.drive.CarStatus.From.APP -> "앱"; else -> "모의" } }
                fun line(ic: String, a: String, v: io.github.somanghao.makecar.hyun.drive.CarStatus.V<*>?, value: String) = row(ic, "$a  ·  ${src(v)}", value, ACC, if (v?.v == null) FAINT else FG)
                line("battery", st?.energyLabel?.v ?: "배터리", st?.soc, st?.soc?.v?.let { "$it%" } ?: "—")
                line("road", "주행 가능", st?.rangeKm, st?.rangeKm?.v?.let { "${it}km" } ?: "—")
                line("clock", "이번 주행", st?.tripKm, st?.tripKm?.v?.let { "%.1fkm · ${st.tripMin.v ?: 0}분".format(it) } ?: "—")
                line("cruise", "크루즈 · 주행 모드", st?.cruise, "${st?.cruise?.v ?: "—"} · ${st?.mode?.v ?: "—"}")
                line("temp", "실내 설정 온도", st?.cabinC, st?.cabinC?.v?.let { "%.1f°".format(it) } ?: "—")
                line("cloud", "와이퍼", st?.wiper, st?.wiper?.v?.let { if (it) "작동" else "꺼짐" } ?: "—")
                line("chat", "조수석 · 벨트", st?.passenger, "${st?.passenger?.v?.let { if (it) "착석" else "비어 있음" } ?: "—"} · ${st?.belt?.v?.let { if (it) "맴" else "풀림" } ?: "—"}")
                line("lock", "문", st?.locked, st?.locked?.v?.let { if (it) "잠김" else "열림" } ?: "—") }
            else -> {
                val bar = RectF(x0, y + 8 * U, x1, y + 22 * U)
                p.color = SURF2; c.drawRoundRect(bar, 7 * U, 7 * U, p)
                p.color = WARN; c.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * (m.weekPoints.toFloat() / m.weekGoal).coerceIn(0f, 1f), bar.bottom), 7 * U, 7 * U, p)
                y += 44 * U
                val list = (m.ledger + m.dayLedger).distinct()
                if (list.isEmpty()) row("star", "아직 기록이 없어요 — 100m 안전 주행마다 +1", "")
                list.reversed().forEach { e -> row(e.icon, e.why, if (e.delta >= 0) "+${e.delta}" else "−${-e.delta}", if (e.delta >= 0) GOOD else RED, if (e.delta >= 0) GOOD else RED) }
            }
        }
    }

    // ======================================================================
    // 주행 중 — 제목+아이콘만(2초 안에 읽힘). 차 상태가 바뀌면 6초 동안 그 한 줄로
    // ======================================================================
    private fun driveCard(c: Canvas, zone: RectF) {
        val ev = m.carEvent
        val showEv = ev != null && SystemClock.elapsedRealtime() - m.carEventAt < 6000
        val hh = 168 * U
        val r = RectF(zone.left, zone.centerY() - hh / 2, zone.right, zone.centerY() + hh / 2)
        glassCard(c, r, 28 * U, if (showEv && ev!!.first == "warn") RED else null)
        val ic = 104 * U
        val icx = r.left + 32 * U + ic / 2; val icy = r.centerY()
        if (showEv) {
            val col = when (ev!!.first) { "warn" -> RED; "signal" -> WARN; "cruise", "road", "flag" -> GOOD; else -> ACC }
            p.color = Glass.a(col, if (darkUi) 60 else 36); c.drawCircle(icx, icy, ic / 2, p)
            val blink = ev.first != "signal" || (SystemClock.elapsedRealtime() / 500) % 2 == 0L
            if (blink) icon(c, ev.first, icx, icy, ic * 0.5f, col)
            wrap(c, ev.second, icx + ic / 2 + 28 * U, icy + 14 * U, fit(ev.second, 40 * U, r.right - icx - ic / 2 - 60 * U, bold), FG, r.right - icx - ic / 2 - 60 * U, 1, bold)
        } else {
            p.color = ACC; c.drawCircle(icx, icy, ic / 2, p)
            v.turnIcon(c, m.navTurnType.ifBlank { "TURN_STRAIGHT" }, icx, icy, ic * 0.6f, Color.WHITE)
            p.style = Paint.Style.FILL; p.strokeWidth = 0f
            val tx = icx + ic / 2 + 28 * U; val tw = r.right - tx - 32 * U
            val title = "${v.dist(m.navTurnM)} 앞 ${shortTurn(m.navTurnType, m.navTurn)}"
            text(c, title, tx, icy + 4 * U, fit(title, 44 * U, tw, bold), FG, bold, maxW = tw)
            text(c, "${m.navEta} · ${m.navRemain}" + if (m.mockRoute) " · 모의 경로" else "", tx, icy + 44 * U, 24 * U, MUTED, medium, maxW = tw)
        }
    }

    private fun shortTurn(type: String, desc: String) = when {
        type.contains("LEFT") || type == "TURN_KEEP_L" -> "좌회전"; type.contains("RIGHT") || type == "TURN_KEEP_R" -> "우회전"
        type == "TURN_UTURN" -> "유턴"; type.startsWith("TURN_ENTER_IC") -> "고속도로 진입"; type.startsWith("TURN_EXIT_IC") -> "나들목 나가기"
        type == "TURN_ARRIVE" -> "도착"; else -> desc.substringBefore(" ").ifBlank { "직진" }
    }

    /** 마이크 — 오른쪽 아래 동그라미 하나(상태 글은 옆에 작게) */
    private fun micButton(c: Canvas, w: Float, h: Float) {
        val rr = 40 * U
        val mx = w - 48 * U - rr; val my = h - 48 * U - rr
        val mc = when (v.mode) { HyunView.Mode.LISTENING -> GLOW; HyunView.Mode.SPEAKING -> MUTED; else -> ACC }
        if (v.mode == HyunView.Mode.LISTENING) { p.color = Glass.a(GLOW, 70); c.drawCircle(mx, my, rr * (1.3f + 0.08f * sin(SystemClock.elapsedRealtime() / 120f)), p) }
        p.color = mc; p.setShadowLayer(16 * U, 0f, 6 * U, 0x29111827); c.drawCircle(mx, my, rr, p); p.clearShadowLayer()
        micGlyph(c, mx, my, rr * 1.15f, Color.WHITE)
        hits += RectF(mx - rr, my - rr, mx + rr, my + rr) to "mic"
        val lab = when (v.mode) { HyunView.Mode.LISTENING -> "듣고 있어요"; HyunView.Mode.THINKING -> "생각 중…"; HyunView.Mode.SPEAKING -> ""; else -> if (v.listenAlways) "\"현비서야\"" else "" }
        if (lab.isNotBlank()) text(c, lab, mx - rr - 16 * U, my + 8 * U, 22 * U, if (darkUi || !k.light) 0xEEFFFFFF.toInt() else MUTED, medium, Paint.Align.RIGHT)
    }

    /** 해금 연출 — 1.2초 동안 문 두 짝이 열린다 */
    private fun doors(c: Canvas, w: Float, h: Float, x: Float) {
        val e = (if (x < 0.25f) 0f else ((x - 0.25f) / 0.75f)).let { 1f - (1f - it) * (1f - it) }
        val half = w / 2 * (1f - e)
        p.shader = null; p.color = SURF2
        c.drawRect(0f, 0f, half, h, p); c.drawRect(w - half, 0f, w, h, p)
        p.color = LINE; c.drawRect(half - 2 * U, 0f, half, h, p); c.drawRect(w - half, 0f, w - half + 2 * U, h, p)
    }

    // ======================================================================
    // 테마 상점 — 화면 전체가 그 테마로 미리보기(잠긴 테마는 10초 체험) · 진열장 카드
    // ======================================================================
    private fun shop(c: Canvas, w: Float, h: Float, t: Float) {
        val pad = 48 * U
        val face = v.faceOverride ?: if (m.face != "neutral") m.face else k.idleFace
        character(c, w * 0.22f, h * 0.87f, h * 0.5f, t, face, true)
        val previewing = m.preview != null
        val trialLeft = if (previewing) (10 - (SystemClock.elapsedRealtime() - m.previewAt) / 1000).coerceAtLeast(0) else 0
        val tag = if (previewing) "미리보기 · ${k.name}" + (if ("concept:${k.id}" !in m.owned) " · ${trialLeft}초" else "") else k.name
        p.textSize = 22 * U; p.typeface = semi
        val tr = RectF(pad, pad, pad + p.measureText(tag) + 40 * U, pad + 44 * U)
        glassCard(c, tr, 22 * U); text(c, tag, tr.left + 20 * U, tr.centerY() + 8 * U, 22 * U, FG, semi)
        val panel = RectF(w * 0.42f, pad, w - pad, h - pad)
        glassCard(c, panel, 28 * U)
        val x0 = panel.left + 40 * U; val x1 = panel.right - 40 * U
        text(c, "테마", x0, panel.top + 64 * U, 44 * U, FG, bold)
        text(c, "안전운전으로 모은 별로 열어요 · 잠긴 테마도 눌러서 10초 미리보기", x0, panel.top + 104 * U, 22 * U, MUTED, medium, maxW = x1 - x0 - 260 * U)
        button(c, "닫기", RectF(x1 - 120 * U, panel.top + 28 * U, x1, panel.top + 84 * U), false, "close")
        p.textSize = 24 * U; p.typeface = semi
        val pw = p.measureText("${m.points}") + 64 * U
        val pr2 = RectF(x1 - 132 * U - pw, panel.top + 28 * U, x1 - 132 * U, panel.top + 84 * U)
        p.color = Glass.a(WARN, 36); c.drawRoundRect(pr2, 28 * U, 28 * U, p); icon(c, "star", pr2.left + 26 * U, pr2.centerY(), 24 * U, WARN); text(c, "${m.points}", pr2.left + 46 * U, pr2.centerY() + 9 * U, 24 * U, FG, bold)
        var tx = x0; val ty = panel.top + 160 * U
        for ((key, lab) in listOf("concept" to "테마", "face" to "표정", "decor" to "소품")) {
            val on = m.decoTab == key
            p.textSize = 24 * U; p.typeface = semi
            val r = RectF(tx, ty - 26 * U, tx + p.measureText(lab) + 48 * U, ty + 26 * U)
            p.color = if (on) ACC else SURF2; c.drawRoundRect(r, 26 * U, 26 * U, p)
            text(c, lab, r.centerX(), ty + 9 * U, 24 * U, if (on) Color.WHITE else FG, semi, Paint.Align.CENTER)
            hits += r to "tab:$key"; tx = r.right + 12 * U
        }
        val gy = ty + 48 * U; val g = 16 * U
        if (m.decoTab == "concept") {
            val cols = 3; val rows = (Concept.ALL.size + cols - 1) / cols
            val cw = (x1 - x0 - g * (cols - 1)) / cols; val ch = (panel.bottom - 40 * U - gy - g * (rows - 1)) / rows
            Concept.ALL.forEachIndexed { i, cc ->
                val r = RectF(x0 + (i % cols) * (cw + g), gy + (i / cols) * (ch + g), 0f, 0f).also { it.right = it.left + cw; it.bottom = it.top + ch }
                val key = "concept:${cc.id}"
                val own = key in m.owned; val using = m.concept == cc.id; val looking = (m.preview ?: m.concept) == cc.id
                val th = RectF(r.left, r.top, r.right, r.bottom - 44 * U)
                c.save(); c.clipPath(Path().apply { addRoundRect(th, 18 * U, 18 * U, Path.Direction.CW) })
                val a = v.asset("room_${cc.room}.webp")
                if (a != null) { val s2 = max(th.width() / a.width, th.height() / a.height); val bw = a.width * s2; val bh = a.height * s2; c.drawBitmap(a, null, RectF(th.left, th.centerY() - bh / 2, th.left + bw, th.centerY() + bh / 2), bp) }
                else ambient(c, th, cc, cc.id == "pleos" && darkUi)
                c.restore()
                p.style = Paint.Style.STROKE; p.strokeWidth = if (looking) 4 * U else max(1f, U); p.color = if (looking) ACC else LINE
                c.drawRoundRect(th, 18 * U, 18 * U, p); p.style = Paint.Style.FILL
                if (!own) { val lr = RectF(th.right - 44 * U, th.top + 10 * U, th.right - 10 * U, th.top + 44 * U); p.color = 0x99000000.toInt(); c.drawRoundRect(lr, 17 * U, 17 * U, p); icon(c, "lock", lr.centerX(), lr.centerY(), 18 * U, Color.WHITE) }
                text(c, cc.name, r.left + 4 * U, r.bottom - 12 * U, 22 * U, FG, semi, maxW = cw * 0.6f)
                text(c, when { using -> "사용 중"; own -> "보유"; else -> "★ ${cc.price}" }, r.right - 4 * U, r.bottom - 12 * U, 20 * U, when { using -> ACC; own -> MUTED; m.points >= cc.price -> WARN; else -> FAINT }, semi, Paint.Align.RIGHT)
                hits += r to "preview:${cc.id}"
            }
            // 미리보기 중인 테마 — 큰 버튼 하나
            val lk = m.preview
            if (lk != null) {
                val cc = Concept.of(lk); val own = "concept:$lk" in m.owned
                val br = RectF(tr.left, tr.bottom + 16 * U, tr.left + 280 * U, tr.bottom + 16 * U + 64 * U)
                button(c, if (own) "이 테마 쓰기" else "★ ${cc.price} 열기", br, own || m.points >= cc.price, "buy:$lk")
            }
        } else {
            val items = if (m.decoTab == "face") StageModel.FACES else StageModel.DECOR
            val cols = 3; val cw = (x1 - x0 - g * (cols - 1)) / cols; val ch = (panel.bottom - 40 * U - gy - g) / 2
            items.forEachIndexed { i, d ->
                val r = RectF(x0 + (i % cols) * (cw + g), gy + (i / cols) * (ch + g), 0f, 0f).also { it.right = it.left + cw; it.bottom = it.top + ch }
                val key = "${d.kind}:${d.id}"
                val own = key in m.owned
                val using = (d.kind == "face" && m.face == d.id) || (d.kind == "decor" && d.id in m.decor)
                p.color = SURF2; c.drawRoundRect(r, 18 * U, 18 * U, p)
                if (using) { p.style = Paint.Style.STROKE; p.strokeWidth = 4 * U; p.color = ACC; c.drawRoundRect(r, 18 * U, 18 * U, p); p.style = Paint.Style.FILL }
                val pcx = r.centerX(); val pcy = r.top + r.height() * 0.42f; val ps = min(r.width(), r.height()) * 0.6f
                (if (d.kind == "face") v.asset("bot_${d.id}.webp") else v.asset("prop_${d.id}.webp"))?.let { bm -> p.color = Color.WHITE; c.drawBitmap(bm, null, RectF(pcx - ps / 2, pcy - ps / 2, pcx + ps / 2, pcy + ps / 2), bp) }
                text(c, d.name, r.left + 16 * U, r.bottom - 16 * U, 22 * U, FG, semi)
                val (st, sc) = when { using -> (if (d.kind == "decor") "놓음" else "사용 중") to ACC; own -> (if (d.kind == "decor") "치움" else "보유") to MUTED; else -> "★ ${d.price}" to (if (m.points >= d.price) WARN else FAINT) }
                text(c, st, r.right - 16 * U, r.bottom - 16 * U, 20 * U, sc, semi, Paint.Align.RIGHT)
                hits += r to (if (d.kind == "decor" && own) "place:${d.id}" else "item:$key")
            }
        }
        if (m.unlockAt > 0 && SystemClock.elapsedRealtime() - m.unlockAt < 1200) doors(c, w, h, (SystemClock.elapsedRealtime() - m.unlockAt) / 1200f)
        toast(c, w, h)
    }

    private fun debug(c: Canvas, w: Float, h: Float) {
        val r = RectF(w * 0.04f, h - 24f * S, w * 0.96f, h - 2f * S)
        glassCard(c, r, 24 * U, WARN)
        text(c, "디버그 · 상황", r.left + 2.4f * S, r.top + 3.6f * S, 1.9f * S, WARN, bold)
        text(c, "adb: am broadcast -a io.github.somanghao.makecar.hyun.STAGE --es sit drive --es sim \"wiper=on,passenger=on\"", r.right - 2.4f * S, r.top + 3.6f * S, 1.5f * S, MUTED, body, Paint.Align.RIGHT)
        val list = Sit.values().map { it.key to it.label } + listOf("auto" to "자동(신호)", "x" to "닫기")
        val bw = (r.width() - 4.8f * S - 1f * S * (list.size - 1)) / list.size
        list.forEachIndexed { i, (key, lab) ->
            val br = RectF(r.left + 2.4f * S + i * (bw + S), r.top + 5f * S, 0f, r.top + 10.4f * S).also { it.right = it.left + bw }
            button(c, lab, br, key == m.sit.key && m.forced, if (key == "x") "debug" else "sit:$key")
        }
        text(c, "차 신호 시뮬레이터(Pleos SDK 대신)", r.left + 2.4f * S, r.top + 13.4f * S, 1.9f * S, WARN, bold)
        val sims = listOf("비(와이퍼)" to "wiper=on", "동승자" to "passenger=on", "뒷좌석" to "kid=on", "더워요 28°" to "cabin=28", "배터리 18%" to "soc=18", "크루즈 100" to "cruise=100",
            "왼쪽 깜빡이" to "turn=left", "문 열림" to "door=open", "벨트 풀림" to "belt=off", "에코" to "mode=ECO", "초기화" to "reset")
        val sw = (r.width() - 4.8f * S - 0.8f * S * (sims.size - 1)) / sims.size
        sims.forEachIndexed { i, (lab, spec) ->
            val br = RectF(r.left + 2.4f * S + i * (sw + 0.8f * S), r.top + 15f * S, 0f, r.bottom - 1.6f * S).also { it.right = it.left + sw }
            button(c, lab, br, false, "sim:$spec")
        }
    }

    // 예전 코드(온보딩·설정) 호환
    private fun gpill(c: Canvas, s: String, x: Float, cy: Float, h: Float, tint: Int?, size: Float, action: String?): Float {
        p.textSize = size; p.typeface = medium
        val r = RectF(x, cy - h / 2, x + p.measureText(s) + h * 0.9f, cy + h / 2)
        glassCard(c, r, h / 2, tint)
        text(c, s, r.centerX(), cy + size * 0.36f, size, FG, medium, Paint.Align.CENTER)
        action?.let { hits += r to it }
        return r.right
    }
    private fun spring(c: Canvas, i: Int, draw: () -> Unit) {
        val x = ((SystemClock.elapsedRealtime() - m.sitAt - 120 - i * 70) / 320f).coerceIn(0f, 1f)
        if (x >= 1f) { draw(); return }
        val e = 1f - (1f - x) * (1f - x) * (1f - x)
        c.save(); c.translate(0f, (1f - e) * 12 * U); draw(); c.restore()
    }

    companion object {
        /** 날씨 옷차림 기호 → 소품 그림 이름(assets/chars/prop_<이름>.webp) */
        val PROPS = mapOf("☂️" to "umbrella", "🧣" to "scarf", "☕" to "mug", "🍵" to "mug", "🧋" to "mug", "🕶️" to "sunglasses")
    }

    // ======================================================================
    // 첫 실행
    // ======================================================================
    private fun onboard(c: Canvas, w: Float, h: Float, t: Float) {
        val pad = 3.2f * S
        val L = RectF(pad, pad, w * 0.42f, h - pad)
        glassCard(c, L, k.radius * S * 1.3f, null)
        val size = min(L.height() * 0.56f, L.width() * 0.66f)
        val cx = L.centerX(); val cy = L.top + L.height() * 0.42f
        character(c, cx, cy + size / 2, size, t, v.botFace(), true)
        text(c, "현비서", cx, cy + size * 0.6f + 2f * S, 2.6f * S, FG, bold, Paint.Align.CENTER)
        if (v.hyunLine.isNotBlank()) wrap(c, v.hyunLine, cx, cy + size * 0.6f + 6.4f * S, 2.2f * S, MUTED, L.width() - 8f * S, 3, body, center = true)
        val x0 = L.right + 4f * S; val x1 = w - pad * 2
        val steps = listOf("차량 점검", "운전 경험", "맞춤 완료")
        val sw = (x1 - x0 - 2f * S) / 3
        steps.forEachIndexed { i, s ->
            val sx = x0 + i * (sw + S)
            p.color = if (i <= m.onboardStep) ACC else 0x2AFFFFFF
            c.drawRoundRect(RectF(sx, pad + 2f * S, sx + sw, pad + 2.7f * S), S, S, p)
            text(c, "${i + 1}  $s", sx, pad + 6.2f * S, 1.9f * S, if (i == m.onboardStep) FG else MUTED, if (i == m.onboardStep) bold else medium)
        }
        val top = pad + 13f * S
        when (m.onboardStep) {
            0 -> onboardCheck(c, x0, x1, top, h - pad)
            1 -> onboardSkill(c, x0, x1, top, h - pad)
            else -> onboardDone(c, x0, x1, top, h - pad)
        }
    }

    private fun onboardCheck(c: Canvas, x0: Float, x1: Float, top: Float, bottom: Float) {
        text(c, "차를 먼저 살펴볼게요", x0, top + 4.4f * S, 4.8f * S, FG, bold)
        val car = m.check.firstOrNull { it.key == "car" }?.value ?: "차량 정보를 읽는 중…"
        text(c, car, x0, top + 9f * S, 2.3f * S, MUTED, medium)
        var y = top + 13f * S
        if (m.check.isEmpty()) {
            val a = (SystemClock.elapsedRealtime() / 3 % 360).toFloat()
            p.style = Paint.Style.STROKE; p.strokeWidth = 0.6f * S; p.color = ACC; p.strokeCap = Paint.Cap.ROUND
            c.drawArc(RectF(x0, y, x0 + 4f * S, y + 4f * S), a, 270f, false, p); p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
            return
        }
        val shown = ((SystemClock.elapsedRealtime() - m.checkAt) / 320 + 1).toInt()
        val items = m.check.filter { it.key != "car" }
        val rh = min(7.2f * S, (bottom - y - 9f * S) / items.size)
        items.take(shown).forEach { it2 ->
            val r = RectF(x0, y, x1, y + rh - 1f * S)
            glassCard(c, r, 2f * S, null)
            val col = when (it2.state) { CarCheck.State.OK -> GOOD; CarCheck.State.WARN -> WARN; CarCheck.State.NA -> MUTED }
            p.color = Glass.a(col, 60); c.drawCircle(r.left + 3.4f * S, r.centerY(), 1.7f * S, p)
            text(c, when (it2.state) { CarCheck.State.OK -> "✓"; CarCheck.State.WARN -> "!"; CarCheck.State.NA -> "–" }, r.left + 3.4f * S, r.centerY() + 0.75f * S, 2f * S, col, bold, Paint.Align.CENTER)
            text(c, it2.label, r.left + 6.8f * S, r.centerY() + (if (it2.note.isBlank()) 0.8f * S else -0.2f * S), 2.2f * S, FG, medium)
            if (it2.note.isNotBlank()) text(c, it2.note, r.left + 6.8f * S, r.centerY() + 2.4f * S, 1.6f * S, MUTED, body)
            text(c, it2.value, r.right - 2.4f * S, r.centerY() + 0.85f * S, 2.4f * S, if (it2.state == CarCheck.State.NA) MUTED else FG, bold, Paint.Align.RIGHT)
            y += rh
        }
        if (shown >= items.size) button(c, "다음", RectF(x1 - 18f * S, bottom - 6.4f * S, x1, bottom), true, "onboard:next")
        text(c, "실제 차량 신호(AAOS)로 읽은 값 · '확인 불가'는 외부 앱에 열려 있지 않은 항목", x0, bottom - 2.6f * S, 1.6f * S, MUTED, body, maxW = x1 - x0 - 22f * S)
    }

    private fun onboardSkill(c: Canvas, x0: Float, x1: Float, top: Float, bottom: Float) {
        text(c, "운전이 처음이세요?", x0, top + 4.4f * S, 5f * S, FG, bold)
        text(c, "말로 대답해도 돼요 — \"처음이야\" 또는 \"익숙해\"", x0, top + 9.4f * S, 2.3f * S, MUTED, medium)
        if (v.mode == HyunView.Mode.LISTENING) solidPillR(c, "● 듣고 있어요", x1, top + 8.6f * S, 4f * S, Glass.a(GOOD, 50), GOOD, 1.8f * S)
        val y = top + 14f * S; val g = 2f * S; val cw = (x1 - x0 - g) / 2; val chh = min(bottom - y - 2f * S, 52f * S)
        val opts = listOf(
            Triple("novice", "", listOf("처음이에요", "초보 운전", "출발·차선·주차를 하나씩 말로|회전 300m 앞에서 미리 알림|감점은 부드럽게, 칭찬은 크게|50분마다 쉼표 제안")),
            Triple("expert", "", listOf("익숙해요", "숙련 운전", "꼭 필요한 알림만 짧게|화면은 조용히, 말은 줄여서|안전운전 점수는 정확하게|쉼표는 120분에 한 번")))
        opts.forEachIndexed { i, (key, icon, txt) ->
            val r = RectF(x0 + i * (cw + g), y, x0 + i * (cw + g) + cw, y + chh)
            val col = if (key == "novice") WARN else INFO
            spring(c, i) {
                glassCard(c, r, k.radius * S * 1.2f, col)
                icon(c, if (key == "novice") "flag" else "road", r.left + 6f * S, r.top + 7f * S, 5f * S, col)
                text(c, txt[0], r.left + 3f * S, r.top + 17f * S, 3.8f * S, FG, bold)
                solidPill(c, txt[1], r.left + 3f * S, r.top + 22f * S, 3.6f * S, Glass.a(col, 70), FG, 1.8f * S)
                var ly = r.top + 28.6f * S
                txt[2].split("|").forEach { line -> p.color = col; c.drawCircle(r.left + 3.6f * S, ly - 0.7f * S, 0.5f * S, p); text(c, line, r.left + 5.2f * S, ly, 2.2f * S, FG, body, maxW = r.width() - 7f * S); ly += 4.2f * S }
            }
            hits += r to "skill:$key"
        }
    }

    private fun onboardDone(c: Canvas, x0: Float, x1: Float, top: Float, bottom: Float) {
        val nov = m.novice
        text(c, if (nov) "초보 모드로 맞췄어요" else "숙련 모드로 맞췄어요", x0, top + 4.4f * S, 5f * S, FG, bold)
        text(c, "설정(⚙)에서 언제든 바꿀 수 있어요 · 말로 \"나 초보야\" / \"운전 익숙해\"", x0, top + 9.4f * S, 2.2f * S, MUTED, medium)
        var x = x0; val y = top + 15f * S
        (if (nov) listOf("말로 자주 챙김", "회전 미리 알림", "50분 쉼표") else listOf("알림 최소", "길 안내 위주", "120분 쉼표")).forEach {
            x = gpill(c, it, x, y, 4.8f * S, null, 2f * S, null) + 1.2f * S
        }
        val r = RectF(x0, y + 5f * S, x1, y + 24f * S)
        glassCard(c, r, k.radius * S * 1.2f, WARN)
        icon(c, "star", r.left + 7f * S, r.centerY(), 5f * S, WARN)
        text(c, "+100 환영 ${k.pointWord}", r.left + 13f * S, r.centerY() - 0.4f * S, 3.8f * S, FG, bold)
        text(c, "안전운전 100m = +1 · 급정거·과속은 감점 · 모아서 컨셉 상점에서 앱 분위기를 통째로 바꿔요", r.left + 13f * S, r.centerY() + 3.6f * S, 2f * S, FG, body, maxW = r.right - r.left - 15f * S)
        button(c, "컨셉 상점 구경", RectF(x1 - 46f * S, bottom - 6.4f * S, x1 - 22f * S, bottom), false, "onboard:deco")
        button(c, "시작하기", RectF(x1 - 20f * S, bottom - 6.4f * S, x1, bottom), true, "onboard:next")
    }

    private fun settings(c: Canvas, w: Float, h: Float) {
        p.color = 0x99000000.toInt(); c.drawRect(0f, 0f, w, h, p)
        hits.clear()
        val r = RectF(w * 0.27f, h * 0.16f, w * 0.73f, h * 0.84f)
        glassCard(c, r, 28 * U)
        hits += r to "noop"
        val x0 = r.left + 4f * S; val x1 = r.right - 4f * S
        text(c, "설정", x0, r.top + 7f * S, 4f * S, FG, bold)
        text(c, "운전 숙련도", x0, r.top + 14f * S, 2.1f * S, MUTED, medium)
        val sh = 6f * S; val sy = r.top + 16f * S; val sw = (x1 - x0) / 2
        listOf("novice" to "초보 운전", "expert" to "숙련 운전").forEachIndexed { i, (key, lab) ->
            val sr = RectF(x0 + i * sw, sy, x0 + (i + 1) * sw, sy + sh).apply { inset(0.4f * S, 0f) }
            val on = m.skill == key
            if (on) { p.color = Glass.a(ACC, 150); c.drawRoundRect(sr, sh / 2, sh / 2, p) } else run { p.color = SURF2; c.drawRoundRect(sr, sh / 2, sh / 2, p) }
            text(c, lab, sr.centerX(), sr.centerY() + 0.8f * S, 2.2f * S, FG, bold, Paint.Align.CENTER)
            hits += sr to "skill:$key"
        }
        val desc = if (m.novice) "출발·정차·주차 팁을 말로 · 회전 300m 앞 차로 알림 · 감점은 절반 · 50분 쉼표" else "꼭 필요한 알림만 · 팁 없이 바로 길 안내 · 점수는 정확하게 · 120분 쉼표"
        wrap(c, desc, x0, sy + sh + 4f * S, 2f * S, MUTED, x1 - x0, 2, body)
        val by = r.bottom - 8.4f * S
        button(c, "차량 점검 다시", RectF(x0, by, x0 + (x1 - x0) * 0.32f, by + 5.6f * S), false, "recheck")
        button(c, "테마", RectF(x0 + (x1 - x0) * 0.34f, by, x0 + (x1 - x0) * 0.62f, by + 5.6f * S), false, "deco")
        button(c, "닫기", RectF(x1 - (x1 - x0) * 0.3f, by, x1, by + 5.6f * S), true, "close")
    }

    private fun toast(c: Canvas, w: Float, h: Float) {
        if (m.toast.isBlank() || SystemClock.elapsedRealtime() - m.toastAt > 2600) return
        p.textSize = 2.2f * S; p.typeface = medium
        val tw = p.measureText(m.toast) + 6f * S
        val r = RectF(w / 2 - tw / 2, h - 13f * S, w / 2 + tw / 2, h - 7f * S)
        glassCard(c, r, r.height() / 2, ACC)
        text(c, m.toast, r.centerX(), r.centerY() + 0.8f * S, 2.2f * S, FG, medium, Paint.Align.CENTER)
    }

    // ======================================================================
    // 그리기 도구
    // ======================================================================
    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, col: Int, face: Typeface = body, align: Paint.Align = Paint.Align.LEFT, maxW: Float = Float.MAX_VALUE) {
        p.shader = null; p.style = Paint.Style.FILL; p.color = col; p.textSize = size; p.typeface = face; p.textAlign = align
        var line = s
        if (p.measureText(line) > maxW) { while (line.isNotEmpty() && p.measureText("$line…") > maxW) line = line.dropLast(1); line += "…" }
        c.drawText(line, x, y, p); p.textAlign = Paint.Align.LEFT
    }

    private fun emoji(c: Canvas, s: String, cx: Float, cy: Float, size: Float, rot: Float) {
        c.save(); c.rotate(rot, cx, cy)
        p.shader = null; p.color = Color.BLACK; p.textSize = size; p.typeface = Typeface.DEFAULT; p.textAlign = Paint.Align.CENTER
        c.drawText(s, cx, cy + size * 0.36f, p); p.textAlign = Paint.Align.LEFT
        c.restore()
    }

    private fun splitLines(s: String, maxW: Float): List<String> {
        val out = mutableListOf<String>(); var rest = s.trim()
        while (rest.isNotEmpty()) {
            var n = p.breakText(rest, true, maxW, null).coerceAtLeast(1)
            if (n < rest.length) rest.lastIndexOf(' ', n).takeIf { it > n / 2 }?.let { n = it + 1 }
            out += rest.substring(0, n).trim(); rest = rest.substring(n).trim()
        }
        return out
    }
    private fun lineCount(s: String, maxW: Float, size: Float, face: Typeface = medium): Int { p.textSize = size; p.typeface = face; return splitLines(s, maxW).size }

    private fun wrap(c: Canvas, s: String, x: Float, y: Float, size: Float, col: Int, maxW: Float, maxLines: Int, face: Typeface = body, center: Boolean = false, lh: Float = 1.42f): Float {
        p.textSize = size; p.typeface = face
        val lines = splitLines(s, maxW); val shown = lines.take(maxLines)
        shown.forEachIndexed { i, l ->
            val last = i == maxLines - 1 && lines.size > maxLines
            text(c, if (last) "$l…" else l, x, y + i * size * lh, size, col, face, if (center) Paint.Align.CENTER else Paint.Align.LEFT, maxW)
        }
        return y + shown.size * size * lh
    }

    private fun fit(s: String, max: Float, maxW: Float, face: Typeface): Float {
        var size = max; p.typeface = face
        while (size > max * 0.6f) { p.textSize = size; if (p.measureText(s) <= maxW) break; size *= 0.94f }
        return size
    }

    /** 칠한 알약(유리 위 작은 표시) */
    private fun solidPill(c: Canvas, s: String, x: Float, cy: Float, h: Float, bg: Int, fg: Int, size: Float): Float {
        p.textSize = size; p.typeface = medium
        val r = RectF(x, cy - h / 2, x + p.measureText(s) + h * 0.9f, cy + h / 2)
        p.shader = null; p.color = bg; c.drawRoundRect(r, h / 2, h / 2, p)
        text(c, s, r.centerX(), cy + size * 0.36f, size, fg, medium, Paint.Align.CENTER)
        return r.right
    }
    private fun solidPillR(c: Canvas, s: String, x: Float, cy: Float, h: Float, bg: Int, fg: Int, size: Float): Float {
        p.textSize = size; p.typeface = medium
        val wd = p.measureText(s) + h * 0.9f
        solidPill(c, s, x - wd, cy, h, bg, fg, size); return x - wd
    }

    private fun button(c: Canvas, s: String, r: RectF, primary: Boolean, action: String) {
        val rad = min(18 * U, r.height() / 2)
        p.shader = null
        if (primary) { p.color = ACC; c.drawRoundRect(r, rad, rad, p) }
        else { p.color = SURF2; c.drawRoundRect(r, rad, rad, p) }
        val fs = min(26 * U, r.height() * 0.42f)
        text(c, s, r.centerX(), r.centerY() + fs * 0.36f, fs, if (primary) Color.WHITE else FG, semi, Paint.Align.CENTER, r.width() * 0.92f)
        hits += r to action
    }

    private fun mixDark(col: Int): Int {   // 밝은 바탕 위 선 아이콘 — 같은 색을 조금 어둡게(대비)
        fun ch(sh: Int) = (((col shr sh) and 0xFF) * 0.72f).toInt() shl sh
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }

    private val iq = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ip2 = Path(); private val ip3 = Path()

    /** 얇은 선 아이콘(Pleos 시스템 아이콘처럼) — 이름 또는 예전 이모지 키 */
    private fun icon(c: Canvas, key: String, cx: Float, cy: Float, s: Float, col: Int) {
        val name = when (key) {
            "🔋" -> "battery"; "🌦" -> "cloud"; "📰" -> "news"; "☕" -> "cup"; "📍" -> "pin"; "🛒", "🛍", "🎨", "👜" -> "bag"; "🎧" -> "music"
            "💬" -> "chat"; "🧭" -> "compass"; "⭐" -> "star"; "🛣" -> "road"; "☁" -> "cup"; "📊" -> "chart"; "⏰" -> "clock"
            "⚠️" -> "warn"; "🚨" -> "speed"; "💡", "↪️" -> "signal"; "◎" -> "cruise"; "🏁" -> "flag"; else -> key
        }
        val q = iq.apply { reset(); isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = max(1.5f, s * 0.09f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = col }
        val h = s / 2
        val pa = ip2; pa.reset(); val pb = ip3; pb.reset()
        when (name) {
            "battery" -> { c.drawRoundRect(RectF(cx - h * 0.9f, cy - h * 0.5f, cx + h * 0.75f, cy + h * 0.5f), h * 0.18f, h * 0.18f, q); c.drawLine(cx + h * 0.95f, cy - h * 0.2f, cx + h * 0.95f, cy + h * 0.2f, q)
                q.style = Paint.Style.FILL; c.drawRect(cx - h * 0.66f, cy - h * 0.26f, cx + h * 0.2f, cy + h * 0.26f, q) }
            "cloud" -> { pa.addCircle(cx - h * 0.35f, cy + h * 0.1f, h * 0.38f, Path.Direction.CW); pb.addCircle(cx + h * 0.15f, cy - h * 0.12f, h * 0.5f, Path.Direction.CW); pa.op(pb, Path.Op.UNION)
                pb.reset(); pb.addRoundRect(RectF(cx - h * 0.72f, cy + h * 0.05f, cx + h * 0.8f, cy + h * 0.48f), h * 0.2f, h * 0.2f, Path.Direction.CW); pa.op(pb, Path.Op.UNION); c.drawPath(pa, q) }
            "news" -> { c.drawRoundRect(RectF(cx - h * 0.75f, cy - h * 0.8f, cx + h * 0.75f, cy + h * 0.8f), h * 0.15f, h * 0.15f, q); for (i in 0..2) c.drawLine(cx - h * 0.45f, cy - h * 0.38f + i * h * 0.38f, cx + h * 0.45f, cy - h * 0.38f + i * h * 0.38f, q) }
            "cup" -> { pa.moveTo(cx - h * 0.7f, cy - h * 0.2f); pa.lineTo(cx + h * 0.4f, cy - h * 0.2f); pa.lineTo(cx + h * 0.3f, cy + h * 0.7f); pa.lineTo(cx - h * 0.6f, cy + h * 0.7f); pa.close(); c.drawPath(pa, q)
                c.drawArc(RectF(cx + h * 0.2f, cy - h * 0.05f, cx + h * 0.85f, cy + h * 0.5f), -90f, 180f, false, q)
                c.drawLine(cx - h * 0.35f, cy - h * 0.85f, cx - h * 0.35f, cy - h * 0.45f, q); c.drawLine(cx, cy - h * 0.85f, cx, cy - h * 0.45f, q) }
            "pin" -> { pa.addCircle(cx, cy - h * 0.25f, h * 0.55f, Path.Direction.CW); pb.moveTo(cx - h * 0.45f, cy); pb.lineTo(cx, cy + h * 0.9f); pb.lineTo(cx + h * 0.45f, cy); pb.close(); pa.op(pb, Path.Op.UNION); c.drawPath(pa, q); c.drawCircle(cx, cy - h * 0.25f, h * 0.18f, q) }
            "bag" -> { c.drawRoundRect(RectF(cx - h * 0.75f, cy - h * 0.35f, cx + h * 0.75f, cy + h * 0.85f), h * 0.18f, h * 0.18f, q); c.drawArc(RectF(cx - h * 0.4f, cy - h * 0.95f, cx + h * 0.4f, cy + h * 0.1f), 180f, 180f, false, q) }
            "music" -> { c.drawCircle(cx - h * 0.4f, cy + h * 0.5f, h * 0.25f, q); c.drawCircle(cx + h * 0.45f, cy + h * 0.35f, h * 0.25f, q); c.drawLine(cx - h * 0.15f, cy + h * 0.5f, cx - h * 0.15f, cy - h * 0.7f, q); c.drawLine(cx + h * 0.7f, cy + h * 0.35f, cx + h * 0.7f, cy - h * 0.85f, q); c.drawLine(cx - h * 0.15f, cy - h * 0.7f, cx + h * 0.7f, cy - h * 0.85f, q) }
            "chat" -> { pa.addRoundRect(RectF(cx - h * 0.85f, cy - h * 0.7f, cx + h * 0.85f, cy + h * 0.45f), h * 0.3f, h * 0.3f, Path.Direction.CW); pb.moveTo(cx - h * 0.4f, cy + h * 0.3f); pb.lineTo(cx - h * 0.55f, cy + h * 0.85f); pb.lineTo(cx + h * 0.05f, cy + h * 0.3f); pb.close(); pa.op(pb, Path.Op.UNION); c.drawPath(pa, q) }
            "compass" -> { c.drawCircle(cx, cy, h * 0.85f, q); pa.moveTo(cx + h * 0.4f, cy - h * 0.4f); pa.lineTo(cx + h * 0.1f, cy + h * 0.1f); pa.lineTo(cx - h * 0.4f, cy + h * 0.4f); pa.lineTo(cx - h * 0.1f, cy - h * 0.1f); pa.close(); c.drawPath(pa, q) }
            "star" -> { for (i in 0 until 10) { val a2 = Math.toRadians(-90.0 + i * 36); val rr = if (i % 2 == 0) h * 0.9f else h * 0.4f; val x = cx + (rr * Math.cos(a2)).toFloat(); val y = cy + (rr * Math.sin(a2)).toFloat(); if (i == 0) pa.moveTo(x, y) else pa.lineTo(x, y) }; pa.close(); c.drawPath(pa, q) }
            "road" -> { c.drawLine(cx - h * 0.35f, cy - h * 0.85f, cx - h * 0.8f, cy + h * 0.85f, q); c.drawLine(cx + h * 0.35f, cy - h * 0.85f, cx + h * 0.8f, cy + h * 0.85f, q); c.drawLine(cx, cy - h * 0.7f, cx, cy - h * 0.35f, q); c.drawLine(cx, cy + h * 0.05f, cx, cy + h * 0.5f, q) }
            "chart" -> { c.drawLine(cx - h * 0.6f, cy + h * 0.8f, cx - h * 0.6f, cy + h * 0.1f, q); c.drawLine(cx, cy + h * 0.8f, cx, cy - h * 0.7f, q); c.drawLine(cx + h * 0.6f, cy + h * 0.8f, cx + h * 0.6f, cy - h * 0.25f, q) }
            "clock" -> { c.drawCircle(cx, cy, h * 0.85f, q); c.drawLine(cx, cy, cx, cy - h * 0.5f, q); c.drawLine(cx, cy, cx + h * 0.38f, cy + h * 0.2f, q) }
            "warn" -> { pa.moveTo(cx, cy - h * 0.85f); pa.lineTo(cx + h * 0.9f, cy + h * 0.75f); pa.lineTo(cx - h * 0.9f, cy + h * 0.75f); pa.close(); c.drawPath(pa, q); c.drawLine(cx, cy - h * 0.25f, cx, cy + h * 0.2f, q); c.drawPoint(cx, cy + h * 0.48f, q) }
            "speed" -> { c.drawArc(RectF(cx - h * 0.85f, cy - h * 0.7f, cx + h * 0.85f, cy + h), 180f, 180f, false, q); c.drawLine(cx, cy + h * 0.15f, cx + h * 0.5f, cy - h * 0.35f, q) }
            "signal" -> { c.drawLine(cx - h * 0.85f, cy, cx - h * 0.15f, cy, q); c.drawLine(cx - h * 0.85f, cy, cx - h * 0.5f, cy - h * 0.35f, q); c.drawLine(cx - h * 0.85f, cy, cx - h * 0.5f, cy + h * 0.35f, q)
                c.drawLine(cx + h * 0.15f, cy, cx + h * 0.85f, cy, q); c.drawLine(cx + h * 0.85f, cy, cx + h * 0.5f, cy - h * 0.35f, q); c.drawLine(cx + h * 0.85f, cy, cx + h * 0.5f, cy + h * 0.35f, q) }
            "cruise" -> { c.drawArc(RectF(cx - h * 0.85f, cy - h * 0.85f, cx + h * 0.85f, cy + h * 0.85f), 140f, 260f, false, q); c.drawLine(cx, cy, cx + h * 0.45f, cy - h * 0.4f, q) }
            "flag" -> { c.drawLine(cx - h * 0.6f, cy + h * 0.9f, cx - h * 0.6f, cy - h * 0.85f, q); pa.moveTo(cx - h * 0.6f, cy - h * 0.85f); pa.lineTo(cx + h * 0.7f, cy - h * 0.5f); pa.lineTo(cx - h * 0.6f, cy - h * 0.1f); c.drawPath(pa, q) }
            "temp" -> { c.drawRoundRect(RectF(cx - h * 0.22f, cy - h * 0.9f, cx + h * 0.22f, cy + h * 0.2f), h * 0.22f, h * 0.22f, q); c.drawCircle(cx, cy + h * 0.5f, h * 0.35f, q) }
            "gear" -> { c.drawCircle(cx, cy, h * 0.32f, q); for (i in 0 until 8) { val a2 = Math.toRadians(i * 45.0); c.drawLine(cx + (h * 0.55f * Math.cos(a2)).toFloat(), cy + (h * 0.55f * Math.sin(a2)).toFloat(), cx + (h * 0.82f * Math.cos(a2)).toFloat(), cy + (h * 0.82f * Math.sin(a2)).toFloat(), q) } }
            "lock" -> { c.drawRoundRect(RectF(cx - h * 0.6f, cy - h * 0.1f, cx + h * 0.6f, cy + h * 0.8f), h * 0.15f, h * 0.15f, q); c.drawArc(RectF(cx - h * 0.38f, cy - h * 0.75f, cx + h * 0.38f, cy + h * 0.1f), 180f, 180f, false, q) }
            "mic" -> micGlyph(c, cx, cy, s, col)
            else -> {}
        }
    }

    private fun micGlyph(c: Canvas, cx: Float, cy: Float, r: Float, col: Int) {
        p.shader = null; p.style = Paint.Style.FILL; p.color = col
        c.drawRoundRect(RectF(cx - r * 0.18f, cy - r * 0.48f, cx + r * 0.18f, cy + r * 0.12f), r * 0.18f, r * 0.18f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.08f
        c.drawArc(RectF(cx - r * 0.34f, cy - r * 0.3f, cx + r * 0.34f, cy + r * 0.3f), 0f, 180f, false, p)
        c.drawLine(cx, cy + r * 0.3f, cx, cy + r * 0.5f, p)
        p.style = Paint.Style.FILL
    }
}
