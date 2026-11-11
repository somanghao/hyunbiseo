package io.github.somanghao.makecar.hyun.phone

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.somanghao.makecar.hyun.brain.Link
import io.github.somanghao.makecar.hyun.feature.CarLinkFeature
import org.json.JSONObject

/**
 * 폰 — 차 현비서 연동 화면: ① 차에서 보낸 장소(가게 카드 — [전화] = 전화 앱에 번호만 · [지도] = 카카오 장소 페이지)
 * ② 차에서 나눈 이야기(서버 /hyun/history — 운전자 말 · 현비서 답 · 카드 제목, 최근 것이 위).
 * 알림을 누르거나 짭비서 첫 화면의 '차 현비서' 줄을 누르면 열린다. 장소는 인텐트·메모리로만 받는다(저장 안 함).
 */
class CarLinkActivity : Activity() {
    private lateinit var list: LinearLayout
    private val bg = 0xFF0E141B.toInt(); private val card = 0xFF18222E.toInt(); private val fg = 0xFFE7EDF3.toInt()
    private val muted = 0xFF9AABBC.toInt(); private val accent = 0xFF22C3C3.toInt(); private val line = 0xFF2A3846.toInt()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.statusBarColor = bg
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(28)) }
        root.addView(text("차 현비서", 24f, fg, bold = true))
        root.addView(text("같은 현비서 서버 · 차에서 나눈 이야기와 보낸 것을 이 폰에서 봐요", 13f, muted).apply { setPadding(0, dp(4), 0, dp(14)) })
        place()?.let { root.addView(placeCard(it)) }
        root.addView(text("차에서 나눈 이야기", 16f, fg, bold = true).apply { setPadding(0, dp(18), 0, dp(8)) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(text("불러오는 중…", 13f, muted))
        root.addView(list)
        setContentView(ScrollView(this).apply { setBackgroundColor(bg); addView(root) })
        loadHistory()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); recreate() }

    private fun place(): JSONObject? =
        intent.getStringExtra("place")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: CarLinkFeature.last

    private fun placeCard(p: JSONObject): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable().apply { setColor(card); cornerRadius = dp(18).toFloat(); setStroke(dp(1), accent) }
        }
        box.addView(text("차에서 보낸 장소", 12f, accent, bold = true))
        box.addView(text(p.optString("name"), 22f, fg, bold = true).apply { setPadding(0, dp(6), 0, dp(2)) })
        val near = p.optString("near").takeIf { it.isNotBlank() }
        val sub = listOfNotNull(p.optString("cat").takeIf { it.isNotBlank() },
            near?.let { "$it 근처 ${p.optInt("m")}m" }, p.optInt("walk").takeIf { it > 0 }?.let { "걸어서 ${it}분" }).joinToString(" · ")
        if (sub.isNotBlank()) box.addView(text(sub, 14f, muted))
        p.optString("addr").takeIf { it.isNotBlank() }?.let { box.addView(text(it, 14f, fg).apply { setPadding(0, dp(6), 0, 0) }) }
        p.optString("tel").takeIf { it.isNotBlank() }?.let { box.addView(text("전화 $it", 14f, fg).apply { setPadding(0, dp(2), 0, 0) }) }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(14), 0, 0) }
        val tel = p.optString("tel")
        if (tel.isNotBlank()) row.addView(button("전화", true) { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + tel.filter { it.isDigit() || it == '+' }))) })
        val url = p.optString("url").ifBlank {
            if (p.has("lat")) "https://map.kakao.com/link/map/${Uri.encode(p.optString("name"))},${p.optDouble("lat")},${p.optDouble("lng")}" else ""
        }
        if (url.isNotBlank()) row.addView(button("지도", tel.isBlank()) { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } })
        box.addView(row)
        box.addView(text("카카오 로컬 · 차 현비서가 보냄(이 폰에만 — 서버에 저장하지 않아요)", 11f, muted).apply { setPadding(0, dp(12), 0, 0) })
        return box
    }

    private fun loadHistory() {
        Link(CarLinkFeature.base).history(30) { r ->
            runOnUiThread {
                list.removeAllViews()
                val items = r?.optJSONArray("items")
                if (r == null) { list.addView(text("서버에 연결하지 못했어요", 13f, muted)); return@runOnUiThread }
                if (items == null || items.length() == 0) { list.addView(text("아직 차에서 나눈 이야기가 없어요", 13f, muted)); return@runOnUiThread }
                for (i in items.length() - 1 downTo 0) list.addView(row(items.getJSONObject(i)))
            }
        }
    }

    private fun row(x: JSONObject): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(card); cornerRadius = dp(14).toFloat(); setStroke(dp(1), line) }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        }
        val talk = x.optString("who") == "운전자"
        box.addView(text(x.optString("t") + (if (talk) "" else " · 현비서가 먼저 · " + x.optString("q")), 11f, muted))
        if (talk) box.addView(text("나  " + x.optString("q"), 15f, fg).apply { setPadding(0, dp(4), 0, 0) })
        box.addView(text("현비서  " + x.optString("a"), 15f, accent).apply { setPadding(0, dp(4), 0, 0) })
        x.optString("card").takeIf { it.isNotBlank() }?.let { box.addView(text("▤ $it", 12f, muted).apply { setPadding(0, dp(6), 0, 0) }) }
        return box
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s; setTextColor(color); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.15f)
    }

    private fun button(s: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = s; isAllCaps = false; setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f); gravity = Gravity.CENTER
        setTextColor(if (primary) Color.parseColor("#04201F") else fg)
        background = GradientDrawable().apply { setColor(if (primary) accent else 0xFF243140.toInt()); cornerRadius = dp(22).toFloat() }
        layoutParams = LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(10) }
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
