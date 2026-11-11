package io.github.somanghao.makecar.hyunphone

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * 현비서 폰 앱 — 맨 위(공통): 헬멧 로봇 + 연결 상태(작게) · 그 아래 탭 2개(사용자 11-11 "대화 기록 탭이 있고 정보 탭이 있어서 모든 대화를 다 볼 필요는 없게"):
 *  [정보](기본) — 대화에서 나온 쓸모 있는 것만 카드로(장소 목록: 가게마다 바로 [전화]·[지도] · 콘텐츠 추천 · 휴게소·인물 소개 · 교통 표), 최신 위, 대화 하나 = 카드 하나
 *  [대화] — 차에서 묻고 답한 전체 기록(시간순), 카드가 있었던 대화엔 '정보 탭에서 보기'
 * 자동 동기화(Sync)가 기본 · "폰으로 보내 줘" 알림을 누르면 [정보] 탭의 그 가게로.
 */
class MainActivity : Activity(), Sync.Listener {
    private lateinit var robot: ImageView
    private lateinit var status: TextView
    private lateinit var dot: View
    private lateinit var box: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var tabInfo: TextView
    private lateinit var tabChat: TextView
    private val main = Handler(Looper.getMainLooper())
    private var tab = "info"
    private var shownChat = 0
    private var blinkAt = 0L
    private var focusMid = 0L          // 이 카드로 굴리고 강조(대화 탭 '정보 탭에서 보기' · 알림)
    private var focusName = ""         // 그 카드 안 가게 줄 강조(알림)
    private val cardViews = HashMap<Long, View>()

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Server.load(this)
        takeServer(intent)
        window.statusBarColor = Ui.BG2; window.navigationBarColor = Ui.BG
        setContentView(build())
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        Sync.listener = this
        Sync.start(this)
        takeFocus(intent)
        main.post(faceTick)
    }

    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); if (takeServer(i)) Sync.refresh(); takeFocus(i) }
    override fun onResume() { super.onResume(); Sync.listener = this; Sync.refresh(); onSync() }
    override fun onDestroy() { if (Sync.listener === this) Sync.listener = null; main.removeCallbacks(faceTick); super.onDestroy() }

    /** 인텐트 `server` extra — 시연에서 서버 주소 바꾸기(저장) */
    private fun takeServer(i: Intent?): Boolean = i?.getStringExtra("server")?.takeIf { it.startsWith("http") }?.let { Server.save(this, it); true } ?: false

    /** 알림("폰으로 보내 줘") → [정보] 탭의 그 가게 카드 · 없으면(시간이 지남 등) 가게 상세 */
    private fun takeFocus(i: Intent?) {
        val p = i?.getStringExtra("focus_place")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        i.removeExtra("focus_place")
        val name = p.optString("name")
        val hit = Sync.feed.lastOrNull { e -> e.places?.any { it.optString("name") == name } == true }
        if (hit == null) { PlaceActivity.open(this, p); return }
        tab = "info"; focusMid = hit.mid; focusName = name; onSync()
    }

    private fun build(): View {
        // 안드로이드 15(targetSdk 35)는 화면을 상태 표시줄 밑까지 그린다 → 시스템 막대만큼 안쪽 여백
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.BG2); fitsSystemWindows = true }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 18), Ui.dp(context, 12), Ui.dp(context, 12), Ui.dp(context, 10)); setBackgroundColor(Ui.BG2)
        }
        robot = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 60), Ui.dp(context, 60)); setImageBitmap(Ui.face(context, "thinking")) }
        head.addView(robot)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(context, 12), 0, 0, 0); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        col.addView(Ui.text(this, "현비서", 22f, Ui.FG, bold = true))
        val st = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        dot = View(this).apply { layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 8), Ui.dp(context, 8)).apply { marginEnd = Ui.dp(context, 6) }
            background = Ui.round(Ui.MUTED, 4f, context) }
        status = Ui.text(this, "차 현비서와 연결하는 중…", 13f, Ui.MUTED)
        st.addView(dot); st.addView(status); col.addView(st)
        head.addView(col)
        head.addView(Ui.text(this, "⚙", 22f, Ui.MUTED).apply { setPadding(Ui.dp(context, 12), Ui.dp(context, 6), Ui.dp(context, 8), Ui.dp(context, 6)); setOnClickListener { askServer() } })
        root.addView(head)
        // 탭 — [정보] [대화]
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Ui.BG2)
            setPadding(Ui.dp(context, 14), 0, Ui.dp(context, 14), Ui.dp(context, 10)) }
        tabInfo = tabView("정보") { tab = "info"; focusMid = 0; onSync(); scroll.scrollTo(0, 0) }
        tabChat = tabView("대화") { tab = "chat"; shownChat = -1; onSync() }
        tabs.addView(tabInfo); tabs.addView(tabChat)
        root.addView(tabs)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(context, 14), Ui.dp(context, 4), Ui.dp(context, 14), Ui.dp(context, 28)) }
        scroll = ScrollView(this).apply { addView(box); setBackgroundColor(Ui.BG); layoutParams = LinearLayout.LayoutParams(-1, 0, 1f) }
        root.addView(scroll)
        return root
    }

    private fun tabView(s: String, onClick: () -> Unit) = Ui.text(this, s, 15f, Ui.MUTED, bold = true).apply {
        gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, Ui.dp(context, 42), 1f).apply { marginEnd = Ui.dp(context, 6) }
        setOnClickListener { onClick() }
    }

    override fun onSync() {
        val on = Sync.online
        status.text = when (on) {
            true -> "차 현비서와 연결됨" + (Sync.carAgo?.let { " · 마지막 대화 " + if (it <= 0) "방금" else "${it}분 전" } ?: "")
            false -> "차 현비서와 연결 안 됨 — 서버 주소 확인(⚙)"
            null -> "차 현비서와 연결하는 중…"
        }
        dot.background = Ui.round(when (on) { true -> Ui.OK; false -> Ui.WARN; null -> Ui.MUTED }, 4f, this)
        val feed = Sync.feed
        val infos = feed.filter { it.hasInfo }.sortedByDescending { it.mid }
            .distinctBy { e -> if (e.places != null || e.placesGone) "m${e.mid}" else e.card.replace(" ", "").ifBlank { "m${e.mid}" } }   // 같은 질문 다시 = 최신 카드 하나
        fun paint(v: TextView, sel: Boolean, label: String) { v.text = label; v.setTextColor(if (sel) 0xFF06302F.toInt() else Ui.MUTED)
            v.background = if (sel) Ui.round(Ui.ACCENT, 21f, this) else Ui.round(0xFF22324A.toInt(), 21f, this) }
        val groups = topics(feed)
        paint(tabInfo, tab == "info", "정보 ${infos.size}"); paint(tabChat, tab == "chat", "대화 ${groups.size}")
        box.removeAllViews(); cardViews.clear()
        if (tab == "info") {
            box.addView(Ui.text(this, "차에서 찾은 결과 · 최신 위 · 자동 동기화", 12f, Ui.MUTED).apply { setPadding(Ui.dp(context, 4), Ui.dp(context, 8), 0, Ui.dp(context, 2)) })
            if (infos.isEmpty()) box.addView(empty(if (on == false) "서버에 연결되면 차에서 찾은 장소·추천이 여기에 카드로 모여요."
                else "아직 카드가 없어요.\n차에서 \"근처 족발집 알려 줘\"처럼 물어보면 여기에 카드로 저절로 떠요."))
            for (e in infos) box.addView(infoCard(e).also { cardViews[e.mid] = it })
            if (focusMid != 0L) cardViews[focusMid]?.let { v -> scroll.post { scroll.smoothScrollTo(0, (v.top - Ui.dp(this, 8)).coerceAtLeast(0)) } }
        } else {
            box.addView(Ui.text(this, "차에서 묻고 답한 기록 · 시간순 · 누르면 전체 답", 12f, Ui.MUTED).apply { setPadding(Ui.dp(context, 4), Ui.dp(context, 8), 0, Ui.dp(context, 2)) })
            if (feed.isEmpty()) box.addView(empty("아직 차에서 나눈 이야기가 없어요.\n차에서 \"현비서야\" 하고 물어보면 여기에 바로 떠요."))
            for (g in groups) box.addView(chatView(g))
            if (feed.size != shownChat) { shownChat = feed.size; scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }
        }
        face()
    }

    private fun empty(s: String) = Ui.text(this, s, 14f, Ui.MUTED).apply { setPadding(Ui.dp(context, 6), Ui.dp(context, 20), 0, 0) }

    // ---------- [정보] 카드 하나 = 대화 하나 — 종류별 모양은 Cards(장소 · 순위 · 추천 · 시세 · 일정 · 소개) ----------
    private val cards by lazy { Cards(this) { p -> PlaceActivity.open(this, p) } }
    private fun infoCard(e: Sync.Entry): View = cards.card(e, focusMid, focusName)

    // ---------- [대화] 압축(사용자 11-11 "대화 압축하는 게 좋고") — 질문 한 줄 + 답 요약 1~2문장 · 누르면 펼침 · 같은 주제 연속 질문은 묶음 ----------
    private val opened = mutableSetOf<Long>()

    /** 이어진 같은 주제(같은 질문 · 같은 카드 제목 · 첫 낱말과 낱말 하나 이상 같음)는 한 묶음 — 묶음 대표 = 마지막 것 */
    private fun topics(feed: List<Sync.Entry>): List<List<Sync.Entry>> {
        fun words(q: String) = q.split(Regex("""[\s,.?!]+""")).filter { it.length >= 2 }
        fun same(x: Sync.Entry, y: Sync.Entry): Boolean {
            if (x.who != "운전자" || y.who != "운전자") return false
            if (x.q.replace(" ", "") == y.q.replace(" ", "")) return true
            if (x.card.isNotBlank() && x.card == y.card) return true
            val a = words(x.q); val b = words(y.q)
            return a.isNotEmpty() && b.isNotEmpty() && a[0] == b[0] && a.drop(1).any { it in b.drop(1) }
        }
        val out = mutableListOf<MutableList<Sync.Entry>>()
        for (e in feed) if (out.isNotEmpty() && same(out.last().last(), e)) out.last() += e else out += mutableListOf(e)
        return out
    }

    /** 답 요약 — 문장 단위로 1~2문장(약 70자), "확인 못 했어요" 같은 문장은 요약에서 뺀다(펼치면 전부) */
    private fun brief(a: String): String {
        val ss = a.split(Regex("""(?<=[.!?])\s+""")).map { it.trim() }.filter { it.isNotBlank() }
        val neg = Regex("""확인\s*못|못\s*찾|못\s*했|지어내지|모르겠""")
        val good = ss.filterNot { neg.containsMatchIn(it) }.ifEmpty { ss }
        val out = StringBuilder()
        for (x in good) { if (out.isNotEmpty() && out.length + x.length > 70) break; if (out.isNotEmpty()) out.append(' '); out.append(x); if (out.length > 40) break }
        return out.toString().ifBlank { a.take(70) }
    }

    private fun chatView(g: List<Sync.Entry>): View {
        val e = g.last()
        val open = e.mid in opened
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, Ui.dp(context, 12), 0, 0) }
        val talk = e.who == "운전자"
        col.addView(Ui.text(this, e.t + (if (talk) "" else " · 현비서가 먼저 · ${e.q}") + (if (g.size > 1) " · 같은 주제 ${g.size}번" else "") + (if (e.live) " · 방금 동기화" else ""), 11f,
            if (e.live) Ui.ACCENT else Ui.MUTED).apply { gravity = if (talk) Gravity.END else Gravity.START })
        if (talk) {
            val q = Ui.text(this, e.q, 15f, Ui.FG).apply { background = Ui.round(Ui.ME, 16f, context); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setPadding(Ui.dp(context, 14), Ui.dp(context, 8), Ui.dp(context, 14), Ui.dp(context, 8)) }
            col.addView(FrameLayout(this).apply { setPadding(Ui.dp(context, 56), Ui.dp(context, 4), 0, 0); addView(q, FrameLayout.LayoutParams(-2, -2, Gravity.END)) })
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, Ui.dp(context, 6), Ui.dp(context, 28), 0) }
        row.addView(ImageView(this).apply { setImageBitmap(Ui.face(context, "happy"))
            layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 30), Ui.dp(context, 30)).apply { marginEnd = Ui.dp(context, 6); topMargin = Ui.dp(context, 2) } })
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = Ui.round(Ui.CARD, 16f, context)
            setPadding(Ui.dp(context, 14), Ui.dp(context, 9), Ui.dp(context, 14), Ui.dp(context, 10))
            setOnClickListener { if (!opened.remove(e.mid)) opened += e.mid; onSync() } }
        val short = brief(e.a)
        card.addView(Ui.text(this, if (open) e.a else short, 14.5f, Ui.CARD_FG))
        if (open && g.size > 1) for (x in g.dropLast(1).reversed()) card.addView(Ui.text(this, "${x.t}  “${x.q}” — ${brief(x.a)}", 12f, Ui.CARD_SUB).apply { setPadding(0, Ui.dp(context, 6), 0, 0) })
        if (!open && (short.length < e.a.length || g.size > 1)) card.addView(Ui.text(this, "더 보기 ▾", 11.5f, Ui.CARD_SUB).apply { setPadding(0, Ui.dp(context, 3), 0, 0) })
        g.lastOrNull { it.hasInfo }?.let { h -> card.addView(Ui.text(this, "▤ 정보 탭에서 보기 · ${h.card.ifBlank { "찾은 것" }}  ›", 12.5f, Ui.ACCENT, bold = true).apply {
            background = Ui.round(0xFFDDF4F3.toInt(), 12f, context); setPadding(Ui.dp(context, 10), Ui.dp(context, 6), Ui.dp(context, 10), Ui.dp(context, 6))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = Ui.dp(context, 7) }
            setOnClickListener { tab = "info"; focusMid = h.mid; focusName = ""; onSync() } }) }
        row.addView(card, LinearLayout.LayoutParams(0, -2, 1f))
        col.addView(row)
        return col
    }

    // ---------- 캐릭터 표정: 연결 끊김 worried · 연결 중 thinking · 카드 받음 excited · 새 대화 happy · 대기 neutral(가끔 깜빡임) ----------
    private val faceTick = object : Runnable { override fun run() { face(); main.postDelayed(this, 400) } }

    private fun face() {
        val now = System.currentTimeMillis()
        val since = now - Sync.lastEventAt
        val name = when {
            Sync.online == false -> "worried"
            Sync.online == null -> "thinking"
            Sync.lastEvent == "place" && since < 4000 -> "excited"
            Sync.lastEvent == "talk" && since < 3000 -> "happy"
            now - blinkAt in 0..180 -> "happy"
            else -> { if (now - blinkAt > 5200) blinkAt = now; "neutral" }
        }
        if (robot.tag != name) { robot.setImageBitmap(Ui.face(this, name)); robot.tag = name }
    }

    private fun askServer() {
        val ed = EditText(this).apply { setText(Server.base); inputType = InputType.TYPE_TEXT_VARIATION_URI; setSingleLine() }
        AlertDialog.Builder(this).setTitle("현비서 서버 주소").setMessage("차 현비서와 같은 서버(에뮬은 http://10.0.2.2:8790)").setView(ed)
            .setPositiveButton("저장") { _, _ -> Server.save(this, ed.text.toString()); Sync.online = null; Sync.refresh(); onSync() }
            .setNegativeButton("취소", null).show()
    }
}
