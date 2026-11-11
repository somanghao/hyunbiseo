package io.github.somanghao.makecar.hyunphone

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 현비서 서버(차 현비서와 같은 서버 — server/hyun) 연결. 주소 = 설정(⚙) 또는 인텐트 `--es server http://…`, 기본은 에뮬 → PC `http://10.0.2.2:8790`.
 * 실패는 null.
 */
object Server {
    const val DEFAULT = "http://10.0.2.2:8790"
    @Volatile var base = DEFAULT

    fun load(c: Context) { base = prefs(c).getString("server", DEFAULT) ?: DEFAULT }
    fun save(c: Context, url: String) { base = url.trim().trimEnd('/'); prefs(c).edit().putString("server", base).apply() }
    private fun prefs(c: Context) = c.getSharedPreferences("hyunphone", Context.MODE_PRIVATE)

    /** 차에서 나눈 이야기(+ 서버 메모리에 남은 장소 목록) {items:[{mid, t, ago, who, q, a, card, places, places_gone}], phone} */
    fun history(n: Int): JSONObject? = get("/hyun/history?src=car&n=$n", 6000)

    /** 차에서 오는 것(대화 동기화 kind=talk · 명시 전송 kind=place)을 기다린다 — 기다리는 동안이 서버가 보는 '폰 연결됨' */
    fun push(after: Long, timeoutS: Int): JSONObject? = get("/hyun/push?after=$after&timeout=$timeoutS", (timeoutS + 10) * 1000)

    private fun get(path: String, readMs: Int): JSONObject? = runCatching {
        val c = (URL(base + path).openConnection() as HttpURLConnection).apply { connectTimeout = 3000; readTimeout = readMs }
        try { JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }) } finally { c.disconnect() }
    }.onFailure { Log.w("HYUNPHONE", "server|$path|${it.javaClass.simpleName} ${it.message}") }.getOrNull()
}
