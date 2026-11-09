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
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { fontFeatureSettings = "tnum" }   // 숫자는 고정폭(tabular)
    private val glass = Glass()
    private val hits = mutableListOf<Pair<RectF, String>>()
    private val path = Path()
    private var S = 10f          // 화면 높이/100 (예전 코드 호환)
    private var U = 1f           // 스펙 px → 화면 px (높이 1000 기준)
    private var taps = 0; private var tapAt = 0L
    private var zoneL = 0f; private var zoneR = 0f
    private var pressRect: RectF? = null; private var pressAt = 0L
    private val BLOCKING = setOf("onboard", "deco", "settings")

    // ---------- 글꼴(Pretendard) ----------
    private val fonts = HashMap<String, Typeface>()
    private fun font(w: String): Typeface = fonts.getOrPut(w) {
        runCatching { Typeface.createFromAsset(v.context.assets, "fonts/Pretendard-$w.otf") }.getOrElse { Typeface.create("sans-serif", if (w == "Bold" || w == "SemiBold") Typeface.BOLD else Typeface.NORMAL) }
    }
    private val bold get() = if (skinned) sk.fonts.title else if (k.id == "pleos") font("Medium") else themeHead()   // unslop: 굵기 400~500 · 테마는 테마 글꼴 · 스킨은 스킨 글꼴
    private val semi get() = if (skinned) sk.fonts.bold else font("SemiBold")
    private val medium get() = if (skinned) sk.fonts.medium else if (k.id == "arcade" || k.id == "hanok") themeBody() else font("Medium")
    private val body get() = if (skinned) sk.fonts.body else themeBody()

    // ---------- 스킨(업스트림 ui/skin — assets/skins/<id>.json) = 그림체 한 벌. basic 이면 지금 모습(Pleos + 테마 9) 그대로 ----------
    //  스킨이 basic 이 아니면: 색·글꼴·카드 모양(잉크 테두리·딱딱한 그림자)·배경 무늬·말풍선 꼬리·스티커·캐릭터 쪽을 스킨이 정하고, 테마는 강조색만 얹는다(기본 테마면 스킨 강조색)
    private val sk get() = v.skin
    private val skinned get() = v.skin.id != "basic"
    private fun lum(c0: Int) = (0.299 * ((c0 shr 16) and 0xFF) + 0.587 * ((c0 shr 8) and 0xFF) + 0.114 * (c0 and 0xFF)) / 255.0

    // ---------- 색 토큰(스펙) — 밝은 카드 / 어두운 카드 ----------
    private var k: Concept = Concept.ALL[0]
    private var darkUi = false
    private val BG get() = if (skinned) sk.colors.bg else if (darkUi) 0xFF0E1116.toInt() else 0xFFF4F6F9.toInt()
    private val SURF get() = if (skinned) sk.colors.card else if (darkUi) 0xFF171B22.toInt() else 0xFFFFFFFF.toInt()
    private val SURF2 get() = if (skinned) sk.colors.card2 else if (darkUi) 0xFF1F2530.toInt() else 0xFFEEF1F5.toInt()
    private val FG get() = if (skinned) sk.colors.fg else if (darkUi) 0xFFF2F4F7.toInt() else 0xFF111827.toInt()
    private val MUTED get() = if (skinned) sk.colors.muted else if (darkUi) 0xFF9AA3B2.toInt() else 0xFF5B6472.toInt()
    private val FAINT get() = if (skinned) sk.colors.faint else if (darkUi) 0xFF6B7482.toInt() else 0xFF8C95A3.toInt()
    private val LINE get() = if (skinned) sk.colors.line else if (darkUi) 0xFF2A313C.toInt() else 0xFFE3E7ED.toInt()
    private val ACC get() = if (skinned) (if (k.id == "pleos") sk.colors.accent else k.accent) else if (k.id == "pleos") (if (darkUi) 0xFF4C8DFF.toInt() else 0xFF1E6BFF.toInt()) else k.accent
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

    fun animating() = m.sit == Sit.DRIVE && ((m.status?.turn?.v ?: 0) != 0 || SystemClock.elapsedRealtime() - m.carEventAt < 6500 || SystemClock.elapsedRealtime() - navMainAt < 900)
    private var lastNavMain = false; private var navMainAt = 0L

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
        val hr = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        darkUi = if (skinned) lum(sk.colors.bg) < 0.45 else if (k.id == "pleos") (m.nightOverride ?: (m.night || hr >= 20 || hr < 6)) else cardDark()   // 야간 주행이 기본 — 밤 시간엔 어두운 램프
        loadArt(k)
        val a = art
        if (skinned) skinBg(c, w, h) else if (a != null && artKey.startsWith("room_${k.room}")) drawArt(c, w, h, a) else if (m.overlay == "deco") ambient(c, RectF(0f, 0f, w, h), k, darkUi) else { scene(c, w, h, t, m.sit == Sit.DRIVE); if (m.overlay != "deco") motif(c, w, h, t, m.sit == Sit.DRIVE) }
        when (m.overlay) {
            "onboard" -> { scrim(c, w, h, 0.55f); onboard(c, w, h, t); return true }
            "deco" -> { shop(c, w, h, t); return true }
        }
        val driving = m.sit == Sit.DRIVE
        val now = SystemClock.elapsedRealtime()
        // 테마 인트로(시동 직후 1.6초) · 마무리(시동 끔)
        val intro = (m.sit == Sit.IGNITION && now - m.sitAt < 1800 && !m.viewForced)
        val outroX = if (m.sit == Sit.OFF && now - m.sitAt < 1800) (now - m.sitAt) / 1800f else -1f
        // 내비가 주화면(내비 레이아웃 · 주행 중) — 캐릭터는 왼쪽 위 작은 배지, 내비가 화면을 넓게(댄 11-09)
        val navMain = !intro && v.card == null && m.overlay !in setOf("brief", "todo", "car", "log") && (m.view == "nav" || (m.view == null && (driving || (m.sit == Sit.READY && m.navActive))))   // D 에서 경로가 있으면 저절로 내비 주화면
        if (navMain != lastNavMain) { lastNavMain = navMain; navMainAt = now }
        val nx = ((now - navMainAt) / 480f).coerceIn(0f, 1f); val ne = 1f - (1f - nx) * (1f - nx) * (1f - nx)
        val nk = if (navMain) ne else 1f - ne            // 0 = 보통 화면 · 1 = 내비 주화면
        zoneL = if (mirror) 48 * U else w * 0.4f; zoneR = if (mirror) w * 0.6f else w - 48 * U
        if (m.sit == Sit.OFF) scrim(c, w, h, ((now - m.sitAt) / 1500f).coerceIn(0f, 0.35f))
        // 캐릭터 — 보통: 화면 높이 33% · 내비 주화면: 14% 배지(왼쪽 위)
        val showLine = !glance() && !intro && m.view == null
        val bigH = h * 0.33f; val bigCx = if (mirror) w * 0.8f else w * 0.2f; val bigFeet = h * 0.7f
        val smH = h * 0.14f; val smCx = 40 * U + smH / 2; val smFeet = 16 * U + smH
        val chH = bigH + (smH - bigH) * nk; val cx = bigCx + (smCx - bigCx) * nk; val feet = bigFeet + (smFeet - bigFeet) * nk
        val react = m.reactFace.takeIf { now - m.reactAt < 1200 }
        val face0 = when { intro -> "waving"; m.sit == Sit.OFF && now - m.sitAt > 2500 -> "sleep"; react != null && v.mode == HyunView.Mode.READY -> react; driving -> "driving"
            m.view != null && v.mode == HyunView.Mode.READY -> VIEW_FACE[m.view!!] ?: v.botFace(); else -> v.botFace() }
        val celebrate = m.unlockAt > 0 && now - m.unlockAt < 1500
        val face = if (celebrate) face0 else calm(face0)
        character(c, cx, feet, chH, t, face, alive = !driving && nk < 0.5f, small = nk > 0.5f)
        if (!intro) {
            if (nk > 0.5f) speech(c, cx + chH * 0.6f, 16 * U + chH * 0.42f, w * 0.5f, navLine())
            else if (mirror) speech(c, zoneR + 64 * U, feet - chH - 56 * U, w - zoneR - 128 * U, tailX = cx)
            else speech(c, 64 * U, feet - chH - 56 * U, zoneL - 128 * U, tailX = cx)
            if (skinned && !glance() && nk < 0.5f) sticker(c, cx, feet, chH, face)
        }
        if (showLine && nk < 0.01f) { if (mirror) timeline(c, zoneR + 64 * U, w - 160 * U, h - 100 * U) else timeline(c, 64 * U, zoneL - 64 * U, h - 100 * U) }
        if (!glance()) topRight(c, w)   // 운전 중엔 누르는 것 없음(별 · 테마 · 설정 숨김)
        if (intro) {   // 테마 인트로
            if (!skinned) themeIntro(c, w, h, ((now - m.sitAt) / 1800f).coerceIn(0f, 1f), false)
        } else {
            if (nk > 0.01f) {   // 내비 주화면 — 아래에서 올라오며 나타남
                if (nk < 1f) c.saveLayerAlpha(0f, 0f, w, h, (nk * 255).toInt()) else c.save()
                c.translate(0f, (1f - nk) * 24 * U)
                navMainScreen(c, RectF(40 * U, 16 * U + smH + 28 * U, w - 40 * U, h - 32 * U), w, h)
                c.restore()
                if (nk > 0.5f) viewChips(c, cx + chH * 0.6f, 16 * U + chH * 0.42f + 44 * U)
            }
            if (nk < 0.99f) {
                if (nk > 0f) c.saveLayerAlpha(0f, 0f, w, h, ((1f - nk) * 255).toInt()) else c.save()
                header(c)
                val card = v.card
                val sheetOn = m.overlay in setOf("brief", "todo", "car", "log")
                val bottom = h - 48 * U - 64 * U - 20 * U
                var top = 96 * U
                if (m.navActive && !navMain) { navStrip(c, RectF(zoneL, top, zoneR, top + 84 * U)); top += 84 * U + 16 * U }
                val full = RectF(zoneL, top, zoneR, bottom)
                when {
                    sheetOn -> sheet(c, full, m.overlay!!)
                    card != null -> { glassCard(c, full, 24 * U); val lt = v.lightTheme; v.lightTheme = !darkUi; v.listCard(c, card, full.left + 24 * U, full.top + 24 * U, full.right - 24 * U, full.bottom - 24 * U, h); v.lightTheme = lt }
                    m.view != null && m.view != "nav" -> {   // 모듈 상세 — 왼쪽 위 끝에 닫기
                        button(c, "닫기", RectF(full.left, full.top, full.left + 110 * U, full.top + 52 * U), false, "view:close")
                        category(c, RectF(full.left, full.top + 68 * U, full.right, full.bottom), m.view!!)
                    }
                    !driving -> stateScreen(c, full)
                }
                if (!glance()) dock(c, w, h)   // 운전 중엔 독도 숨김(한눈 규칙)
                c.restore()
            }
            if (glance()) micHint(c, w, h) else micButton(c, w, h)
        }
        if (outroX >= 0f && !skinned) themeIntro(c, w, h, outroX, true)
        if (m.unlockAt > 0 && now - m.unlockAt < 1200) doors(c, w, h, (now - m.unlockAt) / 1200f)
        if (!skinned) crt(c, w, h)
        when (m.overlay) { "settings" -> settings(c, w, h); "debug" -> debug(c, w, h) }
        toast(c, w, h)
        return true
    }

    /** 평소엔 차분한 표정만(댄 11-08 "유아스러움 줄이기") — 장난스러운 표정은 해금 축하 때만 */
    private fun calm(f: String): String = when (f) {
        "neutral", "neutral2", "happy", "thinking", "driving", "sleep", "tired", "bored", "skeptical" -> f
        "laugh", "excited", "cheer", "humming", "love", "blush", "heart_eyes", "kiss", "wink", "playful", "sparkle", "proud", "cool", "shy", "thumbs_up", "waving" -> "happy"
        "surprised", "alert", "confused", "peek" -> "neutral2"
        else -> "neutral"
    }
    /** 어른스러운 말투 — 느낌표를 줄인다 */
    private fun adult(s: String) = s.replace("!", ".").replace("..", ".").replace(". .", ".")

    fun touch(x: Float, y: Float): Boolean {
        val hit = hits.lastOrNull { (r, _) -> RectF(r).apply { inset(-S * 0.6f, -S * 0.6f) }.contains(x, y) } ?: return m.overlay in BLOCKING
        val a = hit.second
        // 운전 중(출발 준비 D · 주행 · 신호대기)엔 손으로 고르는 것 없음 — 말로만. 개발 도구(디버그 판 · 미리보기 해제)만 예외
        val dev = a == "sitpill" || a == "debug" || a == "view:auto" || a == "view:close" || a == "nav:toggle" || a.startsWith("force:") || a.startsWith("sim:") || a.startsWith("sit:")
        if (glance() && !dev && m.overlay != "debug") return true
        pressRect = hit.first; pressAt = SystemClock.elapsedRealtime()
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
        p.shader = null; p.style = Paint.Style.FILL
        if (skinned) { inkCard(c, r, rad, tint); return }
        if (r.height() > 70 * U && themeCard(c, r, rad, tint)) return
        val sp = Path().apply { addRoundRect(r, rad, rad, Path.Direction.CW) }
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
    private fun character(c: Canvas, cx: Float, feet: Float, size: Float, t: Float, face: String, alive: Boolean, small: Boolean = false) {
        val breathe = 1f   // unslop: 라이브가 아닌 반복 모션 없음
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
            else -> GLOW to 0
        }
        if (ga > 0) { p.shader = RadialGradient(cx, cy, s * 0.62f, Glass.a(gc, ga), Glass.a(gc, 0), Shader.TileMode.CLAMP); c.drawCircle(cx, cy, s * 0.62f, p); p.shader = null }
        val bot = v.asset("bot_$face.webp")
        if (m.overlay != "deco" && !small && !skinned) aroundCharacter(c, cx, cy, s, t, alive, bot)
        accParts(c, cx, cy, s, face, back = true)   // 헬멧 뒤로 가는 조각(머리띠 · 반대쪽 헤드폰 · 넥타이)
        bot?.let { bm -> p.isFilterBitmap = true; p.color = Color.WHITE; c.drawBitmap(bm, null, RectF(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2), p) }
        accParts(c, cx, cy, s, face, back = false)
        if (small) return   // 작은 배지 — 날씨 소품·방 소품은 생략(입은 소품은 함께 줄어든다)
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

    /** 사용자가 고른 소품 — 캐릭터 그림은 그대로 두고 조각만 얹는다. 위치는 기본 표정 헬멧 기준, 운전 자세는 헬멧이 0.92배 · 조금 위 */
    private val srcR = android.graphics.Rect()
    private fun accParts(c: Canvas, cx: Float, cy: Float, s: Float, face: String, back: Boolean) {
        val drive = face == "driving"
        val a = if (drive) 0.92f else 1f; val ox = if (drive) 0.005f else 0f; val oy = if (drive) -0.03f else 0f
        for (slot in listOf("neck", "face", "eyes", "hair", "head", "effect")) {
            val id = m.equipped[slot] ?: continue
            if (drive && (slot == "neck" || slot == "face")) continue   // 운전대와 겹침
            val d = StageModel.ACC_DEFS.firstOrNull { it.id == id } ?: continue
            val bm = v.asset("acc_$id.webp") ?: continue
            for (pt in d.parts) {
                if (pt.back != back) continue
                srcR.set((pt.cx0 * bm.width).toInt(), (pt.cy0 * bm.height).toInt(), (pt.cx1 * bm.width).toInt(), (pt.cy1 * bm.height).toInt())
                val aw = pt.w * s * a; val ah = aw * srcR.height() / max(1, srcR.width())
                val ax = cx + (pt.dx * a + ox) * s; val ay = cy + (pt.dy * a + oy) * s
                c.save(); if (pt.rot != 0f) c.rotate(pt.rot, ax, ay)
                p.color = Color.WHITE; p.isFilterBitmap = true
                c.drawBitmap(bm, srcR, RectF(ax - aw / 2, ay - ah / 2, ax + aw / 2, ay + ah / 2), p); c.restore()
            }
        }
    }

    // ======================================================================
    // 우상단 알약 하나 — ★ 포인트 · 테마 (+ 작은 설정)
    // ======================================================================
    private fun topRight(c: Canvas, w: Float) {
        val h = 48 * U; val cy = 48 * U
        if (k.id == "arcade" && !skinned) {   // 8비트 점수판
            val g = tf("Galmuri11.ttf")
            text(c, "SCORE %06d".format(m.points), w - 48 * U, cy + 6 * U, 26 * U, 0xFFFFE14D.toInt(), g, Paint.Align.RIGHT)
            text(c, "HI %06d".format(max(m.points, 1200)), w - 48 * U, cy + 36 * U, 18 * U, 0xFF00F0FF.toInt(), g, Paint.Align.RIGHT)
            p.textSize = 26 * U; p.typeface = g; hits += RectF(w - 48 * U - p.measureText("SCORE 000000"), cy - 24 * U, w - 48 * U, cy + 40 * U) to "deco"
            return
        }
        val g = RectF(w - 48 * U - h, cy - h / 2, w - 48 * U, cy + h / 2)
        glassCard(c, g, 12 * U); icon(c, "gear", g.centerX(), cy, 22 * U, MUTED); hits += g to "settings"
        val lab = "${m.points}  ·  테마"
        p.textSize = 22 * U; p.typeface = semi
        val wd = p.measureText(lab) + 64 * U
        val r = RectF(g.left - 12 * U - wd, cy - h / 2, g.left - 12 * U, cy + h / 2)
        glassCard(c, r, 12 * U)
        icon(c, "star", r.left + 26 * U, cy, 22 * U, MUTED)
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
        val title = adult(tl?.value?.let { firstSentence(it) } ?: m.headline)
        val bodyText = (tl?.value?.let { restOf(it) }?.ifBlank { null } ?: tl?.sub)?.let { adult(it) }
        val caption = if (bodyText == tl?.sub || m.sit == Sit.IGNITION || m.sit == Sit.PARK) null else tl?.sub   // 그림 칸이 있으면 캡션 생략(겹침 방지)
        val ledger = tl?.lines.orEmpty()
        // 높이 계산
        val titleSize = 50 * U * headScale(); val bodySize = 27 * U
        val newsImg = tl?.takeIf { it.icon == "news" }?.let { m.newsImgs[m.newsList.firstOrNull()] }
        val imgH = if (m.sit == Sit.IGNITION || m.sit == Sit.PARK || newsImg != null) 150 * U else 0f
        val titleLines = min(2, lineCount(title, iw, titleSize, bold))
        val bodyLines = bodyText?.let { min(2, lineCount(it, iw, bodySize, body)) } ?: 0
        var hh = imgH + 24 * U + pad + 40 * U + 24 * U + titleLines * titleSize * 1.25f + (if (bodyLines > 0) 16 * U + bodyLines * bodySize * 1.45f else 0f) +
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
        glassCard(c, r, 24 * U)
        if (imgH > 0) heroImage(c, RectF(r.left, r.top, r.right, r.top + imgH), tl)
        var y = r.top + imgH + (if (imgH > 0) pad * 0.7f else pad)
        // 상황 칩
        val chip = "${m.sit.label} · ${m.kicker.substringAfter(" · ")}" + (tl?.label?.let { "  ·  $it" } ?: "")
        p.textSize = 20 * U; p.typeface = semi
        val cw = min(p.measureText(chip) + 8 * U, iw)
        val cr = RectF(r.left + pad, y, r.left + pad + cw, y + 40 * U)
        text(c, chip, cr.left, cr.centerY() + 7 * U, 20 * U, MUTED, medium, maxW = cw + 40 * U)
        hits += cr to "sitpill"
        if (tl?.tag?.isNotBlank() == true) text(c, tl.tag, r.right - pad, cr.centerY() + 7 * U, 20 * U, FAINT, medium, Paint.Align.RIGHT)
        y = cr.bottom + 24 * U
        // 제목
        y = wrap(c, title, r.left + pad, y + titleSize * 0.92f, titleSize, if (k.id == "cafe") 0xFFEFEDE4.toInt() else FG, iw, 2, bold, lh = 1.25f) - titleSize * 0.92f + titleSize * 0.25f
        divider(c, r.left + pad, r.right - pad, y + 14 * U); y += 24 * U
        if (k.id == "hanok") { var vy = r.top + imgH + 52 * U; for (ch in m.sit.label) { text(c, ch.toString(), r.left - 28 * U, vy, 24 * U, if (sceneDark) 0xEEFFF4E2.toInt() else 0xFF3A2A1E.toInt(), tf("GowunBatang-Regular.ttf"), Paint.Align.CENTER); vy += 30 * U } }   // 세로 글씨
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
            text(c, lab, r.right - pad, by + 40 * U, 22 * U, MUTED, medium, Paint.Align.RIGHT)
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
    // 살아 있는 배경 — 시간대 하늘 · 날씨(실제 날씨 + 와이퍼) · 구름·별·해/달 · 먼 언덕 · 바닥. 컨셉이 색을 물들인다
    // 주행 중엔 아주 느리게(멈추진 않음). 도형은 부드러운 원·곡선만
    // ======================================================================
    private fun mixC(x: Int, y: Int, k2: Float): Int {
        fun ch(sh: Int) = ((((x shr sh) and 0xFF) * (1 - k2) + ((y shr sh) and 0xFF) * k2).toInt() and 0xFF) shl sh
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }
    private val stars by lazy { val r = java.util.Random(11); List(90) { floatArrayOf(r.nextFloat(), r.nextFloat() * 0.55f, 0.6f + r.nextFloat() * 1.4f, r.nextFloat() * 6.28f) } }
    private val drops by lazy { val r = java.util.Random(5); List(80) { floatArrayOf(r.nextFloat(), r.nextFloat(), 0.6f + r.nextFloat() * 0.8f) } }

    private var sceneDark = false
    /** 절제된 하늘 — 채도 낮은 그라데이션 + 은은한 빛 번짐 하나 + 가는 빗줄기/눈. 언덕·달·구름 같은 사물은 없음(프리미엄 차 화면 톤) */
    private fun scene(c: Canvas, w: Float, h: Float, t0: Float, still: Boolean) {
        val t = if (still) t0 * 0.2f else t0
        val hour = java.util.Calendar.getInstance().let { it.get(java.util.Calendar.HOUR_OF_DAY) + it.get(java.util.Calendar.MINUTE) / 60f }
        val nightSky = darkUi || hour >= 19.5f || hour < 5f
        sceneDark = nightSky || (k.id != "pleos" && k.darkCard)
        var top: Int; var bot: Int
        when {
            nightSky -> { top = 0xFF0E131B.toInt(); bot = 0xFF1B222D.toInt() }
            hour < 7f -> { top = 0xFFE6DCD6.toInt(); bot = 0xFFF5F1EE.toInt() }
            hour >= 17f -> { top = 0xFFE2D3CA.toInt(); bot = 0xFFF4EEEA.toInt() }
            else -> { top = 0xFFDCE4EC.toInt(); bot = 0xFFF5F7F9.toInt() }
        }
        if (k.id != "pleos") { top = mixC(top, k.bg[1], 0.55f); bot = mixC(bot, k.bg[2], 0.5f); if (!k.darkCard && !nightSky) { top = mixC(top, Color.WHITE, 0.25f); bot = mixC(bot, Color.WHITE, 0.35f) } }
        if (m.raining || m.snowing) { val g = if (nightSky) 0xFF1E2530.toInt() else 0xFFCDD3DA.toInt(); top = mixC(top, g, 0.35f); bot = mixC(bot, g, 0.2f) }
        p.color = Color.WHITE; p.shader = LinearGradient(0f, 0f, 0f, h, top, bot, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, h, p)
        // 아래쪽 바닥 기운(선 없이 명도만)
        p.shader = LinearGradient(0f, h * 0.7f, 0f, h, Glass.a(Color.BLACK, 0), Glass.a(Color.BLACK, if (nightSky) 60 else 14), Shader.TileMode.CLAMP); c.drawRect(0f, h * 0.7f, w, h, p); p.shader = null
        if (m.raining) {
            p.color = Glass.a(if (nightSky) 0xFFC8D3E6.toInt() else 0xFF8C99A8.toInt(), if (nightSky) 46 else 60); p.strokeWidth = 1.2f * U
            for (d in drops) { val len = 34 * U * d[2]; val y = ((d[1] * h + t * 700 * U * d[2]) % (h + len)) - len; val x = d[0] * w - y * 0.12f
                c.drawLine(x, y, x - len * 0.12f, y + len, p) }
        } else if (m.snowing) {
            p.color = Glass.a(Color.WHITE, 150)
            for (d in drops) { val y = ((d[1] * h + t * 30 * U * d[2]) % h); val x = d[0] * w + sin(t * 0.6f + d[2] * 6f) * 10 * U; c.drawCircle(x, y, 2.2f * U * d[2], p) }
        }
    }

    /** 비 올 때 캐릭터가 드는 우산(부드러운 선) */
    private fun umbrella(c: Canvas, cx: Float, top: Float, s: Float) {
        val r = s * 0.3f; val cy = top + r * 0.6f
        c.save(); c.rotate(-18f, cx, cy + r)
        path.reset(); path.moveTo(cx - r, cy + r * 0.05f); path.cubicTo(cx - r, cy - r * 0.95f, cx + r, cy - r * 0.95f, cx + r, cy + r * 0.05f)
        for (i in 4 downTo 1) { val x2 = cx - r + (2 * r) * (i - 1) / 4f; path.quadTo(x2 + r / 4f, cy - r * 0.12f, x2, cy + r * 0.05f) }
        path.close()
        p.color = 0xFFFFC94A.toInt(); c.drawPath(path, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2.4f * U; p.color = 0xFFE0A22A.toInt(); p.strokeCap = Paint.Cap.ROUND
        for (i in 1..3) { val x2 = cx - r + (2 * r) * i / 4f; c.drawLine(cx, cy - r * 0.7f, x2, cy + r * 0.02f, p) }
        p.strokeWidth = 4f * U; p.color = 0xFF6B5A44.toInt()
        c.drawLine(cx, cy - r * 0.72f, cx, cy + r * 1.0f, p)
        c.drawArc(RectF(cx - r * 0.18f, cy + r * 0.92f, cx + r * 0.02f, cy + r * 1.16f), 0f, 180f, false, p)
        p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
        c.restore()
    }

    // ---------- 캐릭터 말풍선(짧게, 타자 치듯) ----------
    private var bubbleText = ""; private var bubbleAt = 0L
    private fun speech(c: Canvas, cx: Float, headTop: Float, maxW: Float, over: String? = null, tailX: Float? = null) {
        val full = when {
            over != null && v.mode == HyunView.Mode.READY -> over
            m.view != null && v.mode == HyunView.Mode.READY -> viewLine(m.view!!)
            v.mode == HyunView.Mode.LISTENING -> "듣고 있어요…"
            v.mode == HyunView.Mode.THINKING -> "음… 생각 중이에요"
            v.mode == HyunView.Mode.SPEAKING && v.hyunLine.isNotBlank() -> firstSentence(v.hyunLine)
            (SystemClock.elapsedRealtime() / 9000) % 2 == 1L && THEME_LINES[k.id] != null -> THEME_LINES[k.id]!![((SystemClock.elapsedRealtime() / 18000) % 2).toInt()]
            else -> m.tiles.getOrNull(m.heroIdx)?.label?.let { lab -> bubbleFor(lab) } ?: m.bubble.let { firstSentence(it) }
        }.let { if (it.length > 34) it.take(33) + "…" else it }
        if (full.isBlank()) return
        if (full != bubbleText) { bubbleText = full; bubbleAt = SystemClock.elapsedRealtime() }
        val n = if (m.sit == Sit.DRIVE) full.length else ((SystemClock.elapsedRealtime() - bubbleAt) / 38).toInt().coerceIn(0, full.length)   // 주행 중엔 화면을 자주 다시 그리지 않으니 타자 효과 없이
        val shown = full.take(n)
        val fs = 26 * U
        val col = if (sceneDark) 0xF2FFFFFF.toInt() else FG
        p.textSize = fs; p.typeface = medium
        val lines = splitLines(adult(full), maxW).take(2)
        var y = headTop
        if (skinned && sk.style.bubbleTail) {   // 스킨 말풍선 — 잉크 테두리 + 캐릭터 쪽 꼬리
            p.textSize = fs; p.typeface = medium
            val tw = lines.maxOfOrNull { p.measureText(it) } ?: 0f
            val br = RectF(cx, y - fs * 1.25f, cx + tw + 36 * U, y + (lines.size - 1) * fs * 1.4f + fs * 0.6f)
            val tx = (tailX ?: br.centerX()).coerceIn(br.left + 28 * U, br.right - 28 * U)
            val bw = sk.style.border * S * 100
            path.reset(); path.addRoundRect(br, 22 * U, 22 * U, Path.Direction.CW)
            val tail = Path().apply { moveTo(tx - 14 * U, br.bottom - 2 * U); lineTo(tx + 12 * U, br.bottom - 2 * U); lineTo(tx - 4 * U, br.bottom + 30 * U); close() }
            path.op(tail, Path.Op.UNION)
            if (sk.style.shadowX > 0f) { c.save(); c.translate(sk.style.shadowX * S * 60, sk.style.shadowY * S * 60); p.color = sk.style.shadowColor; c.drawPath(path, p); c.restore() }
            p.color = sk.colors.bubbleHyun; c.drawPath(path, p)
            if (bw > 0f) { p.style = Paint.Style.STROKE; p.strokeWidth = bw; p.color = sk.style.borderColor; c.drawPath(path, p); p.style = Paint.Style.FILL }
            var left2 = n
            for (ln in lines) { val part = ln.take(left2.coerceAtLeast(0)); left2 -= ln.length; text(c, part, cx + 18 * U, y, fs, sk.colors.fg, medium); y += fs * 1.4f }
            return
        }
        p.color = ACC; c.drawRect(cx, y - fs * 0.9f, cx + 3 * U, y + (lines.size - 1) * fs * 1.4f + fs * 0.25f, p)
        var left = n
        for (ln in lines) { val part = ln.take(left.coerceAtLeast(0)); left -= ln.length
            text(c, part, cx + 18 * U, y, fs, col, medium); y += fs * 1.4f }
    }
    private fun bubbleFor(label: String): String = when (label) {
        "오늘 날씨" -> if (m.raining) "비가 와요. 천천히 가요" else "오늘 날씨를 정리했어요"
        "차 컨디션" -> "차 상태는 괜찮아요"
        "관심 뉴스", "오늘의 헤드라인" -> "오늘 소식 하나 골라 뒀어요"
        "추천 목적지", "오늘 목적지" -> "어디로 갈까요?"
        "이번 주행" -> "수고하셨어요"
        "안전운전 포인트" -> "이번 주행 기록이에요"
        "쉼표" -> "잠깐 쉬어 가요"
        "동승자 모드" -> "반가워요"
        "비 오는 날" -> "비가 와요. 간격을 넉넉히"
        else -> label
    }

    // ---------- 오늘의 드라이브 — 가로 타임라인(출발 · 거리 · 별 · 정차) ----------
    private fun timeline(c: Canvas, x0: Float, x1: Float, y: Float) {
        val start = if (m.dayStartAt > 0) java.text.SimpleDateFormat("HH:mm", java.util.Locale.KOREA).format(java.util.Date(m.dayStartAt)) else "아직"
        val nodes = listOf("출발" to start, "달린 거리" to "%.1fkm".format(m.dayKmShown), "오늘 별" to "${if (m.todayPoints >= 0) "+" else ""}${m.todayPoints}", "정차" to "${m.stopsToday}회")
        val onScene = if (sceneDark) 0xEEFFFFFF.toInt() else FG
        val sub = if (sceneDark) 0xAAFFFFFF.toInt() else MUTED
        text(c, "오늘의 드라이브", x0, y - 30 * U, 20 * U, sub, semi)
        p.color = Glass.a(onScene, 70); c.drawRect(x0, y - 1 * U, x1, y + 1 * U, p)
        nodes.forEachIndexed { i, (lab, v2) ->
            val x = x0 + (x1 - x0) * (i + 0.5f) / nodes.size
            p.color = if (i == 0 && m.dayStartAt > 0) ACC else Glass.a(onScene, 120); c.drawCircle(x, y, 5 * U, p)
            p.color = if (darkUi) SURF else Color.WHITE; c.drawCircle(x, y, 2.5f * U, p)
            text(c, v2, x, y + 34 * U, 24 * U, onScene, bold, Paint.Align.CENTER)
            text(c, lab, x, y + 58 * U, 18 * U, sub, medium, Paint.Align.CENTER)
        }
    }

    // ---------- 카드 위 그림 칸 — 날씨 그림 · 경로 미니맵 · 기사 사진 ----------
    private fun heroImage(c: Canvas, r: RectF, tl: Tile?) {
        val rad = 28 * U
        c.save(); c.clipPath(Path().apply { addRoundRect(RectF(r.left, r.top, r.right, r.bottom + rad), floatArrayOf(rad, rad, rad, rad, 0f, 0f, 0f, 0f), Path.Direction.CW) }); c.clipRect(r)
        val img = tl?.takeIf { it.icon == "news" }?.let { m.newsImgs[m.newsList.firstOrNull()] }?.let { v.urlBmp(it) }
        when {
            img != null -> { val sc = max(r.width() / img.width, r.height() / img.height); val bw = img.width * sc; val bh = img.height * sc
                c.drawBitmap(img, null, RectF(r.centerX() - bw / 2, r.centerY() - bh / 2, r.centerX() + bw / 2, r.centerY() + bh / 2), bp)
                p.shader = LinearGradient(0f, r.bottom - 60 * U, 0f, r.bottom, 0x00000000, 0x55000000, Shader.TileMode.CLAMP); c.drawRect(r, p); p.shader = null
                text(c, "연합뉴스", r.right - 16 * U, r.bottom - 14 * U, 16 * U, 0xDDFFFFFF.toInt(), medium, Paint.Align.RIGHT) }
            tl?.icon == "road" || tl?.icon == "star" || m.sit == Sit.PARK -> miniMap(c, r)
            else -> weatherArt(c, r)
        }
        c.restore()
    }

    private fun weatherArt(c: Canvas, r: RectF) {
        val night = darkUi
        p.shader = LinearGradient(r.left, r.top, r.right, r.bottom, if (night) 0xFF1B2433.toInt() else 0xFFE6ECF2.toInt(), if (night) 0xFF242E40.toInt() else 0xFFF6F8FA.toInt(), Shader.TileMode.CLAMP)
        p.color = Color.WHITE; c.drawRect(r, p); p.shader = null
        val col = if (night) Color.WHITE else FG
        val t2 = m.tempNow?.let { "$it°" } ?: "—"
        text(c, t2, r.left + 40 * U, r.bottom - 34 * U, 84 * U, col, font("SemiBold"))
        p.textSize = 84 * U; p.typeface = font("SemiBold")
        val tx = r.left + 40 * U + p.measureText(t2) + 24 * U
        text(c, m.weatherShort.substringAfter(" ", "").ifBlank { "날씨" }, tx, r.bottom - 74 * U, 24 * U, col, semi)
        text(c, m.weatherLine.substringAfter(" · ", "").ifBlank { "" }, tx, r.bottom - 42 * U, 20 * U, if (night) 0xAAFFFFFF.toInt() else MUTED, medium, maxW = r.right - tx - 40 * U)
        icon(c, "cloud", r.right - 64 * U, r.top + 56 * U, 44 * U, if (night) 0xAAFFFFFF.toInt() else MUTED)
    }

    private fun miniMap(c: Canvas, r: RectF) {
        p.color = if (darkUi) 0xFF1C2330.toInt() else 0xFFEDF1F5.toInt(); c.drawRect(r, p)
        p.color = if (darkUi) 0xFF242C3A.toInt() else 0xFFE1E7EE.toInt()
        val bw = r.width() / 6; val bh = r.height() / 3
        for (i in 0 until 6) for (j in 0 until 3) if ((i + j) % 3 != 0) c.drawRoundRect(RectF(r.left + i * bw + 8 * U, r.top + j * bh + 8 * U, r.left + (i + 1) * bw - 8 * U, r.top + (j + 1) * bh - 8 * U), 6 * U, 6 * U, p)
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        p.color = Glass.a(ACC, 60); p.strokeWidth = 14 * U
        path.reset(); path.moveTo(r.left + 40 * U, r.bottom - 30 * U); path.cubicTo(r.left + r.width() * 0.3f, r.bottom - 30 * U, r.left + r.width() * 0.35f, r.top + 30 * U, r.left + r.width() * 0.6f, r.top + r.height() * 0.45f)
        path.cubicTo(r.left + r.width() * 0.78f, r.top + r.height() * 0.6f, r.right - 80 * U, r.top + 40 * U, r.right - 50 * U, r.top + 36 * U)
        c.drawPath(path, p); p.color = ACC; p.strokeWidth = 6 * U; c.drawPath(path, p)
        p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
        p.color = Color.WHITE; c.drawCircle(r.left + 40 * U, r.bottom - 30 * U, 10 * U, p); p.color = ACC; c.drawCircle(r.left + 40 * U, r.bottom - 30 * U, 6 * U, p)
        icon(c, "pin", r.right - 50 * U, r.top + 30 * U, 34 * U, RED)
        text(c, "%.1fkm".format(m.dayKmShown), r.right - 24 * U, r.bottom - 18 * U, 28 * U, FG, bold, Paint.Align.RIGHT)
    }

    // ======================================================================
    // 다음 이야기 카드 3장 · 아래 독(브리핑 · 할 일 · 내 차 · 테마 · 기록) · 캐릭터 존 배지
    // ======================================================================
    private fun nextUp(c: Canvas, r: RectF) {
        val g = 16 * U; val cw = (r.width() - g * 2) / 3.4f; fun wOf(i: Int) = if (i == 0) cw * 1.4f else cw
        val st = m.status
        val soc = st?.soc?.v
        val items = listOf(
            Triple("news", "관심 뉴스", m.newsList.firstOrNull() ?: "헤드라인을 불러오는 중") to "screen:brief",
            Triple("bag", "가는 김에", m.todos.firstOrNull() ?: "할 일이 없어요") to "screen:todo",
            Triple("star", "이번 주 안전운전", "${m.weekPoints} / ${m.weekGoal}점") to "screen:log",
        ).let { if (m.sit == Sit.PARK || m.sit == Sit.OFF) it.take(2) + (Triple("battery", "내 차", (soc?.let { "배터리 $it% · " } ?: "") + "주행 가능 ${st?.rangeKm?.v ?: "-"}km") to "screen:car") else it }
        items.forEachIndexed { i, (tr, action) ->
            val (ic, label, value) = tr
            val bx = r.left + (0 until i).sumOf { (wOf(it) + g).toDouble() }.toFloat()
            val b = RectF(bx, r.top, bx + wOf(i), r.bottom)
            spring(c, i + 1) {
                glassCard(c, b, 16 * U)
                icon(c, ic, b.left + 30 * U, b.top + 32 * U, 22 * U, MUTED)
                text(c, label, b.left + 52 * U, b.top + 40 * U, 20 * U, MUTED, medium, maxW = wOf(i) - 70 * U)
                if (ic == "star") {   // 진행 바
                    val pr = (m.weekPoints.toFloat() / m.weekGoal).coerceIn(0f, 1f)
                    text(c, value, b.left + 22 * U, b.top + 84 * U, 26 * U, FG, bold)
                    val bar = RectF(b.left + 22 * U, b.bottom - 34 * U, b.right - 22 * U, b.bottom - 24 * U)
                    p.color = SURF2; c.drawRoundRect(bar, 5 * U, 5 * U, p)
                    p.color = WARN; c.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * pr, bar.bottom), 5 * U, 5 * U, p)
                } else wrap(c, value, b.left + 22 * U, b.top + 80 * U, 22 * U, FG, wOf(i) - 44 * U, 2, medium, lh = 1.35f)
            }
            hits += b to action
        }
    }

    private val DOCK = listOf("brief" to "브리핑", "todo" to "할 일", "car" to "내 차", "deco" to "테마", "skin" to "스킨", "log" to "기록")
    private fun dock(c: Canvas, w: Float, h: Float) {
        val dh = 64 * U; val iw = 128 * U
        val r = RectF(zoneL, h - 48 * U - dh, zoneL + iw * DOCK.size, h - 48 * U)
        glassCard(c, r, 16 * U)
        DOCK.forEachIndexed { i, (key, lab) ->
            val b = RectF(r.left + i * iw, r.top, r.left + (i + 1) * iw, r.bottom)
            val on = m.overlay == key
            if (on) { p.color = SURF2; c.drawRoundRect(RectF(b.left + 6 * U, b.top + 6 * U, b.right - 6 * U, b.bottom - 6 * U), 12 * U, 12 * U, p) }
            val ic = when (key) { "brief" -> "news"; "todo" -> "bag"; "car" -> "battery"; "deco" -> "star"; "skin" -> "palette"; else -> "chart" }
            icon(c, ic, b.left + 34 * U, b.centerY(), 22 * U, if (on) FG else MUTED)
            text(c, lab, b.left + 54 * U, b.centerY() + 8 * U, 22 * U, if (on) FG else MUTED, medium, maxW = iw - 60 * U)
            hits += b to (when (key) { "deco" -> "deco"; "skin" -> "skintab"; else -> "screen:$key" })
        }
    }

    /** 캐릭터 존 배지 — 날씨 · 오늘 모은 별(작게) */
    private fun badges(c: Canvas) {
        if (m.weatherShort.isBlank()) return
        val col = if (sceneDark) 0xCCFFFFFF.toInt() else MUTED
        icon(c, "cloud", 64 * U + 12 * U, 52 * U, 22 * U, col)
        text(c, m.weatherShort, 64 * U + 34 * U, 60 * U, 22 * U, col, medium)
    }

    /** 다른 화면 — 오른쪽 큰 카드 한 장(목록). 브리핑 · 할 일 · 내 차 · 기록 */
    private fun sheet(c: Canvas, zone: RectF, which: String) {
        glassCard(c, zone, 24 * U)
        val pad = 40 * U; val x0 = zone.left + pad; val x1 = zone.right - pad
        val (title, sub) = when (which) {
            "brief" -> "오늘의 브리핑" to (m.weatherLine.ifBlank { "날씨를 불러오는 중" })
            "todo" -> "할 일 · 가는 김에" to "가는 길에 들를 수 있는 것부터 — 말로 \"가는 김에 들르자\""
            "car" -> "내 차" to "Pleos 차량 SDK → AAOS 차량 API → 앱 측정 순으로 읽어요"
            else -> "안전운전 기록" to "이번 주 ${m.weekPoints} / ${m.weekGoal}점 · 모은 별 ${m.points}"
        }
        // 닫기 — 상세 화면 왼쪽 위 끝
        button(c, "닫기", RectF(x0, zone.top + pad, x0 + 110 * U, zone.top + pad + 52 * U), false, "screen:$which")
        text(c, title, x0 + 130 * U, zone.top + pad + 36 * U, 40 * U, FG, bold, maxW = x1 - x0 - 130 * U)
        text(c, sub, x0, zone.top + pad + 76 * U, 22 * U, MUTED, medium, maxW = x1 - x0)
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
                m.newsList.forEachIndexed { i, n ->
                    val im = m.newsImgs[n]?.let { v.urlBmp(it) }
                    val yy = y
                    row("news", "${i + 1}. $n", if (im != null) " ·" else "", ACC, Color.TRANSPARENT)
                    if (im != null && yy + rowH <= zone.bottom - pad) { val ir = RectF(x1 - 96 * U, yy + 6 * U, x1, yy + rowH - 6 * U); c.save(); c.clipPath(Path().apply { addRoundRect(ir, 10 * U, 10 * U, Path.Direction.CW) })
                        val sc = max(ir.width() / im.width, ir.height() / im.height); c.drawBitmap(im, null, RectF(ir.centerX() - im.width * sc / 2, ir.centerY() - im.height * sc / 2, ir.centerX() + im.width * sc / 2, ir.centerY() + im.height * sc / 2), bp); c.restore() } }
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
        val mc = when (v.mode) { HyunView.Mode.LISTENING -> ACC; else -> if (darkUi) 0xFF262C36.toInt() else 0xFF1F2733.toInt() }
        if (v.mode == HyunView.Mode.LISTENING) { p.color = Glass.a(ACC, 50); c.drawCircle(mx, my, rr * (1.25f + 0.08f * sin(SystemClock.elapsedRealtime() / 160f)), p) }   // 라이브 상태만 움직인다
        p.color = mc; p.setShadowLayer(16 * U, 0f, 6 * U, 0x29111827); c.drawCircle(mx, my, rr, p); p.clearShadowLayer()
        micGlyph(c, mx, my, rr * 1.15f, Color.WHITE)
        hits += RectF(mx - rr, my - rr, mx + rr, my + rr) to "mic"
        val lab = when (v.mode) { HyunView.Mode.LISTENING -> "듣고 있어요"; HyunView.Mode.THINKING -> "생각 중…"; HyunView.Mode.SPEAKING -> ""; else -> if (v.listenAlways) "\"현비서야\"" else "" }
        if (lab.isNotBlank() && false) text(c, lab, mx, my, 20 * U, MUTED, medium)
    }

    /** 운전 중 — 버튼 대신 말로 하라는 옅은 글 한 줄(누를 수 없음) */
    private fun micHint(c: Canvas, w: Float, h: Float) {
        val col = if (sceneDark) 0x99FFFFFF.toInt() else FAINT
        micGlyph(c, w - 48 * U - 290 * U, h - 60 * U, 30 * U, col)
        text(c, "\"현비서야\" 말로 하세요", w - 48 * U, h - 52 * U, 24 * U, col, medium, Paint.Align.RIGHT)
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
        for ((key, lab) in listOf("skin" to "스킨", "concept" to "테마", "decor" to "꾸미기 소품", "face" to "표정")) {
            val on = m.decoTab == key
            p.textSize = 24 * U; p.typeface = semi
            val r = RectF(tx, ty - 26 * U, tx + p.measureText(lab) + 48 * U, ty + 26 * U)
            p.color = if (on) ACC else SURF2; c.drawRoundRect(r, 26 * U, 26 * U, p)
            text(c, lab, r.centerX(), ty + 9 * U, 24 * U, if (on) Color.WHITE else FG, semi, Paint.Align.CENTER)
            hits += r to "tab:$key"; tx = r.right + 12 * U
        }
        val gy = ty + 48 * U; val g = 16 * U
        if (m.decoTab == "skin") {   // 스킨 = 그림체 한 벌(assets/skins/*.json) — 무료, 누르면 바로 바뀜
            val all = io.github.somanghao.makecar.hyun.ui.skin.Skins.all(v.context)
            val cols = 2; val cw = (x1 - x0 - g) / cols; val ch = min(300 * U, panel.bottom - 40 * U - gy)
            all.forEachIndexed { i, sn ->
                val r = RectF(x0 + (i % cols) * (cw + g), gy + (i / cols) * (ch + g), 0f, 0f).also { it.right = it.left + cw; it.bottom = it.top + ch }
                val using = sn.id == sk.id
                val th = RectF(r.left, r.top, r.right, r.bottom - 56 * U)
                c.save(); c.clipPath(Path().apply { addRoundRect(th, 18 * U, 18 * U, Path.Direction.CW) })
                p.color = sn.colors.bg; c.drawRect(th, p)
                if (sn.style.pattern == "halftone") { p.color = sn.style.patternColor; var yy = th.top + 8 * U; var row2 = 0; while (yy < th.bottom) { var xx = th.left + (if (row2 % 2 == 0) 8 * U else 18 * U); while (xx < th.right) { c.drawCircle(xx, yy, 3 * U, p); xx += 20 * U }; yy += 18 * U; row2++ } }
                val cr = RectF(th.left + 28 * U, th.top + 28 * U, th.right - 28 * U, th.bottom - 28 * U)
                val bw = sn.style.border * S * 100
                if (sn.style.shadowX > 0f) { p.color = sn.style.shadowColor; c.drawRoundRect(RectF(cr).apply { offset(sn.style.shadowX * S * 60, sn.style.shadowY * S * 60) }, 16 * U, 16 * U, p) }
                p.color = sn.colors.card; c.drawRoundRect(cr, 16 * U, 16 * U, p)
                if (bw > 0f) { p.style = Paint.Style.STROKE; p.strokeWidth = bw; p.color = sn.style.borderColor; c.drawRoundRect(cr, 16 * U, 16 * U, p); p.style = Paint.Style.FILL }
                text(c, "안녕하세요", cr.left + 24 * U, cr.top + 60 * U, 40 * U, sn.colors.fg, sn.fonts.title)
                p.color = sn.colors.accent; c.drawRoundRect(RectF(cr.left + 24 * U, cr.bottom - 64 * U, cr.left + 184 * U, cr.bottom - 20 * U), 12 * U, 12 * U, p)
                text(c, "현비서", cr.left + 104 * U, cr.bottom - 34 * U, 22 * U, sn.colors.onAccent, sn.fonts.bold, Paint.Align.CENTER)
                c.restore()
                p.style = Paint.Style.STROKE; p.strokeWidth = if (using) 4 * U else max(1f, U); p.color = if (using) ACC else LINE; c.drawRoundRect(th, 18 * U, 18 * U, p); p.style = Paint.Style.FILL
                text(c, sn.name + (if (sn.author.isNotBlank()) " · ${sn.author}" else ""), r.left + 4 * U, r.bottom - 16 * U, 24 * U, FG, semi, maxW = cw * 0.7f)
                text(c, if (using) "사용 중" else "무료 · 누르면 바뀜", r.right - 4 * U, r.bottom - 16 * U, 20 * U, if (using) ACC else MUTED, semi, Paint.Align.RIGHT)
                hits += r to "skin:${sn.id}"
            }
        } else if (m.decoTab == "concept") {
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
            if (m.decoTab == "decor") {   // 꾸미기 소품 30종 — 칸마다 하나
                val cols = 6; val rows = 5; val g2 = 10 * U
                val cw = (x1 - x0 - g2 * (cols - 1)) / cols; val ch = (panel.bottom - 40 * U - gy - g2 * (rows - 1)) / rows
                StageModel.ACC_DEFS.forEachIndexed { i, d ->
                    val r = RectF(x0 + (i % cols) * (cw + g2), gy + (i / cols) * (ch + g2), 0f, 0f).also { it.right = it.left + cw; it.bottom = it.top + ch }
                    val own = "acc:${d.id}" in m.owned; val on = m.equipped[d.slot] == d.id
                    p.color = SURF2; c.drawRoundRect(r, 12 * U, 12 * U, p)
                    if (on) { p.style = Paint.Style.STROKE; p.strokeWidth = 3 * U; p.color = ACC; c.drawRoundRect(r, 12 * U, 12 * U, p); p.style = Paint.Style.FILL }
                    v.asset("acc_${d.id}.webp")?.let { bm -> val iw2 = min(r.width() * 0.7f, (r.height() - 34 * U) * 0.9f * bm.width / bm.height); val ih2 = iw2 * bm.height / bm.width
                        p.color = Color.WHITE; p.alpha = if (own || m.points >= d.price) 255 else 150
                        c.drawBitmap(bm, null, RectF(r.centerX() - iw2 / 2, r.top + (r.height() - 30 * U) / 2 - ih2 / 2, r.centerX() + iw2 / 2, r.top + (r.height() - 30 * U) / 2 + ih2 / 2), bp); p.alpha = 255 }
                    text(c, d.name, r.left + 10 * U, r.bottom - 10 * U, 17 * U, FG, medium, maxW = r.width() * 0.62f)
                    text(c, when { on -> "착용"; own -> "보유"; else -> "★${d.price}" }, r.right - 10 * U, r.bottom - 10 * U, 16 * U, when { on -> ACC; own -> MUTED; m.points >= d.price -> WARN; else -> FAINT }, semi, Paint.Align.RIGHT)
                    hits += r to "acc:${d.id}"
                }
            } else {
            val items = StageModel.FACES

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
        }
        if (m.unlockAt > 0 && SystemClock.elapsedRealtime() - m.unlockAt < 1200) doors(c, w, h, (SystemClock.elapsedRealtime() - m.unlockAt) / 1200f)
        toast(c, w, h)
    }

    private fun debug(c: Canvas, w: Float, h: Float) {
        val r = RectF(w * 0.04f, h - 36f * S, w * 0.96f, h - 2f * S)
        glassCard(c, r, 24 * U, WARN)
        text(c, "화면 미리보기 · 상황", r.left + 2.4f * S, r.top + 3.6f * S, 1.9f * S, WARN, bold)
        text(c, "adb: am broadcast -a io.github.somanghao.makecar.hyun.SIM --es screen webtoon --es nav on  (screen auto = 자동)", r.right - 2.4f * S, r.top + 3.6f * S, 1.5f * S, MUTED, body, Paint.Align.RIGHT)
        fun rowOf(list: List<Triple<String, String, Boolean>>, top: Float, bottom: Float) {
            val bw = (r.width() - 4.8f * S - 0.8f * S * (list.size - 1)) / list.size
            list.forEachIndexed { i, (lab, action, on) -> button(c, lab, RectF(r.left + 2.4f * S + i * (bw + 0.8f * S), top, r.left + 2.4f * S + i * (bw + 0.8f * S) + bw, bottom), on, action) }
        }
        rowOf(Sit.values().map { Triple(it.label, "force:${it.key}", it == m.sit && m.forced) } + listOf(Triple("자동", "force:auto", !m.forced && m.view == null), Triple("닫기", "debug", false)), r.top + 5f * S, r.top + 10.4f * S)
        text(c, "취미 레이아웃 · 내비", r.left + 2.4f * S, r.top + 13.4f * S, 1.9f * S, WARN, bold)
        rowOf(StageModel.CATS.map { Triple(it.second.substringBefore("·"), "force:${it.first}", m.view == it.first) } + listOf(Triple(if (m.simNav) "내비 끄기" else "내비 켜기", "nav:toggle", m.simNav)), r.top + 15f * S, r.top + 20.4f * S)
        text(c, "차 신호 시뮬레이터(Pleos SDK 대신)", r.left + 2.4f * S, r.top + 23.4f * S, 1.9f * S, WARN, bold)
        val sims = listOf("비(와이퍼)" to "wiper=on", "동승자" to "passenger=on", "뒷좌석" to "kid=on", "더워요 28°" to "cabin=28", "배터리 18%" to "soc=18", "크루즈 100" to "cruise=100",
            "왼쪽 깜빡이" to "turn=left", "문 열림" to "door=open", "벨트 풀림" to "belt=off", "에코" to "mode=ECO", "초기화" to "reset")
        rowOf(sims.map { Triple(it.first, "sim:${it.second}", false) }, r.top + 25f * S, r.bottom - 1.6f * S)
    }

    // ======================================================================
    // 상황 화면 6 · 취미 레이아웃 9 · 상시 내비 띠 · 내비 주화면 — docs/hyun-content-plan.md (댄 11-09)
    //  - 정보마다 모양이 다르다: 사진 / 큰 화살표 / 표지 / 정사각 / 전광판 / 큰 숫자+선 / 큰 기온 / 인용 / 시간 축
    //  - 경로 안내 중이면 모든 화면 위에 내비 띠(폰의 진행 중 알림처럼) · 누르면 내비 주화면
    //  - 내비 주화면: 캐릭터는 왼쪽 위 배지(높이 14%), 내비가 화면을 넓게
    // ======================================================================
    private val G get() = 16 * U
    private val UP = 0xFFE5484D.toInt()        // 오름(국내 관례 빨강)
    private val DOWN = 0xFF2F6BFF.toInt()      // 내림(파랑)
    private fun updown(r: Float) = if (r > 0f) UP else if (r < 0f) DOWN else MUTED
    private fun pct(r: Float) = (if (r > 0f) "+" else "") + "%.2f%%".format(r)
    private fun box(c: Canvas, r: RectF, action: String? = null) { glassCard(c, r, 24 * U); action?.let { hits += r to it } }
    private fun lab(c: Canvas, s: String, x: Float, y: Float, maxW: Float = Float.MAX_VALUE, col: Int = MUTED) = text(c, s, x, y, 20 * U, col, medium, maxW = maxW)
    private fun cols(r: RectF, vararg wts: Float): List<RectF> {
        val tot = wts.sum(); val avail = r.width() - G * (wts.size - 1); var x = r.left
        return wts.map { wt -> val ww = avail * wt / tot; RectF(x, r.top, x + ww, r.bottom).also { x += ww + G } }
    }
    /** 세로로 쌓기 — 들어가지 않는 줄은 버린다 */
    private fun stack(c: Canvas, zone: RectF, rows: List<Pair<Float, (RectF) -> Unit>>) {
        var y = zone.top; var i = 0
        for ((hh, d) in rows) {
            val r = if (hh <= 0f) RectF(zone.left, y, zone.right, zone.bottom) else RectF(zone.left, y, zone.right, y + hh)
            if (r.bottom > zone.bottom + 1 || r.height() < 60 * U) break
            spring(c, i) { d(r) }; y = r.bottom + G; i++
        }
    }
    private fun row(hh: Float, d: (RectF) -> Unit): Pair<Float, (RectF) -> Unit> = hh to d
    private fun etaIn(min: Int): String { val k = java.util.Calendar.getInstance().apply { add(java.util.Calendar.MINUTE, min) }; return "%02d:%02d".format(k.get(java.util.Calendar.HOUR_OF_DAY), k.get(java.util.Calendar.MINUTE)) }
    private fun sgn(n: Int) = if (n >= 0) "+$n" else "−${-n}"

    /** 인터넷 그림을 칸에 꽉 채워(가운데 자르기) — 아직 없으면 옅은 면 */
    private fun cover(c: Canvas, url: String?, r: RectF, rad: Float, clip: RectF? = null) {
        val bm = url?.let { v.urlBmp(it) }
        c.save(); c.clipPath(Path().apply { addRoundRect(clip ?: r, rad, rad, Path.Direction.CW) }); c.clipRect(r)
        if (bm != null) { val sc = max(r.width() / bm.width, r.height() / bm.height); val bw = bm.width * sc; val bh = bm.height * sc
            c.drawBitmap(bm, null, RectF(r.centerX() - bw / 2, r.centerY() - bh / 2, r.centerX() + bw / 2, r.centerY() + bh / 2), bp) }
        else { p.shader = null; p.color = SURF2; c.drawRect(r, p) }
        c.restore()
    }

    /** 사진을 자르지 않고 통째로(contain) — 남는 칸은 같은 사진을 흐리게 깔아 채운다 */
    private val blurCache = HashMap<String, Bitmap>()
    private fun photoFit(c: Canvas, url: String?, r: RectF, rad: Float, clip: RectF? = null) {
        val bm = url?.let { v.urlBmp(it) }
        if (bm == null) { cover(c, null, r, rad, clip); return }
        c.save(); c.clipPath(Path().apply { addRoundRect(clip ?: r, rad, rad, Path.Direction.CW) }); c.clipRect(r)
        val ar = bm.width.toFloat() / bm.height; val rr = r.width() / r.height()
        if (kotlin.math.abs(ar - rr) / rr > 0.04f) {   // 비율이 다르면 흐린 바탕
            val small = blurCache.getOrPut(url!!) { Bitmap.createScaledBitmap(bm, max(4, bm.width / 24), max(4, bm.height / 24), true) }
            val sc = max(r.width() / bm.width, r.height() / bm.height); val bw = bm.width * sc; val bh = bm.height * sc
            c.drawBitmap(small, null, RectF(r.centerX() - bw / 2, r.centerY() - bh / 2, r.centerX() + bw / 2, r.centerY() + bh / 2), bp)
            p.shader = null; p.color = 0x33000000; c.drawRect(r, p)
        }
        val sc = min(r.width() / bm.width, r.height() / bm.height); val bw = bm.width * sc; val bh = bm.height * sc
        c.drawBitmap(bm, null, RectF(r.centerX() - bw / 2, r.centerY() - bh / 2, r.centerX() + bw / 2, r.centerY() + bh / 2), bp)
        c.restore()
    }

    private fun stat(c: Canvas, x: Float, y: Float, big: String, sub: String, col: Int, maxW: Float, size: Float = 56 * U) {
        text(c, big, x, y, fit(big, size, maxW, bold), col, bold, maxW = maxW)
        lab(c, sub, x, y + 36 * U, maxW)
    }
    /** 작은 카드 — 이름 · 값 · 설명 */
    private fun mini(c: Canvas, r: RectF, label: String, value: String, sub: String, action: String?, vcol: Int = FG) {
        box(c, r, action)
        val x0 = r.left + 28 * U; val iw = r.width() - 56 * U
        lab(c, label, x0, r.top + 38 * U, iw)
        text(c, value, x0, r.top + 84 * U, fit(value, 34 * U, iw, bold), vcol, bold, maxW = iw)
        if (sub.isNotBlank() && r.height() > 110 * U) lab(c, sub, x0, r.top + 118 * U, iw)
    }
    private fun chip(c: Canvas, s: String, x: Float, cy: Float, action: String, warn: Boolean): Float {
        p.textSize = 20 * U; p.typeface = medium
        val r = RectF(x, cy - 20 * U, x + p.measureText(s) + 32 * U, cy + 20 * U)
        p.shader = null; p.color = if (warn) Glass.a(WARN, if (darkUi) 70 else 52) else if (darkUi) SURF2 else Glass.a(Color.WHITE, 220); c.drawRoundRect(r, 20 * U, 20 * U, p)
        text(c, s, x + 16 * U, cy + 7 * U, 20 * U, if (warn) (if (darkUi) 0xFFFFD27A.toInt() else 0xFF8A5300.toInt()) else FG, medium)
        hits += r to action
        return r.right
    }
    /** 왼쪽 위 — 상황 · 시각 · 날씨(세 번 누르면 디버그 판) + 레이아웃 칩(상황 화면으로 · 미리보기) */
    private fun header(c: Canvas) {
        val col = if (sceneDark) 0xCCFFFFFF.toInt() else MUTED
        val x = if (mirror) zoneR + 64 * U else 64 * U
        val line = "${m.sit.label} · ${m.kicker.substringAfter(" · ")}" + (if (m.weatherShort.isNotBlank()) "   ${m.weatherShort}" else "")
        text(c, line, x, 60 * U, 22 * U, col, medium)
        p.textSize = 22 * U; p.typeface = medium; hits += RectF(x - 8 * U, 28 * U, x + p.measureText(line) + 8 * U, 76 * U) to "sitpill"
        viewChips(c, x, 112 * U)
    }
    private fun viewChips(c: Canvas, x: Float, cy: Float) {
        var xx = x
        if (m.view != null) xx = chip(c, "‹ 상황 화면", xx, cy, "view:close", false) + 12 * U
        if (m.viewForced && (m.view != null || m.forced)) chip(c, "미리보기 · 자동으로", xx, cy, "view:auto", true)
    }

    /** 리드 기사 — 사진이 있는 관심 뉴스 먼저(연합뉴스 RSS 사진) */
    private fun leadNews(): String? {
        fun ok(t: String) = m.newsImgs[t]?.let { v.urlBmp(it) } != null   // 사진을 실제로 받은 기사(깨진 주소는 건너뜀)
        return m.newsList.firstOrNull { ok(it) } ?: m.newsImgs.keys.firstOrNull { ok(it) } ?: m.newsList.firstOrNull { m.newsImgs[it] != null } ?: m.newsList.firstOrNull()
    }
    private fun navLine(): String = "${v.dist(m.navTurnM)} 앞에서 ${shortTurn(m.navTurnType, m.navTurn)}이에요"
    private fun outfitLine(): String {
        val t = m.tempNow ?: return "날씨를 살펴보는 중이에요"
        val base = when { t <= 5 -> "두꺼운 외투에 목도리까지"; t <= 12 -> "얇은 니트에 가벼운 겉옷이면 돼요"; t <= 19 -> "긴소매 한 장이면 충분해요"; t <= 25 -> "반소매도 괜찮아요"; else -> "가볍게 입고 선글라스 챙겨요" }
        return base + if (m.rainMax >= 50 || m.raining) " · 우산 챙겨요" else ""
    }
    private fun viewLine(k: String): String = when (k) {
        "news" -> "오늘 소식 정리했어요"
        "nav" -> navLine()
        "webtoon" -> "쉬는 동안 한 화 어때요"
        "music" -> "도착까지 잔잔하게 틀게요"
        "sports" -> "응원하는 팀이 이기고 있어요"
        "stock" -> m.indices.firstOrNull()?.let { if (it.ratio < 0f) "오늘 장은 조금 내렸어요" else "오늘 장은 올랐어요" } ?: "장 소식을 불러오는 중이에요"
        "weather" -> m.todayMax?.let { "오늘 최고 ${it}°, ${outfitLine().substringBefore(" ·")}" } ?: "오늘 날씨를 정리했어요"
        "local" -> "신호 기다리는 동안 하나"
        else -> "첫 일정까지 여유 있어요"
    }

    // ---------- 상시 내비 띠 — 다음 회전 · 거리 · 길 이름 · 도착/남은 시간. 누르면 내비 주화면 ----------
    private fun navStrip(c: Canvas, r: RectF) {
        glassCard(c, r, 20 * U); hits += r to "view:nav"
        val tile = RectF(r.left + 12 * U, r.top + 12 * U, r.left + 12 * U + r.height() - 24 * U, r.bottom - 12 * U)
        p.shader = null; p.color = ACC; c.drawRoundRect(tile, 14 * U, 14 * U, p)
        v.turnIcon(c, m.navTurnType.ifBlank { "TURN_STRAIGHT" }, tile.centerX(), tile.centerY() - tile.height() * 0.04f, tile.height() * 0.86f, Color.WHITE)
        p.style = Paint.Style.FILL; p.strokeWidth = 0f
        val d = v.dist(m.navTurnM)
        text(c, d, tile.right + 20 * U, r.centerY() + 14 * U, 40 * U, FG, bold)
        p.textSize = 40 * U; p.typeface = bold; val sx = tile.right + 20 * U + p.measureText(d) + 20 * U
        // 한눈에 — 거리 · 길 이름 · 도착 시각 셋만(남은 거리·모의 경로 같은 작은 글은 뺌)
        val right = m.navEta
        p.textSize = 30 * U; p.typeface = medium; val rw = p.measureText(right)
        text(c, m.navTurn, sx, r.centerY() + 11 * U, 30 * U, FG, medium, maxW = r.right - 28 * U - rw - 32 * U - sx)
        text(c, right, r.right - 28 * U, r.centerY() + 11 * U, 30 * U, FG, medium, Paint.Align.RIGHT)
    }

    // ---------- 내비 주화면 — 왼쪽: 다음 회전 · 차로 · 도착 / 오른쪽: 경로 지도 ----------
    /** 운전 중(출발 준비 D · 주행 · 신호대기) — 한눈 규칙: 초점 하나 + 보조 1~2, 큰 글자, 작은 라벨·목록·그림·차트 없음 */
    private fun glance() = m.sit == Sit.DRIVE || m.sit == Sit.STOP || m.sit == Sit.READY

    /** 조사 로/으로 — 받침(ㄹ 제외) 있으면 으로 */
    private fun ro(w: String): String { val ch = w.lastOrNull() ?: return "로"; if (ch !in '가'..'힣') return "로"; val jong = (ch - '가') % 28; return if (jong == 0 || jong == 8) "로" else "으로" }
    private fun turnWord() = shortTurn(m.navTurnType, m.navTurn)
    private fun lanes(c: Canvas, r: RectF, size: Float) {
        val ty = m.navTurnType
        val rt = ty.contains("RIGHT") || ty == "TURN_KEEP_R" || ty == "TURN_EXIT_IC"; val lt = ty.contains("LEFT") || ty == "TURN_KEEP_L" || ty == "TURN_UTURN"
        val lw = r.width() / 4
        for (i in 0 until 4) {
            val on = (rt && i >= 2) || (lt && i < 2) || (!rt && !lt)
            val lty = if (rt && i >= 2) "TURN_RIGHT" else if (lt && i < 2) "TURN_LEFT" else "TURN_STRAIGHT"
            v.turnIcon(c, lty, r.left + lw * (i + 0.5f), r.centerY(), size, if (on) ACC else Glass.a(MUTED, 90)); p.style = Paint.Style.FILL; p.strokeWidth = 0f
        }
    }

    /** 운전 중 내비 — 지도 없이: 왼쪽 큰 회전(화살표 · 거리 · 길 이름) / 오른쪽 차로 한 줄 · 도착 시각 */
    private fun navGlance(c: Canvas, r: RectF) {
        val cs = cols(r, 1.35f, 1f)
        val L = cs[0]; box(c, L)
        val ts = min(300 * U, L.height() * 0.46f)
        val blockTop = L.centerY() - (ts + 130 * U) / 2   // 화살표 + 길 이름 묶음을 카드 가운데로
        val tile = RectF(L.left + 48 * U, blockTop, L.left + 48 * U + ts, blockTop + ts)
        p.shader = null; p.color = ACC; c.drawRoundRect(tile, 44 * U, 44 * U, p)
        v.turnIcon(c, m.navTurnType.ifBlank { "TURN_STRAIGHT" }, tile.centerX(), tile.centerY() - ts * 0.03f, ts * 0.72f, Color.WHITE); p.style = Paint.Style.FILL; p.strokeWidth = 0f
        val dist = v.dist(m.navTurnM); val num = dist.takeWhile { it.isDigit() || it == '.' }; val unit = dist.drop(num.length)
        val nx = tile.right + 48 * U; val ns = fit(num, 190 * U, L.right - nx - 110 * U, bold)
        text(c, num, nx, tile.top + ts * 0.62f, ns, FG, bold)
        p.textSize = ns; p.typeface = bold; text(c, unit, nx + p.measureText(num) + 10 * U, tile.top + ts * 0.62f, 64 * U, FG, bold)
        text(c, turnWord(), nx, tile.top + ts * 0.62f + 72 * U, 48 * U, MUTED, medium, maxW = L.right - nx - 40 * U)
        text(c, m.navTurn, L.left + 48 * U, tile.bottom + 110 * U, fit(m.navTurn, 60 * U, L.width() - 96 * U, medium), FG, medium, maxW = L.width() - 96 * U)
        // 바로 이어지는 회전(200m 안)만 한 줄
        if (m.navNext.isNotBlank() && m.navNextM in 1..200 && m.navTurnM <= 300)
            text(c, "이어서 ${v.dist(m.navNextM)} 뒤 ${shortTurn(m.navNextType, m.navNext)}", L.left + 48 * U, L.bottom - 48 * U, 36 * U, ACC, medium, maxW = L.width() - 96 * U)
        val R = cs[1]
        val top = RectF(R.left, R.top, R.right, R.top + R.height() * 0.46f)
        val ev = m.carEvent
        if (m.sit == Sit.DRIVE && ev != null && SystemClock.elapsedRealtime() - m.carEventAt < 6000) {   // 차 상태가 바뀌면 6초 동안 그 한 줄
            val col = when (ev.first) { "warn" -> RED; "signal" -> WARN; "cruise", "road", "flag" -> GOOD; else -> ACC }
            glassCard(c, top, 24 * U, col)
            if (ev.first != "signal" || (SystemClock.elapsedRealtime() / 500) % 2 == 0L) icon(c, ev.first, top.left + 80 * U, top.centerY(), 64 * U, col)
            val n2 = min(2, lineCount(ev.second, top.width() - 190 * U, 44 * U, bold))
            wrap(c, ev.second, top.left + 150 * U, top.centerY() + 16 * U - (n2 - 1) * 26 * U, 44 * U, FG, top.width() - 190 * U, 2, bold, lh = 1.2f)
        } else { box(c, top); lanes(c, RectF(top.left + 32 * U, top.top, top.right - 32 * U, top.bottom), min(130 * U, top.height() * 0.6f)) }
        val bot = RectF(R.left, top.bottom + G, R.right, R.bottom)
        box(c, bot)
        val eta = m.navEta.removePrefix("도착 ")
        text(c, eta, bot.left + 48 * U, bot.centerY() + 20 * U, fit(eta, 110 * U, bot.width() * 0.6f, bold), FG, bold)
        p.textSize = fit(eta, 110 * U, bot.width() * 0.6f, bold); p.typeface = bold; val ex = bot.left + 48 * U + p.measureText(eta) + 24 * U
        text(c, "도착", ex, bot.centerY() + 18 * U, 40 * U, MUTED, medium)
        if (m.navMin > 0) text(c, "${m.navMin}분", bot.left + 48 * U, bot.centerY() + 84 * U, 40 * U, MUTED, medium)
    }

    private fun navMainScreen(c: Canvas, r: RectF, w: Float, h: Float) {
        if (glance()) { navGlance(c, r); return }
        val leftW = min(600 * U, r.width() * 0.36f)
        val L = RectF(r.left, r.top, r.left + leftW, r.bottom)
        val M = RectF(L.right + G, r.top, r.right, r.bottom)
        val t1 = RectF(L.left, L.top, L.right, L.top + 380 * U)
        box(c, t1)
        val tile = RectF(t1.left + 32 * U, t1.top + 32 * U, t1.left + 212 * U, t1.top + 212 * U)
        p.shader = null; p.color = ACC; c.drawRoundRect(tile, 32 * U, 32 * U, p)
        v.turnIcon(c, m.navTurnType.ifBlank { "TURN_STRAIGHT" }, tile.centerX(), tile.centerY(), 124 * U, Color.WHITE)
        p.style = Paint.Style.FILL; p.strokeWidth = 0f
        val dist = v.dist(m.navTurnM); val num = dist.takeWhile { it.isDigit() || it == '.' }; val unit = dist.drop(num.length)
        val nx = tile.right + 28 * U; val ns = fit(num, 112 * U, t1.right - nx - 90 * U, bold)
        text(c, num, nx, tile.top + 116 * U, ns, FG, bold)
        p.textSize = ns; p.typeface = bold; text(c, unit, nx + p.measureText(num) + 6 * U, tile.top + 116 * U, 44 * U, FG, bold)
        text(c, shortTurn(m.navTurnType, m.navTurn), nx, tile.top + 168 * U, 30 * U, MUTED, medium, maxW = t1.right - nx - 24 * U)
        wrap(c, m.navTurn, t1.left + 32 * U, tile.bottom + 56 * U, 34 * U, FG, t1.width() - 64 * U, 2, medium, lh = 1.3f)
        if (m.navNext.isNotBlank()) {
            p.color = LINE; c.drawRect(t1.left + 32 * U, t1.bottom - 70 * U, t1.right - 32 * U, t1.bottom - 70 * U + max(1f, U), p)
            lab(c, "그다음", t1.left + 32 * U, t1.bottom - 28 * U)
            v.turnIcon(c, m.navNextType.ifBlank { "TURN_STRAIGHT" }, t1.left + 120 * U, t1.bottom - 35 * U, 34 * U, MUTED); p.style = Paint.Style.FILL; p.strokeWidth = 0f
            text(c, "${v.dist(m.navNextM)}  ${m.navNext}", t1.left + 148 * U, t1.bottom - 26 * U, 24 * U, FG, medium, maxW = t1.width() - 180 * U)
        }
        // 차로(주행 중 차 신호가 바뀌면 6초 동안 그 알림)
        val t2 = RectF(L.left, t1.bottom + G, L.right, t1.bottom + G + 150 * U)
        val ev = m.carEvent
        if (m.sit == Sit.DRIVE && ev != null && SystemClock.elapsedRealtime() - m.carEventAt < 6000) {
            val col = when (ev.first) { "warn" -> RED; "signal" -> WARN; "cruise", "road", "flag" -> GOOD; else -> ACC }
            glassCard(c, t2, 24 * U, col)
            p.color = Glass.a(col, if (darkUi) 60 else 36); c.drawCircle(t2.left + 76 * U, t2.centerY(), 44 * U, p)
            if (ev.first != "signal" || (SystemClock.elapsedRealtime() / 500) % 2 == 0L) icon(c, ev.first, t2.left + 76 * U, t2.centerY(), 44 * U, col)
            text(c, ev.second, t2.left + 140 * U, t2.centerY() + 12 * U, fit(ev.second, 34 * U, t2.width() - 170 * U, bold), FG, bold, maxW = t2.width() - 170 * U)
        } else {
            box(c, t2)
            lab(c, "차로", t2.left + 32 * U, t2.top + 38 * U)
            val ty = m.navTurnType
            val rt = ty.contains("RIGHT") || ty == "TURN_KEEP_R" || ty == "TURN_EXIT_IC"; val lt = ty.contains("LEFT") || ty == "TURN_KEEP_L" || ty == "TURN_UTURN"
            val lw = (t2.width() - 64 * U) / 4
            for (i in 0 until 4) {
                val on = (rt && i >= 2) || (lt && i < 2) || (!rt && !lt)
                val lty = if (rt && i >= 2) "TURN_RIGHT" else if (lt && i < 2) "TURN_LEFT" else "TURN_STRAIGHT"
                val lx = t2.left + 32 * U + lw * (i + 0.5f)
                v.turnIcon(c, lty, lx, t2.centerY() + 18 * U, 64 * U, if (on) ACC else Glass.a(MUTED, 110)); p.style = Paint.Style.FILL; p.strokeWidth = 0f
                if (i > 0) { p.color = LINE; var yy = t2.top + 56 * U; while (yy < t2.bottom - 20 * U) { c.drawRect(t2.left + 32 * U + lw * i - U, yy, t2.left + 32 * U + lw * i + U, yy + 10 * U, p); yy += 18 * U } }
            }
        }
        // 도착
        val t3 = RectF(L.left, t2.bottom + G, L.right, L.bottom)
        if (t3.height() > 100 * U) {
            box(c, t3)
            lab(c, "도착" + if (m.mockRoute) " · 모의 경로" else "", t3.left + 32 * U, t3.top + 40 * U)
            text(c, m.navEta.removePrefix("도착 "), t3.left + 32 * U, t3.top + 108 * U, 60 * U, FG, bold)
            text(c, "${m.navRemain}" + (if (m.navMin > 0) " · ${m.navMin}분" else "") + " · ${m.navDest}", t3.left + 32 * U, t3.top + 150 * U, 26 * U, MUTED, medium, maxW = t3.width() - 64 * U)
        }
        mapView(c, M)
    }

    /** 경로 지도(코드로 그림) — 차는 아래 가운데, 다음 회전 지점이 거리만큼 다가온다 */
    private fun mapView(c: Canvas, r: RectF) {
        val rad = 24 * U
        val clip = Path().apply { addRoundRect(r, rad, rad, Path.Direction.CW) }
        c.save(); c.clipPath(clip)
        val bg = if (darkUi) 0xFF141A22.toInt() else 0xFFE6EBF0.toInt(); val blk = if (darkUi) 0xFF1B222C.toInt() else 0xFFF4F6F9.toInt(); val road = if (darkUi) 0xFF2A3442.toInt() else Color.WHITE
        p.shader = null; p.color = bg; c.drawRect(r, p)
        val bw = 220 * U; val bh = 150 * U; val gap = 22 * U
        val off = ((600 - m.navTurnM.coerceIn(0, 600)) * 0.5f * U) % bh
        p.color = blk
        var by = r.top - bh + off
        while (by < r.bottom) { var bx = r.left - 40 * U; var j = 0; while (bx < r.right) { c.drawRoundRect(RectF(bx + gap / 2, by + gap / 2, bx + bw * (if (j % 3 == 1) 1.4f else 1f) - gap / 2, by + bh - gap / 2), 10 * U, 10 * U, p); bx += bw * (if (j % 3 == 1) 1.4f else 1f); j++ }; by += bh }
        val ty = m.navTurnType
        val rt = ty.contains("RIGHT") || ty == "TURN_KEEP_R" || ty == "TURN_EXIT_IC"; val lt = ty.contains("LEFT") || ty == "TURN_KEEP_L" || ty == "TURN_UTURN"
        val vx = r.left + r.width() * (if (lt) 0.66f else 0.34f)
        val carY = r.bottom - 120 * U
        val turnY = carY - (m.navTurnM.coerceIn(0, 600) / 600f) * (carY - r.top - 170 * U) - 30 * U
        val endX = if (rt) r.left + r.width() * 0.8f else if (lt) r.left + r.width() * 0.2f else vx
        // 큰 길
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.BUTT; p.strokeWidth = 40 * U; p.color = road
        c.drawLine(vx, r.top, vx, r.bottom, p); c.drawLine(r.left, turnY, r.right, turnY, p)
        if (rt || lt) c.drawLine(endX, r.top, endX, turnY, p)
        // 경로
        path.reset(); path.moveTo(vx, r.bottom + 20 * U); path.lineTo(vx, turnY)
        if (rt || lt) { path.lineTo(endX, turnY); path.lineTo(endX, r.top + 90 * U) } else path.lineTo(vx, r.top + 90 * U)
        p.strokeJoin = Paint.Join.ROUND; p.strokeCap = Paint.Cap.ROUND
        p.color = Glass.a(ACC, 70); p.strokeWidth = 30 * U; c.drawPath(path, p)
        p.color = ACC; p.strokeWidth = 16 * U; c.drawPath(path, p)
        p.style = Paint.Style.FILL; p.strokeJoin = Paint.Join.MITER; p.strokeCap = Paint.Cap.BUTT
        // 길 이름
        val street = m.navTurn.substringBefore(" 방면").substringBefore(" ").ifBlank { "" }
        if (street.isNotBlank()) text(c, street, (if (rt) vx + 40 * U else if (lt) r.left + 40 * U else vx + 40 * U), turnY - 30 * U, 22 * U, MUTED, medium)
        // 회전 지점 · 도착 핀 · 내 차
        p.color = Color.WHITE; c.drawCircle(vx, turnY, 18 * U, p); p.color = ACC; c.drawCircle(vx, turnY, 11 * U, p)
        icon(c, "pin", endX, r.top + 60 * U, 52 * U, RED)
        p.color = Color.WHITE; p.setShadowLayer(12 * U, 0f, 4 * U, 0x33000000); c.drawCircle(vx, carY, 34 * U, p); p.clearShadowLayer()
        path.reset(); path.moveTo(vx, carY - 20 * U); path.lineTo(vx + 15 * U, carY + 16 * U); path.lineTo(vx, carY + 8 * U); path.lineTo(vx - 15 * U, carY + 16 * U); path.close()
        p.color = ACC; c.drawPath(path, p)
        // 위 왼쪽 — 목적지까지
        val tag = "${m.navDest}까지 ${m.navRemain}" + if (m.mockRoute) " · 모의 경로" else ""
        p.textSize = 22 * U; p.typeface = medium
        val tr = RectF(r.left + 24 * U, r.top + 24 * U, r.left + 24 * U + p.measureText(tag) + 36 * U, r.top + 72 * U)
        p.color = if (darkUi) Glass.a(SURF, 230) else Glass.a(Color.WHITE, 235); c.drawRoundRect(tr, 24 * U, 24 * U, p)
        text(c, tag, tr.left + 18 * U, tr.centerY() + 8 * U, 22 * U, FG, medium)
        c.restore()
        p.style = Paint.Style.STROKE; p.strokeWidth = max(1f, U); p.color = LINE; c.drawPath(clip, p); p.style = Paint.Style.FILL
    }

    // ---------- 상황 화면 ----------
    private fun storyRow(c: Canvas, r: RectF, tl: Tile) {
        box(c, r, tl.action)
        val col = tone(tl.tone)
        p.shader = null; p.color = col; c.drawRoundRect(RectF(r.left + 20 * U, r.top + 22 * U, r.left + 25 * U, r.bottom - 22 * U), 3 * U, 3 * U, p)
        icon(c, tl.icon, r.left + 62 * U, r.centerY(), 28 * U, col)
        lab(c, tl.label + (if (tl.tag.isNotBlank()) " · ${tl.tag}" else ""), r.left + 96 * U, r.top + 38 * U, r.width() - 130 * U)
        text(c, adult(firstSentence(tl.value)), r.left + 96 * U, r.top + 76 * U, 28 * U, FG, medium, maxW = r.width() - 130 * U)
    }
    private fun tipRow(c: Canvas, r: RectF, s: String) {
        box(c, r)
        text(c, "초보 팁", r.left + 28 * U, r.centerY() + 8 * U, 22 * U, if (darkUi) WARN else 0xFFB26A00.toInt(), semi)
        text(c, s, r.left + 130 * U, r.centerY() + 9 * U, 25 * U, FG, medium, maxW = r.width() - 160 * U)
    }
    private fun newsRow(c: Canvas, r: RectF) {
        box(c, r, "view:news")
        val n = leadNews()
        val th = RectF(r.left + 16 * U, r.top + 16 * U, r.left + 16 * U + (r.height() - 32 * U) * 1.5f, r.bottom - 16 * U)
        photoFit(c, n?.let { m.newsImgs[it] }, th, 14 * U)
        val x0 = th.right + 24 * U
        lab(c, "관심 뉴스 · 연합뉴스" + (n?.let { m.newsTimes[it] }?.let { " $it" } ?: ""), x0, r.top + 40 * U)
        text(c, n ?: "헤드라인을 불러오는 중이에요", x0, r.top + 80 * U, 28 * U, FG, medium, maxW = r.right - x0 - 120 * U)
        text(c, "듣기", r.right - 32 * U, r.centerY() + 9 * U, 24 * U, ACC, semi, Paint.Align.RIGHT)
    }
    private fun questRow(c: Canvas, r: RectF) {
        box(c, r, "screen:log")
        val x0 = r.left + 28 * U
        lab(c, "오늘의 온기 퀘스트", x0, r.top + 38 * U)
        text(c, "급정거 없이 10km", x0, r.top + 78 * U, 28 * U, FG, medium)
        val pr = (m.dayKmShown / 10.0).toFloat().coerceIn(0f, 1f)
        val bar = RectF(x0 + 300 * U, r.top + 66 * U, r.right - 280 * U, r.top + 76 * U)
        p.shader = null; p.color = SURF2; c.drawRoundRect(bar, 5 * U, 5 * U, p)
        p.color = WARN; c.drawRoundRect(RectF(bar.left, bar.top, bar.left + max(10 * U, bar.width() * pr), bar.bottom), 5 * U, 5 * U, p)
        text(c, "%.1f / 10km · +20".format(m.dayKmShown), r.right - 28 * U, r.top + 78 * U, 24 * U, MUTED, medium, Paint.Align.RIGHT)
    }

    private fun stateScreen(c: Canvas, z: RectF) {
        val rows = mutableListOf<Pair<Float, (RectF) -> Unit>>()
        if (!glance()) m.story?.let { st -> rows += row(108 * U) { r -> storyRow(c, r, st) } }
        val st = m.status
        val soc = st?.soc?.v; val rng = st?.rangeKm?.v
        when (m.sit) {
            Sit.IGNITION -> {
                rows += row(170 * U) { r ->
                    box(c, r, "screen:car"); val x0 = r.left + 32 * U
                    lab(c, "차 컨디션 · 시동 점검", x0, r.top + 40 * U)
                    val cs = cols(RectF(x0, r.top, r.right - 32 * U, r.bottom), 1f, 1f, 1f)
                    val ok = st?.belt?.v != false && st?.doorOpen?.v != true
                    stat(c, cs[0].left, r.top + 108 * U, soc?.let { "$it%" } ?: "—", "${st?.energyLabel?.v ?: "배터리"} · 주행 가능 ${rng ?: "-"}km", if (soc != null && soc < 30) WARN else FG, cs[0].width())
                    stat(c, cs[1].left, r.top + 108 * U, if (ok) "OK" else "확인", "벨트 · 문 · 전조등", if (ok) FG else WARN, cs[1].width())
                    stat(c, cs[2].left, r.top + 108 * U, "—", "타이어는 계기판에서", FAINT, cs[2].width())
                }
                rows += row(140 * U) { r ->
                    val cs = cols(r, 1f, 1f, 1f)
                    mini(c, cs[0], "날씨", m.tempNow?.let { "$it° · 최고 ${m.todayMax ?: it}°" } ?: "불러오는 중", outfitLine(), "view:weather")
                    mini(c, cs[1], "첫 일정 · 예시", "09:30 주간 회의", "지금 출발하면 ${etaIn(23)} 도착", "view:schedule")
                    val ks = m.indices.firstOrNull()
                    mini(c, cs[2], "장 · ${ks?.name ?: "코스피"}", ks?.let { "${it.price}  ${pct(it.ratio)}" } ?: "불러오는 중", m.stockAsof.substringAfter(" ").ifBlank { "네이버 증권" }, "view:stock", ks?.let { updown(it.ratio) } ?: FG)
                }
                rows += row(112 * U) { r -> newsRow(c, r) }
                rows += row(100 * U) { r -> questRow(c, r) }
                if (m.novice) rows += row(76 * U) { r -> tipRow(c, r, "출발 전에 시트 → 미러 → 벨트 순서로. 빨간 경고등이 없는지도 한 번.") }
            }
            Sit.READY -> {   // 기어 D(경로 없을 때) — 한눈: 목적지 · 도착 시각 · 말로 하라는 글 (+ 보조 한 줄). 버튼 없음
                rows += row(320 * U) { r ->
                    box(c, r); val x0 = r.left + 48 * U
                    val hr = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                    val (name, sub) = when {
                        m.navActive -> m.navDest to "${m.navEta} · ${m.navMin}분"
                        hr in 6..10 -> "회사" to "도착 ${etaIn(23)} · 23분"
                        hr in 17..21 -> "집" to "도착 ${etaIn(30)} · 30분"
                        else -> "자주 가는 카페" to "도착 ${etaIn(9)} · 9분"
                    }
                    text(c, name, x0, r.top + 120 * U, fit(name, 72 * U, r.width() - 96 * U, bold), FG, bold, maxW = r.width() - 96 * U)
                    text(c, sub, x0, r.top + 180 * U, 36 * U, MUTED, medium, maxW = r.width() - 96 * U)
                    // 버튼 없음 — 경로가 생기면 저절로 내비 화면, 안내는 말로
                    micGlyph(c, x0 + 18 * U, r.bottom - 74 * U, 34 * U, MUTED)
                    text(c, "\"${name}${ro(name)} 안내해 줘\" 하고 말하세요", x0 + 48 * U, r.bottom - 62 * U, 32 * U, MUTED, medium, maxW = r.width() - 140 * U)
                }
                val low = soc != null && soc < 30
                if (low) rows += row(100 * U) { r -> box(c, r, "screen:car"); text(c, "배터리 $soc% — 가는 길에 충전해요", r.left + 48 * U, r.centerY() + 12 * U, 34 * U, WARN, medium, maxW = r.width() - 96 * U) }
                else if (m.novice) rows += row(100 * U) { r -> box(c, r); text(c, "브레이크 꾹 → 주변 확인 → 깜빡이", r.left + 48 * U, r.centerY() + 12 * U, 34 * U, FG, medium, maxW = r.width() - 96 * U) }
            }
            Sit.STOP -> {   // 신호대기 — 짧은 이야기 한 장(목록 · 점수 · 라벨 없음)
                val g = m.tiles.firstOrNull { it.icon == "chat" }?.value ?: LOCAL[0].first
                val gl = min(4, lineCount(g, z.width() - 96 * U, 46 * U))
                rows += row(min(z.height(), 96 * U + gl * 61 * U + 40 * U)) { r ->
                    box(c, r, "view:local"); val x0 = r.left + 48 * U
                    wrap(c, g, x0, r.top + 100 * U, 46 * U, FG, r.width() - 96 * U, 4, medium, lh = 1.32f)
                }
                if (m.novice) rows += row(100 * U) { r -> box(c, r); text(c, "브레이크를 계속 꾹", r.left + 48 * U, r.centerY() + 12 * U, 34 * U, FG, medium) }
            }
            Sit.PARK -> {
                rows += row(170 * U) { r ->
                    val cs = cols(r, 1.2f, 1f)
                    box(c, cs[0], "screen:log"); var x0 = cs[0].left + 32 * U
                    lab(c, "이번 주행", x0, r.top + 40 * U)
                    stat(c, x0, r.top + 108 * U, "%.1fkm · ${max(1, m.tripMin)}분".format(m.tripKm), "급정거 ${m.harshN} · 크루즈 ${m.cruiseMin}분 · 평균 ${m.avgKmh}km/h", FG, cs[0].width() - 64 * U)
                    box(c, cs[1], "deco"); x0 = cs[1].left + 32 * U
                    lab(c, "온기 포인트", x0, r.top + 40 * U)
                    stat(c, x0, r.top + 108 * U, sgn(m.tripPoints), m.ledger.take(2).joinToString(" · ") { "${it.why} ${sgn(it.delta)}" }.ifBlank { "모은 별 ${m.points} · 꾸미기에 써요" }, if (m.tripPoints >= 0) GOOD else RED, cs[1].width() - 64 * U)
                }
                rows += row(200 * U) { r ->
                    box(c, r, "view:webtoon"); val x0 = r.left + 32 * U
                    lab(c, "쉬는 동안 · 웹툰 이어보기", x0, r.top + 40 * U)
                    val items = m.toons.take(4)
                    if (items.isEmpty()) text(c, "웹툰을 불러오는 중이에요", x0, r.top + 110 * U, 26 * U, MUTED, medium)
                    val cw = (r.width() - 64 * U) / 4
                    items.forEachIndexed { i, tn ->
                        val ix = x0 + i * cw
                        val th = RectF(ix, r.top + 62 * U, ix + (r.bottom - 22 * U - r.top - 62 * U) / 1.3f, r.bottom - 22 * U)
                        photoFit(c, tn.img, th, 10 * U)
                        text(c, tn.title, th.right + 16 * U, th.top + 34 * U, 24 * U, FG, medium, maxW = cw - 130 * U)
                        lab(c, "이어보기", th.right + 16 * U, th.top + 66 * U)
                    }
                }
                rows += row(140 * U) { r ->
                    val cs = cols(r, 1f, 1f)
                    mini(c, cs[0], "쉼표", "15분 쉬어 가기", "등받이 젖히고 목 스트레칭 세 번", null)
                    if (m.novice) mini(c, cs[1], "초보 · 주차 요령", "핸들 풀고 → P → 주차 브레이크", "내리기 전에 사이드미러 접기", null)
                    else mini(c, cs[1], "내리기 전에", "휴대폰 · 지갑", "뒷좌석도 한 번 봐요", null)
                }
            }
            Sit.OFF -> {
                rows += row(170 * U) { r ->
                    box(c, r, "screen:log"); val x0 = r.left + 32 * U
                    lab(c, "오늘 하루", x0, r.top + 40 * U)
                    val cs = cols(RectF(x0, r.top, r.right - 32 * U, r.bottom), 1f, 1f, 1f)
                    stat(c, cs[0].left, r.top + 108 * U, "%.1fkm".format(m.dayKmShown), "오늘 달린 거리", FG, cs[0].width())
                    stat(c, cs[1].left, r.top + 108 * U, sgn(m.todayPoints), "오늘 별 · 이번 주 ${m.weekPoints} / ${m.weekGoal}", GOOD, cs[1].width())
                    stat(c, cs[2].left, r.top + 108 * U, "${m.stopsToday}회", "신호 대기", FG, cs[2].width())
                }
                rows += row(220 * U) { r ->
                    box(c, r); val x0 = r.left + 32 * U; val x1 = r.right - 32 * U
                    lab(c, "내리기 전에", x0, r.top + 40 * U)
                    val win = st?.windowOpen?.v == true; val kid = st?.rearKid?.v == true; val pas = st?.passenger?.v == true
                    listOf(Triple("휴대폰 · 지갑", if (pas) "조수석에 누가 있어요 — 함께 챙겨요" else "조수석 감지 없음", false),
                        Triple("뒷좌석 확인", if (kid) "뒷좌석 착석 감지 — 꼭 확인" else "뒷좌석 감지 없음", kid),
                        Triple("창문 · 실내등", if (win) "창문이 열려 있어요" else "모두 닫힘", win)).forEachIndexed { i, (a, b, warn) ->
                        val y = r.top + 64 * U + i * 50 * U
                        p.color = LINE; c.drawRect(x0, y, x1, y + max(1f, U), p)
                        text(c, a, x0, y + 34 * U, 26 * U, FG, medium)
                        text(c, b, x1, y + 34 * U, 22 * U, if (warn) WARN else MUTED, medium, Paint.Align.RIGHT)
                    }
                }
                rows += row(130 * U) { r ->
                    val cs = cols(r, 1f, 1f)
                    mini(c, cs[0], "내일", m.tomorrowLine.ifBlank { "불러오는 중" }, "09:30 주간 회의 · 예시", "view:weather")
                    val next = StageModel.CONCEPTS.filter { "concept:${it.id}" !in m.owned }.minByOrNull { it.price }
                    mini(c, cs[1], "꾸미기", next?.let { if (m.points >= it.price) "${it.name} 열 수 있어요" else "${it.name}까지 ${it.price - m.points}★" } ?: "전부 열었어요", "테마 · 소품 보기", "deco")
                }
            }
            Sit.DRIVE -> {}
        }
        stack(c, z, rows)
    }

    // ---------- 취미 레이아웃 ----------
    private fun category(c: Canvas, z: RectF, k: String) {
        when (k) {
            "news" -> {
                val cs = cols(z, 1.1f, 1f)
                val lead = leadNews()
                val L = cs[0]
                box(c, L, "say:뉴스 들려줘")
                val ir = RectF(L.left, L.top, L.right, L.bottom - 150 * U)
                photoFit(c, lead?.let { m.newsImgs[it] }, ir, 24 * U, L)
                lab(c, "관심 뉴스 · 연합뉴스" + (lead?.let { m.newsTimes[it] }?.let { " $it" } ?: ""), L.left + 32 * U, ir.bottom + 40 * U)
                wrap(c, lead ?: "헤드라인을 불러오는 중이에요", L.left + 32 * U, ir.bottom + 84 * U, 30 * U, FG, L.width() - 64 * U, 2, medium, lh = 1.3f)
                val R = cs[1]; box(c, R); val x0 = R.left + 32 * U; val x1 = R.right - 32 * U
                lab(c, "헤드라인 · ${m.newsSource.substringBefore(" ·").ifBlank { "연합뉴스" }}", x0, R.top + 40 * U, R.width() - 64 * U)
                var y = R.top + 64 * U
                m.newsList.filter { it != lead }.forEachIndexed { i, n ->
                    if (y + 68 * U > R.bottom - 112 * U) return@forEachIndexed
                    p.color = LINE; c.drawRect(x0, y, x1, y + max(1f, U), p)
                    text(c, "${i + 2}", x0, y + 43 * U, 22 * U, FAINT, medium)
                    text(c, n, x0 + 36 * U, y + 43 * U, 24 * U, FG, medium, maxW = x1 - x0 - 120 * U)
                    m.newsTimes[n]?.let { text(c, it, x1, y + 42 * U, 18 * U, FAINT, medium, Paint.Align.RIGHT) }
                    y += 68 * U
                }
                button(c, "헤드라인 듣기", RectF(x0, R.bottom - 32 * U - 60 * U, x0 + 230 * U, R.bottom - 32 * U), true, "say:뉴스 들려줘")
                text(c, "다음 소식", x0 + 256 * U, R.bottom - 32 * U - 22 * U, 22 * U, MUTED, medium)
            }
            "webtoon" -> {
                val shelf = RectF(z.left, z.top, z.right, z.bottom - 112 * U)
                box(c, shelf)
                lab(c, "오늘의 인기 · 네이버 웹툰 ${m.toonSource.substringAfter(" ")}", shelf.left + 32 * U, shelf.top + 40 * U)
                // 표지(네이버 썸네일 480×623 ≈ 1:1.3)를 자르지 않게 — 비율 그대로 한 줄 4권, 넘기기
                val g = RectF(shelf.left + 32 * U, shelf.top + 62 * U, shelf.right - 32 * U, shelf.bottom - 20 * U)
                val per = 4; val ratio = 623f / 480f
                val coverW = min((g.width() - G * 1.5f * (per - 1)) / per, (g.height() - 60 * U) / ratio); val coverH = coverW * ratio
                val gapX = (g.width() - coverW * per) / (per - 1)
                val pages = max(1, (m.toons.size + per - 1) / per); val pg = m.toonPage % pages
                if (m.toons.isEmpty()) text(c, "웹툰을 불러오는 중이에요", g.left, g.top + 60 * U, 26 * U, MUTED, medium)
                if (pages > 1) { val lab2 = "${pg * per + 1}–${min(m.toons.size, pg * per + per)} / ${m.toons.size}   다음 ›"
                    text(c, lab2, shelf.right - 32 * U, shelf.top + 40 * U, 20 * U, ACC, semi, Paint.Align.RIGHT)
                    p.textSize = 20 * U; p.typeface = semi; hits += RectF(shelf.right - 48 * U - p.measureText(lab2), shelf.top + 8 * U, shelf.right - 16 * U, shelf.top + 60 * U) to "toon:next" }
                val gy = g.top + (g.height() - coverH - 52 * U) / 2
                m.toons.drop(pg * per).take(per).forEachIndexed { i, tn ->
                    val gx = g.left + i * (coverW + gapX)
                    val cr = RectF(gx, gy, gx + coverW, gy + coverH)
                    photoFit(c, tn.img, cr, 14 * U)
                    val br = RectF(cr.left + 10 * U, cr.top + 10 * U, cr.left + 46 * U, cr.top + 46 * U)
                    p.color = 0xCC111827.toInt(); c.drawRoundRect(br, 8 * U, 8 * U, p)
                    text(c, "${tn.rank}", br.centerX(), br.centerY() + 8 * U, 22 * U, Color.WHITE, semi, Paint.Align.CENTER)
                    text(c, tn.title, cr.left, cr.bottom + 30 * U, 24 * U, FG, medium, maxW = coverW)
                    text(c, "★ %.2f · ${tn.author}".format(tn.star), cr.left, cr.bottom + 54 * U, 18 * U, FAINT, medium, maxW = coverW)
                    hits += cr to "noop"
                }
                val rr = RectF(z.left, z.bottom - 96 * U, z.right, z.bottom)
                box(c, rr)
                val t0 = m.toons.firstOrNull()
                photoFit(c, t0?.img, RectF(rr.left + 16 * U, rr.top + 12 * U, rr.left + 16 * U + 72 * U / 1.3f, rr.bottom - 12 * U), 8 * U)
                lab(c, "이어보기 · 예시", rr.left + 96 * U, rr.top + 36 * U)
                text(c, "${t0?.title ?: "웹툰"} · 128화", rr.left + 96 * U, rr.top + 72 * U, 28 * U, FG, bold)
                button(c, "이어서 보기", RectF(rr.right - 24 * U - 200 * U, rr.top + 18 * U, rr.right - 24 * U, rr.bottom - 18 * U), true, "noop")
            }
            "music" -> {
                val top = RectF(z.left, z.top, z.right, z.top + min(360 * U, z.height() * 0.58f))
                box(c, top)
                val a = top.height() - 48 * U
                val ar = RectF(top.left + 24 * U, top.top + 24 * U, top.left + 24 * U + a, top.top + 24 * U + a)
                p.shader = LinearGradient(ar.left, ar.top, ar.right, ar.bottom, ACC, 0xFFC9A27A.toInt(), Shader.TileMode.CLAMP); c.drawRoundRect(ar, 20 * U, 20 * U, p); p.shader = null
                icon(c, "music", ar.centerX(), ar.centerY(), a * 0.36f, Glass.a(Color.WHITE, 220))
                val x0 = ar.right + 40 * U; val iw = top.right - 40 * U - x0
                lab(c, "지금 재생 · 예시(미디어 세션 연동 전)", x0, top.top + 52 * U, iw)
                text(c, "Morning Drive", x0, top.top + 116 * U, 52 * U, FG, bold, maxW = iw)
                text(c, "출근길 플레이리스트 · 3/12", x0, top.top + 156 * U, 24 * U, MUTED, medium, maxW = iw)
                val bar = RectF(x0, top.top + 196 * U, top.right - 40 * U, top.top + 204 * U)
                p.color = SURF2; c.drawRoundRect(bar, 4 * U, 4 * U, p); p.color = ACC; c.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * 0.38f, bar.bottom), 4 * U, 4 * U, p)
                text(c, "1:24", bar.left, bar.bottom + 28 * U, 18 * U, FAINT, medium); text(c, "3:41", bar.right, bar.bottom + 28 * U, 18 * U, FAINT, medium, Paint.Align.RIGHT)
                val by = top.bottom - 40 * U - 60 * U
                if (by > bar.bottom + 40 * U) {
                    button(c, "이전", RectF(x0, by, x0 + 120 * U, by + 60 * U), false, "noop")
                    button(c, "일시정지", RectF(x0 + 136 * U, by, x0 + 316 * U, by + 60 * U), true, "noop")
                    button(c, "다음", RectF(x0 + 332 * U, by, x0 + 452 * U, by + 60 * U), false, "noop")
                }
                val q = RectF(z.left, top.bottom + G, z.right, z.bottom)
                if (q.height() > 120 * U) { box(c, q); val qx = q.left + 32 * U; lab(c, "다음 곡", qx, q.top + 40 * U)
                    listOf(Triple("4", "Golden Hour Highway", "3:58"), Triple("5", "Soft Lights", "4:12"), Triple("6", "뉴스 5분 브리핑(편성)", "5:00")).forEachIndexed { i, (n, tt, d) ->
                        val y = q.top + 60 * U + i * 56 * U; if (y + 56 * U > q.bottom - 8 * U) return@forEachIndexed
                        p.color = LINE; c.drawRect(qx, y, q.right - 32 * U, y + max(1f, U), p)
                        text(c, n, qx, y + 38 * U, 20 * U, FAINT, medium); text(c, tt, qx + 40 * U, y + 38 * U, 24 * U, FG, medium); text(c, d, q.right - 32 * U, y + 38 * U, 20 * U, FAINT, medium, Paint.Align.RIGHT) } }
            }
            "sports" -> {
                val sb = RectF(z.left, z.top, z.right, z.top + min(360 * U, z.height() - 150 * U))
                box(c, sb); val x0 = sb.left + 32 * U
                lab(c, "한국시리즈 6차전 · 8회말 · 2사 1,2루 · 예시(스포츠 연동 전)", x0, sb.top + 40 * U, sb.width() - 64 * U)
                val sc = cols(RectF(x0, sb.top, sb.right - 32 * U, sb.bottom), 1.1f, 1f)
                val a = sc[0]; val l = a.left + a.width() * 0.22f; val rr = a.left + a.width() * 0.78f
                text(c, "서울", l, sb.top + 110 * U, 30 * U, MUTED, medium, Paint.Align.CENTER); text(c, "울산", rr, sb.top + 110 * U, 30 * U, MUTED, medium, Paint.Align.CENTER)
                text(c, "4", l, sb.top + 270 * U, 150 * U, FG, bold, Paint.Align.CENTER); text(c, "2", rr, sb.top + 270 * U, 150 * U, FG, bold, Paint.Align.CENTER)
                text(c, ":", a.centerX(), sb.top + 230 * U, 60 * U, FAINT, medium, Paint.Align.CENTER)
                val tb = sc[1]; val heads = listOf("", "1", "2", "3", "4", "5", "6", "7", "8", "9", "R")
                val rowsT = listOf(listOf("서울", "0", "1", "0", "0", "2", "0", "0", "1", "-", "4"), listOf("울산", "0", "0", "0", "2", "0", "0", "0", "0", "-", "2"))
                val cw = tb.width() / heads.size; val ty = sb.top + 140 * U
                heads.forEachIndexed { i, s2 -> text(c, s2, tb.left + cw * (i + 0.5f), ty, 20 * U, FAINT, medium, Paint.Align.CENTER) }
                rowsT.forEachIndexed { ri, row -> val yy = ty + 52 * U + ri * 48 * U; p.color = LINE; c.drawRect(tb.left, yy - 34 * U, tb.right, yy - 34 * U + max(1f, U), p)
                    row.forEachIndexed { i, s2 -> text(c, s2, tb.left + cw * (i + 0.5f), yy, 22 * U, if (i == row.size - 1) FG else MUTED, if (i == row.size - 1) bold else medium, Paint.Align.CENTER) } }
                val o = RectF(z.left, sb.bottom + G, z.right, z.bottom)
                if (o.height() > 100 * U) { box(c, o); val ox = o.left + 32 * U; lab(c, "다른 경기 · 예시", ox, o.top + 40 * U)
                    listOf("K리그1 · 울산 2 : 1 서울" to "종료", "NBA · LAL 98 : 101 BOS" to "4Q 2:10").forEachIndexed { i, (a2, b2) -> val y = o.top + 60 * U + i * 56 * U; if (y + 56 * U > o.bottom) return@forEachIndexed
                        p.color = LINE; c.drawRect(ox, y, o.right - 32 * U, y + max(1f, U), p); text(c, a2, ox, y + 38 * U, 24 * U, FG, medium); text(c, b2, o.right - 32 * U, y + 38 * U, 20 * U, FAINT, medium, Paint.Align.RIGHT) } }
            }
            "stock" -> {
                val cs = cols(z, 1.15f, 1f)
                val L = cs[0]; box(c, L); val x0 = L.left + 32 * U
                m.indices.take(2).forEachIndexed { i, q ->
                    val ix = x0 + i * (L.width() - 64 * U) / 2
                    lab(c, q.name, ix, L.top + 40 * U)
                    text(c, q.price, ix, L.top + 104 * U, 52 * U, FG, bold)
                    text(c, pct(q.ratio), ix, L.top + 140 * U, 24 * U, updown(q.ratio), semi)
                }
                if (m.indices.isEmpty()) text(c, "시세를 불러오는 중이에요", x0, L.top + 100 * U, 28 * U, MUTED, medium)
                val ch = RectF(x0, L.top + 180 * U, L.right - 32 * U, L.bottom - 64 * U)
                if (ch.height() > 60 * U) {
                    val r0 = m.indices.firstOrNull()?.ratio ?: 0f
                    val rnd = java.util.Random(7); val n = 48
                    val pts = FloatArray(n) { i -> val f = i / (n - 1f); (0.5f + 0.18f * sin(f * 9f) * (1 - f) + (rnd.nextFloat() - 0.5f) * 0.12f) * (1 - f) + (0.5f - r0 * 0.25f) * f }
                    p.color = LINE; var dx = ch.left; while (dx < ch.right) { c.drawRect(dx, ch.top + ch.height() * 0.5f, dx + 8 * U, ch.top + ch.height() * 0.5f + max(1f, U), p); dx += 16 * U }
                    path.reset(); pts.forEachIndexed { i, v2 -> val px = ch.left + ch.width() * i / (n - 1); val py = ch.top + ch.height() * v2.coerceIn(0.05f, 0.95f); if (i == 0) path.moveTo(px, py) else path.lineTo(px, py) }
                    p.style = Paint.Style.STROKE; p.strokeWidth = 3 * U; p.strokeJoin = Paint.Join.ROUND; p.color = updown(r0).let { if (it == MUTED) ACC else it }; c.drawPath(path, p); p.style = Paint.Style.FILL; p.strokeJoin = Paint.Join.MITER
                    lab(c, "장중 흐름 · 모양 예시(장중 시세 연동 전)", x0, L.bottom - 28 * U, col = FAINT)
                }
                val R = cs[1]; box(c, R); val rx = R.left + 32 * U; val rx1 = R.right - 32 * U
                lab(c, "시가총액 상위 · ${m.stockAsof}", rx, R.top + 40 * U, R.width() - 64 * U)
                m.stocks.forEachIndexed { i, q ->
                    val y = R.top + 64 * U + i * 62 * U; if (y + 62 * U > R.bottom - 12 * U) return@forEachIndexed
                    p.color = LINE; c.drawRect(rx, y, rx1, y + max(1f, U), p)
                    text(c, "${i + 1}", rx, y + 40 * U, 20 * U, FAINT, medium)
                    text(c, q.name, rx + 36 * U, y + 40 * U, 24 * U, FG, medium, maxW = (rx1 - rx) * 0.42f)
                    text(c, "${q.price}원", rx1 - 110 * U, y + 40 * U, 22 * U, FG, medium, Paint.Align.RIGHT)
                    text(c, pct(q.ratio), rx1, y + 40 * U, 22 * U, updown(q.ratio), semi, Paint.Align.RIGHT)
                }
            }
            "weather" -> stack(c, z, listOf(
                row(230 * U) { r -> box(c, r); val x0 = r.left + 40 * U
                    val t2 = m.tempNow?.let { "$it°" } ?: "—"
                    text(c, t2, x0, r.top + 176 * U, 150 * U, FG, bold)
                    p.textSize = 150 * U; p.typeface = bold; val tx = x0 + p.measureText(t2) + 40 * U
                    text(c, m.weatherShort.substringAfter(" ", "").ifBlank { "날씨" }, tx, r.top + 104 * U, 36 * U, FG, medium)
                    lab(c, "최저 ${m.todayMin ?: "-"}° · 최고 ${m.todayMax ?: "-"}° · 비 확률 ${m.rainMax}% · Open-Meteo", tx, r.top + 146 * U, r.right - tx - 32 * U)
                    icon(c, "cloud", r.right - 80 * U, r.top + 80 * U, 64 * U, MUTED) },
                row(160 * U) { r -> box(c, r)
                    val hs = m.hourly.take(6); if (hs.isEmpty()) text(c, "시간대별 날씨를 불러오는 중이에요", r.left + 32 * U, r.centerY() + 9 * U, 26 * U, MUTED, medium)
                    val cw = (r.width() - 64 * U) / max(1, hs.size)
                    hs.forEachIndexed { i, (hh, tt, rp) -> val hx = r.left + 32 * U + cw * (i + 0.5f)
                        text(c, if (i == 0) "지금" else hh, hx, r.top + 44 * U, 20 * U, MUTED, medium, Paint.Align.CENTER)
                        text(c, "$tt°", hx, r.top + 98 * U, 36 * U, FG, bold, Paint.Align.CENTER)
                        text(c, "비 $rp%", hx, r.top + 132 * U, 18 * U, if (rp >= 50) ACC else FAINT, medium, Paint.Align.CENTER) } },
                row(130 * U) { r -> box(c, r); val x0 = r.left + 32 * U
                    lab(c, "차림 · 차 안", x0, r.top + 40 * U)
                    text(c, outfitLine(), x0, r.top + 84 * U, 30 * U, FG, medium, maxW = r.width() - 64 * U)
                    lab(c, "출발 10분 전 히터 22°로 미리 켜 둘까요 · 와이퍼가 켜지면 비 모드로 바꿔요", x0, r.top + 116 * U, r.width() - 64 * U) }))
            "local" -> {
                val qh = min(320 * U, z.height() - 150 * U)
                val q = RectF(z.left, z.top, z.right, z.top + qh)
                box(c, q, "say:동네 이야기 들려줘"); val x0 = q.left + 40 * U
                text(c, "“", x0, q.top + 100 * U, 96 * U, ACC, bold)
                val g = m.tiles.firstOrNull { it.icon == "chat" }?.value ?: LOCAL[0].first
                wrap(c, g, x0, q.top + 140 * U, 38 * U, FG, q.width() - 80 * U, 3, medium, lh = 1.3f)
                lab(c, "소금빵 가게 · 오른쪽 180m · 걸어서 3분", x0, q.bottom - 32 * U)
                lab(c, "예시 — 카카오 로컬 연동 예정", q.right - 40 * U - 300 * U, q.bottom - 32 * U, 300 * U, FAINT)
                val l = RectF(z.left, q.bottom + G, z.right, z.bottom)
                if (l.height() > 100 * U) { box(c, l); val lx = l.left + 32 * U; lab(c, "근처", lx, l.top + 40 * U)
                    LOCAL.drop(1).forEachIndexed { i, (a, b) -> val y = l.top + 60 * U + i * 56 * U; if (y + 56 * U > l.bottom) return@forEachIndexed
                        p.color = LINE; c.drawRect(lx, y, l.right - 32 * U, y + max(1f, U), p); text(c, a, lx, y + 38 * U, 24 * U, FG, medium); text(c, b, l.right - 32 * U, y + 38 * U, 20 * U, FAINT, medium, Paint.Align.RIGHT) } }
            }
            else -> {   // schedule
                box(c, z); val x0 = z.left + 32 * U; val x1 = z.right - 32 * U
                lab(c, "오늘 일정 · 예시(폰 일정·할 일 연동 전)", x0, z.top + 40 * U)
                val items = listOf(Triple("09:30", "주간 회의", "판교 사옥 3층"), Triple("가는 김에", "세탁물 찾기", "가는 길 0.4km · 5분"), Triple("12:00", "점심 — 민지", "정자동 카페거리"), Triple("17:30", "정비 예약", "블루핸즈 분당점"))
                items.forEachIndexed { i, (tm, tt, sub) ->
                    val y = z.top + 64 * U + i * 100 * U; if (y + 100 * U > z.bottom - 8 * U) return@forEachIndexed
                    val by2 = i == 1
                    if (by2) { p.color = ACC_SOFT; c.drawRoundRect(RectF(x0 - 12 * U, y + 8 * U, x1 + 12 * U, y + 92 * U), 16 * U, 16 * U, p) }
                    else { p.color = LINE; c.drawRect(x0, y, x1, y + max(1f, U), p) }
                    text(c, tm, x0, y + 46 * U, if (by2) 24 * U else 30 * U, if (by2) ACC else FG, if (by2) semi else bold)
                    text(c, tt, x0 + 200 * U, y + 46 * U, 28 * U, FG, medium, maxW = x1 - x0 - 440 * U)
                    lab(c, sub, x0 + 200 * U, y + 76 * U)
                    if (by2) button(c, "경유 추가", RectF(x1 - 170 * U, y + 22 * U, x1, y + 78 * U), true, "say:세탁물 찾으러 들렀다 가자")
                }
            }
        }
    }

    // ---------- 스킨 그리기 ----------
    /** 스킨 바탕 — 단색 + 무늬(halftone 점 · grid 선). 주행 중에도 정지된 무늬라 그대로 */
    private fun skinBg(c: Canvas, w: Float, h: Float) {
        p.shader = null; p.style = Paint.Style.FILL; p.color = sk.colors.bg; c.drawRect(0f, 0f, w, h, p)
        sceneDark = darkUi
        when (sk.style.pattern) {
            "halftone" -> { p.color = sk.style.patternColor; var y = 10 * U; var row = 0
                while (y < h) { val r = 1.4f * U + 3.2f * U * (y / h); var x = if (row % 2 == 0) 10 * U else 24 * U; while (x < w) { c.drawCircle(x, y, r, p); x += 28 * U }; y += 24 * U; row++ } }
            "grid" -> { p.color = sk.style.patternColor; var x = 0f; while (x < w) { c.drawRect(x, 0f, x + U, h, p); x += 48 * U }; var y = 0f; while (y < h) { c.drawRect(0f, y, w, y + U, p); y += 48 * U } }
        }
    }
    /** 스킨 카드 — 딱딱한 그림자 + 면 + 잉크 테두리(스킨 style) */
    private fun inkCard(c: Canvas, r: RectF, rad0: Float, tint: Int?) {
        val st = sk.style; val rad = rad0 * st.radius; val bw = st.border * S * 100
        p.shader = null; p.style = Paint.Style.FILL
        if (st.shadowX > 0f || st.shadowY > 0f) { p.color = st.shadowColor; c.drawRoundRect(RectF(r).apply { offset(st.shadowX * S * 100, st.shadowY * S * 100) }, rad, rad, p) }
        p.color = SURF; c.drawRoundRect(r, rad, rad, p)
        tint?.let { p.color = Glass.a(it, 40); c.drawRoundRect(r, rad, rad, p) }
        if (bw > 0f) { p.style = Paint.Style.STROKE; p.strokeWidth = bw; p.color = st.borderColor; c.drawRoundRect(r, rad, rad, p); p.style = Paint.Style.FILL }
    }
    /** 스킨 스티커(효과음 글) + 별 터짐 — 듣기·생각·말하기·기쁨일 때만, 운전 중엔 그리지 않는다(부르는 쪽에서 막음) */
    private fun sticker(c: Canvas, cx: Float, feet: Float, chH: Float, face: String) {
        val key = when (v.mode) { HyunView.Mode.LISTENING -> "listening"; HyunView.Mode.THINKING -> "thinking"; HyunView.Mode.SPEAKING -> "speaking"; else -> if (face == "happy" || face == "cheer") "happy" else null } ?: return
        val txt = sk.style.stickers[key] ?: return
        val sx = cx + chH * (if (mirror) -0.55f else 0.55f); val sy = feet - chH * 0.92f
        if (sk.style.burst) { p.color = sk.style.burstColor; path.reset()
            for (i in 0 until 16) { val a2 = Math.toRadians(i * 22.5); val rr = if (i % 2 == 0) 64 * U else 40 * U; val x = sx + (rr * Math.cos(a2)).toFloat(); val y = sy + (rr * Math.sin(a2)).toFloat(); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
            path.close(); c.drawPath(path, p) }
        c.save(); c.rotate(-8f, sx, sy)
        p.textSize = 26 * U; p.typeface = sk.fonts.title; val tw = p.measureText(txt)
        val r = RectF(sx - tw / 2 - 16 * U, sy - 24 * U, sx + tw / 2 + 16 * U, sy + 22 * U)
        p.color = sk.style.stickerColor; c.drawRoundRect(r, 12 * U, 12 * U, p)
        val bw = sk.style.border * S * 100; if (bw > 0f) { p.style = Paint.Style.STROKE; p.strokeWidth = bw; p.color = sk.style.borderColor; c.drawRoundRect(r, 12 * U, 12 * U, p); p.style = Paint.Style.FILL }
        text(c, txt, sx, sy + 9 * U, 26 * U, sk.style.stickerText, sk.fonts.title, Paint.Align.CENTER)
        c.restore()
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
        /** 테마 말투 — 대기 중 캐릭터 캡션에 번갈아 */
        val THEME_LINES = mapOf(
            "pleos" to listOf("오늘도 조용히 곁에 있을게요", "필요하면 \"현비서야\" 하고 불러 주세요"),
            "glass" to listOf("오로라가 흐르는 밤이에요", "창밖 빛이 곱네요"),
            "cafe" to listOf("오늘의 블렌드, 준비됐어요", "따뜻한 한 잔 내려 둘까요"),
            "camp" to listOf("불 쬐면서 쉬어 가요", "숲 냄새가 좋은 날이에요"),
            "ocean" to listOf("오늘 파도 괜찮아요", "바람이 시원하네요"),
            "space" to listOf("관제실, 이상 없음", "궤도 순항 중이에요"),
            "hanok" to listOf("차 한 잔 하고 가세요", "마루에 바람이 드네요"),
            "neon" to listOf("도시는 아직 깨어 있어요", "오늘 밤도 함께 달려요"),
            "arcade" to listOf("코인은 충분해요", "다음 스테이지로 가 볼까요"))
        /** 취미 레이아웃마다 캐릭터 표정 */
        val VIEW_FACE = mapOf("music" to "humming", "stock" to "thinking", "local" to "humming", "webtoon" to "happy", "weather" to "happy", "sports" to "happy")
        /** 동네 이야기(예시 — 카카오 로컬 연동 전) · 거리 */
        val LOCAL = listOf("이 길 건너 빵집은 아침 일곱 시 소금빵이 십 분 만에 동난대요." to "180m", "탄천 산책로 — 해 질 무렵 자전거 길" to "1.1km", "모퉁이 카페 — 직접 볶은 원두" to "350m", "점심 샐러드 가게 — 줄이 제일 긴 곳" to "600m")
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
        glassCard(c, r, 24 * U)
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

    // ======================================================================
    // 테마 — "다른 앱처럼"(댄 11-09). 글꼴 · 카드 틀 · 버튼 · 구분선 · 배경 모티프 · 캐릭터 소품 · 인트로/마무리 · 말투를 테마마다
    // 그림 파일 없이 코드로만. 주행 중엔 입자·반복 모션을 끄고 정지된 모티프만 남긴다
    // ======================================================================
    private fun tf(name: String): Typeface = fonts.getOrPut(name) { runCatching { Typeface.createFromAsset(v.context.assets, "fonts/$name") }.getOrElse { font("Medium") } }
    /** 테마 제목 글꼴 */
    private fun themeHead(): Typeface = when (k.id) {
        "cafe" -> tf("NanumPenScript-Regular.ttf"); "arcade" -> tf("Galmuri11.ttf"); "hanok" -> tf("GowunBatang-Regular.ttf")
        "neon" -> tf("BlackHanSans-Regular.ttf"); "camp", "ocean" -> tf("Jua-Regular.ttf"); "space" -> tf("DoHyeon-Regular.ttf")
        "glass" -> font("Regular"); else -> font("Medium")
    }
    /** 테마 본문 글꼴 — 아케이드·한옥만 본문까지 바꾼다(가독성) */
    private fun themeBody(): Typeface = when (k.id) { "arcade" -> tf("Galmuri11.ttf"); "hanok" -> tf("GowunBatang-Regular.ttf"); else -> font("Regular") }
    /** 테마 제목 크기 배율 — 손글씨·픽셀은 크기감이 달라 보정 */
    private fun headScale() = if (skinned) 1f else when (k.id) { "cafe" -> 1.25f; "arcade" -> 0.78f; "neon" -> 0.95f; "space" -> 1.05f; else -> 1f }
    private val mirror get() = if (skinned) sk.layout.charRight else k.id == "cafe"   // 카페: 메뉴판(카드)이 왼쪽, 바리스타(캐릭터)가 오른쪽

    // 테마 카드 위 글자색(카드 면이 어두운지)
    private fun cardDark() = when (k.id) { "pleos" -> darkUi; "hanok", "ocean" -> false; else -> true }

    /** 테마 카드 틀 — 기본은 glassCard */
    private fun themeCard(c: Canvas, r: RectF, rad: Float, tint: Int?): Boolean {
        val sp = Path()
        when (k.id) {
            "cafe" -> {   // 칠판 메뉴판 — 나무 틀 + 분필 면
                sp.addRoundRect(r, 10 * U, 10 * U, Path.Direction.CW)
                p.color = 0xFF2A3731.toInt(); p.setShadowLayer(18 * U, 0f, 8 * U, 0x40000000); c.drawPath(sp, p); p.clearShadowLayer()
                p.style = Paint.Style.STROKE; p.strokeWidth = 9 * U; p.color = 0xFF8A5B38.toInt(); c.drawRoundRect(r, 10 * U, 10 * U, p)
                p.strokeWidth = 1.5f * U; p.color = 0xFFC99D6E.toInt(); c.drawRoundRect(RectF(r).apply { inset(5 * U, 5 * U) }, 7 * U, 7 * U, p)
                p.style = Paint.Style.FILL
                p.color = Glass.a(Color.WHITE, 10); for (i in 0 until 6) c.drawCircle(r.left + r.width() * (0.15f + i * 0.14f), r.top + r.height() * (0.3f + (i % 3) * 0.2f), 40 * U, p)   // 분필 얼룩
            }
            "arcade" -> {   // CRT 패널 — 각진 이중 네온 테두리
                p.color = 0xF00D0918.toInt(); c.drawRect(r, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 3 * U; p.color = 0xFFFF4FD8.toInt(); c.drawRect(r, p)
                p.strokeWidth = 1.5f * U; p.color = 0xFF00F0FF.toInt(); c.drawRect(RectF(r).apply { inset(7 * U, 7 * U) }, p)
                p.style = Paint.Style.FILL
                p.color = 0xFFFF4FD8.toInt(); for ((x, y) in listOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)) c.drawRect(x - 5 * U, y - 5 * U, x + 5 * U, y + 5 * U, p)
            }
            "hanok" -> {   // 한지 + 문살 띠
                sp.addRoundRect(r, 6 * U, 6 * U, Path.Direction.CW)
                p.color = 0xFFF3EBDC.toInt(); p.setShadowLayer(16 * U, 0f, 6 * U, 0x33000000); c.drawPath(sp, p); p.clearShadowLayer()
                c.save(); c.clipPath(sp); paper(c, r); c.restore()
                val b = 16 * U
                p.style = Paint.Style.STROKE; p.color = 0xFF7A5639.toInt(); p.strokeWidth = 2.4f * U; c.drawRoundRect(r, 6 * U, 6 * U, p)
                p.strokeWidth = 1.2f * U; p.color = 0xAA7A5639.toInt()
                val inner = RectF(r).apply { inset(b, b) }; c.drawRect(inner, p)
                var x = r.left + b; while (x < r.right - b) { c.drawLine(x, r.top, x, r.top + b, p); c.drawLine(x, r.bottom - b, x, r.bottom, p); x += b }
                var y = r.top + b; while (y < r.bottom - b) { c.drawLine(r.left, y, r.left + b, y, p); c.drawLine(r.right - b, y, r.right, y, p); y += b }
                p.style = Paint.Style.FILL
            }
            "neon" -> {   // 어두운 유리 + 빛나는 윤곽
                sp.addRoundRect(r, 18 * U, 18 * U, Path.Direction.CW)
                p.color = 0xE6120D24.toInt(); c.drawPath(sp, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 2.2f * U; p.color = 0xFFFF5CB8.toInt(); p.setShadowLayer(14 * U, 0f, 0f, 0xFFFF5CB8.toInt()); c.drawPath(sp, p); p.clearShadowLayer()
                p.strokeWidth = 1f * U; p.color = 0x8800E0FF.toInt(); c.drawRoundRect(RectF(r).apply { inset(6 * U, 6 * U) }, 13 * U, 13 * U, p); p.style = Paint.Style.FILL
            }
            "camp" -> {   // 숲 판 — 위쪽 나무결 띠
                sp.addRoundRect(r, 14 * U, 14 * U, Path.Direction.CW)
                p.color = 0xF21C2620.toInt(); p.setShadowLayer(16 * U, 0f, 6 * U, 0x40000000); c.drawPath(sp, p); p.clearShadowLayer()
                c.save(); c.clipPath(sp)
                val band = RectF(r.left, r.top, r.right, r.top + 18 * U)
                p.shader = LinearGradient(0f, band.top, 0f, band.bottom, 0xFF8B5A34.toInt(), 0xFF6B4226.toInt(), Shader.TileMode.CLAMP); c.drawRect(band, p); p.shader = null
                p.style = Paint.Style.STROKE; p.strokeWidth = 1f * U; p.color = 0x55301A0A
                for (i in 0 until 4) { path.reset(); var xx = r.left; path.moveTo(xx, band.top + 4 * U + i * 3.5f * U); while (xx < r.right) { path.quadTo(xx + 30 * U, band.top + 2 * U + i * 3.5f * U + sin(xx * 0.03f + i) * 2 * U, xx + 60 * U, band.top + 4 * U + i * 3.5f * U); xx += 60 * U }; c.drawPath(path, p) }
                p.style = Paint.Style.FILL; c.restore()
                p.style = Paint.Style.STROKE; p.strokeWidth = 1f * U; p.color = 0x33FFE0B0; c.drawPath(sp, p); p.style = Paint.Style.FILL
            }
            "ocean" -> {   // 모래빛 둥근 판 + 거품 테두리
                sp.addRoundRect(r, 32 * U, 32 * U, Path.Direction.CW)
                p.color = 0xF5FFFBF2.toInt(); p.setShadowLayer(20 * U, 0f, 8 * U, 0x2A0B3B5C); c.drawPath(sp, p); p.clearShadowLayer()
                c.save(); c.clipPath(sp)
                p.color = 0xFFF3E6CC.toInt(); path.reset(); path.moveTo(r.left, r.bottom - 26 * U)
                var xx = r.left; while (xx <= r.right) { path.lineTo(xx, r.bottom - 26 * U + sin(xx * 0.02f) * 6 * U); xx += 8 * U }
                path.lineTo(r.right, r.bottom); path.lineTo(r.left, r.bottom); path.close(); c.drawPath(path, p); c.restore()
                p.style = Paint.Style.STROKE; p.strokeWidth = 2f * U; p.color = 0xFFBFEFF5.toInt(); c.drawPath(sp, p); p.style = Paint.Style.FILL
            }
            "space" -> {   // 계기 패널 — 모서리 괄호
                p.color = 0xE60A1222.toInt(); c.drawRoundRect(r, 8 * U, 8 * U, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 1f * U; p.color = 0x557DF9FF; c.drawRoundRect(r, 8 * U, 8 * U, p)
                p.strokeWidth = 3f * U; p.color = 0xFF7DF9FF.toInt(); val L = 22 * U
                for ((x, y, dx, dy) in listOf(floatArrayOf(r.left, r.top, 1f, 1f), floatArrayOf(r.right, r.top, -1f, 1f), floatArrayOf(r.left, r.bottom, 1f, -1f), floatArrayOf(r.right, r.bottom, -1f, -1f)).map { listOf(it[0], it[1], it[2], it[3]) }) {
                    c.drawLine(x, y, x + dx * L, y, p); c.drawLine(x, y, x, y + dy * L, p) }
                p.style = Paint.Style.FILL
            }
            "glass" -> {   // 서리 유리
                sp.addRoundRect(r, 26 * U, 26 * U, Path.Direction.CW)
                p.color = 0x33FFFFFF; c.drawPath(sp, p)
                p.shader = LinearGradient(0f, r.top, 0f, r.top + 80 * U, 0x30FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP); c.drawPath(sp, p); p.shader = null
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f * U; p.color = 0x66FFFFFF; c.drawPath(sp, p); p.style = Paint.Style.FILL
            }
            else -> return false
        }
        return true
    }

    // 한지 결 — 한 번 만든 작은 비트맵을 깔기
    private var paperBmp: Bitmap? = null
    private fun paper(c: Canvas, r: RectF) {
        val b = paperBmp ?: Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).also { bm ->
            val cv = Canvas(bm); val q = Paint(Paint.ANTI_ALIAS_FLAG); val rnd = java.util.Random(3)
            q.strokeWidth = 1f
            for (i in 0 until 420) { q.color = Glass.a(if (rnd.nextBoolean()) 0xFF8B6A4A.toInt() else 0xFFFFFFFF.toInt(), 18 + rnd.nextInt(26)); val x = rnd.nextFloat() * 256; val y = rnd.nextFloat() * 256; val a = rnd.nextFloat() * 6.28f; val l = 4 + rnd.nextFloat() * 14
                cv.drawLine(x, y, x + kotlin.math.cos(a) * l, y + kotlin.math.sin(a) * l, q) }
            paperBmp = bm
        }
        p.shader = android.graphics.BitmapShader(b, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT); p.color = Color.WHITE; c.drawRect(r, p); p.shader = null
    }

    /** 테마 구분선 — 칠판 물결 · 픽셀 점선 · 붓질 · 네온 선 · 나뭇가지 · 파도 · 눈금자 · 가는 선 */
    private fun divider(c: Canvas, x0: Float, x1: Float, y: Float) {
        val w = min(x1 - x0, 220 * U)
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        when (k.id) {
            "cafe" -> { p.strokeWidth = 2.4f * U; p.color = 0xCCEFEDE4.toInt(); path.reset(); path.moveTo(x0, y); var x = x0; while (x < x0 + w) { path.quadTo(x + 10 * U, y - 5 * U, x + 20 * U, y); path.quadTo(x + 30 * U, y + 5 * U, x + 40 * U, y); x += 40 * U }; c.drawPath(path, p) }
            "arcade" -> { p.style = Paint.Style.FILL; var x = x0; var i = 0; while (x < x0 + w) { p.color = if (i % 2 == 0) 0xFFFF4FD8.toInt() else 0xFF00F0FF.toInt(); c.drawRect(x, y - 3 * U, x + 6 * U, y + 3 * U, p); x += 12 * U; i++ } }
            "hanok" -> { p.style = Paint.Style.FILL; p.color = 0xFF3A2A1E.toInt(); path.reset(); path.moveTo(x0, y); path.cubicTo(x0 + w * 0.3f, y - 4 * U, x0 + w * 0.7f, y - 2 * U, x0 + w, y + 1 * U); path.cubicTo(x0 + w * 0.7f, y + 3 * U, x0 + w * 0.3f, y + 5 * U, x0, y + 1.5f * U); path.close(); c.drawPath(path, p)
                p.color = 0xFFB23A2E.toInt(); c.drawRect(x0 + w + 12 * U, y - 9 * U, x0 + w + 30 * U, y + 9 * U, p) }   // 붓질 + 붉은 낙관
            "neon" -> { p.strokeWidth = 2.4f * U; p.color = 0xFF7DE2FF.toInt(); p.setShadowLayer(8 * U, 0f, 0f, 0xFF7DE2FF.toInt()); c.drawLine(x0, y, x0 + w, y, p); p.clearShadowLayer() }
            "camp" -> { p.strokeWidth = 2.4f * U; p.color = 0xFF8B5A34.toInt(); c.drawLine(x0, y, x0 + w, y, p); p.style = Paint.Style.FILL; p.color = 0xFF6FCF97.toInt()
                for (i in 0 until 3) { val lx = x0 + w * (0.3f + i * 0.25f); c.drawOval(RectF(lx, y - 9 * U, lx + 14 * U, y - 1 * U), p) } }
            "ocean" -> { p.strokeWidth = 2.6f * U; p.color = 0xFF2BB5C9.toInt(); path.reset(); path.moveTo(x0, y); var x = x0; while (x < x0 + w) { path.cubicTo(x + 8 * U, y - 7 * U, x + 16 * U, y - 7 * U, x + 24 * U, y); x += 24 * U }; c.drawPath(path, p) }
            "space" -> { p.strokeWidth = 1f * U; p.color = 0xAA7DF9FF.toInt(); c.drawLine(x0, y, x0 + w, y, p); var x = x0; var i = 0; while (x <= x0 + w) { c.drawLine(x, y, x, y - (if (i % 5 == 0) 9f else 4f) * U, p); x += 10 * U; i++ } }
            "glass" -> { p.strokeWidth = 1.2f * U; p.shader = LinearGradient(x0, y, x0 + w, y, 0xFF7DF0FF.toInt(), 0x00B79CFF, Shader.TileMode.CLAMP); c.drawLine(x0, y, x0 + w, y, p); p.shader = null }
            else -> { p.strokeWidth = max(1f, U); p.color = LINE; c.drawLine(x0, y, x0 + 48 * U, y, p) }
        }
        p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
    }

    /** 테마 배경 모티프 — scene() 위, 캐릭터 아래 */
    private val flies by lazy { val r = java.util.Random(9); List(16) { floatArrayOf(r.nextFloat(), 0.45f + r.nextFloat() * 0.45f, r.nextFloat() * 6.28f, 0.5f + r.nextFloat()) } }
    private fun motif(c: Canvas, w: Float, h: Float, t: Float, still: Boolean) {
        when (k.id) {
            "glass" -> for (i in 0 until 3) {   // 오로라 띠
                val col = intArrayOf(0xFF6EF0D2.toInt(), 0xFF8C7CFF.toInt(), 0xFF4FB8FF.toInt())[i]
                val ph = if (still) 0f else t * 0.12f
                path.reset(); path.moveTo(0f, h * (0.18f + i * 0.08f)); var x = 0f
                while (x <= w) { path.lineTo(x, h * (0.18f + i * 0.08f) + sin(x / w * 5f + ph + i * 1.7f) * h * 0.05f); x += w / 48 }
                path.lineTo(w, 0f); path.lineTo(0f, 0f); path.close()
                p.color = Color.WHITE; p.shader = LinearGradient(0f, 0f, 0f, h * (0.26f + i * 0.08f), Glass.a(col, 0), Glass.a(col, 64), Shader.TileMode.CLAMP); c.drawPath(path, p); p.shader = null
            }
            "cafe" -> for (i in 0 until 3) {   // 매달린 전구 세 개(따뜻한 빛)
                val x = w * (if (mirror) 0.08f + i * 0.12f else 0.62f + i * 0.12f) + 0f; val y = h * (0.12f + (i % 2) * 0.04f)
                p.color = 0x66352A20; p.strokeWidth = 1.5f * U; c.drawLine(x, 0f, x, y - 14 * U, p)
                p.color = 0xFF3A2F26.toInt(); path.reset(); path.moveTo(x - 20 * U, y); path.lineTo(x - 10 * U, y - 16 * U); path.lineTo(x + 10 * U, y - 16 * U); path.lineTo(x + 20 * U, y); path.close(); c.drawPath(path, p)
                val fl = if (still) 1f else 0.9f + 0.1f * sin(t * 3f + i)
                p.shader = RadialGradient(x, y + 6 * U, 90 * U, Glass.a(0xFFFFC27A.toInt(), (110 * fl).toInt()), 0x00FFC27A, Shader.TileMode.CLAMP); c.drawCircle(x, y + 6 * U, 90 * U, p); p.shader = null
                p.color = 0xFFFFE2B0.toInt(); c.drawCircle(x, y + 3 * U, 6 * U, p)
            }
            "camp" -> {   // 솔숲 실루엣 + 반딧불
                p.color = 0xFF0F1A14.toInt()
                for (i in 0 until 9) { val x = w * (i / 8f) + (i % 2) * 30 * U; val bh = h * (0.22f + (i % 3) * 0.06f); val by = h * 0.74f
                    path.reset(); path.moveTo(x, by - bh); path.lineTo(x - bh * 0.28f, by); path.lineTo(x + bh * 0.28f, by); path.close(); c.drawPath(path, p) }
                if (m.sit != Sit.DRIVE) for (f in flies) {
                    val x = (f[0] * w + sin(t * 0.4f * f[3] + f[2]) * 40 * U); val y = f[1] * h + sin(t * 0.6f * f[3] + f[2] * 2) * 20 * U
                    val a = (0.4f + 0.6f * (0.5f + 0.5f * sin(t * 2f * f[3] + f[2])))
                    p.shader = RadialGradient(x, y, 14 * U, Glass.a(0xFFF8F08A.toInt(), (160 * a).toInt()), 0x00F8F08A, Shader.TileMode.CLAMP); c.drawCircle(x, y, 14 * U, p); p.shader = null
                    p.color = Glass.a(0xFFFFFCC8.toInt(), (230 * a).toInt()); c.drawCircle(x, y, 2.4f * U, p) }
            }
            "ocean" -> for (i in 0 until 3) {   // 바닥 파도 세 겹 — 천천히
                val ph = if (still) 0f else t * (0.5f + i * 0.2f)
                val base = h * (0.8f + i * 0.06f)
                path.reset(); path.moveTo(0f, h); path.lineTo(0f, base); var x = 0f
                while (x <= w) { path.lineTo(x, base + sin(x / w * 9f + ph + i) * 10 * U); x += w / 64 }
                path.lineTo(w, h); path.close()
                p.color = intArrayOf(0xCC5FD4E4.toInt(), 0xDD2BA7C4.toInt(), 0xFFF3E6CC.toInt())[i]; c.drawPath(path, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 2 * U; p.color = 0xAAFFFFFF.toInt(); c.drawPath(path, p); p.style = Paint.Style.FILL
            }
            "space" -> { val rnd = java.util.Random(21); for (i in 0 until 120) { val tw = if (still) 0.8f else 0.5f + 0.5f * sin(t * 1.4f + i); p.color = Glass.a(Color.WHITE, (170 * tw).toInt()); c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.8f, (0.6f + rnd.nextFloat() * 1.3f) * U, p) } }
            "neon" -> {   // 빗속 보케
                if (m.sit != Sit.DRIVE) { val rnd = java.util.Random(4); for (i in 0 until 16) { val col = intArrayOf(0xFFFF3EA5.toInt(), 0xFF7A5CFF.toInt(), 0xFF00E0FF.toInt())[i % 3]
                    val x = rnd.nextFloat() * w; val y = rnd.nextFloat() * h * 0.6f + sin(t * 0.3f + i) * 8 * U; val rr = (30 + rnd.nextFloat() * 60) * U
                    p.shader = RadialGradient(x, y, rr, Glass.a(col, 60), Glass.a(col, 0), Shader.TileMode.CLAMP); c.drawCircle(x, y, rr, p); p.shader = null } }
                p.shader = LinearGradient(0f, h * 0.72f, 0f, h, 0x00FF3EA5, 0x33FF3EA5, Shader.TileMode.CLAMP); c.drawRect(0f, h * 0.72f, w, h, p); p.shader = null
            }
            "arcade" -> {   // 원근 격자 바닥
                val hz = h * 0.7f; val cx = w * 0.5f
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.4f * U; p.color = 0x99FF4FD8.toInt()
                val sh = if (still) 0f else (t * 0.3f) % 1f
                for (i in 0 until 10) { val kk = (i + sh) / 10f; val y = hz + (h - hz) * kk * kk; c.drawLine(0f, y, w, y, p) }
                for (i in -12..12) c.drawLine(cx + i * w * 0.02f, hz, cx + i * w * 0.12f, h, p)
                p.style = Paint.Style.FILL
            }
            "hanok" -> { p.color = Color.WHITE; paper(c, RectF(0f, 0f, w, h))   // 한지 결 + 처마선
                p.color = 0xFF2E2420.toInt(); path.reset(); path.moveTo(0f, 0f); path.lineTo(w, 0f); path.lineTo(w, 26 * U); path.cubicTo(w * 0.7f, 46 * U, w * 0.3f, 46 * U, 0f, 26 * U); path.close(); c.drawPath(path, p)
                p.color = 0xFFB23A2E.toInt(); c.drawRect(0f, 26 * U, w, 30 * U, p); p.color = 0xFF3C8D7A.toInt(); c.drawRect(0f, 30 * U, w, 33 * U, p) }
        }
    }

    /** 캐릭터 둘레 효과(소품 아래) — 계기 고리 · 모닥불 빛 · 물에 비친 그림자 */
    private fun aroundCharacter(c: Canvas, cx: Float, cy: Float, s: Float, t: Float, alive: Boolean, bm: Bitmap?) {
        when (k.id) {
            "space" -> { p.style = Paint.Style.STROKE
                for (i in 0 until 2) { val rr = s * (0.62f + i * 0.14f); p.strokeWidth = 1.2f * U; p.color = Glass.a(0xFF7DF9FF.toInt(), 90 - i * 30); c.drawCircle(cx, cy, rr, p)
                    val rot = (if (alive) t * (12f - i * 20f) else 0f)
                    p.strokeWidth = 3f * U; p.color = Glass.a(0xFF7DF9FF.toInt(), 200); c.drawArc(RectF(cx - rr, cy - rr, cx + rr, cy + rr), rot + i * 120f, 40f, false, p)
                    for (j in 0 until 24) { val a = Math.toRadians((j * 15).toDouble()); val r1 = rr - (if (j % 6 == 0) 10 else 5) * U
                        p.strokeWidth = 1f * U; p.color = Glass.a(0xFF7DF9FF.toInt(), 70); c.drawLine(cx + (rr * kotlin.math.cos(a)).toFloat(), cy + (rr * kotlin.math.sin(a)).toFloat(), cx + (r1 * kotlin.math.cos(a)).toFloat(), cy + (r1 * kotlin.math.sin(a)).toFloat(), p) } }
                p.style = Paint.Style.FILL }
            "camp" -> { val fl = if (alive) 0.8f + 0.2f * sin(t * 9f) * sin(t * 5.3f) else 0.9f   // 모닥불 빛(아래에서)
                p.shader = RadialGradient(cx, cy + s * 0.7f, s * 0.9f, Glass.a(0xFFFFA24A.toInt(), (120 * fl).toInt()), 0x00FFA24A, Shader.TileMode.CLAMP); c.drawCircle(cx, cy + s * 0.7f, s * 0.9f, p); p.shader = null
            }
            "neon" -> if (bm != null) {   // 젖은 바닥에 비친 모습
                c.save(); c.translate(0f, (cy + s * 0.5f) * 2); c.scale(1f, -1f)
                p.alpha = 60; c.drawBitmap(bm, null, RectF(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2), p); p.alpha = 255; c.restore()
                p.shader = LinearGradient(0f, cy + s * 0.5f, 0f, cy + s * 1.3f, 0x00120D24, 0xFF120D24.toInt(), Shader.TileMode.CLAMP); p.color = Color.WHITE; c.drawRect(cx - s, cy + s * 0.5f, cx + s, cy + s * 1.3f, p); p.shader = null }
            else -> {}
        }
    }

    /** 캐릭터 소품(코드로 그린 작은 것) — 머리 위·목 */
    private fun accessory(c: Canvas, cx: Float, cy: Float, s: Float, t: Float) {
        val top = cy - s * 0.37f
        when (k.id) {
            "cafe" -> { p.color = 0xFF4A3426.toInt(); c.save(); c.rotate(-14f, cx, top); c.drawOval(RectF(cx - s * 0.26f, top - s * 0.1f, cx + s * 0.26f, top + s * 0.06f), p); p.color = 0xFF3A281D.toInt(); c.drawCircle(cx, top - s * 0.08f, s * 0.025f, p); c.restore() }   // 베레모
            "arcade" -> { p.color = 0xFFFF4FD8.toInt(); c.drawRect(cx - s * 0.36f, top + s * 0.03f, cx + s * 0.36f, top + s * 0.08f, p); p.color = 0xFFFFE14D.toInt(); val px = 6 * U   // 머리띠 + 픽셀 별
                for ((dx, dy) in listOf(0 to -2, -1 to -1, 0 to -1, 1 to -1, -2 to 0, -1 to 0, 0 to 0, 1 to 0, 2 to 0, -1 to 1, 1 to 1)) c.drawRect(cx + s * 0.3f + dx * px, top - s * 0.04f + dy * px, cx + s * 0.3f + dx * px + px, top - s * 0.04f + dy * px + px, p) }
            "hanok" -> { p.color = Glass.a(0xFF1C1612.toInt(), 225); c.drawOval(RectF(cx - s * 0.46f, top - s * 0.02f, cx + s * 0.46f, top + s * 0.07f), p)   // 갓
                c.drawRoundRect(RectF(cx - s * 0.15f, top - s * 0.19f, cx + s * 0.15f, top + s * 0.03f), s * 0.05f, s * 0.05f, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 1.4f * U; p.color = 0x99000000.toInt(); c.drawLine(cx - s * 0.32f, top + s * 0.05f, cx - s * 0.36f, cy + s * 0.2f, p); c.drawLine(cx + s * 0.32f, top + s * 0.05f, cx + s * 0.36f, cy + s * 0.2f, p); p.style = Paint.Style.FILL }
            "neon" -> { p.color = 0xCC00E0FF.toInt(); p.setShadowLayer(10 * U, 0f, 0f, 0xFF00E0FF.toInt()); c.drawRoundRect(RectF(cx - s * 0.3f, cy - s * 0.02f, cx + s * 0.3f, cy + s * 0.025f), 3 * U, 3 * U, p); p.clearShadowLayer() }   // 바이저 줄
            "camp" -> { p.color = 0xFFE07A3F.toInt(); c.drawArc(RectF(cx - s * 0.3f, top - s * 0.16f, cx + s * 0.3f, top + s * 0.2f), 180f, 180f, true, p)   // 털모자
                p.color = 0xFFC8622D.toInt(); c.drawRoundRect(RectF(cx - s * 0.31f, top - s * 0.0f, cx + s * 0.31f, top + s * 0.06f), 6 * U, 6 * U, p)
                p.color = 0xFFF5E6D0.toInt(); c.drawCircle(cx, top - s * 0.17f, s * 0.05f, p) }
            "ocean" -> { val fx = cx + s * 0.33f; val fy = top + s * 0.06f; p.color = 0xFFFF6B8A.toInt()   // 히비스커스
                for (i in 0 until 5) { val a = Math.toRadians((i * 72 - 90).toDouble()); c.drawCircle(fx + (s * 0.05f * kotlin.math.cos(a)).toFloat(), fy + (s * 0.05f * kotlin.math.sin(a)).toFloat(), s * 0.045f, p) }
                p.color = 0xFFFFD25E.toInt(); c.drawCircle(fx, fy, s * 0.022f, p) }
            "space" -> { p.color = 0xFFB8C4D6.toInt(); p.strokeWidth = 2 * U; c.drawLine(cx + s * 0.18f, top + s * 0.02f, cx + s * 0.26f, top - s * 0.14f, p)   // 안테나 + 깜빡이는 불
                p.color = if ((SystemClock.elapsedRealtime() / 700) % 2 == 0L) 0xFFFF6B6B.toInt() else 0xFF7DF9FF.toInt(); c.drawCircle(cx + s * 0.26f, top - s * 0.14f, s * 0.03f, p) }
            "glass" -> { p.shader = LinearGradient(cx - s * 0.32f, 0f, cx + s * 0.32f, 0f, 0xFF6EF0D2.toInt(), 0xFF8C7CFF.toInt(), Shader.TileMode.CLAMP); p.color = Color.WHITE   // 오로라 목도리
                c.drawRoundRect(RectF(cx - s * 0.3f, cy + s * 0.34f, cx + s * 0.3f, cy + s * 0.43f), s * 0.04f, s * 0.04f, p); c.drawRoundRect(RectF(cx + s * 0.12f, cy + s * 0.38f, cx + s * 0.2f, cy + s * 0.56f), s * 0.03f, s * 0.03f, p); p.shader = null }
            else -> {}
        }
    }

    /** 테마 인트로(시동 1.8초) · 마무리(시동 끔 1.8초) */
    private fun themeIntro(c: Canvas, w: Float, h: Float, x: Float, outro: Boolean) {
        val e = if (outro) x else 1f - x   // 덮개 양(1 = 완전히 덮음)
        val fg = if (cardDark() || k.id == "pleos" && darkUi) Color.WHITE else FG
        when (k.id) {
            "hanok" -> { val half = w / 2 * e.coerceIn(0f, 1f)   // 창호지 문
                for ((l, r) in listOf(0f to half, w - half to w)) { val rr = RectF(l, 0f, r, h); p.color = 0xFFF3EBDC.toInt(); c.drawRect(rr, p); paper(c, rr)
                    p.style = Paint.Style.STROKE; p.color = 0xFF7A5639.toInt(); p.strokeWidth = 3 * U; val g = 64 * U
                    var xx = rr.left; while (xx <= rr.right) { c.drawLine(xx, 0f, xx, h, p); xx += g }; var yy = 0f; while (yy <= h) { c.drawLine(rr.left, yy, rr.right, yy, p); yy += g }; p.style = Paint.Style.FILL }
                if (e > 0.6f) text(c, if (outro) "편히 쉬세요" else "어서 오세요", w / 2, h / 2, 56 * U, 0xFF3A2A1E.toInt(), themeHead(), Paint.Align.CENTER) }
            "arcade" -> { p.color = Glass.a(0xFF0E0019.toInt(), (230 * e.coerceIn(0f, 1f)).toInt()); c.drawRect(0f, 0f, w, h, p)
                if ((SystemClock.elapsedRealtime() / 300) % 2 == 0L) text(c, if (outro) "GAME OVER" else if (x < 0.5f) "INSERT COIN" else "PLAYER 1  START", w / 2, h / 2, 54 * U, 0xFFFFE14D.toInt(), tf("Galmuri11.ttf"), Paint.Align.CENTER) }
            "neon" -> { val on = if (outro) x < 0.4f && (SystemClock.elapsedRealtime() / 90) % 3 != 0L else x > 0.5f || (SystemClock.elapsedRealtime() / 90) % 3 == 0L
                p.color = Glass.a(0xFF07061A.toInt(), (220 * e).toInt()); c.drawRect(0f, 0f, w, h, p)
                if (on) { p.setShadowLayer(18 * U, 0f, 0f, 0xFFFF5CB8.toInt()); text(c, "현비서", w / 2, h / 2, 80 * U, 0xFFFFD6F0.toInt(), tf("BlackHanSans-Regular.ttf"), Paint.Align.CENTER); p.clearShadowLayer() } }
            "ocean" -> { val y = h * (1f - e); p.color = 0xFF2BA7C4.toInt(); path.reset(); path.moveTo(0f, h); path.lineTo(0f, y); var xx = 0f; while (xx <= w) { path.lineTo(xx, y + sin(xx / w * 10f) * 18 * U); xx += w / 64 }; path.lineTo(w, h); path.close(); c.drawPath(path, p)
                if (e > 0.5f) text(c, if (outro) "다음 파도에서 만나요" else "오늘도 좋은 파도", w / 2, h / 2, 52 * U, Color.WHITE, tf("Jua-Regular.ttf"), Paint.Align.CENTER) }
            "space" -> { p.color = Glass.a(0xFF02030A.toInt(), (230 * e).toInt()); c.drawRect(0f, 0f, w, h, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 2 * U; p.color = 0xFF7DF9FF.toInt(); val rr = (if (outro) 1f - x else x) * w * 0.4f; c.drawCircle(w * 0.27f, h * 0.55f, rr, p); c.drawCircle(w * 0.27f, h * 0.55f, rr * 0.7f, p); p.style = Paint.Style.FILL
                if (e > 0.3f) text(c, if (outro) "SYSTEM OFF" else "SYSTEM ONLINE", w / 2, h / 2, 44 * U, 0xFF7DF9FF.toInt(), tf("DoHyeon-Regular.ttf"), Paint.Align.CENTER) }
            "cafe" -> { p.color = Glass.a(0xFF1E140D.toInt(), (200 * e).toInt()); c.drawRect(0f, 0f, w, h, p)
                val sw = (if (outro) 1f - x else x).let { sin(it * 9f) * 12f * (1f - it) }   // 흔들리는 팻말
                c.save(); c.rotate(sw, w / 2, h * 0.3f); p.color = 0xFF2A3731.toInt(); val r = RectF(w / 2 - 170 * U, h * 0.38f, w / 2 + 170 * U, h * 0.38f + 120 * U); c.drawRoundRect(r, 10 * U, 10 * U, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 8 * U; p.color = 0xFF8A5B38.toInt(); c.drawRoundRect(r, 10 * U, 10 * U, p); c.drawLine(w / 2 - 80 * U, r.top, w / 2, h * 0.3f, p); c.drawLine(w / 2 + 80 * U, r.top, w / 2, h * 0.3f, p); p.style = Paint.Style.FILL
                text(c, if (outro) "CLOSED" else "OPEN", w / 2, r.centerY() + 22 * U, 64 * U, 0xFFEFEDE4.toInt(), tf("NanumPenScript-Regular.ttf"), Paint.Align.CENTER); c.restore() }
            "camp" -> { p.color = Glass.a(0xFF0B120E.toInt(), (230 * e).toInt()); c.drawRect(0f, 0f, w, h, p)
                p.shader = RadialGradient(w / 2, h, h * (if (outro) 1f - x else x) + 1f, 0x99FF8A3A.toInt(), 0x00FF8A3A, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, h, p); p.shader = null
                if (e > 0.4f) text(c, if (outro) "불 끄고 쉬어요" else "불 피웠어요", w / 2, h / 2, 56 * U, 0xFFFFE6BF.toInt(), tf("Jua-Regular.ttf"), Paint.Align.CENTER) }
            "glass" -> { p.color = Glass.a(0xFF0A0F24.toInt(), (200 * e).toInt()); c.drawRect(0f, 0f, w, h, p)
                if (e > 0.3f) text(c, "현비서", w / 2, h / 2, 72 * U, Glass.a(Color.WHITE, (255 * e).toInt()), font("Regular"), Paint.Align.CENTER) }
            else -> { if (e > 0.05f) { c.saveLayerAlpha(0f, 0f, w, h, (255 * e).toInt()); text(c, "현비서", w * 0.72f, h * 0.46f, 72 * U, fg, bold, Paint.Align.CENTER); text(c, k.name, w * 0.72f, h * 0.46f + 48 * U, 26 * U, MUTED, medium, Paint.Align.CENTER); c.restore() } }
        }
    }

    /** 아케이드 마무리 — 화면 전체 주사선 + 가장자리 어둡게(CRT) */
    private fun crt(c: Canvas, w: Float, h: Float) {
        if (k.id != "arcade") return
        p.color = 0x22000000; var y = 0f; while (y < h) { c.drawRect(0f, y, w, y + 2 * U, p); y += 5 * U }
        p.shader = RadialGradient(w / 2, h / 2, w * 0.7f, 0x00000000, 0x88000000.toInt(), Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, h, p); p.shader = null
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

    private fun button(c: Canvas, s: String, r0: RectF, primary: Boolean, action: String, fsMax: Float = 26 * U) {
        val pressed = pressRect?.let { it.left == r0.left && it.top == r0.top } == true && SystemClock.elapsedRealtime() - pressAt < 180
        val r = if (pressed) RectF(r0).apply { inset(r0.width() * 0.015f, r0.height() * 0.015f) } else r0   // 눌림 0.97
        p.shader = null; p.style = Paint.Style.FILL
        val fs = min(fsMax, r.height() * 0.42f)
        var tc = FG; var face = medium; var size = fs
        when (if (skinned) "skin" else k.id) {
            "arcade" -> { if (primary) { p.color = 0xFFFF4FD8.toInt(); c.drawRect(r.left + 5 * U, r.top + 5 * U, r.right + 5 * U, r.bottom + 5 * U, p); p.color = 0xFF00F0FF.toInt(); c.drawRect(r, p); tc = 0xFF0D0918.toInt() }
                else { p.style = Paint.Style.STROKE; p.strokeWidth = 2 * U; p.color = 0xFF00F0FF.toInt(); c.drawRect(r, p); p.style = Paint.Style.FILL; tc = 0xFF00F0FF.toInt() }; face = tf("Galmuri11.ttf"); size = fs * 0.85f }
            "cafe" -> { p.style = Paint.Style.STROKE; p.strokeWidth = 2.4f * U; p.color = 0xFFEFEDE4.toInt(); c.drawRoundRect(r, 8 * U, 8 * U, p); p.style = Paint.Style.FILL
                if (primary) { p.color = 0x22FFFFFF; c.drawRoundRect(r, 8 * U, 8 * U, p) }; tc = 0xFFEFEDE4.toInt(); face = tf("NanumPenScript-Regular.ttf"); size = fs * 1.3f }
            "hanok" -> { p.color = if (primary) 0xFFB23A2E.toInt() else 0xFFE8DCC6.toInt(); c.drawRoundRect(r, 4 * U, 4 * U, p); tc = if (primary) 0xFFFFF4E2.toInt() else 0xFF3A2A1E.toInt(); face = tf("GowunBatang-Regular.ttf") }
            "neon" -> { p.style = Paint.Style.STROKE; p.strokeWidth = 2 * U; p.color = 0xFFFF5CB8.toInt(); if (primary) p.setShadowLayer(10 * U, 0f, 0f, 0xFFFF5CB8.toInt()); c.drawRoundRect(r, r.height() / 2, r.height() / 2, p); p.clearShadowLayer(); p.style = Paint.Style.FILL; tc = 0xFFFFD6F0.toInt() }
            "camp" -> { p.color = if (primary) 0xFF8B5A34.toInt() else 0x33FFE6BF; c.drawRoundRect(r, 10 * U, 10 * U, p); tc = 0xFFFFF0DA.toInt(); face = tf("Jua-Regular.ttf") }
            "ocean" -> { p.color = if (primary) 0xFFFF7A59.toInt() else 0xFFE6F6F9.toInt(); c.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
                if (primary) { p.color = 0x55FFFFFF; c.drawRect(r.left + r.width() * 0.3f, r.top, r.left + r.width() * 0.36f, r.bottom, p); c.drawRect(r.left + r.width() * 0.64f, r.top, r.left + r.width() * 0.7f, r.bottom, p) }   // 부표 줄무늬
                tc = if (primary) Color.WHITE else 0xFF0E5C86.toInt(); face = tf("Jua-Regular.ttf") }
            "space" -> { p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f * U; p.color = 0xFF7DF9FF.toInt(); c.drawRoundRect(r, 4 * U, 4 * U, p); p.style = Paint.Style.FILL; if (primary) { p.color = 0x227DF9FF; c.drawRoundRect(r, 4 * U, 4 * U, p) }; tc = 0xFF7DF9FF.toInt(); face = tf("DoHyeon-Regular.ttf"); size = fs * 1.05f }
            "glass" -> { p.color = if (primary) 0x55FFFFFF else 0x22FFFFFF; c.drawRoundRect(r, r.height() / 2, r.height() / 2, p); p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f * U; p.color = 0x88FFFFFF.toInt(); c.drawRoundRect(r, r.height() / 2, r.height() / 2, p); p.style = Paint.Style.FILL; tc = Color.WHITE }
            "skin" -> { val rad = min(12 * U * sk.style.radius + 2 * U, r.height() / 2); val bw = sk.style.border * S * 100
                if (sk.style.shadowX > 0f) { p.color = sk.style.shadowColor; c.drawRoundRect(RectF(r).apply { offset(sk.style.shadowX * S * 60, sk.style.shadowY * S * 60) }, rad, rad, p) }
                p.color = if (primary) ACC else SURF2; c.drawRoundRect(r, rad, rad, p); tc = if (primary) sk.colors.onAccent else FG
                if (bw > 0f) { p.style = Paint.Style.STROKE; p.strokeWidth = bw; p.color = sk.style.borderColor; c.drawRoundRect(r, rad, rad, p); p.style = Paint.Style.FILL } }
            else -> { val rad = min(12 * U, r.height() / 2)
                if (primary) { p.color = if (darkUi) 0xFFEDEDED.toInt() else ACC; c.drawRoundRect(r, rad, rad, p); tc = if (darkUi) 0xFF141415.toInt() else Color.WHITE }
                else { p.color = SURF2; c.drawRoundRect(r, rad, rad, p) } }
        }
        text(c, s, r.centerX(), r.centerY() + size * 0.36f, size, tc, face, Paint.Align.CENTER, r.width() * 0.92f)
        hits += r0 to action
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
            "palette" -> { c.drawCircle(cx, cy, h * 0.82f, q); q.style = Paint.Style.FILL; c.drawCircle(cx - h * 0.35f, cy - h * 0.25f, h * 0.15f, q); c.drawCircle(cx + h * 0.15f, cy - h * 0.42f, h * 0.15f, q); c.drawCircle(cx + h * 0.42f, cy + h * 0.05f, h * 0.15f, q) }
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
