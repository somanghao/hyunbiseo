package io.github.somanghao.makecar.hyun.voice

import android.os.SystemClock

/**
 * 말하기 스케줄러 — 여러 말이 동시에 생기면 누가 언제 말하나(설계 정본 dashboard/hyun/plan.html #priority · server/hyun/persona.md).
 *
 *  P0 놓치면 안 되는 것 — 차: 사고·역주행 경고 / 폰: 리마인더·타이머·회의 직전·하차. 즉시, 말·듣기를 끊는다(앱 규칙 문장, 두뇌 X)
 *  P1 주행 필수·시킨 일 — 차: 출발·도착·교통/우회 / 폰: 메모·할 일·질문. 운전자 질문의 대답도 이 자리
 *  P2 챙겨주기 — 인사·브리핑·일정·우산·배터리
 *  P3 관계·재미 — 수다·놀이. 차에서만(폰 모드는 먼저 꺼내지 않는다)
 *
 * 게이트는 정책(Policy)이 정한다 — 차 주행 중 / 폰 모드(대중교통·산책·업무중)마다 같은 단계 쿨다운·수명이 다르고, 차 정차는 게이트가 없다.
 * 수명이 지나면 버린다(늦은 말은 소음). 위 단계가 말하는 아래 단계를 끊는다. 대답은 쿨다운 없이 P1 자리로 줄 선다.
 */
class Speaker(private val log: (String) -> Unit) {
    data class Line(val text: String, val mood: String, val listen: Boolean, val prio: Int, val reply: Boolean, val tag: String) {
        val born = SystemClock.elapsedRealtime()
        /** 줄 서기·끊기 기준 — 대답은 P1 자리 */
        val rank get() = if (reply) minOf(prio, 1) else prio
    }

    /** 단계별 쿨다운·수명(ms, P0~P3) · P3 허용 여부 */
    class Policy(val name: String, val cooldown: LongArray, val ttl: LongArray, val allowP3: Boolean) {
        companion object {
            private const val M = 60_000L
            val PARKED = Policy("정차", longArrayOf(0, 0, 0, 0), longArrayOf(30_000, M, 3 * M, 10 * M), true)
            val DRIVE = Policy("주행", longArrayOf(0, 30_000, 3 * M, 10 * M), longArrayOf(30_000, M, 3 * M, 10 * M), true)
            val TRANSIT = Policy("대중교통", longArrayOf(0, 0, 5 * M, 0), longArrayOf(2 * M, 2 * M, 10 * M, 0), false)
            val WALK = Policy("산책", longArrayOf(0, 0, 3 * M, 0), longArrayOf(2 * M, 2 * M, 10 * M, 0), false)
            val WORK = Policy("업무중", longArrayOf(0, 0, 15 * M, 0), longArrayOf(2 * M, 3 * M, 30 * M, 0), false)   // 방해 금지 — 챙겨주기는 15분에 한 번
        }
    }

    /** 지금 상황 — 부를 때마다 채운다 */
    data class Now(val listening: Boolean, val speaking: Boolean, val thinking: Boolean, val turnBusy: Boolean = false)

    sealed class Act {
        data class Speak(val line: Line, val cut: Boolean) : Act()   // cut = 지금 하던 말·듣기를 끊고
        object Wait : Act()
    }

    var policy = Policy.PARKED
    private val queue = mutableListOf<Line>()
    var current: Line? = null
        private set
    private val lastAt = LongArray(4) { -3_600_000L }

    val waiting: List<Line> get() = queue.toList()

    fun offer(l: Line) {
        if (l.prio == 3 && !l.reply && !policy.allowP3) { log("prio|drop|P3|${policy.name} 모드는 수다 없음|${l.text}"); return }
        log("prio|in|P${l.prio}${if (l.reply) "·답" else ""}|${l.tag}|${l.text}")
        if (l.prio == 0) queue.removeAll { it.prio == 0 && it.text == l.text }   // 같은 경고 반복 금지
        queue += l
    }

    /** 다음에 할 일 — Speak를 받으면 부르는 쪽이 실제로 말하고, 끝나면 [finished]를 부른다 */
    fun next(now: Now): Act {
        val t = SystemClock.elapsedRealtime()
        queue.removeAll { l -> (t - l.born > ttl(l)).also { if (it) log("prio|drop|P${l.prio}|수명 지남|${l.text}") } }
        // 같은 자리면 대답이 먼저 — 쿨다운에 걸린 안내가 앞에 있다고 운전자 대답까지 막히면 안 된다(2026-11-06 실측)
        val l = queue.minWithOrNull(compareBy<Line>({ it.rank }, { !it.reply }, { it.born })) ?: return Act.Wait
        val cur = current
        if (now.speaking && cur != null) {
            // 말하는 중 — 더 급한 단계만 끊고 들어간다(대답·P1이 수다·제안을, P0가 모두를)
            return if (l.rank < cur.rank && (l.prio == 0 || cur.rank >= 2)) take(l, cut = true, why = "P${cur.prio} 끊고") else Act.Wait
        }
        // 마이크가 열려 있으면(말하는 중이거나 말하려는 참) P0만 끊고, 나머지는 듣기가 끝나면
        if (now.listening) return if (l.prio == 0) take(l, cut = true, why = "듣기 끊고") else Act.Wait
        if (l.prio == 0) return take(l, cut = false, why = "즉시")
        if (now.thinking && l.rank >= 2) return Act.Wait                     // 질문에 답을 기다리는 중 — 제안·수다는 끼어들지 않는다
        // 위 단계가 막혀 있으면 아래 단계도 기다린다(급한 말이 먼저)
        if (now.turnBusy) return Act.Wait                                    // 회전 구간 — 지나고 나서
        if (!l.reply && t - lastAt[l.prio] < policy.cooldown[l.prio]) return Act.Wait   // 같은 단계를 방금 말했다
        return take(l, cut = false, why = policy.name)
    }

    private fun take(l: Line, cut: Boolean, why: String): Act {
        queue.remove(l)
        current?.takeIf { cut }?.let { log("prio|cut|P${it.prio}|${it.text}") }
        current = l
        if (!l.reply) lastAt[l.prio] = SystemClock.elapsedRealtime()   // 대답은 쿨다운을 쓰지 않는다 — 대답 뒤 진짜 P1 안내가 밀리지 않게
        log("prio|say|P${l.prio}${if (l.reply) "·답" else ""}|$why|${l.tag}")
        return Act.Speak(l, cut)
    }

    /** 말이 끝났다(다 말했거나 끊겼다) */
    fun finished() { current = null }

    /** 줄 선 말 중 조건에 맞는 것을 버린다("조용히" 등) */
    fun drop(why: String, pred: (Line) -> Boolean) {
        queue.removeAll { l -> pred(l).also { if (it) log("prio|drop|P${l.prio}|$why|${l.text}") } }
    }

    private fun ttl(l: Line) = if (l.reply) policy.ttl[1] else policy.ttl[l.prio].takeIf { it > 0 } ?: 60_000L

    /** 시연 — 단계별 쿨다운을 비운다(영상에서 몇 분 간격 장면을 이어 찍을 때만, 영상에 '시간 압축'으로 밝힌다) */
    fun resetCooldown() { lastAt.fill(-3_600_000L); log("prio|demo|쿨다운 초기화") }

    companion object {
        val LABEL = arrayOf("안전", "주행", "제안", "수다")
        val LABEL_PHONE = arrayOf("시각", "할 일", "챙김", "수다")

        /** 이벤트별 기본 단계 — 두뇌가 prio를 주면 그걸 따른다.
         *  휴게소(restarea)는 지나치면 소용없는 길 안내라 P1 — P2였을 땐 다른 제안(P2 쿨다운 3분)에 밀려 수명 3분이 지나 버려졌다(11-11 영상 녹화 2회) */
        fun eventPrio(ev: String) = when (ev) {
            "depart", "near", "arrived", "traffic", "rerouted", "long_drive", "no_dest", "waypoint", "restarea" -> 1
            "hello", "halfway", "mode", "battery_low" -> 2
            "quiet" -> 3
            else -> 2
        }
    }
}
