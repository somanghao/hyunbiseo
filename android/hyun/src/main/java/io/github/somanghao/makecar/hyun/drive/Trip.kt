package io.github.somanghao.makecar.hyun.drive

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 목적지까지의 진행 + 연속 주행 시간.
 * 남은 거리는 NaviHelper 값이 바뀔 때마다 그 값을 기준으로 다시 잡고, 그 사이엔 속도를 적분해 줄인다
 * (에뮬레이터는 Virtual Console로 속도만 바꾸고 GPS는 그대로라 내비 값이 안 줄어든다 — 이때도 진행이 보이게).
 */
class Trip {
    var dest: String? = null
        private set
    var totalM = 0.0
        private set
    private var baseM = 0.0      // 마지막으로 받은 남은 거리
    private var baseS = 0.0      // 그때 남은 시간(초) — 모르면 0
    private var moved = 0.0      // 그 뒤로 달린 거리
    var driveS = 0.0             // 연속 주행 시간 — 5분 넘게 서 있으면 0으로
        private set
    private var stoppedS = 0.0
    val fired = mutableSetOf<String>()   // 이번 목적지에서 이미 말한 이벤트(출발·절반·곧 도착·도착)

    val remainM get() = max(0.0, baseM - moved)
    val remainS get() = when {
        dest == null -> 0.0
        baseS > 0 && baseM > 0 -> baseS * remainM / baseM
        else -> remainM / AVG_MS
    }
    val progress get() = if (totalM > 0) (1 - remainM / totalM).coerceIn(0.0, 1.0) else 0.0

    private var estimated = false  // 전체 거리가 직선거리 어림 — 내비 경로가 오면 그 값으로 바꾼다

    fun setDestination(name: String, remainM: Double, remainS: Double, estimate: Boolean = false) {
        dest = name; totalM = remainM; baseM = remainM; baseS = remainS; moved = 0.0; estimated = estimate
        fired.clear()
    }

    /** NaviHelper가 준 남은 거리·시간. 같은 값이 반복되면(에뮬 GPS 고정) 무시해 적분한 진행을 지키고, 바뀌면 그 값을 기준으로 */
    fun navi(name: String?, remainM: Double, remainS: Double) {
        if (remainM <= 0) return
        if (dest == null) { setDestination(name ?: "목적지", remainM, remainS); return }
        if (name != null && name.isNotBlank()) dest = name
        if (estimated) { estimated = false; totalM = remainM + moved }
        // 거리가 같아도 시간은 받는다 — 어림 거리가 내비 값과 우연히 같아(13000m) 내비 시간을 버리고 시속 30km 어림(26분)을 보였다(11-07, 내비는 22분).
        // 정체로 도착 예정만 바뀌는 경우도 같다. 달린 거리(moved)는 그대로 둔다
        if (remainM == baseM) { if (remainS > 0) baseS = remainS; return }
        baseM = remainM; baseS = remainS; moved = 0.0
        if (remainM > totalM) totalM = remainM
    }

    fun clear() { dest = null; totalM = 0.0; baseM = 0.0; baseS = 0.0; moved = 0.0; estimated = false; fired.clear() }

    fun tick(dt: Double, speedMs: Double) {
        if (dest != null) moved += speedMs * dt
        if (speedMs > 1.4) { driveS += dt; stoppedS = 0.0 }
        else { stoppedS += dt; if (stoppedS > 300) driveS = 0.0 }
    }

    companion object {
        private const val AVG_MS = 30 / 3.6   // 내비 시간이 없을 때 평균 시속 30km로 어림

        /** 두 좌표 사이 직선거리(m) */
        fun meters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val r = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1); val dLng = Math.toRadians(lng2 - lng1)
            val a = sin(dLat / 2).let { it * it } + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).let { it * it }
            return 2 * r * asin(sqrt(a))
        }
    }
}
