package dev.jamkkanman.app.core

import java.util.Calendar
import kotlin.math.max

data class ZoneHit(
    val zone: Zone,
    val distanceM: Double,
    /** false면 GPS 오차 범위 때문에 '그럴 수 있음' 단계 */
    val certain: Boolean,
) {
    /** 이만큼만 옮기면 범위 밖 */
    val exitM: Double get() = max(0.0, zone.radiusM - distanceM)
}

data class SpotHint(val spot: SafeSpot, val distanceM: Double)

data class CheckResult(
    val fix: Fix,
    val hits: List<ZoneHit>,
    val spot: SpotHint?,
    val hour: Int,
) {
    val top: ZoneHit? get() = hits.firstOrNull()
    val isClear: Boolean get() = hits.isEmpty()
}

class ZoneChecker(val data: ZoneData) {

    /** 멈춘 자리 하나를 확인한다. */
    fun check(fix: Fix, hour: Int = currentHour()): CheckResult {
        // GPS 오차의 절반(최대 5m)까지는 '그럴 수 있음'으로 알린다.
        val margin = ((fix.accuracyM ?: 0f) * 0.5).coerceIn(0.0, 5.0)
        val hits = data.zones.mapNotNull { z ->
            val d = Geo.distanceM(fix.lat, fix.lon, z.lat, z.lon)
            when {
                d <= z.radiusM -> ZoneHit(z, d, certain = true)
                d <= z.radiusM + margin -> ZoneHit(z, d, certain = false)
                else -> null
            }
        }.sortedWith(
            compareByDescending<ZoneHit> { it.certain }
                .thenByDescending { it.zone.type.fineAt(hour) }
                .thenBy { it.distanceM }
        )
        return CheckResult(fix, hits, nearestSpot(fix.lat, fix.lon), hour)
    }

    /** 목적지처럼 '앞으로 세울 자리' 주변의 금지구역 (반경 + 여유) */
    fun zonesNear(lat: Double, lon: Double, extraM: Double, hour: Int = currentHour()): List<ZoneHit> =
        data.zones.mapNotNull { z ->
            val d = Geo.distanceM(lat, lon, z.lat, z.lon)
            if (d <= z.radiusM + extraM) ZoneHit(z, d, certain = d <= z.radiusM) else null
        }.sortedWith(compareByDescending<ZoneHit> { it.zone.type.fineAt(hour) }.thenBy { it.distanceM })

    fun nearestSpot(lat: Double, lon: Double, maxM: Double = 600.0): SpotHint? =
        data.spots
            .map { SpotHint(it, Geo.distanceM(lat, lon, it.lat, it.lon)) }
            .filter { it.distanceM <= maxM }
            .minByOrNull { it.distanceM }

    companion object {
        fun currentHour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    }
}
