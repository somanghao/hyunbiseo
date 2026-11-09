package io.github.somanghao.makecar.hyun.core

import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.somanghao.makecar.hyun.FrontActivity
import io.github.somanghao.makecar.hyun.brain.Link
import io.github.somanghao.makecar.hyun.drive.CarSense
import io.github.somanghao.makecar.hyun.drive.HeadsUp
import io.github.somanghao.makecar.hyun.drive.HomeWindow
import io.github.somanghao.makecar.hyun.drive.Navi
import io.github.somanghao.makecar.hyun.drive.PhoneSense
import io.github.somanghao.makecar.hyun.drive.Sense
import io.github.somanghao.makecar.hyun.drive.Trip
import io.github.somanghao.makecar.hyun.ui.HyunView
import io.github.somanghao.makecar.hyun.ui.HyunView.Mode
import io.github.somanghao.makecar.hyun.voice.Ear
import io.github.somanghao.makecar.hyun.voice.EarService
import io.github.somanghao.makecar.hyun.voice.Speaker
import io.github.somanghao.makecar.hyun.voice.Voice
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 현비서 코어 — 귀(STT · 상시 귀) · 입(TTS) · 말하기 스케줄러 · 두뇌(서버) 묻기·답 나누기 · 1초 루프 · 맥락 · 화면.
 * 기능(길 · 정찰 · 글레오 · 폰 모드 …)은 [Feature] 플러그인이 갖고, 코어는 훅으로 나눠 준다 → 새 기능 = `feature/` 파일 하나 + Registry 한 줄.
 *
 * 차(Pleos Connect·에뮬) = Pleos 음성·AAOS 차량·NaviHelper, 서버 http://10.0.2.2:8790
 * 폰(샤오미 등) = 안드로이드 음성·GPS, 서버 http://127.0.0.1:8790 — PC에서 `adb reverse tcp:8790 tcp:8790`
 * 로그 `adb logcat -s HYUN` · 코어 시연 인텐트(마이크 없이 말 등)는 [onIntent], 기능 인텐트는 각 기능 파일 맨 위
 */
class Hyun(val act: Activity, val view: HyunView, all: List<Feature>) : Voice.Events, Sense.Out, Ear.Out {
    val main = Handler(Looper.getMainLooper())
    val isCar = act.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    val botName = if (isCar) "현비서" else "짭비서"   // 폰은 짭비서 — 두뇌는 같고 캐릭터 이름·말투만 다르다(server/hyun/persona.md)
    /** 이 기기에서 켠 기능들(등록 순서) — 생성자 인자 이름을 따로 둔다: 속성 초기화 안의 익명 객체(1초 루프 등)가 인자(거르기 전 목록)를 잡았다(11-07) */
    val features: List<Feature> = all.filter { it.enabled(isCar) }
    val link = Link(if (isCar) "http://10.0.2.2:8790" else "http://127.0.0.1:8790")
    val trip = Trip()
    val prefs: SharedPreferences get() = act.getSharedPreferences("hyun", Context.MODE_PRIVATE)
    var navi: Navi? = null
        private set
    private var sense: Sense? = null
    private var voice: Voice? = null
    private val speaker = Speaker(::log)

    // ---------- 주행 맥락(Sense · 내비) ----------
    @Volatile var speedMs = 0f
        private set
    @Volatile var gear = 'P'
        private set
    @Volatile private var uxDo = false
    var lat: Double? = null
        private set
    var lng: Double? = null
        private set
    var addr: String? = null
        private set
    val moveMs get() = if (isCar) 1.4f else PhoneSense.MOVE_MS
    val driving get() = uxDo || speedMs > moveMs
    fun kmh() = (speedMs * 3.6f).roundToInt()
    fun mins() = ceil(trip.remainS / 60).toInt()

    // ---------- 두뇌 ----------
    var brain: Boolean? = null            // 서버 /hyun/health — null 서버 없음 · false 두뇌 자리 비움 · true 연결
        private set
    val brainOn get() = brain == true
    private var healthFails = 0
    private var healthSeen = false
    private var waitingId = 0             // 답을 기다리는 내 말 id(새로 말하면 앞의 답은 버린다)
    var lastTalkAt = SystemClock.elapsedRealtime()   // 마지막으로 말을 주고받은 때(수다 기능이 본다)

    // ---------- 기능이 바꾸는 말하기 설정(폰 모드) ----------
    var policyOverride: Speaker.Policy? = null       // null = 차(주행/정차 정책)
    var silent = false                               // 소리 없이 자막만(P0 제외) — 대중교통 + 이어폰 없음
    var buzz: ((Boolean) -> Unit)? = null            // 말할 때 진동(폰)

    /** 다른 기능 찾기 — 없으면 null(그 기능을 빼도 돌아가게) */
    inline fun <reified T : Feature> feature(): T? = features.firstOrNull { it is T } as T?

    fun log(line: String) { Log.i(TAG, line) }

    // ======================================================================
    // 시작 · 끝
    // ======================================================================
    fun start(intent: Intent?) {
        view.phone = !isCar; view.lightTheme = !isCar; view.botName = botName; view.hyunLine = "안녕하세요, ${botName}예요"
        view.onMic = ::micPressed
        view.onRecentCard = { k -> view.card = k; cardAt = SystemClock.elapsedRealtime(); log("card|reopen|${k.title}"); render() }
        view.onCardClose = { view.card = null; log("card|close|tap"); render() }
        if (isCar) navi = Navi(act, main, ::log).also { it.listen(naviCore); it.beforeCommand = ::primeFront }
        features.forEach { it.start(this) }
        intent?.let { onIntent(it, first = true) }
        log("start|${if (isCar) "car" else "phone"}|${Build.MANUFACTURER} ${Build.MODEL} API ${Build.VERSION.SDK_INT}|server=${link.base}|기능 ${features.joinToString(",") { it.id }}")
        voice = Voice.create(act, isCar, this)
        if (isCar) {
            sttBase = intent?.getStringExtra("stt") ?: link.base.replace(":8790", ":8791"); ear = Ear({ sttBase }, this)
            prefs.edit().remove("earAlways").apply()
            naviSplit = prefs.getBoolean("naviSplit", true)   // 옛 '눌러서 말하기' 설정 — 이제 늘 상시 듣기(사용자 11-09)
            sense = CarSense(act, this).also { it.start() }
            navi?.start(::onNaviConnected)
        } else {
            val need = PHONE_PERMS.filter { act.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (need.isEmpty()) startPhoneSense() else act.requestPermissions(need.toTypedArray(), REQ_PERMS)
        }
        main.post(tick)
        main.post(health)
        refreshMemory()
    }

    private fun onNaviConnected() {
        navi?.call("get") { getCurrentLocationInfo(); getDestinationInfo(); getWaypointInfo() }
        // 안내 중에 앱을 켰으면 시동 인사(브리핑 '교통')보다 먼저 정찰 — 11-07 실측: "교통은 아직 정보가 없어요"
        main.postDelayed({ if (trip.dest != null) navi?.call("scan") { getRouteStateInfo(); getTBTInfo() } }, 1200)
    }

    fun onPermissions(permissions: Array<out String>, grantResults: IntArray) {
        permissions.zip(grantResults.toList()).forEach { (p, g) -> log("perm|${p.substringAfterLast('.')}|${g == PackageManager.PERMISSION_GRANTED}") }
        if (act.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            act.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) startPhoneSense()
        if (!micGranted()) { view.userLine = "(마이크 권한이 없어요 — 설정에서 허용해 주세요)"; render() }
        features.forEach { it.onPermissions() }
    }

    private fun startPhoneSense() { if (sense == null) sense = PhoneSense(act, this).also { it.start() } }
    private fun micGranted() = isCar || act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private var fg = false   // 앱이 앞에 있나(onResume ~ onPause) — 마이크 포그라운드 서비스는 앞에 있을 때만 새로 켤 수 있다
    fun onResume() { fg = true; ensureEar(); if (isCar) HeadsUp.cancel(act) }
    fun onPause() { fg = false }
    fun onStop() { keepFront() }

    // ---------- 현비서 화면 지키기 — 내비 명령(경로·취소·경유지·옵션)을 받으면 내비 앱이 스스로 맨 앞으로 나와 현비서를 덮는다(11-08 실측) ----------
    // 운전자가 고른 게 아니니 현비서를 다시 앞으로 — 회전 안내는 현비서 화면의 내비 판이 보여 준다(사용자 11-08 "현비서 화면에서 좌회전·우회전").
    // 덮인 뒤엔 '뒤에서 화면 띄우기'가 막힌다(BAL_BLOCK — onPause 때도 이미 창이 안 보임) → 명령 직전에 보이지 않는 한순간 화면(FrontActivity)을
    // 켰다 닫아 10초 유예(BAL_ALLOW_GRACE_PERIOD)를 만들고, 덮이면(onStop) 그 안에 다시 앞으로. 다른 앱을 보던 중(뒤에서 음성 명령)이면 내비가 떠도 둔다.
    // 반반 화면에서 내비가 옆 칸에 뜨면 현비서는 가려지지 않아 onStop이 오지 않는다
    private var primedAt = 0L                     // 앞에 있을 때 내비 명령을 보낸 시각(0 = 지킬 것 없음)

    private fun primeFront(what: String) {
        if (!isCar || !fg) { primedAt = 0L; return }
        primedAt = SystemClock.elapsedRealtime()
        runCatching { act.startActivity(Intent(act, FrontActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)) }
            .onFailure { log("front|prime|FAIL|$what|${it.javaClass.simpleName} ${it.message}") }
    }

    private fun keepFront() {
        val at = primedAt; if (at == 0L) return
        val since = SystemClock.elapsedRealtime() - at
        primedAt = 0L                              // 명령 하나에 한 번만
        if (since > KEEP_FRONT_MS) return          // 한참 뒤 가려짐 = 운전자가 다른 화면을 고른 것
        if (navi?.lastCommand == "cancelRoute") {   // 안내 끝 — 내비를 옆에 둘 까닭이 없다: 내비를 숨기면 그 자리에 현비서(0.24초) · 안 되면 예전 되돌리기
            log("front|covered|안내 끝 ${since}ms 뒤 → 내비 숨김")
            runCatching { HomeWindow.hide(act, HomeWindow.NAVI.packageName) }.onFailure { log("front|hide|FAIL|${it.message}") }
            main.postDelayed({ if (!fg) toFront() }, 1500)
        } else if (naviSplit) {   // 반반 — 내비를 1칸으로 줄이면 빈 칸에 현비서(가장 최근에 숨은 앱). 홈이 옮기므로 BAL 무관(11-09 실측 0.37초)
            log("front|covered|명령 ${since}ms 뒤 → 반반(내비 1칸 · 홈 방송)")
            runCatching { HomeWindow.slots(act, HomeWindow.NAVI, HomeWindow.ONE) }.onFailure { log("front|split|FAIL|${it.message}") }
            autoSplit = true; sidesAt = SystemClock.elapsedRealtime() + 3000
            main.postDelayed({ if (!fg) { log("front|split|안 보임 → 다시 앞으로(대비책)"); toFront() } }, 1500)   // 홈 방송이 안 먹는 빌드 대비 — FrontActivity 유예 10초 안
        } else { log("front|covered|명령 ${since}ms 뒤 → 현비서 다시 앞으로"); toFront() }
    }

    private fun toFront() {
        runCatching { act.startActivity(Intent(act, act.javaClass).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            .onFailure { log("front|FAIL|${it.javaClass.simpleName} ${it.message}") }
    }

    /** 내비가 덮으면 반반으로(기본, 앱 설정 naviSplit · `--ez navisplit false` = 예전처럼 현비서를 다시 앞으로). Pleos 홈 비공식 방송 → wiki/hyun-pleos-capabilities.md §⑧ */
    private var naviSplit = true
    private var autoSplit = false                 // 우리가 반반으로 만들었나 — 안내가 끝나면 1칸으로 되돌린다

    /** 헤드업 알림은 Pleos 시스템이 왼쪽 위(11시)에 띄워 내비 회전 안내를 가린다 — 앱이 위치를 못 정함(11-09 실측) →
     *  사용자 11-09 "11시 방향이면 무조건 반반": 가려진 채 말하면 보이는 앱 하나를 1칸으로 줄여 빈 칸에 현비서. 헤드업은 `--ez hun true`일 때만 */
    private var hunMode = false

    private fun coveredTalk(l: Speaker.Line) {
        if (hunMode) { log("hun|P${l.prio}|${l.text}"); HeadsUp.show(act, l.text, l.prio == 0); return }
        val vis = HomeWindow.visible(act)
        val me = ComponentName(act, act.javaClass).flattenToString()
        val other = vis.singleOrNull()?.takeIf { it != me }   // 1칸 전체에 다른 앱 하나(내비 등) — 그 앱을 1칸으로
        if (other == null) { log("talk|covered|P${l.prio}|반반 불가(보이는 창 ${vis.size}) — 소리만"); return }
        log("talk|covered|P${l.prio} → 반반(${other.substringBefore('/')} 1칸)")
        ComponentName.unflattenFromString(other)?.let { HomeWindow.slots(act, it, HomeWindow.ONE); autoSplit = true }
    }

    /** 반반이면 내비 왼쪽 · 현비서 오른쪽으로 고정(사용자 11-09) — 2초마다 보이는 창 순서(왼→오)를 보고, 현비서가 왼쪽이면
     *  내비를 전체로 키웠다가 다시 1칸으로(홈은 1칸으로 줄인 앱을 왼쪽에, 빈 칸엔 최근에 숨은 앱 = 현비서) — 운전자가 ⋮로 나눈 경우도 */
    private var sidesAt = 0L

    private fun keepSides() {
        val now = SystemClock.elapsedRealtime()
        if (now - sidesAt < 2000) return
        sidesAt = now
        val vis = HomeWindow.visible(act)
        if (vis.size != 2 || vis[0] != ComponentName(act, act.javaClass).flattenToString() || !vis[1].startsWith(HomeWindow.NAVI.packageName + "/")) return
        log("front|sides|현비서 왼쪽 → 내비 왼쪽 · 현비서 오른쪽")
        sidesAt = now + 3000
        HomeWindow.slots(act, HomeWindow.NAVI, HomeWindow.TWO)
        main.postDelayed({ HomeWindow.slots(act, HomeWindow.NAVI, HomeWindow.ONE); autoSplit = true }, 700)
    }

    /** 안내 끝·도착 — 우리가 만든 반반이면 현비서를 다시 전체로 */
    private fun unsplit(why: String) {
        if (!autoSplit) return
        autoSplit = false
        sidesAt = SystemClock.elapsedRealtime() + 4000   // 1칸으로 바뀌는 사이 창 순서가 잠깐 [현비서, 내비]로 보인다 — 좌우 고정이 끼어들지 않게
        if (HomeWindow.visible(act).size >= 2) { log("front|unsplit|$why"); HomeWindow.slots(act, ComponentName(act, act.javaClass), HomeWindow.TWO) }
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        features.forEach { runCatching { it.stop() } }
        ear?.stop()
        EarService.stop(act)
        voice?.release()
        sense?.stop()
        navi?.release()
    }

    /** 길이 새로 정해졌거나 끝났다 — 기능들이 경로에 묶인 상태를 비운다 */
    fun tripReset() = features.forEach { it.onTripReset() }

    // ======================================================================
    // 시연 인텐트(코어) — 기능 인텐트는 각 기능의 onIntent
    //   말 --es say "강남역 가자" [--ez harness true] · 이벤트 강제 --es event halfway · 말하기 주입 --es line "P3|수다"
    //   서버 --es server http://… · 받아 적기 --es stt http://… · 손 안 대고 듣기 --ez listen true · 디버그 화면 --ez debug true
    //   회전 앞 침묵 --ei turnm 250 [--es turntype TURN_LEFT] · 녹음 진단 --ez rawrec true · 귀 시험 --es earfile <이름>(서버 testaudio)
    //   표 띄우기(화면 점검) --es card '{"title":"…","source":"…","items":["…"]}'
    // ======================================================================
    fun onIntent(i: Intent, first: Boolean) {
        i.getStringExtra("server")?.let { link.base = it }
        if (i.hasExtra("hun")) { hunMode = i.getBooleanExtra("hun", false); log("front|hunMode=$hunMode") }
        if (i.hasExtra("navisplit")) { naviSplit = i.getBooleanExtra("navisplit", true); prefs.edit().putBoolean("naviSplit", naviSplit).apply(); log("front|naviSplit=$naviSplit") }
        i.getStringExtra("line")?.let { l ->       // "P3|말" — 스케줄러 시험
            val pr = l.substringBefore("|").removePrefix("P").toIntOrNull()?.coerceIn(0, 3) ?: 2
            main.postDelayed({ say(l.substringAfter("|"), if (pr == 0) "alert" else "", false, pr, tag = "demo") }, if (first) 6000 else i.getIntExtra("after", 0).toLong())   // --ei after ms(가려진 뒤 말 — 헤드업 알림 시험)
        }
        val harness = i.getBooleanExtra("harness", false)   // 하네스 화면 시험 — 서버가 취향·기억·최근 대화에 넣지 않는다(tools/hyun_harness)
        i.getStringExtra("say")?.let { q -> main.postDelayed({ heard(q, harness) }, if (first) 5000 else 0) }
        if (i.getBooleanExtra("rawrec", false)) { rawRecArmed = true; view.userLine = "(녹음 진단: 마이크를 누르고 말하세요)"; render() }
        if (i.hasExtra("debug")) { view.debug = i.getBooleanExtra("debug", false); render() }
        if (i.getBooleanExtra("listen", false)) main.postDelayed({ listen() }, if (first) 7000 else 0)
        if (i.hasExtra("turnm")) main.postDelayed({   // 회전 앞 침묵 시험 — 다음 안내까지 N미터, 주행 속도로 다가간다
            setTurn(i.getIntExtra("turnm", 250).toDouble(), i.getStringExtra("turntype") ?: "TURN_LEFT", "demo")
            turnDemoUntil = SystemClock.elapsedRealtime() + 120_000
            log("turn|demo|${turnM.toInt()}m|$turnType")
        }, if (first) 6000 else 0)
        i.getStringExtra("sttfile")?.let { f -> main.postDelayed({ feedStt(f) }, if (first) 7000 else 0) }
        i.getStringExtra("earfile")?.let { f -> main.postDelayed({ feedEar(f) }, if (first) 8000 else 0) }
        i.getStringExtra("card")?.let { j -> runCatching { JSONObject(j) }.getOrNull()?.let { k -> main.postDelayed({ showCard(k); render() }, if (first) 3000 else 0) } }   // 화면 점검 — 표 띄우기(--es card '{"title":…,"items":[…]}')
        features.forEach { it.onIntent(i, first) }
        i.getStringExtra("event")?.let { e -> main.postDelayed({ trip.fired -= e; fire(e) }, if (first) 5000 else 0) }
    }

    // ======================================================================
    // 1초 루프 · 서버 상태
    // ======================================================================
    private var cardAt = 0L                     // 카드가 뜬 시각 — CARD_MS 지나면 닫는다

    private val tick = object : Runnable {
        override fun run() {
            trip.tick(1.0, speedMs.toDouble())
            val now = SystemClock.elapsedRealtime()
            if (view.card != null && now - cardAt > CARD_MS) view.card = null
            if (now < turnDemoUntil && turnM >= 0) {   // 시연 회전: 속도만큼 다가가고, 지나면 지운다
                turnM -= speedMs; turnAt = now
                if (turnM < 0) { log("turn|demo|passed"); turnDemoUntil = 0; turnM = -1.0 }
            }
            features.forEach { it.tick() }
            if (isCar && fg) keepSides()
            if (speaker.waiting.isNotEmpty()) pump() else render()
            main.postDelayed(this, 1000)
        }
    }

    private val health = object : Runnable {
        override fun run() {
            link.health { j ->
                val b: Boolean? = j?.optBoolean("brain")
                main.post {
                    view.brainMode = j?.optString("mode")?.takeIf { it.isNotBlank() && it != "null" } ?: ""
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

    /** 지금 한가한가 — 듣기·말하기·생각 중이 아니고 줄 선 말도 없다(먼저 말 걸기 기능이 본다) */
    fun idle() = view.mode == Mode.READY && waitingId == 0 && speaker.waiting.isEmpty()

    // ======================================================================
    // 이벤트 · 대화(두뇌)
    // ======================================================================
    /** 주행 이벤트 — 두뇌가 있으면 묻고, 없으면 기능의 규칙 문장(fallback) */
    fun fire(ev: String, extra: JSONObject? = null) {
        trip.fired += ev
        log("event|$ev${extra?.let { "|$it" } ?: ""}")
        if (!brainOn) { fallbackEvent(ev, extra)?.let { say(it, "", ev in LISTEN_EVENTS, Speaker.eventPrio(ev), tag = "$ev·규칙") }; return }
        ask("event", ev, extra)
    }

    /** 운전자가 한 말(귀 · STT · 글 · 글레오 · 시연) — 기능 명령이면 기능이, 아니면 두뇌가 */
    fun heard(text: String, harness: Boolean = false) {
        if (maxLevel > 0f) logLevel("heard")
        log("heard|$text")
        main.removeCallbacks(listenTimeout)
        view.userLine = text
        view.addChat(true, text)   // 대화형 말풍선
        view.card = null   // 새 질문 — 이전 표를 지운다(하네스 화면 점검: 이전 카드가 남아 답과 안 맞음 4건, 11-06)
        lastTalkAt = SystemClock.elapsedRealtime()
        reasked = false
        view.mode = Mode.READY
        if (features.any { it.onHeard(text) }) { render(); return }
        if (!brainOn) { say(offlineAnswer(text), "", false, 1, reply = true, tag = "규칙 답"); return }
        view.mode = Mode.THINKING
        render()
        ask("talk", text, if (harness) JSONObject().put("harness", true) else null)
    }

    fun ask(kind: String, text: String, extra: JSONObject? = null) {
        val c = ctx().also { c -> extra?.keys()?.forEach { k -> c.put(k, extra.get(k)) } }
        val destAtSend = trip.dest   // 이벤트 답이 오는 사이 목적지가 바뀌면 그 답은 옛 길 얘기 — onReply에서 버린다
        link.send(kind, text, c) { id ->
            main.post {
                log("ask|$id|$kind|$text")   // 시험 기록(tools/hyun_e2e)이 이 질문의 답(reply|id)을 짝짓는다
                if (id == null) {   // 서버가 사라졌다
                    brain = null
                    if (kind == "talk") { view.mode = Mode.READY; say(offlineAnswer(text), "", false, 1, reply = true, tag = "규칙 답") }
                    else fallbackEvent(text, null)?.let { say(it, "", false, Speaker.eventPrio(text), tag = "$text·규칙") }
                    return@post
                }
                if (kind == "talk") {
                    waitingId = id
                    // 두뇌 한 턴은 5~10초 — 조용히 기다리면 못 들은 줄 안다. 3.5초 넘으면 짧게 맞장구(한 번), 검색은 13초에 한 번 더
                    main.postDelayed({ if (waitingId == id) say(FILLERS[id % FILLERS.size], "", false, 1, reply = true, tag = "맞장구") }, FILLER_MS)
                    main.postDelayed({ if (waitingId == id) say("조금만 더 찾아볼게요.", "", false, 1, reply = true, tag = "맞장구2") }, FILLER2_MS)
                }
                link.awaitReply(id, if (kind == "talk") 45_000 else 30_000) { r -> main.post { onReply(kind, text, id, r, destAtSend) } }
            }
        }
    }

    private fun onReply(kind: String, text: String, id: Int, r: JSONObject?, destAtSend: String? = trip.dest) {
        if (kind == "talk") { if (id != waitingId) return; waitingId = 0 }
        log("reply|$id|$r")
        // 이벤트를 보낸 뒤 목적지가 바뀌었으면(곧 도착 제안에 "응" → 식당으로) 옛 길의 답(출발 안내·카드)은 말하지 않는다 — 11-08 E2E e18:
        // 식당으로 바꾼 뒤 '코엑스까지 34분'이 나옴. 목적지가 없다가 생긴 것(시동 인사 뒤 내비 목적지 읽힘)은 그대로
        if (kind == "event" && destAtSend != null && destAtSend != trip.dest) { log("reply|$id|stale|$text|$destAtSend→${trip.dest}"); render(); return }
        val talk = kind == "talk"
        val base = if (talk) 1 else Speaker.eventPrio(text)
        if (view.mode == Mode.THINKING && waitingId == 0) view.mode = Mode.READY
        if (r == null) {   // 두뇌가 늦다
            if (talk) say("음, 생각이 길어졌네요. 한 번만 다시 말해 줄래요?", "", true, 1, reply = true, tag = "늦음")
            else fallbackEvent(text, null)?.let { say(it, "", false, base, tag = "$text·규칙") }
            return
        }
        if (r.optBoolean("skipped")) { render(); return }
        features.forEach { it.onReply(kind, text, r) }   // nav · via · route · mode · tasks … 각 기능이 자기 필드만
        r.optJSONObject("card")?.let(::showCard)
        // 두뇌가 정한 단계(1~3)가 있으면 그걸로 — P0은 앱 규칙만. 주행 필수 이벤트(P1)는 두뇌가 낮춰도 P1(P2 쿨다운에 3분 밀린 11-07 실측)
        val prio = if (r.has("prio") && !r.isNull("prio")) r.optInt("prio", base).coerceIn(1, 3).let { if (!talk && base == 1) 1 else it } else base
        val s = r.optString("say")
        if (s.isNotBlank()) say(s, r.optString("mood"), r.optBoolean("listen"), prio, reply = talk, tag = if (talk) "답" else text)
        else render()
        refreshMemory()
    }

    /** 카드(목록 · 표지 격자 · 소개) — 정차 화면에(주행 중엔 화면이 숨기고, 정차하면 보인다). 서버가 중계하는 사진(/hyun/img)은 서버 주소를 붙인다 */
    fun showCard(k: JSONObject) {
        val items = k.optJSONArray("items")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty().filter { it.isNotBlank() }
        if (items.isEmpty()) return
        val imgs = k.optJSONArray("images")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }.orEmpty()
            .map { if (it.startsWith("/")) link.base + it else it }
        view.card = HyunView.Card(k.optString("title"), k.optString("source"), items, imgs, k.optString("kind"))
        cardAt = SystemClock.elapsedRealtime()
        log("card|${k.optString("title")}|${items.size}|kind=${k.optString("kind").ifBlank { "list" }}|img=${imgs.count { it.isNotBlank() }}|${items.take(3).joinToString(" / ")}")
    }

    private fun refreshMemory() = link.memory { m ->
        m ?: return@memory
        fun list(k: String) = m.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
        // 정차 화면 '현비서가 아는 나' — HyunView는 끝에서 8개를 거꾸로 보여 주므로, 취향(많이 말한 순)을 뒤집어 끝에 둬서 맨 앞에 뜨게
        val lines = list("facts").filterNot { it.startsWith("호칭") } + list("taste").reversed()
        main.post { view.name = m.optString("name"); view.memory = lines; view.update() }
    }

    // ---------- 두뇌가 없을 때: 사실은 앱이 직접(기능이 먼저), 나머지는 솔직하게 ----------
    private fun offlineAnswer(q: String): String {
        features.firstNotNullOfOrNull { it.offline(q) }?.let { return it }
        val t = q.replace(" ", "")
        return when {
            t.contains("몇시") || t.contains("시간") -> "지금은 ${Fmt.hhmm(System.currentTimeMillis()).replace(":", "시 ")}분이에요."
            listOf("클로드", "claude", "두뇌", "연결", "로그인").any { it in t.lowercase() } ->
                if (brain == false) "Claude가 아직 연결 안 됐어요. PC나 폰 브라우저에서 현비서 서버 주소 뒤에 hyun 슬래시 login을 열고 API 키를 넣으면 바로 이어져요."
                else "Claude 서버가 꺼져 있어요. 지금은 시간, 배터리, 남은 거리만 알려드릴 수 있어요."
            else -> "지금은 제 생각 회로가 연결이 안 됐어요. 남은 시간이나 배터리는 바로 알려드릴 수 있어요."
        }
    }

    private fun fallbackEvent(ev: String, extra: JSONObject?): String? =
        features.firstNotNullOfOrNull { it.fallback(ev, extra) } ?: when (ev) {
            "hello" -> trip.dest?.let { "안녕하세요, ${botName}예요. ${it}까지 같이 가요." } ?: "안녕하세요, ${botName}예요. 오늘은 어디로 가요?"
            else -> null   // quiet 등은 두뇌가 있을 때만
        }

    // ======================================================================
    // 맥락 · 화면
    // ======================================================================
    /** 두뇌에게 같이 보내는 맥락 — 코어 기본 + 기능들이 보탠다 */
    fun ctx() = JSONObject().apply {
        put("t", Fmt.hhmm(System.currentTimeMillis())); put("speed", kmh()); put("gear", gear.toString()); put("driving", driving)
        put("bot", botName)
        if (!isCar) put("device", "phone")
        put("driveMin", (trip.driveS / 60).toInt())
        put("quietMin", ((SystemClock.elapsedRealtime() - lastTalkAt) / 60_000).toInt())
        if (quiet()) put("quiet", true)   // 운전자가 "조용히"라 함 — 두뇌도 제안·수다를 꺼내지 않는다
        addr?.let { put("addr", it) }
        features.forEach { f -> f.ctx(this) }
    }

    fun render() {
        view.driving = driving; view.gear = gear; view.kmh = kmh(); view.brain = brain
        ear?.paused = view.mode == Mode.SPEAKING
        view.note = when {
            lastTurnBusy -> "회전 앞 ${turnM.toInt().coerceAtLeast(0)}m · 잠깐 조용히"
            quiet() -> "조용히 모드 ${ceil((quietUntil - SystemClock.elapsedRealtime()) / 60_000.0).toInt()}분"
            earOn() -> "상시 듣기 · \"현비서야\""
            else -> ""
        }
        view.waiting = speaker.waiting.map { it.prio }
        view.lines.clear()   // 기능 한 줄은 매번 기능들이 다시 채운다
        features.forEach { it.render(view) }
        view.update()
    }

    // ======================================================================
    // 말하기 — 스케줄러(Speaker)가 순서·끊기·보류·버림을 정한다
    // ======================================================================
    fun say(text: String, mood: String = "", listen: Boolean = false, prio: Int = 1, reply: Boolean = false, tag: String = "") {
        if (text.isBlank()) return
        if (!reply && prio >= 2 && quiet()) { log("prio|drop|P$prio|조용히 모드|$text"); return }
        speaker.offer(Speaker.Line(text, mood, listen, prio, reply, tag))
        pump()
    }

    // "조용히" — 이 시각까지 P2·P3(대답 제외)를 말하지 않는다
    private var quietUntil = 0L
    fun quiet() = SystemClock.elapsedRealtime() < quietUntil

    /** 조용히 모드 켜기·끄기 — 돌려주는 말은 부른 쪽이 말한다(글레오는 자기 응답으로) */
    fun setQuiet(on: Boolean): String = if (on) {
        quietUntil = SystemClock.elapsedRealtime() + QUIET_MS
        speaker.drop("조용히") { !it.reply && it.prio >= 2 }
        log("quiet|on|${QUIET_MS / 60_000}분")
        "알겠어요. ${QUIET_MS / 60_000}분 동안은 꼭 필요한 말만 할게요."
    } else { quietUntil = 0L; log("quiet|off"); "네, 다시 말동무할게요." }

    private fun pump() {
        speaker.policy = policyOverride ?: if (driving) Speaker.Policy.DRIVE else Speaker.Policy.PARKED
        val tb = turnBusy()
        if (tb != lastTurnBusy) { lastTurnBusy = tb; log("turn|gate|${if (tb) "on" else "off"}|$turnType|${turnM.toInt()}m|대기 ${speaker.waiting.size}") }
        val now = Speaker.Now(listening = view.mode == Mode.LISTENING, speaking = view.mode == Mode.SPEAKING, thinking = waitingId != 0, turnBusy = tb)
        val a = speaker.next(now)
        if (a is Speaker.Act.Speak) speakNow(a.line)
        render()
    }

    private var listenAfter = false               // 말이 끝나면 마이크를 연다(질문으로 끝난 답)

    private fun speakNow(l: Speaker.Line) {
        if (view.mode == Mode.LISTENING) stopListening()
        log("say|P${l.prio}|${if (silent && l.prio > 0) "자막만|" else ""}${l.text}")
        view.hyunLine = l.text; view.mood = l.mood; view.prio = l.prio; view.prioReply = l.reply
        view.addChat(false, l.text)
        if (isCar && !fg) coveredTalk(l)   // 가려진 동안(내비 전체 화면 등) 말하면 — 반반으로 옆에 나온다
        listenAfter = l.listen
        buzz?.invoke(l.prio == 0)
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

    // ---------- 회전 앞 침묵 — 내비 다음 안내까지 300m 안이면 P0 말고는 기다린다(45초 넘게 묶이면 푼다) ----------
    private var turnM = -1.0
    private var turnType = ""
    private var turnKey = ""
    private var turnAt = 0L
    private var gateSince = 0L
    private var turnDemoUntil = 0L                // 시연 주입(--ei turnm) 동안은 실제 TBT로 덮지 않고 속도로 다가간다
    private var lastTurnBusy = false

    private fun setTurn(m: Double, type: String, desc: String) {
        val key = "$type|$desc"
        if (key != turnKey) { turnKey = key; gateSince = 0L }
        turnM = m; turnType = type; turnAt = SystemClock.elapsedRealtime()
    }

    private fun turnBusy(): Boolean {
        val now = SystemClock.elapsedRealtime()
        val on = isCar && driving && now - turnAt < 15_000 && turnM in 0.0..TURN_GATE_M && turnType !in OPEN_TURNS
        if (!on) { gateSince = 0L; return false }
        if (gateSince == 0L) gateSince = now
        return now - gateSince < TURN_GATE_MAX_MS
    }

    /** 코어가 듣는 내비 콜백 — 위치(맥락)와 다음 회전(침묵 구간) */
    private val naviCore = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) { place(info.latitude, info.longitude, info.address) }
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) { unsplit("arrived") }
        override fun onRouteCancelled() {   // 새 경로 전에도 온다(11-07) — 안내 끝 명령이거나 운전자가 내비에서 끝낸 것만
            val n = navi ?: return
            if (n.lastCommand == "cancelRoute" || SystemClock.elapsedRealtime() - n.lastCommandAt > 10_000) unsplit("cancel")
        }
        override fun onTBTInfo(tbtInfos: List<TBTInfo>) {
            if (SystemClock.elapsedRealtime() < turnDemoUntil) return   // 시연 주입 중엔 덮지 않는다
            val n = tbtInfos.firstOrNull()
            if (n == null) { turnM = -1.0; turnType = "" } else setTurn(n.distance, n.type.name, n.description)
        }
    }

    // ======================================================================
    // 듣기 — 상시 귀(Ear, 받아 적기 서버) 또는 Pleos/안드로이드 STT
    // ======================================================================
    private var ear: Ear? = null
    private var earReady = false
    private var sttBase = ""
    private fun earOn() = ear?.running == true && earReady
    private fun earUsable() = ear != null && earReady
    private var heardText = ""
    private var answered = false
    private var reasked = false                   // 못 알아들으면 한 번만 다시 묻는다(대화 창마다)
    private var spoke = false                     // 이번 듣기에서 말소리가 시작됐나(Pleos STT) — 빈 결과면 다시 묻기
    private var greeted = false

    /** 귀 켜기 — 늘 상시 듣기(호출어 "현비서야", 눌러서 말하기 모드는 11-09 없앰). 차는 마이크 포그라운드 서비스도(내비 화면에서도 듣게) */
    private fun ensureEar() {
        if (earUsable() && micGranted()) {
            ear?.start()
            if (isCar && fg) EarService.start(act)
        } else { ear?.stop(); EarService.stop(act) }
    }

    private fun micPressed() {
        if (rawRecArmed && view.mode != Mode.LISTENING) { rawRecArmed = false; rawRecord(); return }
        when (view.mode) {
            Mode.SPEAKING -> { listenAfter = false; voice?.stopSpeaking(); speaker.finished(); view.mode = Mode.READY; listen() }
            Mode.LISTENING -> {   // 다시 누르면 듣기 끝 — 들은 말이 있으면 보낸다
                val q = heardText.takeIf { it.isNotBlank() && !answered }
                stopListening()
                if (q != null) heard(q) else endListening()
            }
            else -> { reasked = false; listen() }
        }
    }

    /** 대화 창 열기(손 안 대고 이어 말하기 · 마이크 버튼 · 글레오 호출 자리) */
    fun listen() {
        if (!micGranted()) { view.userLine = "(마이크 권한을 허용해 주세요)"; act.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_PERMS); render(); return }
        if (earUsable()) {   // 앱 귀 — 이미 상시로 듣는 중 · 호출어 없이 받을 '대화 창'을 연다
            ear?.start()
            if (view.mode == Mode.SPEAKING) { voice?.stopSpeaking(); speaker.finished() }
            heardText = ""; answered = false
            view.mode = Mode.LISTENING; view.userLine = "말씀하세요…"; render()
            main.removeCallbacks(listenTimeout); main.postDelayed(listenTimeout, EAR_WINDOW_MS)
            return
        }
        val v = voice
        if (v == null || !v.canListen) { view.userLine = "(음성 인식을 준비하고 있어요)"; render(); return }
        if (view.mode == Mode.SPEAKING) { v.stopSpeaking(); speaker.finished() }
        heardText = ""; answered = false; spoke = false
        view.mode = Mode.LISTENING; view.userLine = "…"
        render()
        v.listen()
        main.removeCallbacks(listenTimeout)
        main.postDelayed(listenTimeout, SILENCE_MS + WARMUP_MS)   // 엔진 준비(onHearReady) 전엔 길게 — 준비되면 그때부터 8초
    }

    /** STT는 말소리가 없으면 끝없이 기다리기도 한다(에뮬 실측) — 조용하면 8초, 말하기 시작했으면 20초에 접는다 */
    private val listenTimeout = Runnable {
        if (view.mode != Mode.LISTENING) return@Runnable
        log("stt|timeout|${heardText.ifBlank { "(조용)" }}")
        if (heardText.isNotBlank() && !answered) { answered = true; stopListening(); heard(heardText) }
        else { view.userLine = ""; stopListening(); endListening() }
    }

    fun stopListening() {
        main.removeCallbacks(listenTimeout)
        answered = true
        voice?.stopListening()
        if (view.mode == Mode.LISTENING) view.mode = Mode.READY
    }

    /** 듣기가 끝났으면 듣는 사이 줄 선 말을 꺼낸다 */
    private fun endListening() {
        logLevel("end")
        main.removeCallbacks(listenTimeout)
        if (view.mode == Mode.LISTENING) view.mode = Mode.READY
        pump()
    }

    /** 말소리는 있었는데 못 알아들었다(받아 적기가 빈 글자) — 대화 창마다 한 번만 다시 묻고, 또 못 알아들으면 조용히 닫는다 */
    private fun reask() {
        if (reasked) { log("reask|again|닫음"); view.userLine = ""; stopListening(); endListening(); return }
        reasked = true
        log("reask")
        stopListening()
        say("잘 못 들었어요. 한 번만 다시 말해 주세요.", "calm", true, 1, reply = true, tag = "다시 묻기")
    }

    /** "현비서야 …"의 호출어 뒤 말 — 호출어가 없으면 null. 한 구간 어디서든 찾고 **마지막** 호출어 뒤만(앞의 남의 말은 버림) */
    private fun stripWake(t: String): String? {
        val m = WAKE.findAll(t).lastOrNull() ?: return null
        return t.substring(m.range.last + 1).trim().trimStart(',', '.', '!', '?', '~', ' ')
    }

    @Volatile private var maxLevel = 0f   // 한 번 듣는 동안 마이크 최대 음량(0..1) — 0이면 소리가 앱까지 안 온 것
    private fun logLevel(why: String) { log("stt|level|$why|max=${"%.2f".format(maxLevel)}"); maxLevel = 0f }

    // ---------- Voice.Events (아무 스레드 → main) ----------
    override fun onVoiceReady() { main.post { helloWhenReady(0) } }

    /** 첫 인사 — 서버 health를 한 번 받은 뒤에(안 그러면 두뇌가 있어도 규칙 인사가 나간다, 폰 실측) · 최대 8초 */
    private fun helloWhenReady(tries: Int) {
        if (greeted) return
        if (!healthSeen && tries < 8) { main.postDelayed({ helloWhenReady(tries + 1) }, 1000); return }
        greeted = true; main.postDelayed({ fire("hello") }, 800)
    }
    override fun onSpeakDone() { main.post { log("tts|done"); afterSpeech() } }   // tts|done = 시험 기록이 말이 끝난 때를 잰다
    override fun onSpeakStopped() { log("tts|stopped") }
    override fun onHearReady() { main.post { if (view.mode == Mode.LISTENING && heardText.isBlank()) { main.removeCallbacks(listenTimeout); main.postDelayed(listenTimeout, SILENCE_MS); view.userLine = "말씀하세요…"; render() } } }
    override fun onHearStart() { main.post { spoke = true; main.removeCallbacks(listenTimeout); main.postDelayed(listenTimeout, SPEAKING_MS) } }
    override fun onHeard(text: String, final: Boolean) {
        main.post {
            if (answered) return@post
            heardText = text; view.userLine = text
            if (final) { answered = true; view.mode = Mode.READY; heard(text) } else view.update()
        }
    }
    override fun onHearEnd() {
        main.post {
            log("stt|end|$heardText")
            if (answered) return@post
            answered = true
            if (heardText.isNotBlank()) { view.mode = Mode.READY; heard(heardText) }
            else if (spoke) reask()
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
    override fun onLevel(level: Float) { if (view.mode == Mode.LISTENING && level > maxLevel) maxLevel = level; view.pulse(level) }

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
                window -> { log("ear|text|$tag|$t"); answered = true; main.removeCallbacks(listenTimeout); view.mode = Mode.READY; heard(rest?.ifBlank { null } ?: t) }
                rest != null && rest.isNotBlank() -> {
                    log("ear|wake+text|$tag|$t")
                    if (view.mode == Mode.SPEAKING) { listenAfter = false; voice?.stopSpeaking(); speaker.finished(); view.mode = Mode.READY }
                    heard(rest)
                }
                rest != null -> { log("ear|wake|$tag|$t"); reasked = false; say("네, 말씀하세요.", "happy", true, 1, reply = true, tag = "호출어") }
                else -> log("ear|ignored|$tag|${t.length}자")
            }
            render()
        }
    }

    // ---------- Sense.Out (아무 스레드) ----------
    override fun onSpeed(ms: Float) { speedMs = ms }
    override fun onGear(g: Char) { if (g != gear) log("gear|$g"); gear = g }
    override fun onDo(on: Boolean) { if (on != uxDo) log("ux|DO=$on"); uxDo = on }
    override fun onPlace(lat: Double, lng: Double, addr: String?) { place(lat, lng, addr) }

    private fun place(la: Double, lo: Double, ad: String?) = main.post {
        val first = lat == null
        lat = la; lng = lo
        if (ad != null && ad != addr) { addr = ad; log("loc|$la,$lo|$ad") }
        features.forEach { it.onLocation(la, lo, addr, first) }
    }

    // ---------- 진단 · 시험 ----------
    /** 마이크 원본 7초 녹음(16kHz mono PCM) → filesDir/rec.pcm — 소리 경로(에뮬 가상 마이크)가 멀쩡한지 PC에서 들어 본다 */
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
                java.io.File(act.filesDir, "rec.pcm").writeBytes(out.toByteArray())
                "${out.size()}B peak=$peak"
            }.getOrElse { "FAIL ${it.javaClass.simpleName} ${it.message}" }
            log("rawrec|$r")
            main.post { view.mode = Mode.READY; view.userLine = "(녹음 진단 끝 — $r)"; render() }
        }.start()
    }

    /** 마이크 대신 녹음 파일(filesDir, 16kHz PCM)로 Pleos STT 시험 — 서비스가 죽어 Pleos 쪽은 막아 둠(Voice.feed) */
    private fun feedStt(f: String) {
        val bytes = runCatching { java.io.File(act.filesDir, f).readBytes() }.getOrNull()
        if (bytes == null) { log("stt|feed|no file $f"); return }
        if (view.mode == Mode.SPEAKING) { voice?.stopSpeaking(); speaker.finished() }
        heardText = ""; answered = false; view.mode = Mode.LISTENING; view.userLine = "…"; render()
        main.removeCallbacks(listenTimeout); main.postDelayed(listenTimeout, SPEAKING_MS)
        if (voice?.feed(bytes) != true) { log("stt|feed|unsupported"); endListening() } else log("stt|feed|${bytes.size}B")
    }

    /** 귀 시험 — PC 서버의 녹음(/hyun/testaudio)을 받아 귀에 넣는다(마이크 없이 받아 적기·호출어·두뇌까지, tools/hyun_e2e) */
    private fun feedEar(name: String) = Thread {
        val b = runCatching {
            val c = (URL("${link.base}/hyun/testaudio?name=$name").openConnection() as HttpURLConnection).apply { connectTimeout = 3000; readTimeout = 5000 }
            c.inputStream.use { it.readBytes() }
        }.getOrNull()
        if (b == null || b.isEmpty()) { log("ear|feed|FAIL|$name"); return@Thread }
        log("ear|feed|$name|${b.size}B|${"%.1f".format(b.size / 32000.0)}s")
        ear?.feed(b) ?: log("ear|feed|no ear")
    }.start()

    companion object {
        private const val TAG = "HYUN"
        private const val CARD_MS = 180_000L
        private const val SILENCE_MS = 8_000L       // 엔진이 듣기 시작한 뒤 말소리가 없으면 접는다
        private const val WARMUP_MS = 6_000L        // 엔진 준비를 기다리는 여유
        private const val EAR_WINDOW_MS = 10_000L   // 상시 귀: 버튼·질문 뒤 호출어 없이 받는 시간
        private const val SPEAKING_MS = 20_000L     // 말하기 시작한 뒤 최대 길이
        private const val FILLER_MS = 3_500L        // 두뇌 답이 이보다 늦으면 맞장구 한 번
        private const val FILLER2_MS = 13_000L      // 검색 질문 — 그래도 안 오면 한 번 더
        private val FILLERS = arrayOf("음, 잠깐만요.", "생각해 볼게요.", "어디 보자.")
        private const val QUIET_MS = 10 * 60_000L
        private const val KEEP_FRONT_MS = 4_000L    // 내비 명령 뒤 이 안에 가려지면 내비가 스스로 뜬 것 — 그 뒤는 운전자가 고른 것으로 본다
        private const val TURN_GATE_M = 300.0
        private const val TURN_GATE_MAX_MS = 45_000L
        private val OPEN_TURNS = setOf("TURN_UNKNOWN", "TURN_STRAIGHT", "TURN_ARRIVE")
        private val LISTEN_EVENTS = setOf("no_dest")   // 규칙 문장이 질문인 이벤트 — 말한 뒤 듣기
        // 받아 적기가 첫 글자를 비슷한 소리로 적는다(실측: "변비서") — 비슷한 첫소리는 받되 그냥 "비서"는 받지 않는다
        private val WAKE = Regex("(헤이\\s*)?(현|형|혁|헌|변|편|연|견|션|선|련|헨|핸|행)\\s*(비|빈|피)\\s*(서|사|소|써)(야|아|씨|님|요)?[\\s,.!?~]*")
        const val REQ_PERMS = 7
        private val PHONE_PERMS = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.READ_CALENDAR) + (if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray())
    }
}
