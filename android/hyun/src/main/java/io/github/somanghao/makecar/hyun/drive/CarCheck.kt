package io.github.somanghao.makecar.hyun.drive

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log

/**
 * 시동 점검 — 서드파티 앱이 AAOS CarPropertyManager 로 **실제로 읽을 수 있는 것만** 읽고, 못 읽는 건 "확인 불가"로 솔직하게 표시한다.
 * 타이어 공기압·안전 텔테일은 서드파티 차단(현대차 확인, 메모 hyundai-facts-from-nash) → 항목은 보이되 늘 "차량 계기판에서 확인".
 * 차가 아닌 기기(폰)에선 Car 클래스가 없다 — 부르는 쪽이 isCar 일 때만.
 */
object CarCheck {
    enum class State { OK, WARN, NA }
    data class Item(val key: String, val label: String, val value: String, val state: State, val note: String = "")

    fun read(ctx: Context): List<Item> {
        val car = runCatching { Car.createCar(ctx) }.getOrNull()
        val pm = runCatching { car?.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager }.getOrNull()
        fun <T> get(id: Int, area: Int = 0): T? = runCatching { pm?.getProperty<T>(id, area)?.value }.onFailure { Log.i("HYUN", "check|${VehiclePropertyIds.toString(id)}|${it.javaClass.simpleName}") }.getOrNull()
        val out = mutableListOf<Item>()
        // 차 정보
        val make = get<String>(VehiclePropertyIds.INFO_MAKE).orEmpty(); val model = get<String>(VehiclePropertyIds.INFO_MODEL).orEmpty()
        val year = get<Int>(VehiclePropertyIds.INFO_MODEL_YEAR)
        val name = listOf(make, model).filter { it.isNotBlank() }.joinToString(" ")
        out += if (name.isNotBlank()) Item("car", "차량", name + (year?.let { " · ${it}년식" } ?: ""), State.OK) else Item("car", "차량", "정보 없음", State.NA)
        // 에너지 — 전기차 배터리 % (잔량 Wh / 용량 Wh) · 연료 %
        val evCap = get<Float>(VehiclePropertyIds.INFO_EV_BATTERY_CAPACITY); val ev = get<Float>(VehiclePropertyIds.EV_BATTERY_LEVEL)
        val fuelCap = get<Float>(VehiclePropertyIds.INFO_FUEL_CAPACITY); val fuel = get<Float>(VehiclePropertyIds.FUEL_LEVEL)
        val pct = when {
            ev != null && evCap != null && evCap > 0 -> (ev / evCap * 100).toInt() to "배터리"
            fuel != null && fuelCap != null && fuelCap > 0 -> (fuel / fuelCap * 100).toInt() to "연료"
            else -> null
        }
        out += pct?.let { (v, l) -> Item("energy", l, "$v%", if (v < 20) State.WARN else State.OK, if (v < 20) "충전·주유가 필요해요" else "") }
            ?: Item("energy", "배터리·연료", "확인 불가", State.NA, "권한 없음")
        // 0은 아직 안 채워진 값(배터리 100%인데 0km — 11-11 에뮬) → 확인 불가로
        val range = get<Float>(VehiclePropertyIds.RANGE_REMAINING)?.takeIf { it > 0f }
        out += range?.let { Item("range", "주행 가능", "${(it / 1000).toInt()}km", if (it < 50_000) State.WARN else State.OK) } ?: Item("range", "주행 가능", "확인 불가", State.NA)
        val odo = get<Float>(VehiclePropertyIds.PERF_ODOMETER)
        out += odo?.let { Item("odo", "누적 주행", "${"%,d".format(it.toInt())}km", State.OK) } ?: Item("odo", "누적 주행", "확인 불가", State.NA, "권한 없음")
        // 주차 상태
        val gear = get<Int>(VehiclePropertyIds.GEAR_SELECTION)
        out += gear?.let { Item("gear", "기어", if (it == 4) "P (주차)" else gearName(it), if (it == 4) State.OK else State.WARN) } ?: Item("gear", "기어", "확인 불가", State.NA)
        val pb = get<Boolean>(VehiclePropertyIds.PARKING_BRAKE_ON)
        out += pb?.let { Item("brake", "주차 브레이크", if (it) "잠김" else "풀림", State.OK) } ?: Item("brake", "주차 브레이크", "확인 불가", State.NA)
        val lights = get<Int>(VehiclePropertyIds.HEADLIGHTS_STATE)
        out += lights?.let { Item("lights", "전조등", when (it) { 0 -> "꺼짐"; 1 -> "켜짐"; 2 -> "주간등"; else -> "-" }, State.OK) } ?: Item("lights", "전조등", "확인 불가", State.NA)
        out += Item("tire", "타이어 공기압", "계기판에서 확인", State.NA, "외부 앱엔 막혀 있어요")
        runCatching { car?.disconnect() }
        return out
    }

    private fun gearName(g: Int) = when (g) { 1 -> "N"; 2 -> "R"; 8 -> "D"; else -> "D" }
}
