package io.github.somanghao.makecar.hyun.feature

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import android.content.Intent
import android.os.SystemClock
import io.github.somanghao.makecar.hyun.core.Feature
import io.github.somanghao.makecar.hyun.core.Hyun
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/** 날씨 — 처음 받은 위치로 Open-Meteo(키 없음) 지금 기온 · 6시간 비 확률. 대화 소재 · 출발 한마디("비 소식이 있으니") */
class WeatherFeature : Feature {
    override val id = "weather"
    private lateinit var hub: Hyun
    var tempC: Float? = null
        private set
    var rainPct: Int? = null
        private set

    override fun start(hub: Hyun) { this.hub = hub }

    override fun onLocation(lat: Double, lng: Double, addr: String?, first: Boolean) { if (first) forecast(lat, lng) }

    private fun forecast(la: Double, lo: Double) = Thread {
        runCatching {
            val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$la&longitude=$lo" +
                "&current=temperature_2m&hourly=precipitation_probability&forecast_hours=6&timezone=auto")
            val c = (url.openConnection() as HttpURLConnection).apply { connectTimeout = 5000; readTimeout = 5000 }
            val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val rain = j.getJSONObject("hourly").getJSONArray("precipitation_probability")
            val t = j.getJSONObject("current").getDouble("temperature_2m").toFloat()
            val r = (0 until rain.length()).maxOfOrNull { rain.optInt(it, 0) } ?: 0
            hub.log("weather|$la,$lo|${t}C|rain6h=$r%")
            hub.main.post { tempC = t; rainPct = r }
        }.onFailure { hub.log("weather|FAIL|${it.javaClass.simpleName} ${it.message}") }
    }.start()

    override fun ctx(c: JSONObject) { tempC?.let { c.put("tempC", it.roundToInt()); c.put("rainPct", rainPct ?: 0) } }
}

/** 차 배터리(Vehicle SDK, 전기차) — 맥락 soc · 20% 아래면 한 번 알림(30% 넘게 채우면 다시) · 두뇌 없을 때 "배터리" 답 */
class CarBatteryFeature : Feature {
    override val id = "car-battery"
    override fun enabled(car: Boolean) = car
    private lateinit var hub: Hyun
    private var vehicle: Vehicle? = null
    var soc: Int? = null
        private set
    private var low = false

    override fun start(hub: Hyun) {
        this.hub = hub
        val v = Vehicle(hub.act).also { vehicle = it }
        runCatching { v.initialize() }.onFailure { hub.log("vehicle|init|${it.message}"); return }
        hub.main.postDelayed({   // 서비스 바인딩 대기(H1·에이전트와 같은 방식) · 권한이 없으면 register가 동기 예외를 던진다
            runCatching { v.getEvBattery().getEvBatteryStateOfCharge({ x -> hub.main.post { soc = x } }, { e -> hub.log("ev.soc|FAIL|${e.message}") }) }
                .onFailure { hub.log("ev.soc|THROW|${it.message}") }
            runCatching {
                v.getEvBattery().registerEvBatteryStateOfCharge(object : EvBatteryStateOfChargeListener {
                    override fun onEvBatteryStateOfChargeUpdated(x: Int?) { hub.main.post { soc = x } }
                    override fun onFailed(e: Exception) { hub.log("ev.soc.register|FAIL|${e.message}") }
                })
            }.onFailure { hub.log("ev.soc.register|THROW|${it.message}") }
        }, 1500)
    }

    override fun stop() { runCatching { vehicle?.release() } }

    override fun tick() {
        val s = soc ?: return
        if (s <= 20 && !low) { low = true; hub.fire("battery_low") } else if (s >= 30) low = false
    }

    override fun ctx(c: JSONObject) { soc?.let { c.put("soc", it) } }
    override fun offline(text: String): String? = if (text.contains("배터리") && soc != null) "배터리는 ${soc}퍼센트예요." else null
    override fun fallback(ev: String, extra: JSONObject?): String? = if (ev == "battery_low") "배터리가 ${soc}퍼센트예요. 가는 길에 충전소를 들를까요?" else null
}

/**
 * 운전자 챙기기(차) — 연속 90분 운전이면 쉬어 가자(이후 30분마다) · 주행 중 한동안(12분) 말이 없으면 현비서가 먼저 가볍게 말 걸기(quiet 이벤트, 두뇌가 있을 때만).
 * 쉼표 모듈(휴게소 쉬어 가기)·말동무 모드가 붙을 자리. 시연: --ei longmin 3 --ei quietmin 2
 */
class DriveCareFeature : Feature {
    override val id = "drive-care"
    override fun enabled(car: Boolean) = car
    private lateinit var hub: Hyun
    private var longMin = 90
    private var quietMin = 12
    private var nextLongS = 0.0

    override fun start(hub: Hyun) { this.hub = hub; nextLongS = longMin * 60.0 }

    override fun onIntent(i: Intent, first: Boolean) {
        if (i.hasExtra("longmin")) { longMin = i.getIntExtra("longmin", longMin); nextLongS = longMin * 60.0 }
        if (i.hasExtra("quietmin")) quietMin = i.getIntExtra("quietmin", quietMin)
    }

    override fun tick() {
        val trip = hub.trip
        if (trip.driveS < 60) nextLongS = longMin * 60.0           // 쉬고 나면 다시 처음부터
        if (trip.driveS >= nextLongS) { nextLongS += 30 * 60; hub.fire("long_drive") }
        // 한동안 말이 없으면(주행 중·두뇌 연결·조용히 아닐 때만) 먼저 말을 건다 — 맥락(말없이 N분)을 보낸 뒤에 시계를 되돌린다
        if (hub.driving && hub.brainOn && hub.idle() && !hub.quiet() && SystemClock.elapsedRealtime() - hub.lastTalkAt > quietMin * 60_000L) {
            hub.fire("quiet")
            hub.lastTalkAt = SystemClock.elapsedRealtime()
        }
    }

    override fun fallback(ev: String, extra: JSONObject?): String? =
        if (ev == "long_drive") "${(hub.trip.driveS / 60).toInt()}분째 운전 중이에요. 다음 휴게소에서 잠깐 쉬어 가요." else null
}
