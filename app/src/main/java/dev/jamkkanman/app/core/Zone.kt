package dev.jamkkanman.app.core

/**
 * 주정차 금지구역 (도로교통법 제32조). 주민신고는 조건이 있어(안전표지가 있는 소화전, 정지선~횡단보도, 초등학교 정문 앞 등,
 * 안전신문고 신고기준) 표지·노면표시를 모르는 이 앱은 '신고된다'고 말하지 않고 '주정차 금지구역'으로만 안내한다.
 * 소화전 과태료는 안전표지가 있으면 8만 원, 없으면 일반 위반과 같은 4만 원이라 4만 원으로 셈하고 8만 원은 따로 알린다.
 * 어린이보호구역은 2021.10.21부터 주정차 전면 금지, 오전 8시~오후 8시 과태료 12만 원(내 손안에 서울 2021.10.27).
 * 과태료는 승용차 기준. 인도(보도) 정차는 GPS만으로 판별할 수 없어 이 버전에서는 다루지 않는다.
 */
enum class ZoneType(
    /** 화면·음성에 쓰는 장소 이름 */
    val place: String,
    /** 화면용 범위 표현 */
    val range: String,
    /** 음성용 범위 표현 */
    val spokenRange: String,
    /** 이 반경 안이면 금지구역으로 본다 (데이터에 반경이 따로 있으면 그것을 쓴다) */
    val radiusM: Double,
    private val fineWon: Int,
) {
    HYDRANT("소화전", "5m 안", "5미터 안", 5.0, 40_000),
    CORNER("교차로 모퉁이", "5m 안", "5미터 안", 5.0, 40_000),
    BUS_STOP("버스정류장", "10m 안", "10미터 안", 10.0, 40_000),
    CROSSWALK("횡단보도", "10m 안", "10미터 안", 10.0, 40_000),
    SCHOOL_ZONE("어린이보호구역", "안", "안", 100.0, 120_000);

    /** "버스정류장 10m 안이에요" 같은 화면 제목 */
    val title: String get() = "$place ${range}이에요"

    /** "버스정류장 10미터 안" 같은 음성 표현 (뒤에 '이에요'를 붙여 쓴다) */
    val spoken: String get() = "$place $spokenRange"

    /** 왜 안 되는지 한마디 */
    val risk: String get() = if (this == SCHOOL_ZONE) "주정차가 전면 금지된 곳" else "주정차 금지구역"

    /** 과태료가 조건에 따라 더 높아지는 경우 덧붙이는 말 */
    val fineNote: String? get() = if (this == HYDRANT) "안전표지가 있으면 8만 원" else null

    /** 어린이보호구역 과태료 가중(3배)은 오전 8시~오후 8시. 그 밖의 시간은 일반 주정차 위반과 같게 안내한다. */
    fun fineAt(hour: Int): Int = if (this == SCHOOL_ZONE && hour !in 8..19) 40_000 else fineWon
}

data class Zone(
    val id: String,
    val type: ZoneType,
    val name: String,
    val lat: Double,
    val lon: Double,
    val source: String,
    /** 이 구역의 반경(m). 어린이보호구역처럼 시설마다 다를 수 있다. */
    val radiusM: Double = type.radiusM,
    /** 구역의 실제 모양 (위도, 경도, 위도, 경도 …). 횡단보도처럼 모양이 있으면 테두리에서 반경을 잰다. */
    val shape: DoubleArray? = null,
) {
    /** 이 자리에서 구역까지 거리(m). 모양이 있으면 테두리까지(안이면 0), 없으면 중심점까지 */
    fun distanceTo(lat: Double, lon: Double): Double {
        val s = shape ?: return Geo.distanceM(lat, lon, this.lat, this.lon)
        val n = s.size / 2
        if (n < 3) return Geo.distanceM(lat, lon, this.lat, this.lon)
        val kx = 111_320.0 * kotlin.math.cos(Math.toRadians(lat))
        val ky = 110_540.0
        var inside = false
        var best = Double.MAX_VALUE
        for (i in 0 until n) {
            val j = (i + 1) % n
            val x0 = (s[i * 2 + 1] - lon) * kx
            val y0 = (s[i * 2] - lat) * ky
            val x1 = (s[j * 2 + 1] - lon) * kx
            val y1 = (s[j * 2] - lat) * ky
            if ((y0 > 0) != (y1 > 0) && x0 + (x1 - x0) * (0 - y0) / (y1 - y0) > 0) inside = !inside
            val dx = x1 - x0
            val dy = y1 - y0
            val len2 = dx * dx + dy * dy
            val t = if (len2 < 1e-12) 0.0 else (-(x0 * dx + y0 * dy) / len2).coerceIn(0.0, 1.0)
            best = minOf(best, kotlin.math.hypot(x0 + t * dx, y0 + t * dy))
        }
        return if (inside) 0.0 else best
    }
}

enum class SpotKind(val label: String) {
    SAFE_DROP("어린이 안심승하차구역"),
    PUBLIC_PARKING("공영주차장"),
    SHORT_STOP("잠깐 세울 수 있는 곳"),
}

/** 금지구역 대신 안내할 '세울 수 있는 곳' */
data class SafeSpot(
    val id: String,
    val kind: SpotKind,
    val name: String,
    val lat: Double,
    val lon: Double,
    val note: String,
    val source: String,
)

/** 시연 모드에서 바로 옮겨 갈 수 있는 위치 */
data class DemoPlace(
    val label: String,
    val lat: Double,
    val lon: Double,
)

/** 고정형 불법주정차 단속 CCTV (서울시 강남구 불법주정차 위반 단속 CCTV 위치정보) */
data class Cctv(
    val name: String,
    val lat: Double,
    val lon: Double,
)

/** 최근 실제 단속 기록을 25m 칸으로 모은 것 (서울시 불법주정차 단속 정보). 칸 가운데 위치와 건수 */
data class EnforcementCell(
    val lat: Double,
    val lon: Double,
    val count: Int,
)

/**
 * 금지구역을 확인할 수 있는 범위. 행정구역(강남구) 소방용수시설 위치를 따라 만든 격자라
 * 사각형 범위와 달리 옆 구로 넘어가면 '확인 범위 밖'으로 안내한다.
 */
class Coverage(
    private val lat0: Double,
    private val lon0: Double,
    private val cellM: Double,
    private val cols: Int,
    private val cells: Set<Int>,
) {
    private val kx = 111_320.0 * kotlin.math.cos(Math.toRadians(lat0))

    fun contains(lat: Double, lon: Double): Boolean {
        val col = kotlin.math.floor((lon - lon0) * kx / cellM).toInt()
        val row = kotlin.math.floor((lat - lat0) * 110_540.0 / cellM).toInt()
        if (col < 0 || row < 0 || col >= cols) return false
        return (row * cols + col) in cells
    }
}

data class ZoneData(
    val region: String,
    val isSample: Boolean,
    val sources: List<String>,
    val zones: List<Zone>,
    val spots: List<SafeSpot>,
    val demo: List<DemoPlace>,
    val cctvs: List<Cctv> = emptyList(),
    val enforcement: List<EnforcementCell> = emptyList(),
    /** 단속 기록 기간 (화면 표시용, 예: "2025년 10~12월") */
    val enforcementPeriod: String = "",
    val coverage: Coverage? = null,
) {
    companion object {
        val EMPTY = ZoneData("데이터 없음", true, emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/** 지금 쓰는 위치 하나 */
data class Fix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float?,
    val source: String,
    val atMs: Long,
)
