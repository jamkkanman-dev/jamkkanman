package dev.jamkkanman.app.core

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
    /** false면 금지구역 데이터가 없는 지역이라 '세워도 된다'고 말할 수 없다 */
    val covered: Boolean = true,
) {
    val top: ZoneHit? get() = hits.firstOrNull()
    val isClear: Boolean get() = hits.isEmpty() && covered
}

class ZoneChecker(val data: ZoneData) {

    // 데이터가 들어 있는 범위 (금지구역 위치의 바깥 사각형 + 여유 300m)
    private val latMin = (data.zones.minOfOrNull { it.lat } ?: 0.0) - COVER_MARGIN_DEG
    private val latMax = (data.zones.maxOfOrNull { it.lat } ?: 0.0) + COVER_MARGIN_DEG
    private val lonMin = (data.zones.minOfOrNull { it.lon } ?: 0.0) - COVER_MARGIN_DEG
    private val lonMax = (data.zones.maxOfOrNull { it.lon } ?: 0.0) + COVER_MARGIN_DEG

    /** 이 자리를 확인할 데이터가 있는지 (지금은 대치동과 그 주변만) */
    fun covers(lat: Double, lon: Double): Boolean =
        data.zones.isNotEmpty() && lat in latMin..latMax && lon in lonMin..lonMax

    /** 멈춘 자리 하나를 확인한다. */
    fun check(fix: Fix, hour: Int = currentHour()): CheckResult {
        if (!covers(fix.lat, fix.lon)) return CheckResult(fix, emptyList(), null, hour, covered = false)
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
        /** 약 300m */
        private const val COVER_MARGIN_DEG = 0.003

        /** 한국 시각 기준 (기기 시간대와 무관) */
        fun currentHour(): Int = KoreaTime.hour()
    }
}
