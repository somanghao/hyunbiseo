package io.github.somanghao.makecar.hyun.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import ai.pleos.playground.stt.SpeechToText as PleosStt
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.stt.listener.ResultListener as SttResults
import ai.pleos.playground.tts.TextToSpeech as PleosTts
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener as TtsEvents
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener
import android.speech.tts.TextToSpeech as AndroidTts

/**
 * 현비서의 귀(STT)·입(TTS). 차(Pleos Connect)는 Pleos SDK, 폰(샤오미 등)은 안드로이드 표준 음성으로 같은 일을 한다.
 * 콜백은 아무 스레드에서나 온다 — 받는 쪽(HyunActivity)이 main으로 넘긴다.
 */
interface Voice {
    val canSpeak: Boolean
    val canListen: Boolean
    val name: String
    fun speak(text: String)
    fun stopSpeaking()
    fun listen()
    fun stopListening()
    fun release()
    /** 시험용 — 마이크 대신 녹음(16kHz·16bit·mono PCM)을 귀에 넣는다. 지원 안 하면 false */
    fun feed(pcm: ByteArray): Boolean = false

    interface Events {
        fun onVoiceReady()                         // 입 준비 끝(첫 인사 시점)
        fun onSpeakDone()                          // 다 말함(오류 포함)
        fun onSpeakStopped()                       // 끊김
        fun onHearReady() {}                       // 엔진이 실제로 녹음을 시작함(첫 요청은 모델 로딩으로 몇 초 늦다)
        fun onHearStart()                          // 말소리 시작
        fun onHeard(text: String, final: Boolean)  // 중간·최종 결과
        fun onHearEnd()                            // 최종 결과 없이 인식 끝(조용·못 알아들음)
        fun onHearError(what: String)
        fun onLevel(level: Float)                  // 0..1 — 캐릭터 입·귀 움직임
    }

    companion object {
        fun create(ctx: Context, car: Boolean, ev: Events): Voice = if (car) PleosVoice(ctx, ev) else PhoneVoice(ctx, ev)
    }
}

/** 차량 — Pleos TextToSpeech / SpeechToText(ON_DEVICE). 에뮬 실측: 말소리가 없으면 STT가 끝없이 기다린다(앱이 시간 제한) */
class PleosVoice(ctx: Context, private val ev: Voice.Events) : Voice {
    override var canSpeak = false; private set
    override var canListen = false; private set
    override val name = "Pleos"
    private val tts = PleosTts(ctx.applicationContext, TtsMode.ON_DEVICE)
    private val stt = PleosStt(ctx.applicationContext, SttMode.ON_DEVICE)

    init {
        runCatching {
            tts.initialize(object : TtsInitListener {
                override fun onSuccess() {
                    tts.addEventListener(object : TtsEvents {
                        override fun onReady() {}
                        override fun onStart() {}
                        override fun onUpdatedRms(rms: Double) = ev.onLevel((rms / 10.0).toFloat().coerceIn(0f, 1f))
                        override fun onDone() = ev.onSpeakDone()
                        override fun onStop() = ev.onSpeakStopped()
                        override fun onError(errorType: TtsError) { log("tts|error|$errorType"); ev.onSpeakDone() }
                    })
                    canSpeak = true; log("tts|ready"); ev.onVoiceReady()
                }
                override fun onFailure(errorType: TtsError) { log("tts|init|$errorType") }
            })
        }.onFailure { log("tts|init|${it.message}") }
        runCatching {
            stt.initialize(object : SttInitListener {
                override fun onSuccess() {
                    stt.setLocale(Locale.KOREA)
                    stt.addListener(object : SttResults {
                        override fun onReady() { log("stt|ready"); ev.onHearReady() }
                        override fun onStartedRecognition() { log("stt|start"); ev.onHearStart() }
                        override fun onUpdatedRms(rms: Double) = ev.onLevel((rms / 10.0).toFloat().coerceIn(0f, 1f))
                        override fun onUpdatedEpdData(start: Long, end: Long) { log("stt|epd|$start-$end") }
                        override fun onUpdated(text: String, isFinal: Boolean) { log("stt|text|${if (isFinal) "final" else "part"}|$text"); ev.onHeard(text, isFinal) }
                        override fun onEndedRecognition() { log("stt|end"); ev.onHearEnd() }
                        override fun onError(errorType: SttError) { log("stt|error|$errorType"); ev.onHearError(errorType.toString()) }
                    })
                    canListen = true; log("stt|init|OK")
                }
                override fun onFailure(errorType: SttError) { log("stt|init|$errorType") }
            })
        }.onFailure { log("stt|init|${it.message}") }
    }

    override fun speak(text: String) { runCatching { tts.speak(text, true) }.onFailure { log("tts|speak|${it.message}"); ev.onSpeakDone() } }
    override fun stopSpeaking() { runCatching { tts.stop() } }
    override fun listen() { runCatching { stt.request() }.onFailure { log("stt|request|${it.message}"); ev.onHearError("request") } }
    override fun stopListening() { runCatching { stt.stop() } }
    override fun release() { runCatching { stt.release() }; runCatching { tts.release() } }

    /**
     * request() 뒤 sendAudio(100ms씩) → completeAudioSend() — 마이크 없이 STT 인식을 시험하려던 경로.
     * ⚠️ 에뮬 v2.1.0 ON_DEVICE에서 이걸 부르면 시스템 음성 서비스(ai.pleos.playground.caas)가 네이티브 SIGSEGV로 죽는다
     * (SpeechToText.recognize, 2026-11-06 실측 — 글레오 연결까지 끊겼다 재접속). 그래서 막아 둔다: 다시 시험하려면 SERVER 모드에서만.
     */
    override fun feed(pcm: ByteArray): Boolean {
        if (!FEED_ENABLED || !canListen) return false
        runCatching { stt.request() }.onFailure { log("stt|feed|request|${it.message}"); return false }
        Thread {
            Thread.sleep(400)
            var i = 0
            while (i < pcm.size) {
                val n = minOf(3200, pcm.size - i)
                runCatching { stt.sendAudio(pcm.copyOfRange(i, i + n)) }.onFailure { log("stt|feed|send|${it.message}"); return@Thread }
                i += n
                Thread.sleep(100)
            }
            runCatching { stt.completeAudioSend() }.onFailure { log("stt|feed|complete|${it.message}") }
            log("stt|feed|sent|${pcm.size}B")
        }.start()
        return true
    }

    companion object { private const val FEED_ENABLED = false }
}

/**
 * 폰 — 안드로이드 TextToSpeech + SpeechRecognizer. 한국어 음성이 없는 기본 엔진(일부 샤오미)이면 구글 TTS로 다시 붙는다.
 * 인식기: 기기 기본 → 없으면 온디바이스(API 31+). SpeechRecognizer는 main 스레드에서 만들고 부른다.
 */
class PhoneVoice(private val ctx: Context, private val ev: Voice.Events) : Voice {
    override var canSpeak = false; private set
    override var canListen = false; private set
    override var name = "Android"; private set
    private var tts: AndroidTts? = null
    private var rec: SpeechRecognizer? = null
    private var n = 0
    @Volatile private var lastId = ""
    private var triedGoogle = false

    init {
        initTts(null)
        initRecognizer()
    }

    private fun initTts(engine: String?) {
        val l = AndroidTts.OnInitListener { status ->
            val t = tts ?: return@OnInitListener
            if (status != AndroidTts.SUCCESS) { log("tts|init|FAIL|$status|${engine ?: "default"}"); fallbackGoogle(); return@OnInitListener }
            val lang = t.setLanguage(Locale.KOREAN)
            val engines = t.engines.map { it.name }
            log("tts|engine=${t.defaultEngine}|use=${engine ?: "default"}|ko=$lang|engines=$engines")
            if (lang < AndroidTts.LANG_AVAILABLE && fallbackGoogle(engines)) return@OnInitListener
            t.setSpeechRate(1.05f)
            t.setPitch(1.08f)   // 캐릭터 목소리 — 살짝 밝게
            // 새 말이 앞 말을 끊으면(QUEUE_FLUSH) 앞 말의 끝 알림이 늦게 온다 — 마지막 말(lastId) 것만 넘긴다
            t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) { ev.onLevel(0.6f) }
                override fun onDone(id: String?) { if (id == lastId) ev.onSpeakDone() }
                @Deprecated("API 21") override fun onError(id: String?) { log("tts|error|$id"); if (id == lastId) ev.onSpeakDone() }
                override fun onError(id: String?, code: Int) { log("tts|error|$id|$code"); if (id == lastId) ev.onSpeakDone() }
                override fun onStop(id: String?, interrupted: Boolean) { if (id == lastId) ev.onSpeakStopped() }
                override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) = ev.onLevel(1f)   // 어절마다 입을 연다
            })
            name = "Android TTS(${engine ?: t.defaultEngine})"
            canSpeak = true; log("tts|ready"); ev.onVoiceReady()
        }
        tts = if (engine == null) AndroidTts(ctx.applicationContext, l) else AndroidTts(ctx.applicationContext, l, engine)
    }

    /** 기본 엔진이 한국어를 못 하면 구글 TTS로 — 이미 구글이거나 설치 안 됐으면 false */
    private fun fallbackGoogle(engines: List<String> = emptyList()): Boolean {
        if (triedGoogle || (engines.isNotEmpty() && GOOGLE_TTS !in engines) || tts?.defaultEngine == GOOGLE_TTS) return false
        triedGoogle = true
        log("tts|retry with $GOOGLE_TTS")
        runCatching { tts?.shutdown() }
        initTts(GOOGLE_TTS)
        return true
    }

    private fun initRecognizer() {
        val r = when {
            SpeechRecognizer.isRecognitionAvailable(ctx) -> SpeechRecognizer.createSpeechRecognizer(ctx)
            Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx) -> SpeechRecognizer.createOnDeviceSpeechRecognizer(ctx)
            else -> { log("stt|init|인식 서비스 없음(구글 앱·음성 입력 확인)"); return }
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { log("stt|ready"); ev.onHearReady() }
            override fun onBeginningOfSpeech() { log("stt|start"); ev.onHearStart() }
            override fun onRmsChanged(rmsdB: Float) = ev.onLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                log("stt|error|${err(error)}")
                // 조용했거나 못 알아들음 = 끝(들은 게 있으면 그걸로) · 그 밖은 오류
                if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) ev.onHearEnd()
                else ev.onHearError(err(error))
            }
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                if (t.isNotBlank()) ev.onHeard(t, true) else ev.onHearEnd()
            }
            override fun onPartialResults(partial: Bundle?) {
                partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { ev.onHeard(it, false) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        rec = r; canListen = true; log("stt|init|OK")
    }

    override fun speak(text: String) {
        val t = tts ?: return ev.onSpeakDone()
        lastId = "hyun-${++n}"
        if (t.speak(text, AndroidTts.QUEUE_FLUSH, Bundle(), lastId) != AndroidTts.SUCCESS) { log("tts|speak|FAIL"); ev.onSpeakDone() }
    }
    override fun stopSpeaking() { runCatching { tts?.stop() } }

    override fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            // 말 사이 잠깐 쉬어도 끝내지 않게("3시간 후 … 이불 빨래"가 둘로 쪼개졌다, 폰 실측) — 엔진이 지원하면 적용
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1800L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        runCatching { rec?.startListening(i) ?: ev.onHearError("인식 서비스 없음") }.onFailure { log("stt|request|${it.message}"); ev.onHearError("request") }
    }
    override fun stopListening() { runCatching { rec?.cancel() } }
    override fun release() { runCatching { rec?.destroy() }; runCatching { tts?.shutdown() } }

    private fun err(e: Int) = when (e) {
        SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"; SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
        SpeechRecognizer.ERROR_AUDIO -> "AUDIO"; SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "PERMISSION"; SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"; SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "BUSY"
        SpeechRecognizer.ERROR_SERVER -> "SERVER"; else -> "E$e"
    }

    companion object { private const val GOOGLE_TTS = "com.google.android.tts" }
}

private fun log(line: String) { Log.i("HYUN", "voice|$line") }
