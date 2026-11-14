package io.github.somanghao.makecar.hyun.ui

import io.github.somanghao.makecar.hyun.ui.skin.Kit
import io.github.somanghao.makecar.hyun.ui.skin.Skin
import io.github.somanghao.makecar.hyun.ui.skin.Skins
import io.github.somanghao.makecar.hyun.voice.Speaker
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.SweepGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 현비서 화면 — 목적지 띠 + 캐릭터(빛 구슬 몸 · 눈 · 입 · 더듬이) + 자막 두 줄 + 큰 마이크.
 * 캐릭터: 기분(mood) 5종이 몸 색·표정을 바꾸고, 상태가 동작을 바꾼다 — 듣기(귀 링이 목소리 크기만큼) · 생각(눈을 위로, 점 셋) ·
 * 말하기(입이 어절마다 열림) · 대기(숨쉬기·눈 깜빡). 더듬이 불빛 = 지금 말의 우선순위 색(P0 빨강 · P1 주황 · P2 파랑 · P3 보라).
 * 주행 중 대기엔 반복 애니메이션을 멈춘다(주의분산 가이드 · CPU), 정차하면 "현비서가 기억하는 것"까지 펼친다.
 * 가로(차 화면)·세로(폰) 배치 둘 다. 시스템 바 안쪽(padding)에 그린다.
 */
class HyunView(ctx: Context, val model: ScreenModel = ScreenModel()) : View(ctx), ScreenData by model {
    enum class Mode(val label: String) { READY("대기"), LISTENING("듣는 중"), THINKING("생각 중"), SPEAKING("말하는 중") }
    /** 내비 다음 안내(TBT) — m = 내비 값 그대로: [0]은 내 차에서, [1]부터는 앞 안내에서부터의 구간 거리(11-07 실측 — 내비 화면 "700m · 그다음 50m"과 같다) */
    data class Turn(val type: String, val m: Int, val desc: String)
    /** 기능 한 줄(아이콘 + 글) — 1칸 = 내비 판 바로 아래 · 반반 = 길 카드 아래. 코어가 render마다 비우고 기능 render가 채운다 */
    data class Line(val icon: String, val text: String)
    /** 목록 카드(정차) — kind "" = 목록(images가 있으면 표지 격자) · "info" = 소개(왼쪽 사진 · 오른쪽 "라벨 — 값" 표 — 휴게소·인물) */
    data class Card(val title: String, val source: String, val items: List<String>, val images: List<String> = emptyList(), val kind: String = "",
                    val tels: List<String> = emptyList())   // tels = 가게마다 [전화](장소 카드 — 서버 places, 화면에만)
    /** 대화형 말풍선 — me = 운전자 */
    data class Bubble(val me: Boolean, val text: String)

    // ---------- 스킨(배치 값 · UI 색·모양 · 글꼴) — 데이터(ScreenData)와 따로. 고르기 = SkinFeature(앱 설정 skin) · 정의 = assets/skins/<id>.json ----------
    private val kit = Kit()
    var skin: Skin = Skins.get(ctx, null)
        set(v) { field = v; invalidate() }
    var onSkin: () -> Unit = {}
    private val skinBtn = RectF()
    private val phoneSkin by lazy { Skins.phone(Skins.get(ctx, "basic")) }
    /** 지금 그리는 스킨 — 폰(짭비서 흰 화면)은 스킨과 별개 */
    private val look get() = if (lightTheme) phoneSkin else skin
    private val C get() = kit.skin.colors
    private val L get() = kit.skin.layout
    private val BG get() = C.bg
    private val CARD get() = C.card
    private val FG get() = C.fg
    private val MUTED get() = C.muted
    private val SUB get() = C.sub
    private val FAINT get() = C.faint
    private val LINE get() = C.line
    private val ACCENT get() = C.accent
    private val TEAL get() = C.accent
    private val OK get() = C.ok
    private val WARN get() = C.warn
    private val TBT_BG get() = C.tbtBg
    private val PRIO get() = C.prio
    private val p get() = kit.p
    private val body get() = kit.skin.fonts.body
    private val bold get() = kit.skin.fonts.bold
    private val medium get() = kit.skin.fonts.medium
    private val light get() = kit.skin.fonts.light

    // ---------- 화면 이벤트(누르기) — 데이터(ScreenData)는 여기서 바꾸지 않고 코어에 알린다 ----------
    var onMode: (Int) -> Unit = {}
    var onUiMode: (Boolean) -> Unit = {}
    var onCharacter: () -> Unit = {}
    var onMic: () -> Unit = {}
    var onKeyboard: (() -> Unit)? = null   // 폰만 — 글로 묻기(차는 키보드 금지)
    var onRecentCard: (Card) -> Unit = {}   // 최근 본 정보 다시 열기
    var onCardClose: () -> Unit = {}          // 카드 누름 = 닫기(코어가 card를 비운다 — 화면은 데이터를 바꾸지 않는다)
    private val recentHits = mutableListOf<Pair<RectF, Card>>()
    private var listOpen = false
    private val nextHit = RectF()
    private val listBtn = RectF()
    var onCarLink: () -> Unit = {}       // 폰 — '차 현비서' 줄 누름 → 차에서 나눈 이야기 · 보낸 장소(CarLinkFeature)
    var onCardDial: (Int) -> Unit = {}   // 목록 카드 가게 줄의 [전화] 누름 → 코어가 ACTION_DIAL(번호만 입력 — 통화는 운전자가)
    private val telHits = mutableListOf<Pair<RectF, Int>>()
    private val carHit = RectF()
    // 우리 쪽(dan-emu) — 상시 듣기 토글 · 상황 화면(StageUi) · 표정 덮기. 데이터(목적지·회전·말…)는 위 ScreenData 로 옮겨 갔다(업스트림 11-09)
    var listenAlways = true             // 상시 듣기("현비서야") ↔ 눌러서 말하기
    var showListenToggle = false
    var onListenMode: (Boolean) -> Unit = {}
    private val segAlways = RectF()
    private val segPtt = RectF()
    /** 차 가로 화면의 상황별 추천 화면(StageFeature) — 있으면 drawCar 대신 그린다 */
    var stage: StageUi? = null
    private var swDownX = 0f; private var swDownY = 0f
    /** 상황 화면이 잠깐 정하는 표정(온보딩 손 흔들기 등) · 대기 기본 표정(꾸미기) */
    var faceOverride: String? = null
    var idleFace = "neutral"
    private val segChat = RectF()
    private val segInfo = RectF()
    private val cardChip = RectF()       // 대화형에서 표가 왔을 때 "정보형에서 보기"
    private val charBtn = RectF()
    private val cardHit = RectF()
    private val mic = RectF()
    private val modeHits = mutableListOf<RectF>()
    private val kbd = RectF()
    private val charFace = RectF()     // 지금 그린 캐릭터 상자 — 만화 말풍선 꼬리가 입 쪽을 향한다
    private val comic get() = kit.skin.style.bubbleShape == "comic"

    /** 캐릭터 — human(사람 비서 그림) · orb3d(젤리 구슬 그림) · orb(코드로 그린 구슬). 바꾸면 그림을 미리 읽는다 */
    override var character: String
        get() = model.character
        set(v) { model.character = v; charImages().forEach { bmp(it) } }
    private val bmps = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
    private val loading = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private var level = 0f              // 목소리 크기(듣기: 마이크 · 말하기: TTS 어절) — 그릴 때마다 줄어든다
    fun pulse(l: Float) { level = max(level, l) }
    private val t0 = System.nanoTime()

    fun update() = postInvalidate()

    // ---------- 배치 ----------
    private class Box(
        val portrait: Boolean, val u: Float, val pad: Float, val barTop: Float, val barH: Float,
        val cx: Float, val cy: Float, val r: Float, val capX: Float, val capW: Float, val userY: Float, val hyunY: Float,
        val hyunLines: Int, val chipY: Float, val chipX0: Float, val chipX1: Float, val micX: Float, val micY: Float, val micR: Float,
    )

    private fun box(w: Float, h: Float): Box {
        val u = min(w, h); val pad = u * 0.045f
        if (h > w * 1.1f) {   // 세로(폰)
            val barTop = pad + u * 0.08f; val barH = u * 0.26f
            val r = min(w * 0.19f, h * 0.085f)
            val cy = barTop + barH + r * 1.75f
            val capY = cy + r * 1.55f + u * 0.1f
            val micR = u * 0.12f; val micY = h - pad - micR
            val ch = u * 0.06f
            return Box(true, u, pad, barTop, barH, w / 2, cy, r, pad * 1.4f, w - pad * 2.8f, capY, capY + u * 0.17f,
                6, micY - micR - pad - ch, pad, w - pad, w / 2, micY, micR)
        }
        val micR = u * 0.085f; val ch = u * 0.06f
        val k = if (phone) 0.05f else 0f   // 폰 가로는 위 카드가 두 줄(모드 칩 + 다음 일정)
        return Box(false, u, pad, u * 0.12f, u * (0.13f + k * 1.2f), w * 0.2f, h * 0.5f + k * h, u * 0.17f, w * 0.4f, w * 0.6f - pad, h * (0.33f + k), h * (0.49f + k),
            if (driving) 3 else 4, h - pad - ch, pad, w * 0.62f, w * 0.82f, h - pad - micR, micR)
    }

    override fun onDraw(c: Canvas) {
        kit.skin = look
        recentHits.clear()   // 정보 보드가 그릴 때만 다시 채운다
        val w = (width - paddingLeft - paddingRight).toFloat(); val h = (height - paddingTop - paddingBottom).toFloat()
        kit.background(c, width.toFloat(), height.toFloat(), min(max(w, 1f), max(h, 1f)))
        if (w <= 0 || h <= 0) return
        val t = (System.nanoTime() - t0) / 1e9f
        val b = box(w, h)
        c.save(); c.translate(paddingLeft.toFloat(), paddingTop.toFloat())
        if (phone && b.portrait) {   // 폰 세로 = 조용한 3단(사용자 피드백 "산만해 보인다", 2026-10-31)
            drawPhone(c, w, h, t)
            c.restore(); level *= 0.82f; postInvalidateDelayed(40); return
        }
        if (!phone) {   // 차 가로 = 왼쪽 좁은 칸(구슬·마이크) + 오른쪽 넓은 칸(말·목록) — 사용자 "캐릭터 작게, 목록 10개 한눈에, 화면 알차게"(2026-11-06)
            drawCar(c, w, h, t, b)
            c.restore(); level *= 0.82f
            if (mode != Mode.READY || !driving) postInvalidateDelayed(40)
            else if (stage?.animating() == true) postInvalidateDelayed(250)   // 주행 중 깜빡이 등 — 4fps
            return
        }
        header(c, w, b)
        tripBar(c, w, b)
        // 상태 글자("대기")는 안 쓴다 — 구슬의 빛이 상태. 듣는 중·생각 중만 글로
        val showCard = card != null && !driving && !phone
        // 목록 카드가 뜨면 구슬을 왼쪽에 작게 — 카드가 화면 70%를 쓴다(제목이 잘리지 않게)
        if (showCard) character(c, w * 0.14f, b.cy, b.r * 0.72f, t, label = false)
        else character(c, b.cx, b.cy, b.r, t, label = mode == Mode.LISTENING || mode == Mode.THINKING)
        if (showCard) cardPanel(c, w, b) else { cardHit.setEmpty(); captions(c, b) }
        if (phone) {   // 폰: 아래 줄 = 할 일·알림, 그 위 = 기억
            chipRow(c, b, "할 일·알림 ${tasks.size}", tasks, b.chipY)
            if (memory.isNotEmpty()) chipRow(c, b, "${botName}가 기억하는 것 ${memory.size}", memory.takeLast(8).reversed(), b.chipY - b.u * 0.13f)
        } else if (!driving && !showCard) memoryChips(c, b)
        micButton(c, b)
        c.restore()
        level *= 0.82f
        // 주행 중 대기 = 정지 화면(1초 갱신은 HyunActivity가) · 그 밖엔 25fps
        if (mode != Mode.READY || !driving) postInvalidateDelayed(40)
    }

    private fun header(c: Canvas, w: Float, b: Box) {
        val u = b.u; val base = b.pad + u * 0.045f
        text(c, botName, b.pad, base, u * 0.05f, FG, bold)
        p.textSize = u * 0.05f; p.typeface = bold
        val x = b.pad + p.measureText(botName) + u * 0.04f
        // 두뇌 = Claude(Claude Code 세션 또는 로그인한 Claude API) — 연결 상태와 방식을 이름으로 보인다
        // 연결되면 점 + "Claude"만(조용히) — 문제가 있을 때만 무슨 문제인지 글로
        val (label, col) = when (brain) {
            true -> (if (debug) when (brainMode) { "claude" -> "Claude · 내 계정"; "api" -> "Claude · API"; "session" -> "Claude · 세션"; else -> "Claude" } else "Claude") to OK
            false -> "Claude 로그인 필요" to WARN
            null -> "Claude 서버 없음" to MUTED
        }
        p.style = Paint.Style.FILL; p.color = col
        c.drawCircle(x, base - u * 0.011f, u * 0.011f, p)
        text(c, label, x + u * 0.025f, base, u * 0.03f, if (brain == true) MUTED else col)
        // 오른쪽: 차는 기어·속도를 계기판이 이미 보여 준다 → 알릴 것(조용히 모드·회전 앞 등)이 있을 때만
        val state = when {
            phone -> "폰" + (modes.getOrNull(modeIdx)?.let { " · $it" } ?: "") + if (silent) " · 이어폰 없음(자막)" else ""
            debug -> if (driving) "주행 중 · $gear ${kmh}km/h" else "정차 · $gear"
            else -> ""
        }
        val right = listOf(note, state).filter { it.isNotBlank() }.joinToString(" · ")
        if (right.isNotBlank()) text(c, right, w - b.pad, base, u * 0.03f, if (silent || note.isNotBlank()) WARN else MUTED, align = Paint.Align.RIGHT)
    }

    private fun tripBar(c: Canvas, w: Float, b: Box) {
        if (!phone && dest == null) return   // 차: 목적지가 없으면 막대를 비운다("어디로 갈까요?" 안내는 말로)
        val u = b.u; val pad = b.pad; val top = b.barTop; val bh = b.barH
        val r = RectF(pad, top, w - pad, top + bh)
        p.style = Paint.Style.FILL; p.color = CARD
        c.drawRoundRect(r, u * 0.035f, u * 0.035f, p)
        var top0 = top
        if (phone) {   // 모드 칩 줄 — 눌러서 바꾸기(말로도: "업무 모드")
            modeHits.clear()
            var x = r.left + pad * 0.7f; val y = top + bh * (if (b.portrait) 0.33f else 0.42f) * (if (dest == null) 1f else 0.75f)
            for ((i, m) in modes.withIndex()) {
                val on = i == modeIdx
                val size = u * 0.034f
                p.textSize = size; p.typeface = bold
                val cw = p.measureText(m) + size * 1.6f; val chH = size * 1.9f
                val rr = RectF(x, y - chH * 0.68f, x + cw, y + chH * 0.32f)
                p.style = Paint.Style.FILL; p.color = if (on) TEAL else 0xFF222A35.toInt()
                c.drawRoundRect(rr, chH / 2, chH / 2, p)
                text(c, m, x + size * 0.8f, y, size, if (on) BG else MUTED, bold)
                modeHits += RectF(rr).apply { offset(paddingLeft.toFloat(), paddingTop.toFloat()) }
                x += cw + pad * 0.35f
            }
            top0 = y + u * 0.02f
            if (dest == null) {
                val line = nextUp.ifBlank { "\"10분 뒤 알려줘\" · \"메모해 줘\" · \"오늘 일정 뭐야?\"" }
                wrap(c, line, r.left + pad, top0 + u * 0.055f, u * 0.036f, if (nextUp.isBlank()) MUTED else FG, r.width() - pad * 2, if (b.portrait) 2 else 1)
                return
            }
        }
        val d = dest
        if (d == null) {
            val hint = "어디로 갈까요?  \"강남역 가자\"처럼 말해 보세요"
            if (b.portrait) wrap(c, hint, r.left + pad, r.centerY() - u * 0.01f, u * 0.04f, MUTED, r.width() - pad * 2, 2)
            else text(c, hint, r.left + pad, r.centerY() + u * 0.013f, u * 0.036f, MUTED, maxW = r.width() - pad * 2)
            return
        }
        val barY: Float
        if (phone) {   // 대중교통 하차·목적지 — 모드 칩 아래 한 줄 + 막대
            text(c, "$d   $remain", r.left + pad, top0 + u * 0.055f, u * 0.04f, FG, bold, maxW = r.width() - pad * 2)
            barY = top + bh * 0.88f
        } else if (b.portrait) {
            text(c, d, r.left + pad, top + bh * 0.3f, u * 0.05f, FG, bold, maxW = r.width() - pad * 2)
            text(c, "$remain   $eta", r.left + pad, top + bh * 0.58f, u * 0.042f, moodColor(), bold, maxW = r.width() - pad * 2)
            barY = top + bh * 0.8f
        } else {
            text(c, d, r.left + pad, top + bh * 0.42f, u * 0.042f, FG, bold, maxW = r.width() * 0.45f)
            text(c, "$remain   $eta", r.right - pad, top + bh * 0.42f, u * 0.042f, moodColor(), bold, Paint.Align.RIGHT)
            barY = top + bh * 0.74f
        }
        // 진행 막대 + 차 점
        val x0 = r.left + pad; val x1 = r.right - pad
        p.color = C.track; p.strokeWidth = u * 0.01f; p.strokeCap = Paint.Cap.ROUND
        c.drawLine(x0, barY, x1, barY, p)
        val xc = x0 + (x1 - x0) * progress.toFloat()
        p.color = moodColor()
        c.drawLine(x0, barY, xc, barY, p)
        c.drawCircle(xc, barY, u * 0.016f, p)
        p.color = FG
        c.drawCircle(x1, barY, u * 0.009f, p)
    }

    // ---------- 캐릭터: 유리 구슬 + 안에서 흐르는 오로라 + 빛의 눈 (2026-10-31 "고급스럽게") ----------
    // 만화 얼굴·더듬이 대신 — 기분(mood)은 오로라 두 색, 상태는 빛의 움직임(듣기 링 · 생각 혜성 · 말하기 음성 파형)으로.
    // 우선순위 색은 말하는 동안 테두리 빛에 섞인다. 주행 중 대기엔 모두 멈춘다.
    private fun palette(): IntArray = C.mood(mood)

    private fun a(col: Int, alpha: Int) = (col and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    /** 두 색 섞기(0 = x, 1 = y) */
    private fun mix(x: Int, y: Int, k: Float): Int {
        fun ch(sh: Int) = (((x shr sh) and 0xFF) * (1 - k) + ((y shr sh) and 0xFF) * k).toInt() shl sh
        return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
    }

    private val clip = Path()
    private val arrow = Path()          // 회전 화살표·화살촉
    private val m = Matrix()

    private fun character(c: Canvas, cx: Float, cy0: Float, r: Float, t: Float, label: Boolean = true) {
        if (character == "face") charImage()?.let { bmp(it) }?.let { drawImage(c, it, RectF(cx - r * 1.3f, cy0 - r * 1.3f, cx + r * 1.3f, cy0 + r * 1.3f), t); return }   // 폰 화면도 얼굴 그림
        val pal = palette(); val c1 = pal[0]; val c2 = pal[1]
        val still = driving && mode == Mode.READY
        val cy = cy0 + if (still) 0f else sin(t * 1.1f) * r * 0.02f
        val lv = level
        val energy = when (mode) { Mode.SPEAKING -> 0.82f + 0.18f * lv; Mode.LISTENING -> 0.78f + 0.22f * lv; Mode.THINKING -> 0.8f; Mode.READY -> 0.72f }
        p.style = Paint.Style.FILL

        // 1 바깥 빛
        p.shader = RadialGradient(cx, cy, r * 2f, intArrayOf(a(c1, ((if (lightTheme) 20 else 40) + (if (lightTheme) 45 else 70) * energy).toInt()), a(c2, if (lightTheme) 12 else 22), 0), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r * 2f, p)
        // 2 짙은 유리 몸
        p.shader = if (lightTheme) RadialGradient(cx, cy - r * 0.3f, r * 1.25f, intArrayOf(mix(c1, 0xFFFFFFFF.toInt(), 0.92f), mix(c1, 0xFFFFFFFF.toInt(), 0.72f), mix(c2, 0xFFFFFFFF.toInt(), 0.5f)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
            else RadialGradient(cx, cy + r * 0.2f, r * 1.2f, intArrayOf(mix(c2, 0xFF05070A.toInt(), 0.62f), mix(c1, 0xFF05070A.toInt(), 0.8f), 0xFF05070A.toInt()), floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        // 3 안에서 흐르는 오로라(구슬 안으로 자르기)
        c.save()
        clip.reset(); clip.addCircle(cx, cy, r * 0.985f, Path.Direction.CW); c.clipPath(clip)
        val sp = if (still) 0.6f else t * 0.55f
        val k = if (lightTheme) 0.62f else 1f   // 흰 배경에선 오로라를 옅게(파스텔)
        fun blob(x: Float, y: Float, rad: Float, col: Int) { p.shader = RadialGradient(x, y, rad, col, a(col, 0), Shader.TileMode.CLAMP); c.drawCircle(x, y, rad, p) }
        // 아래에서 올라오는 빛 + 두 색이 천천히 도는 오로라 + 밝은 심지
        blob(cx, cy + r * 0.95f, r * 1.05f, a(c1, (235 * energy * k).toInt()))
        blob(cx + cos(sp) * r * 0.42f, cy + r * 0.28f + sin(sp * 1.3f) * r * 0.2f, r * 0.8f, a(c1, (225 * energy * k).toInt()))
        blob(cx + cos(sp + 2.2f) * r * 0.45f, cy + r * 0.05f + sin(sp * 0.8f + 1f) * r * 0.28f, r * 0.75f, a(c2, (215 * energy * k).toInt()))
        blob(cx + cos(sp + 4.1f) * r * 0.2f, cy + r * 0.62f, r * 0.42f, a(mix(c1, 0xFFFFFFFF.toInt(), 0.65f), (200 * energy).toInt()))
        // 4 유리 반사광 — 위쪽 얇은 초승달
        p.shader = LinearGradient(cx, cy - r, cx, cy - r * 0.35f, if (lightTheme) 0xB3FFFFFF.toInt() else 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawOval(RectF(cx - r * 0.62f, cy - r * 0.93f, cx + r * 0.62f, cy - r * 0.3f), p)
        c.restore()
        // 5 테두리 빛 — 돌아가는 두 색(말하는 중이면 우선순위 색이 섞인다)
        val accent = if (mode == Mode.SPEAKING && prio in 0..3) PRIO[prio] else c2
        p.shader = SweepGradient(cx, cy, intArrayOf(a(c1, 235), a(accent, 150), a(c1, 30), a(c2, 210), a(c1, 235)), null).apply {
            m.setRotate(if (still) 0f else t * 35f, cx, cy); setLocalMatrix(m)
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.03f
        c.drawCircle(cx, cy, r * 0.985f, p)
        p.shader = null

        eyes(c, cx, cy, r, t, still, c1)

        // 6 상태 빛
        when (mode) {
            Mode.LISTENING -> {   // 목소리 크기만큼 퍼지는 링 두 겹
                p.style = Paint.Style.STROKE
                p.strokeWidth = r * 0.022f; p.color = a(c1, 200)
                c.drawCircle(cx, cy, r * (1.1f + 0.2f * lv), p)
                p.strokeWidth = r * 0.012f; p.color = a(c1, 90)
                c.drawCircle(cx, cy, r * (1.24f + 0.32f * lv + 0.04f * sin(t * 5f)), p)
            }
            Mode.THINKING -> {    // 테두리를 도는 혜성
                p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.035f; p.strokeCap = Paint.Cap.ROUND
                p.shader = SweepGradient(cx, cy, intArrayOf(0, 0, a(c1, 255)), floatArrayOf(0f, 0.72f, 1f)).apply { m.setRotate(t * 240f, cx, cy); setLocalMatrix(m) }
                c.drawCircle(cx, cy, r * 1.12f, p)
                p.shader = null
            }
            Mode.SPEAKING -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.012f; p.color = a(accent, (60 + 120 * lv).toInt())
                c.drawCircle(cx, cy, r * (1.08f + 0.1f * lv), p)
            }
            Mode.READY -> {}
        }
        if (label) text(c, mode.label, cx, cy0 + r * 1.55f, r * 0.2f, MUTED, align = Paint.Align.CENTER)
    }

    /** 빛의 눈 — calm 캡슐 · happy 초승달 · fun 윙크 · care 기운 캡슐 · alert 둥근 점 / 말할 땐 입 자리에 음성 파형 */
    private fun eyes(c: Canvas, cx: Float, cy: Float, r: Float, t: Float, still: Boolean, c1: Int) {
        val md = if (mood in setOf("happy", "fun", "care", "alert")) mood else "calm"
        val light = if (lightTheme) 0xFF1E2A36.toInt() else 0xFFFFFFFF.toInt()
        val (gx, gy) = when (mode) {
            Mode.THINKING -> r * 0.07f to -r * 0.08f
            Mode.LISTENING -> 0f to -r * 0.03f
            Mode.SPEAKING -> sin(t * 0.8f) * r * 0.015f to 0f
            Mode.READY -> if (still) 0f to 0f else sin(t * 0.4f) * r * 0.04f to cos(t * 0.27f) * r * 0.015f
        }
        val blink = !still && mode != Mode.SPEAKING && (t % 4.1f) < 0.12f
        val ey = cy - r * 0.06f + gy
        val tall = if (mode == Mode.LISTENING) 1.12f else 1f
        for (side in intArrayOf(-1, 1)) {
            val ex = cx + side * r * 0.27f + gx
            // 눈 뒤 은은한 빛
            p.style = Paint.Style.FILL
            if (!lightTheme) {
                p.shader = RadialGradient(ex, ey, r * 0.2f, a(0xFFFFFFFF.toInt(), 70), a(c1, 0), Shader.TileMode.CLAMP)
                c.drawCircle(ex, ey, r * 0.2f, p)
            }
            p.shader = null; p.color = light
            val arc = md == "happy" || (md == "fun" && side == 1)
            when {
                blink -> { val w = r * 0.06f; c.drawRoundRect(RectF(ex - w, ey - r * 0.018f, ex + w, ey + r * 0.018f), w, w, p) }
                arc && mode != Mode.LISTENING -> {
                    p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.06f; p.strokeCap = Paint.Cap.ROUND
                    c.drawArc(RectF(ex - r * 0.1f, ey - r * 0.05f, ex + r * 0.1f, ey + r * 0.15f), 205f, 130f, false, p)
                }
                md == "alert" -> c.drawCircle(ex, ey, r * 0.085f, p)
                else -> {
                    val w = r * 0.052f; val hh = r * (if (md == "care") 0.09f else 0.12f) * tall
                    c.save()
                    if (md == "care") c.rotate(side * -14f, ex, ey)   // 안쪽이 올라간 걱정 눈
                    c.drawRoundRect(RectF(ex - w, ey - hh, ex + w, ey + hh), w, w, p)
                    c.restore()
                }
            }
        }
        // 입 자리
        val my = cy + r * 0.32f
        p.shader = null
        when {
            mode == Mode.SPEAKING -> {   // 빛의 음성 파형
                val beat = 0.3f + 0.5f * abs(sin(t * 9.5f)) * abs(sin(t * 3.1f + 1f))
                val o = max(beat * 0.8f, level).coerceIn(0.15f, 1f)
                val path = Path(); val half = r * 0.2f
                for (i in 0..40) {
                    val k = i / 40f; val x = cx - half + 2 * half * k
                    val y = my + sin(k * 3f * PI.toFloat() * 2 + t * 12f) * r * (0.015f + 0.06f * o) * sin(k * PI.toFloat())
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.03f; p.strokeCap = Paint.Cap.ROUND; p.color = a(light, 230)
                c.drawPath(path, p)
            }
            mode == Mode.READY && (md == "happy" || md == "fun" || md == "calm") -> {   // 희미한 미소
                p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.028f; p.strokeCap = Paint.Cap.ROUND; p.color = a(light, if (md == "calm") 110 else 170)
                val wd = if (md == "calm") 0.09f else 0.12f
                c.drawArc(RectF(cx - r * wd, my - r * 0.08f, cx + r * wd, my + r * 0.05f), 25f, 130f, false, p)
            }
        }
    }

    // ---------- 폰 세로: 머리줄(이름·연결 점 | 모드) · 다음 한 줄 → 캐릭터 + 대화(가운데) → 글 · 마이크 · 목록 ----------
    // 이름표·칩 줄을 없애고, 알림·할 일·기억은 "목록"을 누를 때만. 우선순위는 더듬이 색 + 대사 위 작은 점 하나
    private fun drawPhone(c: Canvas, w: Float, h: Float, t: Float) {
        val u = w; val pad = u * 0.06f
        val base = pad + u * 0.05f
        text(c, botName, pad, base, u * 0.056f, FG, bold)
        p.textSize = u * 0.056f; p.typeface = bold
        val dotX = pad + p.measureText(botName) + u * 0.028f
        p.style = Paint.Style.FILL; p.color = when (brain) { true -> OK; false -> WARN; null -> MUTED }
        c.drawCircle(dotX, base - u * 0.018f, u * 0.011f, p)
        // 모드 — 오른쪽 작은 세그먼트
        modeHits.clear()
        val size = u * 0.032f; p.textSize = size; p.typeface = bold
        val padX = size * 0.75f; val gap = u * 0.008f
        val widths = modes.map { p.measureText(it) + padX * 2 }
        val segH = size * 1.9f; val segTop = base - size * 1.3f
        var x = w - pad - widths.sum() - gap * (modes.size - 1).coerceAtLeast(0)
        p.style = Paint.Style.FILL; p.color = CARD
        c.drawRoundRect(RectF(x - gap * 2, segTop - gap * 2, w - pad + gap * 2, segTop + segH + gap * 2), segH, segH, p)
        for ((i, m) in modes.withIndex()) {
            val r = RectF(x, segTop, x + widths[i], segTop + segH)
            if (i == modeIdx) { p.style = Paint.Style.FILL; p.color = if (lightTheme) Color.WHITE else 0xFF243140.toInt(); c.drawRoundRect(r, segH / 2, segH / 2, p) }
            text(c, m, r.centerX(), segTop + segH * 0.66f, size, if (i == modeIdx) ACCENT else FAINT, bold, Paint.Align.CENTER)
            modeHits += RectF(r).apply { offset(paddingLeft.toFloat(), paddingTop.toFloat()) }
            x += widths[i] + gap
        }
        // 다음 한 줄(누르면 목록)
        val ny = base + u * 0.115f
        val has = nextUp.isNotBlank()
        val line = if (has) nextUp + (if (nextMore > 0) "   +$nextMore" else "") else "\"10분 뒤 알려줘\"처럼 말해 보세요"
        text(c, line, pad, ny, u * 0.035f, if (has) SUB else FAINT, maxW = w - pad * 2)
        nextHit.set(0f, ny - u * 0.06f, w, ny + u * 0.03f)
        // 차 현비서 연동 줄(누르면 차에서 나눈 이야기 · 보낸 장소) — 폰 연동 기능이 채운다
        carHit.setEmpty()
        if (carLine.isNotBlank()) {
            val cy0 = ny + u * 0.075f; val chH = u * 0.075f
            p.textSize = u * 0.032f; p.typeface = medium
            val tw = p.measureText(carLine) + u * 0.1f
            val r0 = RectF(pad, cy0 - chH * 0.72f, min(w - pad, pad + tw + u * 0.05f), cy0 + chH * 0.28f)
            p.style = Paint.Style.FILL; p.color = CARD; c.drawRoundRect(r0, chH / 2, chH / 2, p)
            p.color = if (carLine.contains("연결됨")) OK else MUTED; c.drawCircle(r0.left + u * 0.035f, r0.centerY(), u * 0.011f, p)
            text(c, carLine + "  ›", r0.left + u * 0.065f, cy0, u * 0.032f, SUB, medium, maxW = r0.width() - u * 0.08f)
            carHit.set(RectF(r0).apply { inset(0f, -chH * 0.3f); offset(paddingLeft.toFloat(), paddingTop.toFloat()) })
        }

        // 캐릭터 + 대화
        val r = min(w * 0.19f, h * 0.085f); val cx = w / 2; val cy = h * 0.33f
        character(c, cx, cy, r, t, label = false)
        val colW = w - pad * 2
        var y = cy + r * 1.85f
        if (userLine.isNotEmpty()) y = wrap(c, userLine, cx, y, u * 0.036f, MUTED, colW, 2, light, center = true) + u * 0.035f
        else y += u * 0.035f
        if (prio in 0..3) {
            val lab = "P$prio ${Speaker.LABEL_PHONE[prio]}" + if (prioReply) " · 대답" else ""
            p.textSize = u * 0.026f; p.typeface = body
            val tw = p.measureText(lab); val dr = u * 0.008f
            p.style = Paint.Style.FILL; p.color = PRIO[prio]
            c.drawCircle(cx - tw / 2 - dr * 2.2f, y - u * 0.009f, dr, p)
            text(c, lab, cx + dr * 1.4f, y, u * 0.026f, FAINT, align = Paint.Align.CENTER)
            y += u * 0.07f
        }
        wrap(c, hyunLine, cx, y, u * 0.047f, FG, colW, 5, medium, center = true)

        // 아래: 글 · 마이크 · 목록
        val mr = u * 0.105f; val my = h - pad - mr * 0.95f
        mic.set(cx - mr, my - mr, cx + mr, my + mr)
        val mp = palette()
        p.style = Paint.Style.FILL
        if (mode == Mode.LISTENING) {   // 듣는 중 — 마이크 둘레가 기분 색으로 빛난다
            p.shader = RadialGradient(cx, my, mr * 1.9f, intArrayOf(a(mp[0], 120), a(mp[0], 0)), null, Shader.TileMode.CLAMP)
            c.drawCircle(cx, my, mr * 1.9f, p)
        }
        p.shader = RadialGradient(cx, my - mr * 0.5f, mr * 1.5f, if (lightTheme) intArrayOf(0xFFFFFFFF.toInt(), 0xFFEEF1F5.toInt()) else intArrayOf(0xFF26313E.toInt(), 0xFF0E141B.toInt()), null, Shader.TileMode.CLAMP)
        c.drawCircle(cx, my, mr, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = mr * 0.045f
        p.shader = SweepGradient(cx, my, intArrayOf(mp[0], mp[1], a(mp[0], 60), mp[0]), null)
        c.drawCircle(cx, my, mr * 0.975f, p)
        p.shader = null; p.style = Paint.Style.FILL
        p.color = if (lightTheme) 0xFF1E2A36.toInt() else 0xFFF2F5F8.toInt()
        c.drawRoundRect(RectF(cx - mr * 0.18f, my - mr * 0.48f, cx + mr * 0.18f, my + mr * 0.12f), mr * 0.18f, mr * 0.18f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = mr * 0.08f; p.strokeCap = Paint.Cap.ROUND
        c.drawArc(RectF(cx - mr * 0.34f, my - mr * 0.3f, cx + mr * 0.34f, my + mr * 0.3f), 0f, 180f, false, p)
        c.drawLine(cx, my + mr * 0.3f, cx, my + mr * 0.5f, p)
        val sr = mr * 0.5f
        fun side(xc: Float, label: String, hit: RectF, badge: Int) {
            hit.set(xc - sr, my - sr, xc + sr, my + sr)
            p.style = Paint.Style.STROKE; p.strokeWidth = sr * 0.05f; p.color = LINE
            c.drawCircle(xc, my, sr, p)
            p.style = Paint.Style.FILL
            text(c, label, xc, my + sr * 0.2f, sr * 0.55f, MUTED, medium, Paint.Align.CENTER)
            if (badge > 0) {
                p.style = Paint.Style.FILL; p.color = mp[0]
                c.drawCircle(xc + sr * 0.75f, my - sr * 0.75f, sr * 0.32f, p)
                text(c, "$badge", xc + sr * 0.75f, my - sr * 0.62f, sr * 0.38f, Color.WHITE, bold, Paint.Align.CENTER)
            }
        }
        if (onKeyboard != null) side(cx - mr * 2.3f, "글", kbd, 0)
        side(cx + mr * 2.3f, "목록", listBtn, reminderItems.size + todoItems.size)

        if (listOpen) listOverlay(c, w, h, u, pad)
    }

    /** 목록 — 알림(날짜 포함)·할 일·기억. 아무 데나 누르면 닫힌다 */
    private fun listOverlay(c: Canvas, w: Float, h: Float, u: Float, pad: Float) {
        p.style = Paint.Style.FILL; p.color = if (lightTheme) 0x66000000 else 0xE6070A0E.toInt()
        c.drawRect(-paddingLeft.toFloat(), -paddingTop.toFloat(), w + paddingRight, h + paddingBottom, p)
        val card = RectF(pad * 0.6f, h * 0.08f, w - pad * 0.6f, h * 0.9f)
        p.color = if (lightTheme) Color.WHITE else CARD; c.drawRoundRect(card, u * 0.05f, u * 0.05f, p)
        val x = card.left + pad; val maxW = card.width() - pad * 2
        var y = card.top + u * 0.1f
        // 닫기 — 왼쪽 위 끝(목록은 아무 데나 눌러도 닫힘)
        text(c, "닫기", x, y, u * 0.034f, MUTED)
        p.textSize = u * 0.034f
        text(c, "목록", x + p.measureText("닫기") + u * 0.04f, y, u * 0.05f, FG, bold)
        y += u * 0.06f
        val sections = listOf("알림" to reminderItems, "할 일" to todoItems, "${botName}가 기억하는 것" to memory.reversed())
        for ((title, items) in sections) {
            if (items.isEmpty()) continue
            y += u * 0.05f
            if (y > card.bottom - pad) break
            text(c, "$title ${items.size}", x, y, u * 0.03f, ACCENT, bold)
            y += u * 0.02f
            for (it in items) {
                y += u * 0.065f
                if (y > card.bottom - pad) break
                text(c, it, x, y, u * 0.04f, FG, maxW = maxW)
            }
            y += u * 0.02f
        }
        if (reminderItems.isEmpty() && todoItems.isEmpty() && memory.isEmpty()) text(c, "아직 없어요 — \"10분 뒤 알려줘\" · \"메모해 줘\"", x, y + u * 0.08f, u * 0.036f, MUTED, maxW = maxW)
    }

    // ---------- 자막 · 우선순위 ----------
    private fun captions(c: Canvas, b: Box) {
        val u = b.u; val x = b.capX; val maxW = b.capW
        if (userLine.isNotEmpty()) {
            text(c, (if (name.isNotEmpty()) name else "나"), x, b.userY, u * 0.028f, MUTED)
            wrap(c, userLine, x, b.userY + u * 0.05f, u * 0.034f, SUB, maxW, 2)
        }
        text(c, botName, x, b.hyunY, u * 0.028f, moodColor())
        // 우선순위 배지·줄 선 말 — 개발용(사용자: 불필요한 정보 빼기, 2026-11-06). 안전 경고(P0)만 늘 붉은 배지
        p.textSize = u * 0.028f; p.typeface = body
        var bx = x + p.measureText(botName) + u * 0.02f
        if (prio == 0) bx = pill(c, "안전", bx, b.hyunY, u * 0.024f, PRIO[0]) + u * 0.015f
        else if (debug && prio in 1..3) {
            val label = "P$prio ${(if (phone) Speaker.LABEL_PHONE else Speaker.LABEL)[prio]}" + if (prioReply) " · 대답" else ""
            bx = pill(c, label, bx, b.hyunY, u * 0.024f, PRIO[prio]) + u * 0.015f
        }
        if (debug && waiting.isNotEmpty()) text(c, "대기 " + waiting.joinToString(" ") { "P$it" }, bx, b.hyunY, u * 0.024f, MUTED)
        val size = if (driving) u * 0.05f else u * 0.044f
        wrap(c, hyunLine, x, b.hyunY + u * 0.065f, size, FG, maxW, b.hyunLines, bold)
    }

    // ---------- 캐릭터 그림(디자인 에셋) ----------
    /** 그림은 처음 쓸 때 뒤에서 읽어 둔다(절반 크기) — 아직 없으면 null(그 사이는 그린 구슬) */
    internal fun asset(name: String): Bitmap? = bmp(name)
    private fun bmp(name: String): Bitmap? {
        bmps[name]?.let { return it }
        if (loading.add(name)) Thread {
            try {
                context.assets.open("chars/$name").use { s -> BitmapFactory.decodeStream(s, null, BitmapFactory.Options().apply { inSampleSize = if (name.startsWith("bot_") || name.startsWith("room_") || name.startsWith("acc_")) 1 else 2 }) }
                    ?.let { bmps[name] = it; postInvalidate() }
            } catch (_: Exception) { }
        }.start()
        return null
    }

    /** 인터넷 그림(웹툰 표지 등) — 뒤에서 받아 캐시, 아직 없으면 null */
    internal fun urlBmp(url: String): Bitmap? {
        if (url.isBlank()) return null
        bmps[url]?.let { return it }
        if (loading.add(url)) Thread {
            try {
                val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 5000; c.readTimeout = 8000; c.setRequestProperty("User-Agent", "Mozilla/5.0")
                c.inputStream.use { s -> BitmapFactory.decodeStream(s) }?.let { bmps[url] = it; postInvalidate() }
                c.disconnect()
            } catch (_: Exception) { }
        }.start()
        return null
    }

    private fun charImages(): List<String> = when (character) {
        "human" -> listOf("sec_portrait", "sec_pose_think", "sec_pose_tablet", "sec_pose_guide", "sec_pose_clap", "sec_pose_rest",
            "sec_sd_calm", "sec_sd_happy", "sec_sd_fun", "sec_sd_care", "sec_sd_think").map { "$it.png" }
        "orb3d" -> listOf("calm", "happy", "fun", "care", "alert", "listen", "think", "speak").map { "orb_$it.png" }
        "face" -> BOT_USED.map { "bot_$it.webp" }
        else -> emptyList()
    }

    private fun moodKey() = when (mood) { "happy", "fun", "care" -> mood; else -> "calm" }

    /** 지금 상태에 맞는 그림 이름 — 사람 비서는 주행 중엔 SD 얼굴만(화면 규칙) */
    private fun charImage(): String? = when (character) {
        "orb3d" -> when {
            mood == "alert" || prio == 0 -> "orb_alert.png"; mode == Mode.LISTENING -> "orb_listen.png"
            mode == Mode.THINKING -> "orb_think.png"; mode == Mode.SPEAKING -> "orb_speak.png"; else -> "orb_${moodKey()}.png"
        }
        // 얼굴 화면 캐릭터 = 헬멧 로봇(댄 11-08 새 시트 7×5 → assets/chars/bot_*.webp, 35표정). 상태별 표정은 botFace()
        "face" -> "bot_${botFace()}.webp"
        "human" -> when {
            driving -> if (mode == Mode.THINKING) "sec_sd_think.png" else "sec_sd_${moodKey()}.png"
            mode == Mode.THINKING -> "sec_pose_think.png"
            mode == Mode.LISTENING -> "sec_pose_tablet.png"
            mode == Mode.SPEAKING -> if (mood == "happy" || mood == "fun") "sec_pose_clap.png" else "sec_pose_guide.png"
            mood == "care" -> "sec_pose_rest.png"
            else -> "sec_portrait.png"
        }
        else -> null
    }

    /** 헬멧 로봇 표정 — 경고 > 듣기 > 생각 > 말하기(기분별) > 대기(주행 = 운전대, 기분별). 대기·말하기 중 4초마다 잠깐 눈 감기(깜빡임) */
    private var blinkNext = 3.0
    private var blinkFrom = -1.0
    internal fun botFace(): String {
        val t = (System.nanoTime() - t0) / 1e9
        if (t > blinkNext) { blinkFrom = t; blinkNext = t + 4.0 + Math.random() * 3.0 }   // 깜빡임 4~7초 무작위(디자인 스펙)
        val blink = t - blinkFrom < 0.14 && (mode == Mode.READY || mode == Mode.SPEAKING) && !(driving && mode == Mode.READY)
        return when {
            mood == "alert" || prio == 0 -> "alert"
            mode == Mode.LISTENING -> "surprised"
            mode == Mode.THINKING -> "thinking"
            blink -> "bored"
            mode == Mode.SPEAKING -> when (mood) { "happy" -> "laugh"; "fun" -> "excited"; "care" -> "worried"; else -> "happy" }
            faceOverride != null -> faceOverride!!
            driving -> "driving"
            mood == "happy" -> "happy"; mood == "fun" -> "humming"; mood == "care" -> "worried"
            brain == false -> "tired"
            else -> idleFace
        }
    }

    /** 그림을 상자 안에 비율 유지·아래 맞춤으로 — 숨쉬듯 살짝(주행 중 대기엔 멈춤), 말할 땐 아주 살짝 커짐 */
    private fun drawImage(c: Canvas, bm: Bitmap, box: RectF, t: Float) {
        val s = min(box.width() / bm.width, box.height() / bm.height)
        val still = driving && mode == Mode.READY
        val pulse = if (mode == Mode.SPEAKING) 1f + level * 0.025f else 1f
        val bw = bm.width * s * pulse; val bh = bm.height * s * pulse
        val bob = if (still) 0f else sin(t * 1.3f) * box.height() * 0.008f
        p.shader = null; p.color = Color.WHITE; p.isFilterBitmap = true
        c.drawBitmap(bm, null, RectF(box.centerX() - bw / 2, box.bottom - bh + bob, box.centerX() + bw / 2, box.bottom + bob), p)
    }

    // ---------- 차 화면 (가로) ----------
    // = 정보형(대화형은 drawChat). 왼쪽 24%: 이름·연결 → (조용히 모드 등 알림) → 캐릭터 → 내 질문 → 현비서 답(표가 있을 때) → 작은 조작 줄
    // 오른쪽 76%: 길 안내 중이면 맨 위 **내비 판**(다음 회전 크게 · 그다음 · 목적지·남은 거리·도착·교통 · 이어지는 안내) → 그 아래 표 또는 현비서 답.
    //   표(목록 카드, 정차)가 뜨면 내비 판은 한 줄 띠로 줄인다. 길 안내가 없으면 예전처럼 답이 맨 위
    // 사용자 11-06: "표가 맨 위에 없어 — 내 질문·니 답변 모두 캐릭터 밑에" · 11-08: "캐릭터 오른쪽 정보창에 내비 정보 전체가 보이게 · 마이크·듣기 모드는 작게"
    private fun drawCar(c: Canvas, w: Float, h: Float, t: Float, b: Box) {
        // 앱 영역 2칸(내비와 나란히 — 오른쪽 ⋮ 끌기)이면 폭이 반이라 세로로 쌓는다(11-07 실측: 왼쪽 열이 좁아 도착 시각·버튼이 "…")
        if (uiChat) { drawChat(c, w, h, t); return }
        cardChip.setEmpty()
        // 상황 화면은 반반(내비와 나란히)에서도 — 안내를 시작하면 Pleos가 반반으로 바꾸는데, 그때 상황 화면이 사라지고 예전 좁은 화면이 나왔다(11-10)
        stage?.let { if (it.draw(c, w, h, t)) return }
        if (w < h * 1.15f) { drawCarNarrow(c, w, h, t); return }
        val u = b.u; val pad = b.pad
        val colW = w * L.infoCharW   // 캐릭터 칸 폭 · 왼쪽/오른쪽 = 스킨 layout
        val lx0 = if (L.charRight) w - colW else pad; val lx1 = if (L.charRight) w - pad else colW; val lcx = (lx0 + lx1) / 2
        val k = card?.takeIf { !driving }
        // 이름 · 연결 점
        var y = pad + u * 0.04f
        val dx = nameplate(c, lx0, y, u * 0.04f) + u * 0.025f
        val (label, col) = when (brain) {
            true -> "Claude" to OK; false -> "로그인 필요" to WARN; null -> "서버 없음" to MUTED
        }
        p.style = Paint.Style.FILL; p.shader = null; p.color = col
        c.drawCircle(dx, y - u * 0.01f, u * 0.009f, p)
        text(c, label, dx + u * 0.02f, y, u * 0.024f, if (brain == true) MUTED else col, maxW = lx1 - dx - u * 0.02f)
        if (note.isNotBlank()) { y += u * 0.04f; text(c, note, lx0, y, u * 0.022f, WARN, maxW = lx1 - lx0) }
        y += u * 0.01f
        // 캐릭터 — 사람 비서(정차: 상반신 포즈 크게 / 주행: SD 얼굴) · 젤리 구슬 그림 · 그린 구슬 (목적지는 오른쪽 내비 판에)
        val img = charImage()?.let { bmp(it) }
        if (img != null && character == "human" && !driving) {
            val boxH = min((lx1 - lx0) / 0.78f, (h - y) * 0.5f)
            val box = RectF(lx0, y + u * 0.015f, lx1, y + u * 0.015f + boxH)
            charDeco(c, box, u, t)
            drawImage(c, img, box, t)
            mic.set(box); charFace.set(box)   // 캐릭터를 누르면 대화 창(마이크 버튼은 없다 — 사용자 11-09)
            y = box.bottom + u * 0.01f
        } else {
            val r = min((lx1 - lx0) * 0.3f, h * 0.11f)
            val oy = y + r * 1.45f + u * 0.01f
            charDeco(c, RectF(lcx - r * 1.3f, oy - r * 1.3f, lcx + r * 1.3f, oy + r * 1.3f), u, t)
            charFace.set(lcx - r * 1.3f, oy - r * 1.3f, lcx + r * 1.3f, oy + r * 1.3f)
            if (img != null) drawImage(c, img, RectF(lcx - r * 1.3f, oy - r * 1.3f, lcx + r * 1.3f, oy + r * 1.3f), t)
            else character(c, lcx, oy, r, t, label = false)
            mic.set(lcx - r * 1.3f, oy - r * 1.3f, lcx + r * 1.3f, oy + r * 1.3f)
            y = oy + r * 1.3f
        }
        // 조작 줄(맨 아래 한 줄 — [대화형 | 정보형] · ⇄)
        val micR = u * 0.038f
        val micY = h - pad - micR
        val textBottom = micY - micR - u * 0.025f
        // 내 질문(작게) → 현비서 답(표가 있을 때 여기, 없으면 오른쪽 크게)
        if (mode == Mode.LISTENING || mode == Mode.THINKING) {
            text(c, mode.label, lcx, y + u * 0.028f, u * 0.024f, if (mode == Mode.LISTENING) OK else MUTED, align = Paint.Align.CENTER)
            y += u * 0.045f
        }
        val qs = u * 0.023f
        if (L.answerAtChar) {   // 캐릭터 칸 말풍선 — 내 질문(노랑, 꼬리 = 화면 밖 운전자) → 현비서 답(꼬리 = 캐릭터)
            val outX = if (L.charRight) w + u * 0.2f else -u * 0.2f
            if (userLine.isNotEmpty() && !driving)
                balloonText(c, RectF(lx0, y + u * 0.005f, lx1, min(textBottom, y + u * 0.14f)), userLine, u * 0.026f, u * 0.021f, body, C.bubbleMeText, C.bubbleMe, u,
                    outX, y + u * 0.2f, 0, anchorRight = !L.charRight, anchorBottom = false, maxLines = 2)?.let { y = it.bottom + u * 0.018f }
            if (hyunLine.isNotBlank())
                balloonText(c, RectF(lx0, y, lx1, textBottom), hyunLine, if (driving) u * 0.04f else u * 0.03f, u * 0.022f, bold, FG, C.bubbleHyun, u,
                    charFace.centerX(), charFace.top, if (prio == 0) 1 else 0, anchorRight = L.charRight, anchorBottom = false, maxLines = if (driving) 3 else 9)
        } else if (userLine.isNotEmpty() && !driving) {
            text(c, name.ifEmpty { "나" }, lx0, y + qs, u * 0.019f, MUTED)
            y = wrap(c, userLine, lx0, y + qs * 2.3f, qs, SUB, lx1 - lx0, 3) + u * 0.012f
        }
        if (!L.answerAtChar && k != null && hyunLine.isNotBlank()) {
            val hs = u * 0.026f
            val lines = ((textBottom - y - hs) / (hs * 1.42f)).toInt().coerceIn(1, 8)
            text(c, botName, lx0, y + hs * 0.9f, u * 0.019f, moodColor())
            wrap(c, hyunLine, lx0, y + hs * 2.1f, hs, FG, lx1 - lx0, lines, bold)
        }
        ctrlRow(c, lx0, lx1, micY, micR, u)
        // 오른쪽 칸 — 길 안내 중이면 내비 판(표가 뜨면 한 줄 띠)
        val x0 = if (L.charRight) pad else colW + pad * 0.7f; val x1 = if (L.charRight) w - colW - pad * 0.7f else w - pad
        var top = pad
        if (dest != null) top = (if (k != null) naviStrip(c, x0, top, x1, u) else naviPanel(c, x0, top, x1, min(u * 0.44f, h * 0.52f), u)) + u * 0.03f
        top = featureLines(c, x0, top, x1, u * 0.064f, u)
        if (k != null) { listCard(c, k, x0, top, x1, h - pad, u); return }
        cardHit.setEmpty()
        var tx = x0
        val size = if (driving) u * 0.054f else u * 0.046f
        if (prio == 0) tx = pill(c, "안전", x0, top + size * 0.9f, u * 0.026f, PRIO[0]) + u * 0.015f
        val chips = !driving && memory.isNotEmpty()
        val bottom = h - pad - (if (chips) u * 0.1f else 0f)
        if (L.answerAtChar) {   // 답은 캐릭터 칸 말풍선 — 정보 칸은 정보만
            if (!driving) infoBoard(c, RectF(x0, top, x1, bottom), u)
        } else {
            val lines = ((bottom - top - size * 0.3f) / (size * 1.42f)).toInt().coerceIn(1, if (driving) 3 else 7)
            wrap(c, hyunLine, tx, top + size * 1.1f, size, FG, x1 - tx, lines, bold)
        }
        if (chips)   // 기억(취향) — 오른쪽 칸 맨 아래
            chipRow(c, b, "${botName}가 아는 것 ${memory.size}", memory.takeLast(8).reversed(), h - pad - u * 0.06f, x0, x1)
    }

    /** 기능 한 줄들 — 둥근 띠(왼쪽 기분 색 막대 · 아이콘 · 글). 반환 = 아래 끝 y(다음 요소 시작) */
    private fun featureLines(c: Canvas, x0: Float, y0: Float, x1: Float, lh: Float, u: Float): Float {
        var y = y0
        for (ln in lines.take(2)) {
            val r = RectF(x0, y, x1, y + lh)
            kit.panel(c, r, lh * 0.3f, CARD, u)
            p.color = moodColor(); c.drawRoundRect(RectF(r.left, r.top, r.left + u * 0.008f, r.bottom), u * 0.004f, u * 0.004f, p)
            val fs = lh * 0.42f
            text(c, ln.icon, r.left + u * 0.022f, r.centerY() + fs * 0.36f, fs, FG)
            text(c, ln.text, r.left + u * 0.022f + fs * 1.5f, r.centerY() + fs * 0.36f, fs, FG, bold, maxW = r.right - u * 0.02f - (r.left + u * 0.022f + fs * 1.5f))
            y = r.bottom + u * 0.016f
        }
        return if (lines.isEmpty()) y0 else y + u * 0.01f
    }

    // ---------- 내비 판 — 내비 화면이 가려져도 현비서 화면에서 길을 본다(사용자 11-08). 값은 내비 그대로(TBTInfo · DrivingInfo) ----------
    /** 차 가로 오른쪽 위 — 왼쪽 검은 칸 = 다음 회전(화살표 크게 · 거리 · 회전 · 방면) + 그다음(구간 거리 — 내비 화면과 같은 값) ·
     *  오른쪽 = 목적지 · 남은 거리·시간 · 도착 · 경유지 · 진행선 · 내비 교통 · 이어지는 안내(내 차에서 누적 거리). 반환 = 아래 끝 y */
    private fun naviPanel(c: Canvas, x0: Float, y0: Float, x1: Float, hh: Float, u: Float): Float {
        val r = RectF(x0, y0, x1, y0 + hh)
        kit.panel(c, r, u * 0.025f, CARD, u)
        val gap = u * 0.022f
        var rx = r.left + gap
        val t0 = turns.firstOrNull()
        if (t0 != null) {
            val box = RectF(r.left + gap, r.top + gap, r.left + r.width() * 0.42f, r.bottom - gap)
            kit.panel(c, box, u * 0.02f, TBT_BG, u, deco = false)
            // 위: 화살표 + 거리(칸에 맞게 글자 크기) · 가운데: 회전 이름 + 방면(두 줄까지) · 맨 아래: 그다음
            val s = min(box.height() * 0.34f, u * 0.13f)
            val ix = box.left + gap * 1.1f; val iy = box.top + gap
            val iw = box.right - gap * 1.1f - ix
            turnIcon(c, t0.type, ix + s / 2, iy + s / 2, s, C.tbtFg)
            val tx = ix + s + gap; val d = dist(t0.m)
            val ds = fitSize(d, u * 0.085f, box.right - gap - tx, bold)
            text(c, d, tx, iy + s / 2 + ds * 0.36f, ds, if (t0.m in 0..300) C.near else C.tbtFg, bold)
            var ly = iy + s + gap * 1.3f
            val nm = turnName(t0.type)
            if (t0.type !in NAMED_TURNS && t0.desc.isNotBlank()) { text(c, nm, ix, ly + u * 0.024f, u * 0.026f, C.tbtMuted, maxW = iw); ly += u * 0.036f }
            val nextH = if (turns.size > 1) u * 0.075f else 0f
            val ds2 = u * 0.036f
            val room = ((box.bottom - gap - nextH - ly) / (ds2 * 1.42f)).toInt().coerceIn(1, 2)
            wrap(c, t0.desc.ifBlank { nm }, ix, ly + ds2, ds2, C.tbtFg, iw, room, bold)
            turns.getOrNull(1)?.let { n ->   // 그다음 — 회전이 잇달아 오면(내비 화면의 작은 칸과 같은 값, 앞 안내에서부터 m)
                val top = box.bottom - gap * 0.6f - nextH
                p.style = Paint.Style.FILL; p.color = 0x33FFFFFF; c.drawRect(ix, top, ix + iw, top + 2f, p)
                val ms = nextH * 0.56f; val my = top + nextH / 2 + u * 0.002f
                text(c, "그다음", ix, my + u * 0.009f, u * 0.024f, C.tbtMuted)
                p.textSize = u * 0.024f; p.typeface = body; val lx = ix + p.measureText("그다음") + gap * 0.7f
                turnIcon(c, n.type, lx + ms / 2, my, ms, C.tbtFg)
                val nx = lx + ms + gap * 0.6f; val nd = "${dist(n.m)} 뒤"
                text(c, nd, nx, my + u * 0.012f, u * 0.034f, C.tbtFg, bold)
                p.textSize = u * 0.034f; p.typeface = bold; val dx2 = nx + p.measureText(nd) + gap * 0.6f
                text(c, n.desc.ifBlank { turnName(n.type) }, dx2, my + u * 0.009f, u * 0.024f, C.tbtMuted, maxW = ix + iw - dx2)
            }
            rx = box.right + gap
        }
        // 오른쪽 — 목적지 · 남은 거리 · 도착 · 경유지 · 진행선
        val rw = r.right - gap - rx
        var y = r.top + gap + u * 0.04f
        text(c, dest ?: "", rx, y, u * 0.042f, FG, bold, maxW = rw * 0.52f)
        text(c, remain, rx + rw, y, u * 0.036f, moodColor(), bold, Paint.Align.RIGHT, maxW = rw * 0.46f)
        y += u * 0.045f
        text(c, eta, rx, y, u * 0.03f, SUB, bold, maxW = rw * 0.42f)
        if (via.isNotBlank()) text(c, via, rx + rw, y, u * 0.026f, SUB, align = Paint.Align.RIGHT, maxW = rw * 0.56f)
        y += u * 0.026f
        p.style = Paint.Style.FILL; p.shader = null; p.strokeWidth = u * 0.007f; p.strokeCap = Paint.Cap.ROUND
        p.color = C.track; c.drawLine(rx, y, rx + rw, y, p)
        p.color = moodColor(); c.drawLine(rx, y, rx + rw * progress.toFloat(), y, p)
        y += u * 0.012f
        if (trafficLine.isNotBlank()) y = wrap(c, trafficLine, rx, y + u * 0.035f, u * 0.025f, MUTED, rw, 2)
        // 이어지는 안내 — 그다음 뒤의 안내들(내 차에서 누적 거리)
        val ahead = turns.runningFold(0) { acc, x -> acc + x.m }.drop(1).zip(turns).drop(2)
        val rowH = u * 0.05f
        if (ahead.isNotEmpty() && y + u * 0.03f + rowH <= r.bottom - gap) {
            y += u * 0.03f
            text(c, "이어지는 안내", rx, y + u * 0.012f, u * 0.022f, MUTED)
            y += u * 0.012f
            for ((cum, tn) in ahead) {
                if (y + rowH > r.bottom - gap) break
                val my = y + rowH / 2
                val ms = u * 0.034f
                turnIcon(c, tn.type, rx + ms / 2, my, ms, FG)
                val nx = rx + ms + u * 0.015f
                text(c, dist(cum), nx, my + u * 0.01f, u * 0.028f, FG, bold)
                p.textSize = u * 0.028f; p.typeface = bold; val dx2 = nx + max(p.measureText(dist(cum)), u * 0.1f) + u * 0.015f
                text(c, turnLabel(tn), dx2, my + u * 0.009f, u * 0.025f, SUB, maxW = rx + rw - dx2)
                y += rowH
            }
        }
        return r.bottom
    }

    /** 내비 판 한 줄 띠 — 표(목록 카드)가 오른쪽 칸을 쓸 때: 다음 회전 · 거리 · 방면 | 목적지 · 남은 거리 · 도착. 반환 = 아래 끝 y */
    private fun naviStrip(c: Canvas, x0: Float, y0: Float, x1: Float, u: Float): Float {
        val r = RectF(x0, y0, x1, y0 + u * 0.085f)
        kit.panel(c, r, u * 0.02f, CARD, u)
        val g = u * 0.02f; val cy = r.centerY()
        var x = r.left + g
        turns.firstOrNull()?.let { t0 ->
            val s = r.height() * 0.68f
            turnIcon(c, t0.type, x + s / 2, cy, s, FG)
            x += s + g * 0.8f
            val d = dist(t0.m)
            text(c, d, x, cy + u * 0.015f, u * 0.042f, if (t0.m in 0..300) TEAL else FG, bold)
            p.textSize = u * 0.042f; p.typeface = bold; x += p.measureText(d) + g * 0.8f
            text(c, turnLabel(t0), x, cy + u * 0.01f, u * 0.026f, SUB, maxW = max(0f, r.right - r.width() * 0.47f - x))
        }
        text(c, listOf(dest ?: "", remain, eta).filter { it.isNotBlank() }.joinToString(" · "), r.right - g, cy + u * 0.011f, u * 0.028f, moodColor(), bold,
            Paint.Align.RIGHT, maxW = r.width() * (if (turns.isEmpty()) 0.95f else 0.45f))
        return r.bottom
    }

    // ---------- 반반 화면(내비와 나란히) — 세로로 쌓기 ----------
    // 머리줄(이름·연결·알림) → 길 카드(다음 회전 한 줄 · 목적지·경유지·남은 거리·시간·도착·진행선·내비 교통 한 줄) → 캐릭터 + 내 말·현비서 답
    // → (정차) 카드가 남은 자리 전부 → 맨 아래 작은 조작 줄([대화형 | 정보형] · ⇄)
    private fun drawCarNarrow(c: Canvas, w: Float, h: Float, t: Float) {
        val u = max(w, h * 0.75f); val pad = w * 0.045f
        val x0 = pad; val x1 = w - pad; val cw = x1 - x0
        val k = card?.takeIf { !driving }
        // 머리줄
        var y = pad + u * 0.045f
        val dx = nameplate(c, x0, y, u * 0.045f) + u * 0.025f
        val (label, col) = when (brain) { true -> "Claude" to OK; false -> "로그인 필요" to WARN; null -> "서버 없음" to MUTED }
        p.style = Paint.Style.FILL; p.shader = null; p.color = col
        c.drawCircle(dx, y - u * 0.012f, u * 0.009f, p)
        text(c, label, dx + u * 0.02f, y, u * 0.026f, if (brain == true) MUTED else col, maxW = cw * 0.35f)
        if (note.isNotBlank()) text(c, note, x1, y, u * 0.026f, WARN, align = Paint.Align.RIGHT, maxW = cw * 0.45f)
        y += u * 0.03f
        // 길 카드 — 맨 위 다음 회전 한 줄(사용자 11-08 "현비서 화면에서 좌회전·우회전"), 아래 목적지
        if (dest != null) {
            val top = y
            val t0 = turns.firstOrNull()
            val turnH = if (t0 != null) u * 0.1f else 0f
            val lines = 2 + (if (via.isNotBlank()) 1 else 0) + (if (trafficLine.isNotBlank()) 1 else 0)
            val bh = turnH + u * (0.06f + lines * 0.042f)
            kit.panel(c, RectF(x0, top, x1, top + bh), u * 0.02f, CARD, u)
            val ix = x0 + u * 0.03f; val iw = cw - u * 0.06f
            if (t0 != null) {
                val tb = RectF(x0 + u * 0.012f, top + u * 0.012f, x1 - u * 0.012f, top + turnH)
                kit.panel(c, tb, u * 0.016f, TBT_BG, u, deco = false)
                val s = tb.height() * 0.74f; val cy = tb.centerY()
                turnIcon(c, t0.type, ix + s / 2, cy, s, C.tbtFg)
                var tx = ix + s + u * 0.02f
                val d = dist(t0.m)
                text(c, d, tx, cy + u * 0.018f, u * 0.05f, if (t0.m in 0..300) C.near else C.tbtFg, bold)
                p.textSize = u * 0.05f; p.typeface = bold; tx += p.measureText(d) + u * 0.02f
                // 오른쪽 끝 = 그다음(구간 거리) — 자리가 남을 때만
                var right = tb.right - u * 0.02f
                turns.getOrNull(1)?.let { n ->
                    val nd = "${dist(n.m)} 뒤"; val ns = s * 0.5f
                    p.textSize = u * 0.028f; p.typeface = bold
                    val need = ns + u * 0.01f + p.measureText(nd)
                    if (right - need - (tx + u * 0.12f) > 0) {
                        text(c, nd, right, cy + u * 0.01f, u * 0.028f, C.tbtSub, bold, Paint.Align.RIGHT)
                        turnIcon(c, n.type, right - p.measureText(nd) - u * 0.01f - ns / 2, cy, ns, C.tbtSub)
                        right -= need + u * 0.025f
                    }
                }
                text(c, turnLabel(t0), tx, cy + u * 0.01f, u * 0.026f, C.tbtSub, maxW = right - tx)
            }
            var ty = top + turnH + u * 0.055f
            text(c, dest ?: "", ix, ty, u * 0.04f, FG, bold, maxW = iw * 0.55f)
            text(c, remain, ix + iw, ty, u * 0.034f, moodColor(), bold, Paint.Align.RIGHT, maxW = iw * 0.44f)
            if (via.isNotBlank()) { ty += u * 0.042f; text(c, via, ix, ty, u * 0.028f, SUB, maxW = iw) }
            ty += u * 0.042f
            text(c, eta, ix, ty, u * 0.03f, SUB, bold)
            val ly = ty + u * 0.022f
            p.strokeWidth = u * 0.006f; p.strokeCap = Paint.Cap.ROUND
            p.color = C.track; c.drawLine(ix, ly, ix + iw, ly, p)
            p.color = moodColor(); c.drawLine(ix, ly, ix + iw * progress.toFloat(), ly, p)
            if (trafficLine.isNotBlank()) text(c, trafficLine, ix, ly + u * 0.045f, u * 0.026f, MUTED, maxW = iw)
            y = top + bh + u * 0.02f
        }
        y = featureLines(c, x0, y, x1, u * 0.07f, u)
        // 아래: 작은 조작 줄([대화형 | 정보형] · ⇄)
        val micR = u * 0.04f
        val micY = h - pad - micR
        val areaBottom = micY - micR - u * 0.02f
        // 캐릭터(왼쪽) + 말(오른쪽)
        val rowH = if (k != null) min(u * 0.26f, (areaBottom - y) * 0.36f) else min(u * 0.5f, (areaBottom - y) * 0.62f)
        val charW = min(cw * 0.36f, rowH * 0.8f)
        val img = charImage()?.let { bmp(it) }
        val cbox = if (L.charRight) RectF(x1 - charW, y, x1, y + rowH) else RectF(x0, y, x0 + charW, y + rowH)
        charDeco(c, cbox, u, t)
        if (img != null) drawImage(c, img, cbox, t) else character(c, cbox.centerX(), cbox.centerY(), min(charW, rowH) * 0.36f, t, label = false)
        mic.set(cbox); charFace.set(cbox)
        val tx = if (L.charRight) x0 else cbox.right + u * 0.025f; val tw = (if (L.charRight) cbox.left - u * 0.025f else x1) - tx
        var ty = y + u * 0.035f
        val busy = userLine.startsWith("듣는") || userLine.startsWith("말씀")   // "듣는 중…"이 이미 상태를 말한다
        if ((mode == Mode.LISTENING || mode == Mode.THINKING) && !busy) {
            text(c, mode.label, tx, ty, u * 0.026f, if (mode == Mode.LISTENING) OK else MUTED, bold); ty += u * 0.042f
        }
        if (userLine.isNotEmpty() && !driving) ty = wrap(c, userLine, tx, ty, u * 0.026f, if (busy) OK else SUB, tw, 2) + u * 0.01f
        if (prio == 0) { pill(c, "안전", tx, ty + u * 0.008f, u * 0.024f, PRIO[0]); ty += u * 0.045f }
        // 답이 칸에 다 들어가게 글자를 줄여 본다(카드가 뜨면 칸이 작다 — 11-07 화면: 두 줄에서 "…"로 잘림). 주행 중엔 크게·4줄까지
        val room = y + rowH - ty
        var hs = if (driving) u * 0.044f else u * 0.036f
        p.typeface = bold
        if (!driving) while (hs > u * 0.026f) {
            p.textSize = hs
            val need = kotlin.math.ceil(p.measureText(hyunLine) / (tw * 0.93f)).toInt().coerceAtLeast(1)
            if (need * hs * 1.42f + hs * 0.6f <= room) break
            hs *= 0.92f
        }
        val maxLines = ((room - hs * 0.2f) / (hs * 1.42f)).toInt().coerceIn(2, if (driving) 4 else 12)
        if (comic) balloonText(c, RectF(tx, ty - u * 0.01f, tx + tw, y + rowH), hyunLine, if (driving) u * 0.044f else u * 0.034f, u * 0.024f, bold, FG, C.bubbleHyun, u,
            cbox.centerX(), cbox.top + cbox.height() * 0.3f, if (prio == 0) 1 else 0, anchorRight = L.charRight, anchorBottom = false, maxLines = if (driving) 4 else 8)
        else wrap(c, hyunLine, tx, ty + hs * 0.9f, hs, FG, tw, maxLines, bold)
        y += rowH + u * 0.02f
        // 카드(정차만) — 남은 자리 전부
        if (k != null && areaBottom - y > u * 0.15f) listCard(c, k, x0, y, x1, areaBottom, u)
        else {
            cardHit.setEmpty()
            val bx = box(w, h)   // 칩은 y 아래로 그려진다 — 마이크 줄과 안 겹치게 칩 높이만큼 위로(11-07 영상: 칩이 마이크에 겹침)
            val chips = !driving && memory.isNotEmpty() && areaBottom - y > bx.u * 0.16f
            if (L.answerAtChar && !driving) infoBoard(c, RectF(x0, y + u * 0.01f, x1, areaBottom - (if (chips) bx.u * 0.11f else 0f)), u)
            if (chips) chipRow(c, bx, "${botName}가 아는 것 ${memory.size}", memory.takeLast(6).reversed(), areaBottom - bx.u * 0.075f, x0, x1)
        }
        ctrlRow(c, x0, x1, micY, micR, u)
    }

    /** 소개 카드(휴게소·인물) — 왼쪽 사진(꽉 차게 자름) · 오른쪽 제목 + "라벨 — 값" 표(추천 줄 강조) · 맨 아래 출처·사진 라이선스. 누르면 닫힘 */
    private fun infoCard(c: Canvas, k: Card, x0: Float, y0: Float, x1: Float, y1: Float, u: Float) {
        val r = RectF(x0, y0, x1, y1)
        cardHit.set(r); cardHit.offset(paddingLeft.toFloat(), paddingTop.toFloat())
        kit.panel(c, r, u * 0.025f, CARD, u)
        val gap = u * 0.028f
        val img = RectF(r.left + gap, r.top + gap, r.left + gap + r.width() * 0.4f, r.bottom - gap)
        p.color = C.card2; c.drawRoundRect(img, u * 0.018f, u * 0.018f, p)
        val bm = k.images.firstOrNull()?.let { urlBmp(it) }
        if (bm != null) {
            val s = max(img.width() / bm.width, img.height() / bm.height)
            val sw = img.width() / s; val sh = img.height() / s
            val src = android.graphics.Rect(((bm.width - sw) / 2).toInt(), ((bm.height - sh) / 2).toInt(), ((bm.width + sw) / 2).toInt(), ((bm.height + sh) / 2).toInt())
            c.save(); clip.reset(); clip.addRoundRect(img, u * 0.018f, u * 0.018f, Path.Direction.CW); c.clipPath(clip)
            p.color = Color.WHITE; p.isFilterBitmap = true; c.drawBitmap(bm, src, img, p); c.restore()
            if (comic) kit.frame(c, img, u * 0.018f, u)
        } else text(c, if (k.images.isEmpty()) "사진 없음" else "사진 불러오는 중", img.centerX(), img.centerY(), u * 0.024f, MUTED, align = Paint.Align.CENTER)
        // 오른쪽 — 제목 · 표
        val tx = img.right + gap; val tw = r.right - gap - tx
        var y = if (comic) caption(c, k.title.ifBlank { botName }, tx, r.top + gap + u * 0.038f, u * 0.036f, tw, u) + u * 0.02f
            else wrap(c, k.title.ifBlank { botName }, tx, r.top + gap + u * 0.038f, u * 0.038f, FG, tw, 2, bold) + u * 0.005f
        val srcH = if (k.source.isNotBlank()) u * 0.06f else 0f
        val bottom = r.bottom - gap - srcH
        val ls = u * 0.024f; val vs = u * 0.03f
        val labelW = min(tw * 0.32f, u * 0.17f)
        for (item in k.items.take(8)) {
            if (y + vs > bottom) break
            val parts = item.split(" — ", ": ", " - ", limit = 2)
            val lab = if (parts.size > 1) parts[0] else ""; val v = parts.last()
            val hot = lab.contains("추천")
            p.color = C.divider; p.strokeWidth = 1f; c.drawLine(tx, y, tx + tw, y, p)
            val base = y + vs * 1.25f
            if (lab.isNotBlank()) text(c, lab, tx, base, ls, if (hot) moodColor() else MUTED, bold, maxW = labelW - u * 0.01f)
            val vx = if (lab.isNotBlank()) tx + labelW else tx
            y = wrap(c, v, vx, base, vs, if (hot) moodColor() else FG, tx + tw - vx, 2, if (hot) bold else body) + vs * 0.25f
        }
        if (k.source.isNotBlank()) wrap(c, k.source, tx, r.bottom - gap - srcH + u * 0.025f, u * 0.02f, MUTED, tw, 2)
    }

    /** 목적지 한 줄 — 이름(굵게) · 남은 거리·도착(오른쪽) · 아래 얇은 진행선. 반환 = 아래 끝 y */
    private fun tripLine(c: Canvas, x0: Float, x1: Float, y: Float, u: Float): Float {
        val base = y + u * 0.04f
        text(c, dest ?: "", x0, base, u * 0.034f, FG, bold, maxW = (x1 - x0) * 0.5f)
        text(c, "$remain   $eta", x1, base, u * 0.032f, moodColor(), bold, Paint.Align.RIGHT)
        val ly = base + u * 0.025f
        p.style = Paint.Style.FILL; p.shader = null; p.strokeWidth = u * 0.006f; p.strokeCap = Paint.Cap.ROUND
        p.color = C.track; c.drawLine(x0, ly, x1, ly, p)
        p.color = moodColor(); c.drawLine(x0, ly, x0 + (x1 - x0) * progress.toFloat(), ly, p)
        return ly + u * 0.01f
    }

    /** 아래 조작 줄(차) — [대화형 | 정보형] 두 칸 · 캐릭터 바꾸기 ⇄. 가운데 맞춤(좁으면 왼쪽부터).
     *  마이크·듣기 모드 버튼은 없다 — 상시 듣기가 기본이라 버튼이 필요 없다(사용자 11-09). 말 걸기 = "현비서야" · 캐릭터를 눌러도 대화 창이 열린다 */
    private fun ctrlRow(c: Canvas, x0: Float, x1: Float, cy: Float, r: Float, u: Float) {
        val br = r * 0.62f; val gap = u * 0.014f
        val fs = u * 0.021f
        p.textSize = fs; p.typeface = bold
        val labW = p.measureText("대화형") + fs * 2f
        val segW = min(labW, (x1 - x0 - br * 4 - gap * 2.5f) / 2)
        val sh = r * 1.15f
        val groupW = segW * 2 + gap * 0.5f + (gap + br * 2) * 2
        val sx = max(x0, (x0 + x1) / 2 - groupW / 2)
        segChat.set(sx, cy - sh / 2, sx + segW, cy + sh / 2)
        segInfo.set(segChat.right + gap * 0.5f, cy - sh / 2, segChat.right + gap * 0.5f + segW, cy + sh / 2)
        for ((rect, lab, on) in listOf(Triple(segChat, "대화형", uiChat), Triple(segInfo, "정보형", !uiChat))) {
            kit.panel(c, rect, rect.height() / 2, if (on) ACCENT else CARD, u)
            text(c, lab, rect.centerX(), rect.centerY() + fs * 0.36f, fs, if (on) C.onAccent else MUTED, if (on) bold else body, Paint.Align.CENTER, maxW = rect.width() * 0.94f)
        }
        // 캐릭터 바꾸기 — 누를 때마다 사람 비서 → 젤리 구슬 → 그린 구슬
        val bx = segInfo.right + gap + br
        charBtn.set(bx - br, cy - br, bx + br, cy + br)
        kit.circle(c, bx, cy, br, CARD, u)
        text(c, "⇄", bx, cy + br * 0.36f, br, FG, bold, Paint.Align.CENTER)
        // 스킨 바꾸기 — 팔레트(그 스킨의 기분 색 점 셋), 누를 때마다 다음 스킨
        val kx = bx + br * 2 + gap
        skinBtn.set(kx - br, cy - br, kx + br, cy + br)
        kit.circle(c, kx, cy, br, CARD, u)
        listOf("calm", "happy", "fun").forEachIndexed { i, m ->
            val a = Math.toRadians(-90.0 + i * 120.0)
            p.style = Paint.Style.FILL; p.shader = null; p.color = C.mood(m)[0]
            c.drawCircle(kx + cos(a).toFloat() * br * 0.38f, cy + sin(a).toFloat() * br * 0.38f, br * 0.2f, p)
        }
    }

    // ---------- 대화형 — 캐릭터와 대화만(사용자 11-09). 큰 캐릭터 + 말풍선(최근 대화) ----------
    // 1칸(가로) = 왼쪽 42% 캐릭터 · 오른쪽 말풍선 / 반반(좁은 칸) = 위 캐릭터 · 아래 말풍선. 아래 조작 줄은 정보형과 같은 자리(왼쪽 아래).
    // 길 안내 중이면 맨 위 한 줄 띠(다음 회전 · 목적지) — 1칸이면 내비가 가려져 회전을 볼 데가 여기뿐. 표(목록·소개)는 그리지 않고 "정보형에서 보기" 칩만
    private fun drawChat(c: Canvas, w: Float, h: Float, t: Float) {
        val wide = w >= h * 1.15f
        val u = if (wide) min(w, h) else max(w, h * 0.75f)
        val pad = if (wide) u * 0.045f else w * 0.045f
        cardHit.setEmpty()
        // 머리줄 — 이름 · 연결 점 · (오른쪽) 알림
        var y = pad + u * 0.04f
        val dx = nameplate(c, pad, y, u * 0.04f) + u * 0.025f
        val (label, col) = when (brain) { true -> "Claude" to OK; false -> "로그인 필요" to WARN; null -> "서버 없음" to MUTED }
        p.style = Paint.Style.FILL; p.shader = null; p.color = col
        c.drawCircle(dx, y - u * 0.01f, u * 0.009f, p)
        text(c, label, dx + u * 0.02f, y, u * 0.024f, if (brain == true) MUTED else col, maxW = w * 0.25f)
        if (note.isNotBlank()) text(c, note, w - pad, y, u * 0.024f, WARN, align = Paint.Align.RIGHT, maxW = w * 0.5f)
        y += u * 0.03f
        if (dest != null) y = naviStrip(c, pad, y, w - pad, u) + u * 0.025f
        val ctrlR = u * 0.038f; val ctrlY = h - pad - ctrlR
        val charBox: RectF; val chatBox: RectF
        if (wide) {
            val cw = w * L.chatCharW
            charBox = if (L.charRight) RectF(w - cw, y, w - pad, ctrlY - ctrlR - u * 0.025f) else RectF(pad, y, cw, ctrlY - ctrlR - u * 0.025f)
            chatBox = if (L.charRight) RectF(pad, y, w - cw - pad, h - pad) else RectF(cw + pad, y, w - pad, h - pad)
            ctrlRow(c, charBox.left, charBox.right, ctrlY, ctrlR, u)
        } else {
            charBox = RectF(pad, y, w - pad, y + (ctrlY - ctrlR - y) * 0.42f)
            chatBox = RectF(pad, charBox.bottom + u * 0.025f, w - pad, ctrlY - ctrlR - u * 0.025f)
            ctrlRow(c, pad, w - pad, ctrlY, ctrlR, u)
        }
        // 캐릭터 크게 — 사람 비서는 상반신 그림을 칸 가득, 구슬은 가운데 정사각형
        val img = charImage()?.let { bmp(it) }
        val face = if (character == "human") charBox else {
            val s = min(charBox.width(), charBox.height()) * 0.82f
            RectF(charBox.centerX() - s / 2, charBox.centerY() - s / 2, charBox.centerX() + s / 2, charBox.centerY() + s / 2)
        }
        charDeco(c, face, u, t)
        if (img != null) drawImage(c, img, face, t) else character(c, face.centerX(), face.centerY(), face.width() * 0.34f, t, label = false)
        mic.set(face); charFace.set(face)   // 캐릭터를 누르면 대화 창(버튼은 따로 없다)
        bubbles(c, chatBox, u, hyunRight = L.charRight)
    }


    // ---------- 만화 말풍선(스킨 bubbleShape = comic) ----------
    /** 표가 왔을 때 "정보형에서 보기" 칩(대화형) — 반환 = 칩 아래 y */
    private fun cardChipRow(c: Canvas, box: RectF, u: Float): Float {
        val k = card?.takeIf { !driving } ?: run { cardChip.setEmpty(); return box.top }
        val fs = u * 0.026f; val chH = u * 0.064f
        val s = "▤ ${k.title.ifBlank { "목록" }} · 정보형에서 보기"
        p.textSize = fs; p.typeface = bold
        val r = RectF(box.left, box.top, min(box.right, box.left + p.measureText(s) + chH), box.top + chH)
        kit.panel(c, r, chH / 2, C.card2, u)
        text(c, s, r.left + chH / 2, r.centerY() + fs * 0.36f, fs, ACCENT, bold, maxW = r.width() - chH)
        cardChip.set(r)
        return r.bottom + u * 0.025f
    }

    /**
     * 말풍선 하나 — 글이 칸(area)에 들어가게 글자 크기를 maxSize → minSize로 줄여 보고, 그래도 넘치면 줄을 자른다. 반환 = 그린 풍선(없으면 null).
     * anchorRight = 칸 오른쪽에 붙임 · anchorBottom = 칸 아래에 붙임 · 꼬리 = (tx, ty) 쪽 · kind 0 말 · 1 외침 · 2 생각
     */
    private fun balloonText(c: Canvas, area: RectF, s: String, maxSize: Float, minSize: Float, face: Typeface, color: Int, fill: Int, u: Float,
                            tx: Float, ty: Float, kind: Int, anchorRight: Boolean, anchorBottom: Boolean, maxLines: Int = 12): RectF? {
        if (s.isBlank() || area.width() < u * 0.1f || area.height() < u * 0.06f) return null
        var fs = maxSize
        var ls: List<String>
        var tw: Float
        while (true) {
            ls = lines(s, fs, area.width() * 0.74f - fs, face)
            p.textSize = fs; p.typeface = face
            tw = ls.maxOfOrNull { p.measureText(it) } ?: 0f
            val bh = (ls.size * fs * 1.32f + fs * 0.5f) / 0.7f
            if ((bh <= area.height() && ls.size <= maxLines) || fs <= minSize) break
            fs = max(minSize, fs * 0.92f)
        }
        val fit = min(maxLines, max(1, ((area.height() * 0.7f - fs * 0.5f) / (fs * 1.32f)).toInt()))
        if (ls.size > fit) ls = ls.take(fit - 1) + (ls[fit - 1] + "…")
        val bw = min(area.width(), max((tw + fs * 1.2f) / 0.74f, fs * 5f))
        val bh = min(area.height(), max((ls.size * fs * 1.32f + fs * 0.5f) / 0.7f, fs * 2.6f))
        val left = if (anchorRight) area.right - bw else area.left
        val top = if (anchorBottom) area.bottom - bh else area.top
        val r = RectF(left, top, left + bw, top + bh)
        // 꼬리는 캐릭터(또는 화면 밖 운전자) 쪽으로, 위아래로는 풍선 높이 안에서만 — 가파르면 위·아래 풍선을 뚫는다(11-09 화면)
        kit.balloon(c, r, fill, u, tx, r.centerY() + (ty - r.centerY()).coerceIn(-r.height() * 0.45f, r.height() * 0.45f), kind)
        val y0 = r.centerY() - (ls.size - 1) * fs * 1.32f / 2 + fs * 0.36f
        ls.forEachIndexed { i, line -> text(c, line, r.centerX(), y0 + i * fs * 1.32f, fs, color, face, Paint.Align.CENTER, maxW = r.width() * 0.84f) }
        return r
    }

    /** 만화 캡션 상자(카드 제목) — 노란 딱지 + 잉크 테두리. 반환 = 아래 끝 y */
    private fun caption(c: Canvas, s: String, x: Float, base: Float, size: Float, maxW: Float, u: Float): Float {
        p.textSize = size; p.typeface = kit.skin.fonts.title
        val tw = min(p.measureText(s), maxW - size * 1.2f)
        val r = RectF(x - size * 0.4f, base - size * 1.15f, x + tw + size * 0.6f, base + size * 0.4f)
        kit.panel(c, r, size * 0.2f, C.card2, u)
        text(c, s, x + size * 0.1f, base, size, FG, kit.skin.fonts.title, maxW = maxW - size * 0.8f)
        return r.bottom
    }

    /** 정보 보드(정보형 정보 칸, 카드가 없을 때) — 최근 본 정보(누르면 다시 열림) · 현비서가 아는 것 · 물어볼 거리. 정차 중만 */
    private fun infoBoard(c: Canvas, box: RectF, u: Float) {
        recentHits.clear()
        if (box.height() < u * 0.15f) return
        var y = box.top + u * 0.01f
        val rc = recentCards.filter { it.title.isNotBlank() }.asReversed()
        if (rc.isNotEmpty()) {
            y = caption(c, "최근 본 정보", box.left + u * 0.012f, y + u * 0.04f, u * 0.026f, box.width(), u) + u * 0.02f
            val gap = u * 0.018f; val cols = if (box.width() > u * 1.0f) 3 else 2
            val cw = (box.width() - gap * (cols - 1)) / cols; val chH = u * 0.12f
            for ((i, k) in rc.take(cols * 2).withIndex()) {
                val cx = box.left + (i % cols) * (cw + gap); val cy = y + (i / cols) * (chH + gap)
                if (cy + chH > box.bottom) break
                val r = RectF(cx, cy, cx + cw, cy + chH)
                kit.panel(c, r, u * 0.02f, CARD, u)
                k.images.firstOrNull()?.let { urlBmp(it) }?.let { bm ->   // 표지·사진 한 장(있으면)
                    val ib = RectF(r.left + u * 0.012f, r.top + u * 0.012f, r.left + u * 0.012f + chH * 0.62f, r.bottom - u * 0.012f)
                    val s = max(ib.width() / bm.width, ib.height() / bm.height); val sw = ib.width() / s; val sh = ib.height() / s
                    c.save(); clip.reset(); clip.addRoundRect(ib, u * 0.01f, u * 0.01f, Path.Direction.CW); c.clipPath(clip)
                    p.color = Color.WHITE; p.isFilterBitmap = true
                    c.drawBitmap(bm, android.graphics.Rect(((bm.width - sw) / 2).toInt(), ((bm.height - sh) / 2).toInt(), ((bm.width + sw) / 2).toInt(), ((bm.height + sh) / 2).toInt()), ib, p); c.restore()
                }
                val tx = if (k.images.isNotEmpty()) r.left + u * 0.024f + chH * 0.62f else r.left + u * 0.022f
                wrap(c, k.title, tx, r.top + u * 0.045f, u * 0.026f, FG, r.right - u * 0.016f - tx, 2, bold)
                text(c, "${k.items.size}개 · 다시 보기", tx, r.bottom - u * 0.018f, u * 0.02f, MUTED, maxW = r.right - u * 0.016f - tx)
                recentHits += RectF(r) to k
            }
            y += ((min(rc.size, cols * 2) + cols - 1) / cols) * (chH + gap) + u * 0.02f
        }
        if (box.bottom - y > u * 0.12f) {   // 물어볼 거리 — 정보 칸이 비지 않게(누르진 않음 · 말로)
            y = caption(c, "이렇게 물어보세요", box.left + u * 0.012f, y + u * 0.04f, u * 0.024f, box.width(), u) + u * 0.02f
            var x = box.left
            for (q in hints.map { "\"$it\"" }) {
                p.textSize = u * 0.024f; p.typeface = body
                val cw = p.measureText(q) + u * 0.04f; val chH = u * 0.058f
                if (x + cw > box.right) { x = box.left; y += chH + u * 0.014f }
                if (y + chH > box.bottom) break
                kit.panel(c, RectF(x, y, x + cw, y + chH), chH / 2, CARD, u)
                text(c, q, x + u * 0.02f, y + chH * 0.66f, u * 0.024f, SUB)
                x += cw + u * 0.014f
            }
        }
    }

    /** 대화형(만화) — 아래(최신)부터 위로: 듣는 중·생각 중 풍선 → 대사들. 현비서 대사 꼬리는 캐릭터 입 쪽, 내 말 꼬리는 화면 밖(운전자) 쪽.
     *  최신 대사는 크게, 지난 대사는 작게 · 지그재그. 주행 중엔 현비서 마지막 대사만 크게 */
    private fun comicTalk(c: Canvas, box: RectF, u: Float, hyunRight: Boolean) {
        val top = cardChipRow(c, box, u)
        var bottom = box.bottom
        val gap = u * 0.022f; val tail = u * 0.06f
        val mx = charFace.centerX(); val my = charFace.top + charFace.height() * 0.3f
        fun area(right: Boolean, inset: Float) = RectF(
            if (right) box.right - box.width() * L.bubbleMaxW else box.left + tail + inset, top,
            if (right) box.right - tail - inset else box.left + box.width() * L.bubbleMaxW, bottom)
        when (mode) {   // 듣는 중 = 내 쪽 옅은 풍선 · 생각 중 = 현비서 쪽 구름
            Mode.LISTENING -> balloonText(c, area(!hyunRight, 0f), userLine.ifBlank { "말씀하세요…" }, u * 0.03f, u * 0.026f, body, OK, C.card2, u,
                if (!hyunRight) box.right + u * 0.1f else box.left - u * 0.1f, bottom + u * 0.15f, 0, !hyunRight, true, 2)?.let { bottom = it.top - gap }
            Mode.THINKING -> balloonText(c, area(hyunRight, 0f), "음" + ".".repeat((((System.nanoTime() - t0) / 400_000_000L) % 4).toInt()), u * 0.036f, u * 0.03f,
                bold, MUTED, C.bubbleHyun, u, mx, my, 2, hyunRight, true, 1)?.let { bottom = it.top - gap }
            else -> {}
        }
        var items: List<Bubble> = if (driving) chat.filter { !it.me }.takeLast(1) else chat.toList()
        if (items.isEmpty() && hyunLine.isNotBlank()) items = listOf(Bubble(false, hyunLine))
        var firstHyun = true
        for ((i, b) in items.asReversed().withIndex()) {
            if (bottom - top < u * 0.08f) break
            val right = if (b.me) !hyunRight else hyunRight
            val big = i == 0 || driving
            val size = if (driving) u * 0.05f else if (big) u * 0.04f else u * 0.031f
            val kind = if (!b.me && firstHyun && prio == 0) 1 else 0
            if (!b.me) firstHyun = false
            val ttx = if (b.me) (if (right) box.right + u * 0.1f else box.left - u * 0.1f) else mx
            val tty = if (b.me) bottom + u * 0.15f else my
            val r = balloonText(c, area(right, if (i % 2 == 1) u * 0.035f else 0f), b.text, size, size * 0.82f, if (b.me) body else bold,
                if (b.me) C.bubbleMeText else FG, if (b.me) C.bubbleMe else C.bubbleHyun, u, ttx, tty, kind, right, true, if (big) 8 else 3) ?: break
            bottom = r.top - gap
        }
    }

    /** 말풍선 — 아래(최신)부터 위로 쌓는다. 내 말 = 오른쪽 · 현비서 = 왼쪽(이름은 기분 색). 맨 아래 = 듣는 중·생각 중.
     *  주행 중엔 현비서 마지막 말만 크게(정보형과 같은 화면 규칙 — 내 말은 정차 때만) */
    private fun bubbles(c: Canvas, box: RectF, u: Float, hyunRight: Boolean) {
        if (comic) { comicTalk(c, box, u, hyunRight); return }
        var bottom = box.bottom
        val status = when (mode) {
            Mode.LISTENING -> userLine.ifBlank { "듣는 중…" }
            Mode.THINKING -> "생각 중" + ".".repeat((((System.nanoTime() - t0) / 400_000_000L) % 4).toInt())
            else -> ""
        }
        if (status.isNotBlank()) {
            val right = if (mode == Mode.LISTENING) !hyunRight else hyunRight   // 듣는 중 = 내 쪽 · 생각 중 = 현비서 쪽
            text(c, status, if (right) box.right else box.left, bottom - u * 0.012f, u * 0.03f,
                if (mode == Mode.LISTENING) OK else MUTED, bold, if (right) Paint.Align.RIGHT else Paint.Align.LEFT, maxW = box.width())
            bottom -= u * 0.065f
        }
        var top = box.top
        val k = card?.takeIf { !driving }
        if (k != null) {   // 표는 정보형에서 — 눌러서 바로 바꾸기
            val fs = u * 0.026f; val chH = u * 0.064f
            val s = "▤ ${k.title.ifBlank { "목록" }} · 정보형에서 보기"
            p.textSize = fs; p.typeface = bold
            val r = RectF(box.left, top, min(box.right, box.left + p.measureText(s) + chH), top + chH)
            kit.panel(c, r, chH / 2, C.card2, u)
            text(c, s, r.left + chH / 2, r.centerY() + fs * 0.36f, fs, ACCENT, bold, maxW = r.width() - chH)
            cardChip.set(r)
            top = r.bottom + u * 0.025f
        } else cardChip.setEmpty()
        var items: List<Bubble> = if (driving) chat.filter { !it.me }.takeLast(1) else chat.toList()
        if (items.isEmpty() && hyunLine.isNotBlank()) items = listOf(Bubble(false, hyunLine))
        val fs = if (driving) u * 0.05f else u * 0.034f
        val padX = fs * 0.8f; val padY = fs * 0.6f; val gap = u * 0.024f; val lh = fs * 1.42f
        val tailW = if (kit.skin.style.bubbleTail) u * 0.032f else 0f   // 꼬리 자리
        val maxW = box.width() * L.bubbleMaxW - tailW
        for (b in items.asReversed()) {
            val face = if (b.me) body else bold
            val all = lines(b.text, fs, maxW - padX * 2, face)
            val nameH = if (b.me) 0f else u * 0.034f
            val room = kotlin.math.floor((bottom - top - nameH - padY * 2 - fs * 1.1f) / lh).toInt() + 1
            if (room < 1) break
            val ls = if (all.size > room) all.take(room - 1) + (all[room - 1] + "…") else all
            val bh = padY * 2 + fs * 1.1f + (ls.size - 1) * lh
            p.textSize = fs; p.typeface = face
            val bw = min(maxW, (ls.maxOfOrNull { p.measureText(it) } ?: 0f) + padX * 2)
            val onRight = if (b.me) !hyunRight else hyunRight
            val r = if (onRight) RectF(box.right - tailW - bw, bottom - bh, box.right - tailW, bottom) else RectF(box.left + tailW, bottom - bh, box.left + tailW + bw, bottom)
            kit.bubble(c, r, fs * 0.7f, if (b.me) C.bubbleMe else C.bubbleHyun, u, if (onRight) 1 else -1, r.top + fs * 0.95f)
            if (!b.me && !kit.skin.style.bubbleTail) {   // 꼬리가 없으면 캐릭터 쪽 끝에 기분 색 막대
                val bx = if (onRight) r.right - u * 0.007f else r.left
                p.style = Paint.Style.FILL; p.shader = null; p.color = moodColor()
                c.drawRoundRect(RectF(bx, r.top + fs * 0.4f, bx + u * 0.007f, r.bottom - fs * 0.4f), u * 0.004f, u * 0.004f, p)
            }
            ls.forEachIndexed { i, s -> text(c, s, r.left + padX, r.top + padY + fs * 0.95f + i * lh, fs, if (b.me) C.bubbleMeText else FG, face, maxW = r.width() - padX * 2 + 1f) }
            if (!b.me) text(c, botName, if (onRight) r.right - u * 0.004f else r.left + u * 0.004f, r.top - u * 0.01f, u * 0.022f, moodColor(), bold,
                if (onRight) Paint.Align.RIGHT else Paint.Align.LEFT)
            bottom = r.top - nameH - gap
            if (bottom <= top) break
        }
    }

    // ---------- 회전 안내(TBT) 그림·말 ----------
    /** 회전 화살표 — 꺾는 각도(0 = 직진, + 오른쪽)로 그린다 · 유턴(왼쪽으로 돌아 내려옴) · 회전교차로(고리) · 도착·경유지(핀) */
    internal fun turnIcon(c: Canvas, type: String, cx: Float, cy: Float, s: Float, col: Int) {
        p.shader = null; p.color = col
        val sw = s * 0.12f; val hd = s * 0.26f
        p.strokeWidth = sw; p.strokeJoin = Paint.Join.ROUND; p.strokeCap = Paint.Cap.BUTT
        when {
            type == "TURN_ARRIVE" || type == "TURN_WAY" -> {   // 핀
                val pr = s * 0.22f; val py = cy - s * 0.1f
                p.style = Paint.Style.FILL
                arrow.reset(); arrow.moveTo(cx, cy + s * 0.42f); arrow.lineTo(cx - pr * 0.86f, py + pr * 0.5f); arrow.lineTo(cx + pr * 0.86f, py + pr * 0.5f); arrow.close()
                c.drawPath(arrow, p); c.drawCircle(cx, py, pr, p)
                p.color = TBT_BG; c.drawCircle(cx, py, pr * 0.42f, p)
            }
            type == "TURN_UTURN" -> {   // 위로 갔다 왼쪽으로 돌아 내려옴
                val rr = s * 0.17f; val top = cy - s * 0.1f
                p.style = Paint.Style.STROKE
                arrow.reset(); arrow.moveTo(cx + rr, cy + s * 0.42f); arrow.lineTo(cx + rr, top)
                arrow.arcTo(RectF(cx - rr, top - rr, cx + rr, top + rr), 0f, -180f, false)
                arrow.lineTo(cx - rr, cy + s * 0.08f)
                c.drawPath(arrow, p)
                head(c, cx - rr, cy + s * 0.08f + hd * 0.75f, 180f, hd)
            }
            else -> {
                val a = turnAngle(type); val rad = Math.toRadians(a.toDouble())
                val dx = sin(rad).toFloat(); val dy = -cos(rad).toFloat()
                val bx = cx - dx * s * 0.2f; val by = cy + s * 0.04f
                val ring = type.startsWith("TURN_ENTER_ROUNDABOUT")
                val rr = if (ring) s * 0.13f else 0f
                val tipX = bx + dx * (s * 0.46f + rr); val tipY = by + dy * (s * 0.46f + rr)
                p.style = Paint.Style.STROKE
                if (ring) {
                    p.strokeWidth = sw * 0.7f; c.drawCircle(bx, by, rr, p); p.strokeWidth = sw
                    c.drawLine(bx, cy + s * 0.44f, bx, by + rr, p)
                    c.drawLine(bx + dx * rr, by + dy * rr, tipX - dx * hd * 0.7f, tipY - dy * hd * 0.7f, p)
                } else {
                    arrow.reset(); arrow.moveTo(bx, cy + s * 0.44f); arrow.lineTo(bx, by)
                    arrow.lineTo(tipX - dx * hd * 0.7f, tipY - dy * hd * 0.7f)
                    c.drawPath(arrow, p)
                }
                head(c, tipX, tipY, a, hd)
            }
        }
        p.style = Paint.Style.FILL; p.strokeJoin = Paint.Join.MITER
    }

    /** 화살촉 — 끝점(tip)에서 각도 방향(0 = 위, + 시계 방향) */
    private fun head(c: Canvas, tx: Float, ty: Float, deg: Float, len: Float) {
        val rad = Math.toRadians(deg.toDouble())
        val dx = sin(rad).toFloat(); val dy = -cos(rad).toFloat()
        val bx = tx - dx * len; val by = ty - dy * len
        val px = -dy * len * 0.62f; val py = dx * len * 0.62f
        p.style = Paint.Style.FILL
        arrow.reset(); arrow.moveTo(tx, ty); arrow.lineTo(bx + px, by + py); arrow.lineTo(bx - px, by - py); arrow.close()
        c.drawPath(arrow, p)
    }

    /** 회전 종류 → 꺾는 각도(도, 0 = 직진, + 오른쪽). 회전교차로는 'N시 방향' */
    private fun turnAngle(type: String): Float = when {
        type == "TURN_LEFT" -> -90f
        type == "TURN_RIGHT" -> 90f
        type == "TURN_SHARP_L" -> -135f
        type == "TURN_SHARP_R" -> 135f
        type == "TURN_KEEP_L" -> -40f
        type == "TURN_KEEP_R" -> 40f
        type.startsWith("TURN_ENTER_ROUNDABOUT_") -> type.takeLast(2).toIntOrNull()?.let { hr ->
            val d = (hr % 12) * 30f; (if (d > 180f) d - 360f else d).coerceIn(-135f, 135f) } ?: 0f
        type == "TURN_EXIT_ROUNDABOUT" -> 40f
        type == "TURN_ENTER_SERVICEAREA" || type == "TURN_RESTAREA" -> 40f   // 휴게소는 오른쪽으로 빠진다
        type.endsWith("_L") -> -40f
        type.endsWith("_R") -> 40f
        else -> 0f   // 직진 · _C · 요금소 · 터널 · 합류 · 모름
    }

    private fun turnName(type: String): String = when {
        type == "TURN_LEFT" -> "좌회전"; type == "TURN_RIGHT" -> "우회전"; type == "TURN_STRAIGHT" -> "직진"; type == "TURN_UTURN" -> "유턴"
        type == "TURN_KEEP_L" -> "왼쪽 방향"; type == "TURN_KEEP_R" -> "오른쪽 방향"
        type == "TURN_SHARP_L" -> "왼쪽 급회전"; type == "TURN_SHARP_R" -> "오른쪽 급회전"
        type.startsWith("TURN_ENTER_IC") -> "고속도로 진입"; type.startsWith("TURN_EXIT_IC") -> "고속도로 나가기"
        type.startsWith("TURN_ENTER_OVER") -> "고가도로 진입"; type.startsWith("TURN_AVOID_OVER") -> "고가도로 옆길"
        type.startsWith("TURN_ENTER_UNDER") -> "지하차도 진입"; type.startsWith("TURN_AVOID_UNDER") -> "지하차도 옆길"
        type.startsWith("TURN_ENTER_ROUNDABOUT") -> "회전교차로 ${type.takeLast(2).trimStart('0')}시 방향"
        type == "TURN_EXIT_ROUNDABOUT" -> "회전교차로 나가기"; type == "TURN_DEVIDE_MERGE" -> "합류"
        type == "TURN_ENTER_SERVICEAREA" || type == "TURN_RESTAREA" -> "휴게소"
        type == "TURN_TOLL" -> "요금소"; type == "TURN_TUNNEL" -> "터널"; type == "TURN_WAY" -> "경유지"; type == "TURN_ARRIVE" -> "도착"
        else -> "안내"
    }

    /** "우회전 · 대왕판교IC 방면" — 방면 글이 회전 이름을 이미 담으면(대왕판교톨게이트 · 요금소) 하나만 */
    private fun turnLabel(tn: Turn): String {
        val name = turnName(tn.type); val d = tn.desc.trim()
        return when { d.isBlank() -> name; tn.type in NAMED_TURNS -> d; else -> "$name · $d" }
    }

    /** 칸 폭(maxW)에 맞는 글자 크기 — max에서 줄여 가며 */
    private fun fitSize(s: String, max: Float, maxW: Float, face: Typeface): Float {
        var size = max; p.typeface = face
        while (size > max * 0.5f) { p.textSize = size; if (p.measureText(s) <= maxW) break; size *= 0.93f }
        return size
    }

    /** 내비 화면과 같은 거리 표기 — 100m 넘으면 10m 단위, 1km 넘으면 0.1km */
    internal fun dist(m: Int): String = when {
        m >= 10_000 -> "${m / 1000}km"
        m >= 1000 -> String.format(java.util.Locale.US, "%.1fkm", m / 1000.0).replace(".0km", "km")
        m >= 100 -> "${(m + 5) / 10 * 10}m"
        else -> "${m.coerceAtLeast(0)}m"
    }

    /** 목록 카드(차 오른쪽 칸) — 제목·출처 + 번호 목록 한 칸(최대 10줄), 1~3위 강조, "제목 — 작가"면 작가는 옅게 */
    internal fun listCard(c: Canvas, k: Card, x0: Float, y0: Float, x1: Float, y1: Float, u: Float) {
        kit.skin = look   // 칼리 화이트 화면(StageUi)이 lightTheme만 바꿔 부르면 kit은 어두운 기본 스킨 색 그대로라 카드가 검게 나왔다(11-14)
        if (k.kind == "info") { infoCard(c, k, x0, y0, x1, y1, u); return }
        if (k.kind == "schedule") { scheduleCard(c, k, x0, y0, x1, y1, u); return }
        val r = RectF(x0, y0, x1, y1)
        cardHit.set(r); cardHit.offset(paddingLeft.toFloat(), paddingTop.toFloat())
        kit.panel(c, r, u * 0.025f, CARD, u)
        val ix = r.left + u * 0.035f; val iw = r.width() - u * 0.07f
        var ty = r.top + u * 0.055f
        val narrow = r.width() < u * 0.95f   // 반반 화면 — 제목 한 줄 전부, 출처는 아래 줄
        if (comic) caption(c, k.title.ifBlank { botName }, ix, ty, u * 0.034f, if (narrow) iw else iw * 0.6f, u)
        else text(c, k.title.ifBlank { botName }, ix, ty, u * 0.034f, FG, bold, maxW = if (narrow) iw else iw * 0.6f)
        if (k.source.isNotBlank()) {
            if (narrow) { ty += u * (if (comic) 0.05f else 0.034f); text(c, k.source, ix, ty, u * 0.022f, MUTED, maxW = iw) }   // 만화 캡션 상자가 아래로 더 내려온다
            else text(c, k.source, ix + iw, ty, u * 0.022f, MUTED, align = Paint.Align.RIGHT, maxW = iw * 0.38f)
        }
        val items = k.items.take(10)
        if (items.isEmpty()) return
        if (k.images.any { it.isNotBlank() }) { imageGrid(c, k, ix, ty + u * 0.03f, iw, r.bottom - u * 0.025f, u); return }
        val listTop = ty + u * 0.025f
        val rowH = (r.bottom - u * 0.02f - listTop) / max(items.size, 5)
        val size = min(u * 0.034f, rowH * 0.56f)
        p.textSize = size; p.typeface = bold
        val numW = p.measureText("10") + size * 0.8f
        telHits.clear()
        val hasTel = k.tels.any { it.isNotBlank() }
        val chipW = if (hasTel) min(u * 0.15f, iw * 0.22f) else 0f
        val walkRe = Regex("""걸어서\s*(\d+)\s*분""")
        val meters = items.map { s -> Regex("""(\d+)\s*m\b""").find(s.substringAfter(" — ", ""))?.groupValues?.get(1)?.toIntOrNull() }
        val nearest = if (items.any { walkRe.containsMatchIn(it) }) meters.withIndex().filter { it.value != null }.minByOrNull { it.value!! }?.index ?: -1 else -1
        items.forEachIndexed { i, s ->
            val y = listTop + rowH * i + rowH * 0.66f
            text(c, "${i + 1}", ix + numW - size * 0.8f, y, size, if (i < 3) moodColor() else MUTED, bold, Paint.Align.RIGHT)
            var parts = s.split(" — ", " - ", limit = 2)
            val face = if (i < 3) bold else body
            var avail = iw - numW - (if (hasTel) chipW + u * 0.02f else 0f)
            walkRe.find(parts.getOrNull(1).orEmpty())?.let { w ->
                val label = "걸어서 ${w.groupValues[1]}분"
                val ps = size * 0.84f
                p.textSize = ps; p.typeface = bold
                val pw = p.measureText(label) + ps * 1.2f
                val right = ix + iw - (if (hasTel) chipW + u * 0.02f else 0f)
                val pr = RectF(right - pw, listTop + rowH * i + rowH * 0.2f, right, listTop + rowH * (i + 1) - rowH * 0.2f)
                p.style = Paint.Style.FILL; p.shader = null
                p.color = if (i == nearest) ACCENT else (ACCENT and 0x00FFFFFF) or 0x40000000
                c.drawRoundRect(pr, pr.height() / 2, pr.height() / 2, p)
                text(c, label, pr.centerX(), pr.centerY() + ps * 0.36f, ps, if (i == nearest) C.tbtBg else FG, bold, Paint.Align.CENTER)
                avail -= pw + u * 0.015f
                parts = listOf(parts[0], parts[1].replace(w.value, "").split(" · ").map { it.trim() }.filter { it.isNotBlank() }.joinToString(" · "))
            }
            k.tels.getOrNull(i)?.takeIf { it.isNotBlank() }?.let {   // 가게 줄 오른쪽 [전화] — 누르기 영역은 줄 높이 거의 전부(사용자 11-11 "리스트 아래에 바로 전화하기")
                val ch = RectF(ix + iw - chipW, listTop + rowH * i + rowH * 0.14f, ix + iw, listTop + rowH * (i + 1) - rowH * 0.14f)
                p.style = Paint.Style.FILL; p.shader = null; p.color = ACCENT
                c.drawRoundRect(ch, ch.height() / 2, ch.height() / 2, p)
                text(c, "전화", ch.centerX(), ch.centerY() + size * 0.36f, size * 0.92f, Color.WHITE, bold, Paint.Align.CENTER)
                telHits += RectF(ch).apply { inset(-u * 0.01f, -rowH * 0.12f); offset(paddingLeft.toFloat(), paddingTop.toFloat()) } to i
            }
            p.textSize = size; p.typeface = face
            val tw = p.measureText(parts[0])
            text(c, parts[0], ix + numW, y, size, FG, face, maxW = avail)
            if (parts.size > 1 && tw < avail * 0.85f) text(c, parts[1], ix + numW + tw + size * 0.6f, y, size * 0.78f, MUTED, maxW = avail - tw - size * 0.6f)
            if (i < items.size - 1) {
                p.color = C.divider; p.strokeWidth = 1f
                c.drawLine(ix, listTop + rowH * (i + 1), ix + iw, listTop + rowH * (i + 1), p)
            }
        }
    }

    /** "04:00" → "새벽 4시" — 폰 [정보] 탭(Cards.easyTime)과 같은 규칙 */
    private fun easyTime(hm: String): String? {
        val m = Regex("""(\d{1,2}):(\d{2})""").find(hm) ?: return null
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
        val part = when (h) { in 0..5 -> "새벽"; in 6..11 -> "오전"; 12 -> "낮"; in 13..17 -> "오후"; in 18..20 -> "저녁"; else -> "밤" }
        val h12 = when { h == 0 -> 12; h > 12 -> h - 12; else -> h }
        return "$part ${h12}시" + if (mi > 0) " ${mi}분" else ""
    }

    /** 일정 카드(정차, 한눈) — 왼쪽 선수·팀 사진(작게, 있으면) · 오른쪽 제목 + 일정 3줄(날짜 · 읽기 쉬운 시각 크게 · 대진) · 다음 일정 강조.
     *  폰 [정보] 탭과 같은 카드 데이터(값 동일) — 폰은 전부·사진 크게, 차는 3줄·큰 글씨 */
    private fun scheduleCard(c: Canvas, k: Card, x0: Float, y0: Float, x1: Float, y1: Float, u: Float) {
        val r = RectF(x0, y0, x1, y1)
        cardHit.set(r); cardHit.offset(paddingLeft.toFloat(), paddingTop.toFloat())
        kit.panel(c, r, u * 0.025f, CARD, u)
        val gap = u * 0.028f
        val srcH = if (k.source.isNotBlank()) u * 0.05f else 0f
        var left = r.left + gap
        if (k.images.any { it.isNotBlank() }) {
            val ih = r.height() - gap * 2 - srcH
            val img = RectF(left, r.top + gap, left + min(r.width() * 0.22f, ih * 0.78f), r.top + gap + ih)
            p.style = Paint.Style.FILL; p.shader = null; p.color = C.card2
            c.drawRoundRect(img, u * 0.018f, u * 0.018f, p)
            val bm = k.images.firstOrNull { it.isNotBlank() }?.let { urlBmp(it) }
            if (bm != null) {   // 인물 사진 — 얼굴이 위쪽이라 위를 남기고 자른다
                val sc = max(img.width() / bm.width, img.height() / bm.height)
                val sw = img.width() / sc; val sh = img.height() / sc
                val sx = (bm.width - sw) / 2; val sy = (bm.height - sh) * 0.15f
                val src = android.graphics.Rect(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt())
                c.save(); clip.reset(); clip.addRoundRect(img, u * 0.018f, u * 0.018f, Path.Direction.CW); c.clipPath(clip)
                p.color = Color.WHITE; p.isFilterBitmap = true; c.drawBitmap(bm, src, img, p); c.restore()
                if (comic) kit.frame(c, img, u * 0.018f, u)
            } else text(c, "사진 불러오는 중", img.centerX(), img.centerY(), u * 0.022f, MUTED, align = Paint.Align.CENTER)
            left = img.right + gap
        }
        val tw = r.right - gap - left
        val titleY = r.top + gap + u * 0.038f
        if (comic) caption(c, k.title.ifBlank { botName }, left, titleY, u * 0.034f, tw, u)
        else text(c, k.title.ifBlank { botName }, left, titleY, u * 0.036f, FG, bold, maxW = tw)
        val rowsTop = titleY + u * (if (comic) 0.05f else 0.03f)
        val bottom = r.bottom - gap - srcH
        val rows = k.items.take(3)   // 차 = 한눈(3줄) · 폰 = 전부
        val past = { s: String -> Regex("끝남|결과|종료").containsMatchIn(s.substringBefore(" — ")) }
        val next = rows.indexOfFirst { !past(it) }
        val rh = min((bottom - rowsTop) / max(rows.size, 1), u * 0.13f)
        val dateW = min(tw * 0.34f, u * 0.25f)
        rows.forEachIndexed { i, s ->
            val top = rowsTop + rh * i
            val box = RectF(left, top + rh * 0.06f, left + tw, top + rh * 0.94f)
            if (i == next) { p.style = Paint.Style.FILL; p.shader = null; p.color = (ACCENT and 0x00FFFFFF) or 0x33000000; c.drawRoundRect(box, u * 0.016f, u * 0.016f, p) }
            val parts = s.split(" — ", limit = 2)
            val date = Regex("""\d{1,2}-\d{1,2}(\(.\))?""").find(parts[0])?.value.orEmpty()
            val hm = Regex("""\d{1,2}:\d{2}""").find(parts[0])?.value
            val big = when { past(s) -> "끝남"; hm != null -> easyTime(hm) ?: hm; else -> "시각 미정" }
            val dim = past(s)
            text(c, date, left + u * 0.02f, box.top + box.height() * 0.36f, u * 0.022f, MUTED, maxW = dateW)
            text(c, big, left + u * 0.02f, box.top + box.height() * 0.82f, u * 0.034f, if (dim) MUTED else FG, bold, maxW = dateW)
            val segs = parts.getOrNull(1).orEmpty().split(" · ")
            val rx = left + dateW + u * 0.03f; val rw = left + tw - rx - u * 0.015f
            text(c, segs.first(), rx, box.top + box.height() * 0.48f, u * 0.03f, if (dim) MUTED else FG, bold, maxW = rw)
            val sub = (if (i == next && hm != null) "$hm KST · " else "") + segs.drop(1).joinToString(" · ")
            if (sub.isNotBlank()) text(c, sub, rx, box.top + box.height() * 0.84f, u * 0.022f, MUTED, maxW = rw)
        }
        if (k.source.isNotBlank()) text(c, k.source, r.left + gap, r.bottom - gap * 0.7f, u * 0.02f, MUTED, maxW = r.width() - gap * 2)
    }

    /** 그림 목록(웹툰 표지 등) — 5칸 × 2줄 격자: 표지 + 순위 + 제목 한 줄 */
    private fun imageGrid(c: Canvas, k: Card, x0: Float, y0: Float, w: Float, y1: Float, u: Float) {
        val n = k.items.size.coerceAtMost(10)
        val cols = if (n > 6) 5 else n.coerceAtLeast(1); val rows = (n + cols - 1) / cols
        val gap = u * 0.018f
        val cw = (w - gap * (cols - 1)) / cols
        val chH = (y1 - y0 - gap * (rows - 1)) / rows
        val ts = min(u * 0.031f, cw * 0.15f)   // 제목이 작다는 화면 판정(11-06) — 키움
        for (i in 0 until n) {
            val cx = x0 + (i % cols) * (cw + gap); val cy = y0 + (i / cols) * (chH + gap)
            val imgBox = RectF(cx, cy, cx + cw, cy + chH - ts * 3.4f)   // 제목 두 줄 자리
            p.shader = null; p.style = Paint.Style.FILL; p.color = C.card2
            c.drawRoundRect(imgBox, u * 0.012f, u * 0.012f, p)
            k.images.getOrNull(i)?.let { urlBmp(it) }?.let { bm ->   // 표지 — 칸을 채우게 가운데 자르기
                val s = max(imgBox.width() / bm.width, imgBox.height() / bm.height)
                val sw = imgBox.width() / s; val sh = imgBox.height() / s
                val src = android.graphics.Rect(((bm.width - sw) / 2).toInt(), ((bm.height - sh) / 2).toInt(), ((bm.width + sw) / 2).toInt(), ((bm.height + sh) / 2).toInt())
                c.save(); clip.reset(); clip.addRoundRect(imgBox, u * 0.012f, u * 0.012f, Path.Direction.CW); c.clipPath(clip)
                p.color = Color.WHITE; p.isFilterBitmap = true; c.drawBitmap(bm, src, imgBox, p); c.restore()
                if (comic) kit.frame(c, imgBox, u * 0.012f, u)
            }
            // 순위 배지
            val br = ts * 0.95f
            p.color = if (i < 3) moodColor() else Color.WHITE   // 어두운 표지 위에서도 보이게 — 단색 배지(화면 판정 11-06)
            if (comic) kit.circle(c, imgBox.left + br * 1.2f, imgBox.top + br * 1.2f, br, p.color, u) else c.drawCircle(imgBox.left + br * 1.2f, imgBox.top + br * 1.2f, br, p)
            text(c, "${i + 1}", imgBox.left + br * 1.2f, imgBox.top + br * 1.55f, ts * 0.95f, C.tbtBg, bold, Paint.Align.CENTER)
            val title = k.items[i].split(" — ", " - ", limit = 2)[0]
            wrap(c, title, cx + cw / 2, imgBox.bottom + ts * 1.35f, ts, FG, cw, 2, if (i < 3) bold else body, center = true)
        }
    }

    /** 목록 카드(정차) — 구슬 오른쪽 전체를 쓴다: 제목·출처 한 줄 + 번호 목록(6개 넘으면 두 칸). 누르면 닫힘 */
    private fun cardPanel(c: Canvas, w: Float, b: Box) {
        val k = card ?: return
        val u = b.u; val pad = b.pad
        val left = w * 0.28f; val right = w - pad
        val top = if (dest != null) b.barTop + b.barH + pad * 0.5f else b.barTop
        val bottom = b.micY - b.micR - pad * 0.6f
        val r = RectF(left, top, right, bottom)
        cardHit.set(r); cardHit.offset(paddingLeft.toFloat(), paddingTop.toFloat())
        p.style = Paint.Style.FILL; p.color = CARD
        c.drawRoundRect(r, u * 0.03f, u * 0.03f, p)
        val ix = r.left + pad * 0.8f; val iw = r.width() - pad * 1.6f
        // 제목 줄
        val ty = r.top + u * 0.07f
        text(c, k.title.ifBlank { botName }, ix, ty, u * 0.042f, FG, bold, maxW = iw * 0.62f)
        if (k.source.isNotBlank()) text(c, k.source, ix + iw, ty, u * 0.024f, MUTED, align = Paint.Align.RIGHT, maxW = iw * 0.36f)
        // 목록
        val items = k.items.take(10)
        if (items.isEmpty()) return
        val cols = if (items.size > 6) 2 else 1
        val rows = (items.size + cols - 1) / cols
        val listTop = ty + u * 0.045f
        val rowH = ((r.bottom - pad * 0.6f) - listTop) / rows
        val size = min(u * 0.033f, rowH * 0.48f)
        val colW = (iw - (cols - 1) * pad) / cols
        p.textSize = size; p.typeface = bold
        val numW = p.measureText("10") + size * 0.6f
        items.forEachIndexed { i, s ->
            val col = i / rows; val row = i % rows
            val x = ix + col * (colW + pad)
            val y = listTop + rowH * row + rowH * 0.62f
            text(c, "${i + 1}", x + numW - size * 0.6f, y, size, if (i < 3) moodColor() else MUTED, bold, Paint.Align.RIGHT)
            // "제목 — 작가": 제목을 먼저 다 보이게, 남는 자리에만 작가(옅게)
            val parts = s.split(" — ", " - ", limit = 2)
            val face = if (i < 3) bold else body
            val avail = colW - numW
            p.textSize = size; p.typeface = face
            val tw = p.measureText(parts[0])
            text(c, parts[0], x + numW, y, size, FG, face, maxW = avail)
            if (parts.size > 1 && tw < avail * 0.8f) {
                text(c, parts[1], x + numW + tw + size * 0.5f, y, size * 0.8f, MUTED, maxW = avail - tw - size * 0.5f)
            }
            if (row < rows - 1) {   // 줄 사이 옅은 선
                p.color = C.divider; p.strokeWidth = 1f
                c.drawLine(x, listTop + rowH * (row + 1), x + colW, listTop + rowH * (row + 1), p)
            }
        }
    }

    private fun pill(c: Canvas, s: String, x: Float, base: Float, size: Float, color: Int): Float = kit.pill(c, s, x, base, size, color)

    /** 정차 화면에서만: 대화로 배운 것 — 학습형 비서를 눈으로 보여준다 */
    private fun memoryChips(c: Canvas, b: Box) {
        if (memory.isNotEmpty()) chipRow(c, b, "${botName}가 기억하는 것 ${memory.size}", memory.takeLast(8).reversed(), b.chipY)
    }

    private fun chipRow(c: Canvas, b: Box, title: String, items: List<String>, y: Float, x0: Float = b.chipX0, x1: Float = b.chipX1) {
        if (items.isEmpty()) return
        val u = b.u
        text(c, title, x0, y - u * 0.02f, u * 0.026f, MUTED)
        var x = x0; val ch = u * 0.06f; val size = u * 0.027f
        p.textSize = size
        for (m in items) {
            p.textSize = size
            val tw = p.measureText(m).coerceAtMost((x1 - x0) * 0.6f) + ch * 0.8f
            if (x + tw > x1) break
            kit.panel(c, RectF(x, y, x + tw, y + ch), ch / 2, CARD, u)
            text(c, m, x + ch * 0.4f, y + ch * 0.66f, size, FG, maxW = tw - ch * 0.8f)
            x += tw + b.pad * 0.3f
        }
    }

    private fun micButton(c: Canvas, b: Box) {
        val r = b.micR; val cx = b.micX; val cy = b.micY
        mic.set(cx - r, cy - r, cx + r, cy + r)
        p.style = Paint.Style.FILL
        p.color = when (mode) { Mode.LISTENING -> 0xFF2EA043.toInt(); Mode.SPEAKING -> 0xFF30363D.toInt(); else -> moodColor() }
        c.drawCircle(cx, cy, r, p)
        // 마이크 모양(이모지 대신 도형 — 글꼴 없는 기기 대비)
        p.color = if (mode == Mode.LISTENING || mode == Mode.SPEAKING) Color.WHITE else BG
        c.drawRoundRect(RectF(cx - r * 0.18f, cy - r * 0.48f, cx + r * 0.18f, cy + r * 0.12f), r * 0.18f, r * 0.18f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.08f
        c.drawArc(RectF(cx - r * 0.34f, cy - r * 0.3f, cx + r * 0.34f, cy + r * 0.3f), 0f, 180f, false, p)
        c.drawLine(cx, cy + r * 0.3f, cx, cy + r * 0.5f, p)
        if (onKeyboard != null) {   // 글로 묻기 — 마이크 오른쪽 작은 버튼
            val kr = r * 0.55f; val kx = cx + r * 1.9f
            kbd.set(kx - kr, cy - kr, kx + kr, cy + kr)
            p.style = Paint.Style.FILL; p.color = CARD
            c.drawCircle(kx, cy, kr, p)
            text(c, "글", kx, cy + kr * 0.35f, kr, FG, bold, Paint.Align.CENTER)
        }
        val hint = when (mode) { Mode.SPEAKING -> "눌러서 끊고 말하기"; Mode.LISTENING -> "말씀하세요"; else -> "눌러서 말하기" }
        text(c, hint, cx - r * 1.35f, cy + b.u * 0.012f, b.u * 0.03f, MUTED, align = Paint.Align.RIGHT)
    }

    private fun moodColor() = C.mood(mood)[0]

    // ---------- 글자 · 배지 — 스킨 UI 도구(Kit)에 맡긴다 ----------
    private fun wrap(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, maxW: Float, maxLines: Int, face: Typeface = body,
                     center: Boolean = false): Float = kit.wrap(c, s, x, y, size, color, maxW, maxLines, face, center)
    private fun lines(s: String, size: Float, maxW: Float, face: Typeface): List<String> = kit.lines(s, size, maxW, face)
    private fun text(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, face: Typeface = body,
                     align: Paint.Align = Paint.Align.LEFT, maxW: Float = Float.MAX_VALUE) = kit.text(c, s, x, y, size, color, face, align, maxW)
    /** 이름표 — 스킨 제목 글꼴(기울기). 반환 = 이름 오른쪽 끝 x */
    private fun nameplate(c: Canvas, x: Float, y: Float, size: Float): Float = kit.title(c, botName, x, y, size, FG)

    /** 캐릭터 뒤·옆 꾸밈(스킨 style) — 별 터짐 + 효과음 스티커. 주행 중엔 끈다(주의 분산 · 화면 규칙) */
    private fun charDeco(c: Canvas, box: RectF, u: Float, t: Float) {
        if (driving) return
        val st = kit.skin.style
        if (st.burst) kit.burst(c, box.centerX(), box.top + box.height() * 0.45f, min(box.width(), box.height()) * 0.5f, u, if (mode == Mode.READY) t * 4f else t * 24f)
        val key = when (mode) { Mode.LISTENING -> "listening"; Mode.THINKING -> "thinking"; Mode.SPEAKING -> "speaking"; else -> moodKey() }
        st.stickers[key]?.let { word ->
            val sx = if (L.charRight) box.left + box.width() * 0.16f else box.right - box.width() * 0.16f
            kit.sticker(c, word, sx, box.top + box.height() * 0.1f, u * 0.03f, if (L.charRight) -9f else 9f, u)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val x = e.x - paddingLeft; val y = e.y - paddingTop
        if (e.action == MotionEvent.ACTION_DOWN) { swDownX = e.x; swDownY = e.y }
        if (e.action == MotionEvent.ACTION_UP && !phone) {   // 가로로 밀기 = 상황 화면 넘기기(정차·시동만 — StageUi 가 판단)
            val dx = e.x - swDownX; val dy = e.y - swDownY
            if (kotlin.math.abs(dx) > width * 0.06f && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) stage?.let { if (it.swipe(dx)) { invalidate(); return true } }
        }
        if (e.action != MotionEvent.ACTION_UP) return true
        if (listOpen) { listOpen = false; invalidate(); return true }
        if (card != null && !driving) telHits.firstOrNull { it.first.contains(e.x, e.y) }?.let { onCardDial(it.second); return true }   // 목록 카드 [전화]
        if (!phone) stage?.let { if (it.touch(x, y)) { invalidate(); return true } }
        if (!phone && !charBtn.isEmpty && RectF(charBtn).apply { inset(-width() * 0.25f, -height() * 0.25f) }.contains(x, y)) { onCharacter(); return true }   // 캐릭터 바꾸기
        if (!phone && !skinBtn.isEmpty && RectF(skinBtn).apply { inset(-width() * 0.25f, -height() * 0.25f) }.contains(x, y)) { onSkin(); return true }   // 스킨 바꾸기
        if (!cardHit.isEmpty && cardHit.contains(e.x, e.y)) { cardHit.setEmpty(); onCardClose(); return true }   // 목록 카드 닫기(코어가)
        if (phone && !carHit.isEmpty && carHit.contains(e.x, e.y)) { onCarLink(); return true }   // 차 현비서 연동 화면
        if (phone && (RectF(listBtn).apply { inset(-listBtn.width() * 0.3f, -listBtn.height() * 0.3f) }.contains(x, y) || nextHit.contains(x, y))) {
            listOpen = true; invalidate(); return true
        }
        modeHits.indexOfFirst { RectF(it).apply { inset(-it.height() * 0.2f, -it.height() * 0.3f) }.contains(e.x, e.y) }.takeIf { it >= 0 }?.let { onMode(it); return true }
        if (onKeyboard != null && RectF(kbd).apply { inset(-width() * 0.2f, -height() * 0.2f) }.contains(x, y)) { onKeyboard?.invoke(); return true }
        if (!phone && !segChat.isEmpty) {   // [대화형 | 정보형]
            if (RectF(segChat).apply { inset(0f, -height() * 0.35f) }.contains(x, y)) { onUiMode(true); return true }
            if (RectF(segInfo).apply { inset(0f, -height() * 0.35f) }.contains(x, y)) { onUiMode(false); return true }
        }
        if (!phone) recentHits.firstOrNull { RectF(it.first).apply { inset(0f, -height() * 0.15f) }.contains(x, y) }?.let { onRecentCard(it.second); return true }   // 최근 본 정보 다시 열기
        if (!phone && !cardChip.isEmpty && RectF(cardChip).apply { inset(0f, -height() * 0.2f) }.contains(x, y)) { onUiMode(false); return true }   // 대화형 → 표 보기
        if (RectF(mic).apply { inset(-width() * 0.25f, -height() * 0.25f) }.contains(x, y)) onMic()
        return true
    }

    companion object {
        /** 화면이 실제로 쓰는 로봇 표정(미리 읽어 둠) — 나머지 표정도 assets/chars/bot_*.webp 에 있음 */
        private val BOT_USED = listOf("neutral", "happy", "laugh", "excited", "humming", "worried", "alert", "surprised", "thinking", "bored", "driving", "tired",
            "waving", "confused", "thumbs_up", "cool", "cheer", "sparkle", "heart_eyes", "proud")
        /** 방면 글이 곧 이름인 안내(대왕판교톨게이트 · ○○휴게소 · 경유지 이름) — 회전 이름을 앞에 붙이지 않는다 */
        private val NAMED_TURNS = setOf("TURN_TOLL", "TURN_TUNNEL", "TURN_ENTER_SERVICEAREA", "TURN_RESTAREA", "TURN_WAY", "TURN_ARRIVE")
    }
}
