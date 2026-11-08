package io.github.somanghao.makecar.hyun.feature

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import io.github.somanghao.makecar.hyun.core.Feature
import io.github.somanghao.makecar.hyun.core.Hyun
import io.github.somanghao.makecar.hyun.drive.CarCheck
import io.github.somanghao.makecar.hyun.drive.CarStatus
import io.github.somanghao.makecar.hyun.ui.StageUi
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import kotlin.math.max
import kotlin.math.roundToInt

/** 차 상황 6단계 — 시동 → 출발 준비 → 주행 중 → 잠깐 정차(신호대기) → 주차 → 시동 끔 (댄 11-08) */
enum class Sit(val key: String, val label: String) {
    IGNITION("ignition", "시동"), READY("ready", "출발 준비"), DRIVE("drive", "주행 중"),
    STOP("stop", "잠깐 정차"), PARK("park", "주차"), OFF("off", "시동 끔");
    companion object { fun of(k: String) = values().firstOrNull { it.key == k || it.label == k } }
}

/** 상황 화면의 카드 한 장 — tone = 색(accent·warn·good·info·muted) · tag = 오른쪽 위 작은 표시(예: "예시") */
data class Tile(val icon: String, val label: String, val value: String, val sub: String = "", val tone: String = "accent", val tag: String = "", val action: String? = null)

/** 꾸미기 상품 — kind theme(배경 조명 색) · face(기본 표정) · acc(소품 스티커) */
data class Deco(val kind: String, val id: String, val name: String, val price: Int, val color: Int = 0, val glyph: String = "")

/** 화면(StageUi)이 읽는 상태 — 기능이 채우고 화면은 그리기만 */
class StageModel {
    var sit = Sit.IGNITION
    var sitAt = SystemClock.elapsedRealtime()
    var forced = false
    var skill: String? = null                 // "novice" · "expert" · null(아직 안 물음)
    var overlay: String? = null               // onboard · deco · settings · debug
    var onboardStep = 0                       // 0 점검 · 1 질문 · 2 맞춤 완료
    var check: List<CarCheck.Item> = emptyList()
    var checkAt = 0L                          // 점검 결과가 나온 시각(한 줄씩 나타나는 연출)
    var kicker = ""
    var headline = ""
    var subline = ""
    var tiles: List<Tile> = emptyList()
    var tip = ""                              // 초보 팁 한 줄(정차·주차·출발 직전)
    var bubble = ""                           // 말풍선 기본 글(이 상황에서 아직 새로 말한 게 없을 때)
    var lineAtEnter = ""                      // 상황에 들어올 때의 마지막 말 — 같으면 지난 상황의 말이라 bubble 을 보인다
    var weatherStickers: List<String> = emptyList()   // 날씨 옷차림(시동 화면) — 🧣 ☂️ ☕ 🕶️
    var points = 0
    var tripPoints = 0
    var theme = "midnight"; var face = "neutral"; var acc = ""
    var owned: Set<String> = setOf("theme:midnight", "face:neutral", "acc:")
    var decoTab = "theme"
    var status: io.github.somanghao.makecar.hyun.drive.CarStatus? = null
    var toast = ""; var toastAt = 0L
    // 주행 중 길 안내(내비가 없으면 모의 경로)
    var mockRoute = false
    var navTurn = ""; var navTurnType = ""; var navTurnM = 0; var navDest = ""; var navRemain = ""; var navEta = ""
    val novice get() = skill == "novice"
    fun accent(): Int = THEMES.firstOrNull { it.id == theme }?.color ?: 0xFF4FD1C5.toInt()

    companion object {
        val THEMES = listOf(
            Deco("theme", "midnight", "미드나잇", 0, 0xFF4FD1C5.toInt()), Deco("theme", "ocean", "오션", 50, 0xFF60A5FA.toInt()),
            Deco("theme", "aurora", "오로라", 80, 0xFFA78BFA.toInt()), Deco("theme", "sunset", "선셋", 120, 0xFFFF8A65.toInt()),
            Deco("theme", "cherry", "체리", 150, 0xFFF472B6.toInt()), Deco("theme", "gold", "골드", 250, 0xFFF5C451.toInt()))
        val FACES = listOf(
            Deco("face", "neutral", "기본", 0), Deco("face", "happy", "방긋", 30), Deco("face", "sparkle", "반짝", 60),
            Deco("face", "heart_eyes", "하트 눈", 90), Deco("face", "cool", "선글라스", 120), Deco("face", "proud", "왕관", 200))
        val ACCS = listOf(
            Deco("acc", "", "없음", 0, glyph = ""), Deco("acc", "ribbon", "리본", 20, glyph = "🎀"), Deco("acc", "flower", "꽃", 40, glyph = "🌸"),
            Deco("acc", "phones", "헤드폰", 70, glyph = "🎧"), Deco("acc", "star", "별", 100, glyph = "⭐"), Deco("acc", "balloon", "풍선", 150, glyph = "🎈"))
        fun all() = THEMES + FACES + ACCS
    }
}

/**
 * 상황별 추천 화면 · 첫 실행(차량 점검 → 운전 숙련도) · 운전 어시스턴트 강도 · 꾸미기(안전운전 포인트) — 댄 11-08
 *  - 상황 판정: 시동(IGNITION_STATE) · 기어(GEAR_SELECTION) · 속도(PERF_VEHICLE_SPEED). 신호가 바뀔 때만 다시 판정(강제 전환은 다음 신호 변화까지 유지)
 *  - 초보: 출발·정차·주차 팁을 말로, 회전 300m 앞 미리 알림, 급정거 부드럽게, 50분이면 쉼표 · 숙련: 꼭 필요한 것만
 *  - 주행 중엔 화면 요소 최소(길 안내 1장) · 말 위주. 꾸미기·설정·온보딩은 정차·주차 때만
 * 시연(adb):
 *   am broadcast -a io.github.somanghao.makecar.hyun.STAGE --es sit ignition|ready|drive|stop|park|off|auto
 *   am broadcast -a io.github.somanghao.makecar.hyun.STAGE --es screen onboard|deco|settings|debug|none  · --es skill novice|expert · --ei points 300 · --ez reset true
 *   (같은 extra 를 am start -n …/.HyunActivity 로 줘도 된다) · 화면 왼쪽 위 상황 칩을 세 번 누르면 디버그 판
 */
class StageFeature : Feature {
    override val id = "stage"
    override fun enabled(car: Boolean) = car
    private lateinit var hub: Hyun
    val m = StageModel()
    private var ui: StageUi? = null
    private var status: CarStatus? = null
    private var pollN = 0

    // 판정
    private var lastKey = ""
    private var inTrip = false
    private var prevGear = 'P'
    private var stillS = 0
    // 이번 주행 기록
    private var tripM = 0.0
    private var tripS = 0
    private var harsh = 0
    private var lastSpeed = 0f
    private var pointM = 0.0
    private var restSaid = false
    private var settled = false
    private var onboardGen = 0
    private var dayKm = 0.0
    private var dayPoints = 0
    // 날씨 · 관심 뉴스
    private var tempC: Int? = null
    private var wcode = -1
    private var rain = 0
    private var tomorrow = ""
    private var news: List<String> = emptyList()
    private var newsTag = "오늘의 헤드라인"
    private var gossipIdx = 0
    private var tipIdx = 0
    private var turnWarned = ""

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { hub.main.post { apply(i) } }
    }

    override fun start(hub: Hyun) {
        this.hub = hub
        val pr = hub.prefs
        m.skill = pr.getString("stage.skill", null)
        m.points = pr.getInt("stage.points", 0)
        m.theme = pr.getString("stage.theme", "midnight")!!; m.face = pr.getString("stage.face", "neutral")!!; m.acc = pr.getString("stage.acc", "")!!
        m.owned = pr.getStringSet("stage.owned", m.owned)!!.toSet() + m.owned
        status = CarStatus(hub.act).also { runCatching { it.start() }; m.status = it }
        ui = StageUi(hub.view, m, ::act).also { hub.view.stage = it }
        val f = IntentFilter(ACTION)
        runCatching { if (Build.VERSION.SDK_INT >= 33) hub.act.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED) else hub.act.registerReceiver(receiver, f) }
            .onFailure { hub.log("stage|receiver|${it.message}") }
        val need = CAR_PERMS.filter { hub.act.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) runCatching { hub.act.requestPermissions(need.toTypedArray(), Hyun.REQ_PERMS) }
        if (!pr.getBoolean("stage.onboarded", false)) { hub.holdHello = true; startOnboarding() }
        enter(Sit.IGNITION, why = "앱 시작")
        fetchWeather(); fetchNews()
    }

    override fun stop() { runCatching { hub.act.unregisterReceiver(receiver) }; status?.stop() }

    override fun onIntent(i: Intent, first: Boolean) { if (i.hasExtra("sit") || i.hasExtra("screen") || i.hasExtra("skill") || i.hasExtra("points") || i.hasExtra("reset") || i.hasExtra("turn")) hub.main.postDelayed({ apply(i) }, if (first) 2500 else 0) }

    /** 시연 extra — 방송·인텐트 공통 */
    private fun apply(i: Intent) {
        if (i.getBooleanExtra("reset", false)) {
            hub.prefs.edit().remove("stage.onboarded").remove("stage.skill").remove("stage.points").remove("stage.owned").remove("stage.theme").remove("stage.face").remove("stage.acc").apply()
            m.skill = null; m.points = 0; m.theme = "midnight"; m.face = "neutral"; m.acc = ""; m.owned = setOf("theme:midnight", "face:neutral", "acc:")
            startOnboarding()
        }
        i.getStringExtra("turn")?.let { t -> m.status?.turn?.set(when (t) { "left" -> 1; "right" -> 2; "hazard" -> 3; else -> 0 }, CarStatus.From.MOCK) }   // 깜빡이 시연(표준 AAOS 는 외부 앱에 안 열림)
        if (i.hasExtra("points")) { m.points = i.getIntExtra("points", m.points); save() }
        i.getStringExtra("skill")?.let { setSkill(it, say = false) }
        i.getStringExtra("sit")?.let { k ->
            if (k == "auto") { m.forced = false; lastKey = ""; hub.log("stage|auto") }
            else Sit.of(k)?.let { s -> m.forced = true; if (s == Sit.DRIVE) startTripIfNeeded(); enter(s, why = "강제") }
        }
        i.getStringExtra("screen")?.let { s ->
            when (s) { "none", "close" -> m.overlay = null; "onboard" -> startOnboarding(); else -> m.overlay = s }
            if (s == "onboard") Unit
        }
        refresh(); hub.render()
    }

    // ======================================================================
    // 상황 판정 — 1초마다
    // ======================================================================
    override fun tick() {
        val on = hub.ignition >= 3        // ACC · ON · START
        val g = hub.gear
        val v = hub.speedMs
        val moving = v > hub.moveMs
        if (prevGear == 'P' && g != 'P') inTrip = false   // 주차 뒤 다시 기어를 넣으면 새 출발
        prevGear = g
        stillS = if (!moving && g != 'P') stillS + 1 else 0
        // 주행 기록 · 안전운전 포인트(100m 마다 1점, 급정거 없이)
        if (moving) {
            inTrip = true
            tripM += v; tripS += 1; pointM += v; dayKm += v / 1000.0
            if (pointM >= 100) { pointM -= 100; m.tripPoints += 1 }
            settled = false
        }
        if (lastSpeed - v > HARSH_MS && lastSpeed > 4f) {
            harsh++; hub.log("stage|harsh|${"%.1f".format(lastSpeed)}→${"%.1f".format(v)}")
            if (m.novice) hub.say("조금 부드럽게 멈춰 볼까요. 브레이크는 미리, 천천히요.", "care", false, 2, tag = "초보·급정거")
        }
        lastSpeed = v
        val key = "$on|$g|$moving|${stillS >= 2}"
        if (key != lastKey) {
            lastKey = key
            if (!m.forced || moving) {
                m.forced = false
                val next = when {
                    !on -> Sit.OFF
                    moving -> Sit.DRIVE
                    g == 'P' -> if (inTrip) Sit.PARK else Sit.IGNITION
                    stillS >= 2 && inTrip -> Sit.STOP
                    inTrip -> m.sit
                    else -> Sit.READY
                }
                if (next == Sit.IGNITION && m.sit == Sit.OFF) inTrip = false
                if (next != m.sit) enter(next, why = "신호 시동=${hub.ignition} 기어=$g 속도=${hub.kmh()}")
            }
        }
        // 초보: 회전 300m 앞 미리 알림 · 50분이면 쉼표
        if (m.sit == Sit.DRIVE && m.novice) {
            val t = hub.view.turns.firstOrNull()
            if (t != null && t.m in 1..320 && turnWarned != "${t.type}|${t.desc}") {
                turnWarned = "${t.type}|${t.desc}"
                lane(t.type)?.let { hub.say("${(t.m + 50) / 100 * 100}미터 앞 $it", "", false, 1, tag = "초보·차로") }
            }
            if (!restSaid && hub.trip.driveS > NOVICE_REST_S) { restSaid = true; hub.say("50분 가까이 달렸어요. 다음 쉼터에서 잠깐 쉬어 가요.", "care", false, 1, tag = "초보·쉼표") }
        }
        if (m.sit == Sit.DRIVE && m.overlay != null && m.overlay != "debug") m.overlay = null   // 주행 중엔 덮개 화면을 닫는다
        status?.let { st -> if (pollN++ % 2 == 0) Thread { st.poll() }.start(); st.appTrip(tripS / 60, (tripM / 1000).toFloat()) }
        hub.view.idleFace = m.face
        refresh()
    }

    private fun lane(type: String) = when (type) {
        "TURN_LEFT", "TURN_SHARP_L", "TURN_UTURN", "TURN_KEEP_L" -> "${if (type == "TURN_UTURN") "유턴" else "좌회전"}이에요. 미리 왼쪽 차로로 가요."
        "TURN_RIGHT", "TURN_SHARP_R", "TURN_KEEP_R" -> "우회전이에요. 미리 오른쪽 차로로 가요."
        else -> null
    }

    private fun startTripIfNeeded() { if (!inTrip) { inTrip = true } }

    /** 상황 진입 — 화면 내용 + (필요하면) 한마디. 숙련 운전자는 말을 줄인다 */
    private fun enter(s: Sit, why: String) {
        val prev = m.sit
        m.sit = s; m.sitAt = SystemClock.elapsedRealtime(); m.lineAtEnter = hub.view.hyunLine
        hub.log("stage|sit|${prev.key}→${s.key}|$why")
        hub.view.faceOverride = null
        when (s) {
            Sit.IGNITION -> { tripM = 0.0; tripS = 0; harsh = 0; m.tripPoints = 0; restSaid = false }
            Sit.DRIVE -> if (prev == Sit.READY || prev == Sit.IGNITION || prev == Sit.PARK) { tripM = 0.0; tripS = 0; harsh = 0; m.tripPoints = 0; pointM = 0.0; restSaid = false }
            Sit.PARK -> settleTrip()
            Sit.OFF -> inTrip = false
            else -> {}
        }
        refresh()
        if (m.overlay == "onboard" || why == "앱 시작") return
        val line = when (s) {
            Sit.IGNITION -> ignitionLine()
            Sit.READY -> if (m.novice) "출발 순서예요. 브레이크를 꾹 밟고, D로 바꾼 다음, 천천히 발을 떼요." else null
            Sit.DRIVE -> if (m.novice) "출발! 앞차와 간격은 넉넉히, 제가 길을 미리미리 말해 줄게요." else null
            Sit.STOP -> gossip().let { g -> if (m.novice && tipIdx++ % 2 == 0) "신호 대기 중엔 브레이크를 계속 꾹. ${g.second}" else g.second }
            Sit.PARK -> parkLine()
            Sit.OFF -> offLine()
        }
        line?.let { hub.say(it, if (s == Sit.PARK) "happy" else "", false, if (s == Sit.STOP) 3 else 2, tag = "상황·${s.key}") }
    }

    private fun settleTrip() {
        if (settled || (tripM < 50 && m.tripPoints == 0)) return
        settled = true
        val bonus = if (harsh == 0 && tripM >= 500) 10 else 0
        val got = m.tripPoints + bonus
        m.tripPoints = got
        m.points += got; dayPoints += got
        save()
        hub.log("stage|trip|${"%.1f".format(tripM / 1000)}km|${tripS / 60}분|급정거 $harsh|+$got → ${m.points}")
    }

    // ======================================================================
    // 첫 실행 — 차량 점검 → "운전이 처음이세요?" → 맞춤 완료
    // ======================================================================
    private fun startOnboarding() {
        m.overlay = "onboard"; m.onboardStep = 0; m.check = emptyList()
        val gen = ++onboardGen
        hub.view.faceOverride = "waving"
        Thread {
            val items = runCatching { CarCheck.read(hub.act) }.getOrElse { hub.log("check|FAIL|${it.message}"); emptyList() }
            hub.main.post {
                m.check = items; m.checkAt = SystemClock.elapsedRealtime()
                hub.log("check|${items.joinToString(" · ") { "${it.label}=${it.value}" }}")
                hub.view.faceOverride = "thinking"
                val e = items.firstOrNull { it.key == "energy" && it.state != CarCheck.State.NA }
                val r = items.firstOrNull { it.key == "range" && it.state != CarCheck.State.NA }
                val sum = listOfNotNull(e?.let { "${it.label} ${it.value}" }, r?.let { "${it.label} ${it.value}" }).joinToString(", ")
                hub.say("안녕하세요, 현비서예요. 차부터 살펴볼게요. " + (if (sum.isNotBlank()) "$sum, 좋아요. " else "") + "타이어 공기압은 계기판에서 한 번 봐 주세요.", "happy", false, 1, tag = "온보딩·점검")
                hub.main.postDelayed({ if (gen == onboardGen && m.overlay == "onboard" && m.onboardStep == 0) askSkill() }, 9000)
            }
        }.start()
    }

    private fun askSkill() {
        m.onboardStep = 1
        hub.view.faceOverride = "confused"
        hub.say("운전이 처음이세요? 처음이면 처음이야, 익숙하면 익숙해 라고 말해 주세요.", "", true, 1, tag = "온보딩·질문")
    }

    fun setSkill(k: String, say: Boolean = true) {
        val s = if (k.startsWith("n") || k == "초보") "novice" else "expert"
        m.skill = s
        hub.prefs.edit().putString("stage.skill", s).apply()
        hub.log("stage|skill|$s")
        if (m.overlay == "onboard") {
            onboardGen++
            m.onboardStep = 2
            hub.view.faceOverride = if (s == "novice") "thumbs_up" else "cool"
            if (!hub.prefs.getBoolean("stage.onboarded", false)) { m.points += WELCOME; save() }
            hub.prefs.edit().putBoolean("stage.onboarded", true).apply()
            hub.say(if (s == "novice") "초보 모드로 맞췄어요. 출발, 차선, 주차까지 하나씩 말로 챙길게요. 환영 포인트 100점도 드렸어요."
                    else "숙련 모드로 맞췄어요. 꼭 필요한 것만 짧게 말할게요. 환영 포인트 100점은 꾸미기에 써 보세요.", "happy", false, 1, tag = "온보딩·완료")
            val gen = onboardGen
            hub.main.postDelayed({ if (gen == onboardGen && m.overlay == "onboard" && m.onboardStep == 2) finishOnboarding() }, 12_000)
        } else if (say) hub.say(if (s == "novice") "초보 모드로 바꿨어요. 더 자주, 천천히 챙길게요." else "숙련 모드로 바꿨어요. 꼭 필요한 말만 할게요.", "", false, 1, reply = true, tag = "숙련도")
        refresh()
    }

    private fun finishOnboarding() {
        m.overlay = null; hub.view.faceOverride = null
        hub.holdHello = false
        enter(m.sit, why = "온보딩 끝")
        hub.render()
    }

    // ======================================================================
    // 화면 행동(StageUi 가 부른다)
    // ======================================================================
    private fun act(a: String) {
        hub.log("stage|tap|$a")
        when {
            a == "onboard:next" -> if (m.onboardStep == 0) askSkill() else finishOnboarding()
            a == "onboard:deco" -> { finishOnboarding(); m.overlay = "deco" }
            a.startsWith("skill:") -> setSkill(a.substringAfter(":"))
            a == "deco" -> if (!movingNow()) m.overlay = "deco" else toast("주행 중엔 꾸미기를 열 수 없어요")
            a == "settings" -> if (!movingNow()) m.overlay = "settings" else toast("정차하면 열 수 있어요")
            a == "debug" -> m.overlay = if (m.overlay == "debug") null else "debug"
            a == "close" -> { m.overlay = null; hub.view.faceOverride = null }
            a == "recheck" -> startOnboarding()
            a.startsWith("tab:") -> m.decoTab = a.substringAfter(":")
            a.startsWith("item:") -> buyOrUse(a.substringAfter(":"))
            a.startsWith("sit:") -> apply(Intent().putExtra("sit", a.substringAfter(":")))
            a == "mic" -> hub.view.onMic()
            a.startsWith("listen:") -> hub.view.onListenMode(a.endsWith("always"))
            a == "card:close" -> hub.view.card = null
        }
        refresh(); hub.render()
    }

    private fun movingNow() = hub.speedMs > hub.moveMs || m.sit == Sit.DRIVE

    private fun buyOrUse(key: String) {
        val d = StageModel.all().firstOrNull { "${it.kind}:${it.id}" == key } ?: return
        if (key !in m.owned) {
            if (m.points < d.price) { toast("포인트가 ${d.price - m.points}점 모자라요 — 안전운전으로 모아요"); return }
            m.points -= d.price; m.owned = m.owned + key
            toast("${d.name} 열었어요! −${d.price}점")
            hub.view.faceOverride = "cheer"; hub.main.postDelayed({ if (hub.view.faceOverride == "cheer") hub.view.faceOverride = null }, 1800)
        }
        when (d.kind) { "theme" -> m.theme = d.id; "face" -> m.face = d.id; "acc" -> m.acc = d.id }
        save()
    }

    private fun toast(s: String) { m.toast = s; m.toastAt = SystemClock.elapsedRealtime() }

    private fun save() = hub.prefs.edit().putInt("stage.points", m.points).putStringSet("stage.owned", m.owned)
        .putString("stage.theme", m.theme).putString("stage.face", m.face).putString("stage.acc", m.acc).apply()

    // ======================================================================
    // 말 명령 — 숙련도 · 꾸미기 · 포인트 · 점검 (온보딩 질문 중엔 넓게 받는다)
    // ======================================================================
    override fun onHeard(text: String): Boolean {
        val t = text.replace(" ", "")
        if (m.overlay == "onboard" && m.onboardStep == 1) {
            when {
                Regex("처음|초보|서툴|잘못|무서|네|응|맞아").containsMatchIn(t) -> { setSkill("novice"); return true }
                Regex("익숙|숙련|오래|베테랑|잘해|아니").containsMatchIn(t) -> { setSkill("expert"); return true }
            }
        }
        when {
            Regex("(나|저)?초보(야|예요|에요|모드|운전)|운전(이)?처음").containsMatchIn(t) -> { setSkill("novice"); return true }
            Regex("운전(이|은)?익숙|숙련모드|베테랑").containsMatchIn(t) -> { setSkill("expert"); return true }
            Regex("꾸미기|꾸며").containsMatchIn(t) -> { act("deco"); if (m.overlay == "deco") hub.say("꾸미기를 열었어요. 포인트는 ${m.points}점이에요.", "happy", false, 1, reply = true, tag = "꾸미기"); return true }
            Regex("포인트(몇|얼마|알려)|내포인트").containsMatchIn(t) -> { hub.say("안전운전 포인트는 ${m.points}점이에요.", "happy", false, 1, reply = true, tag = "포인트"); return true }
            Regex("차량점검|차점검|점검해").containsMatchIn(t) -> { act("recheck"); return true }
        }
        return false
    }

    override fun ctx(c: JSONObject) {
        c.put("situation", m.sit.key)
        m.skill?.let { c.put("driverSkill", it) }
        c.put("safePoints", m.points)
    }

    override fun fallback(ev: String, extra: JSONObject?): String? = if (ev == "hello") ignitionLine() else null

    // ======================================================================
    // 상황별 추천 내용
    // ======================================================================
    private fun refresh() {
        val now = Calendar.getInstance()
        val hhmm = "%02d:%02d".format(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
        m.kicker = "${m.sit.label} · $hhmm"
        m.tip = ""
        m.weatherStickers = if (m.sit == Sit.IGNITION || m.sit == Sit.READY) outfit() else emptyList()
        val name = hub.view.name.ifBlank { "" }
        val who = if (name.isNotBlank()) "${name}님, " else ""
        m.bubble = when (m.sit) {
            Sit.IGNITION -> tempC?.let { "오늘 ${it}°라 " + (if (it <= 8) "목도리 둘렀어요! " else "") + "${drink().first} 한 잔 어때요?" } ?: "오늘도 같이 가요!"
            Sit.READY -> "어디로 갈지 말해 주세요. \"코엑스 가자\"처럼요."
            Sit.DRIVE -> "길은 제가 말로 알려 드릴게요. 앞만 봐요."
            Sit.STOP -> gossip().second
            Sit.PARK -> "수고했어요! 안전운전 포인트 +${m.tripPoints}점"
            Sit.OFF -> "오늘도 고생했어요. 내일 또 만나요."
        }
        when (m.sit) {
            Sit.IGNITION -> {
                m.headline = "${who}${greet()}"
                m.subline = weatherComic()
                m.tiles = listOfNotNull(
                    carTile(),
                    Tile("🌦", "날씨", tempC?.let { "${it}° ${wname()}" } ?: "불러오는 중", if (rain > 0) "비 확률 $rain% · $tomorrow" else tomorrow, "info"),
                    Tile("📰", newsTag, news.firstOrNull() ?: "헤드라인을 가져오는 중", news.getOrNull(1) ?: "", "accent"),
                    Tile("☕", "따뜻한 한 잔", drink().first, drink().second, "warn"),
                )
                if (m.novice) m.tip = "출발 전에 시트 → 미러 → 벨트 순서로 맞춰요. 계기판에 빨간 경고등이 없는지도 한 번."
            }
            Sit.READY -> {
                m.headline = "어디로 갈까요?"
                m.subline = if (m.novice) "출발은 천천히. 목적지를 말해 주면 길을 미리미리 알려 드려요." else "목적지를 말하면 바로 안내할게요. 가는 김에 들를 곳도 챙겼어요."
                val dest = hub.view.dest
                m.tiles = listOf(
                    if (dest != null) Tile("📍", "목적지", dest, hub.view.remain.ifBlank { "경로 계산 중" }, "accent")
                    else Tile("📍", "추천 목적지", recommendDest().first, recommendDest().second, "accent", "추천"),
                    Tile("🛒", "가는 김에", "세탁물 찾기", "가는 길 0.4km · 5분이면 돼요", "good", "예시"),
                    Tile("🎧", "출발 편성", "뉴스 5분 + 잔잔한 음악", "도착까지 맞춰서 틀어 드려요", "info"),
                    carTile(),
                )
                if (m.novice) m.tip = "브레이크 꾹 → D → 주변 확인 → 발을 천천히. 출발할 땐 깜빡이부터 켜요."
            }
            Sit.DRIVE -> {
                navState()
                m.headline = m.navTurn
                m.subline = ""
                m.tiles = emptyList()
            }
            Sit.STOP -> {
                val g = gossip()
                m.headline = "신호 대기 중"
                m.subline = "잠깐 쉬어 가는 동안 동네 이야기 하나"
                navState()
                m.tiles = listOf(Tile("💬", g.first, g.second, "", "accent", "예시"),
                    Tile("🧭", "다음 안내", "${hub.view.dist(m.navTurnM)} 앞 · ${m.navTurn}", "${m.navEta} · ${m.navDest} ${m.navRemain}", "info", if (m.mockRoute) "모의 경로" else ""))
                if (m.novice) m.tip = "정차 중엔 브레이크를 계속 꾹. 앞차가 출발하면 한 박자 쉬고 천천히."
            }
            Sit.PARK -> {
                m.headline = "주차 완료 — 수고했어요"
                m.subline = if (harsh == 0) "급정거 한 번 없이 왔어요. 아주 부드러웠어요." else "급정거가 ${harsh}번 있었어요. 다음엔 조금 더 미리 멈춰 봐요."
                m.tiles = listOf(
                    Tile("🛣", "이번 주행", "%.1fkm · %d분".format(tripM / 1000, max(1, tripS / 60)), "평균 ${if (tripS > 0) (tripM / tripS * 3.6).roundToInt() else 0}km/h", "info"),
                    Tile("⭐", "안전운전 포인트", "+${m.tripPoints}", "모은 포인트 ${m.points}점 · 꾸미기에 써요", "warn", action = "deco"),
                    Tile("☁", "쉼표", "15분 쉬어 가기", "등받이 살짝 · 목 스트레칭 3번", "good"),
                    Tile("🎨", "꾸미기", nextUnlock(), "포인트로 새 테마·표정을 열어요", "accent", action = "deco"),
                )
                if (m.novice) m.tip = "주차 요령: 핸들을 똑바로 풀고 → P → 주차 브레이크. 내리기 전에 사이드미러 접기."
            }
            Sit.OFF -> {
                m.headline = "오늘도 안전운전 끝"
                m.subline = "푹 쉬어요. 내일 아침에 또 만나요."
                m.tiles = listOf(
                    Tile("📊", "하루 정리", "%.1fkm".format(dayKm), "오늘 모은 포인트 +$dayPoints · 총 ${m.points}점", "info"),
                    Tile("👜", "두고 내린 것", "휴대폰 · 지갑 · 뒷좌석", "창문·실내등도 한 번 확인", "warn"),
                    Tile("⏰", "내일", tomorrow.ifBlank { "내일 날씨를 불러오는 중" }, if (rain >= 50) "우산 챙겨요" else "출발 10분 전에 공조 켜 둘까요?", "accent"),
                    Tile("🎨", "꾸미기", nextUnlock(), "모은 포인트 ${m.points}점", "good", action = "deco"),
                )
            }
        }
    }

    private fun greet(): String {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (h) { in 5..10 -> "좋은 아침이에요"; in 11..16 -> "좋은 오후예요"; in 17..21 -> "좋은 저녁이에요"; else -> "늦은 시간이네요, 조심히 가요" }
    }

    private fun carTile(): Tile {
        val e = m.check.firstOrNull { it.key == "energy" }; val r = m.check.firstOrNull { it.key == "range" }
        val soc = hub.feature<CarBatteryFeature>()?.soc
        val v = soc?.let { "$it%" } ?: e?.takeIf { it.state != CarCheck.State.NA }?.value ?: "확인 중"
        return Tile("🔋", "차 상태", "${e?.label ?: "배터리"} $v", r?.takeIf { it.state != CarCheck.State.NA }?.let { "주행 가능 ${it.value}" } ?: "타이어는 계기판에서", if (e?.state == CarCheck.State.WARN) "warn" else "good")
    }

    /** 날씨 옷차림 — 캐릭터 둘레 스티커 */
    private fun outfit(): List<String> {
        val t = tempC ?: return emptyList()
        val out = mutableListOf<String>()
        if (wcode in 51..67 || wcode in 80..82 || rain >= 60) out += "☂️"
        if (wcode in 71..77 || wcode in 85..86) out += "❄️"
        if (t <= 8) out += "🧣"
        if (t >= 26) out += "🕶️"
        out += if (t <= 15) "☕" else if (t >= 25) "🧋" else "🍵"
        return out
    }

    private fun weatherComic(): String {
        val t = tempC ?: return "오늘 날씨를 살펴보는 중이에요."
        val wear = mutableListOf<String>()
        if (t <= 8) wear += "목도리 두르고"
        if (wcode in 51..67 || wcode in 80..82 || rain >= 60) wear += "우산 챙겼어요"
        if (t >= 26) wear += "선글라스 꼈어요"
        val w = if (wear.isEmpty()) "오늘은 가볍게 나왔어요" else wear.joinToString(" ")
        return "밖은 ${t}° ${wname()} — 저는 $w. ${drink().first} 어때요?"
    }

    private fun drink(): Pair<String, String> {
        val t = tempC ?: 12
        return when {
            t <= 5 -> "따끈한 유자차" to "손난로 대신 한 잔 — 가는 길 카페 2곳"
            t <= 15 -> "따뜻한 보리차" to "목이 칼칼한 날엔 따뜻하게"
            t >= 25 -> "시원한 아이스티" to "차 안이 덥기 전에 에어컨 먼저"
            else -> "향긋한 녹차 한 잔" to "출발 전 여유 한 모금"
        }
    }

    private fun wname() = when (wcode) {
        0 -> "맑음"; 1, 2 -> "구름 조금"; 3 -> "흐림"; 45, 48 -> "안개"; in 51..57 -> "이슬비"; in 61..67 -> "비"; in 71..77 -> "눈"
        in 80..82 -> "소나기"; in 85..86 -> "눈 소나기"; in 95..99 -> "천둥번개"; else -> ""
    }

    private fun recommendDest(): Pair<String, String> {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (h) { in 6..10 -> "회사" to "평소 출근 시간이에요 · 약 25분"; in 17..21 -> "집" to "퇴근길 · 약 30분"; else -> "자주 가는 카페" to "지난주 두 번 갔어요" }
    }

    private fun nextUnlock(): String {
        val next = StageModel.all().filter { "${it.kind}:${it.id}" !in m.owned }.minByOrNull { it.price } ?: return "전부 열었어요!"
        return if (m.points >= next.price) "${next.name} 열 수 있어요" else "${next.name}까지 ${next.price - m.points}점"
    }

    private fun ignitionLine(): String {
        val t = tempC
        val base = "${greet()}. " + (t?.let { "밖이 ${it}도라 " + (if (t <= 8) "목도리 둘렀어요. " else "") + "${drink().first} 한 잔 어때요?" } ?: "오늘도 같이 가요.")
        return if (m.novice) "$base 출발 전에 시트랑 미러부터 맞춰요." else base
    }

    private fun parkLine(): String {
        val p = "주차 완료, 수고했어요. 안전운전 포인트 ${m.tripPoints}점 받았어요."
        return if (m.novice) "$p 핸들 똑바로 풀고, P, 주차 브레이크까지 확인해요." else p
    }

    private fun offLine() = "오늘 %.1f킬로미터 달렸어요. 휴대폰이랑 지갑 챙기셨죠? 내일 또 만나요.".format(dayKm)

    /** 신호 대기 수다 — 주변 상권·동네 이야기. 카카오 로컬은 서버 도착 추천에만 열려 있어 지금은 예시 이야기(곧 서버 places 로 바꿀 자리) */
    private fun gossip(): Pair<String, String> {
        val g = GOSSIP[gossipIdx % GOSSIP.size]
        return g
    }

    // ---------- 주행 중 길 안내 — 내비(NaviHelper)가 있으면 그 값, 없으면(표준 에뮬) 모의 경로 ----------
    private fun navState() {
        val v = hub.view
        val t = v.turns.firstOrNull()
        if (v.dest != null && t != null) {
            m.mockRoute = false
            m.navTurnType = t.type; m.navTurnM = t.m; m.navTurn = t.desc.ifBlank { "다음 안내" }
            m.navDest = v.dest ?: ""; m.navRemain = v.remain; m.navEta = v.eta
            return
        }
        m.mockRoute = true
        val total = MOCK.sumOf { it.second }
        var d = (tripM % total).toInt(); var i = 0
        while (i < MOCK.size - 1 && d >= MOCK[i].second) { d -= MOCK[i].second; i++ }
        val (type, seg, desc) = MOCK[i]
        m.navTurnType = type; m.navTurnM = (seg - d).coerceAtLeast(0); m.navTurn = desc
        val left = (total - tripM % total).coerceAtLeast(0.0)
        m.navDest = "코엑스"; m.navRemain = "%.1fkm".format(left / 1000)
        val eta = Calendar.getInstance().apply { add(Calendar.MINUTE, max(1, (left / 8.3 / 60).roundToInt())) }
        m.navEta = "도착 %02d:%02d".format(eta.get(Calendar.HOUR_OF_DAY), eta.get(Calendar.MINUTE))
    }

    // ---------- 날씨(Open-Meteo, 키 없음) · 관심 뉴스(현비서 서버 /hyun/feed + /hyun/taste) ----------
    private fun fetchWeather() = Thread {
        runCatching {
            val la = hub.lat ?: 37.45; val lo = hub.lng ?: 127.13   // 위치를 아직 모르면 성남(서버 날씨 기본과 같은 곳)
            val j = getJson("https://api.open-meteo.com/v1/forecast?latitude=$la&longitude=$lo&current=temperature_2m,weather_code" +
                "&hourly=precipitation_probability&forecast_hours=8&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code&forecast_days=2&timezone=auto")
            val cur = j.getJSONObject("current")
            val pr = j.getJSONObject("hourly").getJSONArray("precipitation_probability")
            val d = j.getJSONObject("daily")
            val tmx = d.getJSONArray("temperature_2m_max").getDouble(1).roundToInt(); val tmn = d.getJSONArray("temperature_2m_min").getDouble(1).roundToInt()
            val trn = d.getJSONArray("precipitation_probability_max").optInt(1, 0)
            hub.main.post {
                tempC = cur.getDouble("temperature_2m").roundToInt(); wcode = cur.optInt("weather_code", -1)
                rain = (0 until pr.length()).maxOfOrNull { pr.optInt(it, 0) } ?: 0
                tomorrow = "내일 $tmn~$tmx°" + if (trn >= 40) " · 비 $trn%" else ""
                hub.log("stage|weather|${tempC}°|code=$wcode|rain=$rain|$tomorrow")
                refresh()
            }
        }.onFailure { hub.log("stage|weather|FAIL|${it.message}") }
    }.start()

    private fun fetchNews() = Thread {
        runCatching {
            val base = hub.link.base
            val likes = runCatching {
                val t = getJson("$base/hyun/taste")
                val a = t.optJSONArray("slots")
                (0 until (a?.length() ?: 0)).flatMap { i -> a!!.getJSONObject(i).optJSONArray("like")?.let { l -> (0 until l.length()).map { l.getString(it) } } ?: emptyList() }
            }.getOrDefault(emptyList())
            val n = getJson("$base/hyun/feed?cat=news&top=10")
            val items = n.optJSONArray("items")
            val titles = (0 until (items?.length() ?: 0)).map { items!!.getJSONObject(it).optString("title") }.filter { it.isNotBlank() }
            val hit = likes.firstOrNull { k -> titles.any { it.contains(k) } }
            hub.main.post {
                news = if (hit != null) titles.filter { it.contains(hit) }.take(2) + titles.filterNot { it.contains(hit) }.take(1) else titles.take(2)
                newsTag = if (hit != null) "관심 뉴스 · $hit" else "오늘의 헤드라인"
                hub.log("stage|news|${newsTag}|${news.size}")
                refresh()
            }
        }.onFailure { hub.log("stage|news|FAIL|${it.message}") }
    }.start()

    private fun getJson(u: String): JSONObject {
        val c = (URL(u).openConnection() as HttpURLConnection).apply { connectTimeout = 5000; readTimeout = 8000 }
        try { return JSONObject(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() }
    }

    override fun render(v: io.github.somanghao.makecar.hyun.ui.HyunView) {
        if (m.sit == Sit.STOP && SystemClock.elapsedRealtime() - m.sitAt > 25_000) { gossipIdx++; m.sitAt = SystemClock.elapsedRealtime() }
    }

    companion object {
        const val ACTION = "io.github.somanghao.makecar.hyun.STAGE"
        private const val HARSH_MS = 3.5f          // 1초에 이만큼(약 12.6km/h) 넘게 줄면 급정거
        private const val NOVICE_REST_S = 50 * 60.0
        private const val WELCOME = 100
        private val CAR_PERMS = listOf("android.car.permission.CAR_ENERGY", "android.car.permission.CAR_SPEED", "android.car.permission.CAR_MILEAGE")
        /** 모의 경로(판교 → 코엑스) — 내비가 없는 표준 에뮬레이터에서 주행 화면 구조를 보이려고 */
        private val MOCK = listOf(
            Triple("TURN_RIGHT", 350, "판교역로 방면 우회전"), Triple("TURN_STRAIGHT", 900, "분당내곡로 직진"),
            Triple("TURN_ENTER_IC", 1200, "경부고속도로 진입"), Triple("TURN_KEEP_L", 4200, "서울 방면 왼쪽 길"),
            Triple("TURN_EXIT_IC", 2600, "양재IC 나가기"), Triple("TURN_LEFT", 1500, "테헤란로 방면 좌회전"), Triple("TURN_ARRIVE", 800, "코엑스 도착"))
        /** 신호 대기 수다(예시) — 짧게, 말로 듣기 좋게 */
        private val GOSSIP = listOf(
            "판교 이야기" to "판교라는 이름은 옛날 이 근처 냇가에 놓인 널빤지 다리, 널다리에서 왔대요.",
            "근처 가게" to "이 길 건너 빵집은 아침 일곱 시 소금빵이 십 분 만에 동난대요. 다음엔 일찍 와 볼까요?",
            "동네 상권" to "요즘 이 동네는 점심마다 샐러드 가게 앞 줄이 제일 길어요. 직장인이 많아서 그렇대요.",
            "숨은 명소" to "조금만 가면 탄천 산책로가 나와요. 해 질 무렵 자전거 타기 좋은 길로 유명해요.",
            "알쓸신잡" to "신호등 빨간불이 맨 위에 있는 건, 눈이 쌓여도 제일 잘 보이게 하려는 거래요.",
            "근처 카페" to "모퉁이 카페는 사장님이 직접 볶은 원두를 써서, 고소한 냄새가 길까지 난대요.",
        )
    }
}
