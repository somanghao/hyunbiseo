package io.github.somanghao.makecar.hyun.feature

import android.content.Intent
import io.github.somanghao.makecar.hyun.core.Feature
import io.github.somanghao.makecar.hyun.core.Hyun
import io.github.somanghao.makecar.hyun.ui.skin.Skin
import io.github.somanghao.makecar.hyun.ui.skin.Skins

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

/** 차 화면 두 가지(사용자 11-09) — 대화형(큰 캐릭터 + 말풍선) ↔ 정보형(왼쪽 캐릭터 · 오른쪽 내비 판·표·답).
 *  화면 왼쪽 아래 [대화형 | 정보형] 두 칸 · 말("대화형으로 바꿔 줘" · "정보 화면으로") · 시연 `--es ui chat|info`. 앱 설정에 남는다(기본 정보형).
 *  듣기는 늘 상시(호출어) — 예전 '눌러서 말하기' 버튼·모드·말 명령은 같은 날 없앴다 */
class UiModeFeature : Feature {
    override val id = "ui-mode"
    override fun enabled(car: Boolean) = car
    private lateinit var hub: Hyun

    override fun start(hub: Hyun) {
        this.hub = hub
        hub.view.uiChat = hub.prefs.getString("ui", "info") == "chat"
        hub.view.onUiMode = { set(it, byVoice = false) }
    }

    override fun onIntent(i: Intent, first: Boolean) {
        when (i.getStringExtra("ui")) { "chat" -> set(true, false); "info" -> set(false, false) }
    }

    override fun onHeard(text: String): Boolean {
        val t = text.replace(" ", "")
        val chat = when {
            Regex("대화형|대화화면|대화모드").containsMatchIn(t) -> true
            Regex("정보형|정보화면|정보모드").containsMatchIn(t) -> false
            else -> return false
        }
        set(chat, byVoice = true)
        return true
    }

    private fun set(chat: Boolean, byVoice: Boolean) {
        hub.view.uiChat = chat
        hub.prefs.edit().putString("ui", if (chat) "chat" else "info").apply()
        hub.log("ui|${if (chat) "chat" else "info"}|${if (byVoice) "voice" else "tap"}")
        if (byVoice) hub.say(if (chat) "대화 화면으로 바꿨어요." else "정보 화면으로 바꿨어요.", "calm", false, 1, reply = true, tag = "화면")
        hub.render()
    }
}

/** 스킨(차 화면 겉모습 — 배치·색·모양·글꼴, 사용자 11-09) — 기본 · 내쉬(웹툰) · 팀원 스킨(`assets/skins/<id>.json`을 넣으면 목록에 뜬다 → README).
 *  왼쪽 아래 팔레트 버튼 = 다음 스킨 · 말("내쉬 스킨으로" · "기본 스킨" · "스킨 바꿔") · 시연 `--es skin nash` · 앱 설정 `skin` */
class SkinFeature : Feature {
    override val id = "skin"
    override fun enabled(car: Boolean) = car
    private lateinit var hub: Hyun

    override fun start(hub: Hyun) {
        this.hub = hub
        hub.view.skin = Skins.get(hub.act, hub.prefs.getString("skin", "basic"))
        hub.view.onSkin = { next(byVoice = false) }
        hub.log("skin|start|${hub.view.skin.id}|목록 ${Skins.all(hub.act).joinToString(",") { it.id }}")
    }

    override fun onIntent(i: Intent, first: Boolean) {
        i.getStringExtra("skin")?.let { set(Skins.get(hub.act, it), byVoice = false) }
    }

    override fun onHeard(text: String): Boolean {
        val t = text.replace(" ", "")
        if (!t.contains("스킨")) return false
        val hit = Skins.all(hub.act).firstOrNull { s -> t.contains(s.name.replace(" ", "")) || t.contains(s.id) || (s.author.isNotBlank() && t.contains(s.author)) }
        when {
            hit != null -> set(hit, byVoice = true)
            Regex("바꿔|바꾸|다음|변경").containsMatchIn(t) -> next(byVoice = true)
            else -> return false
        }
        return true
    }

    private fun next(byVoice: Boolean) {
        val all = Skins.all(hub.act)
        set(all[(all.indexOfFirst { it.id == hub.view.skin.id } + 1) % all.size], byVoice)
    }

    private fun set(s: Skin, byVoice: Boolean) {
        hub.view.skin = s
        hub.prefs.edit().putString("skin", s.id).apply()
        hub.log("skin|${s.id}|${if (byVoice) "voice" else "tap"}")
        if (byVoice) hub.say("${s.name} 스킨으로 바꿨어요.", "happy", false, 1, reply = true, tag = "스킨")
        hub.render()
    }
}

/** 캐릭터 4종 — 얼굴 화면(dan-emu 기본, 헬멧 로봇) · 사람 비서 · 젤리 구슬 · 빛 구슬(스킨과 따로 — 어느 스킨에서나 고른다). 왼쪽 아래 ⇄ 버튼 · 말("캐릭터 바꿔줘" · "비서로 바꿔" · "구슬로 바꿔"), 앱 설정에 저장 */
class CharacterFeature : Feature {
    override val id = "character"
    override fun enabled(car: Boolean) = car
    private lateinit var hub: Hyun

    override fun start(hub: Hyun) {
        this.hub = hub
        hub.view.character = hub.prefs.getString("character2", "face") ?: "face"   // 얼굴 화면이 기본(사용자 11-08 — 새 키라 예전 선택 대신 얼굴부터)
        hub.view.onCharacter = { set(CHARS[(CHARS.indexOf(hub.view.character) + 1) % CHARS.size]) }
    }

    override fun onHeard(text: String): Boolean {
        val s = text.replace(" ", "")
        if (!Regex("캐릭터|모습|비서로|구슬로|얼굴로").containsMatchIn(s) || !Regex("바꿔|바꾸|변경|해줘|로해").containsMatchIn(s)) return false
        val ch = when {
            Regex("얼굴|로봇").containsMatchIn(s) -> "face"
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
        hub.prefs.edit().putString("character2", ch).apply()
        hub.log("character|$ch"); hub.render()
    }

    companion object {
        private val CHARS = listOf("face", "human", "orb3d", "orb")
        private val HELLO = mapOf("face" to "얼굴 화면으로 바꿨어요.", "human" to "비서 모습으로 바꿨어요.", "orb3d" to "젤리 구슬로 바꿨어요.", "orb" to "빛 구슬로 바꿨어요.")
    }
}
