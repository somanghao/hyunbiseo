package io.github.somanghao.makecar.hyun.ui

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.os.SystemClock
import io.github.somanghao.makecar.hyun.drive.CarCheck
import io.github.somanghao.makecar.hyun.drive.CarStatus
import io.github.somanghao.makecar.hyun.feature.Sit
import io.github.somanghao.makecar.hyun.feature.StageModel
import io.github.somanghao.makecar.hyun.feature.Tile
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 차 가로 화면(1920×1080 AAOS) — 캐릭터가 가운데 서는 카드형 화면. 댄 11-08 "큰 글씨 위주 말고, 상황마다 새 화면 추천, 인테리어가 되게".
 *  왼쪽 = 캐릭터 무대(테마 조명 · 날씨 옷차림 · 소품) + 말풍선 + 마이크 · 오른쪽 = 상황별 추천 카드(주행 중엔 길 안내 1장 + 내 차 상태 그림)
 *  덮개: 첫 실행(점검 → 숙련도 → 완료) · 꾸미기 · 설정 · 디버그(상황 칩 세 번)
 * 글자 단계: 제목 4.6S · 카드 값 3.4S · 본문 2.3S · 보조 1.9S (S = 화면 높이/100). 주행 중엔 글을 거의 두지 않는다.
 */
class StageUi(private val v: HyunView, private val m: StageModel, private val act: (String) -> Unit) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val hits = mutableListOf<Pair<RectF, String>>()
    private val path = Path()
    private var S = 10f
    private var taps = 0; private var tapAt = 0L

    private val BG = 0xFF090D12.toInt()
    private val SURF = 0xFF111821.toInt()
    private val SURF2 = 0xFF17202B.toInt()
    private val LINE = 0x1FFFFFFF
    private val FG = 0xFFEAF0F6.toInt()
    private val MUTED = 0xFF8D99A6.toInt()
    private val FAINT = 0xFF56616E.toInt()
    private val WARN = 0xFFF5B547.toInt()
    private val GOOD = 0xFF4ADE80.toInt()
    private val INFO = 0xFF60A5FA.toInt()
    private val RED = 0xFFFF6B5A.toInt()

    private fun sitColor(s: Sit) = when (s) {
        Sit.IGNITION -> 0xFFF5C451.toInt(); Sit.READY -> INFO; Sit.DRIVE -> 0xFF4FD1C5.toInt()
        Sit.STOP -> 0xFFFF9F43.toInt(); Sit.PARK -> 0xFFA78BFA.toInt(); Sit.OFF -> MUTED
    }
    private fun tone(t: String) = when (t) { "warn" -> WARN; "good" -> GOOD; "info" -> INFO; "muted" -> MUTED; else -> m.accent() }

    private val BLOCKING = setOf("onboard", "deco", "settings")
    fun animating() = m.sit == Sit.DRIVE && (m.status?.turn?.v ?: 0) != 0

    // ======================================================================
    fun draw(c: Canvas, w: Float, h: Float, t: Float): Boolean {
        hits.clear()
        S = h / 100f
        background(c, w, h)
        when (m.overlay) {
            "onboard" -> { onboard(c, w, h, t); return true }
            "deco" -> { deco(c, w, h, t); return true }
        }
        val pad = 3.2f * S
        topBar(c, w, pad)
        val top = pad + 6f * S + 2.4f * S
        val L = RectF(pad, top, w * 0.355f, h - pad)
        stagePanel(c, L, t)
        val R = RectF(L.right + 2.4f * S, top, w - pad, h - pad)
        val k = v.card
        when {
            k != null && m.sit != Sit.DRIVE -> v.listCard(c, k, R.left, R.top, R.right, R.bottom, h)
            m.sit == Sit.DRIVE -> drive(c, R, t)
            else -> situation(c, R)
        }
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

    // ======================================================================
    // 바탕 · 머리줄
    // ======================================================================
    private fun background(c: Canvas, w: Float, h: Float) {
        p.shader = null; p.style = Paint.Style.FILL; p.color = BG
        c.drawRect(0f, 0f, w, h, p)
        val a = m.accent()
        p.color = Color.WHITE; p.shader = RadialGradient(w * 0.18f, h * 0.45f, w * 0.55f, alpha(a, 46), alpha(a, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.color = Color.WHITE; p.shader = RadialGradient(w * 0.92f, -h * 0.1f, w * 0.45f, alpha(sitColor(m.sit), 22), alpha(sitColor(m.sit), 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
    }

    private fun topBar(c: Canvas, w: Float, pad: Float) {
        val cy = pad + 3f * S; val ph = 5f * S
        // 상황 칩(세 번 누르면 디버그)
        val sc = sitColor(m.sit)
        var x = pad
        p.textSize = 2.2f * S; p.typeface = bold
        val sw = p.measureText(m.sit.label) + 6.4f * S
        val sr = RectF(x, cy - ph / 2, x + sw, cy + ph / 2)
        rr(c, sr, ph / 2, alpha(sc, 40)); stroke(c, sr, ph / 2, alpha(sc, 110), S * 0.15f)
        val pulse = 0.6f + 0.4f * sin(SystemClock.elapsedRealtime() / 300f).let { if (m.sit == Sit.DRIVE) 1f else it }
        p.color = alpha(sc, (255 * pulse).toInt()); c.drawCircle(x + 2.4f * S, cy, 0.8f * S, p)
        text(c, m.sit.label, x + 4f * S, cy + 0.8f * S, 2.2f * S, FG, bold)
        hits += sr to "sitpill"
        x = sr.right + 1.2f * S
        // 숙련도
        m.skill?.let {
            val lab = if (it == "novice") "🔰 초보 모드" else "숙련 모드"
            x = pill(c, lab, x, cy, ph, SURF2, if (it == "novice") WARN else MUTED, 1.9f * S, "settings") + 1.2f * S
        }
        text(c, m.kicker.substringAfter(" · "), x + 0.6f * S, cy + 0.75f * S, 2f * S, FAINT, medium)
        // 오른쪽: 두뇌 · 포인트 · 꾸미기 · 설정
        var rx = w - pad
        val gs = ph
        val gear = RectF(rx - gs, cy - gs / 2, rx, cy + gs / 2)
        rr(c, gear, gs / 2, SURF2); text(c, "⚙", gear.centerX(), cy + 0.85f * S, 2.4f * S, MUTED, regular, Paint.Align.CENTER)
        hits += gear to "settings"; rx = gear.left - 1.2f * S
        rx = pillRight(c, "🎨 꾸미기", rx, cy, ph, alpha(m.accent(), 46), FG, 2f * S, "deco") - 1.2f * S
        rx = pillRight(c, "★ ${m.points}", rx, cy, ph, SURF2, WARN, 2f * S, "deco") - 2f * S
        val (bl, bc) = when (v.brain) { true -> "Claude 연결" to GOOD; false -> "로그인 필요" to WARN; null -> "서버 없음" to FAINT }
        p.textSize = 1.9f * S; p.typeface = medium
        val bw = p.measureText(bl)
        text(c, bl, rx, cy + 0.7f * S, 1.9f * S, MUTED, medium, Paint.Align.RIGHT)
        p.color = bc; c.drawCircle(rx - bw - 1.4f * S, cy, 0.6f * S, p)
    }

    // ======================================================================
    // 왼쪽 무대 — 캐릭터 · 말풍선 · 마이크
    // ======================================================================
    private fun stagePanel(c: Canvas, L: RectF, t: Float) {
        val a = m.accent()
        p.color = Color.WHITE; p.shader = LinearGradient(0f, L.top, 0f, L.bottom, SURF, mix(SURF, a, 0.06f), Shader.TileMode.CLAMP)
        c.drawRoundRect(L, 3f * S, 3f * S, p); p.shader = null
        stroke(c, L, 3f * S, LINE, S * 0.12f)
        val driving = m.sit == Sit.DRIVE
        val size = min(L.width() * 0.66f, L.height() * (if (driving) 0.44f else 0.48f))
        val cx = L.centerX(); val cy = L.top + L.height() * 0.34f
        character(c, cx, cy, size, t, v.botFace(), !driving, stickers = true)
        // 말풍선
        var y = cy + size * 0.56f + 2.4f * S
        val tx = L.left + 3f * S; val tw = L.width() - 6f * S
        if (driving) {
            val sp = "${v.kmh}"
            text(c, sp, cx, y + 3.6f * S, 5.2f * S, FG, bold, Paint.Align.CENTER)
            text(c, "km/h", cx, y + 6.4f * S, 1.8f * S, MUTED, medium, Paint.Align.CENTER)
            y += 9f * S
        } else if (v.userLine.isNotBlank()) {
            text(c, "나 · ${v.userLine}", tx, y + 1.6f * S, 1.9f * S, MUTED, regular, maxW = tw); y += 3.4f * S
        }
        val said = if (v.mode == HyunView.Mode.SPEAKING || v.hyunLine != m.lineAtEnter) v.hyunLine else m.bubble.ifBlank { v.hyunLine }
        if (said.isNotBlank()) {
            val bubble = RectF(tx - 1.2f * S, y, tx + tw + 1.2f * S, 0f)
            val lines = if (driving) 2 else 3
            val size2 = if (driving) 2.2f * S else 2.4f * S
            p.textSize = size2; p.typeface = medium
            val n = min(lines, lineCount(said, tw, size2))
            bubble.bottom = y + n * size2 * 1.42f + 2.6f * S
            rr(c, bubble, 2f * S, SURF2)
            // 꼬리
            path.reset(); path.moveTo(cx - 1.2f * S, y); path.lineTo(cx, y - 1.2f * S); path.lineTo(cx + 1.2f * S, y); path.close(); p.color = SURF2; c.drawPath(path, p)
            wrap(c, said, tx, y + 1.3f * S + size2, size2, FG, tw, lines, medium)
        }
        // 마이크 줄
        val r = 3.4f * S
        val my = L.bottom - 2.6f * S - r; val mx = L.left + 2.6f * S + r
        val mc = when (v.mode) { HyunView.Mode.LISTENING -> GOOD; HyunView.Mode.SPEAKING -> 0xFF3A4452.toInt(); else -> a }
        if (v.mode == HyunView.Mode.LISTENING) { p.color = alpha(GOOD, 60); c.drawCircle(mx, my, r * (1.25f + 0.1f * sin(t * 6)), p) }
        p.color = mc; c.drawCircle(mx, my, r, p)
        micGlyph(c, mx, my, r, if (v.mode == HyunView.Mode.READY || v.mode == HyunView.Mode.THINKING) BG else Color.WHITE)
        hits += RectF(mx - r, my - r, mx + r, my + r) to "mic"
        val sx = mx + r + 1.6f * S
        if (v.showListenToggle) {
            val sh = 4.4f * S; val segW = (L.right - 2.6f * S - sx) / 2
            val r1 = RectF(sx, my - sh / 2, sx + segW, my + sh / 2); val r2 = RectF(r1.right, r1.top, r1.right + segW, r1.bottom)
            rr(c, RectF(r1.left, r1.top, r2.right, r2.bottom), sh / 2, SURF2)
            val on = if (v.listenAlways) r1 else r2
            rr(c, RectF(on).apply { inset(0.4f * S, 0.4f * S) }, sh / 2, alpha(a, 70))
            text(c, "상시 듣기", r1.centerX(), my + 0.7f * S, 1.9f * S, if (v.listenAlways) FG else MUTED, medium, Paint.Align.CENTER)
            text(c, "눌러서 말하기", r2.centerX(), my + 0.7f * S, 1.9f * S, if (!v.listenAlways) FG else MUTED, medium, Paint.Align.CENTER)
            hits += r1 to "listen:always"; hits += r2 to "listen:ptt"
        }
        if (v.mode == HyunView.Mode.LISTENING || v.mode == HyunView.Mode.THINKING)
            text(c, if (v.mode == HyunView.Mode.LISTENING) "듣고 있어요" else "생각 중…", mx, my - r - 1.2f * S, 1.8f * S, if (v.mode == HyunView.Mode.LISTENING) GOOD else MUTED, medium, Paint.Align.CENTER)
    }

    /** 캐릭터 — 테마 조명 · 바닥 그림자 · 숨쉬기 · 소품·날씨 스티커 */
    private fun character(c: Canvas, cx: Float, cy: Float, size: Float, t: Float, face: String, bob: Boolean, stickers: Boolean) {
        val a = m.accent()
        p.color = Color.WHITE; p.shader = RadialGradient(cx, cy, size * 0.78f, alpha(a, 90), alpha(a, 0), Shader.TileMode.CLAMP); c.drawCircle(cx, cy, size * 0.78f, p); p.shader = null
        val dy = if (bob) sin(t * 1.4f) * size * 0.018f else 0f
        // 바닥 그림자
        val sh = 1f - dy / size * 4
        p.color = Color.WHITE; p.shader = RadialGradient(cx, cy + size * 0.47f, size * 0.36f * sh, 0x66000000, 0x00000000, Shader.TileMode.CLAMP)
        c.drawOval(RectF(cx - size * 0.36f * sh, cy + size * 0.42f, cx + size * 0.36f * sh, cy + size * 0.52f), p); p.shader = null
        v.asset("bot_$face.webp")?.let { bm ->
            p.isFilterBitmap = true; p.color = Color.WHITE
            c.drawBitmap(bm, null, RectF(cx - size / 2, cy - size / 2 + dy, cx + size / 2, cy + size / 2 + dy), p)
        }
        if (!stickers) return
        val acc = StageModel.ACCS.firstOrNull { it.id == m.acc }?.glyph.orEmpty()
        if (acc.isNotBlank()) emoji(c, acc, cx + size * 0.36f, cy - size * 0.34f + dy, size * 0.17f, -12f)
        val spots = listOf(-0.47f to -0.22f, 0.47f to 0.18f, -0.46f to 0.26f, 0.44f to -0.3f)
        m.weatherStickers.take(4).forEachIndexed { i, s ->
            val (sx, sy) = spots[i]
            val wob = if (bob) sin(t * 1.8f + i) * 4f else 0f
            emoji(c, s, cx + size * sx, cy + size * sy + dy * 1.5f, size * 0.15f, wob + (if (i % 2 == 0) -8f else 8f))
        }
    }

    // ======================================================================
    // 오른쪽 — 상황 추천(시동 · 출발 준비 · 정차 · 주차 · 시동 끔)
    // ======================================================================
    private fun situation(c: Canvas, R: RectF) {
        val sc = sitColor(m.sit)
        var y = R.top + 2.2f * S
        text(c, m.kicker.uppercase(), R.left + 0.4f * S, y, 1.9f * S, sc, bold)
        y += 5.6f * S
        val hs = fit(m.headline, 4.6f * S, R.width(), bold)
        text(c, m.headline, R.left, y, hs, FG, bold, maxW = R.width())
        y += 1.6f * S
        if (m.subline.isNotBlank()) y = wrap(c, m.subline, R.left, y + 2.6f * S, 2.3f * S, MUTED, R.width(), 2, regular)
        y += 1.6f * S
        val tipH = if (m.tip.isNotBlank()) 9.4f * S else 0f
        val bottom = R.bottom - (if (tipH > 0) tipH + 1.6f * S else 0f)
        val tiles = m.tiles
        if (tiles.size == 1) tile(c, tiles[0], RectF(R.left, y, R.right, min(bottom, y + 34f * S)), big = true)
        else if (m.sit == Sit.STOP) {   // 정차: 동네 이야기 크게 + 아래 한 줄(다음 안내)
            val lead = RectF(R.left, y, R.right, y + min(24f * S, (bottom - y) * 0.6f))
            tile(c, tiles[0], lead, big = true)
            val g = 1.6f * S; val n = tiles.size - 1; val tw = (R.width() - g * (n - 1)) / n
            tiles.drop(1).forEachIndexed { i, tl -> tile(c, tl, RectF(R.left + i * (tw + g), lead.bottom + g, R.left + i * (tw + g) + tw, min(bottom, lead.bottom + g + 17f * S)), big = false) }
        } else if (tiles.isNotEmpty()) {
            val cols = 2; val rows = ceil(tiles.size / 2f).toInt(); val g = 1.6f * S
            val tw = (R.width() - g) / cols; val th = min((bottom - y - g * (rows - 1)) / rows, 26f * S)
            tiles.forEachIndexed { i, tl ->
                val cx = R.left + (i % cols) * (tw + g); val cy = y + (i / cols) * (th + g)
                tile(c, tl, RectF(cx, cy, cx + tw, cy + th), big = false)
            }
        }
        if (tipH > 0) {
            val tr = RectF(R.left, R.bottom - tipH, R.right, R.bottom)
            rr(c, tr, 2.4f * S, alpha(WARN, 26)); stroke(c, tr, 2.4f * S, alpha(WARN, 70), S * 0.12f)
            val px = pill(c, "🔰 초보 팁", tr.left + 2f * S, tr.centerY(), 4.4f * S, alpha(WARN, 60), FG, 1.9f * S, null)
            wrap(c, m.tip, px + 1.8f * S, tr.centerY() - (if (lineCount(m.tip, tr.right - px - 4f * S, 2.2f * S) > 1) 0.9f * S else -0.8f * S), 2.2f * S, FG, tr.right - px - 4f * S, 2, medium)
        }
    }

    private fun tile(c: Canvas, tl: Tile, r: RectF, big: Boolean) {
        val col = tone(tl.tone)
        p.color = Color.WHITE; p.shader = LinearGradient(r.left, r.top, r.right, r.bottom, SURF, mix(SURF, col, 0.07f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, 2.6f * S, 2.6f * S, p); p.shader = null
        stroke(c, r, 2.6f * S, LINE, S * 0.12f)
        val ip = 2.2f * S
        val ic = 2.6f * S
        p.color = alpha(col, 40); c.drawCircle(r.left + ip + ic, r.top + ip + ic, ic, p)
        emoji(c, tl.icon, r.left + ip + ic, r.top + ip + ic, ic * 1.05f, 0f)
        text(c, tl.label, r.left + ip + ic * 2 + 1.4f * S, r.top + ip + ic + 0.7f * S, 1.9f * S, MUTED, medium, maxW = r.width() - ic * 2 - 14f * S)
        if (tl.tag.isNotBlank()) pillRight(c, tl.tag, r.right - ip, r.top + ip + ic, 3.2f * S, SURF2, FAINT, 1.6f * S, null)
        var y = r.top + ip + ic * 2 + 1.8f * S
        val vs = if (big) 3.2f * S else 3.1f * S
        val vl = if (big) 4 else 2
        val vf = fit(tl.value, vs, r.width() - ip * 2, bold).let { if (lineCount(tl.value, r.width() - ip * 2, vs) > 1 && !big) max(it, vs * 0.86f) else it }
        y = wrap(c, tl.value, r.left + ip, y + vf, if (big) vs else vf, FG, r.width() - ip * 2, vl, bold)
        if (tl.sub.isNotBlank() && y + 2f * S < r.bottom - 1f * S) wrap(c, tl.sub, r.left + ip, y + 0.6f * S, 2f * S, MUTED, r.width() - ip * 2, 2, regular)
        tl.action?.let { hits += r to it; text(c, "›", r.right - ip, r.bottom - ip, 3f * S, FAINT, regular, Paint.Align.RIGHT) }
    }

    // ======================================================================
    // 주행 중 — 길 안내 1장 + 내 차 상태 그림
    // ======================================================================
    private fun drive(c: Canvas, R: RectF, t: Float) {
        val a = m.accent()
        // 길 안내
        val nh = R.height() * 0.27f
        val nr = RectF(R.left, R.top, R.right, R.top + nh)
        p.color = Color.WHITE; p.shader = LinearGradient(nr.left, 0f, nr.right, 0f, mix(SURF, a, 0.10f), SURF, Shader.TileMode.CLAMP)
        c.drawRoundRect(nr, 3f * S, 3f * S, p); p.shader = null
        stroke(c, nr, 3f * S, LINE, S * 0.12f)
        val ib = nh - 4.4f * S
        val icx = nr.left + 2.2f * S + ib / 2; val icy = nr.centerY()
        p.color = alpha(a, 52); c.drawRoundRect(RectF(icx - ib / 2, icy - ib / 2, icx + ib / 2, icy + ib / 2), 2.4f * S, 2.4f * S, p)
        v.turnIcon(c, m.navTurnType.ifBlank { "TURN_STRAIGHT" }, icx, icy, ib * 0.8f, FG)
        p.style = Paint.Style.FILL; p.strokeWidth = 0f
        val tx = icx + ib / 2 + 2.6f * S
        text(c, v.dist(m.navTurnM), tx, nr.top + nh * 0.46f, 5.4f * S, FG, bold)
        text(c, m.navTurn, tx, nr.top + nh * 0.46f + 3.6f * S, 2.3f * S, MUTED, medium, maxW = nr.right - tx - 26f * S)
        val rx = nr.right - 2.4f * S
        text(c, m.navEta, rx, nr.top + nh * 0.40f, 2.6f * S, FG, bold, Paint.Align.RIGHT)
        text(c, "${m.navDest} · ${m.navRemain}", rx, nr.top + nh * 0.40f + 3.2f * S, 1.9f * S, MUTED, medium, Paint.Align.RIGHT)
        if (m.mockRoute) pillRight(c, "모의 경로", rx, nr.bottom - 2.6f * S, 3f * S, SURF2, FAINT, 1.5f * S, null)
        // 내 차 상태
        val cr = RectF(R.left, nr.bottom + 1.6f * S, R.right, R.bottom)
        rr(c, cr, 3f * S, SURF); stroke(c, cr, 3f * S, LINE, S * 0.12f)
        carStatus(c, cr, t)
    }

    private fun carStatus(c: Canvas, r: RectF, t: Float) {
        val st = m.status
        val a = m.accent()
        val soc = st?.soc?.v ?: 78; val socMock = st?.soc?.v == null
        val range = st?.rangeKm?.v ?: 412
        val turn = st?.turn?.v ?: 0
        val cabin = st?.cabinC?.v ?: 22f
        val locked = st?.locked?.v ?: true
        val cruise = st?.cruise?.v ?: "꺼짐"; val cruiseKmh = st?.cruiseKmh?.v
        val mode = st?.mode?.v ?: "NORMAL"
        val tripMin = st?.tripMin?.v ?: 0; val tripKm = st?.tripKm?.v ?: 0f
        text(c, "내 차", r.left + 2.6f * S, r.top + 4f * S, 2.2f * S, FG, bold)
        text(c, "Pleos 차량 SDK", r.left + 9.4f * S, r.top + 4f * S, 1.7f * S, FAINT, medium)
        // 가운데 차(위에서 본 모습)
        val ch = r.height() * 0.78f; val cw = ch * 0.46f
        val ccx = r.centerX(); val ccy = r.centerY() + 1.6f * S
        carTopDown(c, RectF(ccx - cw / 2, ccy - ch / 2, ccx + cw / 2, ccy + ch / 2), turn, t, cabin, locked, st?.cabinC?.v == null)
        // 왼쪽: 배터리 고리
        val gx = r.left + (ccx - cw / 2 - r.left) / 2; val gy = ccy; val gr = min((ccx - cw / 2 - r.left) * 0.34f, r.height() * 0.3f)
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeWidth = gr * 0.13f
        val arc = RectF(gx - gr, gy - gr, gx + gr, gy + gr)
        p.color = 0x22FFFFFF; c.drawArc(arc, 135f, 270f, false, p)
        val gc = if (soc < 20) RED else a
        p.color = Color.WHITE; p.shader = SweepGradient(gx, gy, intArrayOf(mix(gc, Color.WHITE, 0.3f), gc, gc), floatArrayOf(0f, 0.5f, 1f))
        c.drawArc(arc, 135f, 270f * soc / 100f, false, p); p.shader = null
        p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
        text(c, "$soc%", gx, gy + gr * 0.12f, gr * 0.42f, if (socMock) MUTED else FG, bold, Paint.Align.CENTER)
        text(c, st?.energyLabel?.v ?: "배터리", gx, gy + gr * 0.42f, gr * 0.15f, MUTED, medium, Paint.Align.CENTER)
        text(c, "주행 가능 ${range}km", gx, gy + gr + 3.2f * S, 2f * S, if (st?.rangeKm?.v == null) FAINT else FG, medium, Paint.Align.CENTER)
        // 오른쪽: 크루즈 · 주행 모드 · 이번 주행
        val rx0 = ccx + cw / 2 + 4f * S; val rx1 = r.right - 3f * S
        var y = r.top + r.height() * 0.24f
        val cOn = cruise == "작동"
        val cl = if (cOn) "크루즈 ${cruiseKmh ?: 0}km/h" else if (cruise == "대기") "크루즈 대기" else "크루즈 꺼짐"
        pill(c, "◎ $cl", rx0, y, 4.2f * S, if (cOn) alpha(GOOD, 50) else SURF2, if (cOn) GOOD else MUTED, 1.9f * S, null)
        y += 5.4f * S
        val mcol = when (mode) { "ECO" -> GOOD; "SPORT" -> RED; else -> INFO }
        pill(c, "주행 모드 · $mode", rx0, y, 4.2f * S, alpha(mcol, 36), mcol, 1.9f * S, null)
        y += 6.4f * S
        statRow(c, "이번 주행", "%.1fkm".format(tripKm), rx0, rx1, y); y += 6f * S
        statRow(c, "주행 시간", "${tripMin}분", rx0, rx1, y); y += 6f * S
        statRow(c, "누적", st?.odoKm?.v?.let { "%,dkm".format(it) } ?: "—", rx0, rx1, y)
        // 출처 표시
        val src = listOf(st?.soc, st?.turn, st?.tripKm, st?.cruise, st?.cabinC).mapNotNull { it?.from }
        val legend = "● Pleos SDK  ● AAOS 차량 API  ● 앱 측정  ○ 예시 값" +
            (if (src.none { it == CarStatus.From.PLEOS }) "   — Pleos 이미지에서 실제 값으로 바뀌어요" else "")
        text(c, legend, r.left + 2.6f * S, r.bottom - 2f * S, 1.5f * S, FAINT, regular, maxW = r.width() - 5f * S)
    }

    private fun statRow(c: Canvas, label: String, value: String, x0: Float, x1: Float, y: Float) {
        text(c, label, x0, y, 1.9f * S, MUTED, medium)
        text(c, value, x1, y, 2.6f * S, FG, bold, Paint.Align.RIGHT)
        p.color = LINE; c.drawRect(x0, y + 1.8f * S, x1, y + 1.8f * S + S * 0.1f, p)
    }

    /** 위에서 본 차 — 몸체 · 유리 · 바퀴 · 전조등/후미등 · 깜빡이(0.5초) · 실내 온도 · 문 잠김 */
    private fun carTopDown(c: Canvas, b: RectF, turn: Int, t: Float, cabin: Float, locked: Boolean, cabinMock: Boolean) {
        val a = m.accent()
        val w = b.width(); val h = b.height()
        // 바퀴
        p.color = 0xFF05080B.toInt()
        val ww = w * 0.13f; val wh = h * 0.15f
        for ((fx, fy) in listOf(0f to 0.2f, 1f to 0.2f, 0f to 0.78f, 1f to 0.78f)) {
            val x = b.left + fx * w; val y = b.top + fy * h
            c.drawRoundRect(RectF(x - ww / 2, y - wh / 2, x + ww / 2, y + wh / 2), ww * 0.4f, ww * 0.4f, p)
        }
        // 그림자 · 몸체
        p.color = Color.WHITE; p.shader = RadialGradient(b.centerX(), b.centerY(), h * 0.6f, alpha(a, 50), alpha(a, 0), Shader.TileMode.CLAMP)
        c.drawOval(RectF(b.left - w * 0.5f, b.top - h * 0.06f, b.right + w * 0.5f, b.bottom + h * 0.06f), p); p.shader = null
        val body = RectF(b.left + w * 0.04f, b.top, b.right - w * 0.04f, b.bottom)
        p.color = Color.WHITE; p.shader = LinearGradient(body.left, 0f, body.right, 0f, intArrayOf(0xFF26303C.toInt(), 0xFF3A4757.toInt(), 0xFF26303C.toInt()), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(body, w * 0.42f, w * 0.36f, p); p.shader = null
        stroke(c, body, w * 0.42f, alpha(a, 120), S * 0.18f)
        // 사이드미러
        p.color = 0xFF2C3744.toInt()
        c.drawOval(RectF(body.left - w * 0.1f, b.top + h * 0.3f, body.left + w * 0.04f, b.top + h * 0.35f), p)
        c.drawOval(RectF(body.right - w * 0.04f, b.top + h * 0.3f, body.right + w * 0.1f, b.top + h * 0.35f), p)
        // 앞유리 · 지붕 · 뒷유리
        val glass = intArrayOf(0xFF0D1620.toInt(), alpha(a, 90))
        path.reset()
        path.moveTo(body.left + w * 0.14f, b.top + h * 0.34f); path.lineTo(body.right - w * 0.14f, b.top + h * 0.34f)
        path.lineTo(body.right - w * 0.2f, b.top + h * 0.22f); path.quadTo(b.centerX(), b.top + h * 0.17f, body.left + w * 0.2f, b.top + h * 0.22f); path.close()
        p.color = Color.WHITE; p.shader = LinearGradient(0f, b.top + h * 0.18f, 0f, b.top + h * 0.34f, glass[1], glass[0], Shader.TileMode.CLAMP); c.drawPath(path, p)
        path.reset()
        path.moveTo(body.left + w * 0.16f, b.top + h * 0.8f); path.lineTo(body.right - w * 0.16f, b.top + h * 0.8f)
        path.lineTo(body.right - w * 0.2f, b.top + h * 0.87f); path.quadTo(b.centerX(), b.top + h * 0.9f, body.left + w * 0.2f, b.top + h * 0.87f); path.close()
        p.color = Color.WHITE; p.shader = LinearGradient(0f, b.top + h * 0.8f, 0f, b.top + h * 0.9f, glass[0], glass[1], Shader.TileMode.CLAMP); c.drawPath(path, p); p.shader = null
        val roof = RectF(body.left + w * 0.15f, b.top + h * 0.37f, body.right - w * 0.15f, b.top + h * 0.77f)
        p.color = Color.WHITE; p.shader = LinearGradient(0f, roof.top, 0f, roof.bottom, 0xFF1A232E.toInt(), 0xFF141B24.toInt(), Shader.TileMode.CLAMP)
        c.drawRoundRect(roof, w * 0.12f, w * 0.12f, p); p.shader = null
        // 전조등 · 후미등
        p.color = alpha(Color.WHITE, 200)
        c.drawRoundRect(RectF(body.left + w * 0.12f, b.top + h * 0.012f, body.left + w * 0.34f, b.top + h * 0.03f), S, S, p)
        c.drawRoundRect(RectF(body.right - w * 0.34f, b.top + h * 0.012f, body.right - w * 0.12f, b.top + h * 0.03f), S, S, p)
        p.color = alpha(RED, 220)
        c.drawRoundRect(RectF(body.left + w * 0.1f, b.bottom - h * 0.03f, body.right - w * 0.1f, b.bottom - h * 0.014f), S, S, p)
        // 깜빡이
        val blinkOn = (SystemClock.elapsedRealtime() / 500) % 2 == 0L
        if (turn != 0 && blinkOn) {
            val amber = 0xFFFFB020.toInt()
            fun glow(x: Float, y: Float) {
                p.color = Color.WHITE; p.shader = RadialGradient(x, y, w * 0.32f, alpha(amber, 230), alpha(amber, 0), Shader.TileMode.CLAMP); c.drawCircle(x, y, w * 0.32f, p); p.shader = null
                p.color = amber; c.drawCircle(x, y, w * 0.045f, p)
            }
            if (turn == 1 || turn == 3) { glow(body.left + w * 0.08f, b.top + h * 0.05f); glow(body.left + w * 0.08f, b.bottom - h * 0.05f) }
            if (turn == 2 || turn == 3) { glow(body.right - w * 0.08f, b.top + h * 0.05f); glow(body.right - w * 0.08f, b.bottom - h * 0.05f) }
        }
        if (turn != 0) {
            val lab = when (turn) { 1 -> "◀ 왼쪽 깜빡이"; 2 -> "오른쪽 깜빡이 ▶"; else -> "비상등" }
            text(c, lab, b.centerX(), b.top - 1.4f * S, 1.9f * S, 0xFFFFB020.toInt(), bold, Paint.Align.CENTER)
        }
        // 실내 온도 · 문 잠김
        text(c, "%.1f°".format(cabin), roof.centerX(), roof.centerY() - 0.4f * S, 3f * S, if (cabinMock) MUTED else FG, bold, Paint.Align.CENTER)
        text(c, "실내", roof.centerX(), roof.centerY() + 2.4f * S, 1.6f * S, MUTED, medium, Paint.Align.CENTER)
        lock(c, roof.centerX(), roof.bottom - 3.4f * S, 1.4f * S, if (locked) GOOD else WARN, locked)
    }

    private fun lock(c: Canvas, cx: Float, cy: Float, s: Float, col: Int, closed: Boolean) {
        p.color = col; p.style = Paint.Style.FILL
        c.drawRoundRect(RectF(cx - s, cy - s * 0.2f, cx + s, cy + s * 1.2f), s * 0.3f, s * 0.3f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = s * 0.3f
        val arc = RectF(cx - s * 0.62f, cy - s * 1.2f, cx + s * 0.62f, cy + s * 0.2f)
        if (closed) c.drawArc(arc, 180f, 180f, false, p) else c.drawArc(RectF(arc).apply { offset(s * 0.5f, -s * 0.2f) }, 200f, 160f, false, p)
        p.style = Paint.Style.FILL
    }

    // ======================================================================
    // 첫 실행 — 점검 → 운전 경험 → 맞춤 완료
    // ======================================================================
    private fun onboard(c: Canvas, w: Float, h: Float, t: Float) {
        val pad = 3.2f * S
        val size = min(h * 0.5f, w * 0.27f)
        val cx = w * 0.25f; val cy = h * 0.42f
        character(c, cx, cy, size, t, v.botFace(), true, stickers = false)
        text(c, "현비서", cx, cy + size * 0.6f + 2f * S, 2.4f * S, FG, bold, Paint.Align.CENTER)
        if (v.hyunLine.isNotBlank()) wrap(c, v.hyunLine, cx, cy + size * 0.6f + 6.2f * S, 2.2f * S, MUTED, w * 0.36f, 3, regular, center = true)
        val x0 = w * 0.5f; val x1 = w - pad * 2
        // 단계 막대
        val steps = listOf("차량 점검", "운전 경험", "맞춤 완료")
        val sw = (x1 - x0 - 2f * S) / 3
        steps.forEachIndexed { i, s ->
            val sx = x0 + i * (sw + S)
            val on = i <= m.onboardStep
            p.color = if (on) m.accent() else 0x22FFFFFF
            c.drawRoundRect(RectF(sx, pad + 2f * S, sx + sw, pad + 2.6f * S), S, S, p)
            text(c, "${i + 1}  $s", sx, pad + 6f * S, 1.9f * S, if (i == m.onboardStep) FG else FAINT, if (i == m.onboardStep) bold else medium)
        }
        val top = pad + 13f * S
        when (m.onboardStep) {
            0 -> onboardCheck(c, x0, x1, top, h - pad)
            1 -> onboardSkill(c, x0, x1, top, h - pad)
            else -> onboardDone(c, x0, x1, top, h - pad)
        }
    }

    private fun onboardCheck(c: Canvas, x0: Float, x1: Float, top: Float, bottom: Float) {
        text(c, "차를 먼저 살펴볼게요", x0, top + 4.4f * S, 4.6f * S, FG, bold)
        val car = m.check.firstOrNull { it.key == "car" }?.value ?: "차량 정보를 읽는 중…"
        text(c, car, x0, top + 9f * S, 2.3f * S, MUTED, medium)
        var y = top + 13f * S
        if (m.check.isEmpty()) {
            val a = (SystemClock.elapsedRealtime() / 3 % 360).toFloat()
            p.style = Paint.Style.STROKE; p.strokeWidth = 0.6f * S; p.color = m.accent(); p.strokeCap = Paint.Cap.ROUND
            c.drawArc(RectF(x0, y, x0 + 4f * S, y + 4f * S), a, 270f, false, p); p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
            return
        }
        val shown = ((SystemClock.elapsedRealtime() - m.checkAt) / 320 + 1).toInt()
        val items = m.check.filter { it.key != "car" }
        val rh = min(7.2f * S, (bottom - y - 9f * S) / items.size)
        items.take(shown).forEach { it2 ->
            val r = RectF(x0, y, x1, y + rh - 1f * S)
            rr(c, r, 1.8f * S, SURF)
            val col = when (it2.state) { CarCheck.State.OK -> GOOD; CarCheck.State.WARN -> WARN; CarCheck.State.NA -> FAINT }
            p.color = alpha(col, 50); c.drawCircle(r.left + 3.4f * S, r.centerY(), 1.7f * S, p)
            text(c, when (it2.state) { CarCheck.State.OK -> "✓"; CarCheck.State.WARN -> "!"; CarCheck.State.NA -> "–" }, r.left + 3.4f * S, r.centerY() + 0.75f * S, 2f * S, col, bold, Paint.Align.CENTER)
            text(c, it2.label, r.left + 6.8f * S, r.centerY() + (if (it2.note.isBlank()) 0.8f * S else -0.2f * S), 2.2f * S, FG, medium)
            if (it2.note.isNotBlank()) text(c, it2.note, r.left + 6.8f * S, r.centerY() + 2.4f * S, 1.6f * S, MUTED, regular)
            text(c, it2.value, r.right - 2.4f * S, r.centerY() + 0.85f * S, 2.4f * S, if (it2.state == CarCheck.State.NA) MUTED else FG, bold, Paint.Align.RIGHT)
            y += rh
        }
        if (shown >= items.size) button(c, "다음", RectF(x1 - 18f * S, bottom - 6.4f * S, x1, bottom), true, "onboard:next")
        text(c, "실제 차량 신호(AAOS)로 읽은 값 · '확인 불가'는 외부 앱에 열려 있지 않은 항목", x0, bottom - 2.6f * S, 1.6f * S, FAINT, regular, maxW = x1 - x0 - 22f * S)
    }

    private fun onboardSkill(c: Canvas, x0: Float, x1: Float, top: Float, bottom: Float) {
        text(c, "운전이 처음이세요?", x0, top + 4.4f * S, 4.8f * S, FG, bold)
        text(c, "말로 대답해도 돼요 — \"처음이야\" 또는 \"익숙해\"", x0, top + 9.2f * S, 2.3f * S, MUTED, medium)
        if (v.mode == HyunView.Mode.LISTENING) pillRight(c, "● 듣고 있어요", x1, top + 8.4f * S, 4f * S, alpha(GOOD, 40), GOOD, 1.8f * S, null)
        val y = top + 14f * S; val g = 2f * S; val cw = (x1 - x0 - g) / 2; val chh = min(bottom - y - 2f * S, 50f * S)
        val opts = listOf(
            Triple("novice", "🔰", listOf("처음이에요", "초보 운전", "출발·차선·주차를 하나씩 말로|회전 300m 앞에서 미리 알림|급정거하면 부드럽게 코칭|50분마다 쉼표 제안")),
            Triple("expert", "🚗", listOf("익숙해요", "숙련 운전", "꼭 필요한 알림만 짧게|화면은 조용히, 말은 줄여서|쉼표는 90분에 한 번|팁 없이 바로 길 안내")))
        opts.forEachIndexed { i, (k, icon, txt) ->
            val r = RectF(x0 + i * (cw + g), y, x0 + i * (cw + g) + cw, y + chh)
            val col = if (k == "novice") WARN else INFO
            p.color = Color.WHITE; p.shader = LinearGradient(r.left, r.top, r.right, r.bottom, mix(SURF, col, 0.12f), SURF, Shader.TileMode.CLAMP)
            c.drawRoundRect(r, 3f * S, 3f * S, p); p.shader = null
            stroke(c, r, 3f * S, alpha(col, 120), S * 0.2f)
            emoji(c, icon, r.left + 6f * S, r.top + 7f * S, 4.4f * S, 0f)
            text(c, txt[0], r.left + 3f * S, r.top + 16.6f * S, 3.6f * S, FG, bold)
            pill(c, txt[1], r.left + 3f * S, r.top + 21.6f * S, 3.6f * S, alpha(col, 50), col, 1.8f * S, null)
            var ly = r.top + 28f * S
            txt[2].split("|").forEach { line -> p.color = col; c.drawCircle(r.left + 3.6f * S, ly - 0.7f * S, 0.5f * S, p); text(c, line, r.left + 5.2f * S, ly, 2.1f * S, MUTED, regular, maxW = r.width() - 7f * S); ly += 4f * S }
            hits += r to "skill:$k"
        }
    }

    private fun onboardDone(c: Canvas, x0: Float, x1: Float, top: Float, bottom: Float) {
        val nov = m.novice
        text(c, if (nov) "초보 모드로 맞췄어요" else "숙련 모드로 맞췄어요", x0, top + 4.4f * S, 4.8f * S, FG, bold)
        text(c, "설정(⚙)에서 언제든 바꿀 수 있어요 · 말로 \"나 초보야\" / \"운전 익숙해\"", x0, top + 9.2f * S, 2.2f * S, MUTED, medium)
        var x = x0; val y = top + 15f * S
        (if (nov) listOf("🗣 말로 자주 챙김", "↪ 회전 미리 알림", "☁ 50분 쉼표") else listOf("🔕 알림 최소", "🧭 길 안내 위주", "☁ 90분 쉼표")).forEach {
            x = pill(c, it, x, y, 4.6f * S, SURF2, FG, 2f * S, null) + 1.2f * S
        }
        val r = RectF(x0, y + 5f * S, x1, y + 23f * S)
        p.color = Color.WHITE; p.shader = LinearGradient(r.left, r.top, r.right, r.bottom, alpha(WARN, 60), alpha(m.accent(), 40), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, 3f * S, 3f * S, p); p.shader = null
        emoji(c, "⭐", r.left + 7f * S, r.centerY(), 5f * S, -8f)
        text(c, "+100 환영 포인트", r.left + 13f * S, r.centerY() - 0.4f * S, 3.6f * S, FG, bold)
        text(c, "안전운전 100m = 1점 · 모은 포인트로 테마·표정·소품을 열어요", r.left + 13f * S, r.centerY() + 3.4f * S, 2f * S, FG, regular, maxW = r.right - r.left - 15f * S)
        button(c, "꾸미기 보러 가기", RectF(x1 - 46f * S, bottom - 6.4f * S, x1 - 22f * S, bottom), false, "onboard:deco")
        button(c, "시작하기", RectF(x1 - 20f * S, bottom - 6.4f * S, x1, bottom), true, "onboard:next")
    }

    // ======================================================================
    // 꾸미기
    // ======================================================================
    private fun deco(c: Canvas, w: Float, h: Float, t: Float) {
        val pad = 3.2f * S
        val L = RectF(pad, pad, w * 0.36f, h - pad)
        p.color = Color.WHITE; p.shader = LinearGradient(0f, L.top, 0f, L.bottom, SURF, mix(SURF, m.accent(), 0.12f), Shader.TileMode.CLAMP)
        c.drawRoundRect(L, 3f * S, 3f * S, p); p.shader = null
        text(c, "내 현비서", L.left + 3f * S, L.top + 5f * S, 2.4f * S, FG, bold)
        val face = v.faceOverride ?: m.face
        character(c, L.centerX(), L.top + L.height() * 0.48f, min(L.width() * 0.7f, L.height() * 0.55f), t, face, true, stickers = true)
        val theme = StageModel.THEMES.first { it.id == m.theme }.name
        val fn = StageModel.FACES.first { it.id == m.face }.name
        val an = StageModel.ACCS.first { it.id == m.acc }.name
        text(c, "$theme · $fn · $an", L.centerX(), L.bottom - 4f * S, 2f * S, MUTED, medium, Paint.Align.CENTER)
        val x0 = L.right + 3f * S; val x1 = w - pad
        text(c, "꾸미기", x0, pad + 5f * S, 4.6f * S, FG, bold)
        text(c, "차 안의 작은 인테리어 — 안전운전 포인트로 열어요 · 100m 안전운전 = 1점", x0, pad + 9.6f * S, 2.1f * S, MUTED, regular)
        button(c, "완료", RectF(x1 - 14f * S, pad + 1f * S, x1, pad + 6.4f * S), true, "close")
        pillRight(c, "★ ${m.points}점", x1 - 16f * S, pad + 3.7f * S, 5.4f * S, alpha(WARN, 40), WARN, 2.2f * S, null)
        var tx = x0; val ty = pad + 15.6f * S
        for ((k, lab) in listOf("theme" to "조명 테마", "face" to "기본 표정", "acc" to "소품")) {
            val on = m.decoTab == k
            tx = pill(c, lab, tx, ty, 5f * S, if (on) alpha(m.accent(), 70) else SURF2, if (on) FG else MUTED, 2.1f * S, "tab:$k") + 1.2f * S
        }
        val items = when (m.decoTab) { "face" -> StageModel.FACES; "acc" -> StageModel.ACCS; else -> StageModel.THEMES }
        val gy = ty + 4.6f * S; val g = 1.8f * S; val cols = 3
        val cw = (x1 - x0 - g * (cols - 1)) / cols; val ch = (h - pad - gy - g) / 2
        items.forEachIndexed { i, d ->
            val r = RectF(x0 + (i % cols) * (cw + g), gy + (i / cols) * (ch + g), 0f, 0f).also { it.right = it.left + cw; it.bottom = it.top + ch }
            val key = "${d.kind}:${d.id}"
            val own = key in m.owned
            val using = (d.kind == "theme" && m.theme == d.id) || (d.kind == "face" && m.face == d.id) || (d.kind == "acc" && m.acc == d.id)
            rr(c, r, 2.6f * S, SURF)
            stroke(c, r, 2.6f * S, if (using) m.accent() else LINE, if (using) S * 0.3f else S * 0.12f)
            val pcx = r.centerX(); val pcy = r.top + r.height() * 0.42f; val ps = min(r.width(), r.height()) * 0.5f
            when (d.kind) {
                "theme" -> {
                    p.color = Color.WHITE; p.shader = RadialGradient(pcx, pcy, ps * 0.6f, d.color, alpha(d.color, 0), Shader.TileMode.CLAMP); c.drawCircle(pcx, pcy, ps * 0.6f, p); p.shader = null
                    p.color = d.color; c.drawCircle(pcx, pcy, ps * 0.22f, p)
                }
                "face" -> v.asset("bot_${d.id}.webp")?.let { bm -> p.color = Color.WHITE; c.drawBitmap(bm, null, RectF(pcx - ps / 2, pcy - ps / 2, pcx + ps / 2, pcy + ps / 2), p) }
                else -> if (d.glyph.isBlank()) text(c, "없음", pcx, pcy + S, 2.4f * S, FAINT, medium, Paint.Align.CENTER) else emoji(c, d.glyph, pcx, pcy, ps * 0.38f, 0f)
            }
            if (!own && m.points < d.price) { p.color = 0x88090D12.toInt(); c.drawRoundRect(r, 2.6f * S, 2.6f * S, p) }
            text(c, d.name, r.left + 2f * S, r.bottom - 2.4f * S, 2.2f * S, FG, bold)
            val (st, sc) = when { using -> "사용 중" to m.accent(); own -> "보유" to MUTED; else -> "★ ${d.price}" to (if (m.points >= d.price) WARN else FAINT) }
            text(c, st, r.right - 2f * S, r.bottom - 2.4f * S, 2f * S, sc, bold, Paint.Align.RIGHT)
            hits += r to "item:$key"
        }
    }

    // ======================================================================
    // 설정 · 디버그 · 알림
    // ======================================================================
    private fun settings(c: Canvas, w: Float, h: Float) {
        p.color = 0xB3000000.toInt(); c.drawRect(0f, 0f, w, h, p)
        hits.clear()
        val r = RectF(w * 0.27f, h * 0.16f, w * 0.73f, h * 0.84f)
        rr(c, r, 3.4f * S, SURF2); stroke(c, r, 3.4f * S, LINE, S * 0.12f)
        hits += r to "noop"
        val x0 = r.left + 4f * S; val x1 = r.right - 4f * S
        text(c, "설정", x0, r.top + 7f * S, 3.8f * S, FG, bold)
        text(c, "운전 숙련도", x0, r.top + 14f * S, 2.1f * S, MUTED, medium)
        val sh = 6f * S; val sy = r.top + 16f * S; val sw = (x1 - x0) / 2
        listOf("novice" to "🔰 초보 운전", "expert" to "🚗 숙련 운전").forEachIndexed { i, (k, lab) ->
            val sr = RectF(x0 + i * sw, sy, x0 + (i + 1) * sw, sy + sh).apply { inset(0.4f * S, 0f) }
            val on = m.skill == k
            rr(c, sr, sh / 2, if (on) alpha(m.accent(), 80) else SURF)
            text(c, lab, sr.centerX(), sr.centerY() + 0.8f * S, 2.2f * S, if (on) FG else MUTED, bold, Paint.Align.CENTER)
            hits += sr to "skill:$k"
        }
        val desc = if (m.novice) "출발·정차·주차 팁을 말로 · 회전 300m 앞 차로 알림 · 급정거 코칭 · 50분 쉼표" else "꼭 필요한 알림만 · 팁 없이 바로 길 안내 · 90분 쉼표"
        wrap(c, desc, x0, sy + sh + 4f * S, 2f * S, MUTED, x1 - x0, 2, regular)
        val by = r.bottom - 8.4f * S
        button(c, "차량 점검 다시", RectF(x0, by, x0 + (x1 - x0) * 0.32f, by + 5.6f * S), false, "recheck")
        button(c, "꾸미기", RectF(x0 + (x1 - x0) * 0.34f, by, x0 + (x1 - x0) * 0.62f, by + 5.6f * S), false, "deco")
        button(c, "닫기", RectF(x1 - (x1 - x0) * 0.3f, by, x1, by + 5.6f * S), true, "close")
    }

    private fun debug(c: Canvas, w: Float, h: Float) {
        val r = RectF(w * 0.06f, h - 15f * S, w * 0.94f, h - 2f * S)
        rr(c, r, 2.4f * S, 0xF0161D27.toInt()); stroke(c, r, 2.4f * S, alpha(WARN, 120), S * 0.15f)
        text(c, "디버그 · 상황 바꾸기", r.left + 2.4f * S, r.top + 3.6f * S, 1.9f * S, WARN, bold)
        text(c, "adb: am broadcast -a io.github.somanghao.makecar.hyun.STAGE --es sit drive", r.right - 2.4f * S, r.top + 3.6f * S, 1.6f * S, FAINT, regular, Paint.Align.RIGHT)
        val list = Sit.values().map { it.key to it.label } + listOf("auto" to "자동(신호)", "x" to "닫기")
        val bw = (r.width() - 4.8f * S - 1f * S * (list.size - 1)) / list.size
        list.forEachIndexed { i, (k, lab) ->
            val br = RectF(r.left + 2.4f * S + i * (bw + S), r.top + 5.6f * S, 0f, r.bottom - 1.8f * S).also { it.right = it.left + bw }
            val on = k == m.sit.key && m.forced
            button(c, lab, br, on, if (k == "x") "debug" else "sit:$k")
        }
    }

    private fun toast(c: Canvas, w: Float, h: Float) {
        if (m.toast.isBlank() || SystemClock.elapsedRealtime() - m.toastAt > 2600) return
        p.textSize = 2.2f * S; p.typeface = medium
        val tw = p.measureText(m.toast) + 6f * S
        val r = RectF(w / 2 - tw / 2, h - 13f * S, w / 2 + tw / 2, h - 7f * S)
        rr(c, r, r.height() / 2, 0xF02A3340.toInt())
        text(c, m.toast, r.centerX(), r.centerY() + 0.8f * S, 2.2f * S, FG, medium, Paint.Align.CENTER)
    }

    // ======================================================================
    // 그리기 도구
    // ======================================================================
    private fun alpha(col: Int, a: Int) = (col and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)
    private fun mix(x: Int, y: Int, k: Float): Int {
        fun ch(sh: Int) = ((((x shr sh) and 0xFF) * (1 - k) + ((y shr sh) and 0xFF) * k).toInt() and 0xFF) shl sh
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }
    private fun rr(c: Canvas, r: RectF, rad: Float, col: Int) { p.shader = null; p.style = Paint.Style.FILL; p.color = col; c.drawRoundRect(r, rad, rad, p) }
    private fun stroke(c: Canvas, r: RectF, rad: Float, col: Int, sw: Float) { p.shader = null; p.style = Paint.Style.STROKE; p.strokeWidth = sw; p.color = col; c.drawRoundRect(r, rad, rad, p); p.style = Paint.Style.FILL }

    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, col: Int, face: Typeface = regular, align: Paint.Align = Paint.Align.LEFT, maxW: Float = Float.MAX_VALUE) {
        p.shader = null; p.style = Paint.Style.FILL; p.color = col; p.textSize = size; p.typeface = face; p.textAlign = align
        var line = s
        if (p.measureText(line) > maxW) { while (line.isNotEmpty() && p.measureText("$line…") > maxW) line = line.dropLast(1); line += "…" }
        c.drawText(line, x, y, p); p.textAlign = Paint.Align.LEFT
    }

    private fun emoji(c: Canvas, s: String, cx: Float, cy: Float, size: Float, rot: Float) {
        c.save(); c.rotate(rot, cx, cy)
        p.shader = null; p.color = Color.BLACK; p.textSize = size; p.typeface = regular; p.textAlign = Paint.Align.CENTER
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
    private fun lineCount(s: String, maxW: Float, size: Float): Int { p.textSize = size; p.typeface = medium; return splitLines(s, maxW).size }

    private fun wrap(c: Canvas, s: String, x: Float, y: Float, size: Float, col: Int, maxW: Float, maxLines: Int, face: Typeface = regular, center: Boolean = false): Float {
        p.textSize = size; p.typeface = face
        val lines = splitLines(s, maxW); val shown = lines.take(maxLines)
        shown.forEachIndexed { i, l ->
            val last = i == maxLines - 1 && lines.size > maxLines
            text(c, if (last) "$l…" else l, x, y + i * size * 1.42f, size, col, face, if (center) Paint.Align.CENTER else Paint.Align.LEFT, maxW)
        }
        return y + shown.size * size * 1.42f
    }

    private fun fit(s: String, max: Float, maxW: Float, face: Typeface): Float {
        var size = max; p.typeface = face
        while (size > max * 0.6f) { p.textSize = size; if (p.measureText(s) <= maxW) break; size *= 0.94f }
        return size
    }

    /** 왼쪽 기준 알약 — 반환 = 오른쪽 끝 x */
    private fun pill(c: Canvas, s: String, x: Float, cy: Float, h: Float, bg: Int, fg: Int, size: Float, action: String?): Float {
        p.textSize = size; p.typeface = medium
        val r = RectF(x, cy - h / 2, x + p.measureText(s) + h * 0.9f, cy + h / 2)
        rr(c, r, h / 2, bg)
        text(c, s, r.centerX(), cy + size * 0.36f, size, fg, medium, Paint.Align.CENTER)
        action?.let { hits += r to it }
        return r.right
    }
    /** 오른쪽 기준 알약 — 반환 = 왼쪽 끝 x */
    private fun pillRight(c: Canvas, s: String, x: Float, cy: Float, h: Float, bg: Int, fg: Int, size: Float, action: String?): Float {
        p.textSize = size; p.typeface = medium
        val wd = p.measureText(s) + h * 0.9f
        pill(c, s, x - wd, cy, h, bg, fg, size, action)
        return x - wd
    }

    private fun button(c: Canvas, s: String, r: RectF, primary: Boolean, action: String) {
        rr(c, r, r.height() / 2, if (primary) m.accent() else SURF2)
        if (!primary) stroke(c, r, r.height() / 2, LINE, S * 0.12f)
        text(c, s, r.centerX(), r.centerY() + 0.8f * S, 2.2f * S, if (primary) BG else FG, bold, Paint.Align.CENTER, r.width() * 0.92f)
        hits += r to action
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
