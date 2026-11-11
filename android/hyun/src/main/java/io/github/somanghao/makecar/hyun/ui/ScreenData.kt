package io.github.somanghao.makecar.hyun.ui

import io.github.somanghao.makecar.hyun.ui.HyunView.Bubble
import io.github.somanghao.makecar.hyun.ui.HyunView.Card
import io.github.somanghao.makecar.hyun.ui.HyunView.Line
import io.github.somanghao.makecar.hyun.ui.HyunView.Mode
import io.github.somanghao.makecar.hyun.ui.HyunView.Turn

/**
 * 화면 **데이터** 층 — 코어·기능이 채우고, 화면(배치·스킨)은 **읽기만** 한다. 스킨이 바뀌어도 이 값은 그대로다 — 스킨은 보여 주는 구성·위치만 다르다
 * (사용자 11-09 "데이터·레이아웃·UI·폰트 분리"). 화면에서 누른 것(카드 닫기·다시 열기·모드 바꾸기)은 HyunView의 `on*` 콜백 → 코어가 이 값을 바꾼다.
 *
 *   데이터  ScreenData(이 파일)                  — 무엇을 보일까(목적지·회전·말·표·기분…)
 *   배치    HyunView의 draw*(정보형·대화형·반반·폰) — 어디에(스킨 layout: 캐릭터 왼쪽/오른쪽 · 칸 폭)
 *   UI      skin/Kit                              — 어떻게(스킨 colors·style: 색 · 판 · 말풍선 · 배경 무늬)
 *   글꼴    skin fonts                            — 무슨 글씨로
 *
 * HyunView는 `ScreenData by model`로 이 속성을 그대로 내보낸다 — 기존 `view.dest = …` · `v.turns = …` 호출은 그대로 둔다.
 */
interface ScreenData {
    var mode: Mode
    var mood: String
    var driving: Boolean
    var gear: Char
    var kmh: Int
    var phone: Boolean                   // 폰 모드(차량 API 없음)
    var botName: String                  // 차 = 현비서 · 폰 = 짭비서
    var modes: List<String>              // 폰 모드 칩(대중교통·산책·업무중)
    var modeIdx: Int
    var nextUp: String                   // 폰 — "15:00 팀 회의 · 32분 뒤"
    var carLine: String                  // 폰 — "차 현비서와 연결됨 · 마지막 대화 3분 전"(폰 연동 CarLinkFeature) · 빈 글자면 안 그림
    var nextMore: Int
    var tasks: List<String>
    var reminderItems: List<String>
    var todoItems: List<String>
    var silent: Boolean                  // 대중교통 + 이어폰 없음 → 자막·진동
    var note: String                     // 머리줄 알림 — "회전 앞 180m" · "조용히 모드 9분"
    var lightTheme: Boolean              // 폰(짭비서) 흰 화면 — 차는 스킨이 정한다
    var brain: Boolean?                  // null 서버 없음 · false 로그인 필요 · true 연결
    var brainMode: String
    var uiChat: Boolean                  // 대화형(true) ↔ 정보형(false) — 사용자 11-09
    var dest: String?
    var remain: String                   // "12.3km · 24분"
    var eta: String                      // "도착 14:29"
    var progress: Double
    var via: String                      // "코엑스 거쳐"
    var trafficLine: String              // 내비 기준 경로 교통 한 줄
    var turns: List<Turn>                // 내비 다음 안내들(TBT)
    val lines: MutableList<Line>         // 기능 한 줄 — 코어가 render마다 비우고 기능이 채운다
    var userLine: String
    var hyunLine: String
    var prio: Int                        // 지금 말의 우선순위 0~3, 없으면 -1
    var prioReply: Boolean
    var waiting: List<Int>
    var name: String
    var memory: List<String>
    var card: Card?                      // 목록·소개 카드(정차)
    val recentCards: List<Card>          // 최근 본 정보(카드) — 정보형 빈 칸에 다시 열기
    var hints: List<String>              // 물어볼 거리(정보형 빈 칸) — 기능이 바꿀 수 있다
    var debug: Boolean
    var character: String                // human · orb3d · orb
    val chat: List<Bubble>               // 대화형 말풍선 기록
    fun addChat(me: Boolean, text: String)
}

class ScreenModel : ScreenData {
    override var mode = Mode.READY
    override var mood = ""
    override var driving = false
    override var gear = 'P'
    override var kmh = 0
    override var phone = false
    override var botName = "현비서"
    override var modes: List<String> = emptyList()
    override var modeIdx = -1
    override var nextUp = ""
    override var carLine = ""
    override var nextMore = 0
    override var tasks: List<String> = emptyList()
    override var reminderItems: List<String> = emptyList()
    override var todoItems: List<String> = emptyList()
    override var silent = false
    override var note = ""
    override var lightTheme = false
    override var brain: Boolean? = null
    override var brainMode = ""
    override var uiChat = false
    override var dest: String? = null
    override var remain = ""
    override var eta = ""
    override var progress = 0.0
    override var via = ""
    override var trafficLine = ""
    override var turns: List<Turn> = emptyList()
    override val lines = mutableListOf<Line>()
    override var userLine = ""
    override var hyunLine = ""
    override var prio = -1
    override var prioReply = false
    override var waiting: List<Int> = emptyList()
    override var name = ""
    override var memory: List<String> = emptyList()
    override var card: Card? = null
        set(v) { field = v; if (v != null) { recent.removeAll { it.title == v.title }; recent += v; while (recent.size > 6) recent.removeAt(0) } }
    private val recent = ArrayList<Card>()
    override val recentCards: List<Card> get() = recent
    override var hints = listOf("웹툰 순위", "영화 순위", "앞길 어때?", "오늘 날씨", "손흥민 소식", "배구 결과")
    override var debug = false
    override var character = "orb"
    private val log = ArrayList<Bubble>()
    override val chat: List<Bubble> get() = log
    override fun addChat(me: Boolean, text: String) {
        val s = text.trim(); if (s.isEmpty()) return
        if (log.lastOrNull() == Bubble(me, s)) return
        log += Bubble(me, s); while (log.size > CHAT_MAX) log.removeAt(0)
    }

    companion object { const val CHAT_MAX = 16 }   // 말풍선 기록 — 화면엔 들어가는 만큼만
}
