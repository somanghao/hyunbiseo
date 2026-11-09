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
import io.github.somanghao.makecar.hyun.ui.Concept
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
data class Tile(val icon: String, val label: String, val value: String, val sub: String = "", val tone: String = "accent", val tag: String = "", val action: String? = null,
                val lines: List<Entry> = emptyList())

/** 포인트 내역 한 줄 — 안전 주행(+) · 급정거·과속·깜빡이 없이 회전(−) … */
data class Entry(val icon: String, val why: String, val delta: Int)

/** 웹툰(서버 /hyun/feed?cat=webtoon) · 시세(cat=stock) */
data class Toon(val rank: Int, val title: String, val author: String, val star: Double, val img: String)
data class Quote(val name: String, val price: String, val ratio: Float)

/** 꾸미기 상품 — kind theme(배경 조명 색) · face(기본 표정) · acc(소품 스티커) */
data class Deco(val kind: String, val id: String, val name: String, val price: Int, val color: Int = 0, val glyph: String = "")

/** 캐릭터 꾸미기 소품(assets/chars/acc_<id>.webp, 댄 11-09 소품 시트에서 오림) — 칸(slot)마다 하나. 위치는 캐릭터 그림 한 변 기준 비율 */
data class AccDef(val id: String, val name: String, val slot: String, val price: Int, val parts: List<AccPart>)
/** 소품 조각 — 그림의 일부(crop, 0~1 비율)를 캐릭터 기준 위치에. back = 헬멧 뒤에 그림(머리띠 끝·반대쪽 헤드폰 등) */
data class AccPart(val w: Float, val dx: Float, val dy: Float, val rot: Float, val back: Boolean, val cx0: Float, val cy0: Float, val cx1: Float, val cy1: Float)

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
    var concept = "pleos"; var face = "neutral"; var acc = ""
    var decor: Set<String> = emptySet()       // 방에 놓은 소품(꾸미기)
    val equipped = linkedMapOf<String, String>()   // 칸 → 소품 id(머리 · 눈 · 얼굴 · 목 · 머리핀 · 효과)
    var heroIdx = 0                           // 지금 크게 보이는 이야기
    var heroAt = SystemClock.elapsedRealtime()
    var carEvent: Pair<String, String>? = null   // 주행 중 한 줄 알림(아이콘 이름, 글) — 바뀔 때만 잠깐
    var carEventAt = 0L
    var reactFace: String? = null             // 이야기가 바뀔 때 캐릭터가 먼저 짓는 표정(1.2초)
    var reactAt = 0L
    var unlockAt = 0L                         // 테마 해금 문 열림 연출 시작
    var previewAt = 0L                        // 잠긴 테마 미리보기 시작(10초)
    // 다른 화면(브리핑 · 할 일 · 내 차 · 기록)이 읽는 것
    var newsList: List<String> = emptyList(); var newsSource = ""
    var weatherLine = ""; var weatherShort = ""
    var todos: List<String> = listOf("세탁물 찾기 · 가는 길 0.4km", "엄마 생신 선물 고르기 · 백화점 경유", "다음 주 정비 예약 · 블루핸즈", "장보기 — 우유 · 계란")
    var weekPoints = 0; val weekGoal = 300
    var newsImgs: Map<String, String> = emptyMap()   // 제목 → 기사 사진(연합뉴스 RSS media:content)
    var wcode = -1; var tempNow: Int? = null
    val raining get() = status?.wiper?.v == true || wcode in 51..67 || wcode in 80..82
    val snowing get() = wcode in 71..77 || wcode in 85..86
    var dayStartAt = 0L; var stopsToday = 0; var dayKmShown = 0.0
    var todayPoints = 0
    var preview: String? = null               // 상점에서 미리보기 중인 컨셉
    var owned: Set<String> = setOf("concept:pleos", "face:neutral", "acc:")
    var decoTab = "concept"
    val ledger = mutableListOf<Entry>()       // 이번 주행 포인트 내역
    val dayLedger = mutableListOf<Entry>()    // 오늘
    var status: io.github.somanghao.makecar.hyun.drive.CarStatus? = null
    var toast = ""; var toastAt = 0L
    // 주행 중 길 안내(내비가 없으면 모의 경로)
    var mockRoute = false
    var navTurn = ""; var navTurnType = ""; var navTurnM = 0; var navDest = ""; var navRemain = ""; var navEta = ""
    var navNextType = ""; var navNextM = 0; var navNext = ""   // 그다음 회전
    var navMin = 0                            // 남은 시간(분)
    var simNav = false                        // 모의 경로 안내(SIM nav on) — 내비 목적지가 없을 때 경로 안내 시연
    var navActive = false                     // 경로 안내 중(내비 목적지 또는 모의) → 모든 화면 위 상시 내비 띠
    // 화면 — 상황 화면(기본) 위에 취미 레이아웃(view) 하나. docs/hyun-content-plan.md
    var view: String? = null                  // news · nav · webtoon · music · sports · stock · weather · local · schedule
    var viewForced = false                    // 허브·SIM 미리보기 — 허용 규칙을 무시하고 "미리보기" 표시
    var viewAt = 0L
    var story: Tile? = null                   // 2분 안에 생긴 신호 반응 이야기(상황 화면 맨 위 한 줄)
    // 시동 화면 맨 위 '어디로 갈까요?' — 지금 목적지(내비) · 이 시간에 자주 가는 곳(지난 주행 기록) · 없으면 말로 등록하라는 안내
    var goName = ""; var goSub = ""; var goTag = ""
    // 취미 데이터 — 서버 정보 통로(/hyun/feed) · Open-Meteo · 연합뉴스 RSS
    var newsTimes: Map<String, String> = emptyMap()
    var toons: List<Toon> = emptyList(); var toonSource = ""; var toonPage = 0
    var indices: List<Quote> = emptyList(); var stocks: List<Quote> = emptyList(); var stockAsof = ""
    var hourly: List<Triple<String, Int, Int>> = emptyList()   // (시각, 기온, 비 확률)
    var todayMin: Int? = null; var todayMax: Int? = null; var rainMax = 0; var tomorrowLine = ""
    // 이번 주행 요약(주차 화면)
    var tripKm = 0.0; var tripMin = 0; var harshN = 0; var cruiseMin = 0; var avgKmh = 0
    val novice get() = skill == "novice"
    var night = false                         // 시스템 밤 모드(UiModeManager) — 화면이 매 프레임 채운다
    var nightOverride: Boolean? = null        // 시연 --es night on|off
    fun look(): Concept = Concept.of(preview ?: concept, nightOverride ?: night)
    fun accent(): Int = look().accent

    companion object {
        val CONCEPTS get() = Concept.ALL.map { Deco("concept", it.id, it.name, it.price) }
        val FACES = listOf(
            Deco("face", "neutral", "기본", 0), Deco("face", "happy", "방긋", 30), Deco("face", "sparkle", "반짝", 60),
            Deco("face", "heart_eyes", "하트 눈", 90), Deco("face", "cool", "선글라스", 120), Deco("face", "proud", "왕관", 200))
        /** 방 소품 — 사면 캐릭터 방에 놓인다(놓기/치우기) */
        val DECOR = listOf(
            Deco("decor", "plant", "화분", 30, glyph = "🪴"), Deco("decor", "balloon", "풍선", 40, glyph = "🎈"), Deco("decor", "bear", "곰인형", 60, glyph = "🧸"),
            Deco("decor", "frame", "액자", 80, glyph = "🖼️"), Deco("decor", "cat", "고양이", 120, glyph = "🐈"), Deco("decor", "lamp", "무드등", 150, glyph = "🏮"))
        val ACC_DEFS = listOf(
            AccDef("cat_ears", "고양이 귀", "head", 120, listOf(AccPart(0.86f, 0.02f, -0.29f, 0f, true, 0f, 0f, 1f, 1f))),
            AccDef("bunny_ears", "토끼 귀", "head", 120, listOf(AccPart(0.8f, 0.02f, -0.36f, 0f, true, 0f, 0f, 1f, 1f))),
            AccDef("bear_ears", "곰 귀", "head", 120, listOf(AccPart(0.86f, 0.02f, -0.29f, 0f, true, 0f, 0f, 1f, 1f))),
            AccDef("devil_horns", "악마 뿔", "head", 120, listOf(AccPart(0.86f, 0.02f, -0.3f, 0f, true, 0f, 0f, 1f, 1f))),
            AccDef("halo", "천사 링", "head", 200, listOf(AccPart(0.56f, 0.04f, -0.52f, 0f, false, 0f, 0f, 1f, 1f))),
            AccDef("crown", "왕관", "head", 250, listOf(AccPart(0.36f, 0.04f, -0.46f, -5f, false, 0f, 0f, 1f, 1f))),
            AccDef("witch_hat", "마녀 모자", "head", 120, listOf(AccPart(0.92f, 0.03f, -0.44f, -4f, false, 0f, 0f, 1f, 1f))),
            AccDef("santa_hat", "산타 모자", "head", 150, listOf(AccPart(0.84f, 0.05f, -0.4f, -4f, false, 0f, 0f, 1f, 1f))),
            AccDef("beret", "베레모", "head", 120, listOf(AccPart(0.76f, 0.05f, -0.36f, -6f, false, 0f, 0f, 1f, 1f))),
            AccDef("maid_frill", "메이드 머리띠", "head", 120, listOf(AccPart(0.86f, 0.02f, -0.3f, 0f, true, 0f, 0f, 1f, 1f))),
            AccDef("deerstalker", "탐정 모자", "head", 120, listOf(AccPart(0.84f, 0.04f, -0.37f, 0f, false, 0f, 0f, 1f, 1f))),
            AccDef("headphones", "헤드폰", "head", 180, listOf(AccPart(1.0f, 0.03f, -0.25f, 0f, true, 0.15f, 0.0f, 0.94f, 0.45f), AccPart(0.26f, 0.46f, 0.08f, 0f, true, 0.55f, 0.4f, 1.0f, 1.0f), AccPart(0.26f, -0.42f, 0.08f, 0f, false, 0.0f, 0.4f, 0.43f, 1.0f))),
            AccDef("sunglasses", "선글라스", "eyes", 60, listOf(AccPart(0.62f, 0.07f, 0.085f, 0f, false, 0f, 0f, 0.89f, 1f))),
            AccDef("pixel_glasses", "픽셀 안경", "eyes", 60, listOf(AccPart(0.66f, 0.07f, 0.085f, 0f, false, 0f, 0f, 1f, 1f))),
            AccDef("heart_glasses", "하트 안경", "eyes", 60, listOf(AccPart(0.6f, 0.07f, 0.085f, 0f, false, 0f, 0f, 1f, 1f))),
            AccDef("star_glasses", "별 안경", "eyes", 60, listOf(AccPart(0.62f, 0.07f, 0.085f, 0f, false, 0f, 0f, 0.96f, 1f))),
            AccDef("pixel_glasses2", "픽셀 안경 2", "eyes", 60, listOf(AccPart(0.62f, 0.07f, 0.07f, 0f, false, 0f, 0f, 0.82f, 1f))),
            AccDef("glasses_3d", "3D 안경", "eyes", 60, listOf(AccPart(0.62f, 0.07f, 0.105f, 0f, false, 0f, 0f, 0.78f, 1f))),
            AccDef("bandage_heart", "하트 밴드", "face", 30, listOf(AccPart(0.22f, -0.2f, -0.27f, -25f, false, 0f, 0f, 1f, 1f))),
            AccDef("bandage_cross", "십자 밴드", "face", 30, listOf(AccPart(0.18f, 0.38f, -0.18f, 0f, false, 0f, 0f, 1f, 1f))),
            AccDef("mask", "마스크", "face", 30, listOf(AccPart(0.44f, 0.1f, 0.17f, 0f, false, 0f, 0f, 1f, 1f))),
            AccDef("scarf", "체크 목도리", "neck", 70, listOf(AccPart(0.62f, 0.06f, 0.43f, 0f, false, 0f, 0f, 1f, 1f))),
            AccDef("bow_tie", "나비넥타이", "neck", 70, listOf(AccPart(0.3f, 0.07f, 0.42f, 0f, false, 0f, 0f, 1f, 1f))),
            AccDef("necktie", "넥타이", "neck", 70, listOf(AccPart(0.17f, 0.07f, 0.52f, 0f, true, 0f, 0f, 1f, 1f))),
            AccDef("ribbon_blue", "파란 리본", "hair", 40, listOf(AccPart(0.22f, -0.31f, -0.27f, -15f, false, 0f, 0f, 1f, 1f))),
            AccDef("ribbon_pink", "분홍 리본", "hair", 40, listOf(AccPart(0.22f, -0.31f, -0.27f, -15f, false, 0f, 0f, 1f, 1f))),
            AccDef("star_clip", "별 핀", "hair", 40, listOf(AccPart(0.22f, 0.33f, -0.22f, 25f, false, 0f, 0f, 1f, 1f))),
            AccDef("cherry_blossom", "벚꽃", "hair", 40, listOf(AccPart(0.24f, -0.31f, -0.25f, 0f, false, 0f, 0f, 1f, 1f))),
            AccDef("blue_flame", "푸른 불꽃", "effect", 180, listOf(AccPart(0.24f, 0.62f, -0.28f, 0f, false, 0f, 0f, 1f, 1f))),
            AccDef("hearts", "하트", "effect", 150, listOf(AccPart(0.28f, 0.62f, -0.3f, 0f, false, 0f, 0f, 1f, 1f))),
        )
        val SLOT_NAMES = mapOf("head" to "머리", "eyes" to "눈", "face" to "얼굴", "neck" to "목", "hair" to "머리핀", "effect" to "효과")
        fun all() = CONCEPTS + FACES + DECOR
        /** 취미 레이아웃 9 — 키 → 이름 */
        val CATS = listOf("news" to "뉴스", "nav" to "내비게이션", "webtoon" to "웹툰", "music" to "음악·팟캐스트", "sports" to "스포츠",
            "stock" to "주식", "weather" to "날씨·차림", "local" to "동네 이야기", "schedule" to "일정·할 일")
        private val STILL = setOf(Sit.IGNITION, Sit.READY, Sit.PARK, Sit.OFF)
        /** 상황별 허용(전체 레이아웃) — 주행 중엔 내비만(나머지는 음성), 신호대기는 내비·동네 이야기 한 장, 웹툰은 주차 전용 */
        val ALLOWED: Map<String, Set<Sit>> = mapOf(
            "nav" to Sit.values().toSet(), "news" to STILL, "webtoon" to setOf(Sit.PARK), "music" to STILL, "sports" to STILL,
            "stock" to STILL, "weather" to STILL, "local" to STILL + Sit.STOP, "schedule" to STILL)
        fun allowed(cat: String, s: Sit) = ALLOWED[cat]?.contains(s) == true
        fun catName(k: String) = CATS.firstOrNull { it.first == k }?.second ?: k
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
    private var tripStartWall = 0L            // 이번 주행 출발 시각(벽시계) — 자주 가는 곳 학습
    private var tripDest: String? = null      // 이번 주행에 내비가 읽은 목적지(도착하면 비워지기 전 값)
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
        override fun onReceive(c: Context, i: Intent) { hub.main.post { if (i.action == SIM_ACTION) simIntent(i) else apply(i) } }
    }

    override fun start(hub: Hyun) {
        this.hub = hub
        val pr = hub.prefs
        m.skill = pr.getString("stage.skill", null)
        m.points = pr.getInt("stage.points", 0)
        m.concept = pr.getString("stage.concept", "pleos")!!; m.face = pr.getString("stage.face", "neutral")!!; m.acc = pr.getString("stage.acc", "")!!
        m.owned = pr.getStringSet("stage.owned", m.owned)!!.toSet() + m.owned
        m.decor = pr.getStringSet("stage.decor", emptySet())!!.toSet()
        pr.getString("stage.equip", "")!!.split(',').filter { ':' in it }.forEach { m.equipped[it.substringBefore(':')] = it.substringAfter(':') }
        if (pr.getInt("stage.weekNo", -1) == Calendar.getInstance().get(Calendar.WEEK_OF_YEAR)) m.weekPoints = pr.getInt("stage.week", 0)
        status = CarStatus(hub.act).also { runCatching { it.start() }; m.status = it }
        ui = StageUi(hub.view, m, ::act).also { hub.view.stage = it }
        val f = IntentFilter(ACTION).apply { addAction(SIM_ACTION) }
        runCatching { if (Build.VERSION.SDK_INT >= 33) hub.act.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED) else hub.act.registerReceiver(receiver, f) }
            .onFailure { hub.log("stage|receiver|${it.message}") }
        val need = CAR_PERMS.filter { hub.act.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) runCatching { hub.act.requestPermissions(need.toTypedArray(), Hyun.REQ_PERMS) }
        if (!pr.getBoolean("stage.onboarded", false)) { hub.holdHello = true; startOnboarding() }
        enter(Sit.IGNITION, why = "앱 시작")
        fetchWeather(); fetchNews(); fetchRss(); fetchFeeds()
    }

    override fun stop() { runCatching { hub.act.unregisterReceiver(receiver) }; status?.stop() }

    override fun onIntent(i: Intent, first: Boolean) { if (i.hasExtra("sit") || i.hasExtra("screen") || i.hasExtra("skill") || i.hasExtra("points") || i.hasExtra("reset") || i.hasExtra("turn") || i.hasExtra("steer") || i.hasExtra("cruise") || i.hasExtra("concept") || i.hasExtra("preview") || i.hasExtra("night") || i.hasExtra("sim") || i.hasExtra("hero") || i.hasExtra("equip") || i.hasExtra("tab") || i.hasExtra("nav")) hub.main.postDelayed({ apply(i) }, if (first) 2500 else 0) }

    /**
     * 허브 주행 상태 조작용 — adb shell am broadcast -a io.github.somanghao.makecar.hyun.SIM --es wiper on --es passenger on --ei soc 18 --es sit drive
     * 키: wiper·door·window·passenger·kid·belt·lights·lock(on/off) · cruise(속도 또는 off) · turn(left/right/hazard/off) · cabin(°C) · soc(%) · range(km) · mode(ECO/NORMAL/SPORT) · steer(°) · sit(ignition…off/auto) · reset(true)
     */
    private fun simIntent(i: Intent) {
        val b = i.extras ?: return
        val parts = mutableListOf<String>()
        if (b.get("reset")?.toString() == "true") parts += "reset"
        for (k in SIM_KEYS) b.get(k)?.let { parts += "$k=${it.toString().lowercase().let { v -> if (k == "mode") v.uppercase() else v }}" }
        if (parts.isNotEmpty()) hub.log("stage|SIM|${m.status?.sim(parts.joinToString(","))}")
        b.getString("sit")?.let { apply(Intent().putExtra("sit", it)) }
        b.get("nav")?.toString()?.let { setNav(it == "on" || it == "true" || it == "1") }
        b.getString("skin")?.let { setSkin(it) }
        b.getString("screen")?.let { setScreen(it) }
        b.getString("golog")?.let { if (it == "demo") seedGoLog() else if (it == "clear") hub.prefs.edit().remove(GO_LOG).apply() }   // 시연: 자주 가는 곳 기록
        refresh(); hub.render()
    }

    /** 시연 extra — 방송·인텐트 공통 */
    private fun apply(i: Intent) {
        if (i.getBooleanExtra("reset", false)) {
            hub.prefs.edit().remove("stage.onboarded").remove("stage.skill").remove("stage.points").remove("stage.owned").remove("stage.concept").remove("stage.face").remove("stage.acc").remove("stage.decor").remove("stage.equip").apply()
            m.skill = null; m.points = 0; m.concept = "pleos"; m.preview = null; m.face = "neutral"; m.acc = ""; m.owned = setOf("concept:pleos", "face:neutral", "acc:"); m.ledger.clear(); m.dayLedger.clear(); m.decor = emptySet(); m.equipped.clear()
            startOnboarding()
        }
        i.getStringExtra("turn")?.let { t -> m.status?.sim("turn=$t") }   // 깜빡이 시연(표준 AAOS 는 외부 앱에 안 열림)
        i.getStringExtra("concept")?.let { c -> if (Concept.ALL.any { it.id == c }) { m.concept = c; m.owned = m.owned + "concept:$c"; save() } }
        i.getStringExtra("night")?.let { m.nightOverride = when (it) { "on" -> true; "off" -> false; else -> null } }
        i.getStringExtra("sim")?.let { sp -> val done = m.status?.sim(sp).orEmpty(); hub.log("stage|sim|$done") }
        i.getStringExtra("preview")?.let { m.preview = it.takeIf { p -> Concept.ALL.any { c -> c.id == p } }; m.previewAt = SystemClock.elapsedRealtime() }
        i.getStringExtra("tab")?.let { m.decoTab = it }   // 시연: 상점 탭(concept · decor · face)
        i.getStringExtra("equip")?.let { spec ->   // 시연: --es equip "cat_ears,sunglasses" (보유+착용) · "none" 이면 모두 벗기
            if (spec == "none") m.equipped.clear()
            else spec.split(',').map { it.trim() }.forEach { id -> StageModel.ACC_DEFS.firstOrNull { it.id == id }?.let { d -> m.owned = m.owned + "acc:${d.id}"; m.equipped[d.slot] = d.id } }
            save()
        }
        if (i.hasExtra("hero")) { m.heroIdx = i.getIntExtra("hero", 0); m.heroAt = SystemClock.elapsedRealtime() }
        if (i.hasExtra("points")) { m.points = i.getIntExtra("points", m.points); save() }
        i.getStringExtra("skill")?.let { setSkill(it, say = false) }
        i.getStringExtra("sit")?.let { k ->
            if (k == "auto") { m.forced = false; lastKey = ""; hub.log("stage|auto") }
            else Sit.of(k)?.let { s -> m.forced = true; if (s == Sit.DRIVE) startTripIfNeeded(); enter(s, why = "강제") }
        }
        i.getStringExtra("nav")?.let { setNav(it == "on" || it == "true") }
        if (i.action == ACTION) i.getStringExtra("skin")?.let { setSkin(it) }
        i.getStringExtra("screen")?.let { s ->
            if (s == "auto" || Sit.values().any { it.key == s } || StageModel.CATS.any { it.first == s }) { setScreen(s); refresh(); hub.render(); return }
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
            tripM += v; tripS += 1; pointM += v; dayKm += v / 1000.0; m.dayKmShown = dayKm
            if (m.dayStartAt == 0L) m.dayStartAt = System.currentTimeMillis()
            if (pointM >= 100) { pointM -= 100; m.tripPoints += 1; addPoints(1) }
            settled = false
        }
        if (lastSpeed - v > HARSH_MS && lastSpeed > 4f) {
            harsh++; hub.log("stage|harsh|${"%.1f".format(lastSpeed)}→${"%.1f".format(v)}")
            penalty("⚠️", "급정거", if (m.novice) 2 else 5)
            if (m.novice) hub.say("조금 부드럽게 멈춰 볼까요. 브레이크는 미리, 천천히요.", "care", false, 2, tag = "초보·급정거")
        }
        lastSpeed = v
        if (moving) scoreDrive(v)
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
        m.view?.let { vw -> if (!m.viewForced && !StageModel.allowed(vw, m.sit)) { m.view = null; hub.log("stage|view|$vw 닫음(${m.sit.key})") } }   // 허용 규칙(미리보기는 예외)
        status?.let { st -> if (pollN++ % 2 == 0) Thread { st.poll() }.start(); st.appTrip(tripS / 60, (tripM / 1000).toFloat()) }
        hub.view.idleFace = if (m.face != "neutral") m.face else m.look().idleFace
        react()
        // 이야기 넘기기 — 정차·주차 땐 14초마다 다음 이야기(주행 중엔 넘기지 않음)
        if (m.sit != Sit.DRIVE && m.overlay == null && m.tiles.size > 1 && SystemClock.elapsedRealtime() - m.heroAt > 16_000) { m.heroIdx = (m.heroIdx + 1) % m.tiles.size; m.heroAt = SystemClock.elapsedRealtime(); m.reactAt = m.heroAt; m.reactFace = "happy" }
        // 잠긴 테마 미리보기는 10초
        m.preview?.let { pv -> if ("concept:$pv" !in m.owned && SystemClock.elapsedRealtime() - m.previewAt > 10_000) { m.preview = null; toast("미리보기가 끝났어요 — 별을 모아 열어 보세요") } }
        refresh()
    }

    // ---------- 안전운전 점수 — 거리(+) · 급정거(−) · 과속(−, 모의 경로 제한속도) · 깜빡이 없이 회전(−)/켜고 회전(+) · 크루즈(+) · 쉼표 없이 오래(−) ----------
    private var overS = 0; private var overAt = 0L
    private var cruiseS = 0; private var cruiseGiven = false
    private var signalAt = 0L; private var turnAt = 0L
    private var longGiven = false
    private var penalties = 0

    private fun scoreDrive(v: Float) {
        val now = SystemClock.elapsedRealtime()
        val st = m.status
        // 과속 — 제한속도를 아는 구간만(지금은 모의 경로 구간 제한, 실차는 내비 제한속도로 바꿀 자리)
        val limit = if (m.mockRoute || hub.view.dest == null) mockLimit() else 0
        val kmh = v * 3.6f
        if (limit > 0 && kmh > limit + 10) overS++ else overS = 0
        if (overS >= 8 && now - overAt > 60_000) {
            overAt = now; overS = 0
            penalty("🚨", "과속(${limit}km 구간)", if (m.novice) 1 else 3)
            if (m.novice) hub.say("조금 천천히 가요. 여기는 ${limit}킬로 구간이에요.", "care", false, 2, tag = "초보·과속")
        }
        // 깜빡이 — 조향각(Pleos SteeringWheel)이 크게 꺾일 때 직전 5초 안에 깜빡이를 켰는지
        val turnOn = (st?.turn?.v ?: 0) != 0
        if (turnOn) signalAt = now
        val steer = st?.steer?.v
        if (steer != null && kotlin.math.abs(steer) > 90 && v > 4f && now - turnAt > 20_000) {
            turnAt = now
            if (now - signalAt < 5_000) award("💡", "깜빡이 켜고 회전", 1) else penalty("↪️", "깜빡이 없이 회전", if (m.novice) 1 else 2)
        }
        // 크루즈 — 일정하게 1분 쓰면 +5(주행당 한 번)
        if (st?.cruise?.v == "작동") cruiseS++
        if (!cruiseGiven && cruiseS >= 60) { cruiseGiven = true; award("◎", "크루즈로 일정하게", 5) }
        // 쉼표 없이 오래 — 초보 90분 · 숙련 120분(한 번)
        val longS = if (m.novice) 90 * 60.0 else 120 * 60.0
        if (!longGiven && hub.trip.driveS > longS) {
            longGiven = true; penalty("☕", "쉼표 없이 ${(longS / 60).toInt()}분", if (m.novice) 2 else 5)
            hub.say("오래 달렸어요. 다음 쉼터에서 꼭 쉬어 가요.", "care", false, 1, tag = "쉼표")
        }
    }

    private fun mockLimit(): Int {
        val total = MOCK.sumOf { it.second }
        var d = (tripM % total).toInt(); var i = 0
        while (i < MOCK.size - 1 && d >= MOCK[i].second) { d -= MOCK[i].second; i++ }
        return LIMITS.getOrElse(i) { 60 }
    }

    private fun addPoints(n: Int) {
        m.points = (m.points + n).coerceAtLeast(0)
        m.todayPoints += n; m.weekPoints = (m.weekPoints + n).coerceAtLeast(0)
        hub.prefs.edit().putInt("stage.week", m.weekPoints).putInt("stage.weekNo", Calendar.getInstance().get(Calendar.WEEK_OF_YEAR)).apply()
    }
    private fun award(icon: String, why: String, n: Int) { m.ledger += Entry(icon, why, n); addPoints(n); hub.log("stage|point|+$n|$why"); save() }
    private fun penalty(icon: String, why: String, n: Int) { penalties++; m.ledger += Entry(icon, why, -n); addPoints(-n); hub.log("stage|point|-$n|$why"); save() }

    private fun lane(type: String) = when (type) {
        "TURN_LEFT", "TURN_SHARP_L", "TURN_UTURN", "TURN_KEEP_L" -> "${if (type == "TURN_UTURN") "유턴" else "좌회전"}이에요. 미리 왼쪽 차로로 가요."
        "TURN_RIGHT", "TURN_SHARP_R", "TURN_KEEP_R" -> "우회전이에요. 미리 오른쪽 차로로 가요."
        else -> null
    }

    private fun startTripIfNeeded() { if (!inTrip) { inTrip = true } }

    /** 스킨(업스트림 SkinFeature) — next 면 다음 스킨, 아니면 그 id */
    private fun setSkin(id: String) {
        if (id == "next") hub.view.onSkin() else hub.feature<SkinFeature>()?.onIntent(Intent().putExtra("skin", id), false)
        hub.log("stage|skin|$id→${hub.view.skin.id}")
    }

    /** 화면 강제(허브·SIM 미리보기) — 상황 6 · 취미 9 · auto(자동 흐름으로) */
    fun setScreen(k: String) {
        hub.log("stage|screen|$k")
        m.viewAt = SystemClock.elapsedRealtime()
        when {
            k == "auto" -> { m.view = null; m.viewForced = false; if (m.forced) { m.forced = false; lastKey = "" }; m.overlay = m.overlay.takeIf { it == "onboard" } }
            Sit.values().any { it.key == k } -> { val s = Sit.values().first { it.key == k }; m.view = null; m.viewForced = true; m.overlay = null; m.forced = true; if (s == Sit.DRIVE) startTripIfNeeded(); enter(s, why = "미리보기") }
            StageModel.CATS.any { it.first == k } -> { m.view = k; m.viewForced = true; m.overlay = null }
        }
    }

    /** 모의 경로 안내 켜기/끄기 — 켜면 첫 회전(판교역로 우회전 350m)부터 */
    fun setNav(on: Boolean) {
        if (on && !m.simNav) navBase = tripM
        m.simNav = on
        if (!on && m.view == "nav" && !m.viewForced && hub.view.dest == null) m.view = null
        hub.log("stage|nav|${if (on) "on" else "off"}")
        refresh()
    }
    private var navBase = 0.0

    /** 상황 진입 — 화면 내용 + (필요하면) 한마디. 숙련 운전자는 말을 줄인다 */
    private fun enter(s: Sit, why: String) {
        val prev = m.sit
        m.sit = s; m.sitAt = SystemClock.elapsedRealtime(); m.lineAtEnter = hub.view.hyunLine
        m.heroIdx = 0; m.heroAt = m.sitAt
        m.reactFace = when (s) { Sit.IGNITION -> "waving"; Sit.READY -> "happy"; Sit.DRIVE -> null; Sit.STOP -> "humming"; Sit.PARK -> "cheer"; Sit.OFF -> "tired" }; m.reactAt = m.sitAt
        hub.log("stage|sit|${prev.key}→${s.key}|$why")
        hub.view.faceOverride = null
        when (s) {
            Sit.IGNITION -> newTrip()
            Sit.DRIVE -> if (prev == Sit.READY || prev == Sit.IGNITION || prev == Sit.PARK) newTrip()
            Sit.PARK -> settleTrip()
            Sit.OFF -> inTrip = false
            Sit.STOP -> m.stopsToday++
            else -> {}
        }
        refresh()
        if (m.overlay == "onboard" || why == "앱 시작" || why == "미리보기") return
        val line = when (s) {
            Sit.IGNITION -> ignitionLine()
            Sit.READY -> if (m.novice) "출발 순서예요. 브레이크를 꾹 밟고, D로 바꾼 다음, 천천히 발을 떼요." else null
            Sit.DRIVE -> if (m.novice) "출발. 앞차와 간격은 넉넉히, 제가 길을 미리미리 말해 줄게요." else null
            Sit.STOP -> gossip().let { g -> if (m.novice && tipIdx++ % 2 == 0) "신호 대기 중엔 브레이크를 계속 꾹. ${g.second}" else g.second }
            Sit.PARK -> parkLine()
            Sit.OFF -> offLine()
        }
        // 시동: 브리핑 영역을 말로 고르게 — 듣기 켜기
        line?.let { hub.say(it, if (s == Sit.PARK) "happy" else "", s == Sit.IGNITION, if (s == Sit.STOP) 3 else 2, tag = "상황·${s.key}") }
    }

    private fun newTrip() {
        tripStartWall = System.currentTimeMillis(); tripDest = hub.view.dest
        tripM = 0.0; tripS = 0; harsh = 0; m.tripPoints = 0; pointM = 0.0; restSaid = false
        m.ledger.clear(); cruiseS = 0; cruiseGiven = false; longGiven = false; penalties = 0; overS = 0; settled = false
    }

    private fun settleTrip() {
        if (settled || (tripM < 50 && m.ledger.isEmpty())) return
        settled = true
        if (m.tripPoints > 0) m.ledger.add(0, Entry("🛣", "안전 주행 %.1fkm".format(tripM / 1000), m.tripPoints))
        if (penalties == 0 && tripM >= 500) award("🏁", "무사고 완주 보너스", 10)
        val got = m.ledger.sumOf { it.delta }
        m.tripPoints = got
        dayPoints += got; m.dayLedger += m.ledger
        save()
        recordGo()
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
            a.startsWith("screen:") -> a.substringAfter(":").let { sc -> m.overlay = if (m.overlay == sc || movingNow()) null else sc }
            a == "close" -> { m.overlay = null; m.preview = null; hub.view.faceOverride = null }
            a == "recheck" -> startOnboarding()
            a.startsWith("tab:") -> m.decoTab = a.substringAfter(":")
            a.startsWith("item:") -> buyOrUse(a.substringAfter(":"))
            a.startsWith("preview:") -> a.substringAfter(":").let { id -> m.preview = if (id == m.concept) null else id; m.previewAt = SystemClock.elapsedRealtime() }
            a.startsWith("buy:") -> buyOrUse("concept:" + a.substringAfter(":"))
            a.startsWith("sit:") -> apply(Intent().putExtra("sit", a.substringAfter(":")))
            a == "mic" -> hub.view.onMic()
            a.startsWith("hero:") -> { m.heroIdx = a.substringAfter(":").toInt(); m.heroAt = SystemClock.elapsedRealtime() + 20_000; m.reactAt = SystemClock.elapsedRealtime(); m.reactFace = "happy" }
            a.startsWith("say:") -> hub.heard(a.substringAfter(":"))
            a.startsWith("sim:") -> { m.status?.sim(a.substringAfter(":")) }
            a.startsWith("acc:") -> wearAcc(a.substringAfter(":"))
            a.startsWith("place:") -> a.substringAfter(":").let { id -> m.decor = if (id in m.decor) m.decor - id else m.decor + id; save() }
            a.startsWith("listen:") -> hub.view.onListenMode(a.endsWith("always"))
            a == "card:close" -> hub.view.card = null
            a.startsWith("view:") -> a.substringAfter(":").let { k -> when {
                k == "close" -> { m.view = null; m.viewForced = false }
                k == "auto" -> setScreen("auto")
                m.view == k -> { m.view = null; m.viewForced = false }
                m.viewForced || StageModel.allowed(k, m.sit) -> { m.view = k; m.viewAt = SystemClock.elapsedRealtime() }
                else -> toast(if (m.sit == Sit.DRIVE) "주행 중엔 길 안내만 — ${StageModel.catName(k)}은 말로 들려 드려요" else "${StageModel.catName(k)}은 주차하면 크게 볼 수 있어요")
            } }
            a.startsWith("force:") -> setScreen(a.substringAfter(":"))
            a == "nav:start" -> { setNav(true); hub.say("안내를 시작할게요. 모의 경로예요.", "happy", false, 2, tag = "내비") }
            a == "nav:toggle" -> setNav(!m.simNav)
            a == "toon:next" -> m.toonPage++
            a == "skintab" -> if (!movingNow()) { m.overlay = "deco"; m.decoTab = "skin" }
            a.startsWith("skin:") -> setSkin(a.substringAfter(":"))
        }
        refresh(); hub.render()
    }

    private fun movingNow() = hub.speedMs > hub.moveMs || m.sit == Sit.DRIVE

    private fun buyOrUse(key: String) {
        val d = StageModel.all().firstOrNull { "${it.kind}:${it.id}" == key } ?: return
        if (key !in m.owned) {
            if (m.points < d.price) { toast("포인트가 ${d.price - m.points}점 모자라요 — 안전운전으로 모아요"); return }
            m.points -= d.price; m.owned = m.owned + key
            toast("${d.name} 열었어요. −${d.price}점")
            hub.view.faceOverride = "cheer"; hub.main.postDelayed({ if (hub.view.faceOverride == "cheer") hub.view.faceOverride = null }, 1800)
        }
        when (d.kind) { "concept" -> { m.concept = d.id; m.preview = null; m.unlockAt = SystemClock.elapsedRealtime(); m.reactAt = m.unlockAt; m.reactFace = "cheer"; hub.say(Concept.of(d.id).greet + "${d.name} 컨셉으로 갈아입었어요.", "happy", false, 1, reply = true, tag = "컨셉") }
            "face" -> m.face = d.id; "acc" -> m.acc = d.id; "decor" -> m.decor = m.decor + d.id }
        save()
    }

    /** 소품 — 없으면 별로 사고, 있으면 입기/벗기(같은 칸은 하나만) */
    private fun wearAcc(id: String) {
        val d = StageModel.ACC_DEFS.firstOrNull { it.id == id } ?: return
        val key = "acc:$id"
        if (key !in m.owned) {
            if (m.points < d.price) { toast("별이 ${d.price - m.points}개 모자라요 — 안전운전으로 모아요"); return }
            m.points -= d.price; m.owned = m.owned + key; toast("${d.name} 샀어요  −${d.price}")
        }
        if (m.equipped[d.slot] == id) m.equipped.remove(d.slot) else { m.equipped[d.slot] = id; m.reactAt = SystemClock.elapsedRealtime(); m.reactFace = "happy" }
        save()
    }

    private fun toast(s: String) { m.toast = s; m.toastAt = SystemClock.elapsedRealtime() }

    private fun save() = hub.prefs.edit().putInt("stage.points", m.points).putStringSet("stage.owned", m.owned)
        .putString("stage.concept", m.concept).putString("stage.face", m.face).putString("stage.acc", m.acc).putStringSet("stage.decor", m.decor).putString("stage.equip", m.equipped.entries.joinToString(",") { "${it.key}:${it.value}" }).apply()

    // ======================================================================
    // 말 명령 — 숙련도 · 꾸미기 · 포인트 · 점검 (온보딩 질문 중엔 넓게 받는다)
    // ======================================================================
    override fun onHeard(text: String): Boolean {
        val t = text.replace(" ", "")
        if (pending != null) {
            if (Regex("^(응|네|예|좋아|그래|오케이|ㅇㅇ|해줘|부탁|어|콜)").containsMatchIn(t)) return doPending()
            if (Regex("^(아니|됐어|괜찮|싫어|안해)").containsMatchIn(t)) { pending = null; hub.say("네, 그대로 둘게요.", "", false, 1, reply = true, tag = "반응"); return true }
        }
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
        // 시동 화면 브리핑 영역 고르기 — "차 컨디션" · "증시/코스피" · "뉴스" · "퀘스트"
        if (m.sit == Sit.IGNITION && t.length <= 20) briefPick(t)?.let { return it }
        return false
    }

    private fun briefPick(t: String): Boolean? {
        val st = m.status
        fun say(s: String) = hub.say(s, "", false, 1, reply = true, tag = "브리핑")
        return when {
            Regex("차컨디션|컨디션|차상태|배터리|내차").containsMatchIn(t) -> {
                m.overlay = "car"; refresh(); hub.render()
                val soc = st?.soc?.v; val rng = st?.rangeKm?.v
                val ok = st?.belt?.v != false && st?.doorOpen?.v != true
                say((soc?.let { "배터리는 ${it}%, " } ?: "") + (rng?.let { "${it}킬로미터 갈 수 있어요. " } ?: "") + (if (ok) "벨트랑 문도 괜찮아요. " else "벨트나 문을 한 번 확인해 주세요. ") + "타이어 공기압은 계기판에서 봐 주세요.")
                true
            }
            Regex("증시|코스피|코스닥|주식|장어때|^장$").containsMatchIn(t) -> {
                act("view:stock")
                val q = m.indices.take(2)
                say(if (q.isEmpty()) "증시 정보를 아직 불러오는 중이에요." else q.joinToString(", ") { "${it.name} ${it.price}, ${if (it.ratio >= 0) "%.2f%% 올랐어요".format(it.ratio) else "%.2f%% 내렸어요".format(-it.ratio)}" } + ".")
                true
            }
            Regex("퀘스트|미션").containsMatchIn(t) -> {
                m.overlay = "log"; refresh(); hub.render()
                say("오늘의 운전 퀘스트는 급정거 없이 10킬로미터예요. 지금 %.1f킬로미터 왔어요. 성공하면 별 20개를 드려요.".format(m.dayKmShown))
                true
            }
            Regex("뉴스|소식").containsMatchIn(t) -> { act("view:news"); false }   // 화면만 열고 읽기는 두뇌에게
            else -> null
        }
    }

    override fun ctx(c: JSONObject) {
        c.put("situation", m.sit.key)
        m.skill?.let { c.put("driverSkill", it) }
        c.put("safePoints", m.points)
    }

    override fun fallback(ev: String, extra: JSONObject?): String? = if (ev == "hello") ignitionLine() else null

    // ======================================================================
    // 상황별 이야기 — 숫자는 캐릭터의 말로(댄 11-08 "대시보드 말고 컴패니언"). 한 번에 한 이야기(hero) + 작은 칩 1~2
    // Tile 재사용: icon(선 아이콘 이름) · label(칩 제목) · value(캐릭터의 한마디) · sub(작은 근거) · action
    // ======================================================================
    private fun refresh() {
        val now = Calendar.getInstance()
        val hhmm = "%02d:%02d".format(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE))
        m.kicker = "${m.sit.label} · $hhmm"
        m.tip = ""
        m.weatherStickers = outfit()
        val st = m.status
        val name = hub.view.name.ifBlank { "" }
        val who = if (name.isNotBlank()) "${name}님, " else ""
        val soc = st?.soc?.v; val range = st?.rangeKm?.v
        val km = tripM / 1000; val min = max(1, tripS / 60)
        m.navActive = hub.view.dest != null || m.simNav
        if (inTrip) hub.view.dest?.let { tripDest = it }
        goBox()
        if (m.navActive || m.view == "nav" || m.sit == Sit.DRIVE || m.sit == Sit.STOP) navState()
        m.story = reactions.firstOrNull { SystemClock.elapsedRealtime() - it.second < 120_000 }?.first
        m.tripKm = tripM / 1000; m.tripMin = tripS / 60; m.harshN = harsh; m.cruiseMin = cruiseS / 60
        m.avgKmh = if (tripS > 0) (tripM / tripS * 3.6).roundToInt() else 0
        val out = mutableListOf<Tile>()
        fun tip(s: String) { if (m.novice) out += Tile("flag", "초보 팁", s, "초보 모드라 알려 드려요", "warn") }
        // 신호 반응이 만든 이야기(비 · 동승자 · 더위 · 배터리 …)를 맨 앞에
        out += reactions.filter { SystemClock.elapsedRealtime() - it.second < 120_000 && m.sit != Sit.DRIVE }.map { it.first }
        when (m.sit) {
            Sit.IGNITION -> {
                m.headline = "${who}${greet()}"
                // 시간대별 인사 + 온도별 음료 — 시동 화면 첫 카드
                out.add(0, Tile("cup", "인사", "${who}${greet()}. ${drink().first} 한 잔 어때요?", drink().second, "accent"))
                out += Tile("cloud", "오늘 날씨", weatherComic(), listOfNotNull(tempC?.let { "지금 ${it}° ${wname()}" }, tomorrow.ifBlank { null }).joinToString(" · "), "info")
                if (soc != null) out += Tile("battery", "차 컨디션", if (soc >= 80) "배터리 ${soc}% 꽉 찼어요. 오늘은 어디든 거뜬해요." else if (soc >= 30) "배터리 ${soc}%, ${range ?: soc * 5}km는 문제없어요." else "배터리가 ${soc}%예요. 가는 길에 충전하고 가요.",
                    "주행 가능 ${range ?: "-"}km · 타이어는 계기판에서", if (soc < 30) "warn" else "good")
                news.firstOrNull()?.let { out += Tile("news", newsTag.substringBefore(" ·"), "오늘 소식 하나 들려 드릴까요? \"$it\"", news.getOrNull(1)?.let { n -> "다음 소식: $n" } ?: "말로 \"뉴스 들려줘\"", "accent") }
                tip("출발 전에 시트 → 미러 → 벨트 순서로 맞춰요. 계기판에 빨간 경고등이 없는지도 한 번 봐요.")
            }
            Sit.READY -> {
                m.headline = "어디로 갈까요?"
                val dest = hub.view.dest
                out += if (dest != null) Tile("pin", "오늘 목적지", "${dest}까지 같이 가요. ${hub.view.remain.ifBlank { "길을 찾는 중이에요." }}", hub.view.eta, "accent")
                       else Tile("pin", "추천 목적지", recommendDest().let { "이 시간엔 보통 ${it.first}에 가죠? ${it.second}" }, "말로 \"집으로 가자\"", "accent", "추천")
                if (soc != null && soc < 30) out += Tile("battery", "가는 김에 충전", "배터리가 ${soc}%라, 가는 길 충전소에 들렀다 가요. 20분이면 80%까지 돼요.", "가는 김에 · 경유지 추가", "warn", action = "say:충전소 들렀다 가자")
                out += Tile("bag", "가는 김에", "가는 길에 세탁물 찾을까요? 5분이면 돼요.", "할 일 1건 · 가는 길 0.4km", "good", "예시")
                out += Tile("music", "출발 편성", "뉴스 5분 듣고, 잔잔한 음악으로 이어 갈게요.", "도착 시간에 맞춰 편성", "info")
                tip("브레이크 꾹 → D → 주변 확인 → 발을 천천히. 출발할 땐 깜빡이부터 켜요.")
            }
            Sit.DRIVE -> { m.headline = m.navTurn }
            Sit.STOP -> {
                m.headline = "신호 대기 중"
                val g = gossip()
                out += Tile("chat", g.first, g.second, "신호 기다리는 동안 동네 이야기", "accent", "예시")
                out += Tile("compass", "다음 안내", "다음은 ${hub.view.dist(m.navTurnM)} 앞에서 ${m.navTurn}이에요.", "${m.navEta} · ${m.navDest} ${m.navRemain}", "info", if (m.mockRoute) "모의 경로" else "")
                tip("정차 중엔 브레이크를 계속 꾹. 앞차가 출발하면 한 박자 쉬고 천천히.")
            }
            Sit.PARK -> {
                m.headline = "주차 완료"
                val smooth = penalties == 0
                out += Tile("road", "이번 주행", "${min}분 동안 %.1fkm 달렸어요. ".format(km) + if (smooth) "한 번도 급하게 서지 않았어요. 최고예요." else "급정거가 ${harsh}번 있었어요. 다음엔 조금 더 미리 멈춰 봐요.",
                    "평균 ${if (tripS > 0) (tripM / tripS * 3.6).roundToInt() else 0}km/h", if (smooth) "good" else "warn")
                out += Tile("star", "안전운전 포인트", if (m.tripPoints >= 0) "이번엔 ${sign(m.tripPoints)}${look().pointWord.let { if (it == "포인트") "점" else " $it" }} 모았어요. 방 꾸미기에 써 볼까요?" else "이번엔 ${sign(m.tripPoints)}점이에요. 다음엔 꼭 되찾아요.",
                    m.ledger.take(3).joinToString(" · ") { "${it.why} ${sign(it.delta)}" }.ifBlank { "모은 포인트 ${m.points}" }, "warn", action = "deco", lines = m.ledger.toList())
                out += Tile("cup", "쉼표", "15분만 쉬어 가요. 등받이 살짝 젖히고, 목 스트레칭 세 번.", "오래 달린 날엔 쉼표가 보약", "good")
                tip("주차 요령: 핸들을 똑바로 풀고 → P → 주차 브레이크. 내리기 전에 사이드미러 접기.")
            }
            Sit.OFF -> {
                m.headline = "오늘도 수고했어요"
                out += Tile("chart", "하루 정리", "오늘 %.1fkm 함께 달렸어요. ${sign(dayPoints)}점 모았고요.".format(dayKm), "총 ${m.points} · ${nextUnlock()}", "info", action = "deco", lines = m.dayLedger.toList())
                out += Tile("bag", "두고 내린 것", "휴대폰이랑 지갑 챙겼죠? 뒷좌석도 한 번 봐요.", "창문 · 실내등도 확인", "warn")
                if (tomorrow.isNotBlank()) out += Tile("clock", "내일", "${tomorrow.replace("내일 ", "내일은 ")}" + if (rain >= 50) "이에요. 우산 꼭 챙겨요." else "이에요. 출발 10분 전에 공조 켜 둘까요?", "아침 출발 전 알림", "accent")
            }
        }
        val prevFirst = m.tiles.firstOrNull()?.value
        m.tiles = out
        if (m.heroIdx >= out.size) m.heroIdx = 0
        if (out.firstOrNull()?.value != prevFirst && prevFirst != null && m.tiles.isNotEmpty() && reactions.any { it.first.value == out.first().value }) m.heroIdx = 0
        m.bubble = out.getOrNull(m.heroIdx)?.value ?: when (m.sit) {
            Sit.DRIVE -> "길은 제가 말로 알려 드릴게요. 앞만 봐요."
            else -> "${greet()}!"
        }
    }

    // ======================================================================
    // 차 신호 → 캐릭터 반응(Pleos SDK → AAOS → 모의). 값이 "바뀔 때"만 한 번 — 주행 중엔 화면 대신 말·한 줄 알림
    // ======================================================================
    private val reactions = mutableListOf<Pair<Tile, Long>>()   // (이야기, 생긴 시각) — 2분 동안 상황 화면 맨 앞
    private val prev = HashMap<String, Any?>()
    private var pending: String? = null                         // "응"이면 할 일(cabin:23 · charge)
    private var kmMark = 0

    private fun changed(k: String, v: Any?): Boolean { val c = prev.containsKey(k) && prev[k] != v; prev[k] = v; return c }
    private fun event(icon: String, text: String) { m.carEvent = icon to text; m.carEventAt = SystemClock.elapsedRealtime() }
    private fun story(t: Tile) {
        reactions.removeAll { it.first.label == t.label }; reactions.add(0, t to SystemClock.elapsedRealtime()); m.heroIdx = 0
        m.heroAt = SystemClock.elapsedRealtime(); m.reactAt = m.heroAt
        m.reactFace = when (t.tone) { "warn" -> "worried"; "good" -> "happy"; else -> "surprised" }
    }
    private fun talk(s: String, prio: Int = 2, mood: String = "", listen: Boolean = false) = hub.say(s, mood, listen, prio, tag = "반응")

    private fun react() {
        val st = m.status ?: return
        val driving = m.sit == Sit.DRIVE || m.sit == Sit.STOP
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        st.wiper.v.let { w -> if (changed("wiper", w) && w == true) {
            event("cloud", "와이퍼 켜짐 — 비가 와요")
            story(Tile("cloud", "비 오는 날", "비가 오네요. 우산 쓰고 있을게요. 앞차랑 간격은 평소의 두 배로 둬요.", "와이퍼 신호(Pleos Wiper)", "info"))
            talk(if (m.novice) "비가 오네요. 앞차랑 간격을 평소의 두 배로 두고, 브레이크는 미리미리 밟아요." else "비가 오네요. 간격 넉넉히 가요.", 2, "care")
        } }
        st.doorOpen.v.let { d -> if (changed("door", d) && d == true) {
            if (hub.speedMs > hub.moveMs) { event("warn", "문이 열려 있어요."); hub.say("문이 열려 있어요. 바로 확인해 주세요.", "alert", false, 0, tag = "반응·문") }
            else { event("bag", "문 열림 — 내리시나요?"); story(Tile("bag", "내리기 전에", "내리기 전에 휴대폰이랑 지갑 챙겼죠? 뒷좌석에 두고 내린 건 없나요?", "운전석 문 열림(Pleos Door)", "warn")); talk("내리기 전에 휴대폰이랑 지갑 챙겼죠? 뒷좌석도 한 번 봐요.", 1, "care") }
        } }
        st.windowOpen.v.let { w -> if (changed("window", w) && w == true && st.wiper.v == true) { event("cloud", "비 오는데 창문이 열렸어요"); talk("비가 들어와요. 창문 닫을까요?", 2, "care") } }
        st.cabinC.v.let { c -> if (changed("cabin", c) && c != null) {
            if (c >= 27f) { pending = "cabin:23"; event("temp", "실내 ${"%.0f".format(c)}° — 조금 더워요"); story(Tile("temp", "실내 온도", "좀 덥죠? ${"%.0f".format(c)}도예요. 23도로 낮춰 드릴까요? \"응\"이라고만 해 주세요.", "공조 설정 온도(Pleos HVAC)", "warn")); talk("좀 덥죠? 23도로 낮춰 드릴까요?", 2, "care", listen = !driving) }
            else if (c <= 17f) { pending = "cabin:23"; event("temp", "실내 ${"%.0f".format(c)}° — 쌀쌀해요"); story(Tile("temp", "실내 온도", "쌀쌀하네요, ${"%.0f".format(c)}도예요. 23도로 올려 드릴까요?", "공조 설정 온도(Pleos HVAC)", "info")); talk("쌀쌀하네요. 23도로 올려 드릴까요?", 2, "care", listen = !driving) }
        } }
        st.passenger.v.let { p -> if (changed("passenger", p) && p == true) {
            event("chat", "옆자리에 누가 탔어요")
            story(Tile("chat", "동승자 모드", "옆자리에 친구가 탔네요, 반가워요. 같이 들을 노래 틀어 드릴까요? 혼잣말은 줄일게요.", "조수석 착석(Pleos Seat)", "accent"))
            talk("옆자리에 친구가 탔네요. 반가워요. 같이 들을 노래 틀어 드릴까요?", 2, "happy")
        } }
        st.rearKid.v.let { p -> if (changed("kid", p) && p == true) {
            story(Tile("music", "뒷좌석 손님", "뒷자리에 꼬마 손님인가요? 동요랑 끝말잇기 준비해 뒀어요.", "뒷좌석 착석(Pleos Seat)", "good"))
            talk("뒷자리에 꼬마 손님인가요? 동요랑 끝말잇기 준비해 뒀어요.", 2, "happy")
        } }
        st.belt.v.let { b -> if (changed("belt", b) && b == false && (m.sit == Sit.READY || driving)) { event("warn", "벨트를 매 주세요"); hub.say("벨트부터 매요. 매면 바로 출발해요.", "care", false, 1, tag = "반응·벨트") } }
        st.lowBeam.v.let { l -> if (changed("lights", l) && l == false && driving && (hour >= 18 || hour < 6)) { event("warn", "전조등이 꺼져 있어요"); talk("어두워졌어요. 전조등 켜 볼까요?", 1, "care") } }
        st.cruise.v.let { c -> if (changed("cruise", c) && c == "작동") {
            event("cruise", "크루즈 켜짐 · ${st.cruiseKmh.v ?: hub.kmh()}km/h")
            if (m.novice) talk("크루즈 좋아요. 발은 브레이크 가까이에 두세요.", 2) else talk("크루즈 좋네요. 연비도 아껴요.", 3)
        } }
        st.mode.v.let { md -> if (changed("mode", md) && md != null) when (md) {
            "ECO" -> { event("road", "에코 모드"); talk("에코 모드. 배터리 아끼기 딱 좋아요.", 3, "happy") }
            "SPORT" -> { event("speed", "스포츠 모드"); if (m.novice) talk("스포츠 모드는 반응이 빨라요. 살살 밟아요.", 2, "care") }
        } }
        st.turn.v.let { t -> if (changed("turn", t) && t != null && t != 0) event("signal", when (t) { 1 -> "왼쪽 깜빡이"; 2 -> "오른쪽 깜빡이"; else -> "비상등" }) }
        st.soc.v.let { s -> val low = s != null && s <= 20; if (changed("lowSoc", low) && low) {
            pending = "charge"; event("battery", "배터리 ${s}%")
            story(Tile("battery", "가는 김에 충전", "배터리가 ${s}%예요. 가는 길 충전소에 들렀다 갈까요? 20분이면 80%까지 돼요.", "주행 가능 ${st.rangeKm.v ?: "-"}km(Pleos EvBattery)", "warn", action = "say:충전소 들렀다 가자"))
            talk("배터리가 ${s}퍼센트예요. 가는 길 충전소에 들렀다 갈까요?", 1, "care", listen = !driving)
        } }
        // 주행 이정표 — 10km마다 칭찬 +3, 30분·60분 쉼표 안내
        val km10 = (tripM / 10_000).toInt()
        if (km10 > kmMark) { kmMark = km10; award("flag", "${km10 * 10}km 달성", 3); event("flag", "${km10 * 10}km 달성. +3") }
        val tmin = st.tripMin.v ?: (tripS / 60)
        if (changed("min30", tmin >= 30) && tmin >= 30) { event("clock", "벌써 30분 달렸어요"); if (m.novice) talk("벌써 30분 달렸어요. 어깨 힘 빼고 가요.", 3) }
    }

    /** "응" — 물어본 일을 한다 */
    private fun doPending(): Boolean {
        val p = pending ?: return false
        pending = null
        when {
            p.startsWith("cabin:") -> {
                val c = p.substringAfter(":").toFloat()
                val real = m.status?.setCabin(c) == true
                hub.say("${c.toInt()}도로 맞췄어요." + if (real) "" else " (시험 차라 화면 값만 바꿨어요)", "happy", false, 1, reply = true, tag = "공조")
                event("temp", "실내 ${c.toInt()}°로 맞춤")
            }
            p == "charge" -> { hub.say("좋아요. 가는 길 충전소를 경유지로 넣을게요.", "happy", false, 1, reply = true, tag = "충전"); hub.heard("가는 길에 전기차 충전소 들렀다 가자") }
        }
        return true
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
        if (wcode in 51..67 || wcode in 80..82 || rain >= 60 || m.status?.wiper?.v == true) out += "☂️"
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
        return "밖은 ${t}° ${wname()} — 저는 $w."
    }

    private fun drink(): Pair<String, String> {
        val t = tempC ?: 12
        return when {
            t <= 5 -> "따끈한 유자차" to "손난로 대신 한 잔 — 가는 길 카페 2곳"
            t <= 15 -> "따뜻한 보리차" to "목이 칼칼한 날엔 따뜻하게"
            t >= 25 -> "시원한 아이스티" to "차 안이 덥기 전에 에어컨 먼저"
            else -> "향긋한 녹차" to "출발 전 여유 한 모금"
        }
    }

    private fun wname() = when (wcode) {
        0 -> "맑음"; 1, 2 -> "구름 조금"; 3 -> "흐림"; 45, 48 -> "안개"; in 51..57 -> "이슬비"; in 61..67 -> "비"; in 71..77 -> "눈"
        in 80..82 -> "소나기"; in 85..86 -> "눈 소나기"; in 95..99 -> "천둥번개"; else -> ""
    }

    // ---------- 어디로 갈까요? — 이 시간에 자주 가는 곳(지난 주행 기록으로 계산) ----------
    private data class Go(val name: String, val startMin: Int, val travelMin: Int, val day: String)

    private fun goLog(): List<Go> = hub.prefs.getString(GO_LOG, "")!!.lines().mapNotNull { l ->
        l.split('|').takeIf { it.size == 4 }?.let { p -> runCatching { Go(p[0], p[1].toInt(), p[2].toInt(), p[3]) }.getOrNull() } }

    private fun saveGoLog(list: List<Go>) = hub.prefs.edit().putString(GO_LOG, list.takeLast(80).joinToString("\n") { "${it.name}|${it.startMin}|${it.travelMin}|${it.day}" }).apply()

    private fun dayKey(cal: Calendar) = "%04d%02d%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))

    /** 주차할 때 — 목적지가 있던 주행만 기록(출발 시각 · 걸린 시간) */
    private fun recordGo() {
        val name = tripDest?.trim()?.takeIf { it.isNotBlank() } ?: return
        if (tripS < 60 || tripStartWall == 0L) return
        val cal = Calendar.getInstance().apply { timeInMillis = tripStartWall }
        saveGoLog(goLog() + Go(name.replace('|', ' ').replace('\n', ' '), cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE), max(1, tripS / 60), dayKey(cal)))
        hub.log("stage|go|$name|${tripS / 60}분")
    }

    /** 지금 시각 ±60분에 2일 이상 간 곳 — (이름, 평소 출발 분, 평균 걸린 분, 간 날 수) */
    private fun habitGo(): Triple<Go, Int, Int>? {
        val now = Calendar.getInstance(); val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        fun gap(a: Int, b: Int) = minOf(kotlin.math.abs(a - b), 1440 - kotlin.math.abs(a - b))
        val near = goLog().filter { gap(it.startMin, nowMin) <= 60 }
        val best = near.groupBy { it.name }.maxByOrNull { (_, l) -> l.map { it.day }.distinct().size } ?: return null
        val days = best.value.map { it.day }.distinct().size
        if (days < 2) return null
        val l = best.value
        val start = l.map { it.startMin }.sorted()[l.size / 2]
        return Triple(Go(best.key, start, l.map { it.travelMin }.average().roundToInt(), ""), days, l.size)
    }

    private fun hm(min: Int) = "%02d:%02d".format((min / 60) % 24, min % 60)

    private fun goBox() {
        val v = hub.view
        val dest = v.dest
        val h = habitGo()
        val nowMin = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        when {
            dest != null -> { m.goName = dest; m.goSub = listOf(v.remain, v.eta).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "길을 찾는 중이에요" }; m.goTag = "오늘 목적지" }
            h != null -> {
                val (g, days, n) = h
                m.goName = g.name
                m.goSub = "평소 ${hm(g.startMin)} 출발 · 지금 출발하면 ${hm(nowMin + g.travelMin)} 도착 · 약 ${g.travelMin}분"
                m.goTag = "자주 가는 곳 · 최근 ${days}일 · ${n}번 평균"
            }
            else -> { m.goName = ""; m.goSub = "\"회사 가자\"처럼 말하면 길 안내를 시작해요"; m.goTag = "" }
        }
    }

    /** 시연용 — 지난 5일 이 시간쯤 '회사'에 간 기록 */
    private fun seedGoLog() {
        val now = Calendar.getInstance(); val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val seed = (1..5).map { d -> val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -d) }
            Go("회사", (nowMin - 10 + d * 4 + 1440) % 1440, 21 + d % 3 * 2, dayKey(c)) }
        saveGoLog(goLog() + seed)
    }

    private fun recommendDest(): Pair<String, String> {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (h) { in 6..10 -> "회사" to "평소 출근 시간이에요 · 약 25분"; in 17..21 -> "집" to "퇴근길 · 약 30분"; else -> "자주 가는 카페" to "지난주 두 번 갔어요" }
    }

    private fun nextUnlock(): String {
        val next = (StageModel.CONCEPTS.filter { "${it.kind}:${it.id}" !in m.owned }.minByOrNull { it.price }
            ?: StageModel.all().filter { "${it.kind}:${it.id}" !in m.owned }.minByOrNull { it.price }) ?: return "전부 열었어요."
        return if (m.points >= next.price) "${next.name} 열 수 있어요" else "${next.name}까지 ${next.price - m.points}점"
    }

    private fun sign(n: Int) = if (n >= 0) "+$n" else "−${-n}"
    private fun look() = m.look()

    private fun ignitionLine(): String {
        val t = tempC
        val base = look().greet + "${greet()}. " + (t?.let { "밖이 ${it}도라 " + (if (t <= 8) "목도리 둘렀어요. " else "") } ?: "") + "${drink().first} 한 잔 어때요?"
        // 어디로 갈지 → 브리핑 영역 고르기(말로)
        val go = habitGo()?.let { (g, _, _) -> val nowMin = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
            " 이 시간엔 보통 ${g.name}에 가시죠? 지금 출발하면 ${hm(nowMin + g.travelMin)}쯤 도착해요." } ?: ""
        val ask = " 차 컨디션, 증시, 뉴스, 운전 퀘스트 중에 어떤 걸 브리핑해 드릴까요? 말해 주시면 자세하게 알려 드릴게요."
        return (if (m.novice) "$base 출발 전에 시트랑 미러부터 맞춰요." else base) + go + ask
    }

    private fun parkLine(): String {
        val plus = m.ledger.filter { it.delta > 0 }.sumOf { it.delta }; val minus = -m.ledger.filter { it.delta < 0 }.sumOf { it.delta }
        val p = look().greet + "주차 완료, 수고했어요. " + (if (minus > 0) "플러스 ${plus}점, 마이너스 ${minus}점 해서 ${m.tripPoints}점이에요." else "안전운전 포인트 ${m.tripPoints}점 받았어요.")
        return if (m.novice) "$p 핸들 똑바로 풀고, P, 주차 브레이크까지 확인해요." else p
    }

    private fun offLine() = look().greet + "오늘 %.1f킬로미터 달렸어요. 휴대폰이랑 지갑 챙기셨죠? 내일 또 만나요.".format(dayKm)

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
            v.turns.getOrNull(1).let { n -> m.navNextType = n?.type ?: ""; m.navNextM = n?.m ?: 0; m.navNext = n?.desc ?: "" }
            m.navMin = Regex("(\\d+)분").find(v.remain)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return
        }
        m.mockRoute = true
        val total = MOCK.sumOf { it.second }
        val prog = ((tripM - (if (m.simNav) navBase else 0.0)).coerceAtLeast(0.0)) % total
        var d = prog.toInt(); var i = 0
        while (i < MOCK.size - 1 && d >= MOCK[i].second) { d -= MOCK[i].second; i++ }
        val (type, seg, desc) = MOCK[i]
        m.navTurnType = type; m.navTurnM = (seg - d).coerceAtLeast(0); m.navTurn = desc
        MOCK.getOrNull(i + 1).let { n -> m.navNextType = n?.first ?: ""; m.navNextM = n?.second ?: 0; m.navNext = n?.third ?: "" }
        val left = (total - prog).coerceAtLeast(0.0)
        m.navMin = max(1, (left / 8.3 / 60).roundToInt())
        m.navDest = "코엑스"; m.navRemain = "%.1fkm".format(left / 1000)
        val eta = Calendar.getInstance().apply { add(Calendar.MINUTE, m.navMin) }
        m.navEta = "도착 %02d:%02d".format(eta.get(Calendar.HOUR_OF_DAY), eta.get(Calendar.MINUTE))
    }

    // ---------- 날씨(Open-Meteo, 키 없음) · 관심 뉴스(현비서 서버 /hyun/feed + /hyun/taste) ----------
    private fun fetchWeather() = Thread {
        runCatching {
            val la = hub.lat ?: 37.45; val lo = hub.lng ?: 127.13   // 위치를 아직 모르면 성남(서버 날씨 기본과 같은 곳)
            val j = getJson("https://api.open-meteo.com/v1/forecast?latitude=$la&longitude=$lo&current=temperature_2m,weather_code" +
                "&hourly=precipitation_probability,temperature_2m&forecast_hours=8&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code&forecast_days=2&timezone=auto")
            val cur = j.getJSONObject("current")
            val pr = j.getJSONObject("hourly").getJSONArray("precipitation_probability")
            val d = j.getJSONObject("daily")
            val tmx = d.getJSONArray("temperature_2m_max").getDouble(1).roundToInt(); val tmn = d.getJSONArray("temperature_2m_min").getDouble(1).roundToInt()
            val trn = d.getJSONArray("precipitation_probability_max").optInt(1, 0)
            val ht = j.getJSONObject("hourly").getJSONArray("time"); val htemp = j.getJSONObject("hourly").getJSONArray("temperature_2m")
            val hours = (0 until minOf(8, ht.length())).map { k -> Triple(ht.getString(k).takeLast(5).take(2).trimStart('0').ifBlank { "0" } + "시", htemp.getDouble(k).roundToInt(), pr.optInt(k, 0)) }
            val t0n = d.getJSONArray("temperature_2m_min").getDouble(0).roundToInt(); val t0x = d.getJSONArray("temperature_2m_max").getDouble(0).roundToInt()
            hub.main.post {
                m.hourly = hours; m.todayMin = t0n; m.todayMax = t0x
                tempC = cur.getDouble("temperature_2m").roundToInt(); wcode = cur.optInt("weather_code", -1)
                rain = (0 until pr.length()).maxOfOrNull { pr.optInt(it, 0) } ?: 0
                tomorrow = "내일 $tmn~$tmx°" + if (trn >= 40) " · 비 $trn%" else ""
                m.rainMax = rain; m.tomorrowLine = "$tmn~$tmx° · " + if (trn >= 40) "비 $trn%" else "비 없음"
                hub.log("stage|weather|${tempC}°|code=$wcode|rain=$rain|$tomorrow")
                m.weatherShort = "${tempC}° ${wname()}"; m.wcode = wcode; m.tempNow = tempC; m.weatherLine = "지금 ${tempC}° ${wname()} · 비 확률 $rain% · $tomorrow"
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
            val titles = (0 until (items?.length() ?: 0)).map { items!!.getJSONObject(it).optString("title") }.filter { it.isNotBlank() && !it.startsWith("[알림]") && !it.contains("저작권") }
            val hit = likes.firstOrNull { k -> titles.any { it.contains(k) } }
            hub.main.post {
                news = if (hit != null) titles.filter { it.contains(hit) }.take(2) + titles.filterNot { it.contains(hit) }.take(1) else titles.take(2)
                m.newsList = (if (hit != null) titles.sortedBy { if (it.contains(hit)) 0 else 1 } else titles).take(8); m.newsSource = n.optString("source").ifBlank { "현비서 서버 정보 통로" } + " · " + n.optString("asof")
                newsTag = if (hit != null) "관심 뉴스 · $hit" else "오늘의 헤드라인"
                hub.log("stage|news|${newsTag}|${news.size}")
                refresh()
            }
        }.onFailure { hub.log("stage|news|FAIL|${it.message}") }
    }.start()

    /** 연합뉴스 RSS — 제목과 기사 사진(media:content). 서버 헤드라인이 없으면 목록도 여기서 */
    private fun fetchRss() = Thread {
        runCatching {
            val c = (URL("https://www.yna.co.kr/rss/news.xml").openConnection() as HttpURLConnection).apply { connectTimeout = 5000; readTimeout = 8000 }
            val xml = try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
            val times = HashMap<String, String>()
            val items = Regex("<item>([\\s\\S]*?)</item>").findAll(xml).mapNotNull { mi ->
                val b = mi.groupValues[1]
                val title = Regex("<title><!\\[CDATA\\[(.*?)]]></title>").find(b)?.groupValues?.get(1) ?: return@mapNotNull null
                val img = Regex("<media:content url=\"(.*?)\"").find(b)?.groupValues?.get(1)
                Regex("<pubDate>.*? (\\d\\d:\\d\\d):").find(b)?.groupValues?.get(1)?.let { times[title] = it }
                title to img
            }.filterNot { it.first.startsWith("[알림]") || it.first.contains("저작권") }.take(12).toList()
            hub.main.post {
                m.newsImgs = items.filter { it.second != null }.associate { it.first to it.second!! }; m.newsTimes = times
                if (m.newsList.isEmpty()) { m.newsList = items.map { it.first }.take(8); m.newsSource = "연합뉴스 최신 기사 RSS" }
                if (news.isEmpty()) news = m.newsList.take(2)
                hub.log("stage|rss|${items.size}|img=${m.newsImgs.size}")
                refresh()
            }
        }.onFailure { hub.log("stage|rss|FAIL|${it.message}") }
    }.start()

    /** 웹툰 · 주식 — 서버 정보 통로(네이버 웹툰 인기 · 네이버 증권) */
    private fun fetchFeeds() = Thread {
        runCatching {
            val w = getJson("${hub.link.base}/hyun/feed?cat=webtoon&top=10")
            val a = w.optJSONArray("items")
            val list = (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }.map { o -> Toon(o.optInt("rank"), o.optString("title"), o.optString("author"), o.optDouble("star", 0.0), o.optString("img")) }
            hub.main.post { m.toons = list; m.toonSource = w.optString("asof"); hub.log("stage|toon|${list.size}") }
        }.onFailure { hub.log("stage|toon|FAIL|${it.message}") }
        runCatching {
            val s = getJson("${hub.link.base}/hyun/feed?cat=stock&top=8")
            val a = s.optJSONArray("items")
            val objs = (0 until (a?.length() ?: 0)).map { a!!.getJSONObject(it) }
            val idx = objs.filter { it.has("index") }.map { Quote(it.optString("index"), it.optString("price"), it.optString("ratio").toFloatOrNull() ?: 0f) }
            val top = objs.filter { it.has("name") }.map { Quote(it.optString("name"), it.optString("price"), it.optString("ratio").toFloatOrNull() ?: 0f) }
            hub.main.post { m.indices = idx; m.stocks = top; m.stockAsof = s.optString("asof"); hub.log("stage|stock|${idx.size}+${top.size}") }
        }.onFailure { hub.log("stage|stock|FAIL|${it.message}") }
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
        private const val GO_LOG = "stage.goLog"   // 지난 주행: "목적지|출발 분(하루 중)|걸린 분|yyyyMMdd" 줄 목록
        const val SIM_ACTION = "io.github.somanghao.makecar.hyun.SIM"
        private val SIM_KEYS = listOf("wiper", "door", "window", "passenger", "kid", "belt", "lights", "lock", "cruise", "turn", "cabin", "soc", "range", "mode", "steer")
        private const val HARSH_MS = 3.5f          // 1초에 이만큼(약 12.6km/h) 넘게 줄면 급정거
        private const val NOVICE_REST_S = 50 * 60.0
        private const val WELCOME = 100
        private val CAR_PERMS = listOf("android.car.permission.CAR_ENERGY", "android.car.permission.CAR_SPEED", "android.car.permission.CAR_MILEAGE")
        /** 모의 경로(판교 → 코엑스) — 내비가 없는 표준 에뮬레이터에서 주행 화면 구조를 보이려고 */
        private val LIMITS = listOf(50, 60, 80, 100, 80, 60, 30)   // 모의 경로 구간별 제한속도
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
