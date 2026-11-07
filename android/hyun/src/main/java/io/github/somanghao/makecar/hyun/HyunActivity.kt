package io.github.somanghao.makecar.hyun

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt
import io.github.somanghao.makecar.hyun.HyunView.Mode

/**
 * 현비서 — 목적지까지 조수석에서 함께 가는 대화형 비서.
 * 앱 = 귀(STT)·입(TTS)·눈(속도·기어·목적지·배터리·날씨)·안전 규칙·말하기 스케줄러, 두뇌 = PC의 Claude Code 세션(server/hyun/).
 * 들은 말과 주행 이벤트(출발·절반·곧 도착·도착·장시간 운전·한동안 조용함)를 맥락과 함께 서버로 보내고, 답을 받아
 * 우선순위(P0 안전 > P1 주행 > P2 제안 > P3 수다 — Speaker.kt)에 맞춰 말한다. 두뇌가 없거나 늦으면 규칙 문장으로 대신한다.
 * 경로 정찰(최우선, 차에서만): 1분마다 내비의 내 경로 교통을 읽어 새 위험만 넘기고, 두뇌가 우회를 제안·실행한다.
 *
 * 차(Pleos Connect·에뮬) = Pleos 음성·AAOS 차량·NaviHelper, 서버 http://10.0.2.2:8790
 * 폰(샤오미 등) = 안드로이드 음성·GPS, 서버 http://127.0.0.1:8790 — PC에서 `adb reverse tcp:8790 tcp:8790`
 * 로그: `adb logcat -s HYUN`
 * 시연(마이크 없이): am start -n io.github.somanghao.makecar.hyun/.HyunActivity --es say "강남역 가자"
 *   목적지 --es dest 강남역 --ef lat 37.4979 --ef lng 127.0276 · 이벤트 강제 --es event halfway
 *   기준 줄이기 --ei longmin 3 --ei quietmin 2 · 서버 --es server http://10.0.2.2:8790 · 목적지 취소 --ez cancel true
 *   정찰 주입 --es traffic "accident|1200|강남대로 신논현역 부근 2차로 사고" · --ei etaup 8 · 지금 정찰 --ez scan true · 주기 --ei scoutsec 30
 *   말하기 주입(스케줄러 시험) --es line "P3|수다 한마디" — P0이면 앱 규칙 경고처럼 끊고 들어온다
 *   폰 모드 --es mode transit|walk|work · 리마인더 시험(서버 없이) --es remind "1|빨래 꺼내기"(1분 뒤)
 *   휴게소 소개 --es restarea "기흥휴게소|2500"
 *   경유지 --es via "코엑스|37.5116|127.0595" · 경유지 빼기 --ez clearvia true · 경유지 도착 흉내 --es event waypoint
 *   귀 시험(마이크 없이) --es earfile hey_gangnam — 서버 data/testaudio/<이름>.pcm(16kHz mono)을 받아 귀에 넣는다
 *   글레오 흉내 am start -a ASK_HYUN --es ACTION_PARAM '{"question":"앞길 어때?"}' (HYUN_STATUS {item} · HYUN_QUIET {mode})
 */
class HyunActivity : Activity(), Voice.Events, Sense.Out, Ear.Out {
    private lateinit var view: HyunView
    private val main = Handler(Looper.getMainLooper())
    private var isCar = true
    private var botName = "현비서"                 // 폰은 짭비서 — 두뇌는 같고 캐릭터 이름·말투만 다르다(server/hyun/persona.md)
    private val link = Link("")
    private val trip = Trip()
    private var sense: Sense? = null
    private var vehicle: Vehicle? = null
    private var navi: NaviHelper? = null
    private var voice: Voice? = null
    private val speaker = Speaker(::log)
    // 상시 귀(차) — 앱이 마이크를 직접 듣고 PC 받아 적기 서버(stt_server.py, 8791)로. 서버가 없으면 Pleos STT(버튼)로 돌아간다
    private var ear: Ear? = null
    private var earReady = false
    private var sttBase = ""
    private fun earOn() = ear?.running == true && earReady
    private var earAlways = true                  // 상시 듣기(호출어) ↔ 눌러서 말하기(버튼 누를 때만 마이크) — 앱 설정에 남는다
    private fun earUsable() = ear != null && earReady
    /** 귀를 켤지 — 상시 듣기면 늘, 눌러서 말하기면 대화 창이 열렸을 때만(그 밖엔 마이크를 놓는다).
     *  차는 마이크 포그라운드 서비스를 같이 켜서 내비·음악 화면으로 가도 계속 듣는다(새로 켜는 건 앱이 앞에 있을 때만 된다) */
    private fun ensureEar() {
        if (earUsable() && micGranted() && (earAlways || view.mode == Mode.LISTENING)) {
            ear?.start()
            if (isCar && fg) EarService.start(this)
        } else { ear?.stop(); EarService.stop(this) }
    }
    private var fg = false   // 앱이 앞에 있나(onResume ~ onPause)

    // 주행 맥락
    @Volatile private var speedMs = 0f
    @Volatile private var gear = 'P'
    @Volatile private var uxDo = false
    private var soc: Int? = null
    private var lat: Double? = null
    private var lng: Double? = null
    private var addr: String? = null
    private var tempC: Float? = null
    private var rainPct: Int? = null
    private var destLat: Double? = null           // 폰: 내비가 없어 GPS 직선거리로 남은 거리를 어림
    private var destLng: Double? = null
    private val moveMs get() = if (isCar) 1.4f else PhoneSense.MOVE_MS
    private val driving get() = uxDo || speedMs > moveMs

    // 대화
    private var brain: Boolean? = null            // 서버 /hyun/health — null 서버 없음 · false 두뇌 자리 비움 · true 연결
    private var claudeLogin = false               // Claude API 로그인(키 등록)됨 — 세션이 없어도 API가 답한다
    private var healthFails = 0
    private var healthSeen = false
    private var waitingId = 0                     // 답을 기다리는 내 말 id(새로 말하면 앞의 답은 버린다)
    private var heard = ""
    private var answered = false
    private var listenAfter = false               // 말이 끝나면 마이크를 연다(질문으로 끝난 답)
    private var lastTalkAt = SystemClock.elapsedRealtime()
    private var greeted = false

    // 이벤트 기준(분) — 시연에선 인텐트로 줄인다
    private var longMin = 90
    private var quietMin = 12
    private var nextLongS = 0.0

    // 폰(짭비서) — 모드·일정·리마인더·할 일·배터리·이어폰
    private var mode = PhoneMode.WORK
    private var desk: Desk? = null
    private var headset = true
    private var events = listOf<Desk.Event>()
    private val warnedEvents = mutableSetOf<Long>()   // 10분 전 알림을 한 일정
    private var reminders = listOf<Triple<Int, Long, String>>()   // id · 울릴 시각(ms) · 내용 (끝나지 않은 것)
    private val armed = mutableMapOf<Int, Runnable>()
    private var todos = listOf<String>()
    private var batteryLow = false
    private var phoneSoc: Int? = null
    private var walkM = 0.0
    private var walkSince = 0L
    private var lastFix: Pair<Double, Double>? = null
    private val silent get() = !isCar && mode == PhoneMode.TRANSIT && !headset

    // 경로 정찰
    private val scout = Scout()
    private var scoutSec = 60                     // --ei scoutsec로 고정했을 때만 쓴다(scoutFixed) — 평소엔 nextScanSec()가 상황별로
    private var scoutFixed = false
    private var scoutWhy = ""
    private var beforeReroute: Double? = null     // 우회 요청 직전 남은 시간(초) — 결과 비교용
    private var routeOption: RouteOption? = null  // 지금 경로 옵션(onRouteStateInfo · onRouteOptionChanged)
    private var prevOption: RouteOption? = null   // 우회 직전 옵션 — 바꿔도 시간이 안 줄면 이걸로 되돌린다
    private var rerouteHow = ""
    private var tbts = listOf<Pair<String, Double>>()   // 다음 안내들(종류 · 거리 m) — 우회가 갈라질 분기가 위험보다 앞인지 본다
    private var tbtAt = 0L

    // 경유지(최대 2) — 내비 onWayPointInfo · onDrivingInfo.waypoints가 남은 거리·시간을 준다
    private data class Via(val name: String, val remainM: Int, val remainS: Int)
    private var vias = listOf<Via>()

    // 출발했는데 목적지가 없으면 주행당 한 번 묻는다
    private var noDestS = 0
    private var noDestAsked = false
    // 못 알아들으면 한 번만 다시 묻는다(대화 창마다)
    private var reasked = false

    // 회전 앞 침묵 — 내비 다음 안내(onTBTInfo)까지 300m 안이면 P0 말고는 기다린다. GPS가 멈춘 에뮬·정체에 갇히지 않게 45초면 푼다
    private var turnM = -1.0
    private var turnType = ""
    private var turnKey = ""
    private var turnAt = 0L
    private var gateSince = 0L
    private var turnDemoUntil = 0L                // 시연 주입(--ei turnm) 동안은 실제 TBT로 덮지 않고 속도로 다가간다
    private var lastTurnBusy = false

    // "조용히" — 이 시각까지 P2·P3(대답 제외)를 말하지 않는다
    private var quietUntil = 0L
    private var socLow = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCar = packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        link.base = if (isCar) "http://10.0.2.2:8790" else "http://127.0.0.1:8790"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        botName = if (isCar) "현비서" else "짭비서"
        setTaskDescription(android.app.ActivityManager.TaskDescription(botName))
        view = HyunView(this).also { it.phone = !isCar; it.lightTheme = !isCar; it.botName = botName; it.hyunLine = "안녕하세요, ${botName}예요" }
        val rootView = FrameLayout(this).apply { addView(view); if (!isCar) addView(inputRow(), FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)) }
        rootView.setOnApplyWindowInsetsListener { _, ins ->   // 시스템 바·노치 안쪽에 그린다(targetSdk 35 = edge-to-edge) · 키보드가 뜨면 입력 줄을 올린다
            if (Build.VERSION.SDK_INT >= 30) {
                val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(b.left, b.top, b.right, b.bottom)
                input?.let { row -> (row.parent as View).translationY = -maxOf(ins.getInsets(WindowInsets.Type.ime()).bottom, b.bottom).toFloat() }
            } else @Suppress("DEPRECATION") view.setPadding(ins.systemWindowInsetLeft, ins.systemWindowInsetTop, ins.systemWindowInsetRight, ins.systemWindowInsetBottom)
            ins
        }
        setContentView(rootView)
        if (!isCar) lightBars()   // 창(DecorView)이 생긴 뒤에 — setContentView 전이면 insetsController가 NPE(폰 실측)
        view.onMic = ::micPressed
        nextLongS = longMin * 60.0
        demo(intent, first = true)
        log("start|${if (isCar) "car" else "phone"}|${Build.MANUFACTURER} ${Build.MODEL} API ${Build.VERSION.SDK_INT}|server=${link.base}")
        voice = Voice.create(this, isCar, this)
        if (isCar) {
            sttBase = intent?.getStringExtra("stt") ?: link.base.replace(":8790", ":8791"); ear = Ear({ sttBase }, this)
            earAlways = getSharedPreferences("hyun", MODE_PRIVATE).getBoolean("earAlways", true)
            view.showListenToggle = true; view.listenAlways = earAlways; view.onListenMode = { setListenMode(it, byVoice = false) }
            view.character = getSharedPreferences("hyun", MODE_PRIVATE).getString("character", "human") ?: "human"   // 사람 비서가 기본(사용자 11-06)
            view.onCharacter = { setCharacter(CHARS[(CHARS.indexOf(view.character) + 1) % CHARS.size]) }
        }
        if (isCar) {
            sense = CarSense(this, this).also { it.start() }
            initVehicle()
            initNavi()
        } else {
            desk = Desk(this)
            mode = PhoneMode.of(getSharedPreferences("hyun", MODE_PRIVATE).getString("mode", null)) ?: PhoneMode.WORK
            view.modes = PhoneMode.entries.map { it.label }; view.modeIdx = mode.ordinal
            view.onMode = { i -> setMode(PhoneMode.entries[i], byUser = true) }
            val need = PHONE_PERMS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (need.isEmpty()) startPhoneSense() else requestPermissions(need.toTypedArray(), REQ_PERMS)
            main.post(deskTick)
            refreshTasks()
            view.onKeyboard = ::showInput
            QuickActivity.postNotification(this)   // 알림창에서 말하기·글로 묻기
            QuickWidget.refresh(this)
        }
        main.post(tick)
        main.post(health)
        main.postDelayed(scan, 5000)
        refreshMemory()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissions.zip(grantResults.toList()).forEach { (p, g) -> log("perm|${p.substringAfterLast('.')}|${g == PackageManager.PERMISSION_GRANTED}") }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) startPhoneSense()
        if (!micGranted()) { view.userLine = "(마이크 권한이 없어요 — 설정에서 허용해 주세요)"; render() }
        main.removeCallbacks(deskTick); main.post(deskTick)   // 캘린더 권한이 막 생겼으면 바로 일정 읽기
        QuickActivity.postNotification(this)
    }

    // ---------- 글로 묻기(폰) — 지하철·회의 중 ----------
    private var input: EditText? = null

    private fun inputRow(): View {
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt() }
        val e = EditText(this).apply {
            hint = "${botName}에게 쓰기"; setHintTextColor(0xFF9CA3AF.toInt()); setTextColor(0xFF111827.toInt()); textSize = 16f
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
        if (view.mode == Mode.LISTENING) stopListening()
        log("typed|$t")
        heardLine(t)
    }

    /** 흰 배경 — 상태바·내비바 아이콘을 어둡게, 내비바 회색 막 없애기 */
    private fun lightBars() {
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.setSystemBarsAppearance(
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
    }

    private fun startPhoneSense() { if (sense == null) sense = PhoneSense(this, this).also { it.start() } }
    private fun micGranted() = isCar || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    // ---------- 폰(짭비서): 모드 · 30초 책상 점검 · 리마인더 ----------
    private fun setMode(m: PhoneMode, byUser: Boolean) {
        if (m == mode && !byUser) return
        val changed = m != mode
        mode = m
        getSharedPreferences("hyun", MODE_PRIVATE).edit().putString("mode", m.key).apply()
        if (m == PhoneMode.WALK && changed) { walkM = 0.0; walkSince = System.currentTimeMillis(); lastFix = null }
        log("mode|${m.key}|byUser=$byUser")
        headset = desk?.headset() ?: true
        render()
        if (byUser && changed) fire("mode")   // 두뇌가 그 모드에 맞는 한마디(일정·우산·하차 등) — P2
    }

    /** 30초마다: 이어폰 · 배터리 · 오늘 일정(회의 10분 전 = P0) */
    private val deskTick = object : Runnable {
        override fun run() {
            val d = desk ?: return
            headset = d.headset()
            val (lv, charging) = d.battery()
            phoneSoc = lv
            if (lv != null && lv <= 20 && !charging && !batteryLow) { batteryLow = true; fire("battery_low") }
            if (lv != null && (lv > 30 || charging)) batteryLow = false
            events = d.upcoming()
            for (e in events) {
                if (e.allDay || e.id in warnedEvents) continue
                val m = Desk.minutesUntil(e.start)
                if (m in 1..10) {
                    warnedEvents += e.id
                    say("${m}분 뒤 ${e.title}" + (e.place?.takeIf { it.isNotBlank() }?.let { ", ${it}에서" } ?: "") + "예요.", "alert", false, 0, tag = "일정 10분 전")
                }
            }
            render()
            main.postDelayed(this, 30_000)
        }
    }

    /** 서버의 리마인더·할 일을 받아, 아직 안 건 리마인더를 예약한다(앱이 켜져 있는 동안 — 울리면 P0) */
    private fun refreshTasks() = link.tasks { t ->
        t ?: return@tasks
        val now = System.currentTimeMillis()
        val rs = t.optJSONArray("reminders")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
        val open = rs.filter { !it.optBoolean("done") }.map { Triple(it.getInt("id"), it.getLong("at"), it.getString("text")) }
        val td = (t.optJSONArray("todos")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList())
            .filter { !it.optBoolean("done") }.map { it.getString("text") }
        main.post {
            reminders = open.sortedBy { it.second }; todos = td
            for ((id, at, text) in open) {
                if (id in armed) continue
                if (at < now - 10 * 60_000L) { link.fired(id); continue }   // 10분 넘게 지난 건 조용히 끝냄
                val r = Runnable { ring(id, text) }
                armed[id] = r
                main.postDelayed(r, (at - now).coerceAtLeast(0))
                log("remind|arm|$id|${hhmm(at)}|$text")
            }
            render()
        }
    }

    private fun ring(id: Int, text: String) {
        log("remind|ring|$id|$text")
        armed.remove(id)
        reminders = reminders.filter { it.first != id }
        link.fired(id)
        say("알림이에요. $text, 지금이에요.", "alert", false, 0, tag = "리마인더")
    }

    /** 폰 목적지 800m 전 — 대중교통이면 하차 알림(P0), 아니면 곧 도착(P1) */
    private fun nearPhone() {
        val d = trip.dest ?: return
        if (mode == PhoneMode.TRANSIT) say("곧 ${d}예요. 내릴 준비 하세요.", "alert", false, 0, tag = "하차")
        else if (brain == true) ask("event", "near") else say("곧 ${d}에 도착해요.", "", false, 1, tag = "near·규칙")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        demo(intent, first = false)
    }

    override fun onResume() { super.onResume(); fg = true; if (::view.isInitialized) ensureEar() }
    override fun onPause() { fg = false; super.onPause() }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        ear?.stop()
        EarService.stop(this)
        voice?.release()
        sense?.stop()
        runCatching { navi?.release() }
        runCatching { vehicle?.release() }
        super.onDestroy()
    }

    /** 시연 인텐트 — 마이크·실제 경로 없이 장면을 만든다 */
    private fun demo(i: Intent?, first: Boolean) {
        i ?: return
        if (i.action in GLEO_ACTIONS) { main.postDelayed({ onGleo(i) }, if (first) 3000 else 0); return }
        i.getStringExtra("server")?.let { link.base = it }
        i.getStringExtra("mode")?.let { m -> PhoneMode.of(m)?.let { main.postDelayed({ setMode(it, byUser = true) }, if (first) 4000 else 0) } }
        i.getStringExtra("remind")?.let { r ->   // "분|내용" — 서버 없이 리마인더 시험
            main.postDelayed({ say("알림이에요. ${r.substringAfter("|")}, 지금이에요.", "alert", false, 0, tag = "remind·demo") },
                ((r.substringBefore("|").toDoubleOrNull() ?: 0.0) * 60_000).toLong())
        }
        if (i.hasExtra("longmin")) { longMin = i.getIntExtra("longmin", longMin); nextLongS = longMin * 60.0 }
        if (i.hasExtra("quietmin")) quietMin = i.getIntExtra("quietmin", quietMin)
        if (i.getBooleanExtra("cancel", false)) { cancelTrip("demo"); if (first) main.postDelayed({ runCatching { navi?.cancelRoute() } }, 2500) }   // 처음엔 내비 연결 뒤에 한 번 더
        if (i.hasExtra("scoutsec")) { scoutSec = i.getIntExtra("scoutsec", scoutSec); scoutFixed = scoutSec > 0; if (!scoutFixed) scoutSec = 60 }   // 0 = 적응 주기로
        i.getStringExtra("restarea")?.split("|")?.let { p ->   // "기흥휴게소|2500" — 휴게소 소개 시험(에뮬 GPS가 안 움직여 TBT로 오지 않을 때)
            main.postDelayed({ restTold.remove(p[0]); restArea(p[0], p.getOrNull(1)?.toIntOrNull() ?: 2000) }, if (first) 5000 else 0)
        }
        i.getStringExtra("via")?.split("|")?.takeIf { it.size >= 3 }?.let { p ->
            main.postDelayed({ addVia(p[0], p[1].toDouble(), p[2].toDouble()) }, if (first) 4000 else 0)
        }
        if (i.getBooleanExtra("clearvia", false)) reroute("clear_via")
        i.getStringExtra("earfile")?.let { f -> main.postDelayed({ feedEar(f) }, if (first) 8000 else 0) }
        if (i.getBooleanExtra("scan", false)) runCatching { navi?.getRouteStateInfo() }
        i.getStringExtra("traffic")?.let { t ->   // "종류|앞 거리(m)|설명"
            val p = t.split("|")
            main.postDelayed({ report(listOf(scout.demo(p[0], p.getOrNull(1)?.toIntOrNull(), p.getOrNull(2) ?: ""))) }, if (first) 6000 else 0)
        }
        if (i.hasExtra("etaup")) main.postDelayed({ report(listOf(scout.demoEta(i.getIntExtra("etaup", 8), trip.remainS))) }, if (first) 6000 else 0)
        i.getStringExtra("line")?.let { l ->       // "P3|말" — 스케줄러 시험
            val pr = l.substringBefore("|").removePrefix("P").toIntOrNull()?.coerceIn(0, 3) ?: 2
            main.postDelayed({ say(l.substringAfter("|"), if (pr == 0) "alert" else "", false, pr, tag = "demo") }, if (first) 6000 else 0)
        }
        i.getStringExtra("dest")?.let { d -> main.postDelayed({ goTo(d, i.getFloatExtra("lat", 37.4979f).toDouble(), i.getFloatExtra("lng", 127.0276f).toDouble()) }, if (first) 2500 else 0) }
        // --ez harness true: 하네스 화면 시험 — 서버가 취향·기억·최근 대화에 넣지 않는다(tools/hyun_harness)
        val harness = i.getBooleanExtra("harness", false)
        i.getStringExtra("say")?.let { q -> main.postDelayed({ heardLine(q, harness) }, if (first) 5000 else 0) }
        if (i.getBooleanExtra("rawrec", false)) { rawRecArmed = true; view.userLine = "(녹음 진단: 마이크를 누르고 말하세요)"; render() }
        if (i.hasExtra("debug")) { view.debug = i.getBooleanExtra("debug", false); render() }   // 우선순위 배지·두뇌 방식·기어 표시(개발용)
        if (i.getBooleanExtra("listen", false)) main.postDelayed({ listen() }, if (first) 7000 else 0)   // 손 안 대고 마이크 열기(시험·Gleo 호출 자리)
        if (i.hasExtra("turnm")) main.postDelayed({   // 회전 앞 침묵 시험 — 다음 안내까지 N미터, 주행 속도로 다가간다
            setTurn(i.getIntExtra("turnm", 250).toDouble(), i.getStringExtra("turntype") ?: "TURN_LEFT", "demo")
            turnDemoUntil = SystemClock.elapsedRealtime() + 120_000
            log("turn|demo|${turnM.toInt()}m|$turnType")
        }, if (first) 6000 else 0)
        i.getStringExtra("sttfile")?.let { f ->   // 마이크 대신 녹음 파일(filesDir, 16kHz PCM)로 STT 시험
            main.postDelayed({
                val bytes = runCatching { java.io.File(filesDir, f).readBytes() }.getOrNull()
                if (bytes == null) { log("stt|feed|no file $f"); return@postDelayed }
                if (view.mode == Mode.SPEAKING) { voice?.stopSpeaking(); speaker.finished() }
                heard = ""; answered = false; view.mode = Mode.LISTENING; view.userLine = "…"; render()
                main.removeCallbacks(listenTimeout); main.postDelayed(listenTimeout, SPEAKING_MS)
                if (voice?.feed(bytes) != true) { log("stt|feed|unsupported"); endListening() } else log("stt|feed|${bytes.size}B")
            }, if (first) 7000 else 0)
        }
        i.getStringExtra("event")?.let { e -> main.postDelayed({ trip.fired -= e; fire(e) }, if (first) 5000 else 0) }
    }

    /** 캐릭터 바꾸기 — 버튼·말("캐릭터 바꿔줘" · "비서로 바꿔" · "구슬로 바꿔"), 앱 설정에 저장 */
    private fun setCharacter(ch: String) {
        view.character = ch
        getSharedPreferences("hyun", MODE_PRIVATE).edit().putString("character", ch).apply()
        log("character|$ch"); render()
    }

    private fun characterCommand(t: String): String? {
        val s = t.replace(" ", "")
        if (!Regex("캐릭터|모습|비서로|구슬로").containsMatchIn(s) || !Regex("바꿔|바꾸|변경|해줘|로해").containsMatchIn(s)) return null
        return when {
            Regex("사람|비서로|여자").containsMatchIn(s) -> "human"
            Regex("젤리|3D|입체").containsMatchIn(s) -> "orb3d"
            Regex("구슬").containsMatchIn(s) -> "orb"
            else -> CHARS[(CHARS.indexOf(view.character) + 1) % CHARS.size]
        }
    }

    private var cardAt = 0L                     // 목록 카드가 뜬 시각 — CARD_MS 지나면 닫는다
    private val CARD_MS = 180_000L

    // ---------- 1초 루프: 진행 · 이벤트 · 줄 선 말 · 화면 ----------
    private val tick = object : Runnable {
        override fun run() {
            trip.tick(1.0, speedMs.toDouble())
            val now = SystemClock.elapsedRealtime()
            if (view.card != null && now - cardAt > CARD_MS) view.card = null
            if (now < turnDemoUntil && turnM >= 0) {   // 시연 회전: 속도만큼 다가가고, 지나면 지운다
                turnM -= speedMs; turnAt = now
                if (turnM < 0) { log("turn|demo|passed"); turnDemoUntil = 0; turnM = -1.0 }
            }
            checkEvents()
            if (speaker.waiting.isNotEmpty()) pump() else render()
            main.postDelayed(this, 1000)
        }
    }

    /** 경로 정찰 — 목적지가 있으면 주기적으로 내 경로 교통을 묻는다(답은 onRouteStateInfo). 주기는 상황별(nextScanSec) */
    private val scan = object : Runnable {
        override fun run() {
            if (trip.dest != null) runCatching { navi?.getRouteStateInfo(); navi?.getTBTInfo() }.onFailure { log("scout|get|${it.message}") }
            main.postDelayed(this, nextScanSec() * 1000L)
        }
    }

    /** 적응형 정찰 주기 — 정차 120초 · 고속(70km/h+)·분기 2km 앞·정체 3km 앞 25초 · 시내 저속(40km/h 미만) 90초 · 그 밖 60초. --ei scoutsec이면 고정 */
    private fun nextScanSec(): Int {
        if (scoutFixed || trip.dest == null) return scoutSec
        val k = kmh()
        val branch = nextBranchM()
        val jam = scout.nearestM
        val (sec, why) = when {
            speedMs < 1.4f -> 120 to "정차"
            k >= 70 -> 25 to "고속 ${k}km/h"
            branch != null && branch <= 2000 -> 25 to "분기 ${branch.toInt()}m 앞"
            jam != null && jam <= 3000 -> 25 to "위험 ${jam}m 앞"
            k < 40 -> 90 to "시내 저속 ${k}km/h"
            else -> 60 to "보통 ${k}km/h"
        }
        val tag = why.substringBefore(" ")
        if (tag != scoutWhy) { scoutWhy = tag; log("scout|every|${sec}s|$why") }
        return sec
    }

    /** 다음 '분기'(우회가 갈라질 수 있는 안내 지점)까지 m — 받은 뒤 달린 만큼 뺀다. 30초 넘게 묵은 안내면 모름(null) */
    private fun upcoming(): List<Pair<String, Double>>? {
        val age = SystemClock.elapsedRealtime() - tbtAt
        if (tbts.isEmpty() || age > 60_000) return null
        val moved = speedMs * age / 1000.0
        return tbts.map { it.first to (it.second - moved) }.filter { it.second > 0 }
    }
    private fun nextBranchM(): Double? = upcoming()?.firstOrNull { it.first !in NOT_BRANCH && it.second > 30 }?.second

    /**
     * 위험(앞 [aheadM])을 피해 갈 분기가 그 앞에 있나 — 우회 제안은 '우회 가능한 마지막 분기' 전에만 쓸모 있다.
     * possible = 분기가 위험보다 150m 넘게 앞 · none = 안내 목록이 위험 지점까지 덮는데 그 앞에 분기 없음(이미 지났거나 외길) · unknown = 안내 없음·묵음·목록이 짧음
     */
    private fun detour(aheadM: Int?): Pair<String, Int?> {
        val list = upcoming()
        if (aheadM == null || list.isNullOrEmpty()) return "unknown" to null
        val b = list.firstOrNull { it.first !in NOT_BRANCH && it.second > 30 }
        return when {
            b != null && b.second + 150 < aheadM -> "possible" to b.second.toInt()
            list.maxOf { it.second } >= aheadM -> "none" to b?.second?.toInt()
            else -> "unknown" to b?.second?.toInt()
        }
    }

    /** 새로 찾은 위험 처리 — 가까운 사고·역주행은 앱이 먼저 경고(P0), 알릴 만한 건 두뇌에게(우회 제안 — 분기가 남았을 때만), 사소한 건 맥락 요약에만 */
    private fun report(fs: List<Scout.Finding>) {
        if (fs.isEmpty()) return
        // 안내 목록이 10초 넘게 묵었으면 새로 받고 0.8초 뒤 판정 — 내비는 안내가 바뀔 때만 onTBTInfo를 준다(에뮬 실측: 30초 지나 '모름')
        if (isCar && navi != null && trip.dest != null && SystemClock.elapsedRealtime() - tbtAt > 10_000) {
            runCatching { navi?.getTBTInfo() }
            main.postDelayed({ reportNow(fs) }, 800)
        } else reportNow(fs)
    }

    private fun reportNow(fs: List<Scout.Finding>) {
        val dt = fs.map { detour(it.aheadM) }
        fs.forEachIndexed { k, f -> log("scout|${f.src}|${f.kind}|sev=${f.severity}|ahead=${f.aheadM}|detour=${dt[k].first}@${dt[k].second}|${f.text}") }
        val ui = fs.indexOfFirst { it.severity >= 3 && (it.aheadM ?: Int.MAX_VALUE) <= 1500 }
        val urgent = fs.getOrNull(ui)
        urgent?.let { u ->
            val where = u.aheadM?.let { m -> if (m < 1000) "${(m / 100) * 100}미터 앞 " else "${Scout.km(m).removeSuffix("km")}킬로미터 앞 " } ?: "앞에 "
            val tail = if (dt[ui].first == "none") " 돌아갈 길은 없어요. 차로 지키고 천천히 가요." else " 속도 줄이고 조심하세요."
            say("$where${u.kind} 정보예요.$tail", "alert", false, 0, tag = "scout")
        }
        if (urgent == null && fs.none { it.severity >= 2 }) return
        val arr = JSONArray(fs.mapIndexed { k, f -> f.json().put("detour", dt[k].first).put("branchM", dt[k].second ?: JSONObject.NULL) })
        val extra = JSONObject().put("findings", arr).put("appWarned", urgent?.text ?: JSONObject.NULL)
        if (brain == true) ask("event", "traffic", extra)
        else if (urgent == null) fs.withIndex().maxBy { it.value.severity }.let { (k, f) ->
            val tail = when (dt[k].first) { "none" -> " 돌아갈 길은 없으니 천천히 가요."; "possible" -> " 다음 갈림길에서 돌아갈 수 있어요."; else -> "" }
            say("앞길에 ${f.kind} 정보가 있어요. ${f.text.substringAfter(" · ")}$tail", "care", false, 1, tag = "scout")
        }
    }

    /** 두뇌가 고른 길 바꾸기 — 우회는 바꾸기 전 남은 시간·옵션을 기억했다가 6초 뒤 비교(안 줄면 되돌림) · 경유지 빼기 · 안내 취소 */
    private fun reroute(how: String) {
        when (how) {
            "cancel" -> { cancelTrip("두뇌"); return }
            "clear_via" -> {
                log("via|clear|${vias.map { it.name }}")
                runCatching { navi?.removeWaypoint(WaypointIndex.ALL) }.onFailure { log("via|clear|FAIL|${it.javaClass.simpleName} ${it.message}") }
                vias = emptyList(); render(); return
            }
        }
        beforeReroute = trip.remainS
        prevOption = routeOption ?: RouteOption.RECOMMENDED; rerouteHow = how
        log("route|$how|before=${trip.remainS.toInt()}s|option=$prevOption")
        runCatching { optionOf(how)?.let { navi?.changeRouteOption(it) } ?: navi?.requestReRoute() }
            .onFailure { log("route|FAIL|${it.javaClass.simpleName} ${it.message}") }
        main.postDelayed(::afterReroute, 6000)
    }

    private fun optionOf(how: String) = when (how) {
        "fast" -> RouteOption.SHORT_TIME; "highway" -> RouteOption.HIGHWAY; "free" -> RouteOption.FREE; "comfort" -> RouteOption.COMFORTABLE
        else -> null   // reroute = 같은 옵션으로 다시 탐색
    }

    private fun afterReroute() {
        val before = beforeReroute ?: return
        beforeReroute = null
        if (before <= 0 || trip.dest == null) { log("route|after|no-trip"); return }   // 경로가 없을 때(에뮬 시스템 재시작 뒤 실측) 0분→0분을 보고하지 않는다
        val extra = JSONObject().put("beforeMin", ceil(before / 60).toInt()).put("afterMin", mins())
        // 바꿨는데 1분도 안 줄었으면 원래 옵션으로 되돌린다(재탐색은 되돌릴 옵션이 없다)
        val prev = prevOption; val asked = optionOf(rerouteHow)
        if (prev != null && asked != null && prev != asked && trip.remainS >= before - 60) {
            log("route|revert|$asked→$prev|${ceil(before / 60).toInt()}→${mins()}분")
            runCatching { navi?.changeRouteOption(prev) }.onFailure { log("route|revert|FAIL|${it.javaClass.simpleName} ${it.message}") }
            extra.put("reverted", true)
        }
        log("route|after|$extra")
        if (brain == true) ask("event", "rerouted", extra)
        else say(if (extra.optBoolean("reverted")) "바꿔 봤는데 시간이 안 줄어서 원래 길로 돌렸어요. 남은 시간은 ${ceil(before / 60).toInt()}분이에요."
                 else "경로를 다시 잡았어요. 남은 시간은 ${mins()}분이에요.", "calm", false, 1, tag = "rerouted")
    }

    /** 휴게소가 3km 안 — 이번 길에서 한 번만 소개(두뇌가 검색으로 대표 메뉴·편의 + 취향 추천, 정차하면 사진 카드) */
    private val restTold = mutableSetOf<String>()
    private fun restArea(name: String, aheadM: Int) {
        if (!restTold.add(name)) return
        log("rest|$name|${aheadM}m")
        fire("restarea", JSONObject().put("restName", name).put("aheadM", aheadM))
    }

    /** 길 안내 끝 — 목적지·경유지·정찰을 비우고 내비 안내도 끈다 */
    private fun cancelTrip(by: String) {
        log("nav|cancel|$by|${trip.dest}")
        trip.clear(); scout.reset(); vias = emptyList(); restTold.clear(); destLat = null; destLng = null
        runCatching { navi?.cancelRoute() }.onFailure { log("nav|cancel|FAIL|${it.message}") }
        render()
    }

    /** 경유지 — 길 안내 중이면 addWaypoint(빈 자리, 최대 2). 목적지가 아직 없으면 그곳을 목적지로 */
    private fun addVia(name: String, la: Double, lo: Double) {
        if (trip.dest == null) { log("via|no-dest → 목적지로|$name"); goTo(name, la, lo); return }
        if (vias.any { it.name.replace(" ", "") == name.replace(" ", "") }) { log("via|already|$name"); return }   // 이미 넣은 경유지(두뇌가 다시 실어 보냄)
        if (vias.size >= 2) { log("via|full|$name"); say("경유지는 두 곳까지만 넣을 수 있어요.", "calm", false, 1, reply = true, tag = "경유지"); return }
        val idx = if (vias.isEmpty()) WaypointIndex.FIRST else WaypointIndex.SECOND
        log("via|add|$name|$la,$lo|$idx|dest=${trip.dest}")
        if (isCar) runCatching {
            navi?.addWaypoint(RequestWaypointInfo(latitude = la, longitude = lo, poiName = name, waypointIndex = idx, poiId = "", address = name, poiSubId = "0"))
        }.onFailure { log("via|add|FAIL|${it.javaClass.simpleName} ${it.message}") }
        vias = vias + Via(name, 0, 0)   // 내비가 남은 거리를 주면 onWayPointInfo가 덮어쓴다
        main.postDelayed({ runCatching { navi?.getWaypointInfo() } }, 3000)
        render()
    }

    // ---------- 귀 시험 — PC 서버의 녹음을 받아 귀에 넣는다(마이크 없이 받아 적기·호출어·두뇌까지) ----------
    private fun feedEar(name: String) = Thread {
        val b = runCatching {
            val c = (URL("${link.base}/hyun/testaudio?name=$name").openConnection() as HttpURLConnection).apply { connectTimeout = 3000; readTimeout = 5000 }
            c.inputStream.use { it.readBytes() }
        }.getOrNull()
        if (b == null || b.isEmpty()) { log("ear|feed|FAIL|$name"); return@Thread }
        log("ear|feed|$name|${b.size}B|${"%.1f".format(b.size / 32000.0)}s")
        ear?.feed(b) ?: log("ear|feed|no ear")
    }.start()

    // ---------- Gleo Interface(assets/schema.json) — 글레오가 부르면 7초 안에 APP_ACTION_RESPONSE ----------
    private fun onGleo(i: Intent) {
        val fn = i.action ?: return
        val p = gleoParams(i)
        log("gleo|action|$fn|$p")
        when (fn) {
            "ASK_HYUN" -> {
                val q = p.optString("question").trim()
                if (q.isEmpty()) { gleoReply(fn, "asking", "What would you like to ask Hyun?", "voice"); return }
                // 7초 안에 답이 안 올 수 있다(두뇌 ~5초+) — 글레오엔 바로 알리고, 답은 현비서 목소리로
                gleoReply(fn, "success", "Hyun is answering this in its own voice.", "beep")
                view.userLine = "글레오 → $q"
                heardLine(q)
            }
            "HYUN_STATUS" -> {
                val item = p.optString("item", "all")
                val d = trip.dest
                val tripEn = if (d == null) "There is no destination yet. You can tell Hyun where to go."
                    else "Heading to $d: ${km(trip.remainM)} km and about ${mins()} minutes left, arriving at ${hhmm(System.currentTimeMillis() + (trip.remainS * 1000).toLong())}." +
                        (vias.firstOrNull()?.let { " Stop on the way: ${it.name}." } ?: "")
                val msg = when (item) { "trip" -> tripEn; "traffic" -> if (d == null) tripEn else scout.english(); else -> if (d == null) tripEn else "$tripEn ${scout.english()}" }
                gleoReply(fn, "success", msg, "voice")
                view.userLine = "글레오 → 현비서 상황($item)"; render()
            }
            "HYUN_QUIET" -> {
                val on = p.optString("mode", "quiet") != "talk"
                quietCommand(if (on) "조용히" else "말해도돼")
                gleoReply(fn, "success", if (on) "Hyun will stay quiet for 10 minutes, except safety alerts and answers." else "Hyun can talk again.", "voice")
                view.userLine = "글레오 → ${if (on) "조용히 모드" else "말동무 모드"}"; render()
            }
        }
    }

    /** 문서 예시는 JSON을 따옴표로 한 번 더 감싸 보낸다("\"{...}\"") · 파라미터가 개별 extra로 올 수도 있다(에이전트 앱 실측) */
    private fun gleoParams(i: Intent): JSONObject {
        val raw = i.getStringExtra("ACTION_PARAM")?.trim()
        val j = runCatching {
            val t = raw!!.let { if (it.length > 1 && it.startsWith("\"") && it.endsWith("\"")) it.substring(1, it.length - 1).replace("\\\"", "\"") else it }
            JSONObject(t)
        }.getOrDefault(JSONObject())
        for (k in listOf("question", "item", "mode")) i.getStringExtra(k)?.let { if (!j.has(k)) j.put(k, it) }
        return j
    }

    private fun gleoReply(fn: String, status: String, message: String, mode: String) {
        val res = JSONObject().put("functionName", fn).put("status", status).put("message", message).put("mode", mode)
        runCatching { sendBroadcast(Intent(GLEO_RESPONSE).putExtra("packageName", packageName).putExtra("response", res.toString())) }
            .onFailure { log("gleo|response|FAIL|${it.message}") }
        log("gleo|response|$res")
    }

    private val health = object : Runnable {
        override fun run() {
            link.health { j ->
                val b: Boolean? = j?.optBoolean("brain")
                main.post {
                    view.brainMode = j?.optString("mode")?.takeIf { it.isNotBlank() && it != "null" } ?: ""
                    claudeLogin = j?.optBoolean("login") == true
                    healthFails = if (b == null) healthFails + 1 else 0
                    if (b == true || healthFails >= 2) healthSeen = true
                    val next = if (b == null && healthFails < 2) brain else b   // 한 번 실패로 깜빡이지 않게
                    if (next != brain) log("brain|$next")
                    brain = next; render()
                }
            }
            ear?.ping { ok -> main.post {
                if (ok != earReady) log("ear|server|$ok|$sttBase")
                earReady = ok
                ensureEar()
                render()
            } }
            main.postDelayed(this, 10_000)
        }
    }

    private fun checkEvents() {
        val f = trip.fired
        if (!isCar) {   // 폰: 운전 이벤트 없음 — 목적지(대중교통 하차·산책 목적지)만 본다, 수다(quiet)도 없음
            if (trip.dest != null && lat != null) when {
                "near" !in f && trip.totalM >= 1200 && trip.remainM <= 800 -> { f += "near"; nearPhone() }
                "arrived" !in f && trip.remainM <= 150 -> fire("arrived")
            }
            return
        }
        // 출발했는데 목적지가 없다 — 20초 넘게 달리면 주행당 한 번 "어디로 가세요?"(5분 넘게 섰다 다시 출발하면 새 주행)
        if (trip.driveS < 1) { noDestAsked = false; noDestS = 0 }
        if (trip.dest == null && speedMs > moveMs) noDestS++ else if (trip.dest != null) noDestS = 0
        if (!noDestAsked && noDestS >= NO_DEST_S) { noDestAsked = true; fire("no_dest") }
        if (trip.dest != null) when {
            // 출발 = 정찰 먼저(경로 교통 요약이 출발 한마디에 실리게) → 2.5초 뒤 이벤트
            "depart" !in f && speedMs > moveMs -> { f += "depart"; runCatching { navi?.getRouteStateInfo() }; main.postDelayed({ fire("depart") }, 2500) }
            "arrived" !in f && "depart" in f && trip.remainM <= 150 && speedMs < 2f -> fire("arrived")
            "near" !in f && "depart" in f && trip.totalM >= 2500 && trip.remainM <= 1000 -> fire("near")
            "halfway" !in f && "depart" in f && trip.totalM >= 3000 && trip.progress >= 0.5 -> fire("halfway")
        }
        if (trip.driveS < 60) nextLongS = longMin * 60.0           // 쉬고 나면 다시 처음부터
        if (trip.driveS >= nextLongS) { nextLongS += 30 * 60; fire("long_drive") }
        soc?.let { s ->                                             // 차 배터리 20% 아래 — 한 번만, 30% 넘게 채우면 다시
            if (s <= 20 && !socLow) { socLow = true; fire("battery_low") } else if (s >= 30) socLow = false
        }
        // 한동안 말이 없으면(주행 중·두뇌 연결·조용할 때만) 현비서가 먼저 말을 건다 — 기억을 꺼내 가볍게
        val idle = view.mode == Mode.READY && waitingId == 0 && speaker.waiting.isEmpty()
        if (driving && brain == true && idle && !quiet() && SystemClock.elapsedRealtime() - lastTalkAt > quietMin * 60_000L) {
            fire("quiet")   // 맥락(말없이 N분)을 보낸 뒤에 시계를 되돌린다
            lastTalkAt = SystemClock.elapsedRealtime()
        }
    }

    private fun fire(ev: String, extra0: JSONObject? = null) {
        trip.fired += ev
        val extra = extra0 ?: if (ev == "waypoint") vias.firstOrNull()?.let { JSONObject().put("viaName", it.name) } else null
        log("event|$ev${extra?.let { "|$it" } ?: ""}")
        if (brain != true) { fallbackEvent(ev, extra)?.let { say(it, "", ev == "no_dest", Speaker.eventPrio(ev), tag = "$ev·규칙") }; return }
        ask("event", ev, extra)
    }

    // ---------- 대화: 서버(두뇌)로 보내고 답을 말한다 ----------
    private fun heardLine(text: String, harness: Boolean = false) {
        if (maxLevel > 0f) logLevel("heard")
        log("heard|$text")
        main.removeCallbacks(listenTimeout)
        view.userLine = text
        view.card = null   // 새 질문 — 이전 표를 지운다(하네스 화면 점검: 이전 카드가 남아 답과 안 맞음 4건, 11-06)
        lastTalkAt = SystemClock.elapsedRealtime()
        reasked = false
        quietCommand(text)?.let { a -> view.mode = Mode.READY; say(a, "calm", false, 1, reply = true, tag = "조용히"); return }
        if (isCar) listenModeCommand(text)?.let { always -> view.mode = Mode.READY; setListenMode(always, byVoice = true); return }
        if (isCar) characterCommand(text)?.let { ch -> view.mode = Mode.READY; setCharacter(ch); say(CHAR_HELLO[ch] ?: "바꿨어요.", "happy", false, 1, reply = true, tag = "캐릭터"); return }
        if (brain != true) { view.mode = Mode.READY; say(offlineAnswer(text), "", false, 1, reply = true, tag = "규칙 답"); return }
        view.mode = Mode.THINKING
        render()
        ask("talk", text, if (harness) JSONObject().put("harness", true) else null)
    }

    private fun ask(kind: String, text: String, extra: JSONObject? = null) {
        val c = ctx().also { c -> extra?.keys()?.forEach { k -> c.put(k, extra.get(k)) } }
        link.send(kind, text, c) { id ->
            main.post {
                log("ask|$id|$kind|$text")   // 시험 기록(tools/hyun_e2e)이 이 질문의 답(reply|id)을 짝짓는다
                if (id == null) {   // 서버가 사라졌다
                    brain = null
                    if (kind == "talk") { view.mode = Mode.READY; say(offlineAnswer(text), "", false, 1, reply = true, tag = "규칙 답") }
                    else fallbackEvent(text)?.let { say(it, "", false, Speaker.eventPrio(text), tag = "$text·규칙") }
                    return@post
                }
                if (kind == "talk") {
                    waitingId = id
                    // 두뇌 한 턴은 10초쯤 걸린다 — 조용히 기다리면 못 들은 줄 안다. 3.5초 넘으면 짧게 맞장구(한 번)
                    main.postDelayed({ if (waitingId == id) say(FILLERS[id % FILLERS.size], "", false, 1, reply = true, tag = "맞장구") }, FILLER_MS)
                    // 검색이 필요한 질문(스포츠·뉴스)은 15~25초 — 중간에 한 번 더 알린다
                    main.postDelayed({ if (waitingId == id) say("조금만 더 찾아볼게요.", "", false, 1, reply = true, tag = "맞장구2") }, FILLER2_MS)
                }
                link.awaitReply(id, if (kind == "talk") 45_000 else 30_000) { r -> main.post { onReply(kind, text, id, r) } }
            }
        }
    }

    private fun onReply(kind: String, text: String, id: Int, r: JSONObject?) {
        if (kind == "talk") { if (id != waitingId) return; waitingId = 0 }
        log("reply|$id|$r")
        val talk = kind == "talk"
        val base = if (talk) 1 else Speaker.eventPrio(text)
        if (view.mode == Mode.THINKING && waitingId == 0) view.mode = Mode.READY
        if (r == null) {   // 두뇌가 늦다
            if (talk) say("음, 생각이 길어졌네요. 한 번만 다시 말해 줄래요?", "", true, 1, reply = true, tag = "늦음")
            else fallbackEvent(text)?.let { say(it, "", false, base, tag = "$text·규칙") }
            return
        }
        if (r.optBoolean("skipped")) { render(); return }
        // 목적지·경유지 — 둘 다 오면 경유지 넣은 경로 한 번에, 경유지만 오면 지금 안내에 덧붙인다
        val nav = r.optJSONObject("nav"); val via = r.optJSONObject("via")
        // 두뇌가 지금 목적지를 다시 실어 보내도(경유지만 말했는데) 경로를 새로 잡지 않고 지금 안내에 경유지만 넣는다
        if (nav != null && via != null && trip.dest != null && nav.optString("name") == trip.dest) addVia(via.getString("name"), via.getDouble("lat"), via.getDouble("lng"))
        else if (nav != null) goTo(nav.getString("name"), nav.getDouble("lat"), nav.getDouble("lng"),
            via?.let { Triple(it.getString("name"), it.getDouble("lat"), it.getDouble("lng")) })
        else via?.let { addVia(it.getString("name"), it.getDouble("lat"), it.getDouble("lng")) }
        r.optString("route").takeIf { it.isNotBlank() && it != "null" }?.let(::reroute)
        r.optString("mode").takeIf { it.isNotBlank() && it != "null" }?.let { m -> PhoneMode.of(m)?.let { setMode(it, byUser = false) } }
        if (!isCar) refreshTasks()
        // 목록 카드(순위·여러 개 추천) — 정차 화면에 띄우고 말은 앞 3개만(주행 중엔 화면이 숨기고, 정차하면 보인다)
        r.optJSONObject("card")?.let { k ->
            val items = k.optJSONArray("items")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty().filter { it.isNotBlank() }
            if (items.isNotEmpty()) {
                // 서버가 중계하는 사진(/hyun/img — 위키미디어 공용)은 서버 주소를 붙인다
                val imgs = k.optJSONArray("images")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }.orEmpty()
                    .map { if (it.startsWith("/")) link.base + it else it }
                view.card = HyunView.Card(k.optString("title"), k.optString("source"), items, imgs, k.optString("kind"))
                cardAt = SystemClock.elapsedRealtime()
                log("card|${k.optString("title")}|${items.size}|kind=${k.optString("kind").ifBlank { "list" }}|img=${imgs.count { it.isNotBlank() }}|${items.take(3).joinToString(" / ")}")
            }
        }
        // 두뇌가 정한 단계(1~3)가 있으면 그걸로 — P0은 앱 규칙만 쓴다
        // 주행 필수 이벤트(P1 — 출발·도착·교통·우회·목적지 묻기·경유지)는 두뇌가 낮춰도 P1 — P2 쿨다운에 묶여 "어디로 가세요?"가 3분 밀렸다(11-07 실측)
        val prio = if (r.has("prio") && !r.isNull("prio")) r.optInt("prio", base).coerceIn(1, 3).let { if (!talk && base == 1) 1 else it } else base
        val s = r.optString("say")
        if (s.isNotBlank()) say(s, r.optString("mood"), r.optBoolean("listen"), prio, reply = talk, tag = if (talk) "답" else text)
        else render()
        refreshMemory()
    }

    // ---------- 말하기: 스케줄러(Speaker)가 순서·끊기·보류·버림을 정한다 ----------
    private fun say(text: String, mood: String, listen: Boolean, prio: Int, reply: Boolean = false, tag: String = "") {
        if (text.isBlank()) return
        if (!reply && prio >= 2 && quiet()) { log("prio|drop|P$prio|조용히 모드|$text"); return }
        speaker.offer(Speaker.Line(text, mood, listen, prio, reply, tag))
        pump()
    }

    private fun quiet() = SystemClock.elapsedRealtime() < quietUntil

    /** "상시 모드로 해 줘" → true · "버튼 모드 / 눌러서 말하기로" → false · 아니면 null */
    private fun listenModeCommand(text: String): Boolean? {
        val t = text.replace(" ", "")
        return when {
            listOf("상시모드", "상시듣기", "항상들어", "계속들어").any { it in t } -> true
            listOf("버튼모드", "눌러서말하기", "누를때만", "상시듣기꺼", "상시모드꺼").any { it in t } -> false
            else -> null
        }
    }

    private fun setListenMode(always: Boolean, byVoice: Boolean) {
        val changed = always != earAlways
        earAlways = always; view.listenAlways = always
        getSharedPreferences("hyun", MODE_PRIVATE).edit().putBoolean("earAlways", always).apply()
        log("listenMode|${if (always) "always" else "ptt"}|${if (byVoice) "voice" else "tap"}")
        ensureEar()
        if (changed || byVoice) say(if (always) "상시 듣기로 바꿨어요. 현비서야 하고 불러 주세요." else "이제 마이크 버튼을 누를 때만 들을게요.",
            "calm", false, 1, reply = true, tag = "듣기 모드")
        render()
    }

    /** "조용히 해 줘" → 10분 동안 제안·수다를 끈다(안전 경고·주행 필수·대답은 그대로) / "이제 말해도 돼" → 해제. 두뇌 없이 앱이 바로 */
    private fun quietCommand(text: String): String? {
        val t = text.replace(" ", "")
        return when {
            listOf("말해도돼", "다시말해", "조용히안해도", "조용히그만").any { it in t } -> {
                quietUntil = 0L; log("quiet|off"); "네, 다시 말동무할게요."
            }
            listOf("조용히", "말걸지마", "말시키지마", "좀조용", "쉿").any { it in t } -> {
                quietUntil = SystemClock.elapsedRealtime() + QUIET_MS
                speaker.drop("조용히") { !it.reply && it.prio >= 2 }
                log("quiet|on|${QUIET_MS / 60_000}분")
                "알겠어요. ${QUIET_MS / 60_000}분 동안은 꼭 필요한 말만 할게요."
            }
            else -> null
        }
    }

    private fun setTurn(m: Double, type: String, desc: String) {
        val key = "$type|$desc"
        if (key != turnKey) { turnKey = key; gateSince = 0L }
        turnM = m; turnType = type; turnAt = SystemClock.elapsedRealtime()
    }

    /** 회전 300m 앞 = 침묵 구간. 직진·도착·안내 없음은 제외, 같은 안내에 45초 넘게 묶이면(정체·GPS 정지) 푼다 */
    private fun turnBusy(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val on = isCar && driving && now - turnAt < 15_000 && turnM in 0.0..TURN_GATE_M && turnType !in OPEN_TURNS
        if (!on) { gateSince = 0L; return false }
        if (gateSince == 0L) gateSince = now
        return now - gateSince < TURN_GATE_MAX_MS
    }

    private fun pump() {
        speaker.policy = if (!isCar) mode.policy else if (driving) Speaker.Policy.DRIVE else Speaker.Policy.PARKED
        val tb = turnBusy()
        if (tb != lastTurnBusy) { lastTurnBusy = tb; log("turn|gate|${if (tb) "on" else "off"}|$turnType|${turnM.toInt()}m|대기 ${speaker.waiting.size}") }
        val now = Speaker.Now(listening = view.mode == Mode.LISTENING, speaking = view.mode == Mode.SPEAKING, thinking = waitingId != 0, turnBusy = tb)
        val a = speaker.next(now)
        if (a is Speaker.Act.Speak) speakNow(a.line)
        render()
    }

    private fun speakNow(l: Speaker.Line) {
        if (view.mode == Mode.LISTENING) stopListening()
        log("say|P${l.prio}|${if (silent && l.prio > 0) "자막만|" else ""}${l.text}")
        view.hyunLine = l.text; view.mood = l.mood; view.prio = l.prio; view.prioReply = l.reply
        listenAfter = l.listen
        desk?.buzz(l.prio == 0)
        val v = voice
        if (silent && l.prio > 0) {   // 대중교통 + 이어폰 없음 — 소리 없이 자막·진동만(P0만 소리)
            listenAfter = false
            speaker.finished(); view.mode = if (waitingId != 0) Mode.THINKING else Mode.READY
            main.postDelayed(::pump, 2500)
        } else if (v != null && v.canSpeak) { view.mode = Mode.SPEAKING; ear?.paused = true; v.speak(l.text) }
        else { speaker.finished(); view.mode = if (waitingId != 0) Mode.THINKING else Mode.READY; main.postDelayed(::pump, 1500) }   // 입이 없으면 자막만
    }

    /** 다 말했으면 다음 말로, 질문으로 끝났으면 마이크를 바로 연다(손 안 대고 이어 말하기) */
    private fun afterSpeech() {
        if (view.mode != Mode.SPEAKING) return
        speaker.finished()
        view.mode = if (waitingId != 0) Mode.THINKING else Mode.READY
        if (listenAfter && speaker.waiting.none { it.prio == 0 }) { listenAfter = false; main.postDelayed(::listen, 300) }
        else { listenAfter = false; pump() }
        render()
    }

    private fun micPressed() {
        if (rawRecArmed && view.mode != Mode.LISTENING) { rawRecArmed = false; rawRecord(); return }
        when (view.mode) {
            Mode.SPEAKING -> { listenAfter = false; voice?.stopSpeaking(); speaker.finished(); view.mode = Mode.READY; listen() }
            Mode.LISTENING -> {   // 다시 누르면 듣기 끝 — 들은 말이 있으면 보낸다
                val q = heard.takeIf { it.isNotBlank() && !answered }
                stopListening()
                if (q != null) heardLine(q) else endListening()
            }
            else -> { reasked = false; listen() }
        }
    }

    /** 진단: STT를 거치지 않고 마이크 원본을 7초 녹음(16kHz mono PCM) → filesDir/rec.pcm. 소리 경로(에뮬 가상 마이크)가 멀쩡한지 PC에서 들어 본다 */
    private var rawRecArmed = false
    private fun rawRecord() {
        if (view.mode == Mode.SPEAKING) { voice?.stopSpeaking(); speaker.finished() }
        view.mode = Mode.LISTENING; view.userLine = "녹음 중 7초 — 말씀하세요"; render()
        Thread {
            val r = runCatching {
                val sr = 16_000
                val min = android.media.AudioRecord.getMinBufferSize(sr, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
                val rec = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC, sr, android.media.AudioFormat.CHANNEL_IN_MONO,
                    android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 6400))
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(3200); var peak = 0
                rec.startRecording()
                val end = SystemClock.elapsedRealtime() + 7_000
                while (SystemClock.elapsedRealtime() < end) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        out.write(buf, 0, n)
                        var k = 0
                        while (k + 1 < n) { val v = ((buf[k].toInt() and 0xff) or (buf[k + 1].toInt() shl 8)).toShort().toInt(); if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v); k += 2 }
                    }
                }
                rec.stop(); rec.release()
                java.io.File(filesDir, "rec.pcm").writeBytes(out.toByteArray())
                "${out.size()}B peak=$peak"
            }.getOrElse { "FAIL ${it.javaClass.simpleName} ${it.message}" }
            log("rawrec|$r")
            main.post { view.mode = Mode.READY; view.userLine = "(녹음 진단 끝 — $r)"; render() }
        }.start()
    }

    private fun listen() {
        if (!micGranted()) { view.userLine = "(마이크 권한을 허용해 주세요)"; requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERMS); render(); return }
        if (earUsable()) {   // 앱 귀(받아 적기 서버) — 상시면 이미 듣는 중, 눌러서 말하기면 지금 켠다 · 호출어 없이 받을 '대화 창'을 연다
            ear?.start()
            if (view.mode == Mode.SPEAKING) { voice?.stopSpeaking(); speaker.finished() }
            heard = ""; answered = false
            view.mode = Mode.LISTENING; view.userLine = "말씀하세요…"; render()
            main.removeCallbacks(listenTimeout); main.postDelayed(listenTimeout, EAR_WINDOW_MS)
            return
        }
        val v = voice
        if (v == null || !v.canListen) { view.userLine = "(음성 인식을 준비하고 있어요)"; render(); return }
        if (view.mode == Mode.SPEAKING) { v.stopSpeaking(); speaker.finished() }
        heard = ""; answered = false; spoke = false
        view.mode = Mode.LISTENING; view.userLine = "…"
        render()
        v.listen()
        main.removeCallbacks(listenTimeout)
        main.postDelayed(listenTimeout, SILENCE_MS + WARMUP_MS)   // 엔진 준비(onHearReady) 전엔 길게 — 준비되면 그때부터 8초
    }

    /** STT는 말소리가 없으면 끝없이 기다리기도 한다(에뮬 실측) — 조용하면 8초, 말하기 시작했으면 20초에 접는다 */
    private val listenTimeout = Runnable {
        if (view.mode != Mode.LISTENING) return@Runnable
        log("stt|timeout|${heard.ifBlank { "(조용)" }}")
        if (heard.isNotBlank() && !answered) { answered = true; stopListening(); heardLine(heard) }
        else { view.userLine = ""; stopListening(); endListening() }
    }

    private fun stopListening() {
        main.removeCallbacks(listenTimeout)
        answered = true
        voice?.stopListening()
        if (view.mode == Mode.LISTENING) view.mode = Mode.READY
    }

    /** 듣기가 끝났으면 듣는 사이 줄 선 말을 꺼낸다 */
    private fun endListening() {
        logLevel("end")
        if (!earAlways) main.post { ensureEar() }   // 눌러서 말하기 — 창이 닫히면 마이크를 놓는다
        main.removeCallbacks(listenTimeout)
        if (view.mode == Mode.LISTENING) view.mode = Mode.READY
        pump()
    }

    // ---------- Voice.Events (아무 스레드 → main) ----------
    override fun onVoiceReady() { main.post { helloWhenReady(0) } }

    /** 첫 인사 — 서버 health를 한 번 받은 뒤에(안 그러면 두뇌가 있어도 규칙 인사가 나간다, 폰 실측) · 최대 8초 */
    private fun helloWhenReady(tries: Int) {
        if (greeted) return
        if (!healthSeen && tries < 8) { main.postDelayed({ helloWhenReady(tries + 1) }, 1000); return }
        greeted = true; main.postDelayed({ fire("hello") }, 800)
    }
    override fun onSpeakDone() { main.post { log("tts|done"); afterSpeech() } }   // tts|done = 시험 기록(tools/hyun_e2e)이 말이 끝난 때를 잰다
    override fun onSpeakStopped() { log("tts|stopped") }   // 끊는 쪽(micPressed·P0)이 상태를 직접 정리한다
    /** 엔진이 녹음을 시작했다 — 이때부터 8초를 세고 "말씀하세요"를 띄운다(재부팅 직후 첫 요청은 2.8초 늦었다, 11-06 실측) */
    override fun onHearReady() { main.post { if (view.mode == Mode.LISTENING && heard.isBlank()) { main.removeCallbacks(listenTimeout); main.postDelayed(listenTimeout, SILENCE_MS); view.userLine = "말씀하세요…"; render() } } }
    override fun onHearStart() { main.post { spoke = true; main.removeCallbacks(listenTimeout); main.postDelayed(listenTimeout, SPEAKING_MS) } }
    private var spoke = false   // 이번 듣기에서 말소리가 시작됐나(Pleos STT) — 빈 결과면 다시 묻기
    override fun onHeard(text: String, final: Boolean) {
        main.post {
            if (answered) return@post
            heard = text; view.userLine = text
            if (final) { answered = true; view.mode = Mode.READY; heardLine(text) } else view.update()
        }
    }
    override fun onHearEnd() {
        main.post {
            log("stt|end|$heard")
            if (answered) return@post
            answered = true
            if (heard.isNotBlank()) { view.mode = Mode.READY; heardLine(heard) }
            else if (spoke) reask()   // 말소리는 들렸는데 글자가 없다 = 못 알아들음 → 한 번 다시 묻기
            else { view.userLine = "(못 들었어요)"; endListening() }
        }
    }
    override fun onHearError(what: String) {
        main.post {
            if (answered) return@post
            answered = true
            view.userLine = "(음성 인식 오류 — $what)"; endListening()
        }
    }
    // ---------- Ear.Out (상시 귀 · 아무 스레드 → main) ----------
    override fun onSpeechStart() {
        main.post { if (view.mode == Mode.LISTENING) { view.userLine = "듣는 중…"; main.removeCallbacks(listenTimeout); main.postDelayed(listenTimeout, SPEAKING_MS); render() } }
    }
    override fun onEarLevel(level: Float) { if (view.mode == Mode.LISTENING) onLevel(level) }
    override fun onEarError(what: String) { log("ear|error|$what") }

    /** 대화 창(버튼·질문 직후)이면 그대로 대화 · 아니면 "현비서야 …"로 시작할 때만. 호출어 없는 말은 버리고 내용도 남기지 않는다 */
    override fun onText(text: String, sec: Double, ms: Long) {
        main.post {
            val t = text.trim()
            val window = view.mode == Mode.LISTENING
            val rest = stripWake(t)
            val tag = "${"%.1f".format(sec)}s|${ms}ms"
            when {
                t.isBlank() -> { log("ear|text|$tag|(없음)"); if (window) reask() }
                window -> { log("ear|text|$tag|$t"); answered = true; main.removeCallbacks(listenTimeout); view.mode = Mode.READY; heardLine(rest?.ifBlank { null } ?: t) }
                rest != null && rest.isNotBlank() -> {
                    log("ear|wake+text|$tag|$t")
                    if (view.mode == Mode.SPEAKING) { listenAfter = false; voice?.stopSpeaking(); speaker.finished(); view.mode = Mode.READY }
                    heardLine(rest)
                }
                rest != null -> { log("ear|wake|$tag|$t"); reasked = false; say("네, 말씀하세요.", "happy", true, 1, reply = true, tag = "호출어") }
                else -> log("ear|ignored|$tag|${t.length}자")
            }
            render()
        }
    }

    /** 말소리는 있었는데 못 알아들었다(받아 적기가 빈 글자) — 대화 창마다 한 번만 다시 묻고, 또 못 알아들으면 조용히 닫는다 */
    private fun reask() {
        if (reasked) { log("reask|again|닫음"); view.userLine = ""; stopListening(); endListening(); return }
        reasked = true
        log("reask")
        stopListening()
        say("잘 못 들었어요. 한 번만 다시 말해 주세요.", "calm", true, 1, reply = true, tag = "다시 묻기")
    }

    /**
     * "현비서야 …"의 호출어 뒤 말을 준다 — 호출어가 없으면 null. 주변 소리가 이어져 한 구간이 길면(최대 12초) 호출어가 중간에 오므로
     * 구간 어디서든 찾고 **마지막** 호출어 뒤만 쓴다(앞의 남의 말은 버림). Whisper가 현/형/혁·서/사로 받아 적는 경우도 받는다
     */
    private fun stripWake(t: String): String? {
        val m = WAKE.findAll(t).lastOrNull() ?: return null
        return t.substring(m.range.last + 1).trim().trimStart(',', '.', '!', '?', '~', ' ')
    }

    override fun onLevel(level: Float) { if (view.mode == Mode.LISTENING && level > maxLevel) maxLevel = level; view.pulse(level) }
    @Volatile private var maxLevel = 0f   // 한 번 듣는 동안 마이크 최대 음량(0..1) — 0이면 소리가 앱까지 안 온 것(마이크 연결 진단)
    private fun logLevel(why: String) { log("stt|level|$why|max=${"%.2f".format(maxLevel)}"); maxLevel = 0f }

    // ---------- Sense.Out (아무 스레드) ----------
    override fun onSpeed(ms: Float) { speedMs = ms }
    override fun onGear(g: Char) { if (g != gear) log("gear|$g"); gear = g }
    override fun onDo(on: Boolean) { if (on != uxDo) log("ux|DO=$on"); uxDo = on }
    override fun onPlace(lat: Double, lng: Double, addr: String?) {
        main.post {
            val first = this.lat == null
            this.lat = lat; this.lng = lng
            if (addr != null && addr != this.addr) { this.addr = addr; log("loc|$lat,$lng|$addr") }
            if (first) forecast(lat, lng)
            if (!isCar && mode == PhoneMode.WALK) {   // 산책 거리 — 3m 이하 흔들림·200m 넘는 튐은 버린다
                lastFix?.let { (a, b) -> Trip.meters(a, b, lat, lng).takeIf { it in 3.0..200.0 }?.let { walkM += it } }
                lastFix = lat to lng
            }
            val dl = destLat; val dn = destLng
            if (!isCar && dl != null && dn != null && trip.dest != null) trip.navi(trip.dest, Trip.meters(lat, lng, dl, dn) * 1.3, 0.0)
        }
    }

    // ---------- 두뇌가 없을 때: 사실은 앱이 직접, 나머지는 솔직하게 ----------
    private fun offlineAnswer(q: String): String {
        val t = q.replace(" ", "")
        val d = trip.dest
        return when {
            (t.contains("남았") || t.contains("도착") || t.contains("얼마나")) && d != null -> "${d}까지 ${km(trip.remainM)}킬로미터, ${dur()} 남았어요."
            t.contains("배터리") && soc != null -> "배터리는 ${soc}퍼센트예요."
            t.contains("몇시") || t.contains("시간") -> "지금은 ${hhmm(System.currentTimeMillis()).replace(":", "시 ")}분이에요."
            listOf("클로드", "claude", "두뇌", "연결", "로그인").any { it in t.lowercase() } ->   // 연결돼 있으면 Claude가 직접 답한다(여긴 안 됐을 때만)
                if (brain == false) "Claude가 아직 연결 안 됐어요. PC나 폰 브라우저에서 현비서 서버 주소 뒤에 hyun 슬래시 login을 열고 API 키를 넣으면 바로 이어져요."
                else "Claude 서버가 꺼져 있어요. 지금은 시간, 배터리, 남은 거리만 알려드릴 수 있어요."
            else -> "지금은 제 생각 회로가 연결이 안 됐어요. 남은 시간이나 배터리는 바로 알려드릴 수 있어요."
        }
    }

    private fun fallbackEvent(ev: String, extra: JSONObject? = null): String? {
        val d = trip.dest
        return when (ev) {
            "hello" -> if (isCar) "안녕하세요, ${botName}예요. 오늘은 어디로 가요?" else "안녕하세요, ${botName}예요. ${mode.label} 모드예요. 무엇을 도와드릴까요?"
            "depart" -> d?.let { "${it}까지 ${km(trip.remainM)}킬로미터, ${dur()}쯤 걸려요." +
                when { scout.summary.isBlank() -> ""; scout.summary.startsWith("내비") -> " 막히는 곳은 없어요."; else -> " 내비 기준 ${scout.summary}예요." } +
                if ((rainPct ?: 0) >= 50) " 비 소식이 있으니 천천히 가요." else " 편하게 가요." }
            "no_dest" -> "어디로 가세요? 목적지를 말해 주시면 길 안내하고 교통도 봐 드릴게요."
            "restarea" -> extra?.let { "${Scout.km(it.optInt("aheadM")).replace("km", "킬로미터").replace("m", "미터")} 앞에 ${it.optString("restName")}가 있어요. 쉬어 갈까요?" }
            "waypoint" -> "경유지 ${extra?.optString("viaName")?.ifBlank { null } ?: ""}에 왔어요. 볼일 보고 출발하면 ${d ?: "목적지"}까지 이어서 안내할게요."
            "halfway" -> d?.let { "벌써 절반 왔어요. ${mins()}분 남았어요." }
            "near" -> "곧 도착이에요. 내릴 준비 하세요."
            "arrived" -> "도착했어요. 오늘도 수고 많으셨어요."
            "long_drive" -> "${(trip.driveS / 60).toInt()}분째 운전 중이에요. 다음 휴게소에서 잠깐 쉬어 가요."
            "mode" -> "${mode.label} 모드예요." + (events.firstOrNull { !it.allDay }?.let { " 다음 일정은 ${it.line()}이에요." } ?: "")
            "battery_low" -> if (isCar) "배터리가 ${soc}퍼센트예요. 가는 길에 충전소를 들를까요?" else "폰 배터리가 ${phoneSoc}퍼센트예요. 충전해 두세요."
            else -> null   // quiet 등은 두뇌가 있을 때만
        }
    }

    // ---------- 주행 맥락(두뇌에게 함께 보낸다) ----------
    private fun ctx() = JSONObject().apply {
        val now = System.currentTimeMillis()
        put("t", hhmm(now)); put("speed", kmh()); put("gear", gear.toString()); put("driving", driving)
        put("bot", botName)
        if (!isCar) {
            put("device", "phone"); put("mode", mode.key); put("headset", headset)
            if (events.isNotEmpty()) put("schedule", JSONArray(events.map { it.line() }))
            if (reminders.isNotEmpty()) put("reminders", JSONArray(reminders.map { (_, at, t) -> "${hhmm(at)} $t" }))
            if (todos.isNotEmpty()) put("todos", JSONArray(todos))
            if (mode == PhoneMode.WALK && walkSince > 0) { put("walkKm", (walkM / 100).roundToInt() / 10.0); put("walkMin", ((System.currentTimeMillis() - walkSince) / 60_000).toInt()) }
        }
        trip.dest?.let { d ->
            put("dest", d); put("remainKm", km(trip.remainM)); put("remainMin", mins())
            put("eta", hhmm(now + (trip.remainS * 1000).toLong())); put("totalKm", km(trip.totalM))
            if (vias.isNotEmpty()) put("via", JSONArray(vias.map { v ->
                v.name + (if (v.remainM > 0) " · ${km(v.remainM.toDouble())}km · ${ceil(v.remainS / 60.0).toInt()}분" else "")
            }))
        }
        put("driveMin", (trip.driveS / 60).toInt())
        put("quietMin", ((SystemClock.elapsedRealtime() - lastTalkAt) / 60_000).toInt())
        scout.summary.takeIf { it.isNotEmpty() && trip.dest != null }?.let { put("traffic", it) }
        scout.ahead.takeIf { it.isNotEmpty() && trip.dest != null }?.let { put("trafficAhead", JSONArray(it)) }
        scout.roads.takeIf { it.isNotEmpty() && trip.dest != null }?.let { put("routeRoads", JSONArray(it)) }
        if (quiet()) put("quiet", true)   // 운전자가 "조용히"라 함 — 두뇌도 제안·수다를 꺼내지 않는다
        (soc ?: phoneSoc)?.let { put("soc", it) }
        addr?.let { put("addr", it) }
        tempC?.let { put("tempC", it.roundToInt()); put("rainPct", rainPct ?: 0) }
    }

    private fun render() {
        view.driving = driving; view.gear = gear; view.kmh = kmh(); view.brain = brain
        ear?.paused = view.mode == Mode.SPEAKING
        view.note = when {
            lastTurnBusy -> "회전 앞 ${turnM.toInt().coerceAtLeast(0)}m · 잠깐 조용히"
            quiet() -> "조용히 모드 ${ceil((quietUntil - SystemClock.elapsedRealtime()) / 60_000.0).toInt()}분"
            earOn() && earAlways -> "상시 듣기 · \"현비서야\""
            else -> ""
        }
        view.dest = trip.dest
        view.via = vias.firstOrNull()?.let { v -> "${v.name} 거쳐" + (if (v.remainM > 0) " · ${km(v.remainM.toDouble())}km ${ceil(v.remainS / 60.0).toInt()}분" else "") } ?: ""
        view.trafficLine = if (trip.dest == null || scout.summary.isBlank()) "" else listOfNotNull("내비 교통 " + scout.summary, scout.ahead.firstOrNull()).joinToString(" · ")
        view.waiting = speaker.waiting.map { it.prio }
        if (!isCar) {
            view.silent = silent; view.modeIdx = mode.ordinal
            val now = System.currentTimeMillis()
            val ev = events.firstOrNull { !it.allDay && it.start > now }?.let { it.line() to it.start }
            val rem = reminders.minByOrNull { it.second }?.let { "${whenLabel(it.second)} ${it.third}" to it.second }
            view.nextUp = listOfNotNull(ev, rem).minByOrNull { it.second }?.let { (line, at) ->
                val m = Desk.minutesUntil(at)
                line + when { m in 0..59 -> " · ${m}분 뒤"; m in 60..719 -> " · ${m / 60}시간${if (m % 60 > 0) " ${m % 60}분" else ""} 뒤"; else -> "" }
            } ?: ""
            view.tasks = reminders.map { (_, at, t) -> "${whenLabel(at)} $t" } + todos.map { "□ $it" }
            view.reminderItems = reminders.map { (_, at, t) -> "${whenLabel(at)}  $t" } + events.map { "일정 ${it.line()}" }
            view.todoItems = todos.map { "□  $it" }
            view.nextMore = (reminders.size + events.count { !it.allDay && it.start > now } + todos.size - (if (view.nextUp.isBlank()) 0 else 1)).coerceAtLeast(0)
        }
        if (trip.dest != null) {
            view.remain = "${km(trip.remainM)}km · ${dur()}"
            view.eta = "도착 ${hhmm(System.currentTimeMillis() + (trip.remainS * 1000).toLong())}"
            view.progress = trip.progress
        }
        view.update()
    }

    private fun refreshMemory() = link.memory { m ->
        m ?: return@memory
        fun list(k: String) = m.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        // 정차 화면 '현비서가 아는 나' — HyunView는 끝에서 8개를 거꾸로 보여 주므로, 취향(많이 말한 순)을 뒤집어 끝에 둬서 맨 앞에 뜨게
        val lines = list("facts").filterNot { it.startsWith("호칭") } + list("taste").reversed()
        main.post { view.name = m.optString("name"); view.memory = lines; view.update() }
    }

    // ---------- 목적지(차 = NaviHelper · 폰 = 직선거리 어림) ----------
    private fun goTo(name: String, dLat: Double, dLng: Double, via: Triple<String, Double, Double>? = null) {
        val here = lat?.let { a -> lng?.let { b -> Trip.meters(a, b, dLat, dLng) } }
        val est = (here ?: 10_000.0) * 1.3   // 도로 거리 어림 — 내비가 경로를 잡으면 그 값으로 바뀐다
        trip.setDestination(name, est, 0.0, estimate = true)
        scout.reset(); vias = emptyList(); restTold.clear(); noDestS = 0
        destLat = dLat; destLng = dLng
        log("nav|$name|$dLat,$dLng|est=${est.toInt()}m${via?.let { "|via=${it.first}" } ?: ""}")
        if (isCar) runCatching {
            val dest = RouteInfo(latitude = dLat, longitude = dLng, displayLongitude = null, displayLatitude = null,
                poiName = name, poiId = "", poiSubId = "0", address = name)
            if (via == null) navi?.requestRoute(dest)
            else {
                navi?.requestRouteWithWaypoints(RouteWithWaypointsRequest(dest, listOf(RequestWaypointInfo(latitude = via.second, longitude = via.third,
                    poiName = via.first, waypointIndex = WaypointIndex.FIRST, poiId = "", address = via.first, poiSubId = "0"))))
                vias = listOf(Via(via.first, 0, 0))
                main.postDelayed({ runCatching { navi?.getWaypointInfo() } }, 3000)
            }
        }.onFailure { log("nav|requestRoute|FAIL|${it.javaClass.simpleName} ${it.message}") }
        render()
    }

    private fun initNavi() {
        val n = NaviHelper(applicationContext).also { navi = it }
        runCatching {
            n.initialize(
                onConnected = {
                    log("navi|connected")
                    n.addListener(naviListener)
                    runCatching { n.getCurrentLocationInfo(); n.getDestinationInfo(); n.getWaypointInfo() }.onFailure { log("navi|get|${it.message}") }
                    // 안내 중에 앱을 켰으면 시동 인사(브리핑 '교통')보다 먼저 정찰 — 11-07 실측: "교통은 아직 정보가 없어요"
                    main.postDelayed({ if (trip.dest != null) runCatching { n.getRouteStateInfo(); n.getTBTInfo() } }, 1200)
                },
                onFailed = { t -> log("navi|init|FAIL|${t.message}") },
            )
        }.onFailure { log("navi|init|${it.message}") }
    }

    private var lastDrivingLog = 0L
    private var lastTbtLog = 0L
    @Volatile private var lastRouteAt = 0L   // 마지막 onRouteStarted — 내비에서 안내를 끈 것과 새 경로 전 취소를 가른다
    private val naviListener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) {
            log("navi|location|${info.latitude},${info.longitude}|${info.address}")
            main.post {
                val first = lat == null
                lat = info.latitude; lng = info.longitude; addr = info.address
                if (first) forecast(info.latitude, info.longitude)
            }
        }
        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            log("navi|destination|$destinationInfo")
            main.post { trip.navi(destinationInfo.name, destinationInfo.remainDistance.toDouble(), destinationInfo.remainTime.toDouble()) }
        }
        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            log("navi|routeStarted|$routeStartInfo")
            lastRouteAt = SystemClock.elapsedRealtime()
            main.post {
                scout.resetEta()
                // 경유지가 있으면 이 값은 '다음 구간'(경유지까지)이다 — 목적지 이름·거리로 덮지 않는다(11-07 실측: name=코엑스). 목적지 값은 onDrivingInfo가 준다
                if (vias.any { it.name == routeStartInfo.name }) vias = vias.map { if (it.name == routeStartInfo.name) Via(it.name, routeStartInfo.distance, routeStartInfo.duration) else it }
                else trip.navi(routeStartInfo.name, routeStartInfo.distance.toDouble(), routeStartInfo.duration.toDouble())
                // 새 경로가 실제로 섰다 = 정찰 '첫 정찰'을 여기서 다시 — 목적지만 바꿀 때 리셋하면 옛 경로의 늦은 정찰이 '첫 정찰'을 써 버려
                // 새 긴 경로의 정체를 줄줄이 알렸다(11-07 대전역 두 번 실측)
                scout.reset()
            }
            main.postDelayed({ runCatching { navi?.getRouteStateInfo() } }, 3000)   // 새 경로 = 바로 정찰
        }
        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
            val ts = routeStateInfo.trafficStatus
            log("navi|routeState|${routeStateInfo.naviMode}|${routeStateInfo.routeOption}|${ts?.summary}|jams=${ts?.congestionItemList?.size}|events=${ts?.trafficEventList?.size}|${ts?.trafficEventList?.take(3)}")
            main.post {
                routeStateInfo.routeOption?.let { routeOption = it }
                report(scout.scan(routeStateInfo, lat, lng, trip.totalM - trip.remainM))
            }
        }
        override fun onCautionPointInfo(cautionPoints: List<CautionPoint>) {
            if (cautionPoints.isNotEmpty()) log("navi|caution|${cautionPoints.take(5)}")
            main.post { report(scout.caution(cautionPoints)) }
        }
        override fun onTBTInfo(tbtInfos: List<TBTInfo>) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastTbtLog > 15_000) { lastTbtLog = now; log("navi|tbt|${tbtInfos.size}|${tbtInfos.take(4).joinToString(";") { "${it.type.name}@${it.distance.toInt()}" }}") }
            val n = tbtInfos.firstOrNull()
            // 거리는 '앞 안내에서부터'(구간)다 — 에뮬 실측 TURN_RIGHT@700;TURN_RIGHT@50 · 내비 화면 "80m → 390m"(11-07). 내 차 기준 누적으로 바꾼다
            var acc = 0.0
            val list = tbtInfos.map { acc += it.distance; it.type.name to acc }
            val rest = tbtInfos.zip(list).firstOrNull { (t, d) -> t.type.name in REST_TURNS && d.second <= REST_AHEAD_M }
            main.post {
                tbts = list; tbtAt = SystemClock.elapsedRealtime()   // 우회 타이밍 게이트(detour)·적응 정찰 주기에 쓴다
                rest?.let { (t, d) -> restArea(t.description.ifBlank { "휴게소" }, d.second.toInt()) }
                if (SystemClock.elapsedRealtime() < turnDemoUntil) return@post   // 시연 주입 중엔 덮지 않는다
                if (n == null) { turnM = -1.0; turnType = "" } else setTurn(n.distance, n.type.name, n.description)
            }
        }
        override fun onReRouteEnded() { log("navi|reRouteEnded") }
        override fun onRouteOptionChanged(routeOption: RouteOption) { log("navi|routeOption|$routeOption"); main.post { this@HyunActivity.routeOption = routeOption; scout.resetEta() } }
        override fun onDrivingInfo(drivingInfo: DrivingInfo) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastDrivingLog > 10_000) { lastDrivingLog = now; log("navi|driving|$drivingInfo") }
            drivingInfo.destination?.let { d -> main.post { trip.navi(d.name, d.distance.toDouble(), d.duration.toDouble()); scout.eta(d.duration.toDouble())?.let { report(listOf(it)) } } }
            drivingInfo.waypoints?.takeIf { it.isNotEmpty() }?.let { ws -> main.post { vias = ws.map { Via(it.name, it.distance, it.duration) } } }
        }
        override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) {
            log("navi|waypointChanged|${waypointChangedInfo.cmdType}|${waypointChangedInfo.index}|${waypointChangedInfo.routeStartInfo}")
            lastRouteAt = SystemClock.elapsedRealtime()   // 경유지를 넣어도 먼저 cancelled가 온다 — 이건 새 경로다(11-07 실측)
            main.post {
                scout.reset()   // 경유지가 들어간 새 경로 — 첫 정찰부터(요약만)
                waypointChangedInfo.routeStartInfo?.let { r -> trip.navi(r.name, r.distance.toDouble(), r.duration.toDouble()) }
                runCatching { navi?.getWaypointInfo() }
            }
            main.postDelayed({ runCatching { navi?.getRouteStateInfo() } }, 3000)   // 경로가 바뀌었다 = 바로 정찰
        }
        override fun onWayPointInfo(waypointInfos: List<WaypointInfo>) {
            log("navi|waypoints|${waypointInfos.joinToString(";") { "${it.name}@${it.remainDistance}m/${it.remainTime}s" }}")
            main.post { vias = waypointInfos.map { Via(it.name, it.remainDistance, it.remainTime) }; render() }
        }
        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {
            log("navi|waypointArrived|$waypointArrivedInfo")
            main.post { fire("waypoint", JSONObject().put("viaName", waypointArrivedInfo.name)) }
        }
        override fun onTripInfo(tripInfo: TripInfo) { log("navi|trip|$tripInfo") }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            log("navi|arrived|$destinationArrivedInfo")
            main.post { if ("arrived" !in trip.fired) fire("arrived") }
        }
        override fun onRouteCancelled() {
            log("navi|cancelled")
            // 새 경로를 잡을 때도 먼저 cancelled가 온다(경유지 경로 실측) — 12초 안에 새 경로가 안 서면 운전자가 내비에서 끈 것으로 보고 비운다
            val at = SystemClock.elapsedRealtime()
            main.postDelayed({ if (lastRouteAt < at && trip.dest != null) { log("nav|cancelled-by-navi"); trip.clear(); scout.reset(); vias = emptyList(); render() } }, 12_000)
        }
        override fun onDestinationChanged() { log("navi|destinationChanged"); runCatching { navi?.getDestinationInfo() } }
        override fun onError(errorCode: NaviErrorCode) { log("navi|error|$errorCode") }
    }

    // ---------- 날씨(위치 → Open-Meteo, 키 없음) — 대화 소재 ----------
    private fun forecast(la: Double, lo: Double) = Thread {
        runCatching {
            val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$la&longitude=$lo" +
                "&current=temperature_2m&hourly=precipitation_probability&forecast_hours=6&timezone=auto")
            val c = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 5000; readTimeout = 5000 }
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val rain = j.getJSONObject("hourly").getJSONArray("precipitation_probability")
            val t = j.getJSONObject("current").getDouble("temperature_2m").toFloat()
            val r = (0 until rain.length()).maxOfOrNull { rain.optInt(it, 0) } ?: 0
            log("weather|$la,$lo|${t}C|rain6h=$r%")
            main.post { tempC = t; rainPct = r }
        }.onFailure { log("weather|FAIL|${it.javaClass.simpleName} ${it.message}") }
    }.start()

    // ---------- 차량 배터리(Vehicle SDK) — 차에서만 ----------
    private fun initVehicle() {
        val v = Vehicle(this).also { vehicle = it }
        runCatching { v.initialize() }.onFailure { log("vehicle|init|${it.message}"); return }
        main.postDelayed({   // 서비스 바인딩 대기(H1·에이전트와 같은 방식) · 권한이 없으면 register가 동기 예외를 던진다
            runCatching { v.getEvBattery().getEvBatteryStateOfCharge({ x -> main.post { soc = x } }, { e -> log("ev.soc|FAIL|${e.message}") }) }
                .onFailure { log("ev.soc|THROW|${it.message}") }
            runCatching {
                v.getEvBattery().registerEvBatteryStateOfCharge(object : EvBatteryStateOfChargeListener {
                    override fun onEvBatteryStateOfChargeUpdated(x: Int?) { main.post { soc = x } }
                    override fun onFailed(e: Exception) { log("ev.soc.register|FAIL|${e.message}") }
                })
            }.onFailure { log("ev.soc.register|THROW|${it.message}") }
        }, 1500)
    }

    // ---------- 공통 ----------
    private fun kmh() = (speedMs * 3.6f).roundToInt()
    private fun km(m: Double) = (m / 100).roundToInt() / 10.0
    private fun mins() = ceil(trip.remainS / 60).toInt()
    /** 60분 넘으면 "3시간 52분"(화면·말 — 11-07 "233분"이라 읽음) */
    private fun dur(m: Int = mins()) = if (m >= 60) "${m / 60}시간" + (if (m % 60 > 0) " ${m % 60}분" else "") else "${m}분"
    private fun hhmm(ms: Long) = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(ms))

    /** 오늘이면 "18:00", 내일이면 "내일 09:00", 그 뒤면 "11/2(월) 09:00" */
    private fun whenLabel(ms: Long): String {
        val day = { t: Long -> SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(Date(t)) }
        val now = System.currentTimeMillis()
        return when (day(ms)) {
            day(now) -> hhmm(ms)
            day(now + 86_400_000L) -> "내일 ${hhmm(ms)}"
            else -> SimpleDateFormat("M/d(E) HH:mm", Locale.KOREA).format(Date(ms))
        }
    }
    private fun log(line: String) { Log.i(TAG, line) }

    companion object {
        private const val TAG = "HYUN"
        private const val SILENCE_MS = 8_000L    // 엔진이 듣기 시작한 뒤 말소리가 없으면 접는다
        private const val WARMUP_MS = 6_000L     // 엔진 준비를 기다리는 여유
        private const val EAR_WINDOW_MS = 10_000L   // 상시 귀: 버튼·질문 뒤 호출어 없이 받는 시간
        // 받아 적기가 첫 글자를 비슷한 소리로 적는다(실측: "변비서") — 비슷한 첫소리는 받되 그냥 "비서"는 받지 않는다
        private val WAKE = Regex("(헤이\\s*)?(현|형|혁|헌|변|편|연|견|션|선|련|헨|핸|행)\\s*(비|빈|피)\\s*(서|사|소|써)(야|아|씨|님|요)?[\\s,.!?~]*")
        private const val SPEAKING_MS = 20_000L  // 말하기 시작한 뒤 최대 길이
        private const val FILLER_MS = 3_500L     // 두뇌 답이 이보다 늦으면 맞장구 한 번
        private const val FILLER2_MS = 13_000L   // 검색 질문 — 그래도 안 오면 한 번 더
        private val CHARS = listOf("human", "orb3d", "orb")
        private val CHAR_HELLO = mapOf("human" to "비서 모습으로 바꿨어요.", "orb3d" to "젤리 구슬로 바꿨어요.", "orb" to "빛 구슬로 바꿨어요.")
        private val FILLERS = arrayOf("음, 잠깐만요.", "생각해 볼게요.", "어디 보자.")
        private const val QUIET_MS = 10 * 60_000L
        private const val TURN_GATE_M = 300.0
        private const val TURN_GATE_MAX_MS = 45_000L
        private val OPEN_TURNS = setOf("TURN_UNKNOWN", "TURN_STRAIGHT", "TURN_ARRIVE")
        // 우회가 갈라질 수 없는 안내 — 직진·도착·경유지·터널·요금소·휴게소(들어가도 같은 길로 나온다)
        private val NOT_BRANCH = setOf("TURN_UNKNOWN", "TURN_STRAIGHT", "TURN_ARRIVE", "TURN_WAY", "TURN_TUNNEL", "TURN_TOLL", "TURN_ENTER_SERVICEAREA", "TURN_RESTAREA")
        private val REST_TURNS = setOf("TURN_ENTER_SERVICEAREA", "TURN_RESTAREA")
        private const val REST_AHEAD_M = 3000.0
        private const val NO_DEST_S = 20          // 목적지 없이 이만큼(초) 달리면 "어디로 가세요?"
        private val GLEO_ACTIONS = setOf("ASK_HYUN", "HYUN_STATUS", "HYUN_QUIET")
        private const val GLEO_RESPONSE = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
        private const val REQ_PERMS = 7
        private val PHONE_PERMS = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CALENDAR) + (if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray())
    }
}
