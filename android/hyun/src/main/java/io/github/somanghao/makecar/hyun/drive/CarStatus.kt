package io.github.somanghao.makecar.hyun.drive

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log

/**
 * 주행 중 "내 차 상태" — 출처를 둘로 나눠 읽고 합친다(값마다 어디서 왔는지 표시):
 *   1) Pleos Vehicle SDK(ai.pleos.playground.vehicle) — 실차·Pleos 에뮬 이미지에서 채워진다(Odometer · TurnSignal · DrivingMode · EvBattery/Fuel)
 *   2) AAOS CarPropertyManager — 표준 AAOS 에뮬에서도 되는 것(방향지시등 · 배터리/연료 · 주행 가능 거리 · 누적 주행 · 문 잠김 · 실내 설정 온도)
 *   둘 다 없으면 null → 화면이 "예시" 값으로 채우고 옅게 표시. 새 출처(예: 다른 SDK)는 [Source] 하나 더 만들면 된다.
 * 2초마다 [poll] (StageFeature.tick). 콜백은 아무 스레드 — 값만 바꾸고 화면은 다음 그리기에서 읽는다.
 */
class CarStatus(private val ctx: Context) {
    enum class From { PLEOS, AAOS, APP, MOCK }
    class V<T>(@Volatile var v: T? = null, @Volatile var from: From = From.MOCK) {
        fun set(x: T?, f: From) { if (x != null) { v = x; from = f } }
        fun or(def: T): T = v ?: def
    }

    val soc = V<Int>()            // 배터리·연료 %
    val energyLabel = V<String>() // "배터리" · "연료"
    val rangeKm = V<Int>()
    val tripMin = V<Int>()
    val tripKm = V<Float>()
    val odoKm = V<Int>()
    val turn = V<Int>()           // 0 꺼짐 · 1 왼쪽 · 2 오른쪽 · 3 비상등
    val cruise = V<String>()      // "꺼짐" · "대기" · "작동"
    val cruiseKmh = V<Int>()
    val mode = V<String>()        // ECO · NORMAL · SPORT
    val cabinC = V<Float>()
    val locked = V<Boolean>()

    interface Source { fun start(); fun poll(); fun stop() }

    private val sources = mutableListOf<Source>()

    fun start() {
        sources += AaosSource(); sources += PleosSource()   // Pleos 가 뒤 — 같은 값이면 Pleos 가 덮는다
        sources.forEach { s -> runCatching { s.start() }.onFailure { log("status|start|${s.javaClass.simpleName}|${it.message}") } }
    }
    fun poll() = sources.forEach { s -> runCatching { s.poll() } }
    fun stop() = sources.forEach { s -> runCatching { s.stop() } }

    /** 앱이 직접 잰 주행 시간·거리(속도 적분) — SDK 값이 없을 때 */
    fun appTrip(min: Int, km: Float) {
        if (tripMin.from != From.PLEOS) tripMin.set(min, From.APP)
        if (tripKm.from != From.PLEOS) tripKm.set(km, From.APP)
    }

    // ---------- AAOS ----------
    private inner class AaosSource : Source {
        private var car: Car? = null
        private var pm: CarPropertyManager? = null
        private val dead = mutableSetOf<Int>()
        override fun start() { car = Car.createCar(ctx); pm = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager }
        private fun <T> get(id: Int, area: Int = 0): T? {
            if (id in dead) return null
            return runCatching { pm?.getProperty<T>(id, area)?.value }.onFailure { dead += id; log("status|aaos|${VehiclePropertyIds.toString(id)}|${it.javaClass.simpleName}") }.getOrNull()
        }
        override fun poll() {
            val evCap = get<Float>(VehiclePropertyIds.INFO_EV_BATTERY_CAPACITY); val ev = get<Float>(VehiclePropertyIds.EV_BATTERY_LEVEL)
            val fCap = get<Float>(VehiclePropertyIds.INFO_FUEL_CAPACITY); val f = get<Float>(VehiclePropertyIds.FUEL_LEVEL)
            if (ev != null && evCap != null && evCap > 0) { soc.set((ev / evCap * 100).toInt(), From.AAOS); energyLabel.set("배터리", From.AAOS) }
            else if (f != null && fCap != null && fCap > 0) { soc.set((f / fCap * 100).toInt(), From.AAOS); energyLabel.set("연료", From.AAOS) }
            get<Float>(VehiclePropertyIds.RANGE_REMAINING)?.let { rangeKm.set((it / 1000).toInt(), From.AAOS) }
            get<Float>(VehiclePropertyIds.PERF_ODOMETER)?.let { odoKm.set(it.toInt(), From.AAOS) }
            get<Int>(VehiclePropertyIds.TURN_SIGNAL_STATE)?.let { turn.set(when (it) { 1 -> 2; 2 -> 1; 3 -> 3; else -> 0 }, From.AAOS) }   // VehicleTurnSignal: 1 RIGHT · 2 LEFT
            if (locked.from != From.PLEOS) get<Boolean>(VehiclePropertyIds.DOOR_LOCK, 1)?.let { locked.set(it, From.AAOS) }
            if (cabinC.from != From.PLEOS) get<Float>(VehiclePropertyIds.HVAC_TEMPERATURE_SET, 0x31)?.let { cabinC.set(it, From.AAOS) }   // 운전석+조수석(ROW_1)
        }
        override fun stop() { runCatching { car?.disconnect() } }
    }

    // ---------- Pleos Vehicle SDK ----------
    private inner class PleosSource : Source {
        private var v: Vehicle? = null
        private var fails = 0
        private var polls = 0
        override fun start() { v = Vehicle(ctx).also { it.initialize() } }
        private fun any() = listOf(soc, rangeKm, tripMin, tripKm, turn, cruise, mode).any { it.from == From.PLEOS }
        override fun poll() {
            val x = v ?: return
            polls++
            if (fails > 40 || (polls > 15 && !any())) return   // 표준 AAOS 에뮬 — Pleos 서비스가 없다(30초 동안 한 값도 안 오면 그만 부른다)
            val err: (Exception) -> Unit = { fails++ }
            runCatching {
                x.getOdometer().getTripCurrentDrivingTime({ s -> s?.let { tripMin.set(it / 60, From.PLEOS) } }, err)
                x.getOdometer().getTripCurrentDrivingDistance({ d -> d?.let { tripKm.set(it, From.PLEOS) } }, err)
                x.getOdometer().getRangeRemainingDistance({ d -> d?.let { rangeKm.set(it.toInt(), From.PLEOS) } }, err)
                x.getTurnSignal().getCurrentTurnSignalState({ t -> turn.set(when (t) { TurnSignalValue.LEFT -> 1; TurnSignalValue.RIGHT -> 2; else -> 0 }, From.PLEOS) }, err)
                x.getDrivingMode().getCruiseControlState({ c -> cruise.set(when (c?.name) { "ACTIVATED" -> "작동"; "ENABLED", "SUSPENDED", "USER_OVERRIDE" -> "대기"; else -> "꺼짐" }, From.PLEOS) }, err)
                x.getDrivingMode().getCruiseControlTargetSpeed({ s -> s?.let { cruiseKmh.set(it.toInt(), From.PLEOS) } }, err)
                x.getDrivingMode().getCurrentDrivingModeState({ m -> m?.let { mode.set(it.name, From.PLEOS) } }, err)
                x.getEvBattery().getEvBatteryStateOfCharge({ s -> s?.let { soc.set(it, From.PLEOS); energyLabel.set("배터리", From.PLEOS) } }, err)
            }.onFailure { fails += 8; if (fails <= 8) log("status|pleos|${it.javaClass.simpleName} ${it.message}") }
        }
        override fun stop() { runCatching { v?.release() } }
    }

    private fun log(s: String) = Log.i("HYUN", s)
}
