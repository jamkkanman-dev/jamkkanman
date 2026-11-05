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

/**
 * 이 자리의 단속 위험: 가까운 고정형 단속 CCTV와 이 근처(반경 50m) 실제 단속 건수.
 * 금지구역 판단과는 따로, 경고를 더 분명하게 하거나 '금지구역은 아니지만 단속이 잦은 곳'을 알리는 데 쓴다.
 */
data class Risk(
    val cctv: Cctv?,
    val cctvM: Double,
    /** 반경 50m 안 단속 건수 */
    val recent: Int,
    /** 단속 기록 기간 (예: "2025년 10~12월") */
    val period: String,
) {
    /** 단속 CCTV가 가깝거나 단속이 잦은 곳 */
    val busy: Boolean get() = cctv != null || recent >= ZoneChecker.BUSY_COUNT
}

data class CheckResult(
    val fix: Fix,
    val hits: List<ZoneHit>,
    val spot: SpotHint?,
    val hour: Int,
    /** false면 금지구역 데이터가 없는 지역이라 '세워도 된다'고 말할 수 없다 */
    val covered: Boolean = true,
    val risk: Risk? = null,
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

    /**
     * 이 자리를 확인할 데이터가 있는지. 데이터 파일에 확인 범위(행정구역 모양을 따라 만든 격자)가 있으면 그것으로,
     * 없으면 금지구역 위치의 바깥 사각형으로 판단한다.
     */
    fun covers(lat: Double, lon: Double): Boolean {
        if (data.zones.isEmpty()) return false
        data.coverage?.let { return it.contains(lat, lon) }
        return lat in latMin..latMax && lon in lonMin..lonMax
    }

    /** 멈춘 자리 하나를 확인한다. */
    fun check(fix: Fix, hour: Int = currentHour()): CheckResult {
        if (!covers(fix.lat, fix.lon)) return CheckResult(fix, emptyList(), null, hour, covered = false)
        // GPS 오차의 절반(최대 5m)까지는 '그럴 수 있음'으로 알린다.
        val margin = ((fix.accuracyM ?: 0f) * 0.5).coerceIn(0.0, 5.0)
        val hits = data.zones.mapNotNull { z ->
            // 멀리 있는 구역은 모양 계산 전에 거른다
            if (Geo.distanceM(fix.lat, fix.lon, z.lat, z.lon) > z.radiusM + FAR_SKIP_M) return@mapNotNull null
            val d = z.distanceTo(fix.lat, fix.lon)
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
        return CheckResult(fix, hits, nearestSpot(fix.lat, fix.lon), hour, risk = risk(fix.lat, fix.lon))
    }

    /** 이 자리의 단속 위험 (CCTV·단속 기록 데이터가 없으면 null) */
    fun risk(lat: Double, lon: Double): Risk? {
        if (data.cctvs.isEmpty() && data.enforcement.isEmpty()) return null
        val near = data.cctvs
            .map { it to Geo.distanceM(lat, lon, it.lat, it.lon) }
            .filter { it.second <= CCTV_NEAR_M }
            .minByOrNull { it.second }
        var recent = 0
        for (c in data.enforcement) {
            if (Math.abs(c.lat - lat) > 0.0008 || Math.abs(c.lon - lon) > 0.001) continue
            if (Geo.distanceM(lat, lon, c.lat, c.lon) <= RECENT_NEAR_M) recent += c.count
        }
        return Risk(near?.first, near?.second ?: Double.MAX_VALUE, recent, data.enforcementPeriod)
    }

    /** 목적지처럼 '앞으로 세울 자리' 주변의 금지구역 (반경 + 여유) */
    fun zonesNear(lat: Double, lon: Double, extraM: Double, hour: Int = currentHour()): List<ZoneHit> =
        data.zones.mapNotNull { z ->
            if (Geo.distanceM(lat, lon, z.lat, z.lon) > z.radiusM + extraM + FAR_SKIP_M) return@mapNotNull null
            val d = z.distanceTo(lat, lon)
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
        /** 중심점이 반경보다 이만큼 더 멀면 모양을 볼 필요도 없다 (가장 긴 횡단보도의 절반보다 넉넉히) */
        private const val FAR_SKIP_M = 60.0
        /** 이 거리 안의 고정형 단속 CCTV를 '가까이 있다'고 본다 */
        const val CCTV_NEAR_M = 50.0
        /** 단속 건수를 세는 반경 */
        const val RECENT_NEAR_M = 50.0
        /** 반경 50m 안 단속이 석 달에 이만큼 이상이면 '단속이 잦은 곳' (대치동 주변 상위 약 25%) */
        const val BUSY_COUNT = 30

        /** 한국 시각 기준 (기기 시간대와 무관) */
        fun currentHour(): Int = KoreaTime.hour()
    }
}
