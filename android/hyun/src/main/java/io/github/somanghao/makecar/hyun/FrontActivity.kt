package io.github.somanghao.makecar.hyun

import android.app.Activity
import android.os.Bundle

/**
 * 보이지 않는 한순간 화면(Theme.NoDisplay) — 켜자마자 닫는다. 내비 명령(경로·취소·경유지·옵션) **직전에** 코어가 띄운다.
 * 왜: 내비 명령을 받으면 내비 앱이 스스로 맨 앞으로 나와 현비서를 덮는다(11-08 실측 — 내비는 SYSTEM_ALERT_WINDOW라 뒤에서도 뜬다).
 * 덮인 뒤 현비서를 다시 앞으로 부르려면 '방금 화면을 띄운 앱'(10초 유예, BAL_ALLOW_GRACE_PERIOD)이어야 한다 —
 * 이미 떠 있는 HyunActivity에 인텐트를 다시 보내는 것으론 유예가 안 생긴다(실측 BAL_BLOCK). → core/Hyun.keepFront
 */
class FrontActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}
