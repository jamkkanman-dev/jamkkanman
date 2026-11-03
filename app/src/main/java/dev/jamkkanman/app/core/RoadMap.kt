package dev.jamkkanman.app.core

import android.content.Context
import android.graphics.Path
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 지도용 도로망 (국토교통부 국가교통정보센터 전국 표준노드링크에서 대치동 주변만 잘라 낸 것).
 * 좌표는 기준점(origin)에서 동쪽 x, 북쪽 y 방향의 미터 값으로 저장한다.
 */
class RoadMap(
    val originLat: Double,
    val originLon: Double,
    val roads: List<Road>,
) {
    class Road(
        /** 도로 등급 (101 고속국도 … 104 특별·광역시도 … 107 시군도) */
        val rank: Int,
        /** 차로 수 */
        val lanes: Int,
        val name: String,
        /** x0, y0, x1, y1 … (m) */
        val points: FloatArray,
    ) {
        val path = Path()
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var lengthM = 0f

        init {
            var i = 0
            while (i + 1 < points.size) {
                val x = points[i]
                val y = points[i + 1]
                if (i == 0) path.moveTo(x, y) else {
                    path.lineTo(x, y)
                    lengthM += hypot(x - points[i - 2], y - points[i - 1])
                }
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                i += 2
            }
        }
    }

    private val kx = (METERS_PER_DEG_LON_AT_EQUATOR * cos(Math.toRadians(originLat))).toFloat()

    /** 위도·경도 → 기준점 기준 미터 (동쪽 x, 북쪽 y) */
    fun toMeters(lat: Double, lon: Double): Pair<Float, Float> =
        ((lon - originLon) * kx).toFloat() to ((lat - originLat) * METERS_PER_DEG_LAT).toFloat()

    companion object {
        const val DEFAULT_FILE = "roads_daechi.json"
        const val METERS_PER_DEG_LAT = 110_540.0
        const val METERS_PER_DEG_LON_AT_EQUATOR = 111_320.0

        fun load(context: Context, fileName: String = DEFAULT_FILE): RoadMap? = runCatching {
            val root = JSONObject(context.assets.open(fileName).bufferedReader(Charsets.UTF_8).use { it.readText() })
            val origin = root.getJSONArray("origin")
            val arr = root.getJSONArray("roads")
            val roads = ArrayList<Road>(arr.length())
            for (i in 0 until arr.length()) {
                val r = arr.getJSONArray(i)
                val n = r.length() - 3
                if (n < 4) continue
                val pts = FloatArray(n)
                for (k in 0 until n) pts[k] = r.getDouble(3 + k).toFloat()
                roads += Road(r.getInt(0), r.getInt(1).coerceAtLeast(1), r.getString(2), pts)
            }
            RoadMap(origin.getDouble(0), origin.getDouble(1), roads)
        }.getOrNull()
    }
}
