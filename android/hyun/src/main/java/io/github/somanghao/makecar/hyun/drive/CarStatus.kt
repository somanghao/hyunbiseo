package io.github.somanghao.makecar.hyun.drive

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log

/**
 * 차 데이터 층 — 현비서의 반응·추천이 읽는 "내 차 상태". 값마다 출처를 기억한다:
 *   1) Pleos Vehicle SDK(ai.pleos.playground.vehicle) — 실차·Pleos 에뮬 이미지: Odometer · Brake · SteeringWheel · TurnSignal · DrivingMode(크루즈·모드)
 *      · EvBattery/Fuel · HVAC(온도 읽기·설정) · Door · Window · Wiper · Light · Seat(착석·벨트) · CarInfo
 *   2) AAOS CarPropertyManager — 표준 AAOS 에뮬에서도: 배터리/연료 · 주행 가능 거리 (그 밖은 시그니처 권한이라 외부 앱엔 막힘)
 *   3) 모의(시뮬레이터) — 디버그 판·방송(--es sim "wiper=on,passenger=on,…")으로 넣은 값. 넣으면 고정(pinned)돼 실제 값이 덮지 않는다
 * 2초마다 [poll](StageFeature.tick, 뒤 스레드). 새 출처는 [Source] 하나 더.
 */
class CarStatus(private val ctx: Context) {
    enum class From { PLEOS, AAOS, APP, MOCK }
    class V<T>(@Volatile var v: T? = null, @Volatile var from: From = From.MOCK) {
        @Volatile var pinned = false
        fun set(x: T?, f: From) { if (x != null && (!pinned || f == From.MOCK)) { v = x; from = f } }
        fun pin(x: T?) { pinned = x != null; v = x; from = From.MOCK }
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
    val cabinC = V<Float>()       // 운전석 설정 온도
    val locked = V<Boolean>()
    val steer = V<Float>()        // 조향각(도)
    val brake = V<Boolean>()      // 브레이크 페달
    val wiper = V<Boolean>()      // 앞 와이퍼 작동
    val doorOpen = V<Boolean>()   // 운전석 문 열림
    val windowOpen = V<Boolean>() // 운전석 창 열림
    val lowBeam = V<Boolean>()    // 전조등
    val passenger = V<Boolean>()  // 조수석 착석
    val rearKid = V<Boolean>()    // 뒷좌석 착석
    val belt = V<Boolean>()       // 운전석 벨트
    val vin = V<String>()

    interface Source { fun start(); fun poll(); fun stop() }
    private val sources = mutableListOf<Source>()
    private var pleos: PleosSource? = null

    fun start() {
        sources += AaosSource(); sources += PleosSource().also { pleos = it }   // Pleos 가 뒤 — 같은 값이면 Pleos 가 덮는다
        sources.forEach { s -> runCatching { s.start() }.onFailure { log("status|start|${s.javaClass.simpleName}|${it.message}") } }
    }
    fun poll() = sources.forEach { s -> runCatching { s.poll() } }
    fun stop() = sources.forEach { s -> runCatching { s.stop() } }
    val pleosLive get() = pleos?.alive == true

    /** 앱이 직접 잰 주행 시간·거리(속도 적분) — SDK 값이 없을 때 */
    fun appTrip(min: Int, km: Float) {
        if (tripMin.from != From.PLEOS) tripMin.set(min, From.APP)
        if (tripKm.from != From.PLEOS) tripKm.set(km, From.APP)
    }

    /** 시뮬레이터 — "wiper=on,passenger=on,soc=18,cabin=27,turn=left,cruise=100,door=open,window=open,belt=off,mode=ECO,steer=120,lights=on,kid=on,reset" */
    fun sim(spec: String): List<String> {
        val done = mutableListOf<String>()
        for (kv in spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val k = kv.substringBefore('='); val v = kv.substringAfter('=', "")
            val on = v in setOf("on", "1", "true", "open", "yes")
            when (k) {
                "reset" -> listOf(soc, rangeKm, turn, cruise, cruiseKmh, mode, cabinC, locked, steer, wiper, doorOpen, windowOpen, lowBeam, passenger, rearKid, belt)
                    .forEach { it.pinned = false; it.v = null }
                "soc" -> { soc.pin(v.toIntOrNull()); rangeKm.pin(v.toIntOrNull()?.let { it * 5 }) }
                "range" -> rangeKm.pin(v.toIntOrNull())
                "turn" -> turn.pin(when (v) { "left" -> 1; "right" -> 2; "hazard" -> 3; else -> 0 })
                "cruise" -> { val n = v.toIntOrNull(); cruise.pin(if (n != null) "작동" else if (on) "대기" else "꺼짐"); cruiseKmh.pin(n) }
                "mode" -> mode.pin(v.uppercase())
                "cabin" -> cabinC.pin(v.toFloatOrNull())
                "steer" -> steer.pin(v.toFloatOrNull())
                "wiper" -> wiper.pin(on)
                "door" -> doorOpen.pin(on)
                "window" -> windowOpen.pin(on)
                "lights" -> lowBeam.pin(on)
                "passenger" -> passenger.pin(on)
                "kid" -> rearKid.pin(on)
                "belt" -> belt.pin(on)
                "lock" -> locked.pin(on)
                else -> continue
            }
            done += kv
        }
        return done
    }

    /** 실내 온도 설정 — Pleos HVAC(실차), 안 되면 false(모의 값만 바꿈) */
    fun setCabin(c: Float): Boolean {
        val ok = pleos?.setTemp(c) == true
        if (!ok) cabinC.pin(c) else cabinC.set(c, From.PLEOS)
        return ok
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
            get<Int>(VehiclePropertyIds.HEADLIGHTS_STATE)?.let { lowBeam.set(it == 1, From.AAOS) }
            get<Boolean>(VehiclePropertyIds.DOOR_LOCK, 1)?.let { locked.set(it, From.AAOS) }
            get<Float>(VehiclePropertyIds.HVAC_TEMPERATURE_SET, 0x31)?.let { cabinC.set(it, From.AAOS) }
        }
        override fun stop() { runCatching { car?.disconnect() } }
    }

    // ---------- Pleos Vehicle SDK ----------
    private inner class PleosSource : Source {
        private var v: Vehicle? = null
        private var fails = 0
        private var polls = 0
        @Volatile var alive = false
        private val driver = SeatArea(SeatValue.ROW_1_LEFT)
        override fun start() { v = Vehicle(ctx).also { it.initialize() } }
        private fun ok() { if (!alive) log("status|pleos|alive"); alive = true }
        fun setTemp(c: Float): Boolean = runCatching {
            if (!alive) return false
            v?.getHVAC()?.setHvacTemperature(driver, c, TemperatureUnit.CELSIUS, { _, _ -> }, { e -> log("status|hvac.set|${e.message}") }); true
        }.getOrDefault(false)
        override fun poll() {
            val x = v ?: return
            polls++
            // 표준 AAOS 에뮬 — Pleos 서비스가 없다(30초 동안 한 값도 안 오면 그만 부른다). 한 번이라도 값이 왔으면 계속 —
            // 지원 안 하는 항목(실내 온도·문 등 ERROR_NOT_SUPPORTED_PROPERTY)의 실패가 쌓여 1분 만에 전체를 멈췄다(11-10 fails=66)
            if (!alive && (fails > 60 || polls > 15)) { if (polls == 16) log("status|pleos|give-up|fails=$fails"); polls++; return }
            val err: (Exception) -> Unit = { e -> fails++; if (fails <= 3) log("status|pleos|err|${e.javaClass.simpleName} ${e.message}") }   // 왜 못 읽는지 처음 몇 개만 남긴다(11-10: Pleos 에뮬인데 와이퍼·문·좌석이 '예시'로 나왔다)
            runCatching {
                x.getOdometer().getTripCurrentDrivingTime({ s -> s?.let { ok(); tripMin.set(it / 60, From.PLEOS) } }, err)
                x.getOdometer().getTripCurrentDrivingDistance({ d -> d?.let { ok(); tripKm.set(it, From.PLEOS) } }, err)
                x.getOdometer().getRangeRemainingDistance({ d -> d?.let { ok(); rangeKm.set(it.toInt(), From.PLEOS) } }, err)
                x.getOdometer().getCurrentDistanceDriven({ d -> d?.let { ok(); odoKm.set(it.toInt(), From.PLEOS) } }, err)
                x.getTurnSignal().getCurrentTurnSignalState({ t -> ok(); turn.set(when (t) { TurnSignalValue.LEFT -> 1; TurnSignalValue.RIGHT -> 2; else -> 0 }, From.PLEOS) }, err)
                x.getDrivingMode().getCruiseControlState({ c -> ok(); cruise.set(when (c?.name) { "ACTIVATED" -> "작동"; "ENABLED", "SUSPENDED", "USER_OVERRIDE" -> "대기"; else -> "꺼짐" }, From.PLEOS) }, err)
                x.getDrivingMode().getCruiseControlTargetSpeed({ s -> s?.let { cruiseKmh.set(it.toInt(), From.PLEOS) } }, err)
                x.getDrivingMode().getCurrentDrivingModeState({ m -> m?.let { mode.set(it.name, From.PLEOS) } }, err)
                x.getSteeringWheel().getCurrentSteeringWheelAngle({ a -> a?.let { steer.set(it, From.PLEOS) } }, err)
                x.getBrake().getCurrentBrakePedalStatus({ b -> b?.let { brake.set(it, From.PLEOS) } }, err)
                x.getEvBattery().getEvBatteryStateOfCharge({ s -> s?.let { ok(); soc.set(it, From.PLEOS); energyLabel.set("배터리", From.PLEOS) } }, err)
                x.getHVAC().getHvacTemperature(driver, TemperatureUnit.CELSIUS, { t -> t?.let { cabinC.set(it, From.PLEOS) } }, err)
                x.getWiper().getCurrentWiperState(WindowArea(WindowValue.FRONT_WINDSHIELD), { w -> w?.let { wiper.set(it.name == "ON", From.PLEOS) } }, err)
                x.getDoor().getCurrentDoorPosition(DoorArea(DoorValue.ROW_1_LEFT), { p -> p?.let { doorOpen.set(it > 0, From.PLEOS) } }, err)
                x.getDoor().getDoorLockState(DoorArea(DoorValue.ROW_1_LEFT), { l -> l?.let { locked.set(it, From.PLEOS) } }, err)
                x.getWindow().getCurrentWindowPosition(WindowArea(WindowValue.ROW_1_LEFT), { p -> p?.let { windowOpen.set(it > 10, From.PLEOS) } }, err)
                x.getLight().getCurrentLowBeamLightState({ l -> l?.let { lowBeam.set(it.name == "ON", From.PLEOS) } }, err)
                x.getSeat().getCurrentSeatOccupancy(SeatArea(SeatValue.ROW_1_RIGHT), { o -> o?.let { passenger.set(it, From.PLEOS) } }, err)
                x.getSeat().getCurrentSeatOccupancy(SeatArea(SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT), { o -> o?.let { rearKid.set(it, From.PLEOS) } }, err)
                x.getSeat().getCurrentSeatBeltBuckledState(driver, { b -> b?.let { belt.set(it, From.PLEOS) } }, err)
                if (vin.v == null) x.getCarInfo().getVINInfo({ s -> s?.let { vin.set(it, From.PLEOS) } }, err)
            }.onFailure { fails += 8; if (fails <= 8) log("status|pleos|${it.javaClass.simpleName} ${it.message}") }
        }
        override fun stop() { runCatching { v?.release() } }
    }

    private fun log(s: String) = Log.i("HYUN", s)
}
