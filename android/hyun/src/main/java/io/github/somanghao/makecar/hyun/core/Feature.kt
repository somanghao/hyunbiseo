package io.github.somanghao.makecar.hyun.core

import android.content.Intent
import io.github.somanghao.makecar.hyun.ui.HyunView
import org.json.JSONObject

/**
 * 기능 플러그인 — 현비서의 기능(길 · 경로 정찰 · 글레오 · 폰 모드 · 앞으로 붙일 쉼표 · 온기 · 엔터 · 튜토리얼 …)은 이걸 구현해
 * `feature/Registry.kt`에 **한 줄** 등록한다. 코어([Hyun])는 대화(듣기 · 말하기 · 두뇌 묻기 · 답 나누기) · 1초 루프 · 맥락 · 화면만 갖고,
 * 기능 쪽 일은 아래 훅으로 나눠 준다. 필요한 훅만 구현하면 된다(전부 기본 구현 있음).
 *
 * - 순서 = 등록 순서. 가로채는 훅([onHeard] · [offline] · [fallback])은 앞 기능이 처리하면 뒤로 안 간다
 * - 모든 훅은 main 스레드에서 불린다. 오래 걸리는 일은 기능이 스스로 스레드로
 * - 내비 콜백이 필요하면 [start]에서 `hub.navi?.listen(…)`(NaviHelperEventListener — 필요한 메서드만 override)
 * - 다른 기능이 필요하면 `hub.feature<OtherFeature>()`(없으면 null — 그 기능을 빼도 돌아가게)
 * - 서버 쪽 짝(두뇌 규칙 · 답 형식 필드 · 질문 블록 · 답 후처리)은 `server/hyun/features/` — 앱 기능 하나 = 앱 Feature + 서버 feature 모듈
 */
interface Feature {
    /** 로그·조회용 짧은 이름 */
    val id: String

    /** 이 기기에서 켤지 — 차(Pleos)·폰(짭비서). 기본 둘 다 */
    fun enabled(car: Boolean): Boolean = true

    /** 코어가 준비된 뒤 한 번(onCreate) — 화면 버튼 연결·설정 읽기·내비 듣기 등록 */
    fun start(hub: Hyun) {}

    /** 앱이 끝날 때(onDestroy) — 잡은 자원 놓기 */
    fun stop() {}

    /** 1초마다 — 주행 이벤트 판정 등 */
    fun tick() {}

    /** 시연·글레오 등 인텐트(처음 켤 때 first=true — 내비·두뇌 연결을 기다리게 지연을 두는 편) */
    fun onIntent(i: Intent, first: Boolean) {}

    /** 운전자 말을 두뇌보다 먼저 본다 — 앱이 바로 할 명령("조용히" · "상시 모드" · "캐릭터 바꿔")이면 처리(말도 직접)하고 true */
    fun onHeard(text: String): Boolean = false

    /** 두뇌 답(대답 kind="talk" · 이벤트 kind="event", text = 말 또는 이벤트 이름) — 이 기능이 아는 필드만(nav · via · route · mode …) */
    fun onReply(kind: String, text: String, r: JSONObject) {}

    /** 두뇌에 보낼 맥락에 보태기 */
    fun ctx(c: JSONObject) {}

    /** 화면 값 채우기(1초·말할 때마다) */
    fun render(v: HyunView) {}

    /** 위치가 바뀜(차 = 내비 · 폰 = GPS) — first = 처음 받은 위치 */
    fun onLocation(lat: Double, lng: Double, addr: String?, first: Boolean) {}

    /** 목적지가 새로 정해졌거나 길 안내가 끝남 — 경로에 묶인 상태(정찰 · 휴게소 알림 등)를 비운다 */
    fun onTripReset() {}

    /** 권한 결과가 왔다(폰) */
    fun onPermissions() {}

    /** 두뇌가 없을 때 이 말에 앱이 바로 할 답(사실만 — 남은 거리 · 배터리 등), 모르면 null */
    fun offline(text: String): String? = null

    /** 두뇌가 없거나 늦을 때 이 이벤트의 규칙 문장, 모르면 null */
    fun fallback(ev: String, extra: JSONObject?): String? = null
}
