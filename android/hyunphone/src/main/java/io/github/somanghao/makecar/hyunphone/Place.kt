package io.github.somanghao.makecar.hyunphone

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/** 가게 [전화] · [지도] — 전화는 ACTION_DIAL(번호만 입력, 통화는 사람이 누름 · CALL_PHONE 권한 안 씀) · 지도는 카카오 장소 페이지 */
object Place {
    fun dial(c: Context, p: JSONObject) {
        val tel = p.optString("tel").filter { it.isDigit() || it == '+' }
        if (tel.isNotBlank()) runCatching { c.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$tel"))) }
    }

    fun mapUrl(p: JSONObject): String = p.optString("url").ifBlank {
        if (p.has("lat")) "https://map.kakao.com/link/map/${Uri.encode(p.optString("name"))},${p.optDouble("lat")},${p.optDouble("lng")}" else ""
    }

    fun map(c: Context, p: JSONObject) {
        mapUrl(p).takeIf { it.isNotBlank() }?.let { u -> runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u))) } }
    }
}
