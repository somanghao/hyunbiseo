package io.github.somanghao.makecar.hyun.feature

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import io.github.somanghao.makecar.hyun.core.Feature
import io.github.somanghao.makecar.hyun.core.Fmt
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

/** 상황별 아이디어 한 개(docs/hyun-content-ideas.md) — moment = 특별한 순간 */
data class Idea(val id: String, val sit: Sit, val name: String, val moment: Boolean = false, val round: Int = 1, val theme: String = "")

/** 웹툰(서버 /hyun/feed?cat=webtoon) · 시세(cat=stock) */
data class Toon(val rank: Int, val title: String, val author: String, val star: Double, val img: String)
data class Quote(val name: String, val price: String, val ratio: Float)

/** 즐겨찾기 도착지 — demoMin: 예시일 때 보이는 걸리는 분(위치·기록이 없을 때만) */
data class Fav(val name: String, val lat: Double, val lng: Double, val demoMin: Int = 0)

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
    var todos: List<String> = emptyList()   // 할 일 — 연동 전엔 비어 있다(예시 목록 '세탁물 찾기…'는 뺐다 — 사용자 11-14)
    var weekPoints = 0; val weekGoal = 300
    var newsImgs: Map<String, String> = emptyMap()   // 제목 → 기사 사진(연합뉴스 RSS media:content)
    var wcode = -1; var tempNow: Int? = null
    val raining get() = forceRain || status?.wiper?.v == true || wcode in 51..67 || wcode in 80..82
    var forceRain = false                     // 특별한 순간 "비 오는 날" 미리보기
    var idea: String? = null                  // 상황별 아이디어 미리보기(docs/hyun-content-ideas.md) — 그 상황 화면에 그 레이아웃
    val snowing get() = forceSnow || wcode in 71..77 || wcode in 85..86
    var forceSnow = false                     // 첫눈 미리보기
    // 캐릭터 관계(2차) — 경험치(모은 별 누적) → 레벨 · 기분(운전 결에 반응, 실제 주행 신호로 오르내림)
    var xp = 0
    var mood = 60                             // 0~100 — 부드러우면 오르고 급정거·깜빡이 없는 회전이면 내려간다
    var moodWhy = ""                          // 마지막으로 기분이 바뀐 이유
    var gleoLast = ""                         // 글레오 함수 마지막 호출(2차 gleo3)
    /** 먼저 건네는 제안(칼리 11-12) — 넓게 묻지 않고, 좌석 착석(Pleos Seat)으로 누가 탔는지 보고 하나를 고른다. (말 · 화면 큰 줄 · 화면 작은 줄)
     *  뒷자리 = 아이들과 가족 → 넌센스 퀴즈 · 조수석만 = 둘이서 퀴즈 · 혼자(또는 좌석을 못 읽음) = 뉴스 세 줄. 전부 "응" 하면 앱 안에서 바로 된다(서버 없이) */
    /** 몇 명이 탔나 — 운전석 1 + 조수석 착석 + 뒷줄 자리별 착석(Pleos Seat). 뒷줄 수를 못 읽으면 '누군가 있다'를 1명으로 */
    fun riders(): Int = 1 + (if (status?.passenger?.v == true) 1 else 0) + (status?.rearN?.v ?: if (status?.rearKid?.v == true) 1 else 0)
    fun offer(): Triple<String, String, String> {
        val kid = status?.rearKid?.v == true; val pas = status?.passenger?.v == true
        val n = riders()
        return when {
            kid -> Triple("우와, 온 가족이 다 탔네요! 오늘의 넌센스 퀴즈, 같이 맞춰 볼래요?", "오늘의 넌센스 퀴즈, 같이 맞춰 볼래요?", "${n}명 탔어요 · \"좋아\" 하면 시작해요")
            pas -> Triple("오, 옆자리에 친구가 탔네요! 둘이서 넌센스 퀴즈 한판 어때요?", "둘이서 넌센스 퀴즈 한판 어때요?", "${n}명 탔어요 · \"좋아\" 하면 시작해요")
            else -> Triple("오늘 주요 뉴스 세 가지, 짧게 들려줄까요?", "오늘 주요 뉴스 세 가지, 짧게 들려줄까요?", "\"응\" 하면 바로 읽어 줘요 · \"퀴즈\"라고 해도 돼요")
        }
    }
    /** 지금 건넨 제안이 무엇인지 — quiz(동승자 있음) · news(혼자) */
    fun offerKind() = if (status?.rearKid?.v == true || status?.passenger?.v == true) "quiz" else "news"
    /** 진행 중인 놀이의 화면 문구(큰 줄 · 작은 줄) — 있으면 주행 말풍선이 제안 대신 이걸 보인다 */
    var prompt: Pair<String, String>? = null
    // 엔진오일 교체 알림 — 마지막 교체 뒤 달린 거리로 계산(누적 주행거리를 못 읽으면 시연 값)
    var oilLeftKm = OIL_KM; var oilDemo = false
    // 쉼표(슬립 모드) — 제안 → 쉼터 도착 → 15·30분 고르기 → 타이머 · 차 안 세팅 → 깨우기
    var restAccepted = false                  // "쉬어 갈래" 한 뒤 — 차를 세우면 고르기 화면
    var restName = ""                         // 다음 쉼터 이름(있으면)
    var restMin = 0; var restEndAt = 0L; var restTotalMs = 0L   // 진행 중이면 restEndAt > 0(elapsedRealtime)
    var restCabin: Map<String, Boolean>? = null                 // 차 안 세팅 결과(light · seat) — null = 맞추는 중
    var pageDir = 0; var pageAt = 0L          // 페이저 — 마지막으로 넘긴 방향(+1 다음 · −1 이전)과 시각(밀어 넘기는 연출)
    val level get() = (1 + kotlin.math.sqrt(xp / 25.0).toInt()).coerceAtMost(10)
    fun xpFor(l: Int) = 25 * (l - 1) * (l - 1)
    val levelTitle get() = LEVELS[level - 1]
    val moodLabel get() = when { mood >= 75 -> "콧노래"; mood >= 40 -> "편안"; else -> "긴장" }
    var dayStartAt = 0L; var stopsToday = 0; var dayKmShown = 0.0
    var questKm = 0.0; var questDone = false   // 오늘의 퀘스트 "급정거 없이 10km" — 급정거하면 처음부터 · 성공하면 별 +20 실제 지급(가짜 감사 #7)
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
    var goName = ""; var goSub = ""; var goTag = ""; var goAction = ""
    var favNames: List<String> = emptyList(); var favIdx = -1   // 즐겨찾기 도착지(고른 것 = favIdx)
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
        const val OIL_KM = 10_000        // 엔진오일 권장 교체 주기(km) — 차종 정비 주기를 받기 전의 기본값
        const val OIL_SOON_KM = 1_000    // 이만큼 남으면 시동 때 먼저 알린다
        val CATS = listOf("news" to "뉴스", "nav" to "내비게이션", "webtoon" to "웹툰", "music" to "음악·팟캐스트", "sports" to "스포츠",
            "stock" to "주식", "weather" to "날씨·차림", "local" to "동네 이야기", "schedule" to "일정·할 일")
        private val STILL = setOf(Sit.IGNITION, Sit.READY, Sit.PARK, Sit.OFF)
        /** 상황별 허용(전체 레이아웃) — 주행 중엔 내비만(나머지는 음성), 신호대기는 내비·동네 이야기 한 장, 웹툰은 주차 전용 */
        val ALLOWED: Map<String, Set<Sit>> = mapOf(
            "nav" to Sit.values().toSet(), "news" to STILL, "webtoon" to setOf(Sit.PARK), "music" to STILL, "sports" to STILL,
            "stock" to STILL, "weather" to STILL, "local" to STILL + Sit.STOP, "schedule" to STILL)
        fun allowed(cat: String, s: Sit) = ALLOWED[cat]?.contains(s) == true
        fun catName(k: String) = CATS.firstOrNull { it.first == k }?.second ?: k
        /** 상황별 아이디어 30 + 특별한 순간 7 — `--es idea <id>`(SIM · STAGE) · 디버그 판 · 허브 '아이디어' */
        val IDEAS = listOf(
            Idea("air", Sit.IGNITION, "미세먼지 → 내기 순환"), Idea("fortune", Sit.IGNITION, "오늘의 차 운세"), Idea("rival", Sit.IGNITION, "어제의 나와 비교"),
            Idea("routine", Sit.IGNITION, "출발 전 30초 루틴"), Idea("bundle", Sit.IGNITION, "출발 전 한눈에"), Idea("weekly", Sit.IGNITION, "주간 리포트"), Idea("charge", Sit.IGNITION, "충전 계획"),
            Idea("habit", Sit.READY, "습관 경로 제안"), Idea("depart", Sit.READY, "출발 시각 코치"), Idea("crew", Sit.READY, "동승자 편성"),
            Idea("doors", Sit.READY, "창문·문 점검"), Idea("launch", Sit.READY, "초보 출발 카운트"),
            Idea("combo", Sit.DRIVE, "부드러운 운전 콤보"), Idea("brakecoach", Sit.DRIVE, "브레이크 칭찬"), Idea("drowsy", Sit.DRIVE, "졸음 기척"),
            Idea("alert", Sit.DRIVE, "기상 특보"), Idea("arrive10", Sit.DRIVE, "도착 10분 전"), Idea("dj", Sit.DRIVE, "라디오 DJ"),
            Idea("quiz", Sit.STOP, "동네 이름 퀴즈"), Idea("score", Sit.STOP, "경기 점수 한 줄"), Idea("signal", Sit.STOP, "신호 길이 감"), Idea("stretch", Sit.STOP, "스트레칭 한 동작"),
            Idea("spot", Sit.PARK, "주차 위치 기억"), Idea("highlight", Sit.PARK, "주행 하이라이트 컷"), Idea("wait", Sit.PARK, "기다림 모드"),
            Idea("nearby", Sit.PARK, "근처 한 곳"), Idea("wash", Sit.PARK, "세차 타이밍"),
            Idea("stamp", Sit.OFF, "별 도장"), Idea("morning", Sit.OFF, "내일 아침 예약"), Idea("leftbehind", Sit.OFF, "두고 내림 감지"), Idea("progress", Sit.OFF, "꾸미기 진척"),
            Idea("first", Sit.IGNITION, "오늘 첫 주행", true), Idea("long", Sit.PARK, "장거리 완주", true), Idea("night", Sit.DRIVE, "늦은 밤", true),
            Idea("rain", Sit.IGNITION, "비·눈 오는 날", true), Idea("birthday", Sit.IGNITION, "생일", true), Idea("hundred", Sit.PARK, "100번째 주행", true),
            Idea("star1000", Sit.OFF, "별 1,000개", true))
        val LEVELS = listOf("견습 내비게이터", "새내기 길동무", "든든한 길동무", "골목 박사", "부드러운 손", "정속의 달인", "도로 위 신사", "안전 장인", "베테랑 내비게이터", "전설의 동승자")
        /** 2차 추천 24(docs/hyun-content-ideas.md "2차 추천") */
        val IDEAS2 = listOf(
            Idea("kidquiz", Sit.DRIVE, "뒷좌석 아이 퀴즈", round = 2, theme = "동승자"), Idea("date", Sit.IGNITION, "데이트 모드", round = 2, theme = "동승자"),
            Idea("parent", Sit.READY, "부모님 모드", round = 2, theme = "동승자"), Idea("childlock", Sit.READY, "아이 잠금 확인", round = 2, theme = "동승자"),
            Idea("holiday", Sit.IGNITION, "명절 귀성 플래너", round = 2, theme = "계절"), Idea("firstsnow", Sit.IGNITION, "첫눈", true, 2, "계절"),
            Idea("blossom", Sit.PARK, "벚꽃 코스", round = 2, theme = "계절"), Idea("heat", Sit.IGNITION, "폭염 출발 루틴", round = 2, theme = "계절"),
            Idea("suneung", Sit.IGNITION, "수능 응원", true, 2, "계절"),
            Idea("level", Sit.PARK, "현비서 레벨", round = 2, theme = "캐릭터"), Idea("mood", Sit.OFF, "운전에 반응하는 기분", round = 2, theme = "캐릭터"),
            Idea("anniv", Sit.IGNITION, "우리 기념일", round = 2, theme = "캐릭터"), Idea("memory", Sit.READY, "그때 기억나요", round = 2, theme = "캐릭터"),
            Idea("cost", Sit.OFF, "충전·주유비", round = 2, theme = "차 관리"), Idea("service", Sit.IGNITION, "정비 알림", round = 2, theme = "차 관리"),
            Idea("tireseason", Sit.IGNITION, "겨울 타이어", round = 2, theme = "차 관리"), Idea("ubi", Sit.OFF, "보험 할인 예상", round = 2, theme = "차 관리"),
            Idea("sharecard", Sit.OFF, "주간 공유 카드", round = 2, theme = "공유"), Idea("league", Sit.PARK, "온기 리그", round = 2, theme = "공유"),
            Idea("commute", Sit.IGNITION, "출근·퇴근 모드", round = 2, theme = "출퇴근"), Idea("parkhunt", Sit.DRIVE, "주차장 찾기", round = 2, theme = "출퇴근"),
            Idea("schoolzone", Sit.DRIVE, "스쿨존", round = 2, theme = "출퇴근"),
            Idea("voicegame", Sit.DRIVE, "말 게임", round = 2, theme = "놀이·글레오"), Idea("senior", Sit.IGNITION, "시니어 모드", round = 2, theme = "놀이·글레오"),
            Idea("gleo3", Sit.PARK, "글레오 함수 3개", round = 2, theme = "놀이·글레오"))
        val ALL_IDEAS get() = IDEAS + IDEAS2
        /** 시동 화면에서 넘겨 보는 장(순서대로). 뺀 것 —
         *  기본 화면 카드와 겹침: news · stock(카드를 누르면 열림) · nav(맨 위 '어디로 갈까요?')
         *  날씨 갈래는 '날씨·차림' 한 장으로: air · rain · firstsnow · heat
         *  겹치는 리포트·차 관리: rival(→ weekly) · service · tireseason(→ charge · 내 차)
         *  특별한 날·모드(그날·그 설정일 때만 뜰 것): first · birthday · suneung · holiday · anniv · date · commute · senior */
        val IGNITION_PAGES = listOf("weather")   // bundle = 주간 리포트 · 충전 계획 · 출발 전 루틴 · 동네 이야기 한 장 · 운세(fortune)는 뺌
        /** 페이저 한 장 — auto(기본 상황 화면) · cat(취미 레이아웃) · idea(아이디어) */
        data class Page(val kind: String, val id: String, val title: String)
        /** 상황마다 넘겨 볼 화면들 — 1장 = 기본 화면, 이어서 그 상황에서 허용된 취미 레이아웃, 그 상황의 아이디어(1차·2차).
         *  운전 중(출발 준비 D · 주행 · 신호대기)은 한눈 규칙이라 취미 레이아웃은 빼고 기본 + 아이디어만 */
        fun pages(s: Sit): List<Page> {
            val out = mutableListOf(Page("auto", "", "${s.label} 기본"))
            if (s == Sit.IGNITION) {   // 시동은 28장 → 10장(칼리 11-12): 기본 화면 카드와 겹치는 것 · 날씨 갈래 · 특별한 날 · 모드는 넘겨 보기에서 뺀다(없앤 건 아님 — `--es idea <id>` · 카드 누르기)
                IGNITION_PAGES.forEach { id -> CATS.firstOrNull { it.first == id }?.let { out += Page("cat", it.first, it.second) } ?: ALL_IDEAS.firstOrNull { it.id == id }?.let { out += Page("idea", it.id, it.name) } }
                return out
            }
            // 다른 상황은 기본 화면 한 장만 — 아이디어 장은 대부분 예시 값이라 넘겨 보기에서 뺐다(코드·`--es idea <id>` 시연은 그대로 · 칼리 11-12 마무리)
            return out
        }
        /** 평소 넘겨 보는 장에 넣는 아이디어 — 실제 데이터로 도는 것만(11-11 가짜 감사: 예전엔 아이디어 62장이 조건 없이 다 들어가
         *  '예시'·고정 값 화면이 평소 운전 중에도 나왔다). 나머지는 허브 미리보기(--es idea)로만 */
        val REAL_IDEAS = setOf("fortune", "first", "routine", "launch", "stretch", "level", "stamp", "progress", "mood")
        /** 조건이 맞을 때만 실제인 아이디어(조수석 착석 · 뒷좌석 착석 · 비) — StageFeature.refresh가 채운다 */
        @Volatile var liveIdeas: Set<String> = emptySet()
        /** 예시 데이터뿐인 취미 장(음악·스포츠·동네 이야기·일정) — 평소엔 빼고 미리보기로만 */
        val DEMO_CATS = setOf("music", "sports", "local", "schedule")
        fun idea(id: String?) = ALL_IDEAS.firstOrNull { it.id == id }
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
    private var smoothM = 0
    private var brakeOn = false
    private var settled = false
    private var tripStartWall = 0L            // 이번 주행 출발 시각(벽시계) — 자주 가는 곳 학습
    private var tripDest: String? = null      // 이번 주행에 내비가 읽은 목적지(도착하면 비워지기 전 값)
    private var tripDestAt: Pair<Double, Double>? = null   // 그 목적지 좌표(실제로 간 곳 배우기 — 자주 가는 곳)
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
    private var tipIdx = 0
    private var turnWarned = ""

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { hub.main.post { if (i.action == SIM_ACTION) simIntent(i) else apply(i) } }
    }

    override fun start(hub: Hyun) {
        this.hub = hub
        val pr = hub.prefs
        m.skill = pr.getString("stage.skill", null)
        m.points = pr.getInt("stage.points", 0); m.xp = pr.getInt("stage.xp", m.points); m.mood = pr.getInt("stage.mood", 60)
        m.concept = pr.getString("stage.concept", "pleos")!!; m.face = pr.getString("stage.face", "neutral")!!; m.acc = pr.getString("stage.acc", "")!!
        m.owned = pr.getStringSet("stage.owned", m.owned)!!.toSet() + m.owned
        m.decor = pr.getStringSet("stage.decor", emptySet())!!.toSet()
        pr.edit().remove("stage.avatar").apply()   // 시험용 아바타 선택(robo/puff) 흔적 지우기
        pr.getString("stage.equip", "")!!.split(',').filter { ':' in it }.forEach { m.equipped[it.substringBefore(':')] = it.substringAfter(':') }
        if (pr.getInt("stage.weekNo", -1) == Calendar.getInstance().get(Calendar.WEEK_OF_YEAR)) m.weekPoints = pr.getInt("stage.week", 0)
        if (pr.getString("stage.questDay", "") == dayKey(Calendar.getInstance())) { m.questDone = pr.getBoolean("stage.questDone", false); m.questKm = pr.getFloat("stage.questKm", 0f).toDouble() }   // 오늘 퀘스트만 이어 간다
        status = CarStatus(hub.act).also { runCatching { it.start() }; m.status = it }
        ui = StageUi(hub.view, m, ::act).also { hub.view.stage = it }
        val f = IntentFilter(ACTION).apply { addAction(SIM_ACTION) }
        runCatching { if (Build.VERSION.SDK_INT >= 33) hub.act.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED) else hub.act.registerReceiver(receiver, f) }
            .onFailure { hub.log("stage|receiver|${it.message}") }
        val need = CAR_PERMS.filter { hub.act.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) runCatching { hub.act.requestPermissions(need.toTypedArray(), Hyun.REQ_PERMS) }
        if (!pr.getBoolean("stage.onboarded", false)) { hub.holdHello = true; startOnboarding() }
        else { hub.holdHello = true; hub.main.postDelayed({ helloAfterCar(0) }, 1500) }   // 첫마디는 차 값(누적 주행거리)을 읽은 뒤 — 엔진오일 알림이 제안보다 먼저(소개자료 6장)
        enter(Sit.IGNITION, why = "앱 시작")
        fetchWeather(); fetchNews(); fetchRss(); fetchFeeds()
    }

    override fun stop() { runCatching { hub.act.unregisterReceiver(receiver) }; status?.stop() }

    override fun onIntent(i: Intent, first: Boolean) { if (i.hasExtra("oil") || i.hasExtra("rest") || i.hasExtra("restsec") || i.hasExtra("restmin") || i.hasExtra("sit") || i.hasExtra("screen") || i.hasExtra("skill") || i.hasExtra("points") || i.hasExtra("reset") || i.hasExtra("turn") || i.hasExtra("steer") || i.hasExtra("cruise") || i.hasExtra("concept") || i.hasExtra("preview") || i.hasExtra("night") || i.hasExtra("sim") || i.hasExtra("hero") || i.hasExtra("equip") || i.hasExtra("tab") || i.hasExtra("nav") || i.hasExtra("idea") || i.hasExtra("moment") || i.hasExtra("page")) hub.main.postDelayed({ apply(i) }, if (first) 2500 else 0) }

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
        (b.getString("idea") ?: b.getString("moment"))?.let { setIdea(it) }
        b.get("page")?.toString()?.let { page(it) }
        b.getString("screen")?.let { setScreen(it) }
        b.getString("golog")?.let { if (it == "demo") seedGoLog() else if (it == "clear") hub.prefs.edit().remove(GO_LOG).apply() }   // 시연: 자주 가는 곳 기록
        refresh(); hub.render()
    }

    /** 시연 extra — 방송·인텐트 공통 */
    private fun apply(i: Intent) {
        if (i.getBooleanExtra("reset", false)) {
            hub.prefs.edit().remove("stage.onboarded").remove("stage.skill").remove("stage.points").remove("stage.owned").remove("stage.concept").remove("stage.face").remove("stage.acc").remove("stage.decor").remove("stage.equip").apply()
            m.questKm = 0.0; m.questDone = false
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
        if (i.hasExtra("oil")) { oilDemoLeft = i.getIntExtra("oil", 800).takeIf { it >= -5000 }; oilSaidDay = -1; hub.prefs.edit().remove("oil.snoozeUntil").apply(); oilUpdate() }   // 시연: 엔진오일 남은 거리(km)
        if (i.hasExtra("restmin")) restMinDrive = i.getIntExtra("restmin", 60)   // 시연: 먼저 권하는 기준(분)
        if (i.hasExtra("restsec")) restSec = i.getIntExtra("restsec", 0)   // 시연: 쉼표 길이를 초로 줄임
        i.getStringExtra("rest")?.let { when (it) { "offer" -> restOffer(); "arrive" -> { m.restAccepted = true; restChoose() }; "15" -> restStart(15); "30" -> restStart(30); "end" -> restEnd(false) } }   // 시연: 쉼표 단계 바로 가기
        if (i.action == ACTION) i.getStringExtra("skin")?.let { setSkin(it) }
        (i.getStringExtra("idea") ?: i.getStringExtra("moment"))?.let { setIdea(it); refresh(); hub.render(); return }
        i.getStringExtra("page")?.let { page(it); return }
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
            if (!m.questDone) {
                m.questKm += v / 1000.0
                if (m.questKm >= QUEST_KM) {
                    m.questDone = true; award("🎯", "퀘스트 · 급정거 없이 10km", QUEST_STARS)
                    hub.say("오늘의 퀘스트 성공! 급정거 없이 10킬로미터, 별 ${QUEST_STARS}개 드렸어요.", "happy", false, 2, tag = "퀘스트")
                }
            }
            if (m.dayStartAt == 0L) m.dayStartAt = System.currentTimeMillis()
            if (pointM >= 100) { pointM -= 100; m.tripPoints += 1; addPoints(1); smoothM += 100; if (smoothM >= 1000) { smoothM = 0; moodBy(3, "부드럽게 1km") } }
            settled = false
        }
        val brk = m.status?.brake?.v == true
        if (brk && !brakeOn && v > 3f && lastSpeed - v in 0.3f..2.5f) moodBy(1, "브레이크 미리, 부드럽게")   // Pleos 브레이크 + 감속 폭이 작으면 칭찬
        brakeOn = brk
        if (lastSpeed - v > HARSH_MS && lastSpeed > 4f) {
            harsh++; hub.log("stage|harsh|${"%.1f".format(lastSpeed)}→${"%.1f".format(v)}")
            if (!m.questDone && m.questKm > 0.0) { hub.log("stage|quest|급정거 → 처음부터(${"%.1f".format(m.questKm)}km)"); m.questKm = 0.0 }
            penalty("⚠️", "급정거", if (m.novice) 2 else 5); moodBy(-15, "급정거")
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
                turnSay(t.type)?.let { hub.say("${(t.m + 50) / 100 * 100}미터 앞 $it", "", false, 1, tag = "초보·회전") }
            }
            if (!restSaid && hub.trip.driveS > NOVICE_REST_S) { restSaid = true; hub.say("50분 가까이 달렸어요. 다음 쉼터에서 잠깐 쉬어 가요.", "care", false, 1, tag = "초보·쉼표") }
        }
        restTick()
        if (m.sit == Sit.DRIVE && m.overlay != null && m.overlay != "debug") m.overlay = null   // 주행 중엔 덮개 화면을 닫는다
        m.view?.let { vw -> if (!m.viewForced && !StageModel.allowed(vw, m.sit)) { m.view = null; hub.log("stage|view|$vw 닫음(${m.sit.key})") } }   // 허용 규칙(미리보기는 예외)
        status?.let { st -> if (pollN++ % 2 == 0) Thread { st.poll() }.start(); st.appTrip(tripS / 60, (tripM / 1000).toFloat()) }
        hub.view.idleFace = if (m.face != "neutral") m.face else when { m.mood >= 75 -> "humming"; m.mood < 40 -> "worried"; else -> m.look().idleFace }   // 기분이 표정으로
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
        // 모의 제한속도로 감점·"○○킬로 구간이에요"를 말하던 것 → 시연 모의 경로(SIM nav on)일 때만. 실제 제한속도(NaviHelper 콜백)는 에뮬 미수신이라 아직 0(가짜 감사 #4)
        val limit = if (m.simNav && m.mockRoute) mockLimit() else 0
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
            if (now - signalAt < 5_000) { award("💡", "깜빡이 켜고 회전", 1); moodBy(2, "깜빡이 켜고 회전") } else { penalty("↪️", "깜빡이 없이 회전", if (m.novice) 1 else 2); moodBy(-8, "깜빡이 없이 회전") }
        }
        // 크루즈 — 일정하게 1분 쓰면 +5(주행당 한 번)
        if (st?.cruise?.v == "작동") cruiseS++
        if (!cruiseGiven && cruiseS >= 60) { cruiseGiven = true; award("◎", "크루즈로 일정하게", 5); moodBy(5, "크루즈로 일정하게") }
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
        if (n > 0) { val lv0 = m.level; m.xp += n; if (m.level > lv0) { hub.say("레벨 업! 이제 ${m.levelTitle}예요.", "happy", false, 2, tag = "레벨"); m.reactAt = SystemClock.elapsedRealtime(); m.reactFace = "cheer" } }
        m.points = (m.points + n).coerceAtLeast(0)
        m.todayPoints += n; m.weekPoints = (m.weekPoints + n).coerceAtLeast(0)
        hub.prefs.edit().putInt("stage.week", m.weekPoints).putInt("stage.weekNo", Calendar.getInstance().get(Calendar.WEEK_OF_YEAR)).apply()
    }
    private fun award(icon: String, why: String, n: Int) { m.ledger += Entry(icon, why, n); addPoints(n); hub.log("stage|point|+$n|$why"); save() }
    private fun penalty(icon: String, why: String, n: Int) { penalties++; m.ledger += Entry(icon, why, -n); addPoints(-n); hub.log("stage|point|-$n|$why"); save() }

    /** 초보 회전 예고 — 회전만 알린다(주행 중 차로 바꾸기 안내는 뺐다) */
    private fun turnSay(type: String) = when (type) {
        "TURN_LEFT", "TURN_SHARP_L", "TURN_UTURN", "TURN_KEEP_L" -> "${if (type == "TURN_UTURN") "유턴" else "좌회전"}이에요."
        "TURN_RIGHT", "TURN_SHARP_R", "TURN_KEEP_R" -> "우회전이에요."
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
            k == "auto" -> { m.view = null; m.viewForced = false; if (m.forced) { m.forced = false; lastKey = "" }; m.overlay = m.overlay.takeIf { it == "onboard" }
                if (m.idea != null) { if (m.idea == "night") m.nightOverride = null; m.idea = null; m.forceRain = false; m.forceSnow = false } }
            Sit.values().any { it.key == k } -> { val s = Sit.values().first { it.key == k }; m.view = null; m.viewForced = true; m.overlay = null; m.forced = true; if (s == Sit.DRIVE) startTripIfNeeded(); enter(s, why = "미리보기") }
            StageModel.CATS.any { it.first == k } -> { m.view = k; m.viewForced = true; m.overlay = null }
        }
    }

    /** 아이디어 미리보기 — 그 아이디어의 상황으로 강제 전환(실제 주행 신호가 와도 아이디어는 남는다). rain · night 는 날씨·밤을 함께 */
    fun setIdea(id: String) {
        if (id == "off" || id == "none") { setScreen("auto"); return }
        val d = StageModel.idea(id) ?: run { hub.log("stage|idea|모름|$id"); return }
        if (m.idea == "night" && id != "night") m.nightOverride = null
        m.forceRain = id == "rain"; m.forceSnow = id == "firstsnow"
        if (id == "night") m.nightOverride = true
        setScreen(d.sit.key)
        m.idea = id
        hub.log("stage|idea|$id|${d.sit.key}")
    }

    // ---------- 페이저 — 상황 안의 화면들을 넘겨 본다(정차·시동은 밀기·버튼, 운전 중은 밀기·말 "다음/이전") ----------
    fun pageIdx(): Int = StageModel.pages(m.sit).indexOfFirst { (it.kind == "auto" && m.view == null && m.idea == null) || (it.kind == "cat" && it.id == m.view) || (it.kind == "idea" && it.id == m.idea) }.coerceAtLeast(0)
    private fun clearIdeaFx() { if (m.idea == "night") m.nightOverride = null; m.forceRain = false; m.forceSnow = false }
    fun goPage(i0: Int, dir: Int) {
        val ps = StageModel.pages(m.sit); if (ps.isEmpty()) return
        val pg = ps[((i0 % ps.size) + ps.size) % ps.size]
        clearIdeaFx(); m.view = null; m.idea = null
        when (pg.kind) {
            "cat" -> { m.view = pg.id; m.viewAt = SystemClock.elapsedRealtime() }
            "idea" -> { m.idea = pg.id; m.forceRain = pg.id == "rain"; m.forceSnow = pg.id == "firstsnow"; if (pg.id == "night") m.nightOverride = true }
        }
        m.overlay = m.overlay.takeIf { it == "onboard" }
        m.pageDir = dir; m.pageAt = SystemClock.elapsedRealtime()
        hub.log("stage|page|${m.sit.key}|${ps.indexOf(pg) + 1}/${ps.size}|${pg.kind}:${pg.id}")
        refresh(); hub.render()
    }
    fun page(spec: String) = when (spec) {
        "next" -> goPage(pageIdx() + 1, 1); "prev" -> goPage(pageIdx() - 1, -1)
        else -> spec.toIntOrNull()?.let { goPage(it - 1, if (it - 1 >= pageIdx()) 1 else -1) } ?: Unit
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
        // 지난 상황의 팁·말이 줄에 남아 뒤늦게 나오지 않게(11-11 영상: 주차 중 '신호 대기 중엔…', 주행 중 '출발 순서예요')
        if (prev != s) hub.dropQueued("상황 바뀜 ${prev.key}→${s.key}") { t ->
            (t.startsWith("상황·") && t != "상황·${s.key}") || (s != Sit.DRIVE && s != Sit.STOP && (t == "초보·차로" || t == "초보·과속"))
        }
        hub.view.faceOverride = null
        if (why != "미리보기" && !m.viewForced && prev != s) { clearIdeaFx(); m.idea = null; m.view = null; m.pageDir = 0 }   // 상황이 바뀌면 그 상황의 1장부터(허브 미리보기는 예외)
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
            Sit.READY -> if (m.novice) "자, 출발해 볼까요? 브레이크 꾹 밟고, D로 바꾼 다음, 발을 천천히 떼면 돼요." else null
            Sit.DRIVE -> if (m.novice) "출발! 앞차랑 간격은 넉넉하게요. 길은 제가 미리미리 알려 줄게요." else null
            Sit.STOP -> stopLine()   // 동네 이야기 = 지금 지역명에 맞춤(고정 판교 문장은 뺐다 — 가짜 감사 #5) + 초보 팁
            Sit.PARK -> if (m.restAccepted) { restChoose(); null } else parkLine()
            Sit.OFF -> offLine()
        }
        // 시동: 브리핑 영역을 말로 고르게 — 듣기 켜기
        line?.let { hub.say(it, if (s == Sit.PARK) "happy" else "", s == Sit.IGNITION, if (s == Sit.STOP) 3 else 2, tag = "상황·${s.key}") }
    }

    private fun newTrip() {
        tripStartWall = System.currentTimeMillis(); tripDest = hub.view.dest; tripDestAt = hub.feature<RouteFeature>()?.let { r -> r.destLat?.let { la -> r.destLng?.let { lo -> la to lo } } }
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
                // 괜찮은 값만 '좋아요' — 주의 값(배터리 낮음 등)은 확인하라고(11-11: "주행 가능 0km, 좋아요")
                val ok = listOfNotNull(e, r).filter { it.state == CarCheck.State.OK }.joinToString(", ") { "${it.label} ${it.value}" }
                val warn = listOfNotNull(e, r).filter { it.state == CarCheck.State.WARN }.joinToString(", ") { "${it.label} ${it.value}" }
                val sum = (if (ok.isNotBlank()) "$ok, 좋아요. " else "") + (if (warn.isNotBlank()) "$warn, 한 번 확인해 주세요. " else "")
                hub.say("안녕, 저 현비서예요! 차부터 같이 살펴봐요. " + sum + "타이어 공기압은 계기판에서 한 번만 봐 줘요.", "happy", false, 1, tag = "온보딩·점검")
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
            hub.say(if (s == "novice") "초보 모드로 맞췄어요. 출발, 정차, 주차까지 하나씩 말로 챙길게요. 환영 포인트 100점도 드렸어요."
                    else "숙련 모드로 맞췄어요. 꼭 필요한 것만 짧게 말할게요. 환영 포인트 100점은 꾸미기에 써 보세요.", "happy", false, 1, tag = "온보딩·완료")
            val gen = onboardGen
            hub.main.postDelayed({ if (gen == onboardGen && m.overlay == "onboard" && m.onboardStep == 2) finishOnboarding() }, 12_000)
        } else if (say) hub.say(if (s == "novice") "초보 모드로 바꿨어요. 더 자주, 천천히 챙길게요." else "숙련 모드로 바꿨어요. 꼭 필요한 말만 할게요.", "", false, 1, reply = true, tag = "숙련도")
        refresh()
    }

    /** 시동 첫마디 — 누적 주행거리(Pleos)를 읽거나 6초가 지나면. 그전에 말하면 엔진오일 알림을 놓쳤다(11-14 실측: 인사 3초 · 첫 차 값 3.4초) */
    private fun helloAfterCar(n: Int) {
        if (m.overlay == "onboard") return
        val ready = m.status?.odoKm?.v != null || n >= 6
        if (!ready) { hub.main.postDelayed({ helloAfterCar(n + 1) }, 1000); return }
        hub.holdHello = false
        hub.log("stage|hello|odo=${m.status?.odoKm?.v}|${n}s")
        if (m.sit == Sit.IGNITION) enter(Sit.IGNITION, why = "차 읽음 · 첫마디") else hub.fire("hello")
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
            a == "oil:later" -> oilLater()
            a.startsWith("rest:") -> when (a.substringAfter(":")) { "15" -> restStart(15); "30" -> restStart(30); "more" -> restMore(); "end" -> restEnd(false); else -> { m.overlay = null; m.restAccepted = false } }
            a.startsWith("hero:") -> { m.heroIdx = a.substringAfter(":").toInt(); m.heroAt = SystemClock.elapsedRealtime() + 20_000; m.reactAt = SystemClock.elapsedRealtime(); m.reactFace = "happy" }
            a.startsWith("say:") -> hub.heard(a.substringAfter(":"))
            a.startsWith("sim:") -> { m.status?.sim(a.substringAfter(":")) }
            a.startsWith("acc:") -> wearAcc(a.substringAfter(":"))
            a.startsWith("place:") -> a.substringAfter(":").let { id -> m.decor = if (id in m.decor) m.decor - id else m.decor + id; save() }
            a.startsWith("listen:") -> hub.view.onListenMode(a.endsWith("always"))
            a == "card:close" -> hub.view.card = null
            a.startsWith("fav:") -> favs().getOrNull(a.substringAfter(":").toIntOrNull() ?: -1)?.let { favSel = it.name }   // 즐겨찾기 고르기
            a.startsWith("go:") -> goFav(a.substringAfter(":").toIntOrNull() ?: -1)                                       // 고른 곳으로 안내
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
            a == "idea:next" || a == "idea:prev" -> { val l = StageModel.ALL_IDEAS; val i0 = l.indexOfFirst { it.id == m.idea }; val n = if (a.endsWith("next")) (i0 + 1) % l.size else (if (i0 <= 0) l.size - 1 else i0 - 1); setIdea(l[n].id) }
            a == "idea:off" -> setScreen("auto")
            a.startsWith("page:") -> page(a.substringAfter(":"))
            a == "skintab" -> if (!movingNow()) { m.overlay = "deco"; m.decoTab = "skin" }
            a.startsWith("skin:") -> setSkin(a.substringAfter(":"))
            a == "news:read" -> readNews()
            a == "news:next" -> if (m.newsList.size > 1) m.newsList = m.newsList.drop(1) + m.newsList.first()
            a.startsWith("media:") -> mediaKey(a.substringAfter(":"))
        }
        refresh(); hub.render()
    }

    /** 헤드라인 읽기 — 이미 받아 둔 연합뉴스 RSS를 앱이 직접 읽는다(11-10: 두뇌로 보내서 서버가 꺼져 있으면 "생각 회로가 연결이 안 됐어요"로 끝났다) */
    private fun readNews() {
        val lead = m.newsList.firstOrNull { m.newsImgs[it] != null } ?: m.newsList.firstOrNull()
        if (lead == null) { toast("헤드라인을 아직 못 받았어요"); return }
        val all = listOf(lead) + m.newsList.filter { it != lead }.take(2)
        hub.say("연합뉴스 헤드라인이에요. " + all.joinToString(". ") { it.trim().trimEnd('.') } + ".", "", false, 1, reply = true, tag = "뉴스")
    }

    /** 음악 버튼 — 차 미디어 키(이전 · 재생/정지 · 다음). 재생 중인 앱(미디어 세션)이 받는다 */
    private fun mediaKey(k: String) {
        val code = when (k) { "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE }
        val am = hub.act.getSystemService(AudioManager::class.java)
        runCatching { am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); am?.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code)) }
            .onFailure { hub.log("stage|media|FAIL|${it.message}") }
        hub.log("stage|media|$k|music=${am?.isMusicActive}")
        toast(when (k) { "prev" -> "이전 곡"; "next" -> "다음 곡"; else -> if (am?.isMusicActive == true) "일시정지" else "재생" })
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

    private fun moodBy(d: Int, why: String) { val o = m.mood; m.mood = (m.mood + d).coerceIn(0, 100); m.moodWhy = why; if (o != m.mood) hub.log("stage|mood|$o→${m.mood}|$why") }
    private fun save() = hub.prefs.edit().putInt("stage.xp", m.xp).putInt("stage.mood", m.mood).putInt("stage.points", m.points).putStringSet("stage.owned", m.owned)
        .putString("stage.questDay", dayKey(Calendar.getInstance())).putBoolean("stage.questDone", m.questDone).putFloat("stage.questKm", m.questKm.toFloat())
        .putString("stage.concept", m.concept).putString("stage.face", m.face).putString("stage.acc", m.acc).putStringSet("stage.decor", m.decor).putString("stage.equip", m.equipped.entries.joinToString(",") { "${it.key}:${it.value}" }).apply()

    // ======================================================================
    // 말 명령 — 숙련도 · 꾸미기 · 포인트 · 점검 (온보딩 질문 중엔 넓게 받는다)
    // ======================================================================
    override fun onHeard(text: String): Boolean {
        val t = text.replace(" ", "")
        playHeard(t)?.let { return it }
        if (Regex("^(다음|이전|앞)(화면|장|페이지)?(보여줘|으로|넘겨)?$").matches(t)) { page(if (t.startsWith("다음")) "next" else "prev"); hub.say(StageModel.pages(m.sit)[pageIdx()].title, "", false, 2, reply = true, tag = "페이지"); return true }
        // 글레오 함수 후보 — 안전점수 · 꾸미기. "브리핑해·오늘 뭐 있어"는 가로채지 않는다(고정 답에 가짜 일정 "첫 일정은 9시 반 주간 회의"가 섞였다 — 가짜 감사 #6) → 코어 브리핑·두뇌가 받는다
        if (Regex("운전(어땠|점수)|안전점수|내점수").containsMatchIn(t)) {
            m.gleoLast = "안전점수"
            hub.say("오늘 급정거 ${harsh}번, 별 ${sign(m.todayPoints)}개예요. 저는 지금 ${m.moodLabel} 기분이고, 레벨 ${m.level} ${m.levelTitle}예요.", "happy", false, 1, reply = true, tag = "글레오·점수"); return true }
        StageModel.ACC_DEFS.firstOrNull { t.contains(it.name.replace(" ", "")) && Regex("씌워|입혀|달아|끼워|해줘").containsMatchIn(t) }?.let { d ->
            m.gleoLast = "꾸미기 · ${d.name}"
            if (movingNow()) { hub.say("${d.name}은 차를 세우면 바로 해 드릴게요.", "", false, 1, reply = true, tag = "글레오·꾸미기"); return true }
            wearAcc(d.id); hub.say("${d.name} 했어요. 어때요?", "happy", false, 1, reply = true, tag = "글레오·꾸미기"); refresh(); hub.render(); return true }
        // "23도로 올려 줘" — 공조 설정 온도(실제로 바뀐다 · 11-11 에뮬 실측). 16~30도만
        Regex("(\\d{2})도로?(올려|내려|맞춰|낮춰|해)").find(t)?.let { mt -> val c = mt.groupValues[1].toFloat(); if (c in 16f..30f) { pending = null; setCabinSay(c); return true } }
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
            Regex("즐겨찾기(에|로)?(추가|등록|넣어|저장|해)").containsMatchIn(t) -> { hub.say(addFav(), "happy", false, 1, reply = true, tag = "즐겨찾기"); return true }
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
                say(if (m.questDone) "오늘 퀘스트는 벌써 성공했어요. 별 ${QUEST_STARS}개 드렸어요."
                    else "오늘의 운전 퀘스트는 급정거 없이 10킬로미터예요. 지금 %.1f킬로미터째예요. 급정거하면 처음부터고, 성공하면 별 ${QUEST_STARS}개를 드려요.".format(m.questKm))
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
        // 즐겨찾기(이름·좌표) — 두뇌가 "집에 가자"·"세탁소 들렀다 집" 같은 말을 길로 바꿀 수 있게(11-10: "집 위치를 몰라요"라고 되물었다)
        val fl = favs()
        c.put("favs", org.json.JSONArray(fl.map { JSONObject().put("name", it.name).put("lat", it.lat).put("lng", it.lng) }))
        if (fl === DEMO_FAVS) c.put("favsDemo", true)
        if (m.goName.isNotBlank()) c.put("favNow", m.goName)
    }

    // ---------- 신호대기 동네 이야기 — 지금 지역명(NaviHelper 현재 위치 주소)에 맞춘 한마디. 두뇌가 있으면 두뇌가 그 동네 이야기, 없으면 규칙 문장 ----------
    private var localSaidAt = 0L; private var localSaidArea = ""
    /** 지금 지역 — (구까지 전체 "수정구 시흥동", 짧게 "시흥동"). 주소를 아직 못 읽었으면 null */
    private fun localArea(): Pair<String, String>? {
        val a = hub.addr?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return a to a.split(' ').last()
    }
    /** 규칙 문장(사실을 지어내지 않는다 — 지역명 + 지금 할 수 있는 것) */
    private fun localLine(a: Pair<String, String>): String {
        val w = m.weatherShort.takeIf { it.isNotBlank() }
        return listOf(
            "지금 ${a.second} 지나는 중이에요. 신호 기다리는 동안 웹툰이나 영화 순위 물어봐도 돼요.",
            "여기는 ${a.first}예요." + (w?.let { " 바깥은 ${it}이에요." } ?: "") + " 이 동네 맛집이 궁금하면 \"현비서야, 근처 맛집\"이라고 해 봐요.",
            "${a.second} 신호 대기 중이에요. 오늘 주요 뉴스나 주식, 말로 물어보면 바로 보여 줄게요.",
        )[(m.stopsToday % 3 + 3) % 3]
    }
    /** 신호대기 들어올 때 — 초보 팁 + (같은 동네엔 10분에 한 번) 동네 이야기. 두뇌가 켜져 있으면 지역명을 주고 두뇌에게 맡긴다 */
    private fun stopLine(): String? {
        val tip = if (m.novice && tipIdx++ % 2 == 0) "신호 대기 중엔 브레이크를 계속 꾹." else null
        val a = localArea() ?: return tip
        val now = SystemClock.elapsedRealtime()
        if (a.first == localSaidArea && now - localSaidAt < 10 * 60_000L) return tip
        localSaidArea = a.first; localSaidAt = now
        if (hub.brainOn) { hub.fire("stop_local", JSONObject().put("area", a.first).put("ask", "신호 대기 중 — 이 동네(${a.first})에 맞는 짧은 한마디 하나. 확실한 사실만(모르면 지어내지 말고 일반 이야기), 두 문장 이내, 질문 없이")); return tip }
        return listOfNotNull(tip, localLine(a)).joinToString(" ")
    }

    override fun fallback(ev: String, extra: JSONObject?): String? = when (ev) { "hello" -> ignitionLine(); "stop_local" -> localArea()?.let { localLine(it) }; "long_drive" -> if (restArea() != null && hub.trip.driveS >= restNextS) { restAskAt = SystemClock.elapsedRealtime(); restNextS = hub.trip.driveS + 30 * 60; restOfferLine() } else null; else -> null }

    // ======================================================================
    // 상황별 이야기 — 숫자는 캐릭터의 말로(댄 11-08 "대시보드 말고 컴패니언"). 한 번에 한 이야기(hero) + 작은 칩 1~2
    // Tile 재사용: icon(선 아이콘 이름) · label(칩 제목) · value(캐릭터의 한마디) · sub(작은 근거) · action
    // ======================================================================
    private fun refresh() {
        // 조건이 맞을 때만 실제인 아이디어 장 — 조수석 착석(동승자 편성) · 뒷좌석 착석(두고 내림) · 비(와이퍼·날씨)
        m.status?.let { st -> StageModel.liveIdeas = buildSet { if (st.passenger.v == true) add("crew"); if (st.rearKid.v == true) add("leftbehind"); if (st.wiper.v == true || m.wcode in 51..67 || m.wcode in 80..82) add("rain") } }
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
        val wasNav = m.navActive
        m.navActive = hub.view.dest != null || m.simNav
        // 실제 길 안내가 시작되면 열어 둔 장(뉴스 등)을 닫아 길 쪽으로 — 안내를 시작했는데 뉴스 장이 그대로 남았다(11-11 영상). 미리보기로 강제한 화면은 그대로
        if (!wasNav && hub.view.dest != null && m.view != null && m.view != "nav" && !m.viewForced) { hub.log("stage|view|${m.view} 닫음(길 안내 시작)"); m.view = null }
        if (inTrip) hub.view.dest?.let { tripDest = it; hub.feature<RouteFeature>()?.let { r -> r.destLat?.let { la -> r.destLng?.let { lo -> tripDestAt = la to lo } } } }
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
                       else recommendDest()?.let { (n, sub, habit) -> Tile("pin", "추천 목적지", if (habit) "이 시간엔 보통 ${n}에 가죠? $sub" else "${n} — $sub", "말로 \"${n} 가자\"", "accent", if (habit) "기록" else "즐겨찾기") }
                           ?: Tile("pin", "어디로 갈까요?", "\"코엑스 가자\"처럼 말하면 길을 안내해요.", "목적지는 말로", "accent")
                if (soc != null && soc < 30) out += Tile("battery", "가는 김에 충전", "배터리가 ${soc}%라, 가는 길 충전소에 들렀다 가면 좋아요.", "가는 김에 · 경유지 추가", "warn", action = "say:충전소 들렀다 가자")
                // '가는 김에(세탁물 · 예시)' · '출발 편성(뉴스 5분 → 음악 — 그런 편성 기능 없음)'은 뺐다(가짜 감사 #9 묶음) — 오늘 할 일·브리핑 모듈이 붙으면 그 자리
                tip("브레이크 꾹 → D → 주변 확인 → 발을 천천히. 출발할 땐 깜빡이부터 켜요.")
            }
            Sit.DRIVE -> { m.headline = m.navTurn }
            Sit.STOP -> {
                m.headline = "신호 대기 중"
                // 동네 이야기 = 지금 지역명에 맞춘 한마디(고정 판교 문장은 뺐다) + 정보 안내(말로 물으면 카드가 이 화면에) — 사용자 11-14
                localArea()?.let { a -> out += Tile("chat", "${a.second} 이야기", localLine(a), "신호 기다리는 동안 · \"현비서야, 웹툰 순위\"처럼 물어보세요", "accent") }
                if (m.navTurnType.isNotBlank()) out += Tile("compass", "다음 안내", "다음은 ${hub.view.dist(m.navTurnM)} 앞에서 ${m.navTurn}이에요.", "${m.navEta} · ${m.navDest} ${m.navRemain}", "info", if (m.mockRoute) "모의 경로" else "")
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

    // 첫 값 도착(모름 → 값)은 변화로 세지 않는다 — 앱 켜자마자 "옆자리에 친구가 탔네요"(A 체크리스트 ④-6 · 11-11 영상). 잠깐 못 읽은 순간(null)은 이전 값을 지우지 않는다
    private fun changed(k: String, v: Any?): Boolean {
        if (v == null) return false
        val p = prev[k]; prev[k] = v
        return p != null && p != v
    }
    private fun event(icon: String, text: String) { m.carEvent = icon to text; m.carEventAt = SystemClock.elapsedRealtime() }
    private fun story(t: Tile) {
        reactions.removeAll { it.first.label == t.label }; reactions.add(0, t to SystemClock.elapsedRealtime()); m.heroIdx = 0
        m.heroAt = SystemClock.elapsedRealtime(); m.reactAt = m.heroAt
        m.reactFace = when (t.tone) { "warn" -> "worried"; "good" -> "happy"; else -> "surprised" }
    }
    // ---------- 쉼표(슬립 모드) — docs/specs/rest.md. 카메라가 없으니 '졸음 감지'라고 하지 않는다 — 1시간 넘게 달렸고 앞에 쉼터가 있으면 가볍게 권한다 ----------
    private var restSec = 0                     // 시연용 쉼표 길이(초, 0 = 실제 분)
    private var restAskAt = 0L                  // 쉬어 갈까요? 를 물은 시각 — 짧은 대답을 받는다
    private var restNextS = 0.0                 // 다음 제안까지(연속 주행 초)
    private var restWarned = false
    private var restMinDrive = 60               // 먼저 권하는 기준 — 연속 주행 분(칼리 11-14: 1시간 넘게 달렸고 앞에 쉼터가 있을 때만 가볍게)
    /** 경로 위 다음 쉼터(휴게소·졸음쉼터) — 이름 · 거기까지 m */
    private fun restArea(): Pair<String, Int>? {
        var acc = 0
        for (t in hub.view.turns) { acc += t.m; if (t.type.contains("SERVICEAREA") || t.type.contains("RESTAREA")) return t.desc.substringBefore(" 방면").ifBlank { "쉼터" } to acc }
        return null
    }
    /** 가볍게 권하는 한마디 — 쉼터가 앞에 있으면 이름·거리와 함께 */
    private fun restOfferLine(): String {
        val mins = (hub.trip.driveS / 60).toInt()
        val head = when { mins >= 120 -> "${mins / 60}시간 넘게 달렸네요."; mins >= 60 -> "한 시간 넘게 달렸네요."; else -> "" }
        val a = restArea(); m.restName = a?.first ?: ""
        return (head + if (a != null) " ${hub.view.dist(a.second)} 앞에 ${a.first}가 있는데, 잠깐 쉬었다 갈까요?" else " 다음 쉼터에서 잠깐 쉬었다 갈까요?").trim()
    }
    private fun restOffer() { restAskAt = SystemClock.elapsedRealtime(); hub.log("stage|rest|offer|${(hub.trip.driveS / 60).toInt()}분|${restArea()?.first ?: "쉼터 없음"}"); hub.say(restOfferLine(), "", true, 2, reply = true, tag = "쉼표·제안") }
    private fun restChoose() {
        m.restAccepted = false; m.restEndAt = 0L; m.overlay = "rest"; restAskAt = 0L
        hub.log("stage|rest|choose")
        hub.say("${m.restName.ifBlank { "쉼터" }}에 도착했어요. 15분 쉴까요, 30분 푹 잘까요?", "care", true, 1, reply = true, tag = "쉼표·고르기"); refresh(); hub.render()
    }
    private fun restStart(min: Int) {
        val total = (if (restSec > 0) restSec else min * 60) * 1000L
        m.restMin = min; m.restTotalMs = total; m.restEndAt = SystemClock.elapsedRealtime() + total; m.restCabin = null; m.overlay = "rest"; restWarned = false; restAskAt = 0L
        hub.log("stage|rest|start|$min|${total / 1000}s")
        val st = m.status
        val done: (Map<String, Boolean>) -> Unit = { res -> hub.main.post {
            if (m.restEndAt == 0L) return@post
            m.restCabin = res; hub.log("stage|rest|cabin|$res")
            val did = listOfNotNull("조명을 낮추고".takeIf { res["light"] == true }, "시트를 눕혔어요".takeIf { res["seat"] == true })
            val ask = listOfNotNull("실내등은 꺼 두고".takeIf { res["light"] != true }, "시트는 조금 눕혀 보세요".takeIf { res["seat"] != true })
            hub.say("${min}분 쉼표 시작할게요. " + (if (did.isNotEmpty()) did.joinToString(" ").let { if (res["seat"] == true) "$it. " else "$it 뒀어요. " } else "") + (if (ask.isNotEmpty()) ask.joinToString(" ") + ". " else "") + "시간 되면 깨워 줄게요. 푹 쉬어요.", "care", false, 1, reply = true, tag = "쉼표·시작")
            refresh(); hub.render() } }
        if (st != null) st.restCabin(true, done) else done(mapOf("light" to false, "seat" to false))
        refresh(); hub.render()
    }
    private fun restMore() { if (m.restEndAt == 0L) return; m.restEndAt += 5 * 60_000L; m.restTotalMs += 5 * 60_000L; restWarned = false; hub.say("네, 5분 더 쉬어요.", "", false, 1, reply = true, tag = "쉼표"); refresh(); hub.render() }
    private fun restEnd(natural: Boolean) {
        if (m.restEndAt == 0L && m.overlay != "rest") return
        val was = m.restEndAt != 0L
        m.restEndAt = 0L; m.overlay = null; m.restAccepted = false; restNextS = hub.trip.driveS + 30 * 60
        hub.log("stage|rest|end|${if (natural) "time" else "early"}")
        if (was) { m.status?.restCabin(false) { res -> hub.log("stage|rest|restore|$res") }
            if (natural) award("☕", "쉼표로 쉬어 가기", 5)
            val go = hub.view.dest?.let { d -> " ${d}까지 ${hub.view.remain.ifBlank { "조금" }} 남았어요." } ?: ""
            hub.say(if (natural) "일어날 시간이에요! 기지개 한번 켜고 물 한 모금 마셔요.$go" else "쉼표는 여기까지 할게요. 천천히 출발해요.$go", "happy", false, 1, reply = true, tag = "쉼표·끝") }
        refresh(); hub.render()
    }
    /** 1초마다 — 먼저 권하기 · 쉼표 타이머 · 깨우기 */
    private fun restTick() {
        val now = SystemClock.elapsedRealtime()
        if (m.restEndAt != 0L) {
            val left = m.restEndAt - now
            if (hub.speedMs > hub.moveMs) { restEnd(false); return }   // 차가 움직이면 끝
            if (!restWarned && left <= kotlin.math.min(60_000L, m.restTotalMs / 4)) { restWarned = true; hub.say("곧 일어날 시간이에요.", "", false, 2, tag = "쉼표·깨우기") }
            if (left <= 0) { restEnd(true); return }
            hub.render(); return
        }
        // 먼저 권하기 — 1시간 넘게 달렸고, 경로 앞에 쉼터(휴게소·졸음쉼터)가 있을 때만. 거절하거나 쉬고 나면 30분 뒤에 다시
        if (hub.speedMs > hub.moveMs && m.overlay != "rest" && !m.restAccepted && hub.trip.driveS >= restNextS && hub.trip.driveS >= restMinDrive * 60 && restArea() != null) { restNextS = hub.trip.driveS + 30 * 60; restOffer() }
    }
    /** 쉼표에 대한 말 — 처리했으면 true, 아니면 null */
    private fun restHeard(t: String): Boolean? {
        val now = SystemClock.elapsedRealtime()
        if (m.restEndAt != 0L) return when {
            Regex("5분더|오분더|조금더|더잘래").containsMatchIn(t) -> { restMore(); true }
            Regex("그만|일어날|일어났|깼어|출발|끝").containsMatchIn(t) -> { restEnd(false); true }
            else -> null
        }
        if (m.overlay == "rest") return when {   // 고르기 화면
            Regex("30|삼십|푹|길게").containsMatchIn(t) -> { restStart(30); true }
            Regex("15|십오|짧게|잠깐").containsMatchIn(t) -> { restStart(15); true }
            Regex("아니|안쉬|됐어|닫아|괜찮").containsMatchIn(t) -> { m.overlay = null; hub.say("알겠어요. 쉬고 싶으면 말해 줘요.", "", false, 1, reply = true, tag = "쉼표"); refresh(); hub.render(); true }
            else -> null
        }
        if (Regex("슬립모드|수면모드|쉼표시작|쉼표모드|눈좀붙|잠깐잘|한숨잘|자고갈").containsMatchIn(t)) {
            if (movingNow()) { m.restAccepted = true; hub.say("달리는 중이라 지금은 못 켜요. 차를 세우면 바로 쉼표 모드를 켜 줄게요.", "care", false, 1, reply = true, tag = "쉼표") }
            else if (Regex("30|삼십").containsMatchIn(t)) restStart(30) else if (Regex("15|십오").containsMatchIn(t)) restStart(15) else restChoose()
            return true
        }
        if (Regex("졸려|졸리|피곤|쉬고싶|쉬어가|쉬었다").containsMatchIn(t)) { if (movingNow()) restOffer() else restChoose(); return true }
        if (restAskAt != 0L && now - restAskAt < 90_000 && t.length <= 10) {
            if (Regex("^(응|어|네|예|좋아|그래|콜|오케이|쉬어|쉴래|쉬자|가자)").containsMatchIn(t)) { restAskAt = 0L; m.restAccepted = true
                hub.say("좋아요. 도착해서 차를 세우면 쉼표 모드를 켜 줄게요.", "happy", false, 1, reply = true, tag = "쉼표"); return true }
            if (Regex("^(아니|됐어|괜찮|나중|싫어|안)").containsMatchIn(t)) { restAskAt = 0L; restNextS = hub.trip.driveS + 30 * 60
                hub.say("알겠어요, 30분 뒤에 다시 물어볼게요.", "", false, 1, reply = true, tag = "쉼표"); return true }
        }
        return null
    }

    // ---------- 엔진오일 교체 알림 — 누적 주행거리(Pleos Odometer)에서 마지막 교체 때 거리를 빼 남은 거리를 센다 ----------
    private var oilSaidDay = -1; private var oilAskAt = 0L; private var oilDemoLeft: Int? = null
    /** 남은 거리 갱신 — 시연 값(--ei oil N)이 있으면 그것, 아니면 누적 주행거리 − 마지막 교체. 처음 보면 지금을 기준으로 삼는다 */
    private fun oilUpdate() {
        val demo = oilDemoLeft; val odo = m.status?.odoKm?.v
        if (demo != null) { m.oilLeftKm = demo; m.oilDemo = true; return }
        m.oilDemo = false
        if (odo == null) { m.oilLeftKm = StageModel.OIL_KM; return }
        var last = hub.prefs.getInt("oil.lastKm", -1)
        if (last < 0 || last > odo) { last = odo; hub.prefs.edit().putInt("oil.lastKm", odo).apply() }
        m.oilLeftKm = StageModel.OIL_KM - (odo - last)
    }
    private fun oilDue(): Boolean { oilUpdate(); return m.oilLeftKm <= StageModel.OIL_SOON_KM && oilSaidDay != Calendar.getInstance().get(Calendar.DAY_OF_YEAR) && System.currentTimeMillis() >= hub.prefs.getLong("oil.snoozeUntil", 0L) }
    private fun oilLater() {
        val c = Calendar.getInstance(); while (c.get(Calendar.DAY_OF_WEEK) != Calendar.SATURDAY || c.timeInMillis <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1)
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0)
        hub.prefs.edit().putLong("oil.snoozeUntil", c.timeInMillis).apply(); oilAskAt = 0L; if (m.idea == "service") m.idea = null
        hub.log("stage|oil|later"); hub.say("알겠어요, 토요일에 다시 알려 줄게요.", "", false, 1, reply = true, tag = "오일"); refresh(); hub.render()
    }
    private fun oilHeard(t: String): Boolean? {
        if (Regex("(엔진)?오일.*(갈았|교체했|바꿨|교환했)").containsMatchIn(t)) {
            val odo = m.status?.odoKm?.v; oilDemoLeft = null; oilAskAt = 0L
            if (odo != null) hub.prefs.edit().putInt("oil.lastKm", odo).remove("oil.snoozeUntil").apply()
            oilUpdate(); if (m.idea == "service") m.idea = null
            hub.log("stage|oil|changed|$odo")
            hub.say(if (odo != null) "기록해 뒀어요. 다음 교체는 ${"%,d".format(StageModel.OIL_KM)}킬로미터 뒤예요." else "기록해 뒀어요. 지금은 누적 주행거리를 읽지 못해서, 읽히는 대로 여기서부터 셀게요.", "happy", false, 1, reply = true, tag = "오일"); refresh(); hub.render(); return true
        }
        if (Regex("엔진오일|오일교체|오일언제").containsMatchIn(t)) { oilUpdate(); m.idea = "service"
            hub.say(if (m.status?.odoKm?.v == null && oilDemoLeft == null) "지금은 누적 주행거리를 읽지 못해서 남은 거리를 알 수 없어요." else "엔진오일은 ${"%,d".format(m.oilLeftKm.coerceAtLeast(0))}킬로미터 남았어요.", "", false, 1, reply = true, tag = "오일"); refresh(); hub.render(); return true }
        if (oilAskAt != 0L && SystemClock.elapsedRealtime() - oilAskAt < 90_000 && t.length <= 10) {
            if (Regex("^(응|어|네|예|좋아|그래|알려)").containsMatchIn(t)) { oilLater(); return true }
            if (Regex("^(아니|됐어|괜찮|안)").containsMatchIn(t)) { oilAskAt = 0L; hub.say("알겠어요. 갈고 나면 엔진오일 갈았어, 라고 말해 줘요.", "", false, 1, reply = true, tag = "오일"); return true }
        }
        return null
    }

    // ---------- 먼저 건넨 제안에 답하기 — 넌센스 퀴즈 · 뉴스 세 줄. 서버(두뇌) 없이 앱 안에서 끝까지 된다(칼리 11-12 마무리) ----------
    private var offerAt = 0L
    private var quizOrder = listOf<Int>(); private var quizN = -1; private var quizOk = 0; private var quizTry = 0
    private fun offerSay() { if (oilAskAt != 0L && SystemClock.elapsedRealtime() - oilAskAt < 60_000) return   // 오일 알림에 대답을 기다리는 중이면 겹쳐 말하지 않는다
        offerAt = SystemClock.elapsedRealtime(); hub.say(m.offer().first, "happy", true, 2, tag = "제안") }
    private fun quizOn() = quizN >= 0
    private fun startQuiz() {
        quizOrder = QUIZ.indices.shuffled().take(3); quizN = 0; quizOk = 0; offerAt = 0L
        hub.log("stage|quiz|start"); askQuiz("좋아요, 넌센스 퀴즈 시작! 세 문제예요. ")
    }
    private fun askQuiz(lead: String) {
        quizTry = 0; val q = QUIZ[quizOrder[quizN]]
        m.prompt = "${quizN + 1}번. ${q.q}" to "말로 대답해요 · \"힌트\" · \"패스\" · \"그만\""
        hub.say("$lead${quizN + 1}번. ${q.q}", "happy", true, 1, reply = true, tag = "퀴즈"); refresh(); hub.render()
    }
    private fun nextQuiz(lead: String) {
        quizN++
        if (quizN >= quizOrder.size) {
            val end = when (quizOk) { 3 -> "세 문제 다 맞혔어요, 천재인데요!"; 0 -> "이번엔 제가 이겼네요. 다음에 또 해요!"; else -> "세 문제 중 ${quizOk}개 맞혔어요. 잘했어요!" }
            endQuiz("$lead$end")
        } else askQuiz(lead)
    }
    private fun endQuiz(line: String) { hub.log("stage|quiz|end|$quizOk"); quizN = -1; m.prompt = null; hub.say(line, "happy", false, 1, reply = true, tag = "퀴즈"); refresh(); hub.render() }
    private fun newsBrief() {
        offerAt = 0L
        val l = m.newsList.take(3); val ord = listOf("첫째", "둘째", "셋째")
        hub.say(if (l.isEmpty()) "뉴스를 아직 못 불러왔어요. 조금 있다 다시 말해 줘요." else "오늘 주요 뉴스 ${l.size}가지예요. " + l.mapIndexed { i, s -> "${ord[i]}, ${s.trimEnd('.')}." }.joinToString(" ") + " 여기까지예요.", "", false, 1, reply = true, tag = "뉴스")
    }
    /** 놀이·제안에 대한 말 — 처리했으면 true, 아니면 null(다음 규칙으로) */
    private fun playHeard(t: String): Boolean? {
        restHeard(t)?.let { return it }
        oilHeard(t)?.let { return it }
        if (quizOn()) {
            val q = QUIZ[quizOrder[quizN]]
            return when {
                Regex("그만|끝내|안해|안할래|스톱|멈춰").containsMatchIn(t) -> { endQuiz("알겠어요, 퀴즈는 여기까지! 맞힌 건 ${quizOk}개예요."); true }
                q.a.any { t.contains(it) } -> { quizOk++; award("🎉", "넌센스 퀴즈 정답", 2); nextQuiz("딩동댕, 정답! 별 2개 드릴게요. "); true }   // 소개자료 7장 대사 — 별 +2는 award 로 쌓인다
                Regex("힌트").containsMatchIn(t) && quizTry == 0 -> { quizTry = 1; hub.say("힌트 줄게요. ${q.hint}", "", true, 1, reply = true, tag = "퀴즈"); true }
                Regex("몰라|모르겠|패스|정답|알려줘|다음").containsMatchIn(t) || quizTry >= 1 -> { nextQuiz("아쉬워요, 정답은 ${q.a[0]}. "); true }
                else -> { quizTry = 1; hub.say("땡! 힌트 줄게요. ${q.hint}", "", true, 1, reply = true, tag = "퀴즈"); true }
            }
        }
        if (Regex("넌센스|퀴즈").containsMatchIn(t)) { startQuiz(); return true }
        if (Regex("뉴스(세|들려|읽어|알려|브리핑|틀어)|주요뉴스").containsMatchIn(t)) { newsBrief(); return true }
        if (t.length <= 8 && pending == null && ((offerAt != 0L && SystemClock.elapsedRealtime() - offerAt < 180_000) || m.sit == Sit.IGNITION || m.sit == Sit.READY || m.sit == Sit.DRIVE || m.sit == Sit.STOP)) {   // 건넨 제안(시동 인사 · 주행 말풍선에 늘 떠 있음)에 대한 짧은 대답
            if (Regex("^(응|어|네|예|좋아|그래|콜|오케이|ㅇㅇ|해줘|해봐|틀어|들려|시작|할래|하자|맞춰)").containsMatchIn(t)) { if (m.offerKind() == "quiz") startQuiz() else newsBrief(); return true }
            if (Regex("^(아니|됐어|괜찮|나중|싫어|안해|노)").containsMatchIn(t)) { offerAt = 0L; hub.say("알겠어요. 필요하면 불러 줘요.", "", false, 1, reply = true, tag = "제안"); return true }
        }
        return null
    }

    private fun talk(s: String, prio: Int = 2, mood: String = "", listen: Boolean = false) = hub.say(s, mood, listen, prio, tag = "반응")

    private fun react() {
        val st = m.status ?: return
        val driving = m.sit == Sit.DRIVE || m.sit == Sit.STOP
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        st.wiper.v.let { w -> if (changed("wiper", w)) {
            if (w == true) {
                event("cloud", "와이퍼 켜짐 — 비가 와요")
                story(Tile("cloud", "비 오는 날", "비가 오네요. 우산 쓰고 있을게요. 앞차랑 간격은 평소의 두 배로 둬요.", "와이퍼 신호(Pleos Wiper)", "info"))
                talk(if (m.novice) "비가 오네요. 앞차랑 간격을 평소의 두 배로 두고, 브레이크는 미리미리 밟아요." else "비가 오네요. 간격 넉넉히 가요.", 2, "care")
            } else reactions.removeAll { it.first.label == "비 오는 날" }   // 와이퍼를 끄면 '비가 오네요' 카드도 내린다(11-10 — 맑은 날씨 옆에 2분간 남았다)
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
            story(Tile("chat", "동승자 모드", m.offer().first, "조수석 착석(Pleos Seat)", "accent"))
            offerSay()   // 누가 탔는지에 맞춘 제안 하나(StageModel.offer) — 대답을 듣는다
        } }
        st.rearKid.v.let { p -> if (changed("kid", p) && p == true) {
            story(Tile("music", "뒷좌석 손님", m.offer().first, "뒷좌석 착석(Pleos Seat)", "good"))
            offerSay()
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
            story(Tile("battery", "가는 김에 충전", "배터리가 ${s}%예요. 가는 길 충전소에 들렀다 갈까요?", "주행 가능 ${st.rangeKm.v ?: "-"}km(Pleos EvBattery)", "warn", action = "say:충전소 들렀다 가자"))
            talk("배터리가 ${s}퍼센트예요. 가는 길 충전소에 들렀다 갈까요?", 1, "care", listen = !driving)
        } }
        // 주행 이정표 — 10km마다 칭찬 +3, 30분·60분 쉼표 안내
        val km10 = (tripM / 10_000).toInt()
        if (km10 > kmMark) { kmMark = km10; award("flag", "${km10 * 10}km 달성", 3); event("flag", "${km10 * 10}km 달성. +3") }
        val tmin = st.tripMin.v ?: (tripS / 60)
        if (changed("min30", tmin >= 30) && tmin >= 30) { event("clock", "벌써 30분 달렸어요"); if (m.novice) talk("벌써 30분 달렸어요. 어깨 힘 빼고 가요.", 3) }
    }

    /** 공조 설정 온도 — 요청 뒤 2초에 차 값을 다시 읽어 맞으면 '맞췄어요', 아니면 '요청했어요'(확인 없이 완료라고 말하지 않기 — 가짜 감사 #8).
     *  에뮬 실측 11-11: Virtual Console 운전석 20.0° → 23.0°(DDS 활성 때) */
    private fun setCabinSay(c: Float) {
        val st = m.status
        val n = c.toInt()
        if (st?.setCabin(c) != true) {
            hub.say("${n}도로 맞추려 했는데, 이 차에선 공조를 바꿀 수 없어서 화면 값만 바꿨어요.", "", false, 1, reply = true, tag = "공조")
            event("temp", "실내 ${n}° — 화면 값만"); return
        }
        hub.main.postDelayed({
            st.readCabinSet { got -> hub.main.post {
                val ok = got != null && kotlin.math.abs(got - c) < 0.6f
                hub.log("stage|hvac|요청 $c → 읽음 ${got ?: "모름"}|${if (ok) "맞음" else "확인 안 됨"}")
                hub.say(if (ok) "${n}도로 맞췄어요." else "${n}도로 맞춰 달라고 요청했어요.", "happy", false, 1, reply = true, tag = "공조")
                event("temp", if (ok) "실내 ${n}°로 맞춤" else "실내 ${n}° 요청")
            } }
        }, 2000)
    }

    /** "응" — 물어본 일을 한다 */
    private fun doPending(): Boolean {
        val p = pending ?: return false
        pending = null
        when {
            p.startsWith("cabin:") -> setCabinSay(p.substringAfter(":").toFloat())
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
        tripDestAt?.let { (la, lo) -> learnPlace(name, la, lo) }
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

    // ---------- 즐겨찾기 도착지 — "이름|위도|경도|예시 분" 줄. 비어 있으면 예시 둘(집·회사) ----------
    private fun favs(): List<Fav> {
        val raw = hub.prefs.getString(FAVS, null)
        val saved = raw?.lines()?.mapNotNull { l -> l.split('|').takeIf { it.size >= 3 }?.let { p ->
            runCatching { Fav(p[0], p[1].toDouble(), p[2].toDouble(), p.getOrNull(3)?.toIntOrNull() ?: 0) }.getOrNull() } }.orEmpty()
        // 실제로 간 곳(자주 간 순 · 최근 순) — 사용자 11-14 '자주 가는 곳 = 앱 안에 저장'. 예시 좌표(DEMO_FAVS)는 둘 다 없을 때만
        val learned = places().sortedWith(compareByDescending<Place> { it.n }.thenByDescending { it.last }).map { Fav(it.name, it.lat, it.lng, 0) }
        val all = (saved + learned).distinctBy { it.name }.take(6)
        return all   // 둘 다 없으면 빈 목록 → '"회사 가자"처럼 말하면…' 빈 상태(예시 좌표 회사·집은 쓰지 않는다 — 사용자 11-14)
    }
    // ---------- 실제로 간 곳 — 목적지가 있던 주행이 끝나면(주차) 이름·좌표·횟수를 쌓는다 ----------
    private data class Place(val name: String, val lat: Double, val lng: Double, val n: Int, val last: Long)
    private fun places(): List<Place> = hub.prefs.getString(PLACES, "")!!.lines().mapNotNull { l -> l.split('|').takeIf { it.size == 5 }?.let { p ->
        runCatching { Place(p[0], p[1].toDouble(), p[2].toDouble(), p[3].toInt(), p[4].toLong()) }.getOrNull() } }
    private fun learnPlace(name: String, la: Double, lo: Double) {
        val cur = places(); val old = cur.firstOrNull { it.name == name }
        val next = listOf(Place(name, la, lo, (old?.n ?: 0) + 1, System.currentTimeMillis())) + cur.filter { it.name != name }
        hub.prefs.edit().putString(PLACES, next.take(12).joinToString("\n") { "${it.name.replace('|', ' ')}|${it.lat}|${it.lng}|${it.n}|${it.last}" }).apply()
        hub.log("stage|place|$name|${(old?.n ?: 0) + 1}번")
    }
    private fun placeCount(name: String) = places().firstOrNull { it.name == name }?.n ?: 0
    private fun saveFavs(l: List<Fav>) = hub.prefs.edit().putString(FAVS, l.take(6).joinToString("\n") { "${it.name}|${it.lat}|${it.lng}|${it.demoMin}" }).apply()

    private fun favKm(f: Fav): Double? { val la = hub.lat ?: return null; val lo = hub.lng ?: return null; return io.github.somanghao.makecar.hyun.drive.Trip.meters(la, lo, f.lat, f.lng) / 1000 }
    /** 지금 출발하면 걸리는 분 — 지난 주행 평균 → 현재 위치 직선거리 어림(×1.3 · 시내 30km/h) → 예시 값. (분, 근거) */
    private fun favMin(f: Fav): Pair<Int, String>? {
        // 지난 주행 평균 — 직선거리로 봐서 말이 되는 기록만(시속 100km 넘게 걸린 셈이면 도착 전에 끝난 기록 — 11-11: 143km 대전역 '지금 가면 4분 · 지난 2번 평균')
        val floorMin = favKm(f)?.let { it / 100.0 * 60 } ?: 0.0
        goLog().filter { it.name == f.name && it.travelMin >= floorMin }.takeIf { it.isNotEmpty() }?.let { l -> return l.map { it.travelMin }.average().roundToInt() to "지난 ${l.size}번 평균" }
        val la = hub.lat; val lo = hub.lng
        if (la != null && lo != null) {
            val km = io.github.somanghao.makecar.hyun.drive.Trip.meters(la, lo, f.lat, f.lng) * 1.3 / 1000
            if (km <= 30) return max(1, (km / 30.0 * 60).roundToInt()) to "약 %.1fkm".format(km)   // 시내 어림만 — 장거리는 직선×시내 속도로 크게 틀린다(대전역 5시간 22분 vs 내비 1시간 53분)
        }
        return if (f.demoMin > 0) f.demoMin to "예시 시간" else null
    }

    /** 고른 즐겨찾기 — 직접 고른 것 → 이 시간에 자주 가는 곳 → 시간대(아침 회사 · 저녁 집) → 첫째 */
    private fun favPick(list: List<Fav>): Int {
        favSel?.let { s -> list.indexOfFirst { it.name == s }.takeIf { it >= 0 }?.let { return it } }
        habitGo()?.let { (g, _, _) -> list.indexOfFirst { it.name == g.name }.takeIf { it >= 0 }?.let { return it } }
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val want = if (h in 5..13) "회사" else "집"
        return list.indexOfFirst { it.name.startsWith(want) }.coerceAtLeast(0)
    }
    private var favSel: String? = null

    private fun goBox() {
        val v = hub.view
        val dest = v.dest
        val nowMin = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
        val list = favs()
        m.favNames = list.map { it.name }
        m.favIdx = -1
        when {
            dest != null -> { m.goName = dest; m.goSub = listOf(v.remain, v.eta).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "길을 찾는 중이에요" }; m.goTag = "안내 중"; m.goAction = "" }
            list.isNotEmpty() -> {
                val i = favPick(list); val f = list[i]
                m.favIdx = i
                m.goName = f.name
                m.goSub = favMin(f)?.let { (mn, _) -> "약 ${if (mn >= 60) "${mn / 60}시간 ${mn % 60}분" else "${mn}분"} · ${hm(nowMin + mn)} 도착" }
                    ?: favKm(f)?.let { "약 %.0fkm · 출발하면 내비가 걸리는 시간을 알려 줘요".format(it) }
                    ?: "지금 위치를 읽으면 걸리는 시간을 알려 드려요"
                m.goTag = if (list === DEMO_FAVS) "예시" else placeCount(f.name).takeIf { it > 0 }?.let { "자주 가는 곳 · ${it}번" } ?: "즐겨찾기"
                m.goAction = "go:$i"
            }
            else -> { m.goName = ""; m.goSub = "\"회사 가자\"처럼 말하면 길 안내를 시작해요"; m.goTag = ""; m.goAction = "" }
        }
    }

    /** 즐겨찾기로 안내 시작 */
    private fun goFav(i: Int) {
        val f = favs().getOrNull(i) ?: return
        val r = hub.feature<RouteFeature>()
        if (r == null) { hub.heard("${f.name} 가자"); return }
        r.goTo(f.name, f.lat, f.lng)
        hub.say("${f.name}까지 안내할게요.", "happy", false, 1, reply = true, tag = "즐겨찾기")
    }

    /** 지금 안내 중인 목적지를 즐겨찾기에 */
    private fun addFav(): String {
        val r = hub.feature<RouteFeature>()
        val name = hub.view.dest; val la = r?.destLat; val lo = r?.destLng
        if (name == null || la == null || lo == null) return "지금 안내 중인 목적지를 즐겨찾기에 넣을 수 있어요. 먼저 어디로 갈지 말해 주세요."
        val cur = favs().let { if (it === DEMO_FAVS) emptyList() else it }.filter { it.name != name }
        saveFavs(listOf(Fav(name, la, lo, 0)) + cur)
        favSel = name; refresh()
        return "${name}을 즐겨찾기에 넣었어요."
    }

    /** 시연용 — 지난 5일 이 시간쯤 '회사'에 간 기록 */
    private fun seedGoLog() {
        val now = Calendar.getInstance(); val nowMin = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val seed = (1..5).map { d -> val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -d) }
            Go("회사", (nowMin - 10 + d * 4 + 1440) % 1440, 21 + d % 3 * 2, dayKey(c)) }
        saveGoLog(goLog() + seed)
    }

    /** 추천 목적지 — 이 시간에 자주 간 곳(실제 주행 기록 goLog · 2일 이상) → 저장한 즐겨찾기. 없으면 null
     *  (예전엔 시간대 고정값 "회사 · 평소 출근 시간이에요 · 약 25분" · "자주 가는 카페 · 지난주 두 번 갔어요" — 가짜 감사 #9). (이름, 설명, 기록 기반?) */
    private fun recommendDest(): Triple<String, String, Boolean>? {
        habitGo()?.let { (g, days, _) -> return Triple(g.name, "최근 ${days}일 이 시간쯤 갔어요 · 평균 ${Fmt.dur(g.travelMin)}", true) }
        val list = favs().takeIf { it !== DEMO_FAVS && it.isNotEmpty() } ?: return null
        val f = list[favPick(list)]
        return Triple(f.name, favMin(f)?.let { (mn, why) -> "지금 가면 ${Fmt.dur(mn)} · $why" } ?: "즐겨찾기", false)
    }

    private fun nextUnlock(): String {
        val next = (StageModel.CONCEPTS.filter { "${it.kind}:${it.id}" !in m.owned }.minByOrNull { it.price }
            ?: StageModel.all().filter { "${it.kind}:${it.id}" !in m.owned }.minByOrNull { it.price }) ?: return "전부 열었어요."
        return if (m.points >= next.price) "${next.name} 열 수 있어요" else "${next.name}까지 ${next.price - m.points}점"
    }

    private fun sign(n: Int) = if (n >= 0) "+$n" else "−${-n}"
    private fun look() = m.look()

    /** 시동 인사 — 날씨·음료·걸리는 시간 인사는 빼고 제안부터(칼리 11-12). 주행 화면 말풍선과 같은 제안(StageModel.offer) */
    private fun ignitionLine(): String {
        goBox()   // 화면 맨 위 '어디로 갈까요?' 칸 채우기
        if (oilDue()) {   // 엔진오일 교체가 가까우면 제안보다 먼저(하루 한 번) — 화면도 그 카드로
            oilSaidDay = Calendar.getInstance().get(Calendar.DAY_OF_YEAR); m.idea = "service"; oilAskAt = SystemClock.elapsedRealtime()
            return if (m.oilLeftKm <= 0) "엔진오일 교체할 때가 됐어요. 이번 주 안에 정비소에 들러요. 주말에 다시 알려 줄까요?"
                   else "엔진오일 교체 시기가 다가와요. ${m.oilLeftKm / 100 * 100}킬로미터쯤 남았어요. 주말에 다시 알려 줄까요?"
        }
        offerAt = SystemClock.elapsedRealtime()
        return m.offer().first
    }

    private fun parkLine(): String {
        val plus = m.ledger.filter { it.delta > 0 }.sumOf { it.delta }; val minus = -m.ledger.filter { it.delta < 0 }.sumOf { it.delta }
        val p = look().greet + "주차 끝, 고생했어요! " + (if (minus > 0) "플러스 ${plus}점, 마이너스 ${minus}점 해서 ${m.tripPoints}점이에요." else "안전운전 포인트 ${m.tripPoints}점 받았어요.")
        return if (m.novice) "$p 핸들 똑바로 풀고, P, 주차 브레이크까지 확인해요." else p
    }

    private fun offLine() = look().greet + "오늘 %.1f킬로미터 달렸어요. 휴대폰이랑 지갑 챙겼죠? 내일 또 봐요!".format(dayKm)

    /** 신호 대기 수다 — 주변 상권·동네 이야기. 카카오 로컬은 서버 도착 추천에만 열려 있어 지금은 예시 이야기(곧 서버 places 로 바꿀 자리) */
    // ---------- 주행 중 길 안내 — 내비(NaviHelper)가 있으면 그 값, 없으면(표준 에뮬) 모의 경로 ----------
    private fun navState() {
        val v = hub.view
        val t = v.turns.firstOrNull()
        if (v.dest != null && t != null) {
            m.mockRoute = false
            m.navTurnType = t.type; m.navTurnM = t.m; m.navTurn = t.desc.ifBlank { "다음 안내" }
            // 남은 거리는 거리만(분은 navMin) — "11.9km · 26분 · 26분" 중복 · 남은 시간은 정수 분 그대로(정규식이 "1시간 53분"의 53만 잡아 "53분"이 됐다, 11-11 영상)
            m.navDest = v.dest ?: ""; m.navRemain = v.remain.substringBefore(" · "); m.navEta = v.eta
            v.turns.getOrNull(1).let { n -> m.navNextType = n?.type ?: ""; m.navNextM = n?.m ?: 0; m.navNext = n?.desc ?: "" }
            m.navMin = hub.mins()
            return
        }
        // 실제 경로가 없으면 모의 경로를 그리지 않는다 — 모의(판교→코엑스)는 시연 키 SIM nav on 일 때만(가짜 감사 #3)
        if (!m.simNav) {
            m.mockRoute = false; m.navTurnType = ""; m.navTurnM = 0; m.navTurn = ""; m.navNextType = ""; m.navNextM = 0; m.navNext = ""
            m.navDest = ""; m.navRemain = ""; m.navEta = ""; m.navMin = 0
            return
        }
        if (v.dest != null && !m.simNav) {   // 실제 목적지는 있는데 회전 안내가 아직 안 왔다 — 모의 경로를 진짜처럼 보이지 않게 기다리는 표시만
            m.mockRoute = false; m.navTurnType = ""; m.navTurnM = 0; m.navTurn = "길을 찾는 중이에요"
            m.navDest = v.dest ?: ""; m.navRemain = v.remain; m.navEta = v.eta; m.navNextType = ""; m.navNextM = 0; m.navNext = ""; m.navMin = 0
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
        }.onFailure { hub.log("stage|stock|FAIL|${it.message}")
            runCatching {   // 서버가 없으면 앱이 직접(네이버 증권 공개 지수 — 코스피 · 나스닥)
                val idx = listOf("코스피" to "https://m.stock.naver.com/api/index/KOSPI/basic", "나스닥" to "https://api.stock.naver.com/index/.IXIC/basic").map { (nm, u) ->
                    val j = getJson(u); val down = j.optJSONObject("compareToPreviousPrice")?.optString("code") in setOf("4", "5")
                    val r = j.optString("fluctuationsRatio").trimStart('-').toFloatOrNull() ?: 0f
                    Quote(nm, j.optString("closePrice"), if (down) -r else r) }
                hub.main.post { m.indices = idx; m.stockAsof = "네이버 증권"; hub.log("stage|stock|direct|${idx.size}"); refresh(); hub.render() }
            }.onFailure { e -> hub.log("stage|stock|direct|FAIL|${e.message}") }
        }
    }.start()

    private fun getJson(u: String): JSONObject {
        val c = (URL(u).openConnection() as HttpURLConnection).apply { connectTimeout = 5000; readTimeout = 8000 }
        try { return JSONObject(c.inputStream.bufferedReader().use { it.readText() }) } finally { c.disconnect() }
    }

    override fun render(v: io.github.somanghao.makecar.hyun.ui.HyunView) {
        // 신호대기 동네 이야기 돌리기(25초마다)는 고정 문장을 뺄 때 함께 뺐다(가짜 감사 #5)
    }

    companion object {
        const val ACTION = "io.github.somanghao.makecar.hyun.STAGE"
        private const val PLACES = "stage.places"   // 실제로 간 곳: "이름|위도|경도|횟수|마지막 ms"
        private const val GO_LOG = "stage.goLog"   // 지난 주행: "목적지|출발 분(하루 중)|걸린 분|yyyyMMdd" 줄 목록
        private const val FAVS = "stage.favs"      // 즐겨찾기 도착지
        /** 즐겨찾기를 아직 안 넣었을 때 보이는 예시(강남역 근처 집 · 삼성역 근처 회사) — 화면에 '예시' 표시 */
        private val DEMO_FAVS = listOf(Fav("회사", 37.5089, 127.0631, 23), Fav("집", 37.4979, 127.0276, 28))
        const val SIM_ACTION = "io.github.somanghao.makecar.hyun.SIM"
        private val SIM_KEYS = listOf("wiper", "door", "window", "passenger", "kid", "belt", "lights", "lock", "cruise", "turn", "cabin", "soc", "range", "mode", "steer")
        private const val HARSH_MS = 3.5f          // 1초에 이만큼(약 12.6km/h) 넘게 줄면 급정거
        private const val NOVICE_REST_S = 50 * 60.0
        private const val WELCOME = 100
        private const val QUEST_KM = 10.0; private const val QUEST_STARS = 20
        // 런타임 요청은 사용자가 허용할 수 있는 권한만. CAR_MILEAGE는 signature|privileged라 일반 앱엔 늘 거부 →
        // 켤 때마다 시스템 권한 화면이 앱 task 맨 위에 남아, 주행 중 Pleos 홈이 앱 칸 전체를 '주행 중에는 사용할 수 없습니다'로 덮었다(11-11 에뮬 A/B).
        // 누적 주행은 Pleos 쪽 권한(pleos.car.permission.CAR_MILEAGE — 매니페스트 선언만)으로 읽는다.
        private val CAR_PERMS = listOf("android.car.permission.CAR_ENERGY", "android.car.permission.CAR_SPEED")
        /** 모의 경로(판교 → 코엑스) — 내비가 없는 표준 에뮬레이터에서 주행 화면 구조를 보이려고 */
        private val LIMITS = listOf(50, 60, 80, 100, 80, 60, 30)   // 모의 경로 구간별 제한속도
        private val MOCK = listOf(
            Triple("TURN_RIGHT", 350, "판교역로 방면 우회전"), Triple("TURN_STRAIGHT", 900, "분당내곡로 직진"),
            Triple("TURN_ENTER_IC", 1200, "경부고속도로 진입"), Triple("TURN_KEEP_L", 4200, "서울 방면 왼쪽 길"),
            Triple("TURN_EXIT_IC", 2600, "양재IC 나가기"), Triple("TURN_LEFT", 1500, "테헤란로 방면 좌회전"), Triple("TURN_ARRIVE", 800, "코엑스 도착"))
        /** 신호 대기 수다(예시) — 짧게, 말로 듣기 좋게 */
        /** 넌센스 퀴즈 — 문제 · 정답(받아 적은 말에 이 중 하나가 들어 있으면 정답, 띄어쓰기 없이) · 힌트 */
        private class Quiz(val q: String, val a: List<String>, val hint: String)
        private val QUIZ = listOf(
            Quiz("세상에서 가장 뜨거운 바다는 뭘까요?", listOf("열바다"), "숫자 십이 들어가요."),
            Quiz("왕이 넘어지면 뭐라고 할까요?", listOf("킹콩"), "왕은 영어로 킹이에요."),
            Quiz("아몬드가 죽으면 뭐가 될까요?", listOf("다이아몬드"), "죽다는 영어로 다이예요."),
            Quiz("소가 웃으면 뭐라고 할까요?", listOf("우하하"), "소는 한자로 우예요."),
            Quiz("세상에서 가장 가난한 왕은 누구일까요?", listOf("최저임금"), "왕을 다른 말로 임금이라고 해요."),
            Quiz("오리가 얼면 뭐가 될까요?", listOf("언덕"), "오리는 영어로 덕이에요."),
            Quiz("세상에서 가장 빠른 닭은 뭘까요?", listOf("후다닭", "후다닥"), "급하게 뛰어가는 소리예요."),
            Quiz("차를 발로 차면 뭐가 될까요?", listOf("카놀라유", "카놀라"), "차는 영어로 카, 그리고 깜짝 놀라요."),
            Quiz("바나나가 웃으면 뭐가 될까요?", listOf("바나나킥"), "웃음소리 킥킥, 과자 이름이에요."),
            Quiz("딸기가 직장을 잃으면 뭐가 될까요?", listOf("딸기시럽"), "직장을 잃는 걸 실업이라고 해요."))
    }
}
