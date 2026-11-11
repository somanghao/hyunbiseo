package io.github.somanghao.makecar.hyun.phone

import io.github.somanghao.makecar.hyun.HyunActivity
import io.github.somanghao.makecar.hyun.R
import io.github.somanghao.makecar.hyun.brain.Link
import io.github.somanghao.makecar.hyun.voice.Voice
import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RemoteViews
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 빠른 대화 — 다른 앱을 보는 중에 짭비서를 부르는 반투명 창(화면 아래 카드). 홈 위젯·상주 알림·앱 안 ⌨에서 연다.
 *  말하기(EXTRA_HOW=voice): 바로 듣고 → 두뇌 → 답을 말하고 → 질문이면 다시 듣고, 아니면 몇 초 뒤 닫힌다
 *  입력(text): 키보드로 쓰고 → 답은 글로(이어폰이 있으면 소리도) — 지하철·회의 중
 *  알림 답장(RemoteInput): 알림창에서 쓴 글을 받아 바로 보낸다
 * 두뇌는 같은 서버(127.0.0.1:8790, adb reverse) — 앱 본체가 꺼져 있어도 이 창만으로 대화한다.
 */
class QuickActivity : Activity(), Voice.Events {
    private val main = Handler(Looper.getMainLooper())
    private val link = Link("http://127.0.0.1:8790")
    private var voice: Voice? = null
    private lateinit var status: TextView
    private lateinit var you: TextView
    private lateinit var answer: TextView
    private lateinit var input: EditText
    private lateinit var micBtn: Button
    private var heard = ""
    private var done = false          // 이번 듣기가 끝났나
    private var speakAfter = true     // 답을 소리로도(입력 모드는 이어폰 있을 때만)
    private var listenAfter = false
    private var pendingSpeak: String? = null
    private val prefs get() = getSharedPreferences("hyun", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); window.setDimAmount(0.25f)
        setContentView(build())
        voice = Voice.create(this, false, this)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handle(intent) }

    override fun onDestroy() { main.removeCallbacksAndMessages(null); voice?.release(); super.onDestroy() }

    private fun handle(i: Intent) {
        val typed = RemoteInput.getResultsFromIntent(i)?.getCharSequence(KEY_REPLY)?.toString()
        when {
            typed != null -> { speakAfter = Desk(this).headset(); send(typed) }
            i.getStringExtra(EXTRA_HOW) == "text" -> textMode()
            else -> { speakAfter = true; main.postDelayed(::listen, 250) }
        }
        postNotification(this)   // 알림이 지워졌으면 다시
    }

    // ---------- 화면: 아래쪽 카드 ----------
    private fun build(): View {
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt() }
        val root = FrameLayout(this).apply { setOnClickListener { finish() } }   // 카드 밖을 누르면 닫힘
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(14f), dp(18f), dp(16f))
            background = GradientDrawable().apply { setColor(0xFCFFFFFF.toInt()); cornerRadius = dp(22f).toFloat() }
            elevation = dp(8f).toFloat()
            isClickable = true
        }
        val name = "현비서"
        val mode = PhoneMode.of(prefs.getString("mode", null)) ?: PhoneMode.WORK
        status = TextView(this).apply { text = "● $name · ${mode.label}"; setTextColor(TEAL); textSize = 13f; typeface = Typeface.DEFAULT_BOLD }
        you = TextView(this).apply { setTextColor(0xFF6B7280.toInt()); textSize = 15f; setPadding(0, dp(8f), 0, 0) }
        answer = TextView(this).apply { setTextColor(0xFF111827.toInt()); textSize = 19f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); setPadding(0, dp(6f), 0, dp(10f)); text = "무엇을 도와드릴까요?" }
        input = EditText(this).apply {
            hint = "현비서에게 쓰기"; setHintTextColor(0xFF9CA3AF.toInt()); setTextColor(0xFF111827.toInt()); textSize = 16f
            imeOptions = EditorInfo.IME_ACTION_SEND; setSingleLine()
            background = GradientDrawable().apply { setColor(0xFFF3F5F8.toInt()); cornerRadius = dp(14f).toFloat() }
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            setOnEditorActionListener { _, a, e -> if (a == EditorInfo.IME_ACTION_SEND || e?.keyCode == KeyEvent.KEYCODE_ENTER) { sendTyped(); true } else false }
        }
        val send = Button(this).apply { text = "보내기"; setOnClickListener { sendTyped() } }
        micBtn = Button(this).apply { text = "🎤"; setOnClickListener { if (voice?.canListen == true) listen() } }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(send); addView(micBtn)
        }
        card.addView(status); card.addView(you); card.addView(answer); card.addView(row)
        root.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(dp(10f), 0, dp(10f), dp(18f))
        })
        return root
    }

    private fun textMode() {
        speakAfter = Desk(this).headset()
        input.requestFocus()
        main.postDelayed({ (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, 0) }, 200)
    }

    private fun sendTyped() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        send(t)
    }

    // ---------- 듣기 → 두뇌 → 답 ----------
    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { answer.text = "마이크 권한이 필요해요 — 짭비서 앱을 한 번 열어 주세요"; return }
        voice?.stopSpeaking()
        heard = ""; done = false
        you.text = "듣는 중…"; micBtn.isEnabled = false
        voice?.listen()
        main.removeCallbacks(timeout); main.postDelayed(timeout, 8000)
    }

    private val timeout = Runnable { if (!done) { done = true; voice?.stopListening(); micBtn.isEnabled = true; if (heard.isNotBlank()) send(heard) else you.text = "(못 들었어요)" } }

    private fun send(text: String) {
        main.removeCallbacks(closer)
        you.text = text; answer.text = "…"; micBtn.isEnabled = true
        val mode = prefs.getString("mode", PhoneMode.WORK.key)
        val ctx = JSONObject().put("t", SimpleDateFormat("HH:mm", Locale.KOREA).format(Date())).put("device", "phone").put("bot", "현비서")
            .put("mode", mode).put("via", "quick").put("headset", Desk(this).headset()).put("driving", false)
        runCatching { Desk(this).upcoming().takeIf { it.isNotEmpty() }?.let { ctx.put("schedule", JSONArray(it.map { e -> e.line() })) } }
        Desk(this).battery().first?.let { ctx.put("soc", it) }
        link.send("talk", text, ctx) { id ->
            if (id == null) { main.post { answer.text = "PC 두뇌에 연결이 안 돼요(서버·USB 확인)" }; return@send }
            link.awaitReply(id, 45_000) { r -> main.post { onReply(r) } }
        }
    }

    private fun onReply(r: JSONObject?) {
        if (r == null || r.optBoolean("skipped")) { answer.text = "음, 생각이 길어졌네요. 다시 말해 줄래요?"; scheduleClose(); return }
        r.optString("mode").takeIf { it.isNotBlank() && it != "null" }?.let { m -> PhoneMode.of(m)?.let { prefs.edit().putString("mode", it.key).apply(); status.text = "● 짭비서 · ${it.label}" } }
        val s = r.optString("say")
        answer.text = s
        listenAfter = r.optBoolean("listen") && speakAfter
        Log.i("HYUN", "quick|reply|$s")
        if (s.isNotBlank() && speakAfter) { if (voice?.canSpeak == true) voice?.speak(s) else pendingSpeak = s } else scheduleClose()
    }

    private val closer = Runnable { finish() }
    private fun scheduleClose() { main.removeCallbacks(closer); main.postDelayed(closer, 6000) }

    // ---------- Voice.Events ----------
    override fun onVoiceReady() { main.post { pendingSpeak?.let { pendingSpeak = null; voice?.speak(it) } } }
    override fun onSpeakDone() { main.post { if (listenAfter) { listenAfter = false; main.postDelayed(::listen, 300) } else scheduleClose() } }
    override fun onSpeakStopped() {}
    override fun onHearStart() { main.post { main.removeCallbacks(timeout); main.postDelayed(timeout, 20_000) } }
    override fun onHeard(text: String, final: Boolean) { main.post { if (done) return@post; heard = text; you.text = text; if (final) { done = true; main.removeCallbacks(timeout); send(text) } } }
    override fun onHearEnd() { main.post { if (done) return@post; done = true; micBtn.isEnabled = true; if (heard.isNotBlank()) send(heard) else { you.text = "(못 들었어요)"; scheduleClose() } } }
    override fun onHearError(what: String) { main.post { if (done) return@post; done = true; micBtn.isEnabled = true; you.text = "(음성 인식 오류 — $what)" } }
    override fun onLevel(level: Float) {}

    companion object {
        const val EXTRA_HOW = "how"
        const val KEY_REPLY = "reply"
        private const val TEAL = 0xFF0F9F92.toInt()   // 흰 카드 위 청록(글자 대비)
        private const val CHANNEL = "hyun_quick"
        private const val NOTI_ID = 42

        fun intent(ctx: Context, how: String): PendingIntent = PendingIntent.getActivity(ctx, if (how == "text") 2 else 1,
            Intent(ctx, QuickActivity::class.java).putExtra(EXTRA_HOW, how).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        /** 상주 알림 — 알림창에서 바로 글로 답장 · 말하기 · 앱 열기 (폰만) */
        fun postNotification(ctx: Context) {
            if (ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) return
            if (ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "현비서 빠른 대화", NotificationManager.IMPORTANCE_LOW))
            val replyPi = PendingIntent.getActivity(ctx, 3, Intent(ctx, QuickActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)   // RemoteInput 결과를 받으려면 MUTABLE
            val reply = Notification.Action.Builder(null, "글로 묻기", replyPi)
                .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("현비서에게").build()).build()
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("현비서").setContentText("말하기 · 글로 묻기 — 다른 앱을 보면서")
                .setOngoing(true).setShowWhen(false)
                .setContentIntent(PendingIntent.getActivity(ctx, 0, Intent(ctx, HyunActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
                .addAction(Notification.Action.Builder(null, "🎤 말하기", intent(ctx, "voice")).build())
                .addAction(reply)
                .build()
            nm.notify(NOTI_ID, n)
        }
    }
}

/** 홈 화면 위젯 — 🎤 말하기 · ⌨ 입력 두 버튼. 누르면 지금 화면 위에 빠른 대화 창이 뜬다 */
class QuickWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val v = RemoteViews(ctx.packageName, R.layout.widget_quick).apply {
            setOnClickPendingIntent(R.id.w_voice, QuickActivity.intent(ctx, "voice"))
            setOnClickPendingIntent(R.id.w_text, QuickActivity.intent(ctx, "text"))
            setOnClickPendingIntent(R.id.w_name, PendingIntent.getActivity(ctx, 0, Intent(ctx, HyunActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        }
        mgr.updateAppWidget(ids, v)
    }

    companion object {
        fun refresh(ctx: Context) {
            val mgr = AppWidgetManager.getInstance(ctx)
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, QuickWidget::class.java))
            if (ids.isNotEmpty()) QuickWidget().onUpdate(ctx, mgr, ids)
        }
    }
}
