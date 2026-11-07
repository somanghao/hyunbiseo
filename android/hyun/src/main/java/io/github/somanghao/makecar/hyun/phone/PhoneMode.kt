package io.github.somanghao.makecar.hyun.phone

import io.github.somanghao.makecar.hyun.voice.Speaker

/** 폰 모드 — 운전이 아닌 때의 짭비서. 모드가 말하기 정책과 출력(소리·자막)을 바꾼다 */
enum class PhoneMode(val key: String, val label: String, val policy: Speaker.Policy) {
    TRANSIT("transit", "대중교통", Speaker.Policy.TRANSIT),
    WALK("walk", "산책", Speaker.Policy.WALK),
    WORK("work", "업무중", Speaker.Policy.WORK);

    companion object { fun of(k: String?) = entries.firstOrNull { it.key == k || it.label == k } }
}
