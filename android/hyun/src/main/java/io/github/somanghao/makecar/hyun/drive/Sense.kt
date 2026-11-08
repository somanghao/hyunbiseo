package io.github.somanghao.makecar.hyun.drive

import android.annotation.SuppressLint
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.Locale

/**
 * 현비서의 눈 — 속도·기어·주행 규제(DO)·위치. 차는 AAOS Car API, 폰은 GPS(LocationManager).
 * 차량 클래스(android.car)는 폰에 없다 → 이 파일의 CarSense는 차에서만 만든다(폰에서 건드리면 NoClassDefFoundError).
 */
interface Sense {
    fun start()
    fun stop()

    /** 값이 바뀌면 부른다 — 아무 스레드 */
    interface Out {
        fun onSpeed(ms: Float)
        fun onGear(g: Char)
        fun onDo(on: Boolean)
        fun onPlace(lat: Double, lng: Double, addr: String?)
        /** 시동 상태(VehicleIgnitionState: 1 LOCK · 2 OFF · 3 ACC · 4 ON · 5 START) — 차만 */
        fun onIgnition(state: Int) {}
    }
}

/** 차량(AAOS) — PERF_VEHICLE_SPEED · GEAR_SELECTION · UX 제한(DO). 위치·주소는 NaviHelper가 준다 */
class CarSense(private val ctx: Context, private val out: Sense.Out) : Sense {
    private var car: Car? = null
    private var cpm: CarPropertyManager? = null
    private var cb: CarPropertyManager.CarPropertyEventCallback? = null
    private var uxm: CarUxRestrictionsManager? = null

    override fun start() {
        val c = runCatching { Car.createCar(ctx) }.getOrElse { log("car|createCar|${it.message}"); return }
        car = c
        val pm = runCatching { c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager }.getOrNull() ?: return
        cpm = pm
        runCatching { out.onSpeed(pm.getProperty<Float>(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0).value ?: 0f) }
        runCatching { out.onGear(gearChar(pm.getProperty<Int>(VehiclePropertyIds.GEAR_SELECTION, 0).value ?: 4)) }
        runCatching { out.onIgnition(pm.getProperty<Int>(VehiclePropertyIds.IGNITION_STATE, 0).value ?: 4) }.onFailure { log("car|ignition|${it.message}") }
        val k = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(v: CarPropertyValue<*>) {
                when (v.propertyId) {
                    VehiclePropertyIds.PERF_VEHICLE_SPEED -> (v.value as? Float)?.let(out::onSpeed)
                    VehiclePropertyIds.GEAR_SELECTION -> out.onGear(gearChar((v.value as? Int) ?: 4))
                    VehiclePropertyIds.IGNITION_STATE -> (v.value as? Int)?.let(out::onIgnition)
                }
            }
            override fun onErrorEvent(propId: Int, zone: Int) { log("car|error|$propId") }
        }
        cb = k
        runCatching { pm.registerCallback(k, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL) }.onFailure { log("car|speed|${it.message}") }
        runCatching { pm.registerCallback(k, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE) }.onFailure { log("car|gear|${it.message}") }
        runCatching { pm.registerCallback(k, VehiclePropertyIds.IGNITION_STATE, CarPropertyManager.SENSOR_RATE_ONCHANGE) }.onFailure { log("car|ignition|${it.message}") }
        runCatching {
            val ux = (c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager).also { uxm = it }
            out.onDo(ux.currentCarUxRestrictions.isRequiresDistractionOptimization)
            ux.registerListener { r -> out.onDo(r.isRequiresDistractionOptimization) }
        }.onFailure { log("ux|${it.message}") }
    }

    override fun stop() {
        runCatching { cb?.let { cpm?.unregisterCallback(it) } }
        runCatching { uxm?.unregisterListener() }
        runCatching { car?.disconnect() }
    }

    private fun gearChar(v: Int) = when (v) { 4 -> 'P'; 2 -> 'R'; 1 -> 'N'; else -> 'D' } // VehicleGear: PARK 4 · REVERSE 2 · NEUTRAL 1 · DRIVE 8
}

/**
 * 폰 — GPS 속도로 주행 여부(차에 거치한 폰), 위치로 주소·날씨. 기어는 속도로 흉내(움직이면 D, 멈추면 P).
 * 실내에선 GPS가 안 잡혀 네트워크 위치만 온다(속도 없음 = 정차로 본다).
 */
class PhoneSense(private val ctx: Context, private val out: Sense.Out) : Sense {
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var gpsAt = 0L
    private var lastAddrAt = 0L
    private var moving = false

    private val l = LocationListener { loc -> onFix(loc) }

    @SuppressLint("MissingPermission")   // 권한은 HyunActivity가 먼저 받는다 — 없으면 SecurityException을 삼킨다
    override fun start() {
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            runCatching {
                if (!lm.isProviderEnabled(p)) { log("loc|$p|off"); return@runCatching }
                lm.getLastKnownLocation(p)?.let { onFix(it) }
                lm.requestLocationUpdates(p, if (p == LocationManager.GPS_PROVIDER) 1000L else 10_000L, 0f, l, Looper.getMainLooper())
                log("loc|$p|on")
            }.onFailure { log("loc|$p|${it.javaClass.simpleName} ${it.message}") }
        }
    }

    override fun stop() { runCatching { lm.removeUpdates(l) } }

    private fun onFix(loc: Location) {
        val now = SystemClock.elapsedRealtime()
        if (loc.provider == LocationManager.GPS_PROVIDER) {
            gpsAt = now
            val v = if (loc.hasSpeed()) loc.speed else 0f
            out.onSpeed(v)
            val m = v > MOVE_MS
            if (m != moving) { moving = m; out.onGear(if (m) 'D' else 'P') }
        } else if (now - gpsAt > 10_000) {   // GPS가 끊겼으면 정차로
            out.onSpeed(0f)
            if (moving) { moving = false; out.onGear('P') }
        }
        if (now - lastAddrAt < 60_000 && lastAddrAt != 0L) { out.onPlace(loc.latitude, loc.longitude, null); return }
        lastAddrAt = now
        Thread {
            @Suppress("DEPRECATION")
            val a = runCatching { Geocoder(ctx, Locale.KOREA).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull() }.getOrNull()
            val addr = a?.let { listOfNotNull(it.locality ?: it.subAdminArea, it.subLocality, it.thoroughfare).distinct().joinToString(" ").ifBlank { null } }
            out.onPlace(loc.latitude, loc.longitude, addr)
        }.start()
    }

    companion object { const val MOVE_MS = 2.8f }   // 10km/h — 폰 GPS는 서 있어도 흔들려 차(1.4)보다 높게
}

private fun log(line: String) { Log.i("HYUN", line) }
