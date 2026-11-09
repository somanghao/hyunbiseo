package io.github.somanghao.makecar.hyun.ui.skin

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 스킨 = 차 화면의 겉모습 한 벌(사용자 11-09 — 팀원 칼리·댄이 각자 만들 수 있게). **데이터(ScreenData)는 건드리지 않는다.**
 * 정의 = `assets/skins/<id>.json` 한 파일(코드 없이) — 칸 이름·뜻은 `assets/skins/README.md`. 새 스킨은 파일만 넣으면 목록에 뜬다.
 *   colors = UI 색 · fonts = 글꼴 · layout = 배치(캐릭터 왼쪽/오른쪽 · 칸 폭) · style = 모양(모서리 · 잉크 테두리 · 딱딱한 그림자 · 배경 무늬 · 말풍선 꼬리 · 별 터짐 · 효과음 스티커)
 * JSON에 없는 칸은 기본 스킨 값을 쓴다(일부만 바꾼 스킨도 된다).
 */
data class Skin(
    val id: String,
    val name: String,
    val author: String,
    val colors: SkinColors,
    val fonts: SkinFonts,
    val layout: SkinLayout,
    val style: SkinStyle,
)

/** UI 색 — 이름 = 쓰임새. 기분 색(moods)은 캐릭터 빛·강조, prio는 말 우선순위(P0 안전 빨강) */
data class SkinColors(
    val bg: Int, val card: Int, val card2: Int, val fg: Int, val sub: Int, val muted: Int, val faint: Int, val line: Int,
    val accent: Int, val onAccent: Int, val ok: Int, val warn: Int, val track: Int, val divider: Int,
    val tbtBg: Int, val tbtFg: Int, val tbtSub: Int, val tbtMuted: Int, val near: Int,
    val bubbleMe: Int, val bubbleMeText: Int, val bubbleHyun: Int,
    val moods: Map<String, IntArray>, val prio: IntArray,
) {
    fun mood(m: String): IntArray = moods[m] ?: moods["calm"] ?: intArrayOf(accent, accent)
}

data class SkinFonts(val title: Typeface, val body: Typeface, val bold: Typeface, val medium: Typeface, val light: Typeface)

/** 배치 — charSide "left"|"right"(정보형 캐릭터 칸 · 대화형 캐릭터 · 반반 줄), 폭은 화면 폭 비율 ·
 *  answerAt "main"(정보형 답을 정보 칸에 크게) | "character"(답은 캐릭터 칸 말풍선, 정보 칸은 정보만 — 내비 판·카드·최근 본 정보, 사용자 11-09) */
data class SkinLayout(val charRight: Boolean, val infoCharW: Float, val chatCharW: Float, val bubbleMaxW: Float, val answerAtChar: Boolean)

/** 모양 — 길이는 화면 짧은 변(u) 비율. border 0 = 테두리 없음 · shadow 0 = 그림자 없음 · pattern none|halftone|grid ·
 *  bubbleShape round(둥근 상자) | comic(만화 말풍선 — 타원 · 안전 경고는 뾰족한 외침 · 생각 중은 구름, 꼬리가 캐릭터 입 쪽) */
data class SkinStyle(
    val radius: Float, val border: Float, val borderColor: Int, val shadowX: Float, val shadowY: Float, val shadowColor: Int,
    val pattern: String, val patternColor: Int, val bubbleTail: Boolean,
    val burst: Boolean, val burstColor: Int, val stickers: Map<String, String>, val stickerColor: Int, val stickerText: Int,
    val titleTilt: Float, val bubbleShape: String,
)

object Skins {
    private const val TAG = "HYUN"
    private var cache: List<Skin>? = null

    /** 스킨 목록 — 기본이 맨 앞, 나머지는 파일 이름 순. 깨진 JSON은 건너뛰고 로그(`skin|bad|…`) */
    fun all(ctx: Context): List<Skin> {
        cache?.let { return it }
        val base = basic(ctx)
        val out = mutableListOf(base)
        val files = runCatching { ctx.assets.list("skins")?.toList() }.getOrNull().orEmpty().filter { it.endsWith(".json") }.sorted()
        for (f in files) {
            val id = f.removeSuffix(".json"); if (id == "basic") continue
            runCatching { parse(ctx, JSONObject(ctx.assets.open("skins/$f").bufferedReader().readText()), base) }
                .onSuccess { out += it }.onFailure { Log.i(TAG, "skin|bad|$f|${it.message}") }
        }
        return out.also { cache = it }
    }

    fun get(ctx: Context, id: String?): Skin = all(ctx).firstOrNull { it.id == id } ?: all(ctx).first()

    /** 기본 스킨 — assets/skins/basic.json(없거나 깨지면 코드 값). 다른 스킨의 빈칸을 채우는 바탕 */
    private fun basic(ctx: Context): Skin = runCatching { parse(ctx, JSONObject(ctx.assets.open("skins/basic.json").bufferedReader().readText()), BUILTIN) }
        .getOrElse { Log.i(TAG, "skin|basic|builtin|${it.message}"); BUILTIN }

    /** 폰(짭비서) 흰 화면 — 스킨과 별개(폰은 스킨을 고르지 않는다) */
    fun phone(base: Skin): Skin = base.copy(id = "phone", colors = base.colors.copy(
        bg = 0xFFFFFFFF.toInt(), card = 0xFFF3F5F8.toInt(), card2 = 0xFFE9EDF2.toInt(), fg = 0xFF111827.toInt(), sub = 0xFF374151.toInt(),
        muted = 0xFF6B7280.toInt(), faint = 0xFF9CA3AF.toInt(), line = 0xFFE5E7EB.toInt(), accent = 0xFF0F9F92.toInt(),
        bubbleMe = 0xFFE9EDF2.toInt(), bubbleMeText = 0xFF374151.toInt(), bubbleHyun = 0xFFF3F5F8.toInt(), divider = 0x14000000),
        style = BUILTIN.style)

    fun parse(ctx: Context, j: JSONObject, base: Skin): Skin {
        val c = j.optJSONObject("colors") ?: JSONObject(); val b = base.colors
        fun col(k: String, d: Int) = c.optString(k).takeIf { it.isNotBlank() }?.let { Color.parseColor(it) } ?: d
        fun pair(a: JSONArray?) = a?.takeIf { it.length() >= 2 }?.let { intArrayOf(Color.parseColor(it.getString(0)), Color.parseColor(it.getString(1))) }
        val moods = b.moods.toMutableMap()
        c.optJSONObject("moods")?.let { m -> m.keys().forEach { k -> pair(m.optJSONArray(k))?.let { moods[k] = it } } }
        val prio = c.optJSONArray("prio")?.takeIf { it.length() == 4 }?.let { a -> IntArray(4) { Color.parseColor(a.getString(it)) } } ?: b.prio
        val colors = SkinColors(
            col("bg", b.bg), col("card", b.card), col("card2", b.card2), col("fg", b.fg), col("sub", b.sub), col("muted", b.muted), col("faint", b.faint),
            col("line", b.line), col("accent", b.accent), col("onAccent", b.onAccent), col("ok", b.ok), col("warn", b.warn), col("track", b.track),
            col("divider", b.divider), col("tbtBg", b.tbtBg), col("tbtFg", b.tbtFg), col("tbtSub", b.tbtSub), col("tbtMuted", b.tbtMuted), col("near", b.near),
            col("bubbleMe", b.bubbleMe), col("bubbleMeText", b.bubbleMeText), col("bubbleHyun", b.bubbleHyun), moods, prio)
        val f = j.optJSONObject("fonts") ?: JSONObject(); val bf = base.fonts
        fun font(k: String, d: Typeface) = f.optString(k).takeIf { it.isNotBlank() }?.let { typeface(ctx, it) } ?: d
        val fonts = SkinFonts(font("title", bf.title), font("body", bf.body), font("bold", bf.bold), font("medium", bf.medium), font("light", bf.light))
        val l = j.optJSONObject("layout") ?: JSONObject(); val bl = base.layout
        val layout = SkinLayout(
            l.optString("charSide").takeIf { it.isNotBlank() }?.let { it == "right" } ?: bl.charRight,
            l.optDouble("infoCharW", bl.infoCharW.toDouble()).toFloat().coerceIn(0.15f, 0.4f),
            l.optDouble("chatCharW", bl.chatCharW.toDouble()).toFloat().coerceIn(0.25f, 0.6f),
            l.optDouble("bubbleMaxW", bl.bubbleMaxW.toDouble()).toFloat().coerceIn(0.5f, 1f),
            l.optString("answerAt").takeIf { it.isNotBlank() }?.let { it == "character" } ?: bl.answerAtChar)
        val s = j.optJSONObject("style") ?: JSONObject(); val bs = base.style
        fun scol(k: String, d: Int) = s.optString(k).takeIf { it.isNotBlank() }?.let { Color.parseColor(it) } ?: d
        fun num(k: String, d: Float) = s.optDouble(k, d.toDouble()).toFloat()
        val shadow = s.optJSONArray("shadow")
        val stickers = s.optJSONObject("stickers")?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } } ?: bs.stickers
        val style = SkinStyle(
            num("radius", bs.radius), num("border", bs.border), scol("borderColor", bs.borderColor),
            shadow?.optDouble(0, 0.0)?.toFloat() ?: bs.shadowX, shadow?.optDouble(1, 0.0)?.toFloat() ?: bs.shadowY, scol("shadowColor", bs.shadowColor),
            s.optString("pattern", bs.pattern), scol("patternColor", bs.patternColor), s.optBoolean("bubbleTail", bs.bubbleTail),
            s.optBoolean("burst", bs.burst), scol("burstColor", bs.burstColor), stickers, scol("stickerColor", bs.stickerColor), scol("stickerText", bs.stickerText),
            num("titleTilt", bs.titleTilt), s.optString("bubbleShape", bs.bubbleShape))
        return Skin(j.optString("id", base.id), j.optString("name", base.name), j.optString("author", ""), colors, fonts, layout, style)
    }

    /** "asset:skins/fonts/Jua-Regular.ttf" · "sans-serif-medium" · "sans-serif:bold" · "default-bold" */
    private fun typeface(ctx: Context, spec: String): Typeface = runCatching {
        when {
            spec.startsWith("asset:") -> Typeface.createFromAsset(ctx.assets, spec.removePrefix("asset:"))
            spec == "default-bold" -> Typeface.DEFAULT_BOLD
            spec.endsWith(":bold") -> Typeface.create(spec.removeSuffix(":bold"), Typeface.BOLD)
            else -> Typeface.create(spec, Typeface.NORMAL)
        }
    }.getOrElse { Log.i(TAG, "skin|font|FAIL|$spec|${it.message}"); Typeface.DEFAULT }

    private fun c(v: Long) = v.toInt()

    /** 코드 바탕값 = 11-08까지의 차 화면 색 그대로(basic.json이 없어도 화면이 같다) */
    val BUILTIN = Skin(
        "basic", "기본", "현비서",
        SkinColors(
            bg = c(0xFF0B0F14), card = c(0xFF151B23), card2 = c(0xFF222A35), fg = c(0xFFE6EDF3), sub = c(0xFFB8C1CC), muted = c(0xFF8B949E),
            faint = c(0xFF6E7681), line = c(0xFF2A3440), accent = c(0xFF4FD1C5), onAccent = c(0xFF0B0F14), ok = c(0xFF3FB950), warn = c(0xFFF0B429),
            track = c(0xFF2A313C), divider = 0x14FFFFFF, tbtBg = c(0xFF000000), tbtFg = c(0xFFFFFFFF), tbtSub = c(0xFFC9D1D9), tbtMuted = c(0xFF8B949E), near = c(0xFF4FD1C5),
            bubbleMe = c(0xFF243140), bubbleMeText = c(0xFFB8C1CC), bubbleHyun = c(0xFF151B23),
            moods = mapOf(
                "calm" to intArrayOf(c(0xFF4FD1C5), c(0xFF6AA8FF)), "happy" to intArrayOf(c(0xFFFFC857), c(0xFFFF8A65)),
                "fun" to intArrayOf(c(0xFFB794F4), c(0xFFF687B3)), "care" to intArrayOf(c(0xFFFF9F43), c(0xFFFF6B8A)),
                "alert" to intArrayOf(c(0xFFFF5A52), c(0xFFFF9F43))),
            prio = intArrayOf(c(0xFFFF6B5A), c(0xFFF2A64B), c(0xFF6AA8FF), c(0xFFB18CFF))),
        SkinFonts(Typeface.DEFAULT_BOLD, Typeface.DEFAULT, Typeface.create(Typeface.DEFAULT, Typeface.BOLD),
            Typeface.create("sans-serif-medium", Typeface.NORMAL), Typeface.create("sans-serif-light", Typeface.NORMAL)),
        SkinLayout(charRight = false, infoCharW = 0.24f, chatCharW = 0.42f, bubbleMaxW = 0.84f, answerAtChar = false),
        SkinStyle(radius = 1f, border = 0f, borderColor = 0, shadowX = 0f, shadowY = 0f, shadowColor = 0, pattern = "none", patternColor = 0,
            bubbleTail = false, burst = false, burstColor = 0, stickers = emptyMap(), stickerColor = 0, stickerText = 0, titleTilt = 0f, bubbleShape = "round"),
    )
}
