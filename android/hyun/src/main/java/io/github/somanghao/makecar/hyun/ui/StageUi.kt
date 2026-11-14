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
import io.github.somanghao.makecar.hyun.core.Fmt
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
    private var stripKey = ""; private var stripAt = 0L   // 위 교통 띠 — 회전 값이 바뀐 시각(같은 값 20초면 접는다, 사용자 11-12·11-14)

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
        streakClip = when {
            w < h * 1.15f || lastNavMain -> null
            mirror -> RectF(w * 0.64f + 8 * U, 0f, w, h)
            else -> RectF(0f, 0f, w * 0.36f - 8 * U, h)
        }
        val a = art
        if (skinned) skinBg(c, w, h) else if (a != null && artKey.startsWith("room_${k.room}")) drawArt(c, w, h, a) else if (m.overlay == "deco") ambient(c, RectF(0f, 0f, w, h), k, darkUi) else { scene(c, w, h, t, m.sit == Sit.DRIVE); if (m.overlay != "deco") motif(c, w, h, t, m.sit == Sit.DRIVE) }
        when (m.overlay) {
            // 온보딩은 배경을 어둡게 깔므로 글자도 어두운 화면용(밝은 글자)으로 — 20시 전엔 검은 바탕에 검은 제목이 나왔다(11-10 19:57 캡처).
            // 밝은 스킨은 덮개를 옅게 해 스킨 글자색(어두움)이 읽히게
            "onboard" -> { val light = skinned && lum(sk.colors.bg) >= 0.45; scrim(c, w, h, if (light) 0.18f else 0.55f)
                val d0 = darkUi; if (!skinned) darkUi = true; onboard(c, w, h, t); darkUi = d0; return true }
            "deco" -> { shop(c, w, h, t); return true }
        }
        val driving = m.sit == Sit.DRIVE
        val now = SystemClock.elapsedRealtime()
        // 목록·소개 카드는 주행·신호대기엔 그리지 않는다(main HyunView와 같은 안전 규칙 — 11-11 영상: 국밥집 5곳이 60km/h에서 보였다). 말은 그대로, 카드는 정차하면
        val shownCard = v.card?.takeIf { m.sit != Sit.DRIVE }   // 신호대기(정차)엔 보인다 = 말로 묻는 정보 안내(사용자 11-14)
        // 테마 인트로(시동 직후 1.6초) · 마무리(시동 끔)
        val intro = (m.sit == Sit.IGNITION && now - m.sitAt < 1800 && !m.viewForced)
        val outroX = if (m.sit == Sit.OFF && now - m.sitAt < 1800) (now - m.sitAt) / 1800f else -1f
        // 내비가 주화면(주행 중) — 맨 위 말풍선 · 큰 지도(칼리 11-12, 소개자료 5장). 말로 받은 카드는 주행 중엔 그리지 않는다(shownCard)
        val navMain = !intro && shownCard == null && m.overlay !in setOf("brief", "todo", "car", "log", "rest") && (m.view == "nav" || (m.view == null && !m.infoMain && (driving || (m.sit == Sit.READY && m.navActive && ideaNow() == null))))   // D 에서 경로가 있으면 저절로 내비 주화면 · '정보 크게'면 위 작은 내비 띠 + 정보 칸
        if (navMain != lastNavMain) { lastNavMain = navMain; navMainAt = now }
        val nx = ((now - navMainAt) / 480f).coerceIn(0f, 1f); val ne = 1f - (1f - nx) * (1f - nx) * (1f - nx)
        val nk = if (navMain) ne else 1f - ne            // 0 = 보통 화면 · 1 = 내비 주화면
        // 반반(내비와 나란히 — 폭이 좁다) — 캐릭터는 왼쪽 위 배지, 카드는 칸 전체(11-10: 반반에선 상황 화면이 아예 안 나왔다)
        val narrow = w < h * 1.15f
        // 정보 칸 ≈ 62%(내쉬 11-10 "정보영역이 좁아 — 캐릭터 영역 줄여도 돼" · 그전 52%는 칼리 11-09 "조금 더 줄여")
        zoneL = if (narrow) 40 * U else if (mirror) 48 * U else w * 0.36f; zoneR = if (narrow) w - 40 * U else if (mirror) w * 0.64f else w - 48 * U
        if (m.sit == Sit.OFF) scrim(c, w, h, ((now - m.sitAt) / 1500f).coerceIn(0f, 0.35f))
        // 캐릭터 — 보통: 화면 높이 33% · 내비 주화면·반반: 14% 배지(왼쪽 위)
        val showLine = !glance() && !intro && m.view == null && !narrow
        val nc = if (narrow) 1f else nk                   // 캐릭터 배지 정도(반반은 늘 배지)
        val bigH = h * 0.28f; val bigCx = if (mirror) w * 0.82f else w * 0.18f; val bigFeet = h * 0.7f   // 캐릭터 33% → 28%(정보 칸을 넓히려고)
        val smH = h * 0.14f; val smCx = 40 * U + smH / 2; val smFeet = 16 * U + smH
        val chH = bigH + (smH - bigH) * nc; val cx = bigCx + (smCx - bigCx) * nc; val feet = bigFeet + (smFeet - bigFeet) * nc
        val react = m.reactFace.takeIf { now - m.reactAt < 1200 }
        val face0 = when { intro -> "waving"; m.sit == Sit.OFF && now - m.sitAt > 2500 -> "sleep"; react != null && v.mode == HyunView.Mode.READY -> react; driving -> "driving"
            m.view != null && v.mode == HyunView.Mode.READY -> VIEW_FACE[m.view!!] ?: v.botFace(); else -> v.botFace() }
        val celebrate = m.unlockAt > 0 && now - m.unlockAt < 1500
        val face = if (celebrate) face0 else calm(face0)
        if (v.mode == HyunView.Mode.SPEAKING) {   // 말하는 동안 현비서 둘레가 밝아진다(움직임 없는 빛)
            val gy = feet - chH * 0.5f; val gr = chH * 0.95f
            p.style = Paint.Style.FILL; p.shader = android.graphics.RadialGradient(cx, gy, gr, intArrayOf(Glass.a(if (darkUi) Color.WHITE else ACC, 170), Glass.a(ACC, 70), Glass.a(ACC, 0)), floatArrayOf(0.35f, 0.7f, 1f), android.graphics.Shader.TileMode.CLAMP)
            c.drawCircle(cx, gy, gr, p); p.shader = null
        }
        character(c, cx, feet, chH, t, face, alive = !driving && nc < 0.5f, small = nc > 0.5f)
        if (!intro) {
            if (nk > 0.5f) { if (!glance()) speech(c, cx + chH * 0.6f, 16 * U + chH * 0.42f, w * 0.5f, navLine()) }   // 운전 중엔 맨 위 말풍선(navGlance · chanStrip)
            else if (narrow) speech(c, cx + chH * 0.6f, 100 * U, w - 40 * U - (cx + chH * 0.6f))   // 반반 — 오른쪽 위 별·설정 줄(≤72) 아래로
            else if (mirror) speech(c, zoneR + 64 * U, feet - chH - 56 * U, w - zoneR - 128 * U, tailX = cx)
            else speech(c, 64 * U, feet - chH - 56 * U, zoneL - 128 * U, tailX = cx)
            if (skinned && !glance() && nc < 0.5f) sticker(c, cx, feet, chH, face)
        }
        if (showLine && nk < 0.01f) { if (mirror) timeline(c, zoneR + 64 * U, w - 160 * U, h - 100 * U) else timeline(c, 64 * U, zoneL - 64 * U, h - 100 * U) }
        if (!glance()) topRight(c, w)   // 운전 중엔 누르는 것 없음(별 · 테마 · 설정 숨김)
        if (intro) {   // 테마 인트로
            if (!skinned) themeIntro(c, w, h, ((now - m.sitAt) / 1800f).coerceIn(0f, 1f), false)
        } else {
            val floatPager = !glance() && m.overlay == null && StageModel.pages(m.sit).size > 1   // 정차 중 내비 화면 — 떠 있는 넘기기 자리
            if (nk > 0.01f) {   // 내비 주화면 — 아래에서 올라오며 나타남
                if (nk < 1f) c.saveLayerAlpha(0f, 0f, w, h, (nk * 255).toInt()) else c.save()
                c.translate(0f, (1f - nk) * 24 * U)
                // 넘기기 알약 자리만큼 비운다(11-10: 알약이 지도 위에 떠 경로선이 "3/28"을 가렸다)
                navMainScreen(c, RectF(40 * U, 16 * U + smH + 28 * U, w - 40 * U, h - (if (floatPager) 100 else 32) * U), w, h)
                c.restore()
                if (nk > 0.5f && !glance()) viewChips(c, cx + chH * 0.6f, 16 * U + chH * 0.42f + 44 * U)
            }
            if (nk < 0.99f) {
                if (nk > 0f) c.saveLayerAlpha(0f, 0f, w, h, ((1f - nk) * 255).toInt()) else c.save()
                if (narrow) viewChips(c, cx + chH * 0.6f, 16 * U + chH + 2 * U) else header(c)
                val card = shownCard
                val sheetOn = m.overlay in setOf("brief", "todo", "car", "log", "rest")
                val pagerOn = !glance() && m.overlay == null && card == null && StageModel.pages(m.sit).size > 1
                val bottom = h - 48 * U - 64 * U - 20 * U - (if (pagerOn) 64 * U else 0f)
                var top = if (narrow) 16 * U + smH + 40 * U else 96 * U
                // 위 교통 띠 — 회전(종류·거리 50m 단위·길 이름)이 바뀌면 다시 나오고, 같은 값 20초면 접어 대화·정보가 화면을 쓴다(사용자 11-12 규칙 · 11-14 "중간중간 바뀌어야")
                val sk = "${m.navTurnType}|${(m.navTurnM / 50).toInt()}|${m.navTurn}"
                if (sk != stripKey) { stripKey = sk; stripAt = now }
                val stripOn = !m.infoMain || !glance() || now - stripAt < 20_000L
                if (m.navActive && !navMain && m.prompt == null && stripOn) { navStrip(c, RectF(zoneL, top, zoneR, top + 84 * U)); top += 84 * U + 16 * U }   // 퀴즈 중엔 운전 안내 줄 숨김(사용자 11-14)
                val full = RectF(zoneL, top, zoneR, bottom)
                // 넘길 때 — 내용 칸만 옆에서 밀려 들어온다(캐릭터·내비 띠·독은 그대로)
                val sx = ((now - m.pageAt) / 340f).coerceIn(0f, 1f); val se = 1f - (1f - sx) * (1f - sx) * (1f - sx)
                if (sx < 1f && m.pageDir != 0) { c.saveLayerAlpha(zoneL - 200 * U, 0f, zoneR + 200 * U, h, (90 + 165 * se).toInt()); c.translate(m.pageDir * (1f - se) * 160 * U, 0f) } else c.save()
                when {
                    sheetOn -> sheet(c, full, m.overlay!!)
                    card != null -> { glassCard(c, full, 24 * U); val lt = v.lightTheme; v.lightTheme = !darkUi; v.listCard(c, card, full.left + 24 * U, full.top + 24 * U, full.right - 24 * U, full.bottom - 24 * U, h); v.lightTheme = lt }
                    m.view != null && m.view != "nav" -> {   // 모듈 상세 — 왼쪽 위 끝에 닫기
                        button(c, "닫기", RectF(full.left, full.top, full.left + 110 * U, full.top + 52 * U), false, "view:close")
                        category(c, RectF(full.left, full.top + 68 * U, full.right, full.bottom), m.view!!)
                    }
                    m.prompt != null && m.sit == Sit.STOP -> quizScreen(c, full)   // 신호대기 넌센스 퀴즈 — 서 있을 때만 카드(달리면 말로만, 사용자 11-14)
                    m.sit == Sit.DRIVE || m.sit == Sit.STOP || (m.sit == Sit.READY && m.navActive) -> driveGlance(c, full)   // 주행·신호대기 — 한눈 정보(11-12) · D 출발 전이라도 이미 안내 중이면 목적지 카드·"안내해 줘" 말 안내 없이 바로(사용자 11-14)
                    !driving -> if (m.prompt != null) quizScreen(c, full) else stateScreen(c, full)
                }
                c.restore()
                if (pagerOn) pagerBar(c, (zoneL + zoneR) / 2, h - 48 * U - 64 * U - 20 * U - 30 * U)
                if (!glance()) dock(c, w, h)   // 운전 중엔 독도 숨김(한눈 규칙)
                c.restore()
            }
            if (glance()) micHint(c, w, h) else micButton(c, w, h)
            if (nk > 0.5f && floatPager && m.overlay == null) pagerBar(c, w / 2, h - 52 * U)   // 정차 중 내비 화면 — 떠 있는 넘기기
            if (glance() && !lastNavMain) StageModel.pages(m.sit).let { ps -> if (ps.size > 1) text(c, "${pageNow(ps) + 1} / ${ps.size}", w - 48 * U - 340 * U, h - 52 * U, 20 * U, FAINT, medium, Paint.Align.RIGHT) }   // 운전 중 — 누를 수 없는 작은 표시(넘기기는 말로)
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
        if (a != "noop") { touchedAt = SystemClock.elapsedRealtime(); act(a) }; return true
    }

    private fun scrim(c: Canvas, w: Float, h: Float, a: Float) { p.shader = null; p.color = Glass.a(Color.BLACK, (a * 255).toInt()); c.drawRect(0f, 0f, w, h, p) }

    /** 카드 — 그림 위면 뒤를 흐리게 비친 흰 72%(어두운 컨셉은 면 82%) · 라이트 그림자 0 8 24 · 1px 선 */
    private fun glassCard(c: Canvas, r: RectF, rad: Float, tint: Int? = null) {
        p.shader = null; p.style = Paint.Style.FILL
        if (skinned) { inkCard(c, r, rad, tint); return }
        if (r.height() > 70 * U && themeCard(c, r, rad, tint)) return
        val sp = Path().apply { addRoundRect(r, rad, rad, Path.Direction.CW) }
        val blur = artBlur
        if (!darkUi && blur != null) { p.color = SURF; p.setShadowLayer(24 * U, 0f, 8 * U, 0x14111827); c.drawPath(sp, p); p.clearShadowLayer() }   // 불투명 그림자 판은 그림 배경일 때만(유리 카드는 비침)
        c.save(); c.clipPath(sp)
        if (blur != null && art != null) {
            c.drawBitmap(blur, null, artRect, bp)
            p.color = if (darkUi) Glass.a(SURF, 210) else Glass.a(Color.WHITE, 184); c.drawRect(r, p)
        } else {   // 바탕과 살짝 다른 면 — 위가 조금 밝은 은은한 그라데이션(칼리 11-09 "배경이랑 동일")
            // 기본 유리 카드 — 그라데이션·광택 없이 한 가지 반투명 면(칼리 11-09 참고: 깔끔한 서리 유리)
            p.color = if (darkUi) Glass.a(Color.WHITE, 22) else Glass.a(Color.WHITE, 178); c.drawRect(r, p)
        }
        tint?.let { p.color = Glass.a(it, if (darkUi) 30 else 18); c.drawRect(r, p) }
        c.restore()
        p.style = Paint.Style.STROKE; p.strokeWidth = max(1f, U); p.color = if (blur != null && !darkUi) 0x99FFFFFF.toInt() else if (darkUi) Glass.a(Color.WHITE, 30) else Glass.a(Color.WHITE, 230); c.drawPath(sp, p); p.style = Paint.Style.FILL
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

    /** 캐릭터 옆 한 줄 — 물음이 있으면 그 물음(운전자가 답할 것), 없으면 첫 문장. "쌀쌀하네요. 23도로 올려 드릴까요?" → 물음 쪽(11-11) */
    private fun keySentence(s: String): String =
        Regex("(?<=[.!?])\\s+").split(s.trim()).lastOrNull { it.trim().endsWith("?") }?.trim() ?: firstSentence(s)
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
    /**
     * 기본 테마 바탕 — 사진 스튜디오 같은 입체 무대(칼리 11-09 "solid 라 밋밋 → 3D 감").
     * 위는 푸른 회색 벽, 아래 26%는 바닥(경계선 없이 명도로만 꺾임) · 캐릭터 뒤 은은한 역광 · 발밑 하늘색·분홍 빛 웅덩이 · 가장자리 비네트
     */
    private fun studio(c: Canvas, w: Float, h: Float, night: Boolean) {
        if (night) { cinema(c, w, h); return }
        val hz = h * 0.74f                                   // 벽과 바닥이 만나는 곳
        val wallTop = if (night) 0xFF0D1420.toInt() else 0xFFAEBDCC.toInt()
        val wallBot = if (night) 0xFF1C2A3E.toInt() else 0xFFD9E3EC.toInt()
        val floorNear = if (night) 0xFF131C2A.toInt() else 0xFFC6D1DC.toInt()
        p.color = Color.WHITE
        p.shader = LinearGradient(0f, 0f, 0f, hz, wallTop, wallBot, Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, hz, p)
        p.shader = LinearGradient(0f, hz, 0f, h, wallBot, floorNear, Shader.TileMode.CLAMP); c.drawRect(0f, hz, w, h, p)
        // 벽→바닥 꺾이는 곳의 부드러운 밝은 띠(스튜디오 곡면 느낌)
        p.shader = LinearGradient(0f, hz - h * 0.08f, 0f, hz + h * 0.06f, intArrayOf(Glass.a(Color.WHITE, 0), Glass.a(Color.WHITE, if (night) 14 else 40), Glass.a(Color.WHITE, 0)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, hz - h * 0.08f, w, hz + h * 0.06f, p)
        // 캐릭터 뒤 역광(하늘색)
        val ccx = if (mirror) w * 0.8f else w * 0.2f
        val back = if (night) 0xFF4C8DFF.toInt() else 0xFFFFFFFF.toInt()
        p.shader = RadialGradient(ccx, h * 0.48f, h * 0.62f, Glass.a(back, if (night) 70 else 120), Glass.a(back, 0), Shader.TileMode.CLAMP); c.drawCircle(ccx, h * 0.48f, h * 0.62f, p)
        // 발밑 빛 웅덩이 — 하늘색(왼쪽) · 분홍(오른쪽) 살짝
        val fy = h * 0.71f
        c.save(); c.scale(1f, 0.22f, ccx, fy)
        p.shader = RadialGradient(ccx - h * 0.1f, fy, h * 0.42f, Glass.a(0xFF7CC4FF.toInt(), if (night) 90 else 70), 0x007CC4FF, Shader.TileMode.CLAMP); c.drawCircle(ccx - h * 0.1f, fy, h * 0.42f, p)
        p.shader = RadialGradient(ccx + h * 0.14f, fy, h * 0.34f, Glass.a(0xFFFFA8C8.toInt(), if (night) 50 else 45), 0x00FFA8C8, Shader.TileMode.CLAMP); c.drawCircle(ccx + h * 0.14f, fy, h * 0.34f, p)
        c.restore()
        // 비네트 — 가장자리를 살짝 어둡게
        p.shader = RadialGradient(w * 0.5f, h * 0.45f, w * 0.75f, intArrayOf(0, 0, Glass.a(Color.BLACK, if (night) 120 else 46)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null
    }

    /**
     * 밤(어두운 화면) 바탕 — 차 화면 같은 시네마틱 풍경(칼리 11-09 참고 이미지: 짙은 남색 · 지평선 안개 · 먼 산 · 푸른 빛줄기).
     * 지평선 62% · 먼 산 두 겹(낮은 대비) · 바닥에서 캐릭터 발밑으로 흘러오는 빛줄기 · 위쪽 얇은 빛 곡선 · 비네트
     */
    private val cinemaPath = Path()
    /** 배경 빛줄기를 그릴 칸 — 캐릭터 쪽만. 카드가 칸 전체를 덮는 화면(내비 주화면 · 반반)은 null = 안 그림.
     *  카드가 반투명 유리라 화면을 가로지르는 빛줄기가 카드 글자 위로 비쳤다(사용자 11-10 "신경쓰여") */
    private var streakClip: RectF? = null
    private fun cinema(c: Canvas, w: Float, h: Float) {
        val hz = h * 0.62f
        val ccx = if (mirror) w * 0.8f else w * 0.2f
        p.color = Color.WHITE
        // 하늘 → 지평선
        p.shader = LinearGradient(0f, 0f, 0f, hz, intArrayOf(0xFF060A13.toInt(), 0xFF0E1726.toInt(), 0xFF22324B.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w, hz, p)
        // 땅
        p.shader = LinearGradient(0f, hz, 0f, h, 0xFF141E2E.toInt(), 0xFF070B13.toInt(), Shader.TileMode.CLAMP); c.drawRect(0f, hz, w, h, p)
        // 먼 산 두 겹 — 부드러운 능선(낮은 대비)
        for (layer in 0..1) {
            val base = hz + h * 0.004f; val amp = h * (if (layer == 0) 0.07f else 0.11f)
            cinemaPath.reset(); cinemaPath.moveTo(0f, base)
            var x = 0f
            while (x <= w) {
                val u = x / w
                val y = base - amp * (0.55f + 0.45f * sin(u * (if (layer == 0) 9.0f else 5.2f) + layer * 1.3f)) * (0.5f + 0.5f * sin(u * 2.3f + 0.6f + layer))
                cinemaPath.lineTo(x, y); x += w / 64
            }
            cinemaPath.lineTo(w, base); cinemaPath.close()
            p.shader = LinearGradient(0f, base - amp, 0f, base, if (layer == 0) 0xFF27364E.toInt() else 0xFF1A2639.toInt(), 0xFF152031.toInt(), Shader.TileMode.CLAMP)
            c.drawPath(cinemaPath, p)
        }
        // 지평선 안개(빛 띠)
        p.shader = LinearGradient(0f, hz - h * 0.1f, 0f, hz + h * 0.05f, intArrayOf(Glass.a(0xFF8FB4E8.toInt(), 0), Glass.a(0xFF8FB4E8.toInt(), 46), Glass.a(0xFF8FB4E8.toInt(), 0)), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, hz - h * 0.1f, w, hz + h * 0.05f, p)
        // 위쪽 얇은 빛 곡선(대시보드 곡면 느낌) · 빛줄기 — 캐릭터 쪽 칸에서만(카드 위로 비치지 않게)
        val sc = streakClip
        c.save(); if (sc != null) c.clipRect(sc) else c.clipRect(0f, 0f, 0f, 0f)
        p.shader = null; p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        cinemaPath.reset(); cinemaPath.moveTo(-w * 0.05f, h * 0.2f); cinemaPath.cubicTo(w * 0.3f, h * 0.05f, w * 0.7f, h * 0.02f, w * 1.05f, h * 0.16f)
        p.strokeWidth = 10 * U; p.color = Glass.a(0xFF6FA8FF.toInt(), 10); c.drawPath(cinemaPath, p)
        p.strokeWidth = 1.5f * U; p.color = Glass.a(0xFFB8D4FF.toInt(), 46); c.drawPath(cinemaPath, p)
        // 빛줄기 — 지평선 저편에서 캐릭터 발밑으로 흘러옴(넓은 번짐 → 가는 심)
        val fy = h * 0.71f
        cinemaPath.reset(); cinemaPath.moveTo(w * (if (mirror) 0.35f else 0.62f), hz + h * 0.01f)
        cinemaPath.cubicTo(w * 0.5f, hz + h * 0.06f, ccx + w * (if (mirror) -0.25f else 0.25f), fy - h * 0.02f, ccx, fy + h * 0.02f)
        cinemaPath.cubicTo(ccx - w * (if (mirror) -0.12f else 0.12f), fy + h * 0.06f, ccx - w * (if (mirror) -0.05f else 0.05f), h * 0.92f, ccx - w * (if (mirror) -0.2f else 0.2f), h * 1.02f)
        for ((sw, al, col) in listOf(Triple(46f, 14, 0xFF2F7BFF.toInt()), Triple(22f, 30, 0xFF3D8BFF.toInt()), Triple(9f, 70, 0xFF5AB0FF.toInt()), Triple(3f, 200, 0xFFBFE4FF.toInt()))) {
            p.strokeWidth = sw * U; p.color = Glass.a(col, al); c.drawPath(cinemaPath, p)
        }
        c.restore()
        p.style = Paint.Style.FILL; p.color = Color.WHITE   // 그라데이션이 앞 선의 반투명을 물려받지 않게
        // 캐릭터 뒤 푸른 역광 · 발밑 빛 웅덩이
        p.shader = RadialGradient(ccx, h * 0.48f, h * 0.55f, Glass.a(0xFF4C8DFF.toInt(), 60), Glass.a(0xFF4C8DFF.toInt(), 0), Shader.TileMode.CLAMP); c.drawCircle(ccx, h * 0.48f, h * 0.55f, p)
        c.save(); c.scale(1f, 0.22f, ccx, fy)
        p.shader = RadialGradient(ccx, fy, h * 0.4f, Glass.a(0xFF5AB0FF.toInt(), 110), 0x005AB0FF, Shader.TileMode.CLAMP); c.drawCircle(ccx, fy, h * 0.4f, p)
        c.restore()
        // 아래 가장자리 네온 선(화면 밑 빛 띠)
        p.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(Glass.a(0xFF3D8BFF.toInt(), 0), Glass.a(0xFF3D8BFF.toInt(), 120), Glass.a(0xFF3D8BFF.toInt(), 0)), floatArrayOf(0.1f, 0.5f, 0.9f), Shader.TileMode.CLAMP)
        c.drawRect(0f, h - 3 * U, w, h - 1 * U, p)
        // 비네트
        p.shader = RadialGradient(w * 0.5f, h * 0.5f, w * 0.75f, intArrayOf(0, 0, Glass.a(Color.BLACK, 130)), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p); p.shader = null
    }

    private fun scene(c: Canvas, w: Float, h: Float, t0: Float, still: Boolean) {
        val t = if (still) t0 * 0.2f else t0
        val hour = java.util.Calendar.getInstance().let { it.get(java.util.Calendar.HOUR_OF_DAY) + it.get(java.util.Calendar.MINUTE) / 60f }
        val nightSky = if (m.nightOverride == false) false else (darkUi || hour >= 19.5f || hour < 5f)   // --es night off면 저녁에도 밝은 하늘(11-14 녹화 — 19:30 넘으면 카드만 밝고 배경이 밤)
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
        if (k.id == "pleos" && !m.raining && !m.snowing) { studio(c, w, h, nightSky); p.shader = null; return }
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
    // 방금 한 대답 — 말이 끝난 뒤에도 잠깐 캐릭터 옆에 남긴다(장·아이디어 고정 문구보다 먼저). 그사이 운전자가 장을 넘기거나 눌렀으면 그 장 문구로.
    // 11-11 사용자: "대전 안내하라고 했는데 '오늘 소식 정리했어요'가 그대로 — 마지막 메시지를 표시해야"
    private var lastSaid = ""; private var lastSaidAt = 0L; private var touchedAt = 0L
    private var lastSaidReply = false; private var lastSaidMoving = false   // 대답(질문에 대한)이었나 · 주행 쪽(D·신호대기)에서 한 말인가 — 주차 때 말이 주행 화면에 남지 않게(11-12)
    private fun movingSide() = m.sit == Sit.DRIVE || m.sit == Sit.STOP || m.sit == Sit.READY
    private fun speech(c: Canvas, cx: Float, headTop: Float, maxW: Float, over: String? = null, tailX: Float? = null) {
        val now = SystemClock.elapsedRealtime()
        if (v.mode == HyunView.Mode.SPEAKING && v.hyunLine.isNotBlank() && v.hyunLine != lastSaid) { lastSaid = v.hyunLine; lastSaidAt = now; lastSaidReply = v.prioReply; lastSaidMoving = movingSide() }
        val hold = if (m.sit == Sit.DRIVE) 60_000L else 30_000L
        val recent = lastSaid.isNotBlank() && now - lastSaidAt < hold && max(m.pageAt, touchedAt) < lastSaidAt && lastSaidMoving == movingSide()
        val full = when {
            recent && v.mode == HyunView.Mode.READY -> keySentence(lastSaid)
            ideaNow() != null && v.mode == HyunView.Mode.READY -> ideaLine(ideaNow()!!)
            over != null && v.mode == HyunView.Mode.READY -> over
            m.view != null && v.mode == HyunView.Mode.READY -> viewLine(m.view!!)
            v.mode == HyunView.Mode.LISTENING -> "듣고 있어요…"
            v.mode == HyunView.Mode.THINKING -> "음… 생각 중이에요"
            v.mode == HyunView.Mode.SPEAKING && v.hyunLine.isNotBlank() -> keySentence(v.hyunLine)
            (SystemClock.elapsedRealtime() / 9000) % 2 == 1L && THEME_LINES[k.id] != null -> THEME_LINES[k.id]!![((SystemClock.elapsedRealtime() / 18000) % 2).toInt()]
            else -> m.tiles.getOrNull(m.heroIdx)?.label?.let { lab -> bubbleFor(lab) } ?: m.bubble.let { firstSentence(it) }
        }.let { if (it.length > 34) it.take(33) + "…" else it }
        if (full.isBlank()) return
        if (full != bubbleText) { bubbleText = full; bubbleAt = SystemClock.elapsedRealtime() }
        val n = if (glance()) full.length else ((SystemClock.elapsedRealtime() - bubbleAt) / 38).toInt().coerceIn(0, full.length)   // 운전 중(출발 준비 D · 주행 · 신호대기)엔 타자 효과 없이 — 한눈 규칙 '움직임 없음'(11-12)
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
    /** 차 컨디션 한 줄 — 읽힌 값만(벨트·문·배터리). 못 읽으면 "차부터 살펴볼게요" */
    private fun carBubble(): String {
        val st = m.status ?: return "차부터 살펴볼게요"
        val soc = st.soc.v?.toInt()
        return when {
            st.doorOpen.v == true -> "운전석 문이 열려 있어요"
            st.belt.v == false -> "벨트부터 매요"
            soc != null && soc < 30 -> "배터리 $soc% — 충전이 필요해요"
            soc != null && st.belt.v == true -> "배터리 $soc%, 벨트 OK예요"
            soc != null -> "배터리 $soc%예요"
            else -> "차부터 살펴볼게요"
        }
    }
    private fun bubbleFor(label: String): String = when (label) {
        "오늘 날씨" -> if (m.raining) "비가 와요. 천천히 가요" else "오늘 날씨를 정리했어요"
        "인사" -> if (m.sit == Sit.IGNITION) "가는 길에 어떤 이야기를 들려 드릴까요?" else label
        "차 컨디션" -> carBubble()   // 예전 고정 "차 상태는 괜찮아요"(실제 상태와 무관 — 가짜 감사 #11) → 읽은 값으로
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

    private val DOCK = listOf("brief" to "브리핑", "car" to "내 차", "deco" to "테마", "skin" to "스킨", "log" to "기록")   // '할 일'은 예시 목록뿐이라 뺐다(사용자 11-14 가짜 없애기) — 일정·할 일 연동 때 다시
    private fun dock(c: Canvas, w: Float, h: Float) {
        // 칸 폭 = 내용 칸에서 마이크(오른쪽 아래 지름 80 + 여유)를 뺀 만큼(내쉬 b9bf485 이후 앱 칸이 좁아짐) — 좁으면 글자 줄이고, 더 좁으면 아이콘만
        val avail = min(zoneR, w - 48 * U - 80 * U - 24 * U) - zoneL   // 마이크 왼쪽까지
        val dh = 64 * U; val iw = min(128 * U, avail / DOCK.size)
        val iconOnly = iw < 92 * U; val fs = if (iw >= 120 * U) 22 * U else 19 * U
        val r = RectF(zoneL, h - 48 * U - dh, zoneL + iw * DOCK.size, h - 48 * U)
        glassCard(c, r, 16 * U)
        DOCK.forEachIndexed { i, (key, lab) ->
            val b = RectF(r.left + i * iw, r.top, r.left + (i + 1) * iw, r.bottom)
            val on = m.overlay == key
            if (on) { p.color = SURF2; c.drawRoundRect(RectF(b.left + 6 * U, b.top + 6 * U, b.right - 6 * U, b.bottom - 6 * U), 12 * U, 12 * U, p) }
            val ic = when (key) { "brief" -> "news"; "todo" -> "bag"; "car" -> "battery"; "deco" -> "star"; "skin" -> "palette"; else -> "chart" }
            if (iconOnly) icon(c, ic, b.centerX(), b.centerY(), 24 * U, if (on) FG else MUTED)
            else { p.textSize = fs; p.typeface = medium; val gw = 22 * U + 10 * U + p.measureText(lab); val gx = b.centerX() - gw / 2   // 아이콘 + 글을 칸 가운데로
                icon(c, ic, gx + 11 * U, b.centerY(), 22 * U, if (on) FG else MUTED)
                text(c, lab, gx + 32 * U, b.centerY() + fs * 0.36f, fs, if (on) FG else MUTED, medium, maxW = iw - 40 * U) }
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
    /** 쉼표(슬립 모드) — 고르기(15분 · 30분) → 남은 시간 · 차 안 세팅 결과 · 5분 더 / 일어나기 */
    private fun restSheet(c: Canvas, z: RectF) {
        glassCard(c, z, 24 * U)
        val pad = 44 * U; val x0 = z.left + pad; val x1 = z.right - pad; val iw = x1 - x0
        if (m.restEndAt == 0L) {
            icon(c, "cup", x0 + 22 * U, z.top + pad + 22 * U, 44 * U, ACC)
            text(c, "쉼표 — 얼마나 쉴까요?", x0 + 62 * U, z.top + pad + 38 * U, 40 * U, FG, bold, maxW = iw - 62 * U)
            text(c, (if (m.restName.isNotBlank()) "${m.restName} · " else "") + "시작하면 조명을 낮추고 시트를 눕혀요", x0, z.top + pad + 86 * U, 24 * U, MUTED, medium, maxW = iw)
            val by = z.top + pad + 130 * U; val bh = min(210 * U, (z.bottom - by - pad - 80 * U))
            val cs = cols(RectF(x0, by, x1, by + bh), 1f, 1f)
            listOf(Triple("15분", "가볍게 쉬기", "rest:15"), Triple("30분", "푹 자기", "rest:30")).forEachIndexed { i, (big, sub, act) ->
                val r = cs[i]; p.shader = null; p.style = Paint.Style.FILL; p.color = if (i == 1) ACC else SURF2; c.drawRoundRect(r, 28 * U, 28 * U, p)
                text(c, big, r.centerX(), r.centerY() + 6 * U, 72 * U, if (i == 1) Color.WHITE else FG, bold, Paint.Align.CENTER)
                text(c, sub, r.centerX(), r.centerY() + 56 * U, 26 * U, if (i == 1) Glass.a(Color.WHITE, 220) else MUTED, medium, Paint.Align.CENTER)
                hits += r to act }
            button(c, "안 쉴래요", RectF(x0, z.bottom - pad - 56 * U, x0 + 170 * U, z.bottom - pad), false, "rest:close")
            text(c, "말로도 돼요 · \"30분\" · \"15분\"", x1, z.bottom - pad - 18 * U, 22 * U, FAINT, medium, Paint.Align.RIGHT)
            return
        }
        val left = (m.restEndAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L); val s = ((left + 999) / 1000).toInt()
        val wake = java.util.Calendar.getInstance().apply { add(java.util.Calendar.SECOND, s) }
        text(c, "쉼표 ${m.restMin}분 · %02d:%02d에 깨워 줄게요".format(wake.get(java.util.Calendar.HOUR_OF_DAY), wake.get(java.util.Calendar.MINUTE)), z.centerX(), z.top + pad + 34 * U, 28 * U, MUTED, medium, Paint.Align.CENTER, maxW = iw)
        val big = "%d:%02d".format(s / 60, s % 60)
        text(c, big, z.centerX(), z.top + z.height() * 0.42f, min(200 * U, z.height() * 0.3f), FG, bold, Paint.Align.CENTER)
        // 남은 시간 막대
        val bar = RectF(x0 + iw * 0.1f, z.top + z.height() * 0.5f, x1 - iw * 0.1f, z.top + z.height() * 0.5f + 10 * U)
        p.shader = null; p.style = Paint.Style.FILL; p.color = SURF2; c.drawRoundRect(bar, 5 * U, 5 * U, p)
        val pr = if (m.restTotalMs > 0) 1f - left.toFloat() / m.restTotalMs else 0f
        p.color = ACC; c.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * pr.coerceIn(0f, 1f), bar.bottom), 5 * U, 5 * U, p)
        // 차 안 세팅 — 실제로 된 것과 직접 해야 하는 것을 구분해 보인다
        val cab = m.restCabin
        val chips = if (cab == null) listOf("차 안을 맞추는 중…" to MUTED) else listOf(
            (if (cab["light"] == true) "조명 낮춤" else "실내등은 직접 꺼 주세요") to (if (cab["light"] == true) GOOD else MUTED),
            (if (cab["seat"] == true) "시트 눕힘" else "시트는 직접 눕혀 주세요") to (if (cab["seat"] == true) GOOD else MUTED))
        val cy = z.top + z.height() * 0.5f + 70 * U
        text(c, chips.joinToString("   ·   ") { it.first }, z.centerX(), cy, 26 * U, if (chips.all { it.second == GOOD }) GOOD else MUTED, medium, Paint.Align.CENTER, maxW = iw)
        val bw = min(300 * U, iw * 0.42f); val by = z.bottom - pad - 72 * U
        button(c, "5분 더", RectF(z.centerX() - bw - 12 * U, by, z.centerX() - 12 * U, by + 72 * U), false, "rest:more")
        button(c, "이제 일어날래요", RectF(z.centerX() + 12 * U, by, z.centerX() + 12 * U + bw, by + 72 * U), true, "rest:end")
    }

    private fun sheet(c: Canvas, zone: RectF, which: String) {
        if (which == "rest") { restSheet(c, zone); return }
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
                text(c, "예시 할 일 — 폰 현비서 앱·서버 할 일과 이어질 자리", x0, zone.bottom - pad + 8 * U, 18 * U, FAINT, medium) }
            "car" -> { val st = m.status
                fun src(v: io.github.somanghao.makecar.hyun.drive.CarStatus.V<*>?) = when (v?.v) { null -> "예시"; else -> when (v.from) { io.github.somanghao.makecar.hyun.drive.CarStatus.From.PLEOS -> "Pleos"; io.github.somanghao.makecar.hyun.drive.CarStatus.From.AAOS -> "AAOS"; io.github.somanghao.makecar.hyun.drive.CarStatus.From.APP -> "앱"; else -> "모의" } }
                fun line(ic: String, a: String, v: io.github.somanghao.makecar.hyun.drive.CarStatus.V<*>?, value: String) = row(ic, "$a  ·  ${src(v)}", value, ACC, if (v?.v == null) FAINT else FG)
                line("battery", st?.energyLabel?.v ?: "배터리", st?.soc, st?.soc?.v?.let { "$it%" } ?: "—")
                line("road", "주행 가능", st?.rangeKm, st?.rangeKm?.v?.let { "${it}km" } ?: "—")
                line("clock", "이번 주행", st?.tripKm, st?.tripKm?.v?.let { "%.1fkm · ${st.tripMin.v ?: 0}분".format(it) } ?: "—")
                line("cruise", "크루즈 · 주행 모드", st?.cruise, "${st?.cruise?.v ?: "—"} · ${st?.mode?.v ?: "—"}")
                line("temp", "실내 온도", st?.cabinNow?.takeIf { it.v != null } ?: st?.cabinC,   // 지금(센서) · 설정 — 읽히는 것만(11-10)
                    listOfNotNull(st?.cabinNow?.v?.let { "지금 %.1f°".format(it) }, st?.cabinC?.v?.let { "설정 %.1f°".format(it) }).joinToString(" · ").ifBlank { "—" })
                line("cloud", "와이퍼", st?.wiper, st?.wiper?.v?.let { if (it) "작동" else "꺼짐" } ?: "—")
                line("chat", "조수석 · 벨트", st?.passenger, "${st?.passenger?.v?.let { if (it) "착석" else "비어 있음" } ?: "—"} · ${st?.belt?.v?.let { if (it) "맴" else "풀림" } ?: "—"}")
                line("lock", "문", st?.doorOpen?.takeIf { it.v != null } ?: st?.locked,   // 운전석 문 열림(위치) · 잠김 — 전엔 잠김만 봐서 늘 '예시'(11-10)
                    listOfNotNull(st?.doorOpen?.v?.let { if (it) "열림" else "닫힘" }, st?.locked?.v?.let { if (it) "잠김" else "안 잠김" }).joinToString(" · ").ifBlank { "—" }) }
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
        // 닫기 — 상세 레이어 왼쪽 위 끝
        button(c, "닫기", RectF(x0, panel.top + 28 * U, x0 + 120 * U, panel.top + 84 * U), false, "close")
        text(c, "테마", x0 + 140 * U, panel.top + 64 * U, 44 * U, FG, bold)
        text(c, "안전운전으로 모은 별로 열어요 · 잠긴 테마도 눌러서 10초 미리보기", x0, panel.top + 112 * U, 22 * U, MUTED, medium, maxW = x1 - x0 - 260 * U)
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
        val r = RectF(w * 0.04f, h - 46f * S, w * 0.96f, h - 2f * S)
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
        rowOf(sims.map { Triple(it.first, "sim:${it.second}", false) }, r.top + 25f * S, r.top + 30.4f * S)
        val cur = StageModel.idea(m.idea)
        text(c, "상황별 아이디어 · ${StageModel.IDEAS.size}개", r.left + 2.4f * S, r.top + 33.4f * S, 1.9f * S, WARN, bold)
        rowOf(listOf(Triple("‹ 이전", "idea:prev", false), Triple(cur?.let { "${it.sit.label} · ${it.name}" } ?: "꺼짐", "idea:next", cur != null), Triple("다음 ›", "idea:next", false), Triple("끄기", "idea:off", false)), r.top + 35f * S, r.bottom - 1.6f * S)
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

    /** 넌센스 퀴즈 중(정차) — 정보 칸 전체를 퀴즈 카드로. 운전 안내·평소 카드는 깔지 않는다(사용자 11-14: 반반이면 내비가 이미 보이고,
     *  한 화면이면 퀴즈와 상관없는 정보라 필요 없다). 주행 중엔 이 화면 대신 한눈 정보 — 퀴즈는 말로만 */
    private fun quizScreen(c: Canvas, r: RectF) {
        val pr = m.prompt ?: return
        box(c, r)
        val x0 = r.left + 56 * U; val iw = r.width() - 112 * U
        val num = pr.first.substringBefore("번.", "").trim()
        val q = pr.first.substringAfter("번. ", pr.first)
        text(c, "넌센스 퀴즈" + (if (num.isNotBlank()) "  ·  $num / 3" else ""), x0, r.top + 76 * U, 34 * U, ACC, bold, maxW = iw)
        val qx = x0 + 120 * U
        text(c, "Q.", x0, r.top + 214 * U, 92 * U, ACC, bold)
        wrap(c, q, qx, r.top + 200 * U, fit(q, 56 * U, (iw - 120 * U) * 2.2f, bold).coerceAtLeast(40 * U), FG, iw - 120 * U, 3, bold, lh = 1.3f)
        text(c, pr.second, x0, r.bottom - 56 * U, 30 * U, MUTED, medium, maxW = iw)
    }
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
        val head = "${m.sit.label} · ${m.kicker.substringAfter(" · ")}"
        text(c, head, x, 60 * U, 22 * U, col, medium)
        p.textSize = 22 * U; p.typeface = medium
        var end = x + p.measureText(head)
        if (m.weatherShort.isNotBlank()) {
            var wx = end + 22 * U
            if (m.wcode == 0) { icon(c, "sun", wx + 12 * U, 52 * U, 24 * U, 0xFFFFB020.toInt()); wx += 32 * U }   // 맑음 — 해 아이콘
            text(c, m.weatherShort, wx, 60 * U, 22 * U, col, medium)
            p.textSize = 22 * U; p.typeface = medium; end = wx + p.measureText(m.weatherShort)
        }
        hits += RectF(x - 8 * U, 28 * U, end + 8 * U, 76 * U) to "sitpill"
        viewChips(c, x, 112 * U)
        if (m.view == null && !(m.viewForced && m.forced)) {   // 레벨 · 기분(2차) — 실제 주행으로 오르내림
            val face = when { m.mood >= 75 -> "♪"; m.mood < 40 -> "!"; else -> "·" }
            chip(c, "Lv.${m.level} ${m.levelTitle}  $face ${m.moodLabel}", x, 112 * U, "noop", false)
        } else if (m.view == null) { p.textSize = 20 * U; p.typeface = medium; chip(c, "Lv.${m.level} · ${m.moodLabel}", x + p.measureText("미리보기 · 자동으로") + 48 * U, 112 * U, "noop", false) }
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
    private fun navLine(): String = if (!hasRoute()) "목적지는 말로 정해요" else "${v.dist(m.navTurnM)} 앞에서 ${shortTurn(m.navTurnType, m.navTurn)}이에요"
    /** 실제 경로(또는 시연 모의 경로)가 있나 — 없으면 길 안내 칸에 아무 값도 꾸며 넣지 않는다 */
    private fun hasRoute() = m.navTurnType.isNotBlank() || m.navTurn.isNotBlank()
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

    // ---------- 내비 주화면 — 왼쪽: 다음 회전 · 도착 / 오른쪽: 경로 지도 ----------
    /** 운전 중(출발 준비 D · 주행 · 신호대기) — 한눈 규칙: 초점 하나 + 보조 1~2, 큰 글자, 작은 라벨·목록·그림·차트 없음 */
    private fun glance() = m.sit == Sit.DRIVE || m.sit == Sit.STOP || m.sit == Sit.READY

    /** 조사 로/으로 — 받침(ㄹ 제외) 있으면 으로 */
    private fun ro(w: String): String { val ch = w.lastOrNull() ?: return "로"; if (ch !in '가'..'힣') return "로"; val jong = (ch - '가') % 28; return if (jong == 0 || jong == 8) "로" else "으로" }
    private fun turnWord() = shortTurn(m.navTurnType, m.navTurn)
    /** 그다음 회전(내비 TBT 둘째 — 회전 · 거리 · 방면). 예전 차로 화살표 4칸은 회전 종류로 짐작해 그린 것이라 뺐다 — Pleos NaviHelper에 차선 정보가 없다(navi-data TBTInfo = 종류·거리·설명뿐, 11-11 바이너리 확인)
     *  그다음 회전이 없으면 남은 거리 · 목적지(이것도 내비 실제 값) */
    /** 사고 예고 카드(IncidentFeature) — 내 경로 앞 실제 돌발 한 건. 머리(종류 · 거리) · 어디 · 출처·접수 시각(가짜 금지 — 서버가 받은 값만) */
    private fun incidentTile(c: Canvas, r: RectF, inc: io.github.somanghao.makecar.hyun.ui.Incident) {
        val col = if (inc.sev >= 3) RED else WARN
        glassCard(c, r, 24 * U, col)
        val pad = 40 * U
        icon(c, "warn", r.left + pad + 28 * U, r.top + pad + 26 * U, 52 * U, col)
        text(c, inc.head, r.left + pad + 76 * U, r.top + pad + 42 * U, fit(inc.head, 40 * U, r.width() - pad * 2 - 76 * U, bold), col, bold, maxW = r.width() - pad * 2 - 76 * U)
        val bw = r.width() - pad * 2
        val n = min(2, lineCount(inc.body, bw, 40 * U, bold))
        wrap(c, inc.body, r.left + pad, r.centerY() + 22 * U - (n - 1) * 24 * U, 40 * U, FG, bw, 2, bold, lh = 1.2f)
        if (inc.foot.isNotBlank()) text(c, inc.foot, r.left + pad, r.bottom - 30 * U, 26 * U, MUTED, medium, maxW = bw)
    }

    private fun nextTurnTile(c: Canvas, r: RectF) {
        box(c, r)
        if (m.navNext.isNotBlank()) {
            lab(c, "그다음", r.left + 40 * U, r.top + 52 * U)
            val ic = min(110 * U, r.height() * 0.42f)
            v.turnIcon(c, m.navNextType.ifBlank { "TURN_STRAIGHT" }, r.left + 40 * U + ic / 2, r.centerY() + 24 * U, ic, ACC); p.style = Paint.Style.FILL; p.strokeWidth = 0f
            val tx = r.left + 64 * U + ic
            text(c, "${v.dist(m.navNextM)} 뒤 ${shortTurn(m.navNextType, m.navNext)}", tx, r.centerY() + 14 * U, fit("${v.dist(m.navNextM)} 뒤 ${shortTurn(m.navNextType, m.navNext)}", 46 * U, r.right - tx - 32 * U, bold), FG, bold, maxW = r.right - tx - 32 * U)
            text(c, m.navNext, tx, r.centerY() + 62 * U, 30 * U, MUTED, medium, maxW = r.right - tx - 32 * U)
        } else {
            lab(c, "남은 거리", r.left + 40 * U, r.top + 52 * U)
            text(c, m.navRemain, r.left + 40 * U, r.centerY() + 40 * U, fit(m.navRemain, 80 * U, r.width() * 0.6f, bold), FG, bold)
            text(c, m.navDest, r.left + 40 * U, r.bottom - 32 * U, 30 * U, MUTED, medium, maxW = r.width() - 80 * U)
        }
    }

    // ================= 주행·신호대기 = 대화 + 한눈 정보(사용자 11-12 "주행 중 화면에 대화하고 정보 보여 줘야 할 거 아냐 — 무슨 좌회전만 있어") =================
    // 한눈 규칙(명세 hyunbiseo docs/specs · 주행 안전): 항목 ≤5 · 큰 글씨 · 터치 없음(버튼·[전화] 숨김 — 정차 때만) · 움직임 없음 ·
    // 카드는 처음 보인 뒤 60초 또는 회전 300m 앞이면 접힘(회전 앞엔 내비가 크게). 정차(주차·시동)하면 지금처럼 전체 카드.
    private var turnKey = ""; private var turnAt = 0L
    private var glCard: HyunView.Card? = null; private var glCardAt = 0L
    private val TURN_FOCUS_MS = 20_000L
    private val GLANCE_CARD_MS = 60_000L

    /** 방금 대화 — 내 질문 한 줄(작게) + 현비서 답 핵심(큰 글씨 3줄까지). 듣는·생각하는·말하는 중이거나 대답 뒤 60초(신호대기 30초). 없으면 false */
    private fun dialog(c: Canvas, x: Float, top: Float, bottom: Float, w: Float): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (v.mode == HyunView.Mode.SPEAKING && v.hyunLine.isNotBlank() && v.hyunLine != lastSaid) { lastSaid = v.hyunLine; lastSaidAt = now; lastSaidReply = v.prioReply; lastSaidMoving = movingSide() }
        val hold = if (m.sit == Sit.DRIVE) 60_000L else 30_000L
        val recent = lastSaid.isNotBlank() && now - lastSaidAt < hold && lastSaidMoving == movingSide()
        val ans = when (v.mode) {
            HyunView.Mode.LISTENING -> "듣고 있어요…"
            HyunView.Mode.THINKING -> "음… 찾아보는 중이에요"
            HyunView.Mode.SPEAKING -> v.hyunLine.ifBlank { lastSaid }
            else -> if (recent) lastSaid else return false
        }
        // 이 대답의 질문 — 마지막 내 말 뒤에 지금 대답이 있을 때(사이에 "거의 다 찾았어요" 같은 중간 말이 낄 수 있다) 또는 아직 생각 중
        val chat = v.chat; val mi = chat.indexOfLast { it.me }
        val cur = setOf(lastSaid.trim(), v.hyunLine.trim())
        val q = chat.getOrNull(mi)?.text?.takeIf { mi >= 0 && (v.mode == HyunView.Mode.THINKING || v.mode == HyunView.Mode.LISTENING || (lastSaidReply && chat.drop(mi + 1).any { b -> !b.me && b.text in cur })) }
        val sents = Regex("(?<=[.!?])\\s+").split(adult(ans).trim()).filter { it.isNotBlank() }
        // 물음이 있으면 그 앞 문장 + 물음("대신 백경한우가 가까워요. 거기로 안내할까요?" — '거기'가 가리키는 문장을 같이), 없으면 앞 두 문장
        val qi = sents.indexOfLast { it.trim().endsWith("?") }
        val core = when { qi > 0 -> sents.subList(qi - 1, qi + 1).joinToString(" "); qi == 0 -> sents[0]; else -> sents.take(2).joinToString(" ") }
        val col = if (sceneDark) 0xF2FFFFFF.toInt() else FG
        var y = top
        if (q != null) { y = wrap(c, "나: $q", x + 18 * U, y + 26 * U, 26 * U, MUTED, w - 18 * U, 1, medium) + 10 * U }
        val fs = 38 * U
        val lines = min(3, max(1, ((bottom - y) / (fs * 1.32f)).toInt()))
        p.color = ACC; c.drawRect(x, y + fs * 0.1f, x + 4 * U, y + fs * 0.25f + min(lines, splitLines(core, w - 18 * U).size) * fs * 1.32f, p)
        wrap(c, core, x + 18 * U, y + fs, fs, col, w - 18 * U, lines, bold, lh = 1.32f)
        return true
    }

    /** 주행·신호대기 오른쪽 — (경로 없으면 '목적지 없음' 한 줄) + 방금 답의 카드 요약 · 카드 없으면 그다음 회전 + 오늘 주행 */
    private fun driveGlance(c: Canvas, z: RectF) {
        val now = SystemClock.elapsedRealtime()
        val card = v.card
        if (card !== glCard) { glCard = card; glCardAt = now }
        var top = z.top
        if (!m.navActive) {
            val r = RectF(z.left, top, z.right, top + 84 * U); box(c, r)
            icon(c, "pin", r.left + 48 * U, r.centerY(), 40 * U, ACC)
            text(c, "목적지 없음 · \"현비서야, 코엑스 가자\"", r.left + 90 * U, r.centerY() + 12 * U, 32 * U, FG, medium, maxW = r.width() - 120 * U)
            top = r.bottom + G
        }
        val body = RectF(z.left, top, z.right, z.bottom)
        if (card != null && now - glCardAt < GLANCE_CARD_MS) { glanceCard(c, body, card); return }
        val tipH = if (m.sit == Sit.STOP && m.novice) 84 * U + G else 0f
        val rest = RectF(body.left, body.top, body.right, body.bottom - tipH)
        if (m.infoMain) { driveInfo(c, rest); return }   // '정보 크게' — 회전은 위 작은 내비 띠에 있으니 여기선 정보만(사용자 11-14)
        if (hasRoute()) {
            val hh = (rest.height() - G) * 0.55f
            nextTurnTile(c, RectF(rest.left, rest.top, rest.right, rest.top + hh))
            tripTile(c, RectF(rest.left, rest.top + hh + G, rest.right, rest.bottom))
        } else tripTile(c, RectF(rest.left, rest.top, rest.right, rest.top + min(rest.height(), 320 * U)))
        if (tipH > 0f) { val r = RectF(body.left, body.bottom - 84 * U, body.right, body.bottom); box(c, r); text(c, "브레이크를 계속 꾹", r.left + 48 * U, r.centerY() + 12 * U, 34 * U, FG, medium) }
    }

    /** 주행 정보판('정보 크게', 답 카드가 없을 때) — 뉴스 헤드라인 · 코스피·나스닥 · 이번 주행을 크게. 전부 실제 값(연합 RSS · 장 마감 지수 · 앱이 잰 주행) */
    private fun driveInfo(c: Canvas, r: RectF) {
        val h1 = (r.height() - 2 * G) * 0.36f; val h2 = (r.height() - 2 * G) * 0.28f
        val nr = RectF(r.left, r.top, r.right, r.top + h1); box(c, nr)
        val x0 = nr.left + 40 * U; val iw = nr.width() - 80 * U
        lab(c, "뉴스", x0, nr.top + 50 * U)
        val n = leadNews()
        wrap(c, n ?: "헤드라인을 불러오는 중이에요", x0, nr.top + 110 * U, 46 * U, FG, iw, 2, bold, lh = 1.3f)
        val sr = RectF(r.left, nr.bottom + G, r.right, nr.bottom + G + h2)
        val cs = cols(sr, 1f, 1f)
        listOf("코스피", "나스닥").forEachIndexed { i, nm ->
            val b = cs[i]; box(c, b)
            val q = m.indices.firstOrNull { it.name.contains(nm) }
            lab(c, nm, b.left + 40 * U, b.top + 50 * U)
            text(c, q?.let { pct(it.ratio) } ?: "—", b.left + 40 * U, b.top + min(b.height() * 0.72f, 140 * U), 64 * U, q?.let { updown(it.ratio) } ?: FAINT, bold, maxW = b.width() - 80 * U)
        }
        tripTile(c, RectF(r.left, sr.bottom + G, r.right, r.bottom))
    }

    /** 오늘 주행 — 이번 주행 거리·시간 · 별 · 오늘의 퀘스트(전부 앱이 잰 실제 값) */
    private fun tripTile(c: Canvas, r: RectF) {
        box(c, r)
        val x0 = r.left + 40 * U
        lab(c, "이번 주행", x0, r.top + 50 * U)
        val cs = cols(RectF(x0, r.top, r.right - 40 * U, r.bottom), 1.2f, 1f, 1.2f)
        val sy = r.top + min(r.height() * 0.62f, 150 * U)
        stat(c, cs[0].left, sy, "%.1fkm".format(m.tripKm), "${max(1, m.tripMin)}분 · 급정거 ${m.harshN}", FG, cs[0].width(), 52 * U)
        stat(c, cs[1].left, sy, sgn(m.tripPoints), "별", if (m.tripPoints >= 0) GOOD else RED, cs[1].width(), 52 * U)
        stat(c, cs[2].left, sy, if (m.questDone) "성공" else "%.1f/10".format(m.questKm), "급정거 없이 10km", if (m.questDone) GOOD else FG, cs[2].width(), 52 * U)
    }

    /** "04:00" → "새벽 4시"(HyunView·폰 [정보] 탭과 같은 규칙) */
    private fun easyHm(hm: String): String? {
        val mt = Regex("""(\d{1,2}):(\d{2})""").find(hm) ?: return null
        val hh = mt.groupValues[1].toInt(); val mi = mt.groupValues[2].toInt()
        val part = when (hh) { in 0..5 -> "새벽"; in 6..11 -> "오전"; 12 -> "낮"; in 13..17 -> "오후"; in 18..20 -> "저녁"; else -> "밤" }
        val h12 = when { hh == 0 -> 12; hh > 12 -> hh - 12; else -> hh }
        return "$part ${h12}시" + if (mi > 0) " ${mi}분" else ""
    }

    /** 한눈 카드 — H가 만든 같은 카드 데이터(폰 [정보] 탭과 값 동일)를 주행용으로: 웹툰 상위 5(작은 표지) · 일정(다음 경기 날짜·한국 시각·상대 + 작은 사진)
     *  · 장소 상위 3(걸어서 N분 강조) · 지수 2~3(등락 색) · 소개(사진 + 3줄) · 그 밖 상위 5. 버튼 없음 */
    private fun glanceCard(c: Canvas, r: RectF, k: HyunView.Card) {
        box(c, r)
        val x0 = r.left + 36 * U; val x1 = r.right - 36 * U
        text(c, k.title.ifBlank { "현비서" }, x0, r.top + 60 * U, fit(k.title.ifBlank { "현비서" }, 42 * U, x1 - x0, bold), FG, bold)
        val a = RectF(x0, r.top + 96 * U, x1, r.bottom - 28 * U)
        val walkRe = Regex("""걸어서\s*(\d+)\s*분""")
        val pctRe = Regex("""([+\-−▲▼])\s*(\d+(?:\.\d+)?)\s*%""")
        fun parts(s: String) = s.split(" — ", " - ", limit = 2)
        when {
            k.kind == "schedule" -> {
                val img = k.images.firstOrNull { it.isNotBlank() }
                var lx = a.left
                if (img != null) { val ih = min(a.height(), 300 * U); val ir = RectF(a.left, a.top, a.left + ih * 0.78f, a.top + ih); photoFit(c, img, ir, 16 * U); lx = ir.right + 32 * U }
                val rows = k.items.take(3)
                val past = { s: String -> Regex("끝남|결과|종료").containsMatchIn(parts(s)[0]) }
                val next = rows.indexOfFirst { !past(it) }
                val rh = min(a.height() / max(rows.size, 1), 120 * U)
                rows.forEachIndexed { i, s ->
                    val ty = a.top + rh * i
                    val ps = parts(s); val date = Regex("""\d{1,2}-\d{1,2}(\(.\))?""").find(ps[0])?.value.orEmpty()
                    val hm = Regex("""\d{1,2}:\d{2}""").find(ps[0])?.value
                    val big = when { past(s) -> "끝남"; hm != null -> easyHm(hm) ?: hm; else -> "시각 미정" }
                    if (i == next) { p.shader = null; p.color = Glass.a(ACC, 50); c.drawRoundRect(RectF(lx - 12 * U, ty + 4 * U, a.right, ty + rh - 4 * U), 14 * U, 14 * U, p) }
                    text(c, date, lx, ty + rh * 0.38f, 26 * U, MUTED, medium, maxW = 200 * U)
                    text(c, big, lx, ty + rh * 0.82f, 38 * U, if (past(s)) MUTED else FG, bold, maxW = 260 * U)
                    val vs = ps.getOrNull(1).orEmpty().split(" · ").first()
                    text(c, vs, lx + 280 * U, ty + rh * 0.66f, fit(vs, 36 * U, a.right - lx - 300 * U, bold), if (past(s)) MUTED else FG, bold, maxW = a.right - lx - 300 * U)
                }
            }
            k.kind == "info" -> {
                val img = k.images.firstOrNull { it.isNotBlank() }
                var lx = a.left
                if (img != null) { val iw = min(a.width() * 0.36f, 320 * U); val ir = RectF(a.left, a.top, a.left + iw, a.top + min(a.height(), iw * 1.1f)); photoFit(c, img, ir, 16 * U); lx = ir.right + 32 * U }
                val rows = k.items.take(3); val rh = min(a.height() / 3f, 110 * U)
                rows.forEachIndexed { i, s -> val ps = parts(s); val ty = a.top + rh * i
                    if (ps.size > 1) { text(c, ps[0], lx, ty + rh * 0.36f, 24 * U, MUTED, medium, maxW = a.right - lx); text(c, ps[1], lx, ty + rh * 0.8f, fit(ps[1], 36 * U, a.right - lx, bold), FG, bold, maxW = a.right - lx) }
                    else wrap(c, s, lx, ty + rh * 0.6f, 32 * U, FG, a.right - lx, 1, bold) }
            }
            k.images.any { it.isNotBlank() } -> {   // 웹툰 순위 — 10개면 좌우 5개씩(1~5위 | 6~10위, 사용자 11-14) · 작은 표지
                val n = min(10, k.items.size); val colN = if (n > 5) 2 else 1
                val gap = 32 * U; val cw = (a.width() - gap * (colN - 1)) / colN
                val rh = min(a.height() / 5f, 96 * U)
                for (i in 0 until n) {
                    val x0 = a.left + (i / 5) * (cw + gap); val ty = a.top + rh * (i % 5); val s = k.items[i]
                    val th = RectF(x0 + 56 * U, ty + rh * 0.08f, x0 + 56 * U + rh * 0.84f * 0.75f, ty + rh * 0.92f)
                    text(c, "${i + 1}", x0 + 40 * U, ty + rh * 0.66f, 36 * U, if (i < 3) ACC else MUTED, bold, Paint.Align.RIGHT)
                    k.images.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { photoFit(c, it, th, 8 * U) }
                    val tx = th.right + 20 * U; val tw = x0 + cw - tx
                    text(c, parts(s)[0], tx, ty + rh * 0.64f, fit(parts(s)[0], 34 * U, tw, bold), FG, bold, maxW = tw)
                }
            }
            k.items.any { walkRe.containsMatchIn(it) } -> {   // 장소 — 상위 3 · 걸어서 N분 강조
                val rows = k.items.take(3); val rh = min(a.height() / 3f, 130 * U)
                val mins = rows.map { walkRe.find(it)?.groupValues?.get(1)?.toIntOrNull() }
                val near = mins.withIndex().filter { it.value != null }.minByOrNull { it.value!! }?.index ?: -1
                rows.forEachIndexed { i, s -> val ty = a.top + rh * i
                    val wm = mins[i]
                    val label = wm?.let { "걸어서 ${it}분" } ?: ""
                    p.textSize = 32 * U; p.typeface = bold; val pw = if (label.isNotBlank()) p.measureText(label) + 44 * U else 0f
                    if (label.isNotBlank()) { val pr = RectF(a.right - pw, ty + rh * 0.22f, a.right, ty + rh * 0.78f); p.shader = null; p.color = if (i == near) ACC else Glass.a(ACC, 70); c.drawRoundRect(pr, pr.height() / 2, pr.height() / 2, p)
                        text(c, label, pr.centerX(), pr.centerY() + 11 * U, 32 * U, Color.WHITE, bold, Paint.Align.CENTER) }
                    text(c, "${i + 1}", a.left + 28 * U, ty + rh * 0.62f, 38 * U, if (i == near) ACC else MUTED, bold, Paint.Align.RIGHT)
                    text(c, parts(s)[0], a.left + 56 * U, ty + rh * 0.62f, fit(parts(s)[0], 40 * U, a.width() - 80 * U - pw, bold), FG, bold, maxW = a.width() - 80 * U - pw) }
            }
            k.items.any { pctRe.containsMatchIn(it) } -> {   // 주식 지수 — 2~3개 · 등락 색
                val rows = k.items.filter { pctRe.containsMatchIn(it) }.take(3); val rh = min(a.height() / 3f, 130 * U)
                rows.forEachIndexed { i, s -> val ty = a.top + rh * i
                    val mt = pctRe.find(s)!!; val up = mt.groupValues[1] == "+" || mt.groupValues[1] == "▲"
                    val col = updown(if (up) 1f else -1f)
                    val ps = parts(s)
                    text(c, ps[0], a.left, ty + rh * 0.62f, 40 * U, FG, bold, maxW = a.width() * 0.4f)
                    val rest = ps.getOrNull(1).orEmpty()
                    text(c, rest, a.right, ty + rh * 0.62f, fit(rest, 40 * U, a.width() * 0.58f, bold), col, bold, Paint.Align.RIGHT, maxW = a.width() * 0.58f) }
            }
            else -> {   // 그 밖 목록 — 제목만, 6개 넘으면 좌우 5개씩(최대 10)
                val n = min(10, k.items.size); val colN = if (n > 5) 2 else 1
                val gap = 32 * U; val cw = (a.width() - gap * (colN - 1)) / colN
                val rh = min(a.height() / 5f, 96 * U)
                for (i in 0 until n) {
                    val x0 = a.left + (i / 5) * (cw + gap); val ty = a.top + rh * (i % 5); val s = k.items[i]
                    text(c, "${i + 1}", x0 + 40 * U, ty + rh * 0.64f, 36 * U, if (i < 3) ACC else MUTED, bold, Paint.Align.RIGHT)
                    text(c, parts(s)[0], x0 + 60 * U, ty + rh * 0.64f, fit(parts(s)[0], 34 * U, cw - 60 * U, bold), FG, bold, maxW = cw - 60 * U)
                }
            }
        }
    }

    /** 운전 중 내비 — 지도 없이: 왼쪽 큰 회전(화살표 · 거리 · 길 이름) / 오른쪽 차로 한 줄 · 도착 시각 */
    private fun navGlance(c: Canvas, r: RectF) {
        val cs = cols(r, 1.35f, 1f)
        val L = cs[0]; box(c, L)
        if (!hasRoute()) {   // 실제 경로가 없으면 꾸민 회전·도착을 보이지 않는다(예전엔 모의 경로 판교→코엑스가 표시 없이 나왔다 — 가짜 감사 #3)
            icon(c, "pin", L.left + 96 * U, L.centerY() - 70 * U, 96 * U, ACC)
            text(c, "목적지 없음", L.left + 170 * U, L.centerY() - 46 * U, fit("목적지 없음", 80 * U, L.width() - 220 * U, bold), FG, bold)
            wrap(c, "\"현비서야, 코엑스 가자\"처럼 말하면 길을 안내해요", L.left + 64 * U, L.centerY() + 70 * U, 40 * U, MUTED, L.width() - 128 * U, 2, medium, lh = 1.3f)
            val R0 = cs[1]
            val top0 = RectF(R0.left, R0.top, R0.right, R0.top + R0.height() * 0.46f)
            val di0 = ideaNow(); val ev0 = m.carEvent
            if (di0 != null && m.sit == Sit.DRIVE) ideaBanner(c, top0, di0)
            else if (m.sit == Sit.DRIVE && ev0 != null && SystemClock.elapsedRealtime() - m.carEventAt < 6000) { glassCard(c, top0, 24 * U, ACC); icon(c, ev0.first, top0.left + 80 * U, top0.centerY(), 64 * U, ACC); wrap(c, ev0.second, top0.left + 150 * U, top0.centerY() + 16 * U, 44 * U, FG, top0.width() - 190 * U, 2, bold, lh = 1.2f) }
            else box(c, top0)
            val bot0 = RectF(R0.left, top0.bottom + G, R0.right, R0.bottom); box(c, bot0)
            val now = java.util.Calendar.getInstance(); val clock = "%02d:%02d".format(now.get(java.util.Calendar.HOUR_OF_DAY), now.get(java.util.Calendar.MINUTE))
            text(c, clock, bot0.left + 48 * U, bot0.centerY() + 20 * U, fit(clock, 110 * U, bot0.width() * 0.6f, bold), FG, bold)
            text(c, "지금", bot0.left + 48 * U, bot0.centerY() + 84 * U, 40 * U, MUTED, medium)
            return
        }
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
        val di = ideaNow()
        val inc = v.incident
        if (inc != null && !(m.sit == Sit.DRIVE && ev != null && SystemClock.elapsedRealtime() - m.carEventAt < 6000)) incidentTile(c, top, inc)   // 사고 예고(차 상태 6초 알림만 먼저)
        else if (di != null && m.sit == Sit.DRIVE) ideaBanner(c, top, di)   // 주행 중 아이디어 — 이 칸 한 줄
        else if (m.sit == Sit.DRIVE && ev != null && SystemClock.elapsedRealtime() - m.carEventAt < 6000) {   // 차 상태가 바뀌면 6초 동안 그 한 줄
            val col = when (ev.first) { "warn" -> RED; "signal" -> WARN; "cruise", "road", "flag" -> GOOD; else -> ACC }
            glassCard(c, top, 24 * U, col)
            if (ev.first != "signal" || (SystemClock.elapsedRealtime() / 500) % 2 == 0L) icon(c, ev.first, top.left + 80 * U, top.centerY(), 64 * U, col)
            val n2 = min(2, lineCount(ev.second, top.width() - 190 * U, 44 * U, bold))
            wrap(c, ev.second, top.left + 150 * U, top.centerY() + 16 * U - (n2 - 1) * 26 * U, 44 * U, FG, top.width() - 190 * U, 2, bold, lh = 1.2f)
        } else nextTurnTile(c, top)
        val bot = RectF(R.left, top.bottom + G, R.right, R.bottom)
        box(c, bot)
        val eta = m.navEta.removePrefix("도착 ")
        text(c, eta, bot.left + 48 * U, bot.centerY() + 20 * U, fit(eta, 110 * U, bot.width() * 0.6f, bold), FG, bold)
        p.textSize = fit(eta, 110 * U, bot.width() * 0.6f, bold); p.typeface = bold; val ex = bot.left + 48 * U + p.measureText(eta) + 24 * U
        text(c, "도착", ex, bot.centerY() + 18 * U, 40 * U, MUTED, medium)
        if (m.navMin > 0) text(c, Fmt.dur(m.navMin), bot.left + 48 * U, bot.centerY() + 84 * U, 40 * U, MUTED, medium)
    }


    /** 운전 중 내비 — 맨 위 현비서 말풍선(칼리 11-12, 소개자료 5장) + 아래는 실제 내비 값만(다음 회전 · 그다음 · 도착 — NaviHelper TBT · onDrivingInfo).
     *  지도는 그리지 않는다(사용자 11-14 "내비게이션은 완전 가짜") — 실제 지도는 Pleos 내비가 반반 [내비 | 현비서]로 옆에 보인다 */
    private fun navGlance(c: Canvas, r: RectF, w: Float, h: Float) {
        val smH = h * 0.14f
        chanStrip(c, RectF(40 * U + smH + 24 * U, 16 * U, w - 40 * U, 16 * U + smH))
        if (r.width() < r.height() * 1.25f) navNarrow(c, r) else navGlance(c, r)
    }

    /** 반반(좁은 칸) 주행 — 위 큰 회전(화살표 · 거리 · 길 이름) · 아래 도착 시각 · 남은 거리. 경로가 없으면 '목적지 없음' */
    private fun navNarrow(c: Canvas, r: RectF) {
        if (!hasRoute()) {
            box(c, r)
            icon(c, "pin", r.left + 80 * U, r.top + 110 * U, 80 * U, ACC)
            text(c, "목적지 없음", r.left + 140 * U, r.top + 134 * U, fit("목적지 없음", 64 * U, r.width() - 180 * U, bold), FG, bold)
            wrap(c, "\"현비서야, 코엑스 가자\"처럼 말하면 길을 안내해요", r.left + 48 * U, r.top + 230 * U, 36 * U, MUTED, r.width() - 96 * U, 3, medium, lh = 1.3f)
            return
        }
        val top = RectF(r.left, r.top, r.right, r.top + (r.height() - G) * 0.66f); box(c, top)
        val ts = min(220 * U, top.height() * 0.42f)
        val tile = RectF(top.left + 40 * U, top.top + 40 * U, top.left + 40 * U + ts, top.top + 40 * U + ts)
        p.shader = null; p.color = ACC; c.drawRoundRect(tile, 36 * U, 36 * U, p)
        v.turnIcon(c, m.navTurnType.ifBlank { "TURN_STRAIGHT" }, tile.centerX(), tile.centerY() - ts * 0.03f, ts * 0.72f, Color.WHITE); p.style = Paint.Style.FILL; p.strokeWidth = 0f
        val dist = v.dist(m.navTurnM); val nx = tile.right + 36 * U
        text(c, dist, nx, tile.top + ts * 0.55f, fit(dist, 120 * U, top.right - nx - 32 * U, bold), FG, bold)
        text(c, turnWord(), nx, tile.top + ts * 0.55f + 64 * U, 44 * U, MUTED, medium, maxW = top.right - nx - 32 * U)
        wrap(c, m.navTurn, top.left + 40 * U, tile.bottom + 80 * U, 44 * U, FG, top.width() - 80 * U, 2, medium, lh = 1.25f)
        val inc = v.incident   // 사고 예고가 있으면 '그다음' 대신 빨간 한 줄
        if (inc != null) text(c, inc.line, top.left + 40 * U, top.bottom - 40 * U, fit(inc.line, 34 * U, top.width() - 80 * U, bold), if (inc.sev >= 3) RED else WARN, bold, maxW = top.width() - 80 * U)
        else if (m.navNext.isNotBlank()) text(c, "그다음 ${v.dist(m.navNextM)} 뒤 ${shortTurn(m.navNextType, m.navNext)}", top.left + 40 * U, top.bottom - 40 * U, 34 * U, ACC, medium, maxW = top.width() - 80 * U)
        val bot = RectF(r.left, top.bottom + G, r.right, r.bottom); box(c, bot)
        val eta = m.navEta.removePrefix("도착 ")
        text(c, eta, bot.left + 40 * U, bot.centerY() + 10 * U, fit(eta, 96 * U, bot.width() * 0.5f, bold), FG, bold)
        p.textSize = fit(eta, 96 * U, bot.width() * 0.5f, bold); p.typeface = bold; val ex = bot.left + 40 * U + p.measureText(eta) + 20 * U
        text(c, "도착", ex, bot.centerY() + 8 * U, 36 * U, MUTED, medium)
        text(c, listOf(m.navRemain, if (m.navMin > 0 && !m.navRemain.contains("분")) Fmt.dur(m.navMin) else "").filter { it.isNotBlank() }.joinToString(" · "), bot.left + 40 * U, bot.centerY() + 66 * U, 34 * U, MUTED, medium, maxW = bot.width() - 80 * U)
    }

    /** 운전 중 맨 위 — 현비서 말풍선(칼리 11-12). 말하는 중엔 그 말, 평소엔 좌석을 보고 고른 제안 하나(StageModel.offer)를 띄우고 말로 하는 대답을 기다리는 모습(오른쪽 마이크). 누르는 것 없음 · 차 신호가 바뀌면 6초 동안 그 알림 */
    private fun chanStrip(c: Canvas, r: RectF) {
        val now = SystemClock.elapsedRealtime()
        val ev = m.carEvent
        if (m.sit == Sit.DRIVE && ev != null && now - m.carEventAt < 6000) {
            val col = when (ev.first) { "warn" -> RED; "signal" -> WARN; "cruise", "road", "flag" -> GOOD; else -> ACC }
            glassCard(c, r, 24 * U, col)
            if (ev.first != "signal" || (now / 500) % 2 == 0L) icon(c, ev.first, r.left + 70 * U, r.centerY(), 52 * U, col)
            text(c, ev.second, r.left + 130 * U, r.centerY() + 14 * U, 40 * U, FG, bold, maxW = r.width() - 170 * U)
            return
        }
        val speaking = v.mode == HyunView.Mode.SPEAKING && v.hyunLine.isNotBlank()
        val listening = v.mode == HyunView.Mode.LISTENING
        // 지난 제안이 말풍선에 계속 남지 않게 — 말하는·생각하는 중, 진행 중인 퀴즈, 건넨 지 45초 안의 제안일 때만(사용자 11-14 "페이지 화면이 바로 나와야지")
        val fresh = m.prompt != null || (m.offerAt != 0L && now - m.offerAt < 45_000L)
        if (!speaking && v.mode != HyunView.Mode.THINKING && !fresh) return
        // 말풍선 — 둥근 판 + 캐릭터 쪽(왼쪽) 꼬리
        val bg = if (darkUi) Glass.a(SURF, 245) else Glass.a(Color.WHITE, 250)
        p.shader = null; p.style = Paint.Style.FILL; p.color = bg
        c.drawRoundRect(r, 40 * U, 40 * U, p)
        path.reset(); path.moveTo(r.left + 4 * U, r.centerY() - 20 * U); path.lineTo(r.left - 26 * U, r.centerY() + 6 * U); path.lineTo(r.left + 4 * U, r.centerY() + 22 * U); path.close(); c.drawPath(path, p)
        if (speaking) { p.style = Paint.Style.STROKE; p.strokeWidth = 3 * U; p.color = Glass.a(ACC, 200); c.drawRoundRect(r, 40 * U, 40 * U, p); p.style = Paint.Style.FILL; p.strokeWidth = 0f }
        val slim = r.width() < 1000 * U   // 반반(좁은 칸) — '말로 대답' 글자는 빼고 제안은 두 줄로(11-14: "오늘 주요 뉴…"로 잘렸다)
        val x0 = r.left + 48 * U; val micW = if (slim) 110 * U else 230 * U; val iw = r.width() - 96 * U - micW
        when {
            speaking -> { val s = adult(v.hyunLine); val n2 = min(2, lineCount(s, iw, 36 * U, bold))
                wrap(c, s, x0, r.centerY() + 13 * U - (n2 - 1) * 24 * U, 36 * U, FG, iw, 2, bold, lh = 1.3f) }
            v.mode == HyunView.Mode.THINKING -> text(c, "음… 생각 중이에요", x0, r.centerY() + 14 * U, 38 * U, FG, bold, maxW = iw)
            else -> {
                val of = m.prompt?.let { Triple("", it.first, it.second) } ?: m.offer()   // 진행 중인 놀이(퀴즈 문제) · 없으면 누가 탔는지 보고 고른 제안 하나
                if (slim) { val n2 = min(2, lineCount(of.second, iw, 34 * U, bold)); wrap(c, of.second, x0, r.centerY() + 12 * U - (n2 - 1) * 22 * U, 34 * U, FG, iw, 2, bold, lh = 1.3f) }
                else { text(c, of.second, x0, r.centerY() - 8 * U, fit(of.second, 38 * U, iw, bold), FG, bold, maxW = iw)
                text(c, of.third, x0, r.centerY() + 40 * U, 28 * U, MUTED, medium, maxW = iw) }
            }
        }
        // 대답을 기다리는 마이크 — 듣는 중이면 또렷하게
        if (!speaking) {
            val mx = r.right - 48 * U - 34 * U; val my = r.centerY()
            p.color = Glass.a(ACC, if (listening) 70 else 34); c.drawCircle(mx, my, 50 * U, p)
            p.color = if (listening) ACC else Glass.a(ACC, 200); c.drawCircle(mx, my, 34 * U, p)
            micGlyph(c, mx, my, 16 * U, Color.WHITE); p.style = Paint.Style.FILL; p.strokeWidth = 0f
            if (!slim) text(c, if (listening) "듣고 있어요" else "말로 대답", mx - 62 * U, my + 9 * U, 24 * U, if (listening) ACC else MUTED, medium, Paint.Align.RIGHT)
        }
    }

    private fun navMainScreen(c: Canvas, r: RectF, w: Float, h: Float) {
        if (glance()) { navGlance(c, r, w, h); return }
        navGlance(c, r); return   // 정차 중 내비 화면도 그린 지도 없이 실제 TBT 값만(사용자 11-14 — 지도는 실제 Pleos 내비). 아래 옛 지도 배치는 쓰지 않는다
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
        // 도착
        val t3 = RectF(L.left, t1.bottom + G, L.right, L.bottom)   // 차로 칸(실제 차로 정보 없이 그린 화살표)은 뺐다
        if (t3.height() > 100 * U) {
            box(c, t3)
            lab(c, "도착" + if (m.mockRoute) " · 모의 경로" else "", t3.left + 32 * U, t3.top + 40 * U)
            text(c, m.navEta.removePrefix("도착 "), t3.left + 32 * U, t3.top + 108 * U, 60 * U, FG, bold)
            text(c, (if (m.navMin > 0) Fmt.dur(m.navMin) + " · " else "") + m.navDest, t3.left + 32 * U, t3.top + 150 * U, 26 * U, MUTED, medium, maxW = t3.width() - 64 * U)
        }
        mapView(c, M)
    }

    /** 경로 지도(코드로 그림) — 차는 아래 가운데, 다음 회전 지점이 거리만큼 다가온다 */
    private fun mapView(c: Canvas, r: RectF, tagOn: Boolean = true) {
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
        if (tagOn) {
        // 이 지도는 회전 종류로 그린 도식(실제 도로 모양 아님) — 실제 경로여도 '경로 도식'이라 밝힌다(가짜 감사 #22)
        val tag = "${m.navDest}까지 ${m.navRemain}" + if (m.mockRoute) " · 모의 경로" else " · 경로 도식"
        p.textSize = 22 * U; p.typeface = medium
        val tr = RectF(r.left + 24 * U, r.top + 24 * U, r.left + 24 * U + p.measureText(tag) + 36 * U, r.top + 72 * U)
        p.color = if (darkUi) Glass.a(SURF, 230) else Glass.a(Color.WHITE, 235); c.drawRoundRect(tr, 24 * U, 24 * U, p)
        text(c, tag, tr.left + 18 * U, tr.centerY() + 8 * U, 22 * U, FG, medium)
        }
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
        text(c, s, r.left + 130 * U, r.centerY() + 9 * U, fit(s, 25 * U, r.width() - 160 * U, medium), FG, medium, maxW = r.width() - 160 * U)
    }
    private fun newsRow(c: Canvas, r: RectF) {
        box(c, r, "view:news")
        val n = leadNews()
        val x0 = r.left + 28 * U   // 사진 없이 제목만(시동 화면 간결하게)
        lab(c, "뉴스", x0, r.top + 40 * U)
        text(c, n ?: "헤드라인을 불러오는 중이에요", x0, r.top + 80 * U, 28 * U, FG, medium, maxW = r.right - x0 - 120 * U)
        text(c, "듣기", r.right - 32 * U, r.centerY() + 9 * U, 24 * U, ACC, semi, Paint.Align.RIGHT)
        if (n != null) hits += RectF(r.right - 96 * U, r.top, r.right, r.bottom) to "news:read"
    }
    /** 시동 화면 맨 위 — 어디로 갈까요? (지금 목적지 · 이 시간 자주 가는 곳 + 지금 출발 시 도착 · 없으면 말로 등록) */
    private fun goRow(c: Canvas, r: RectF) {
        box(c, r, m.goAction.ifBlank { null })   // 즐겨찾기를 누르면 그곳으로 안내 시작
        val x0 = r.left + 32 * U; val iw = r.width() - 64 * U
        icon(c, "pin", x0 + 16 * U, r.top + 34 * U, 24 * U, ACC)
        lab(c, "어디로 갈까요?" + (if (m.goTag.isNotBlank()) " · ${m.goTag}" else ""), x0 + 44 * U, r.top + 41 * U, iw * 0.5f)
        if (m.goName.isNotBlank()) {
            // 즐겨찾기 칩(오른쪽 위) — 눌러서 다른 도착지로
            if (m.favIdx >= 0 && m.favNames.size > 1) {
                p.textSize = 20 * U; p.typeface = medium
                var cx = r.right - 28 * U
                for (i in m.favNames.indices.reversed()) {
                    val s = m.favNames[i]; val cw = p.measureText(s) + 32 * U
                    val cr = RectF(cx - cw, r.top + 16 * U, cx, r.top + 56 * U)
                    val on = i == m.favIdx
                    p.shader = null; p.color = if (on) ACC else if (darkUi) SURF2 else Glass.a(Color.WHITE, 220); c.drawRoundRect(cr, 20 * U, 20 * U, p)
                    text(c, s, cr.left + 16 * U, cr.centerY() + 7 * U, 20 * U, if (on) Color.WHITE else FG, medium)
                    hits += cr to "fav:$i"; cx = cr.left - 10 * U
                }
            }
            text(c, m.goName, x0, r.top + 98 * U, fit(m.goName, 44 * U, iw - 160 * U, bold), FG, bold, maxW = iw - 160 * U)
            text(c, m.goSub, x0, r.top + 134 * U, fit(m.goSub, 24 * U, iw, medium), MUTED, medium, maxW = iw)   // '안내 시작'(위 줄)과 안 겹치는 줄 — 폭 전체(11-10: "거리 14...." 잘림)
            if (m.goAction.isNotBlank()) text(c, "안내 시작", r.right - 32 * U, r.top + 108 * U, 26 * U, ACC, semi, Paint.Align.RIGHT)
        } else {
            micGlyph(c, x0 + 16 * U, r.top + 84 * U, 30 * U, ACC)
            text(c, "오늘 가야 할 곳을 말로 등록해 주세요", x0 + 48 * U, r.top + 96 * U, fit("오늘 가야 할 곳을 말로 등록해 주세요", 36 * U, iw - 48 * U, bold), FG, bold, maxW = iw - 48 * U)
            lab(c, m.goSub, x0 + 48 * U, r.top + 130 * U, iw - 48 * U, MUTED)
        }
    }
    private fun questRow(c: Canvas, r: RectF) {
        box(c, r, "screen:log")
        val x0 = r.left + 28 * U
        lab(c, "퀘스트", x0, r.top + 38 * U)
        text(c, "급정거 없이 10km", x0, r.top + 78 * U, 28 * U, FG, medium)
        // 진행 = 마지막 급정거 뒤 달린 거리(예전엔 오늘 거리만 보고 급정거 조건·지급이 없었다 — 가짜 감사 #7). 성공하면 별 +20 실제 지급
        val pr = if (m.questDone) 1f else (m.questKm / 10.0).toFloat().coerceIn(0f, 1f)
        val bar = RectF(x0 + 300 * U, r.top + 66 * U, r.right - 280 * U, r.top + 76 * U)
        p.shader = null; p.color = SURF2; c.drawRoundRect(bar, 5 * U, 5 * U, p)
        p.color = if (m.questDone) GOOD else WARN; c.drawRoundRect(RectF(bar.left, bar.top, bar.left + max(10 * U, bar.width() * pr), bar.bottom), 5 * U, 5 * U, p)
        text(c, if (m.questDone) "성공 · +20 받음" else "%.1f / 10km · +20".format(m.questKm), r.right - 28 * U, r.top + 78 * U, 24 * U, if (m.questDone) GOOD else MUTED, medium, Paint.Align.RIGHT)
    }

    private fun stateScreen(c: Canvas, z: RectF) {
        val rows = mutableListOf<Pair<Float, (RectF) -> Unit>>()
        if (!glance()) m.story?.let { st -> rows += row(108 * U) { r -> storyRow(c, r, st) } }
        val st = m.status
        val soc = st?.soc?.v; val rng = st?.rangeKm?.v
        when (m.sit) {
            Sit.IGNITION -> {
                // 순서(칼리 11-09): 어디로 갈까요? → 차 컨디션 → 장·뉴스(같은 줄) → 운전 퀘스트. 날씨는 맨 위 줄(5° 맑음)에 있어 뺌
                rows += row(150 * U) { r -> goRow(c, r) }
                rows += row(150 * U) { r ->
                    box(c, r, "screen:car"); val x0 = r.left + 32 * U
                    lab(c, "차 컨디션", x0, r.top + 40 * U)
                    val cs = cols(RectF(x0, r.top, r.right - 32 * U, r.bottom), 1f, 1f)
                    val ok = st?.belt?.v != false && st?.doorOpen?.v != true
                    val why = when { st?.doorOpen?.v == true -> "운전석 문 열림"; st?.belt?.v == false -> "운전석 벨트 풀림"; else -> "벨트 · 문" }   // '확인'만 뜨면 뭘 볼지 몰랐다(11-10)
                    stat(c, cs[0].left, r.top + 98 * U, soc?.let { "$it%" } ?: "—", "주행 가능 ${rng ?: "-"}km", if (soc != null && soc < 30) WARN else FG, cs[0].width(), 48 * U)
                    stat(c, cs[1].left, r.top + 98 * U, if (ok) "OK" else "확인", why, if (ok) FG else WARN, cs[1].width(), 48 * U)
                }
                rows += row(130 * U) { r ->
                    val cs = cols(r, 1f, 2f)
                    box(c, cs[0], "view:stock")   // 코스피 · 나스닥 등락률 두 줄만
                    listOf("코스피", "나스닥").forEachIndexed { i, nm ->
                        val q = m.indices.firstOrNull { it.name.contains(nm) }
                        val y = cs[0].top + 54 * U + i * 48 * U
                        text(c, nm, cs[0].left + 24 * U, y, 22 * U, MUTED, medium)
                        text(c, q?.let { pct(it.ratio) } ?: "—", cs[0].right - 24 * U, y, 25 * U, q?.let { updown(it.ratio) } ?: FAINT, bold, Paint.Align.RIGHT)
                    }
                    newsRow(c, cs[1])
                }
                rows += row(100 * U) { r -> questRow(c, r) }
                if (m.novice) rows += row(76 * U) { r -> tipRow(c, r, "시트 → 미러 → 벨트, 빨간 경고등까지") }
            }
            Sit.READY -> {   // 기어 D(경로 없을 때) — 한눈: 목적지 · 도착 시각 · 말로 하라는 글 (+ 보조 한 줄). 버튼 없음
                rows += row(320 * U) { r ->
                    box(c, r); val x0 = r.left + 48 * U
                    // 예전엔 시간대 고정값("회사 · 23분" · "집 · 30분" · "자주 가는 카페 · 9분" — 가짜 감사 #9) → 실제 안내 · 저장한 즐겨찾기(예시 즐겨찾기 제외) · 없으면 "어디로 갈까요?"
                    val fav = m.goName.isNotBlank() && !m.goTag.contains("예시")
                    val (name, sub) = when {
                        m.navActive -> m.navDest to "${m.navEta} · ${Fmt.dur(m.navMin)}"
                        fav -> m.goName to m.goSub
                        else -> "어디로 갈까요?" to "목적지는 말로 정해요"
                    }
                    text(c, name, x0, r.top + 120 * U, fit(name, 72 * U, r.width() - 96 * U, bold), FG, bold, maxW = r.width() - 96 * U)
                    text(c, sub, x0, r.top + 180 * U, 36 * U, MUTED, medium, maxW = r.width() - 96 * U)
                    // 버튼 없음 — 경로가 생기면 저절로 내비 화면, 안내는 말로
                    micGlyph(c, x0 + 18 * U, r.bottom - 74 * U, 34 * U, MUTED)
                    text(c, if (m.navActive || fav) "\"${name}${ro(name)} 안내해 줘\" 하고 말하세요" else "\"코엑스 가자\"처럼 말하세요", x0 + 48 * U, r.bottom - 62 * U, 32 * U, MUTED, medium, maxW = r.width() - 140 * U)
                }
                val low = soc != null && soc < 30
                if (low) rows += row(100 * U) { r -> box(c, r, "screen:car"); text(c, "배터리 $soc% — 가는 길에 충전해요", r.left + 48 * U, r.centerY() + 12 * U, 34 * U, WARN, medium, maxW = r.width() - 96 * U) }
                else if (m.novice) rows += row(100 * U) { r -> box(c, r); text(c, "브레이크 꾹 → 주변 확인 → 깜빡이", r.left + 48 * U, r.centerY() + 12 * U, 34 * U, FG, medium, maxW = r.width() - 96 * U) }
            }
            Sit.STOP -> {   // 신호대기 — 한 장(목록 · 점수 · 라벨 없음). 예전 '동네 이야기'(위치와 무관한 고정 문장)는 뺐다 → 실제 다음 안내 또는 이번 주행
                val g = if (hasRoute()) "다음은 ${v.dist(m.navTurnM)} 앞에서 ${shortTurn(m.navTurnType, m.navTurn)}이에요. ${m.navTurn}" else "신호 대기 중이에요. 이번 주행 %.1fkm 달렸어요.".format(m.tripKm)
                val gl = min(4, lineCount(g, z.width() - 96 * U, 46 * U))
                rows += row(min(z.height(), 96 * U + gl * 61 * U + 40 * U)) { r ->
                    box(c, r); val x0 = r.left + 48 * U
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
                    lab(c, "쉬는 동안 · 오늘 인기 웹툰", x0, r.top + 40 * U)   // 예전 "웹툰 이어보기" — 인기 순위를 개인 이어보기처럼 보였다(가짜 감사 #10)
                    val items = m.toons.take(3)   // 넷이면 제목이 두 글자("괴력...")만 보였다 → 셋 · 제목 두 줄(11-10)
                    if (items.isEmpty()) text(c, "웹툰을 불러오는 중이에요", x0, r.top + 110 * U, 26 * U, MUTED, medium)
                    val cw = (r.width() - 64 * U) / 3
                    items.forEachIndexed { i, tn ->
                        val ix = x0 + i * cw
                        val th = RectF(ix, r.top + 62 * U, ix + (r.bottom - 22 * U - r.top - 62 * U) / 1.3f, r.bottom - 22 * U)
                        photoFit(c, tn.img, th, 10 * U)
                        val tw = cw - th.width() - 32 * U
                        wrap(c, tn.title, th.right + 14 * U, th.top + 30 * U, 22 * U, FG, tw, 2, medium, lh = 1.3f)
                        lab(c, "인기 ${i + 1}위", th.right + 14 * U, th.top + 94 * U, tw)
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
                    mini(c, cs[0], "내일", m.tomorrowLine.ifBlank { "불러오는 중" }, "내일 날씨 · Open-Meteo", "view:weather")   // 예시 일정("09:30 주간 회의")은 뺐다 — 일정 연동 전
                    val next = StageModel.CONCEPTS.filter { "concept:${it.id}" !in m.owned }.minByOrNull { it.price }
                    mini(c, cs[1], "꾸미기", next?.let { if (m.points >= it.price) "${it.name} 열 수 있어요" else "${it.name}까지 ${it.price - m.points}★" } ?: "전부 열었어요", "테마 · 소품 보기", "deco")
                }
            }
            Sit.DRIVE -> {}
        }
        ideaNow()?.let { id -> val (hh, d) = ideaLayout(c, id, z)   // 아이디어 장 — 그 장의 내용만(기본 화면의 '어디로 갈까요?'·차 컨디션 등은 1장에만 · 칼리 11-12)
            rows.clear()
            if (id == "bundle") {   // 묶음 장 — 주간 리포트 · 충전 계획 · 출발 전 루틴 · 동네 이야기를 한 장에(칼리 11-12)
                val st2 = m.status; val soc2 = st2?.soc?.v ?: 64; val rng2 = st2?.rangeKm?.v ?: soc2 * 5
                val beltOk = st2?.belt?.v != false
                val rh = min(230 * U, (z.height() - G) / 2)
                rows += row(rh) { r -> val cs = cols(r, 1f, 1f)
                    mini(c, cs[0], "주간 리포트 · 예시", "86.4km · ★ +142", "급정거 3 → 이번 주 목표 0", "screen:log")
                    mini(c, cs[1], "충전 계획", "$soc2% · ${rng2}km", if (rng2 >= 82) "일정 3곳 82km — 충분해요(예시)" else "일정 3곳 82km — 충전이 필요해요(예시)", "screen:car", if (rng2 >= 82) GOOD else WARN) }
                rows += row(rh) { r -> val cs = cols(r, 1f, 1f)
                    mini(c, cs[0], "출발 전 30초", if (beltOk) "시트 · 미러 · 벨트 ✓" else "벨트를 매 주세요", "시트 → 미러 → 벨트 순서로", null, if (beltOk) GOOD else WARN)
                    mini(c, cs[1], "동네 이야기", m.tiles.firstOrNull { it.icon == "chat" }?.value ?: LOCAL[0].first, "눌러서 더 보기", "view:local") }
            } else rows.add(0, row(hh, d)) }
        stack(c, z, rows)
    }

    // ---------- 취미 레이아웃 ----------
    private fun category(c: Canvas, z: RectF, k: String) {
        when (k) {
            "news" -> {
                val cs = cols(z, 1f, 1.25f)   // 목록 칸을 넓게(11-10: 제목이 다섯 글자에서 잘렸다)
                val lead = leadNews()
                val L = cs[0]
                box(c, L, "news:read")
                val ir = RectF(L.left, L.top, L.right, L.bottom - 150 * U)
                photoFit(c, lead?.let { m.newsImgs[it] }, ir, 24 * U, L)
                lab(c, "관심 뉴스 · 연합뉴스" + (lead?.let { m.newsTimes[it] }?.let { " $it" } ?: ""), L.left + 32 * U, ir.bottom + 40 * U)
                wrap(c, lead ?: "헤드라인을 불러오는 중이에요", L.left + 32 * U, ir.bottom + 84 * U, 30 * U, FG, L.width() - 64 * U, 2, medium, lh = 1.3f)
                val R = cs[1]; box(c, R); val x0 = R.left + 32 * U; val x1 = R.right - 32 * U
                lab(c, "헤드라인 · ${m.newsSource.substringBefore(" ·").ifBlank { "연합뉴스" }}", x0, R.top + 40 * U, R.width() - 64 * U)
                var y = R.top + 64 * U
                m.newsList.filter { it != lead }.forEachIndexed { i, n ->
                    if (y + 84 * U > R.bottom - 112 * U) return@forEachIndexed
                    p.color = LINE; c.drawRect(x0, y, x1, y + max(1f, U), p)
                    text(c, "${i + 2}", x0, y + 36 * U, 22 * U, FAINT, medium)
                    m.newsTimes[n]?.let { text(c, it, x1, y + 34 * U, 18 * U, FAINT, medium, Paint.Align.RIGHT) }
                    wrap(c, n, x0 + 36 * U, y + 36 * U, 22 * U, FG, x1 - x0 - 36 * U - 64 * U, 2, medium, lh = 1.3f)   // 두 줄까지
                    y += 84 * U
                }
                // 버튼 둘 다 카드 안 — 듣기는 서버 없이 앱이 읽고, 다음 소식은 첫 기사를 바꾼다(11-10: '다음 소식'이 카드 밖으로 삐져나와 눌리지도 않았다)
                val by = R.bottom - 32 * U - 60 * U; val bw = (x1 - x0 - 16 * U) / 2
                button(c, if (bw < 200 * U) "듣기" else "헤드라인 듣기", RectF(x0, by, x0 + bw, by + 60 * U), true, "news:read")   // 반반(좁은 칸)이면 짧게
                button(c, "다음 소식", RectF(x1 - bw, by, x1, by + 60 * U), false, "news:next")
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
                lab(c, "지금 재생 · 예시 곡(버튼은 차 미디어로)", x0, top.top + 52 * U, iw)
                text(c, "Morning Drive", x0, top.top + 116 * U, fit("Morning Drive", 52 * U, iw, bold), FG, bold, maxW = iw)
                text(c, "출근길 플레이리스트 · 3/12", x0, top.top + 156 * U, 24 * U, MUTED, medium, maxW = iw)
                val bar = RectF(x0, top.top + 196 * U, top.right - 40 * U, top.top + 204 * U)
                p.color = SURF2; c.drawRoundRect(bar, 4 * U, 4 * U, p); p.color = ACC; c.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * 0.38f, bar.bottom), 4 * U, 4 * U, p)
                text(c, "1:24", bar.left, bar.bottom + 28 * U, 18 * U, FAINT, medium); text(c, "3:41", bar.right, bar.bottom + 28 * U, 18 * U, FAINT, medium, Paint.Align.RIGHT)
                val by = top.bottom - 40 * U - 60 * U
                if (by > bar.bottom + 40 * U) {   // 세 버튼을 칸 폭에 맞춰(11-10: '다음'이 오른쪽 끝에서 잘렸다) · 누르면 차 미디어 키(이전 · 재생/정지 · 다음)
                    val gap = 12 * U; val bw = (top.right - 40 * U - x0 - gap * 2) / 3
                    button(c, "이전", RectF(x0, by, x0 + bw, by + 60 * U), false, "media:prev")
                    button(c, "재생", RectF(x0 + bw + gap, by, x0 + bw * 2 + gap, by + 60 * U), true, "media:play")   // 누르면 재생/정지 전환(알림으로 어느 쪽인지)
                    button(c, "다음", RectF(x0 + (bw + gap) * 2, by, x0 + bw * 3 + gap * 2, by + 60 * U), false, "media:next")
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
                val cw = tb.width() / (heads.size + 1); val ty = sb.top + 140 * U   // 팀 이름 칸은 두 칸 폭(11-10: "서울0 1 0…"처럼 붙었다)
                fun cx2(i: Int) = if (i == 0) tb.left + cw else tb.left + cw * (i + 1.5f)
                heads.forEachIndexed { i, s2 -> text(c, s2, cx2(i), ty, 20 * U, FAINT, medium, Paint.Align.CENTER) }
                rowsT.forEachIndexed { ri, row -> val yy = ty + 52 * U + ri * 48 * U; p.color = LINE; c.drawRect(tb.left, yy - 34 * U, tb.right, yy - 34 * U + max(1f, U), p)
                    row.forEachIndexed { i, s2 -> text(c, s2, cx2(i), yy, 22 * U, if (i == row.size - 1) FG else MUTED, if (i == row.size - 1) bold else medium, Paint.Align.CENTER) } }
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
                    icon(c, if (m.wcode in 0..1) "sun" else "cloud", r.right - 80 * U, r.top + 80 * U, 64 * U, if (m.wcode in 0..1) 0xFFFFB020.toInt() else MUTED) },   // 맑음엔 해(11-10: 맑음인데 구름)
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
                    // 예전 "출발 10분 전 히터 22°로 미리 켜 둘까요" — 예약 켜기는 없다(가짜 감사 #21). 설정 온도 바꾸기는 실제로 된다(11-11 에뮬 20→23°)
                    lab(c, "쌀쌀하면 \"23도로 올려 줘\" · 와이퍼가 켜지면 비 오는 날 안내로", x0, r.top + 116 * U, r.width() - 64 * U) }))
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
                    // 지금 고른 목적지까지 넣어 말한다 — "세탁물 찾으러 들렀다 가자"만 보내면 두뇌가 세탁소·목적지를 되물었다(11-10)
                    if (by2) button(c, "경유 추가", RectF(x1 - 170 * U, y + 22 * U, x1, y + 78 * U), true, "say:가는 길에 가까운 세탁소 들렀다가 ${m.goName.ifBlank { "집" }}${ro(m.goName.ifBlank { "집" })} 가자")
                }
            }
        }
    }

    // ======================================================================
    // 상황별 아이디어 30 + 특별한 순간 7(docs/hyun-content-ideas.md) — `--es idea <id>` 로 미리보기. 실제 값이 있으면 실제, 없으면 "예시"
    //  정차(시동·주차·시동 끔) = 상황 화면 맨 위 카드 · 출발 준비·신호대기 = 초점 카드 하나(나머지 숨김) · 주행 = 내비 오른쪽 위 칸 한 줄 + 말
    // ======================================================================
    private fun exTag(c: Canvas, r: RectF) = text(c, "예시", r.right - 28 * U, r.top + 38 * U, 20 * U, FAINT, medium, Paint.Align.RIGHT)
    private fun ideaNow(): String? = m.idea?.takeIf { StageModel.idea(it)?.sit == m.sit }
    /** 아이디어가 말로 하는 한 줄(캐릭터 캡션) */
    private fun ideaLine(id: String): String = when (id) {
        "air" -> "미세먼지 나빠요. 내기 순환으로 갈게요"; "fortune" -> "오늘의 차 운세 나왔어요"; "rival" -> "어제의 나를 이겨 봐요"; "routine" -> "출발 전 30초만요"
        "bundle" -> "출발 전에 한눈에 정리했어요"; "weekly" -> "지난주 정리했어요"; "charge" -> "오늘 배터리 넉넉해요"; "habit" -> "목요일 이 시간엔 헬스장이죠?"; "depart" -> "지금 가면 2분 늦어요"
        "crew" -> "같이 들을 노래로 바꿀게요"; "doors" -> "뒤 왼쪽 창이 열렸어요"; "launch" -> "깜빡이 좋아요, 천천히 출발"
        "combo" -> "콤보 3! 부드럽네요"; "brakecoach" -> "방금 정지 아주 부드러웠어요"; "drowsy" -> "창문 살짝 열까요?"; "alert" -> "앞에 호우특보예요, 천천히"
        "arrive10" -> "10분 남았어요"; "dj" -> "맑은 날 고른 노래 이어 갈게요"; "quiz" -> "신호 기다리는 동안 퀴즈 하나"; "score" -> "LG가 이기고 있어요"
        "signal" -> "여긴 보통 40초 기다려요"; "stretch" -> "어깨 으쓱 세 번"; "spot" -> "B2 34번, 기억했어요"; "highlight" -> "오늘의 명장면 뽑았어요"
        "wait" -> "기다리는 동안 쉬어요"; "nearby" -> "3분 거리에 괜찮은 곳 있어요"; "wash" -> "세차하기 좋은 날이에요"; "stamp" -> "오늘도 참 잘했어요"
        "morning" -> "내일 아침 히터 켜 둘까요?"; "leftbehind" -> "뒷좌석 다시 볼까요?"; "progress" -> "다음 소품까지 조금 남았어요"
        "first" -> "오늘 첫 주행이에요"; "long" -> "장거리 완주, 메달 드려요"; "night" -> "늦은 밤이에요. 천천히 가요"; "rain" -> "비 오는 날 특별 퀘스트예요"
        "birthday" -> "생일 축하해요"; "hundred" -> "100번째 주행이에요"; "star1000" -> "별 1,000개 모았어요"
        "kidquiz" -> "뒷자리 친구, 사자는 어떻게 울까?"; "date" -> "오늘은 조용히 분위기만 챙길게요"; "parent" -> "어머님, 편하게 모실게요"; "childlock" -> "뒷문 잠금 확인했어요"
        "holiday" -> "새벽에 출발하면 덜 막혀요"; "firstsnow" -> "첫눈이에요! 천천히 가요"; "blossom" -> "꽃길로 돌아갈까요?"; "heat" -> "먼저 창문부터 내려요"; "suneung" -> "수험생들 응원해요"
        "level" -> "${m.levelTitle}, 레벨 ${m.level}이에요"; "mood" -> "지금 기분은 ${m.moodLabel}"; "anniv" -> "우리 만난 지 100일이에요"; "memory" -> "그때 기억나요?"
        "cost" -> "이번 주 4천 원 아꼈어요"; "service" -> "엔진오일 교체 시기가 다가와요"; "tireseason" -> "다음 주 영하예요"; "ubi" -> "이 점수면 보험 할인 구간이에요"
        "sharecard" -> "이번 주 카드 만들었어요"; "league" -> "엄마가 1등이에요, 따라잡아요"; "commute" -> if (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) < 14) "출근길 편성 끝" else "퇴근길, 오늘도 수고했어요"; "parkhunt" -> "공영주차장이 더 가까워요"
        "schoolzone" -> "스쿨존이에요, 30 이하로"; "voicegame" -> "스무고개 할까요?"; "senior" -> "글씨를 크게 바꿀 수 있어요"; else -> "글레오가 저를 부를 수 있어요"
    }

    /** 정차 화면 카드 높이와 그리기 */
    private fun ideaLayout(c: Canvas, id: String, z: RectF): Pair<Float, (RectF) -> Unit> {
        val st = m.status
        return when (id) {
            "bundle" -> row(0f) { }   // 묶음 장은 stateScreen 이 여러 줄로 채운다
            "air" -> row(220 * U) { r -> box(c, r); exTag(c, r)
                val cx = r.left + 110 * U; val cy = r.centerY()
                p.color = Glass.a(0xFFF08C00.toInt(), 40); c.drawCircle(cx, cy, 72 * U, p); p.style = Paint.Style.STROKE; p.strokeWidth = 8 * U; p.color = 0xFFF08C00.toInt(); c.drawArc(RectF(cx - 72 * U, cy - 72 * U, cx + 72 * U, cy + 72 * U), 135f, 200f, false, p); p.style = Paint.Style.FILL
                text(c, "38", cx, cy + 14 * U, 52 * U, FG, bold, Paint.Align.CENTER); text(c, "㎍/㎥", cx, cy + 44 * U, 18 * U, MUTED, medium, Paint.Align.CENTER)
                val x0 = r.left + 220 * U
                text(c, "초미세먼지 나쁨", x0, r.top + 92 * U, 40 * U, FG, bold)
                text(c, "내기 순환 · ${st?.cabinC?.v?.toInt()?.let { "$it°" } ?: "—"}로 출발할게요", x0, r.top + 136 * U, 28 * U, MUTED, medium, maxW = r.right - x0 - 40 * U)
                text(c, "\"응\" 하면 공조를 맞춰요 · 에어코리아", x0, r.top + 176 * U, 22 * U, FAINT, medium) }
            "fortune" -> row(240 * U) { r -> box(c, r)
                val d = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_YEAR)
                val lane = listOf("1차선", "2차선", "3차선")[d % 3]; val song = listOf("90년대 발라드", "시티팝", "아이돌 데뷔곡", "영화 OST")[d % 4]; val snack = listOf("호두과자", "알감자", "소떡소떡", "핫바")[d % 4]
                lab(c, "오늘의 차 운세", r.left + 32 * U, r.top + 40 * U)
                val cs = cols(RectF(r.left + 32 * U, r.top, r.right - 200 * U, r.bottom), 1f, 1f, 1f)
                listOf("행운의 차선" to lane, "행운의 노래" to song, "행운의 간식" to snack).forEachIndexed { i, (a, b) -> lab(c, a, cs[i].left, r.top + 100 * U); text(c, b, cs[i].left, r.top + 150 * U, fit(b, 36 * U, cs[i].width() - 8 * U, bold), FG, bold) }
                c.save(); c.rotate(-12f, r.right - 110 * U, r.centerY()); p.style = Paint.Style.STROKE; p.strokeWidth = 5 * U; p.color = ACC; c.drawCircle(r.right - 110 * U, r.centerY(), 62 * U, p); p.style = Paint.Style.FILL
                text(c, "대길", r.right - 110 * U, r.centerY() + 16 * U, 46 * U, ACC, bold, Paint.Align.CENTER); c.restore()
                lab(c, "날짜로 뽑은 장난 운세", r.left + 32 * U, r.bottom - 26 * U, col = FAINT) }
            "rival" -> row(230 * U) { r -> box(c, r); exTag(c, r)
                lab(c, "어제의 나 vs 오늘의 나 · 급정거", r.left + 32 * U, r.top + 40 * U)
                text(c, "2", r.left + 120 * U, r.top + 160 * U, 110 * U, FAINT, bold, Paint.Align.CENTER); lab(c, "어제 출근", r.left + 82 * U, r.top + 196 * U)
                icon(c, "road", r.left + 250 * U, r.top + 120 * U, 40 * U, MUTED)
                text(c, "${m.harshN}", r.left + 380 * U, r.top + 160 * U, 110 * U, if (m.harshN == 0) GOOD else WARN, bold, Paint.Align.CENTER); lab(c, "오늘 지금까지", r.left + 330 * U, r.top + 196 * U)
                text(c, "0번이면 ★ +5", r.right - 40 * U, r.top + 140 * U, 40 * U, ACC, bold, Paint.Align.RIGHT) }
            "routine" -> row(220 * U) { r -> box(c, r)
                lab(c, "출발 전 30초 루틴", r.left + 32 * U, r.top + 40 * U)
                val steps = listOf("시트" to null, "미러" to null, "벨트" to st?.belt?.v)   // 시트·미러는 읽을 길이 없다 → 체크 없이 "확인해요"(예전엔 늘 ✓ — 가짜 감사 #14)
                val cs = cols(RectF(r.left + 32 * U, r.top + 70 * U, r.right - 32 * U, r.bottom - 30 * U), 1f, 1f, 1f)
                steps.forEachIndexed { i, (n, ok) -> val q = cs[i]; val cx = q.left + 50 * U
                    p.color = if (ok == true) GOOD else SURF2; c.drawCircle(cx, q.centerY(), 38 * U, p)
                    if (ok == null) text(c, "?", cx, q.centerY() + 14 * U, 40 * U, MUTED, bold, Paint.Align.CENTER)
                    if (ok == true) { p.style = Paint.Style.STROKE; p.strokeWidth = 6 * U; p.strokeCap = Paint.Cap.ROUND; p.color = Color.WHITE; c.drawLine(cx - 16 * U, q.centerY(), cx - 4 * U, q.centerY() + 13 * U, p); c.drawLine(cx - 4 * U, q.centerY() + 13 * U, cx + 18 * U, q.centerY() - 12 * U, p); p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT }
                    text(c, if (ok == null) "$n 확인" else n, cx + 60 * U, q.centerY() + 14 * U, 40 * U, FG, bold) } }
            "weekly" -> row(300 * U) { r -> box(c, r); exTag(c, r)
                lab(c, "지난주 리포트 · 월요일 첫 시동", r.left + 32 * U, r.top + 40 * U)
                text(c, "86.4km", r.left + 32 * U, r.top + 110 * U, 56 * U, FG, bold); text(c, "★ +142 · 급정거 3", r.left + 32 * U, r.top + 150 * U, 26 * U, MUTED, medium)
                val km = listOf(12f, 18f, 9f, 21f, 14f, 6f, 6.4f); val days = listOf("월", "화", "수", "목", "금", "토", "일")
                val ch = RectF(r.left + r.width() * 0.42f, r.top + 70 * U, r.right - 40 * U, r.bottom - 50 * U); val bw = ch.width() / 7
                km.forEachIndexed { i, v2 -> val hh = ch.height() * v2 / 21f; p.color = if (i == 3) ACC else Glass.a(ACC, 90); c.drawRoundRect(RectF(ch.left + i * bw + 8 * U, ch.bottom - hh, ch.left + (i + 1) * bw - 8 * U, ch.bottom), 6 * U, 6 * U, p)
                    text(c, days[i], ch.left + (i + 0.5f) * bw, ch.bottom + 30 * U, 20 * U, MUTED, medium, Paint.Align.CENTER) }
                text(c, "이번 주 목표 · 급정거 0", r.left + 32 * U, r.bottom - 36 * U, 26 * U, ACC, semi) }
            "charge" -> row(220 * U) { r -> box(c, r)
                val soc = st?.soc?.v ?: 64; val rng = st?.rangeKm?.v ?: soc * 5; val need = 82
                lab(c, "오늘 충전 계획", r.left + 32 * U, r.top + 40 * U)
                val bar = RectF(r.left + 32 * U, r.top + 80 * U, r.right - 32 * U, r.top + 124 * U)
                p.color = SURF2; c.drawRoundRect(bar, 22 * U, 22 * U, p); p.color = if (rng >= need) GOOD else WARN
                c.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * (soc / 100f), bar.bottom), 22 * U, 22 * U, p)
                val nx = bar.left + bar.width() * (need / max(1f, rng.toFloat() / soc * 100f)).coerceIn(0f, 1f); p.color = FG; c.drawRect(nx - 2 * U, bar.top - 10 * U, nx + 2 * U, bar.bottom + 10 * U, p)
                text(c, "$soc% · ${rng}km", bar.left, bar.bottom + 42 * U, 30 * U, FG, bold)
                text(c, if (rng >= need) "일정 3곳 ${need}km — 충분해요" else "일정 3곳 ${need}km — 가는 길에 충전", bar.right, bar.bottom + 42 * U, 26 * U, if (rng >= need) GOOD else WARN, semi, Paint.Align.RIGHT)
                lab(c, "일정 거리는 예시(폰 일정 연동 전)", bar.left, r.bottom - 20 * U, col = FAINT) }
            // ---- 출발 준비(D) — 초점 하나, 버튼 없음 ----
            "habit", "depart", "crew", "doors", "launch" -> row(min(360 * U, z.height())) { r -> box(c, r); val x0 = r.left + 48 * U
                when (id) {
                    "habit" -> { exTag(c, r); text(c, "헬스장", x0, r.top + 140 * U, 84 * U, FG, bold); text(c, "목요일 이 시간엔 늘 가셨죠 · 15분", x0, r.top + 200 * U, 36 * U, MUTED, medium)
                        micGlyph(c, x0 + 16 * U, r.bottom - 64 * U, 34 * U, ACC); text(c, "\"응\" 하면 바로 안내", x0 + 48 * U, r.bottom - 52 * U, 34 * U, ACC, medium) }
                    "depart" -> { exTag(c, r); text(c, "2분 늦어요", x0, r.top + 140 * U, 84 * U, WARN, bold); text(c, "9:30 주간 회의 · 지금 가면 9:32 도착", x0, r.top + 200 * U, 36 * U, MUTED, medium)
                        text(c, "다른 길이면 9:27 — \"다른 길\" 이라고 말해요", x0, r.bottom - 52 * U, 34 * U, ACC, medium) }
                    "crew" -> { val pas = st?.passenger?.v == true; if (!pas) exTag(c, r); icon(c, "chat", x0 + 40 * U, r.top + 116 * U, 64 * U, ACC)
                        text(c, "같이 들을 노래로", x0 + 100 * U, r.top + 140 * U, 72 * U, FG, bold); text(c, "조수석에 한 분 · 혼잣말은 줄일게요", x0, r.top + 210 * U, 36 * U, MUTED, medium)
                        text(c, "♪ 두 사람 플레이리스트", x0, r.bottom - 52 * U, 34 * U, ACC, medium) }
                    "doors" -> { val open = st?.windowOpen?.v == true; if (!open) exTag(c, r)
                        val car = RectF(x0, r.top + 50 * U, x0 + 150 * U, r.bottom - 50 * U)
                        p.style = Paint.Style.STROKE; p.strokeWidth = 5 * U; p.color = FG; c.drawRoundRect(car, 50 * U, 50 * U, p); p.style = Paint.Style.FILL
                        p.color = WARN; c.drawRoundRect(RectF(car.left - 10 * U, car.centerY() + 10 * U, car.left + 10 * U, car.centerY() + 80 * U), 6 * U, 6 * U, p)
                        text(c, "뒤 왼쪽 창", x0 + 200 * U, r.top + 150 * U, 72 * U, WARN, bold); text(c, "열려 있어요 — 닫고 출발해요", x0 + 200 * U, r.top + 210 * U, 40 * U, FG, medium) }
                    else -> { val turn = (st?.turn?.v ?: 0) != 0
                        listOf("깜빡이" to turn, "주변 확인" to true, "천천히" to false).forEachIndexed { i, (n, ok) -> val y = r.top + 100 * U + i * 90 * U
                            p.color = if (ok) GOOD else SURF2; c.drawCircle(x0 + 30 * U, y - 16 * U, 26 * U, p)
                            text(c, n + if (ok) " ✓" else "", x0 + 80 * U, y, 52 * U, if (ok) FG else MUTED, bold) } }
                } }
            // ---- 신호대기 — 카드 하나 ----
            "quiz", "score", "signal", "stretch" -> row(min(360 * U, z.height())) { r -> box(c, r); val x0 = r.left + 48 * U
                when (id) {
                    "quiz" -> { exTag(c, r); text(c, "Q.", x0, r.top + 110 * U, 72 * U, ACC, bold)
                        wrap(c, "'정자동'의 정자는 무엇에서 왔을까요?", x0 + 100 * U, r.top + 104 * U, 46 * U, FG, r.width() - 200 * U, 2, medium, lh = 1.3f)
                        text(c, "출발하면 정답을 말로 알려 줄게요", x0, r.bottom - 48 * U, 32 * U, MUTED, medium) }
                    "score" -> { exTag(c, r); text(c, "LG  3 : 2  두산", r.centerX(), r.top + 190 * U, fit("LG  3 : 2  두산", 96 * U, r.width() - 96 * U, bold), FG, bold, Paint.Align.CENTER)
                        text(c, "7회말 · 원 아웃", r.centerX(), r.top + 260 * U, 36 * U, MUTED, medium, Paint.Align.CENTER) }
                    "signal" -> { exTag(c, r); text(c, "보통 40초", x0, r.top + 190 * U, 110 * U, FG, bold); text(c, "이 교차로에서 기다린 평균이에요", x0, r.top + 260 * U, 36 * U, MUTED, medium) }
                    else -> { val tm = max(1, m.tripMin); text(c, "어깨 으쓱 ×3", x0, r.top + 180 * U, 96 * U, FG, bold)
                        text(c, "${tm / 60}시간 ${tm % 60}분째 운전 중".replace("0시간 ", ""), x0, r.top + 250 * U, 36 * U, MUTED, medium) }
                } }
            // ---- 주차 ----
            "spot" -> row(220 * U) { r -> box(c, r); exTag(c, r)
                val tag = RectF(r.left + 32 * U, r.top + 40 * U, r.left + 300 * U, r.bottom - 40 * U); p.color = ACC; c.drawRoundRect(tag, 24 * U, 24 * U, p)
                text(c, "B2 · 34", tag.centerX(), tag.centerY() + 26 * U, 72 * U, Color.WHITE, bold, Paint.Align.CENTER)
                text(c, "주차 위치 기억했어요", tag.right + 40 * U, r.top + 100 * U, 40 * U, FG, bold)
                text(c, "말로 받은 층·기둥 · 시동 끄면 폰에도 남겨요", tag.right + 40 * U, r.top + 146 * U, 24 * U, MUTED, medium, maxW = r.right - tag.right - 80 * U) }
            "highlight" -> row(330 * U) { r -> box(c, r)
                lab(c, "오늘의 주행 하이라이트", r.left + 32 * U, r.top + 40 * U)
                val ps = cols(RectF(r.left + 32 * U, r.top + 64 * U, r.right - 32 * U, r.bottom - 32 * U), 1f, 1.3f, 1f)
                val caps = listOf("출발" to "${m.tripMin.coerceAtLeast(1)}분 전", "오늘의 명장면" to "깜빡이 장인", "도착" to "★ ${sgn(m.tripPoints)}")
                ps.forEachIndexed { i, q -> p.color = SURF2; c.drawRoundRect(q, 14 * U, 14 * U, p); p.style = Paint.Style.STROKE; p.strokeWidth = 3 * U; p.color = FG; c.drawRoundRect(q, 14 * U, 14 * U, p); p.style = Paint.Style.FILL
                    val bot = v.asset(if (i == 1) "bot_cool.webp" else if (i == 0) "bot_driving.webp" else "bot_cheer.webp")
                    bot?.let { b -> val s2 = q.height() * 0.62f; c.drawBitmap(b, null, RectF(q.centerX() - s2 / 2, q.top + 12 * U, q.centerX() + s2 / 2, q.top + 12 * U + s2), bp) }
                    lab(c, caps[i].first, q.left + 16 * U, q.bottom - 52 * U); text(c, caps[i].second, q.left + 16 * U, q.bottom - 18 * U, 28 * U, FG, bold, maxW = q.width() - 32 * U) } }
            "wait" -> row(280 * U) { r -> glassCard(c, r, 24 * U)
                c.save(); c.clipPath(Path().apply { addRoundRect(r, 24 * U, 24 * U, Path.Direction.CW) })
                p.color = 0xFF1A120C.toInt(); c.drawRect(r, p)
                p.shader = RadialGradient(r.left + r.width() * 0.75f, r.bottom, r.height() * 1.1f, 0xCCFF8A3D.toInt(), 0x00FF8A3D, Shader.TileMode.CLAMP); c.drawRect(r, p); p.shader = null
                c.restore()
                text(c, "기다림 모드", r.left + 40 * U, r.top + 100 * U, 52 * U, 0xFFFFF1E0.toInt(), bold)
                text(c, "실내 ${st?.cabinC?.v?.toInt()?.let { "$it°" } ?: "—"} 유지 · 벽난로 장면 · 빗소리", r.left + 40 * U, r.top + 150 * U, 28 * U, 0xCCFFE6C8.toInt(), medium)   // 모르면 —(예전엔 23° 기본값 — 가짜 감사 #12)
                text(c, "\"기다림 모드\" 라고 말하면 켜져요", r.left + 40 * U, r.bottom - 40 * U, 24 * U, 0x99FFE6C8.toInt(), medium) }
            "nearby" -> row(220 * U) { r -> box(c, r); exTag(c, r)
                icon(c, "pin", r.left + 70 * U, r.centerY(), 64 * U, RED)
                text(c, "블루보틀 판교", r.left + 140 * U, r.top + 96 * U, 44 * U, FG, bold)
                text(c, "도보 3분 · 180m · ★ 4.6 · 지금 영업 중", r.left + 140 * U, r.top + 142 * U, 26 * U, MUTED, medium)
                lab(c, "카카오 로컬 연동 예정", r.left + 140 * U, r.top + 182 * U, col = FAINT) }
            "wash" -> row(220 * U) { r -> box(c, r); exTag(c, r)
                text(c, "세차하기 좋은 날", r.left + 32 * U, r.top + 80 * U, 44 * U, FG, bold)
                listOf("내일" to "맑음", "모레" to "맑음", "글피" to "구름").forEachIndexed { i, (d, w2) -> val x = r.left + 40 * U + i * 160 * U
                    icon(c, if (w2 == "맑음") "sun" else "cloud", x + 20 * U, r.top + 138 * U, 34 * U, if (w2 == "맑음") 0xFFFFB020.toInt() else MUTED); text(c, "$d $w2", x + 48 * U, r.top + 148 * U, 24 * U, FG, medium) }
                text(c, "셀프 세차장 1.2km", r.right - 32 * U, r.bottom - 36 * U, 26 * U, ACC, semi, Paint.Align.RIGHT) }
            // ---- 시동 끔 ----
            "stamp" -> row(260 * U) { r -> box(c, r)
                val cx = r.left + 140 * U; val cy = r.centerY()
                c.save(); c.rotate(-14f, cx, cy); p.style = Paint.Style.STROKE; p.strokeWidth = 7 * U; p.color = RED; c.drawCircle(cx, cy, 90 * U, p); c.drawCircle(cx, cy, 76 * U, p); p.style = Paint.Style.FILL
                text(c, "참", cx, cy - 6 * U, 44 * U, RED, bold, Paint.Align.CENTER); text(c, "잘했어요", cx, cy + 38 * U, 30 * U, RED, bold, Paint.Align.CENTER); c.restore()
                text(c, "오늘의 별 도장", r.left + 280 * U, r.top + 100 * U, 44 * U, FG, bold)
                text(c, "오늘 ${sgn(m.todayPoints)}★ · ${"%.1f".format(m.dayKmShown)}km 함께 달렸어요", r.left + 280 * U, r.top + 148 * U, 28 * U, MUTED, medium)
                text(c, "잘 자요. 내일 또 만나요", r.left + 280 * U, r.bottom - 48 * U, 28 * U, ACC, medium) }
            "morning" -> row(220 * U) { r -> box(c, r); exTag(c, r)
                text(c, "7:40", r.left + 32 * U, r.top + 140 * U, 96 * U, FG, bold)
                text(c, "내일 출발 · 7:30에 히터 켜 둘까요?", r.left + 300 * U, r.top + 100 * U, 32 * U, FG, medium, maxW = r.right - r.left - 340 * U)
                text(c, "\"응\" 하면 예약 · 평소 출발 시각으로 골랐어요", r.left + 300 * U, r.top + 146 * U, 24 * U, MUTED, medium, maxW = r.right - r.left - 340 * U) }
            "leftbehind" -> row(240 * U) { r -> box(c, r); val kid = st?.rearKid?.v == true; if (!kid) exTag(c, r)
                val car = RectF(r.left + 40 * U, r.top + 30 * U, r.left + 200 * U, r.bottom - 30 * U)
                p.style = Paint.Style.STROKE; p.strokeWidth = 4 * U; p.color = FG; c.drawRoundRect(car, 40 * U, 40 * U, p); p.style = Paint.Style.FILL
                val sw = 50 * U; val sh = 48 * U
                listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1).forEach { (cx2, cy2) -> val sr = RectF(car.left + 22 * U + cx2 * (sw + 16 * U), car.top + 30 * U + cy2 * (sh + 40 * U), 0f, 0f).also { it.right = it.left + sw; it.bottom = it.top + sh }
                    p.color = if (cy2 == 1 && cx2 == 0) WARN else SURF2; c.drawRoundRect(sr, 10 * U, 10 * U, p) }
                text(c, "뒷좌석 다시 볼까요?", r.left + 240 * U, r.top + 110 * U, 48 * U, FG, bold)
                text(c, "뒤 왼쪽에 누가 탔는데 그쪽 문이 안 열렸어요", r.left + 240 * U, r.top + 160 * U, 26 * U, MUTED, medium, maxW = r.right - r.left - 280 * U) }
            "progress" -> row(220 * U) { r -> box(c, r)
                val next = StageModel.ACC_DEFS.filter { "acc:${it.id}" !in m.owned }.minByOrNull { it.price } ?: StageModel.ACC_DEFS.first { it.id == "santa_hat" }
                val left = (next.price - m.points).coerceAtLeast(0)
                v.asset("acc_${next.id}.webp")?.let { b -> c.drawBitmap(b, null, RectF(r.left + 40 * U, r.top + 40 * U, r.left + 180 * U, r.top + 180 * U), bp) }
                text(c, if (left == 0) "${next.name} 살 수 있어요" else "${next.name}까지 ★ $left", r.left + 220 * U, r.top + 92 * U, 40 * U, FG, bold)
                val bar = RectF(r.left + 220 * U, r.top + 124 * U, r.right - 40 * U, r.top + 140 * U); p.color = SURF2; c.drawRoundRect(bar, 8 * U, 8 * U, p)
                p.color = WARN; c.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * (m.points.toFloat() / next.price).coerceIn(0.03f, 1f), bar.bottom), 8 * U, 8 * U, p)
                text(c, "부드럽게 3번만 더 달리면 돼요", r.left + 220 * U, r.top + 180 * U, 26 * U, MUTED, medium) }
            // ---- 특별한 순간 ----
            "first" -> row(220 * U) { r -> box(c, r)
                val cal = java.util.Calendar.getInstance(); val wd = listOf("일", "월", "화", "수", "목", "금", "토")[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]
                text(c, "오늘 첫 주행", r.left + 32 * U, r.top + 92 * U, 52 * U, FG, bold)
                text(c, "${cal.get(java.util.Calendar.MONTH) + 1}월 ${cal.get(java.util.Calendar.DAY_OF_MONTH)}일 ${wd}요일 · 오늘의 소품은 넥타이", r.left + 32 * U, r.top + 140 * U, 28 * U, MUTED, medium)
                v.asset("acc_necktie.webp")?.let { b -> c.drawBitmap(b, null, RectF(r.right - 160 * U, r.top + 30 * U, r.right - 60 * U, r.bottom - 30 * U), bp) } }
            "long" -> row(300 * U) { r -> box(c, r); exTag(c, r)
                val cx = r.left + 130 * U; val cy = r.top + 140 * U
                p.color = 0xFFFFC94A.toInt(); c.drawCircle(cx, cy, 80 * U, p); p.color = 0xFFE0A22A.toInt(); c.drawCircle(cx, cy, 64 * U, p)
                text(c, "완주", cx, cy + 16 * U, 44 * U, Color.WHITE, bold, Paint.Align.CENTER)
                text(c, "장거리 완주 메달", r.left + 260 * U, r.top + 90 * U, 44 * U, FG, bold); text(c, "2시간 14분 · 162km · 쉼표 2번", r.left + 260 * U, r.top + 134 * U, 28 * U, MUTED, medium)
                lab(c, "들렀던 휴게소", r.left + 260 * U, r.top + 190 * U); text(c, "안성 · 호두과자   /   망향 · 우동", r.left + 260 * U, r.top + 230 * U, 28 * U, FG, medium) }
            "rain" -> row(220 * U) { r -> box(c, r, null)
                umbrella(c, r.left + 110 * U, r.top + 40 * U, 220 * U)
                text(c, "비 오는 날 특별 퀘스트", r.left + 240 * U, r.top + 92 * U, 44 * U, FG, bold)
                text(c, "앞차와 간격 두 배로 10km — ★ +10", r.left + 240 * U, r.top + 138 * U, 30 * U, ACC, semi)
                text(c, "지금 ${m.weatherShort.ifBlank { "비" }} · 와이퍼 신호로 비를 알아요", r.left + 240 * U, r.top + 180 * U, 24 * U, MUTED, medium) }
            "birthday" -> row(260 * U) { r -> box(c, r)
                val cx = r.left + 130 * U; val by = r.bottom - 50 * U
                p.color = 0xFFFFD6E0.toInt(); c.drawRoundRect(RectF(cx - 90 * U, by - 90 * U, cx + 90 * U, by), 18 * U, 18 * U, p)
                p.color = 0xFFFF7AA2.toInt(); c.drawRoundRect(RectF(cx - 90 * U, by - 90 * U, cx + 90 * U, by - 64 * U), 14 * U, 14 * U, p)
                for (i in -1..1) { p.color = 0xFF8EC5FF.toInt(); c.drawRect(cx + i * 40 * U - 5 * U, by - 140 * U, cx + i * 40 * U + 5 * U, by - 90 * U, p); p.color = 0xFFFFB020.toInt(); c.drawCircle(cx + i * 40 * U, by - 150 * U, 10 * U, p) }
                text(c, "생일 축하해요" + (v.name.takeIf { it.isNotBlank() }?.let { ", ${it}님" } ?: ""), r.left + 260 * U, r.top + 100 * U, 48 * U, FG, bold)
                wrap(c, "오늘 운전은 별 2배 · 축하 노래 한 소절 들려 드릴게요", r.left + 260 * U, r.top + 146 * U, 24 * U, MUTED, r.right - r.left - 300 * U, 2, medium, lh = 1.3f)
                lab(c, "생일은 온보딩에서 물어볼 자리(예시)", r.left + 260 * U, r.bottom - 36 * U, col = FAINT) }
            "hundred" -> row(240 * U) { r -> box(c, r); exTag(c, r)
                text(c, "100", r.left + 40 * U, r.top + 170 * U, 140 * U, ACC, bold)
                text(c, "번째 주행", r.left + 330 * U, r.top + 120 * U, 44 * U, FG, bold)
                text(c, "처음 만난 날부터 1,284km · 기념 카드가 기록에 남아요", r.left + 330 * U, r.top + 166 * U, 26 * U, MUTED, medium, maxW = r.right - r.left - 370 * U) }
            // ================= 2차 추천 25 =================
            // ---- 동승자 ----
            "date" -> row(240 * U) { r -> glassCard(c, r, 24 * U)
                c.save(); c.clipPath(Path().apply { addRoundRect(r, 24 * U, 24 * U, Path.Direction.CW) })
                p.shader = LinearGradient(r.left, r.top, r.right, r.bottom, 0xFF2A1638.toInt(), 0xFF5A2346.toInt(), Shader.TileMode.CLAMP); c.drawRect(r, p); p.shader = null
                p.shader = RadialGradient(r.right - 120 * U, r.top + 60 * U, 180 * U, 0x66FF7AA2, 0x00FF7AA2, Shader.TileMode.CLAMP); c.drawRect(r, p); p.shader = null; c.restore()
                val pas = st?.passenger?.v == true
                text(c, "데이트 모드", r.left + 40 * U, r.top + 96 * U, 52 * U, 0xFFFFE8F0.toInt(), bold)
                text(c, "무드등 · 혼잣말 끔 · 오늘 같이 들은 노래를 남겨요", r.left + 40 * U, r.top + 146 * U, 28 * U, 0xCCFFE8F0.toInt(), medium, maxW = r.width() - 80 * U)
                text(c, if (pas) "조수석 착석 감지 · 저녁 ${java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)}시" else "예시 · 조수석 착석 + 저녁에 켜져요", r.left + 40 * U, r.bottom - 36 * U, 22 * U, 0x99FFE8F0.toInt(), medium) }
            "parent", "childlock", "memory" -> row(min(360 * U, z.height())) { r -> box(c, r); val x0 = r.left + 48 * U
                when (id) {
                    "parent" -> { exTag(c, r); text(c, "부모님 모드", x0, r.top + 130 * U, 80 * U, FG, bold); text(c, "글자 크게 · 말은 천천히 · 급정거 경고 일찍", x0, r.top + 196 * U, 36 * U, MUTED, medium)
                        text(c, "어머니 모시는 날 · 별 2배", x0, r.bottom - 52 * U, 36 * U, ACC, semi) }
                    "childlock" -> { val kid = st?.rearKid?.v == true; if (!kid) exTag(c, r)
                        text(c, "뒷문 잠금 됐어요", x0, r.top + 130 * U, 76 * U, GOOD, bold); text(c, "뒷좌석에 아이 · 창문 잠금도 켤까요?", x0, r.top + 196 * U, 36 * U, MUTED, medium)
                        micGlyph(c, x0 + 16 * U, r.bottom - 64 * U, 34 * U, ACC); text(c, "\"응\" 하면 창문 잠금", x0 + 48 * U, r.bottom - 52 * U, 34 * U, ACC, medium) }
                    else -> { exTag(c, r); text(c, "그때 기억나요?", x0, r.top + 120 * U, 72 * U, FG, bold); text(c, "지난번 여기 올 땐 비가 왔죠 · 10월 3일", x0, r.top + 186 * U, 36 * U, MUTED, medium)
                        text(c, "♪ 그날 들은 노래 다시 틀까요", x0, r.bottom - 52 * U, 36 * U, ACC, medium) }
                } }
            // ---- 계절 ----
            "holiday" -> row(286 * U) { r -> box(c, r); exTag(c, r)
                lab(c, "추석 귀성길 플래너 · 3일 전", r.left + 32 * U, r.top + 40 * U)
                val slots = listOf("새벽 5시" to 4.2f, "오전 9시" to 6.5f, "오후 2시" to 5.8f, "밤 10시" to 4.6f)
                val bw = (r.width() - 64 * U) / 4
                slots.forEachIndexed { i, (t2, hr) -> val x = r.left + 32 * U + i * bw; val best = i == 0
                    val bh = 110 * U * hr / 6.5f; p.color = if (best) ACC else Glass.a(ACC, 70); c.drawRoundRect(RectF(x + 12 * U, r.top + 190 * U - bh, x + bw - 12 * U, r.top + 190 * U), 8 * U, 8 * U, p)
                    text(c, "${hr}시간", x + bw / 2, r.top + 190 * U - bh - 10 * U, 22 * U, if (best) ACC else MUTED, semi, Paint.Align.CENTER); text(c, t2, x + bw / 2, r.top + 222 * U, 22 * U, FG, medium, Paint.Align.CENTER) }
                text(c, "새벽 5시 출발이 2시간 덜 막혀요 · 휴게소 간식: 안성 호두과자", r.left + 32 * U, r.bottom - 22 * U, 20 * U, FAINT, medium, maxW = r.width() - 64 * U) }
            "firstsnow" -> row(260 * U) { r -> box(c, r)
                val cx = r.left + 110 * U; val by = r.bottom - 40 * U
                p.color = Color.WHITE; c.drawCircle(cx, by - 56 * U, 56 * U, p); c.drawCircle(cx, by - 140 * U, 40 * U, p)
                p.style = Paint.Style.STROKE; p.strokeWidth = 2 * U; p.color = LINE; c.drawCircle(cx, by - 56 * U, 56 * U, p); c.drawCircle(cx, by - 140 * U, 40 * U, p); p.style = Paint.Style.FILL
                p.color = 0xFF111827.toInt(); c.drawCircle(cx - 12 * U, by - 146 * U, 4 * U, p); c.drawCircle(cx + 12 * U, by - 146 * U, 4 * U, p); p.color = 0xFFFF8A3D.toInt(); c.drawCircle(cx, by - 136 * U, 5 * U, p)
                p.color = RED; c.drawRoundRect(RectF(cx - 44 * U, by - 108 * U, cx + 44 * U, by - 94 * U), 7 * U, 7 * U, p)
                text(c, "올해 첫눈이에요", r.left + 240 * U, r.top + 92 * U, 52 * U, FG, bold)
                wrap(c, "첫눈 운전 5km 급정거 없이 → 한정 소품 '눈사람'", r.left + 240 * U, r.top + 136 * U, 26 * U, ACC, r.width() - 280 * U, 2, semi, lh = 1.3f)
                text(c, "앞차 간격 두 배 · 브레이크는 미리, 살살", r.left + 240 * U, r.top + 222 * U, fit("앞차 간격 두 배 · 브레이크는 미리, 살살", 24 * U, r.width() - 280 * U, medium), MUTED, medium, maxW = r.width() - 280 * U) }
            "blossom" -> row(240 * U) { r -> box(c, r); exTag(c, r)
                val rr = java.util.Random(4); for (i in 0 until 26) { p.color = Glass.a(0xFFFF9EC0.toInt(), 120 + rr.nextInt(100)); c.drawCircle(r.right - 260 * U + rr.nextFloat() * 240 * U, r.top + 20 * U + rr.nextFloat() * (r.height() - 40 * U), (5 + rr.nextInt(7)) * U, p) }
                text(c, "벚꽃 드라이브 코스", r.left + 32 * U, r.top + 92 * U, 48 * U, FG, bold)
                text(c, "윤중로보다 덜 붐비는 탄천길 · 20분 돌아가요", r.left + 32 * U, r.top + 140 * U, 28 * U, MUTED, medium)
                text(c, "\"꽃길로 가자\" 하면 경로를 바꿔요", r.left + 32 * U, r.bottom - 36 * U, 26 * U, ACC, semi) }
            "heat" -> row(240 * U) { r -> box(c, r)
                val t2 = m.tempNow ?: 34
                text(c, "${max(t2, 33)}°", r.left + 32 * U, r.top + 150 * U, 110 * U, RED, bold)
                text(c, "폭염 출발 루틴", r.left + 280 * U, r.top + 80 * U, 40 * U, FG, bold)
                listOf("창문 내리고 30초 환기", "에어컨 내기 순환", "핸들이 뜨거우면 잠깐 기다려요").forEachIndexed { i, s2 -> text(c, "${i + 1}. $s2", r.left + 280 * U, r.top + 124 * U + i * 36 * U, 26 * U, if (i == 0) ACC else MUTED, medium) }
                if (m.tempNow == null || m.tempNow!! < 33) lab(c, "기온 예시(33° 이상일 때 켜져요)", r.left + 32 * U, r.bottom - 20 * U, col = FAINT) }
            "suneung" -> row(260 * U) { r -> box(c, r)
                c.save(); c.rotate(-8f, r.left + 120 * U, r.centerY())
                path.reset(); path.moveTo(r.left + 50 * U, r.centerY() - 60 * U); path.lineTo(r.left + 200 * U, r.centerY()); path.lineTo(r.left + 50 * U, r.centerY() + 60 * U); path.close()
                p.color = RED; c.drawPath(path, p); text(c, "합격", r.left + 96 * U, r.centerY() + 12 * U, 34 * U, Color.WHITE, bold, Paint.Align.CENTER); c.restore()
                text(c, "수능 응원 모드", r.left + 250 * U, r.top + 92 * U, 52 * U, FG, bold)
                text(c, "경적 금지 · 8:10 입실 · 시험장 우회", r.left + 250 * U, r.top + 142 * U, fit("경적 금지 · 8:10 입실 · 시험장 우회", 28 * U, r.width() - 290 * U, medium), MUTED, medium, maxW = r.width() - 290 * U)
                text(c, "오늘 시험 보는 모든 수험생, 응원해요", r.left + 250 * U, r.top + 192 * U, fit("오늘 시험 보는 모든 수험생, 응원해요", 28 * U, r.width() - 290 * U, semi), ACC, semi, maxW = r.width() - 290 * U)
                lab(c, "11월 셋째 목요일 · 시험장 위치는 공공데이터 연동 예정", r.left + 250 * U, r.bottom - 18 * U, col = FAINT) }
            // ---- 캐릭터 ----
            "level" -> row(330 * U) { r -> box(c, r)
                val lv = m.level; val lo = m.xpFor(lv); val hi = m.xpFor(lv + 1); val pr = if (lv >= 10) 1f else ((m.xp - lo).toFloat() / (hi - lo)).coerceIn(0f, 1f)
                val cx = r.left + 130 * U; val cy = r.centerY()
                p.style = Paint.Style.STROKE; p.strokeWidth = 14 * U; p.strokeCap = Paint.Cap.ROUND; p.color = SURF2; c.drawCircle(cx, cy, 92 * U, p)
                p.color = ACC; c.drawArc(RectF(cx - 92 * U, cy - 92 * U, cx + 92 * U, cy + 92 * U), -90f, 360f * pr, false, p); p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
                text(c, "Lv.$lv", cx, cy + 22 * U, 60 * U, FG, bold, Paint.Align.CENTER)
                val x0 = r.left + 270 * U
                text(c, m.levelTitle, x0, r.top + 96 * U, 48 * U, FG, bold)
                text(c, if (lv >= 10) "최고 레벨이에요" else "다음 레벨 '${StageModel.LEVELS[lv]}'까지 ★ ${hi - m.xp}", x0, r.top + 142 * U, 28 * U, MUTED, medium)
                lab(c, "레벨마다 열리는 것", x0, r.top + 196 * U)
                listOf(2 to "웃는 말버릇", 4 to "콧노래 표정", 6 to "운전 칭찬 노래", 8 to "한정 테마").forEachIndexed { i, (l2, n2) -> val on = lv >= l2
                    text(c, "Lv.$l2 $n2", x0 + i * 170 * U, r.top + 236 * U, 22 * U, if (on) ACC else FAINT, if (on) semi else medium) }
                lab(c, "모은 별 ${m.xp} = 경험치 · 실제 주행으로 오름", x0, r.bottom - 26 * U, col = FAINT) }
            "mood" -> row(300 * U) { r -> box(c, r)
                val face = when { m.mood >= 75 -> "humming"; m.mood < 40 -> "worried"; else -> "neutral" }
                v.asset("bot_$face.webp")?.let { b -> c.drawBitmap(b, null, RectF(r.left + 30 * U, r.top + 40 * U, r.left + 250 * U, r.top + 260 * U), bp) }
                val x0 = r.left + 290 * U
                text(c, "지금 기분 · ${m.moodLabel}", x0, r.top + 90 * U, 48 * U, FG, bold)
                val bar = RectF(x0, r.top + 120 * U, r.right - 40 * U, r.top + 140 * U)
                p.shader = LinearGradient(bar.left, 0f, bar.right, 0f, intArrayOf(0xFFE5484D.toInt(), 0xFFF59F00.toInt(), 0xFF12B886.toInt()), null, Shader.TileMode.CLAMP); c.drawRoundRect(bar, 10 * U, 10 * U, p); p.shader = null
                val mx = bar.left + bar.width() * m.mood / 100f; p.color = FG; c.drawCircle(mx, bar.centerY(), 14 * U, p); p.color = SURF; c.drawCircle(mx, bar.centerY(), 7 * U, p)
                text(c, "긴장", bar.left, bar.bottom + 30 * U, 20 * U, MUTED, medium); text(c, "콧노래", bar.right, bar.bottom + 30 * U, 20 * U, MUTED, medium, Paint.Align.RIGHT)
                text(c, "마지막 변화: ${m.moodWhy.ifBlank { "아직 없음" }}", x0, r.top + 216 * U, 26 * U, FG, medium)
                lab(c, "급정거 −15 · 깜빡이 없이 회전 −8 · 부드럽게 1km +3 · 크루즈 +5 (실제 주행 신호)", x0, r.bottom - 28 * U, r.right - x0 - 32 * U, FAINT) }
            "anniv" -> row(240 * U) { r -> box(c, r); exTag(c, r)
                text(c, "D+100", r.left + 32 * U, r.top + 150 * U, 96 * U, ACC, bold)
                text(c, "우리 만난 지 100일", r.left + 360 * U, r.top + 96 * U, fit("우리 만난 지 100일", 44 * U, r.width() - 400 * U, bold), FG, bold, maxW = r.width() - 400 * U)
                wrap(c, "함께 1,284km · 별 ${m.xp}개 · 오늘은 기념 사진 컷", r.left + 360 * U, r.top + 138 * U, 24 * U, MUTED, r.width() - 400 * U, 2, medium, lh = 1.3f)
                text(c, "\"고마워\" 라고 말해 주면 답장해요", r.left + 360 * U, r.bottom - 36 * U, 24 * U, ACC, semi) }
            // ---- 차 관리 ----
            "cost" -> row(260 * U) { r -> box(c, r); exTag(c, r)
                lab(c, "이번 주 충전·주유비", r.left + 32 * U, r.top + 40 * U)
                text(c, "32,400원", r.left + 32 * U, r.top + 120 * U, 64 * U, FG, bold)
                text(c, "지난주보다 4,100원 아꼈어요 · 에코 모드 덕분", r.left + 32 * U, r.top + 166 * U, 26 * U, GOOD, semi)
                val wk = listOf(36500f, 32400f); val bx = r.right - 260 * U
                wk.forEachIndexed { i, v2 -> val hh = 140 * U * v2 / 40000f; p.color = if (i == 1) ACC else SURF2; c.drawRoundRect(RectF(bx + i * 110 * U, r.bottom - 50 * U - hh, bx + i * 110 * U + 80 * U, r.bottom - 50 * U), 8 * U, 8 * U, p)
                    text(c, if (i == 0) "지난주" else "이번 주", bx + i * 110 * U + 40 * U, r.bottom - 20 * U, 20 * U, MUTED, medium, Paint.Align.CENTER) }
                lab(c, "주행 거리 ✅ · 단가는 공공 충전 요금 연동 예정", r.left + 32 * U, r.bottom - 26 * U, col = FAINT) }
            "service" -> row(330 * U) { r -> box(c, r)   // 엔진오일 교체 알림 — 누적 주행거리 기준(마지막 교체 뒤 달린 거리 / 권장 주기)
                val left = m.oilLeftKm; val demo = m.oilDemo
                if (demo) exTag(c, r)
                val x0 = r.left + 40 * U; val iw = r.width() - 80 * U
                icon(c, "warn", x0 + 22 * U, r.top + 62 * U, 40 * U, WARN)
                text(c, if (left <= 0) "엔진오일 교체할 때가 됐어요" else "엔진오일 교체 시기가 다가와요", x0 + 58 * U, r.top + 76 * U, 38 * U, FG, bold, maxW = iw - 120 * U)
                val used = (StageModel.OIL_KM - left).coerceAtLeast(0)
                text(c, if (left <= 0) "권장 주기를 넘겼어요" else "${"%,d".format(left)}km 남았어요", x0, r.top + 150 * U, 56 * U, if (left <= 300) WARN else FG, bold)
                text(c, "마지막 교체 뒤 ${"%,d".format(used)}km · 권장 ${"%,d".format(StageModel.OIL_KM)}km", x0, r.top + 196 * U, 24 * U, MUTED, medium, maxW = iw)
                val bar = RectF(x0, r.top + 222 * U, r.right - 40 * U, r.top + 236 * U)
                p.shader = null; p.style = Paint.Style.FILL; p.color = SURF2; c.drawRoundRect(bar, 7 * U, 7 * U, p)
                p.color = WARN; c.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * (used.toFloat() / StageModel.OIL_KM).coerceIn(0.03f, 1f), bar.bottom), 7 * U, 7 * U, p)
                text(c, "갈고 나면 \"엔진오일 갈았어\"라고 말해 주세요", x0, r.bottom - 34 * U, 22 * U, FAINT, medium, maxW = iw - 250 * U)
                button(c, "주말에 다시 알림", RectF(r.right - 40 * U - 230 * U, r.bottom - 76 * U, r.right - 40 * U, r.bottom - 22 * U), false, "oil:later") }
            "tireseason" -> row(230 * U) { r -> box(c, r); exTag(c, r)
                val cx = r.left + 110 * U; val cy = r.centerY()
                p.color = 0xFF2B2F36.toInt(); c.drawCircle(cx, cy, 80 * U, p); p.color = 0xFF9AA3B2.toInt(); c.drawCircle(cx, cy, 36 * U, p)
                p.color = 0xFFE6EDF3.toInt(); for (i in 0 until 8) { val a = Math.toRadians(i * 45.0); c.drawCircle(cx + (60 * U * Math.cos(a)).toFloat(), cy + (60 * U * Math.sin(a)).toFloat(), 6 * U, p) }
                text(c, "다음 주 영하 −4°", r.left + 230 * U, r.top + 92 * U, 48 * U, FG, bold)
                text(c, "겨울 타이어로 바꿀 때예요 · 예약할까요?", r.left + 230 * U, r.top + 140 * U, 28 * U, MUTED, medium)
                text(c, "\"타이어 예약\" 이라고 말해요", r.left + 230 * U, r.bottom - 36 * U, 26 * U, ACC, semi) }
            "ubi" -> row(250 * U) { r -> box(c, r); exTag(c, r)
                val score = (100 - m.harshN * 5).coerceIn(60, 100) - 8
                text(c, "$score", r.left + 32 * U, r.top + 150 * U, 110 * U, ACC, bold); lab(c, "운전점수", r.left + 40 * U, r.top + 190 * U)
                text(c, "보험 할인 예상 약 10%", r.left + 300 * U, r.top + 96 * U, 44 * U, FG, bold)
                val bar = RectF(r.left + 300 * U, r.top + 130 * U, r.right - 40 * U, r.top + 146 * U); p.color = SURF2; c.drawRoundRect(bar, 8 * U, 8 * U, p)
                p.color = GOOD; c.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * ((score - 60) / 40f), bar.bottom), 8 * U, 8 * U, p)
                text(c, "70점부터 할인 구간 · 급정거 줄이면 더 올라가요", r.left + 300 * U, r.top + 186 * U, 24 * U, MUTED, medium)
                lab(c, "할인율은 보험사 기준 연동 전 예시", r.left + 300 * U, r.bottom - 20 * U, col = FAINT) }
            // ---- 공유 ----
            "sharecard" -> row(min(420 * U, z.height())) { r -> box(c, r)
                val card = RectF(r.left + 24 * U, r.top + 24 * U, r.left + 24 * U + (r.height() - 48 * U) * 0.8f, r.bottom - 24 * U)
                p.color = if (skinned) sk.colors.bg else 0xFFFFF3D6.toInt(); c.drawRoundRect(card, 16 * U, 16 * U, p)
                p.color = if (skinned) sk.style.patternColor else 0xFFF2D9A0.toInt(); var yy = card.top + 10 * U; var row2 = 0; while (yy < card.bottom) { var xx = card.left + (if (row2 % 2 == 0) 10 * U else 20 * U); while (xx < card.right) { c.drawCircle(xx, yy, 2.4f * U, p); xx += 20 * U }; yy += 18 * U; row2++ }
                p.style = Paint.Style.STROKE; p.strokeWidth = 4 * U; p.color = 0xFF16120E.toInt(); c.drawRoundRect(card, 16 * U, 16 * U, p); p.style = Paint.Style.FILL
                val tf2 = if (skinned) sk.fonts.title else bold
                text(c, "이번 주 현비서", card.left + 20 * U, card.top + 50 * U, 30 * U, 0xFF16120E.toInt(), tf2)
                v.asset("bot_cool.webp")?.let { b -> c.drawBitmap(b, null, RectF(card.centerX() - 70 * U, card.top + 70 * U, card.centerX() + 70 * U, card.top + 210 * U), bp) }
                text(c, "%.1fkm".format(max(m.dayKmShown, 86.4)), card.left + 20 * U, card.bottom - 86 * U, 40 * U, 0xFF16120E.toInt(), tf2)
                text(c, "★ +${max(m.weekPoints, 142)} · 명장면: 깜빡이 장인", card.left + 20 * U, card.bottom - 50 * U, 20 * U, 0xFF3A322A.toInt(), medium, maxW = card.width() - 40 * U)
                text(c, "#현비서 #안전운전", card.left + 20 * U, card.bottom - 20 * U, 18 * U, 0xFFFF4F79.toInt(), medium)
                val x0 = card.right + 40 * U
                text(c, "주간 공유 카드", x0, r.top + 100 * U, 48 * U, FG, bold)
                text(c, "지금 스킨 그림체로 만든 한 장 · 일요일 밤마다", x0, r.top + 146 * U, 26 * U, MUTED, medium, maxW = r.right - x0 - 32 * U)
                button(c, "폰으로 보내기", RectF(x0, r.bottom - 120 * U, x0 + 260 * U, r.bottom - 56 * U), true, "noop")
                lab(c, "폰 현비서 앱 공유 연동 예정 · 숫자는 이번 주 기록(없으면 예시)", x0, r.bottom - 22 * U, r.right - x0 - 32 * U, FAINT) }
            "league" -> row(340 * U) { r -> box(c, r); exTag(c, r)
                lab(c, "가족 온기 리그 · 이번 주", r.left + 32 * U, r.top + 40 * U)
                listOf(Triple("엄마", 214, false), Triple("나", max(m.weekPoints, 186), true), Triple("동생", 142, false), Triple("아빠", 98, false)).sortedByDescending { it.second }.forEachIndexed { i, (n2, pts, me) ->
                    val y = r.top + 70 * U + i * 54 * U; if (me) { p.color = ACC_SOFT; c.drawRoundRect(RectF(r.left + 20 * U, y, r.right - 20 * U, y + 48 * U), 12 * U, 12 * U, p) }
                    text(c, "${i + 1}", r.left + 40 * U, y + 34 * U, 28 * U, if (i == 0) WARN else MUTED, bold); text(c, n2, r.left + 90 * U, y + 34 * U, 28 * U, FG, if (me) bold else medium)
                    val bar = RectF(r.left + 220 * U, y + 18 * U, r.right - 160 * U, y + 30 * U); p.color = SURF2; c.drawRoundRect(bar, 6 * U, 6 * U, p); p.color = if (me) ACC else Glass.a(ACC, 90); c.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * pts / 240f, bar.bottom), 6 * U, 6 * U, p)
                    text(c, "★ $pts", r.right - 40 * U, y + 34 * U, 26 * U, FG, semi, Paint.Align.RIGHT) }
                lab(c, "1등은 한정 소품 '왕관' · 가족 계정 연동 예정", r.left + 32 * U, r.bottom - 18 * U, col = FAINT) }
            // ---- 출퇴근 ----
            "commute" -> row(230 * U) { r -> box(c, r)
                val hr = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY); val morning = hr < 14
                icon(c, if (morning) "sun" else "clock", r.left + 80 * U, r.centerY(), 80 * U, if (morning) 0xFFFFB020.toInt() else ACC)
                text(c, if (morning) "출근 모드" else "퇴근 모드", r.left + 170 * U, r.top + 92 * U, 52 * U, FG, bold)
                text(c, if (morning) "뉴스 5분 → 첫 일정 9:30 → 집중 음악" else "\"오늘 수고\" 플레이리스트 · 저녁 장보기 경유", r.left + 170 * U, r.top + 140 * U, 28 * U, MUTED, medium, maxW = r.width() - 200 * U)
                text(c, "평일 ${hr}시 · 시각으로 저절로 바뀌어요", r.left + 170 * U, r.bottom - 36 * U, 24 * U, ACC, semi) }
            // ---- 놀이·글레오 ----
            "senior" -> row(240 * U) { r -> box(c, r)
                text(c, "시니어 모드", r.left + 32 * U, r.top + 100 * U, 64 * U, FG, bold)
                text(c, "글자 1.3배 · 안내 두 번 · 밤엔 대비 크게", r.left + 32 * U, r.top + 160 * U, 36 * U, MUTED, medium)
                text(c, "\"글씨 크게\" 라고 말하면 켜져요", r.left + 32 * U, r.bottom - 32 * U, 30 * U, ACC, semi) }
            "gleo3" -> row(min(380 * U, z.height())) { r -> box(c, r)
                lab(c, "글레오가 부르는 현비서 함수 3개 · Gleo Interface(앱당 3개)", r.left + 32 * U, r.top + 40 * U)
                // 실제 Gleo Interface 3액션(assets/schema.json) 이름 그대로 — 예전 '현비서_오늘_브리핑' 등은 없는 이름이었다(가짜 감사 #23)
                val fns = listOf(Triple("ASK_HYUN", "\"글레오, 현비서한테 오늘 운전 어땠는지 물어봐\"", "질문을 현비서에게 · 현비서 목소리로 답"), Triple("HYUN_STATUS", "\"글레오, 현비서 지금 상황 알려 줘\"", "목적지 · 남은 시간 · 길 교통"), Triple("HYUN_QUIET", "\"글레오, 현비서 조용히 해 줘\"", "수다·제안 끄기(안전·대답만)"))
                val cs = cols(RectF(r.left + 24 * U, r.top + 64 * U, r.right - 24 * U, r.bottom - 64 * U), 1f, 1f, 1f)
                fns.forEachIndexed { i, (fn, say2, what) -> val q = cs[i]; p.color = SURF2; c.drawRoundRect(q, 16 * U, 16 * U, p)
                    text(c, "${i + 1}", q.left + 20 * U, q.top + 46 * U, 34 * U, ACC, bold); text(c, fn, q.left + 56 * U, q.top + 42 * U, 22 * U, FG, semi, maxW = q.width() - 70 * U)
                    wrap(c, say2, q.left + 20 * U, q.top + 100 * U, 24 * U, FG, q.width() - 40 * U, 3, medium, lh = 1.35f)
                    text(c, what, q.left + 20 * U, q.bottom - 22 * U, 20 * U, MUTED, medium, maxW = q.width() - 40 * U) }
                text(c, "앱 안에선 지금도 말로 동작 · 마지막 호출: ${m.gleoLast.ifBlank { "없음" }}", r.left + 32 * U, r.bottom - 24 * U, 22 * U, if (m.gleoLast.isBlank()) FAINT else GOOD, medium) }
            else -> row(240 * U) { r -> box(c, r); exTag(c, r)   // star1000
                icon(c, "star", r.left + 110 * U, r.centerY(), 120 * U, WARN)
                text(c, "별 1,000개!", r.left + 220 * U, r.top + 110 * U, 56 * U, FG, bold)
                text(c, "한정 테마 '오로라' 열렸어요 · 꾸미기에서 입어 봐요", r.left + 220 * U, r.top + 160 * U, 28 * U, MUTED, medium, maxW = r.right - r.left - 260 * U) }
        }
    }

    /** 주행 중 아이디어 — 내비 오른쪽 위 칸 한 줄(초점 하나, 터치 없음) */
    private fun ideaBanner(c: Canvas, r: RectF, id: String) {
        val (ic, col, big, sub) = when (id) {
            "combo" -> Quad("flag", GOOD, "콤보 3", "부드럽게 5km 더 · ★ +1")
            "brakecoach" -> Quad("flag", GOOD, "아주 부드러운 정지", "브레이크 미리, 천천히 · 좋아요")
            "drowsy" -> Quad("cup", WARN, "창문 살짝 열까요?", "다음 쉼터 6km")
            "alert" -> Quad("warn", RED, "1.2km 앞 호우특보", "속도를 줄여요")
            "arrive10" -> Quad("clock", ACC, "10분 남았어요", "주차장 입구는 오른쪽")
            "dj" -> Quad("music", ACC, "맑은 날의 노래", "이어서 틀게요 · DJ 현비서")
            "kidquiz" -> Quad("chat", GOOD, "뒷좌석 퀴즈 · 3점", "아이 목소리로 대답 중 · 운전자는 그대로")
            "parkhunt" -> Quad("pin", ACC, "공영주차장 300m", "건물 주차장은 만차 확률 높아요")
            "schoolzone" -> Quad("warn", WARN, "스쿨존 · 30 이하", "어린이보호구역 400m")
            "voicegame" -> Quad("music", ACC, "스무고개 · 7번째", "\"동물이에요?\" 처럼 말로만")
            else -> Quad("clock", ACC, "늦은 밤이에요", "30분마다 졸음 체크")   // night
        }
        glassCard(c, r, 24 * U, col)
        icon(c, ic, r.left + 80 * U, r.centerY(), 64 * U, col)
        text(c, big, r.left + 150 * U, r.centerY() + 4 * U, fit(big, 56 * U, r.width() - 180 * U, bold), FG, bold, maxW = r.width() - 180 * U)
        text(c, sub, r.left + 150 * U, r.centerY() + 54 * U, 30 * U, MUTED, medium, maxW = r.width() - 180 * U)
        if (id in setOf("alert", "dj", "drowsy", "combo", "kidquiz", "parkhunt", "schoolzone", "voicegame")) text(c, "예시", r.right - 24 * U, r.top + 34 * U, 20 * U, FAINT, medium, Paint.Align.RIGHT)
    }
    private data class Quad(val a: String, val b: Int, val c: String, val d: String)

    // ---------- 페이저(상황 안의 화면들) — 정차·시동: 밀기 · ‹ › · 제목 · n/N · 진행선 / 운전 중: 밀기 · 말("다음·이전") ----------
    private fun pageNow(ps: List<StageModel.Companion.Page>): Int = ps.indexOfFirst { (it.kind == "auto" && m.view == null && m.idea == null) || (it.kind == "cat" && it.id == m.view) || (it.kind == "idea" && it.id == m.idea) }.coerceAtLeast(0)
    /** 가로 밀기 — 덮개 화면이면 받지 않는다. 운전 중에도 받는다(화면 어디든 크게 한 번 밀기 = 겨냥할 버튼이 없어 부담이 작다 · 칼리 11-12) */
    fun swipe(dx: Float): Boolean {
        if (m.overlay != null) return false
        if (glance() && lastNavMain) return false   // 운전 중 내비 화면 — 넘길 것 없음(고르기는 말로)
        act(if (dx < 0) "page:next" else "page:prev"); return true
    }
    private fun pagerBar(c: Canvas, cx: Float, cy: Float) {
        val ps = StageModel.pages(m.sit); if (ps.size < 2) return
        val i = pageNow(ps); val title = ps[i].title; val cnt = "${i + 1} / ${ps.size}"
        p.textSize = 22 * U; p.typeface = medium; val tw = p.measureText(title); p.textSize = 20 * U; val cw = p.measureText(cnt)
        val inner = max(300 * U, tw + cw + 48 * U)
        val r = RectF(cx - inner / 2 - 56 * U, cy - 26 * U, cx + inner / 2 + 56 * U, cy + 26 * U)
        glassCard(c, r, 26 * U)
        val lb = RectF(r.left + 5 * U, r.top + 5 * U, r.left + 47 * U, r.bottom - 5 * U); val rb = RectF(r.right - 47 * U, r.top + 5 * U, r.right - 5 * U, r.bottom - 5 * U)
        for ((b, d) in listOf(lb to -1, rb to 1)) {
            p.shader = null; p.color = SURF2; c.drawOval(b, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 3 * U; p.strokeCap = Paint.Cap.ROUND; p.color = FG
            val ax = b.centerX() - d * 3 * U; c.drawLine(ax + d * 6 * U, b.centerY(), ax - d * 4 * U, b.centerY() - 9 * U, p); c.drawLine(ax + d * 6 * U, b.centerY(), ax - d * 4 * U, b.centerY() + 9 * U, p)
            p.style = Paint.Style.FILL; p.strokeCap = Paint.Cap.BUTT
        }
        hits += lb to "page:prev"; hits += rb to "page:next"
        text(c, title, lb.right + 18 * U, cy + 7 * U, 22 * U, FG, medium, maxW = inner - cw - 24 * U)
        text(c, cnt, rb.left - 18 * U, cy + 7 * U, 20 * U, FAINT, medium, Paint.Align.RIGHT)
        // 진행선 — 전체 중 어디쯤인지
        val tr = RectF(lb.right + 18 * U, r.bottom - 8 * U, rb.left - 18 * U, r.bottom - 6 * U)
        p.color = LINE; c.drawRect(tr, p); p.color = ACC; c.drawRect(tr.left, tr.top, tr.left + tr.width() * (i + 1) / ps.size, tr.bottom, p)
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
            Triple("novice", "", listOf("처음이에요", "초보 운전", "출발·정차·주차를 하나씩 말로|회전 300m 앞에서 미리 알림|감점은 부드럽게, 칭찬은 크게|50분마다 쉼표 제안")),
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
        val r = if (w < h * 1.15f) RectF(40 * U, h * 0.12f, w - 40 * U, h * 0.88f) else RectF(w * 0.27f, h * 0.16f, w * 0.73f, h * 0.84f)   // 반반이면 칸 전체
        p.shader = null; p.color = Glass.a(SURF, 248); c.drawRoundRect(r, 24 * U, 24 * U, p)   // 불투명 바탕 — 유리 카드만 깔면 뒤 카드 글자가 비쳐 섞였다(11-10)
        glassCard(c, r, 24 * U)
        hits += r to "noop"
        val x0 = r.left + 4f * S; val x1 = r.right - 4f * S
        // 닫기 — 상세 레이어 왼쪽 위 끝
        button(c, "닫기", RectF(x0, r.top + 2.4f * S, x0 + 12f * S, r.top + 7.6f * S), false, "close")
        text(c, "설정", x0 + 14f * S, r.top + 7f * S, 4f * S, FG, bold)
        text(c, "운전 숙련도", x0, r.top + 14f * S, 2.1f * S, MUTED, medium)
        val sh = 6f * S; val sy = r.top + 16f * S; val sw = (x1 - x0) / 2
        listOf("novice" to "초보 운전", "expert" to "숙련 운전").forEachIndexed { i, (key, lab) ->
            val sr = RectF(x0 + i * sw, sy, x0 + (i + 1) * sw, sy + sh).apply { inset(0.4f * S, 0f) }
            val on = m.skill == key
            if (on) { p.color = Glass.a(ACC, 150); c.drawRoundRect(sr, sh / 2, sh / 2, p) } else run { p.color = SURF2; c.drawRoundRect(sr, sh / 2, sh / 2, p) }
            text(c, lab, sr.centerX(), sr.centerY() + 0.8f * S, 2.2f * S, FG, bold, Paint.Align.CENTER)
            hits += sr to "skill:$key"
        }
        val desc = if (m.novice) "출발·정차·주차 팁을 말로 · 회전 300m 앞 미리 알림 · 감점은 절반 · 50분 쉼표" else "꼭 필요한 알림만 · 팁 없이 바로 길 안내 · 점수는 정확하게 · 120분 쉼표"
        wrap(c, desc, x0, sy + sh + 4f * S, 2f * S, MUTED, x1 - x0, 2, body)
        val by = r.bottom - 8.4f * S
        button(c, "차량 점검 다시", RectF(x0, by, x0 + (x1 - x0) * 0.49f, by + 5.6f * S), false, "recheck")
        button(c, "테마", RectF(x0 + (x1 - x0) * 0.51f, by, x1, by + 5.6f * S), false, "deco")
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
            "sun" -> { c.drawCircle(cx, cy, h * 0.4f, q)
                for (i in 0 until 8) { val a = Math.toRadians(i * 45.0); val ca = Math.cos(a).toFloat(); val sa = Math.sin(a).toFloat()
                    c.drawLine(cx + ca * h * 0.62f, cy + sa * h * 0.62f, cx + ca * h * 0.9f, cy + sa * h * 0.9f, q) } }
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
