package io.github.somanghao.makecar.hyunphone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/** 가게 상세 — 피드의 장소 카드를 누르거나 '차에서 보냄' 알림을 누르면. [전화] = 전화 앱에 번호만 · [지도] = 카카오 장소 페이지 */
class PlaceActivity : Activity() {
    companion object {
        fun open(c: Context, p: JSONObject) = c.startActivity(Intent(c, PlaceActivity::class.java).putExtra("place", p.toString()))
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.statusBarColor = Ui.BG2; window.navigationBarColor = Ui.BG
        val p = intent.getStringExtra("place")?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (p == null) { finish(); return }
        setContentView(build(p))
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); recreate() }

    private fun build(p: JSONObject): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(context, 18), Ui.dp(context, 14), Ui.dp(context, 18), Ui.dp(context, 28)) }
        root.addView(Ui.text(this, "‹  차에서 물어본 것들", 14f, Ui.MUTED).apply { setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 14))
            setOnClickListener { startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)); finish() } })
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply { setImageBitmap(Ui.face(context, "excited")); layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 72), Ui.dp(context, 72)) })
        val hc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(context, 12), 0, 0, 0) }
        hc.addView(Ui.text(this, "차에서 찾은 장소", 13f, Ui.ACCENT, bold = true))
        hc.addView(Ui.text(this, p.optString("near").takeIf { it.isNotBlank() }?.let { "$it 근처" } ?: "현비서가 찾은 곳", 15f, Ui.FG))
        head.addView(hc)
        root.addView(head)
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = Ui.round(Ui.CARD, 20f, context)
            setPadding(Ui.dp(context, 18), Ui.dp(context, 18), Ui.dp(context, 18), Ui.dp(context, 16))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = Ui.dp(context, 16) } }
        card.addView(Ui.text(this, p.optString("name"), 25f, Ui.CARD_FG, bold = true))
        val sub = listOfNotNull(p.optString("cat").takeIf { it.isNotBlank() },
            p.optString("near").takeIf { it.isNotBlank() }?.let { "$it ${p.optInt("m")}m" },
            p.optInt("walk").takeIf { it > 0 }?.let { "걸어서 ${it}분" }).joinToString(" · ")
        if (sub.isNotBlank()) card.addView(Ui.text(this, sub, 14f, Ui.CARD_SUB).apply { setPadding(0, Ui.dp(context, 4), 0, 0) })
        fun line(label: String, v: String) { if (v.isNotBlank()) card.addView(row(label, v)) }
        line("주소", p.optString("addr")); line("전화", p.optString("tel"))
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, Ui.dp(context, 16), 0, 0) }
        val tel = p.optString("tel")
        if (tel.isNotBlank()) btns.addView(button("전화", true) { Place.dial(this, p) })
        if (Place.mapUrl(p).isNotBlank()) btns.addView(button("지도", tel.isBlank()) { Place.map(this, p) })
        card.addView(btns)
        root.addView(card)
        root.addView(Ui.text(this, "카카오 로컬 · 차 현비서와 동기화 — 이 폰 화면에만 보여요(서버에 저장하지 않아요)", 12f, Ui.MUTED).apply { setPadding(Ui.dp(context, 4), Ui.dp(context, 12), 0, 0) })
        return ScrollView(this).apply { setBackgroundColor(Ui.BG); fitsSystemWindows = true; addView(root) }
    }

    private fun row(label: String, v: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; setPadding(0, Ui.dp(context, 10), 0, 0)
        addView(Ui.text(context, label, 13f, Ui.CARD_SUB).apply { layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 44), -2) })
        addView(Ui.text(context, v, 15f, Ui.CARD_FG))
    }

    private fun button(s: String, primary: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = s; gravity = Gravity.CENTER; textSize = 16f; typeface = Ui.font(context, true)
        setTextColor(if (primary) 0xFF06302F.toInt() else Ui.CARD_FG)
        background = Ui.round(if (primary) Ui.ACCENT else 0xFFE1E8EF.toInt(), 24f, context)
        layoutParams = LinearLayout.LayoutParams(0, Ui.dp(context, 48), 1f).apply { marginEnd = Ui.dp(context, 10) }
        setOnClickListener { onClick() }
    }
}
