package io.github.somanghao.makecar.hyun.brain

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 현비서 서버(PC, server/hyun/hyun_server.py) 연결 — 들은 말·주행 이벤트를 보내고, 두뇌(Claude Code 세션)의 답을 long-poll로 받는다.
 * 콜백은 백그라운드 스레드에서 불린다(화면 갱신은 부르는 쪽이 main으로 넘긴다). 실패는 null.
 */
class Link(var base: String) {
    private val io = Executors.newCachedThreadPool()

    fun send(kind: String, text: String, ctx: JSONObject, done: (Int?) -> Unit) = io.execute {
        // cid: 응답을 못 받아 다시 보내도 서버가 같은 메시지로 알아본다
        val body = JSONObject().put("kind", kind).put("text", text).put("ctx", ctx).put("cid", UUID.randomUUID().toString())
        done(http("POST", "/hyun/in", body)?.optInt("id"))
    }

    /** 답이 올 때까지 25초 long-poll을 되풀이, [totalMs]가 지나면 null */
    fun awaitReply(id: Int, totalMs: Long, done: (JSONObject?) -> Unit) = io.execute {
        val end = System.currentTimeMillis() + totalMs
        while (System.currentTimeMillis() < end) {
            val left = ((end - System.currentTimeMillis()) / 1000).coerceIn(1, 25)
            val r = http("GET", "/hyun/reply?id=$id&timeout=$left", readMs = (left * 1000 + 5000).toInt())
            if (r == null) { Thread.sleep(1000); continue }
            if (!r.optBoolean("pending")) { done(r); return@execute }
        }
        done(null)
    }

    /** {brain: 답할 두뇌가 있나, mode: session|api|null, login: Claude API 로그인 여부} — 서버가 없으면 null */
    fun health(done: (JSONObject?) -> Unit) = io.execute { done(http("GET", "/hyun/health")) }

    fun memory(done: (JSONObject?) -> Unit) = io.execute { done(http("GET", "/hyun/memory")) }

    /** 폰 리마인더·할 일(두뇌가 --remind·--todo로 넣는다) */
    fun tasks(done: (JSONObject?) -> Unit) = io.execute { done(http("GET", "/hyun/tasks")) }

    /** 울린 리마인더 끝냄 표시 */
    fun fired(id: Int) = io.execute { http("POST", "/hyun/tasks", JSONObject().put("fired", id)) }

    /** 폰 연동 — 차가 보낸 것(장소 등)을 long-poll로 기다린다(부르는 쪽 스레드에서 막힘). {items:[…], seq, car:{t, ago, q, a}} · 서버 없으면 null */
    fun pushBlocking(after: Long, timeoutS: Int = 25): JSONObject? =
        once("GET", "/hyun/push?after=$after&timeout=$timeoutS", null, (timeoutS + 10) * 1000)

    /** 폰 연동 — 차에서 나눈 이야기 {items:[{t, ago, src, who, q, a, card}], phone} */
    fun history(n: Int, done: (JSONObject?) -> Unit) = io.execute { done(http("GET", "/hyun/history?src=car&n=$n")) }

    /** 한 번 실패하면 한 번 더 — 에뮬 → PC 연결에서 "unexpected end of stream"이 가끔 난다(서버를 HTTP/1.1로 바꿔 줄임) */
    private fun http(method: String, path: String, body: JSONObject? = null, readMs: Int = 5000): JSONObject? =
        once(method, path, body, readMs) ?: once(method, path, body, readMs)

    private fun once(method: String, path: String, body: JSONObject?, readMs: Int): JSONObject? = runCatching {
        val c = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 3000; readTimeout = readMs
            if (body != null) { doOutput = true; setRequestProperty("Content-Type", "application/json; charset=utf-8") }
        }
        try {
            body?.let { b -> c.outputStream.use { it.write(b.toString().toByteArray(Charsets.UTF_8)) } }
            JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally { c.disconnect() }
    }.onFailure { Log.w("HYUN", "link|$method $path|${it.javaClass.simpleName} ${it.message}") }.getOrNull()
}
