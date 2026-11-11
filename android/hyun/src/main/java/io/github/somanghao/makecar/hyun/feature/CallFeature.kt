package io.github.somanghao.makecar.hyun.feature

import io.github.somanghao.makecar.hyun.core.Feature
import io.github.somanghao.makecar.hyun.core.Hyun

/**
 * 말로 전화 — 장소 목록 카드(지금 보이는 것 또는 최근 본 것)의 가게에 전화 앱을 연다(ACTION_DIAL — 번호만 입력, 통화는 운전자가 누름).
 * "어머니족발에 전화해 줘"(이름) · "두 번째 집 전화해 줘"(순서) · 가게가 하나면 "전화해 줘". 주행 중이면 카드는 안 그려도 데이터는 있어
 * Pleos 전화 앱이 그 번호로 뜬다. 번호는 서버 places가 카드에 실어 준 것(tels — 로그엔 안 남음). 사용자 11-11 "리스트 아래에 바로 전화하기"
 */
class CallFeature : Feature {
    override val id = "call"
    private lateinit var hub: Hyun
    private val call = Regex("""전화\s*(해|걸어|좀|연결|부탁|하자|줘)|(으로|에|에다|한테)\s*전화""")
    private val ord = Regex("""(첫|두|세|네|다섯|[1-5])\s*(번째|번)""")
    private val ordMap = mapOf("첫" to 0, "1" to 0, "두" to 1, "2" to 1, "세" to 2, "3" to 2, "네" to 3, "4" to 3, "다섯" to 4, "5" to 4)

    override fun start(hub: Hyun) { this.hub = hub }

    override fun onHeard(text: String): Boolean {
        if (!call.containsMatchIn(text)) return false
        val card = (listOfNotNull(hub.view.card) + hub.view.recentCards).firstOrNull { k -> k.tels.any { it.isNotBlank() } } ?: return false
        val names = card.items.map { it.substringBefore(" — ").trim() }
        val t = text.replace(" ", "")
        val byName = names.indexOfFirst { n -> n.isNotBlank() && (t.contains(n.replace(" ", "")) || n.split(" ").first().let { h -> h.length >= 2 && t.contains(h) }) }
        val byOrd = ord.find(text)?.groupValues?.get(1)?.let { ordMap[it] }
        val withTel = card.tels.indices.filter { card.tels[it].isNotBlank() }
        val i = when {
            byName >= 0 -> byName
            byOrd != null && byOrd < names.size -> byOrd
            withTel.size == 1 -> withTel[0]
            else -> { hub.say("어느 가게요? \"두 번째 집에 전화해 줘\"처럼 말해 주세요.", "calm", true, 1, reply = true, tag = "전화"); return true }
        }
        val name = names.getOrNull(i).orEmpty()
        if (!hub.dial(card.tels.getOrNull(i).orEmpty(), name, "voice")) {
            hub.say("$name 전화번호는 없어요.", "calm", false, 1, reply = true, tag = "전화"); return true
        }
        hub.say("$name 번호를 띄웠어요. 통화 버튼을 누르시면 걸려요.", "happy", false, 1, reply = true, tag = "전화")
        return true
    }
}
