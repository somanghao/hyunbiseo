package io.github.somanghao.makecar.hyun

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import io.github.somanghao.makecar.hyun.core.Hyun
import io.github.somanghao.makecar.hyun.feature.Registry
import io.github.somanghao.makecar.hyun.ui.HyunView
import io.github.somanghao.makecar.hyun.ui.SysPanel

/**
 * 현비서 — 목적지까지 조수석에서 함께 가는 대화형 비서(차 = 현비서 · 폰 = 짭비서).
 * 이 Activity는 **안드로이드 껍데기**만 — 화면(HyunView) · 시스템 바 · 폰의 글 입력 줄 · 권한 결과 · 생명주기를 코어([Hyun])에 넘긴다.
 *
 * 구조(새 기능 넣는 법 → .claude/docs/wiki/hyun-architecture.md):
 *   core/     Hyun(코어 — 대화·말하기·두뇌·1초 루프·맥락·화면) · Feature(플러그인 약속) · Fmt
 *   feature/  기능 플러그인 — Registry에 한 줄 등록(길 · 정찰 · 글레오 · 폰 모드 · 날씨 · 배터리 · 운전자 챙기기 · 말 명령)
 *   voice/    Voice(Pleos/안드로이드 STT·TTS) · Ear(상시 귀) · EarService(마이크 포그라운드) · Speaker(말하기 스케줄러)
 *   drive/    Navi(NaviHelper 한 연결을 여러 기능에) · Trip · Scout(정찰 판정) · Sense(속도·기어·위치)
 *   brain/    Link(현비서 서버)   ui/ HyunView   phone/ Desk · QuickActivity(빠른 대화 창·위젯) · PhoneMode
 * 시연 인텐트: 코어 = core/Hyun.onIntent, 기능 = 각 feature 파일 맨 위 주석 · 로그 `adb logcat -s HYUN`
 */
class HyunActivity : Activity() {
    private lateinit var hyun: Hyun
    private lateinit var view: HyunView
    private var input: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = HyunView(this)
        hyun = Hyun(this, view, Registry.all())
        setTaskDescription(android.app.ActivityManager.TaskDescription(hyun.botName))
        val rootView = FrameLayout(this).apply { addView(view); if (!hyun.isCar) addView(inputRow(), FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)) }
        root = rootView
        applySide(intent)
        rootView.setOnApplyWindowInsetsListener { _, ins ->   // 시스템 바·노치 안쪽에 그린다(targetSdk 35 = edge-to-edge) · 키보드가 뜨면 입력 줄을 올린다
            if (Build.VERSION.SDK_INT >= 30) {
                val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val side = sidePanel != null
                view.setPadding(if (side) 0 else b.left, b.top, b.right, b.bottom)
                sidePanel?.setPadding(b.left, b.top, 0, b.bottom)
                input?.let { row -> (row.parent as View).translationY = -maxOf(ins.getInsets(WindowInsets.Type.ime()).bottom, b.bottom).toFloat() }
            } else @Suppress("DEPRECATION") view.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            ins
        }
        setContentView(rootView)
        if (savedInstanceState == null) splash(rootView)
        if (!hyun.isCar) { lightBars(); view.onKeyboard = ::showInput }   // 창(DecorView)이 생긴 뒤에 — setContentView 전이면 insetsController가 NPE(폰 실측)
        hyun.start(intent)
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {   // 디버그 빌드만 — release엔 없다
            val f = android.content.IntentFilter(CMD)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(cmdRx, f, RECEIVER_EXPORTED) else registerReceiver(cmdRx, f)
            cmdOn = true
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); applySide(intent); hyun.onIntent(intent, first = false) }

    /** 시험·녹화 명령을 화면을 다시 띄우지 않고 받는다(`am broadcast -a …hyun.CMD -p <패키지> --es …`) — `am start`로 보내면 Pleos 홈이
     *  띄운 앱을 반반 왼쪽 칸으로 옮겨 현비서가 [내비 | 현비서]를 되돌리느라 녹화에 좌우가 바뀌어 보였다(11-14) */
    private var cmdOn = false
    private val cmdRx = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) { hyun.onIntent(i, first = false) }
    }
    companion object { const val CMD = "io.github.somanghao.makecar.hyun.CMD" }

    // ---------- 왼쪽 시스템 칸 흉내(ui/SysPanel) — 실차 Pleos 홈은 왼쪽 ~1/3이 시스템 화면으로 고정, 앱은 오른쪽 2/3.
    // 표준 AAOS 에뮬(Pleos 홈 없음)에서만 깔아 같은 비율로 보이게 한다 · `--ei sideL 643`(px) · `--ei sideL 0` 끄기 · -1 기본
    private var root: FrameLayout? = null
    private var sidePanel: SysPanel? = null
    private fun applySide(i: Intent?) {
        val r = root ?: return
        if (!hyun.isCar) return
        val prefs = getSharedPreferences("hyun", MODE_PRIVATE)
        if (i?.hasExtra("sideL") == true) prefs.edit().putInt("sideL", i.getIntExtra("sideL", -1)).apply()
        val dm = resources.displayMetrics
        val want = prefs.getInt("sideL", -1).let { v ->
            when {
                v >= 0 -> v
                SysPanel.pleosHome(this) || dm.widthPixels < dm.heightPixels * 1.4f -> 0   // 실차·반반·세로 화면은 안 깜
                else -> (dm.widthPixels * SysPanel.DEFAULT_RATIO).toInt()
            }
        }
        sidePanel?.let { r.removeView(it) }; sidePanel = null
        (view.layoutParams as? FrameLayout.LayoutParams)?.let { it.leftMargin = want; view.layoutParams = it }
        if (want > 0) sidePanel = SysPanel(this, hyun).also { r.addView(it, 0, FrameLayout.LayoutParams(want, -1, Gravity.START)) }
        r.requestApplyInsets()
    }
    override fun onResume() { super.onResume(); hyun.onResume() }
    override fun onPause() { hyun.onPause(); super.onPause() }
    override fun onStop() { hyun.onStop(); super.onStop() }
    override fun onDestroy() { if (cmdOn) runCatching { unregisterReceiver(cmdRx) }; hyun.stop(); super.onDestroy() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        hyun.onPermissions(permissions, grantResults)
    }

    // ---------- 시작 스플래시 — 헬멧 로봇이 손 흔들기(assets/chars/bot_waving.webp)이 톡 튀어나왔다 사라진다(누르면 건너뜀) ----------
    private fun splash(root: FrameLayout) {
        val bm = try { assets.open("chars/bot_waving.webp").use { android.graphics.BitmapFactory.decodeStream(it) } } catch (_: Exception) { null } ?: return
        val side = minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 45 / 100
        val face = ImageView(this).apply { setImageBitmap(bm); scaleType = ImageView.ScaleType.FIT_CENTER; scaleX = 0.4f; scaleY = 0.4f; alpha = 0f }
        val cover = FrameLayout(this).apply {
            setBackgroundColor(if (hyun.isCar) 0xFF0B0F14.toInt() else 0xFFFFFFFF.toInt())
            isClickable = true
            addView(face, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
        }
        var gone = false
        fun close() {
            if (gone) return
            gone = true
            cover.animate().alpha(0f).setDuration(300).withEndAction { root.removeView(cover) }.start()
        }
        cover.setOnClickListener { close() }
        root.addView(cover, FrameLayout.LayoutParams(-1, -1))
        face.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(450).setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
        cover.postDelayed({ close() }, 1600)
    }

    // ---------- 글로 묻기(폰) — 지하철·회의 중 ----------
    private fun inputRow(): View {
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt() }
        val e = EditText(this).apply {
            hint = "${hyun.botName}에게 쓰기"; setHintTextColor(0xFF9CA3AF.toInt()); setTextColor(0xFF111827.toInt()); textSize = 16f
            imeOptions = EditorInfo.IME_ACTION_SEND; setSingleLine()
            background = GradientDrawable().apply { setColor(0xFFF3F5F8.toInt()); cornerRadius = dp(14f).toFloat() }
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_SEND) { sendTyped(); true } else false }
        }
        input = e
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            setPadding(dp(10f), dp(8f), dp(10f), dp(8f))
            background = GradientDrawable().apply { setColor(0xFAFFFFFF.toInt()) }
            addView(e, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(this@HyunActivity).apply { text = "보내기"; setOnClickListener { sendTyped() } })
            addView(Button(this@HyunActivity).apply { text = "닫기"; setOnClickListener { hideInput() } })
        }
    }

    private fun showInput() {
        val e = input ?: return
        (e.parent as View).visibility = View.VISIBLE
        e.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(e, 0)
    }

    private fun hideInput() {
        val e = input ?: return
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(e.windowToken, 0)
        (e.parent as View).visibility = View.GONE
    }

    private fun sendTyped() {
        val t = input?.text?.toString()?.trim().orEmpty()
        if (t.isEmpty()) return
        input?.setText("")
        hideInput()
        if (view.mode == HyunView.Mode.LISTENING) hyun.stopListening()
        hyun.log("typed|$t")
        hyun.heard(t)
    }

    /** 흰 배경 — 상태바·내비바 아이콘을 어둡게, 내비바 회색 막 없애기 */
    private fun lightBars() {
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.setSystemBarsAppearance(
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
    }
}
