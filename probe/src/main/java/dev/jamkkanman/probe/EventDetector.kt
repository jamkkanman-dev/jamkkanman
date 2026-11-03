package dev.jamkkanman.probe

import android.car.VehicleGear

object Gears {
    fun name(gear: Int?): String = when (gear) {
        null -> "모름"
        VehicleGear.GEAR_PARK -> "P"
        VehicleGear.GEAR_REVERSE -> "R"
        VehicleGear.GEAR_NEUTRAL -> "N"
        VehicleGear.GEAR_DRIVE -> "D"
        VehicleGear.GEAR_UNKNOWN -> "알 수 없음"
        else -> "기타($gear)"
    }
}

/**
 * 페달 위험 순간 판정 미리보기.
 * 기준값은 모두 시연용이며 공식 안전 기준이 아니다.
 * (참고: 교통안전공단은 '시속 15km 이하에서 가속페달 80% 이상'을 오조작 의심 기준으로 썼다.
 *  Pleos SDK에는 가속 페달 값이 없어 차속 변화로 근사한다.)
 */
class EventDetector(private val report: Report) {

    private data class Sample(val tMs: Long, val kmh: Double)

    private val samples = ArrayDeque<Sample>()
    private val lastFiredAt = mutableMapOf<String, Long>()

    @Volatile var brakePressed: Boolean? = null
    @Volatile var gear: Int? = null
        private set

    private var gearFrom: Int? = null
    private var gearChangedAtMs = 0L
    private var speedAtGearChange = 0.0

    var lowSpeedSurgeCount = 0
        private set
    var gearSurgeCount = 0
        private set
    var harshBrakeCount = 0
        private set

    val recentEvents = ArrayDeque<String>()

    @Synchronized
    fun onGear(newGear: Int, tMs: Long) {
        val old = gear
        if (old != null && old != newGear) {
            gearFrom = old
            gearChangedAtMs = tMs
            speedAtGearChange = samples.lastOrNull()?.kmh ?: 0.0
            report.log("기어 변경: ${Gears.name(old)} → ${Gears.name(newGear)}")
        }
        gear = newGear
    }

    @Synchronized
    fun onSpeed(kmh: Double, tMs: Long) {
        samples.addLast(Sample(tMs, kmh))
        while (samples.isNotEmpty() && tMs - samples.first().tMs > 3_000) samples.removeFirst()

        // ① 저속 급가속: 2초 안에 시속 15km 이하였던 순간보다 10km 이상 빨라졌고, 브레이크는 안 밟음
        val lowStart = samples
            .filter { (tMs - it.tMs) in 1L..2_000L && it.kmh <= LOW_SPEED_KMH }
            .minByOrNull { it.kmh }
        if (lowStart != null && kmh - lowStart.kmh >= SURGE_DELTA_KMH && brakePressed != true) {
            fire("저속 급가속", tMs, "%.1f → %.1f km/h".format(lowStart.kmh, kmh)) { lowSpeedSurgeCount++ }
        }

        // ② 기어 전환 직후 급출발: P·R에서 바꾼 뒤 3초 안에 8km/h 이상 빨라짐
        val from = gearFrom
        if ((from == VehicleGear.GEAR_PARK || from == VehicleGear.GEAR_REVERSE) &&
            tMs - gearChangedAtMs <= 3_000 &&
            kmh - speedAtGearChange >= GEAR_SURGE_DELTA_KMH &&
            brakePressed != true
        ) {
            fire("기어 전환 직후 급출발", tMs, "${Gears.name(from)}→${Gears.name(gear)}, %.1f km/h".format(kmh)) { gearSurgeCount++ }
        }

        // ③ 급제동: 1초 안에 12.4km/h 이상 감소(약 0.35g)
        val peak = samples.filter { tMs - it.tMs <= 1_000 }.maxByOrNull { it.kmh }
        if (peak != null && peak.kmh - kmh >= HARSH_BRAKE_DELTA_KMH) {
            fire("급제동", tMs, "%.1f → %.1f km/h, 브레이크=${brakePressed ?: "모름"}".format(peak.kmh, kmh)) { harshBrakeCount++ }
        }
    }

    @Synchronized
    fun reset() {
        lowSpeedSurgeCount = 0
        gearSurgeCount = 0
        harshBrakeCount = 0
        recentEvents.clear()
        lastFiredAt.clear()
        report.log("이벤트 횟수 초기화")
    }

    private inline fun fire(type: String, tMs: Long, detail: String, count: () -> Unit) {
        val last = lastFiredAt[type]
        if (last != null && tMs - last < DEBOUNCE_MS) return
        lastFiredAt[type] = tMs
        count()
        val line = "[$type] $detail"
        recentEvents.addFirst(line)
        while (recentEvents.size > 5) recentEvents.removeLast()
        report.log("이벤트 $line")
    }

    companion object {
        const val LOW_SPEED_KMH = 15.0
        const val SURGE_DELTA_KMH = 10.0
        const val GEAR_SURGE_DELTA_KMH = 8.0
        const val HARSH_BRAKE_DELTA_KMH = 12.4
        const val DEBOUNCE_MS = 5_000L
    }
}
