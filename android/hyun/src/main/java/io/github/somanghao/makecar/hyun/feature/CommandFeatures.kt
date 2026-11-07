package io.github.somanghao.makecar.hyun.feature

import io.github.somanghao.makecar.hyun.core.Feature
import io.github.somanghao.makecar.hyun.core.Hyun

/** "조용히 해 줘" → 10분 동안 제안·수다를 끈다(안전 경고·주행 필수·대답은 그대로) / "이제 말해도 돼" → 해제. 두뇌 없이 앱이 바로 */
class QuietFeature : Feature {
    override val id = "quiet"
    private lateinit var hub: Hyun
    override fun start(hub: Hyun) { this.hub = hub }

    override fun onHeard(text: String): Boolean {
        val t = text.replace(" ", "")
        val on = when {
            OFF.any { it in t } -> false
            ON.any { it in t } -> true
            else -> return false
        }
        hub.say(hub.setQuiet(on), "calm", false, 1, reply = true, tag = "조용히")
        return true
    }

    companion object {
        private val OFF = listOf("말해도돼", "다시말해", "조용히안해도", "조용히그만")
        private val ON = listOf("조용히", "말걸지마", "말시키지마", "좀조용", "쉿")
    }
}

/** 듣기 모드 말 명령 — "상시 모드로 해 줘" → 상시(호출어) · "버튼 모드 / 눌러서 말하기로" → 버튼(차). 화면 두 칸 버튼은 코어가 바로 */
class ListenModeFeature : Feature {
    override val id = "listen-mode"
    override fun enabled(car: Boolean) = car
    private lateinit var hub: Hyun
    override fun start(hub: Hyun) { this.hub = hub }

    override fun onHeard(text: String): Boolean {
        val t = text.replace(" ", "")
        val always = when {
            listOf("상시모드", "상시듣기", "항상들어", "계속들어").any { it in t } -> true
            listOf("버튼모드", "눌러서말하기", "누를때만", "상시듣기꺼", "상시모드꺼").any { it in t } -> false
            else -> return false
        }
        hub.setListenMode(always, byVoice = true)
        return true
    }
}

/** 캐릭터 3종 — 사람 비서(기본) · 젤리 구슬 · 빛 구슬. 마이크 옆 ⇄ 버튼 · 말("캐릭터 바꿔줘" · "비서로 바꿔" · "구슬로 바꿔"), 앱 설정에 저장 */
class CharacterFeature : Feature {
    override val id = "character"
    override fun enabled(car: Boolean) = car
    private lateinit var hub: Hyun

    override fun start(hub: Hyun) {
        this.hub = hub
        hub.view.character = hub.prefs.getString("character", "human") ?: "human"   // 사람 비서가 기본(사용자 11-06)
        hub.view.onCharacter = { set(CHARS[(CHARS.indexOf(hub.view.character) + 1) % CHARS.size]) }
    }

    override fun onHeard(text: String): Boolean {
        val s = text.replace(" ", "")
        if (!Regex("캐릭터|모습|비서로|구슬로").containsMatchIn(s) || !Regex("바꿔|바꾸|변경|해줘|로해").containsMatchIn(s)) return false
        val ch = when {
            Regex("사람|비서로|여자").containsMatchIn(s) -> "human"
            Regex("젤리|3D|입체").containsMatchIn(s) -> "orb3d"
            Regex("구슬").containsMatchIn(s) -> "orb"
            else -> CHARS[(CHARS.indexOf(hub.view.character) + 1) % CHARS.size]
        }
        set(ch)
        hub.say(HELLO[ch] ?: "바꿨어요.", "happy", false, 1, reply = true, tag = "캐릭터")
        return true
    }

    private fun set(ch: String) {
        hub.view.character = ch
        hub.prefs.edit().putString("character", ch).apply()
        hub.log("character|$ch"); hub.render()
    }

    companion object {
        private val CHARS = listOf("human", "orb3d", "orb")
        private val HELLO = mapOf("human" to "비서 모습으로 바꿨어요.", "orb3d" to "젤리 구슬로 바꿨어요.", "orb" to "빛 구슬로 바꿨어요.")
    }
}
