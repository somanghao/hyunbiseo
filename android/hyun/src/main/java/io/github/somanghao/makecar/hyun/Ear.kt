package io.github.somanghao.makecar.hyun

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 상시 귀 — 앱이 마이크를 직접 계속 듣고(AudioRecord 16kHz mono), 말소리 구간만 잘라(에너지 VAD) 받아 적기 서버로 보낸다.
 * 시연 = PC `server/hyun/stt_server.py`(faster-whisper) · 실서비스 = 기기 안 소형 모델이나 클라우드 STT가 같은 자리.
 *
 * - 잡음 바닥을 천천히 따라가며 그보다 충분히 큰 소리 3프레임(60ms)이면 말 시작, 0.7초 조용하면 말 끝(앞 0.3초도 함께 보낸다)
 * - 보내기 전에 음량을 정규화한다 — 에뮬 가상 마이크는 최대 -38dB로 작게 들어왔다(2026-11-06 실측, Pleos STT가 못 잡은 원인)
 * - 현비서가 말하는 동안(paused)은 버린다 — 자기 목소리를 받아 적지 않게(끝나고 0.3초 더)
 * - 호출어("현비서야") 판정·대화 창은 HyunActivity가 한다. 여기는 글자만 넘긴다
 */
class Ear(private val base: () -> String, private val out: Out) {
    interface Out {
        fun onSpeechStart()
        fun onText(text: String, sec: Double, ms: Long)
        fun onEarLevel(level: Float)
        fun onEarError(what: String)
    }

    @Volatile var paused = false
    @Volatile var running = false
        private set
    private var th: Thread? = null
    private val net = Executors.newSingleThreadExecutor()

    fun start() {
        if (running) return
        running = true
        th = Thread(::loop, "hyun-ear").also { it.start() }
    }

    fun stop() { running = false; th = null }

    /** 받아 적기 서버가 살아 있나(/stt/health) — 콜백은 다른 스레드 */
    fun ping(cb: (Boolean) -> Unit) = net.execute {
        cb(runCatching {
            val c = (URL(base() + "/stt/health").openConnection() as HttpURLConnection).apply { connectTimeout = 2000; readTimeout = 2000 }
            c.inputStream.use { it.readBytes() }; c.responseCode == 200
        }.getOrDefault(false))
    }

    private fun loop() {
        val min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, FRAME * 2 * 20))
        }.getOrElse { out.onEarError("AudioRecord ${it.javaClass.simpleName} ${it.message}"); running = false; return }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { out.onEarError("AudioRecord 초기화 실패"); rec.release(); running = false; return }
        rec.startRecording()
        log("start|${SR}Hz|buf=${max(min, FRAME * 40)}")
        val buf = ShortArray(FRAME)
        val pre = ArrayDeque<ShortArray>()
        val seg = ArrayList<ShortArray>()
        var floor = MIN_RMS / 3
        var inSpeech = false
        var loud = 0
        var quiet = 0
        var tail = 0
        var lastLevelLog = 0L
        while (running) {
            val n = rec.read(buf, 0, FRAME)
            if (n <= 0) continue
            val f = buf.copyOf(n)
            var sum = 0.0
            for (s in f) sum += s.toDouble() * s
            val rms = sqrt(sum / n)
            if (paused) { tail = 15; if (inSpeech) { inSpeech = false; seg.clear() }; pre.clear(); loud = 0; continue }
            if (tail > 0) { tail--; continue }
            if (!inSpeech) floor = if (rms < floor) floor * 0.8 + rms * 0.2 else floor * 0.998 + rms * 0.002
            val gate = max(floor * 3.0, MIN_RMS)
            out.onEarLevel((rms / (gate * 3)).toFloat().coerceIn(0f, 1f))
            val now = System.currentTimeMillis()
            if (now - lastLevelLog > 30_000) { lastLevelLog = now; log("floor|${floor.toInt()}|gate=${gate.toInt()}") }
            if (!inSpeech) {
                pre.addLast(f); if (pre.size > PRE_FRAMES) pre.removeFirst()
                loud = if (rms > gate) loud + 1 else 0
                if (loud >= 3) {
                    inSpeech = true; quiet = 0
                    seg.clear(); seg.addAll(pre); pre.clear()
                    out.onSpeechStart()
                }
            } else {
                seg.add(f)
                quiet = if (rms > gate * 0.7) 0 else quiet + 1
                val sec = seg.size * FRAME / SR.toDouble()
                if (quiet >= END_FRAMES || sec >= MAX_SEC) {
                    inSpeech = false; loud = 0
                    val voiced = sec - quiet * FRAME / SR.toDouble()
                    val clip = seg.toList(); seg.clear()
                    if (voiced >= MIN_VOICED_SEC) send(clip) else log("short|${"%.2f".format(voiced)}s")
                }
            }
        }
        runCatching { rec.stop() }; rec.release()
        log("stop")
    }

    /** 시험: 녹음 한 구간(16kHz mono PCM)을 마이크로 들은 것처럼 받아 적기 서버로 — 마이크 없이 귀 → 호출어 → 두뇌 경로 확인(--es earfile) */
    fun feed(pcm: ByteArray) {
        val n = pcm.size / 2
        send(listOf(ShortArray(n) { i -> ((pcm[2 * i].toInt() and 0xff) or (pcm[2 * i + 1].toInt() shl 8)).toShort() }))
    }

    /** 이어 붙이고 음량 정규화(최대 진폭 → 0.8, 최대 30배) 후 서버로 */
    private fun send(clip: List<ShortArray>) {
        val total = clip.sumOf { it.size }
        var peak = 1
        for (c in clip) for (s in c) peak = max(peak, abs(s.toInt()))
        val g = (26_000.0 / peak).coerceIn(1.0, 30.0)
        val bytes = ByteArray(total * 2)
        var i = 0
        for (c in clip) for (s in c) {
            val v = (s * g).toInt().coerceIn(-32768, 32767)
            bytes[i++] = (v and 0xff).toByte(); bytes[i++] = ((v shr 8) and 0xff).toByte()
        }
        val sec = total / SR.toDouble()
        log("send|${"%.1f".format(sec)}s|peak=$peak|gain=${"%.1f".format(g)}")
        net.execute {
            val t0 = System.currentTimeMillis()
            runCatching {
                val c = (URL(base() + "/stt").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"; doOutput = true; connectTimeout = 3000; readTimeout = 15_000
                    setRequestProperty("Content-Type", "application/octet-stream"); setFixedLengthStreamingMode(bytes.size)
                }
                c.outputStream.use { it.write(bytes) }
                JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optString("text")
            }.onSuccess { out.onText(it, sec, System.currentTimeMillis() - t0) }
                .onFailure { out.onEarError("stt ${it.javaClass.simpleName} ${it.message}") }
        }
    }

    private fun log(s: String) { Log.i("HYUN", "ear|$s") }

    companion object {
        const val SR = 16_000
        private const val FRAME = 320            // 20ms
        private const val PRE_FRAMES = 15        // 말 시작 앞 0.3초
        private const val END_FRAMES = 35        // 0.7초 조용하면 끝
        private const val MAX_SEC = 12.0
        private const val MIN_VOICED_SEC = 0.35
        private const val MIN_RMS = 45.0         // 에뮬 조용할 때 평균 -59dB(≈37) 위
    }
}
