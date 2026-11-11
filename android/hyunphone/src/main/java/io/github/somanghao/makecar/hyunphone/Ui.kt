package io.github.somanghao.makecar.hyunphone

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.widget.TextView

/** 디자인 결 = 댄 상황 화면(Pleos 기본): 깊은 남색 바탕 · 밝은 카드 · 청록 강조 · Pretendard */
object Ui {
    const val BG = 0xFF0F1B2D.toInt()        // 깊은 남색
    const val BG2 = 0xFF17263D.toInt()       // 머리 띠
    const val CARD = 0xFFF3F6FA.toInt()      // 밝은 카드
    const val CARD_FG = 0xFF14202E.toInt()   // 카드 글자
    const val CARD_SUB = 0xFF5B6B7D.toInt()
    const val ME = 0xFF26384F.toInt()        // 운전자 말풍선
    const val FG = 0xFFEAF0F6.toInt()
    const val MUTED = 0xFF8EA2B8.toInt()
    const val ACCENT = 0xFF1FB5B5.toInt()    // 청록
    const val OK = 0xFF3DDC97.toInt()
    const val WARN = 0xFFF2A541.toInt()

    private var regular: Typeface? = null
    private var semi: Typeface? = null

    fun font(c: Context, bold: Boolean): Typeface {
        if (regular == null) {
            regular = runCatching { Typeface.createFromAsset(c.assets, "fonts/Pretendard-Regular.otf") }.getOrDefault(Typeface.DEFAULT)
            semi = runCatching { Typeface.createFromAsset(c.assets, "fonts/Pretendard-SemiBold.otf") }.getOrDefault(Typeface.DEFAULT_BOLD)
        }
        return (if (bold) semi else regular)!!
    }

    fun dp(c: Context, v: Number) = (v.toFloat() * c.resources.displayMetrics.density).toInt()

    fun text(c: Context, s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(c).apply {
        text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        typeface = font(c, bold); setLineSpacing(0f, 1.18f)
    }

    fun round(color: Int, radiusDp: Float, c: Context, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(c, radiusDp).toFloat()
        stroke?.let { setStroke(dp(c, 1), it) }
    }

    /** 헬멧 로봇 표정(assets/chars/bot_<표정>.webp — 차 앱 상황 화면과 같은 캐릭터) */
    private val faces = HashMap<String, android.graphics.Bitmap?>()
    fun face(c: Context, name: String) = faces.getOrPut(name) {
        runCatching { c.assets.open("chars/bot_$name.webp").use { BitmapFactory.decodeStream(it) } }.getOrNull()
    }
}
