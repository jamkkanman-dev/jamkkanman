package dev.jamkkanman.app.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.hypot

/**
 * 지도 배경 (행정안전부 도로명주소 도형에서 대치동 주변만 잘라 낸 것).
 * - areas: 건물, 실폭도로, 하천, 공원 같은 면
 * - lines: 도로구간 중심선과 도로명 (글자 표시용)
 * 좌표는 RoadMap 과 같은 기준점에서 동쪽 x, 북쪽 y 방향의 미터 값이다.
 */
class BaseMap(
    val originLat: Double,
    val originLon: Double,
    val areas: Map<String, List<Area>>,
    val lines: List<NamedLine>,
) {
    /**
     * 면 하나. rings[0]이 바깥 고리, 나머지는 구멍 (그릴 때 EVEN_ODD 로 채운다).
     * 화면은 매번 원근 투영해서 그리므로 미터 좌표를 그대로 들고 있는다.
     */
    class Area(val rings: List<FloatArray>) {
        val minX: Float
        val minY: Float
        val maxX: Float
        val maxY: Float
        /** 바깥 고리의 중심과 넓이(m²) */
        val cx: Float
        val cy: Float
        val areaM2: Float
        /** 바깥 고리가 반시계 방향이면 true (벽의 바깥쪽을 정할 때 쓴다) */
        val ccw: Boolean

        init {
            var x0 = Float.MAX_VALUE
            var y0 = Float.MAX_VALUE
            var x1 = -Float.MAX_VALUE
            var y1 = -Float.MAX_VALUE
            for (r in rings) {
                var k = 0
                while (k + 1 < r.size) {
                    if (r[k] < x0) x0 = r[k]
                    if (r[k] > x1) x1 = r[k]
                    if (r[k + 1] < y0) y0 = r[k + 1]
                    if (r[k + 1] > y1) y1 = r[k + 1]
                    k += 2
                }
            }
            minX = x0; minY = y0; maxX = x1; maxY = y1
            val outer = rings.firstOrNull() ?: FloatArray(0)
            val n = outer.size / 2
            var signed = 0f
            var sx = 0f
            var sy = 0f
            for (i in 0 until n) {
                val j = (i + 1) % n
                signed += outer[i * 2] * outer[j * 2 + 1] - outer[j * 2] * outer[i * 2 + 1]
                sx += outer[i * 2]
                sy += outer[i * 2 + 1]
            }
            areaM2 = kotlin.math.abs(signed) / 2f
            ccw = signed > 0f
            cx = if (n > 0) sx / n else 0f
            cy = if (n > 0) sy / n else 0f
        }
    }

    class NamedLine(
        val name: String,
        /** 도로위계 1 고속도로, 2 대로, 3 로, 4 길 */
        val grade: Int,
        val widthM: Float,
        /** x0, y0, x1, y1 … (m) */
        val points: FloatArray,
    ) {
        val minX: Float
        val minY: Float
        val maxX: Float
        val maxY: Float
        val lengthM: Float

        init {
            var x0 = Float.MAX_VALUE
            var y0 = Float.MAX_VALUE
            var x1 = -Float.MAX_VALUE
            var y1 = -Float.MAX_VALUE
            var len = 0f
            var i = 0
            while (i + 1 < points.size) {
                val x = points[i]
                val y = points[i + 1]
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
                if (i >= 2) len += hypot(x - points[i - 2], y - points[i - 1])
                i += 2
            }
            minX = x0; minY = y0; maxX = x1; maxY = y1; lengthM = len
        }
    }

    fun layer(name: String): List<Area> = areas[name].orEmpty()

    companion object {
        const val DEFAULT_FILE = "basemap_daechi.json"

        /** 파일이 크니 화면 스레드가 아닌 곳에서 부른다. 파일이 없으면 null */
        fun load(context: Context, fileName: String = DEFAULT_FILE): BaseMap? = runCatching {
            val root = JSONObject(context.assets.open(fileName).bufferedReader(Charsets.UTF_8).use { it.readText() })
            val origin = root.getJSONArray("origin")
            val areasJson = root.optJSONObject("areas") ?: JSONObject()
            val areas = HashMap<String, List<Area>>()
            for (name in areasJson.keys()) {
                val polys = areasJson.getJSONArray(name)
                val list = ArrayList<Area>(polys.length())
                for (i in 0 until polys.length()) parseArea(polys.getJSONArray(i))?.let(list::add)
                areas[name] = list
            }
            val linesJson = root.optJSONArray("lines") ?: JSONArray()
            val lines = ArrayList<NamedLine>(linesJson.length())
            for (i in 0 until linesJson.length()) {
                val r = linesJson.getJSONArray(i)
                val n = r.length() - 3
                if (n < 4) continue
                val pts = FloatArray(n)
                for (k in 0 until n) pts[k] = r.getDouble(3 + k).toFloat()
                lines += NamedLine(r.getString(0), r.getInt(1), r.getDouble(2).toFloat(), pts)
            }
            BaseMap(origin.getDouble(0), origin.getDouble(1), areas, lines)
        }.getOrNull()

        private fun parseArea(rings: JSONArray): Area? {
            val list = ArrayList<FloatArray>(rings.length())
            for (r in 0 until rings.length()) {
                val ring = rings.getJSONArray(r)
                if (ring.length() < 6) continue
                list += FloatArray(ring.length()) { ring.getDouble(it).toFloat() }
            }
            return if (list.isEmpty()) null else Area(list)
        }
    }
}
