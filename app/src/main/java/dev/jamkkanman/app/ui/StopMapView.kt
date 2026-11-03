package dev.jamkkanman.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import dev.jamkkanman.app.core.BaseMap
import dev.jamkkanman.app.core.Fix
import dev.jamkkanman.app.core.RoadMap
import dev.jamkkanman.app.core.SafeSpot
import dev.jamkkanman.app.core.SpotHint
import dev.jamkkanman.app.core.Zone
import dev.jamkkanman.app.core.ZoneType
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 밝은 지도 (Pleos Connect Light 테마 톤).
 * - 배경: 행정안전부 도로명주소 도형의 건물·실폭도로·하천·공원 (BaseMap)
 *   배경 파일이 없으면 국토교통부 표준노드링크 도로망(RoadMap)을 차로 폭만큼 그린다.
 * - 위에: 내 차 주변 금지구역, 세울 곳, 내 차
 * 내 차는 화면 가운데 고정, 위쪽이 북쪽.
 */
class StopMapView(context: Context, private val unit: Float) : View(context) {

    enum class Mood { NEUTRAL, WARN, MAYBE, CLEAR }

    /** 디자인 기준 px → 실제 px */
    private fun u(v: Float) = v * unit

    private var roads: RoadMap? = null
    private var base: BaseMap? = null
    private var zones: List<Zone> = emptyList()
    private var spots: List<SafeSpot> = emptyList()

    private var center: Fix? = null
    private var heading = 0f
    private var highlightId: String? = null
    private var target: SpotHint? = null
    private var mood = Mood.NEUTRAL

    private val roadCasing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val roadFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Palette.SURFACE
    }
    private val areaFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val areaEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.GRAY_500
        textSize = u(22f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.SURFACE
        style = Paint.Style.STROKE
        strokeWidth = u(7f)
        strokeJoin = Paint.Join.ROUND
        textSize = u(22f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val tag = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = u(24f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val dash = DashPathEffect(floatArrayOf(u(14f), u(10f)), 0f)
    private val tmpPath = Path()
    private val rect = RectF()

    fun setData(roadMap: RoadMap?, zoneList: List<Zone>, spotList: List<SafeSpot>) {
        roads = roadMap
        zones = zoneList
        spots = spotList
        invalidate()
    }

    fun setBaseMap(baseMap: BaseMap?) {
        base = baseMap
        invalidate()
    }

    fun update(fix: Fix?, headingDeg: Float, highlightZoneId: String?, nearest: SpotHint?, newMood: Mood) {
        center = fix
        heading = headingDeg
        highlightId = highlightZoneId
        target = nearest
        mood = newMood
        invalidate()
    }

    /** 위도·경도 → 기준점 기준 미터. 배경과 도로망은 같은 기준점을 쓴다. */
    private fun meters(lat: Double, lon: Double): Pair<Float, Float>? {
        roads?.let { return it.toMeters(lat, lon) }
        val b = base ?: return null
        val kx = RoadMap.METERS_PER_DEG_LON_AT_EQUATOR * cos(Math.toRadians(b.originLat))
        return ((lon - b.originLon) * kx).toFloat() to ((lat - b.originLat) * RoadMap.METERS_PER_DEG_LAT).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        tagRects.clear()
        val bm = base
        canvas.drawColor(if (bm != null) LAND else MAP_BG)
        val c = center
        val here = c?.let { meters(it.lat, it.lon) }
        if (here == null) {
            label.textAlign = Paint.Align.CENTER
            label.textSize = u(28f)
            canvas.drawText(if (c == null) "위치를 찾는 중" else "지도 데이터 없음", width / 2f, height / 2f, label)
            label.textSize = u(22f)
            label.textAlign = Paint.Align.LEFT
            return
        }

        val ax = width / 2f
        val ay = height * 0.54f
        val (cx, cy) = here

        // 축척: 화면 짧은 변에 약 200m. 세울 곳이 멀면 함께 보이게 넓힌다.
        val short = min(width, height).toFloat()
        var s = short / VIEW_M
        target?.let { t ->
            meters(t.spot.lat, t.spot.lon)?.let { (tx, ty) ->
                val d = hypot(tx - cx, ty - cy)
                if (d in 1f..800f) s = min(s, short * 0.40f / d)
            }
        }
        s = max(s, short / 1600f)

        fun sx(x: Float) = ax + (x - cx) * s
        fun sy(y: Float) = ay - (y - cy) * s

        val left = cx - ax / s
        val right = cx + (width - ax) / s
        val top = cy + ay / s
        val bottom = cy - (height - ay) / s

        canvas.save()
        canvas.translate(ax, ay)
        canvas.scale(s, -s)
        canvas.translate(-cx, -cy)
        if (bm != null) drawBase(canvas, bm, s, left, right, bottom, top)
        else roads?.let { drawRoadNetwork(canvas, it, s, left, right, bottom, top) }
        canvas.restore()

        drawRoadNames(canvas, bm, left, right, bottom, top, ::sx, ::sy, ax, ay)

        // 어린이보호구역 (영역)
        for (z in zones) {
            if (z.type != ZoneType.SCHOOL_ZONE) continue
            val (zx, zy) = meters(z.lat, z.lon) ?: continue
            val r = z.radiusM.toFloat() * s
            val px = sx(zx)
            val py = sy(zy)
            if (px + r < 0 || px - r > width || py + r < 0 || py - r > height) continue
            val hl = z.id == highlightId
            fill.color = withAlpha(Palette.ORANGE, if (hl) 0x2A else 0x18)
            canvas.drawCircle(px, py, r, fill)
            stroke.pathEffect = dash
            stroke.strokeWidth = u(if (hl) 4f else 2.5f)
            stroke.color = withAlpha(Palette.ORANGE, if (hl) 0xFF else 0x99)
            canvas.drawCircle(px, py, r, stroke)
            stroke.pathEffect = null
        }

        // 점 금지구역: 내 차 주변(반경 NEAR_M)과 지금 걸린 곳만
        for (z in zones) {
            if (z.type == ZoneType.SCHOOL_ZONE) continue
            val (zx, zy) = meters(z.lat, z.lon) ?: continue
            val hl = z.id == highlightId
            if (!hl && hypot(zx - cx, zy - cy) > NEAR_M) continue
            val px = sx(zx)
            val py = sy(zy)
            val r = max(z.radiusM.toFloat() * s, u(if (hl) 20f else 12f))
            fill.color = withAlpha(Palette.RED, if (hl) 0x33 else 0x1F)
            canvas.drawCircle(px, py, r, fill)
            stroke.strokeWidth = u(if (hl) 4f else 2.5f)
            stroke.color = withAlpha(Palette.RED, if (hl) 0xFF else 0xB0)
            canvas.drawCircle(px, py, r, stroke)
            fill.color = Palette.RED
            canvas.drawCircle(px, py, u(if (hl) 8f else 5f), fill)
            if (hl) drawTag(canvas, px, py - r - u(14f), "${z.type.place} ${z.type.range}", Palette.RED)
        }

        // 내 차 → 세울 곳 점선
        target?.let { t ->
            meters(t.spot.lat, t.spot.lon)?.let { (tx, ty) ->
                stroke.pathEffect = dash
                stroke.color = Palette.GREEN
                stroke.strokeWidth = u(6f)
                canvas.drawLine(ax, ay, sx(tx), sy(ty) - u(24f), stroke)
                stroke.pathEffect = null
            }
        }

        // 세울 곳 (공영주차장)
        for (sp in spots) {
            val (px0, py0) = meters(sp.lat, sp.lon) ?: continue
            val px = sx(px0)
            val py = sy(py0)
            if (px < -u(40f) || px > width + u(40f) || py < -u(60f) || py > height + u(40f)) continue
            val isTarget = target?.spot?.id == sp.id
            drawPin(canvas, px, py, if (isTarget) 1f else 0.72f, if (isTarget) Palette.GREEN else withAlpha(Palette.GREEN, 0xB0))
            if (isTarget) drawTag(canvas, px, py - u(70f), "세울 곳 ${target!!.distanceM.roundToInt()}m", Palette.GREEN)
        }

        drawCar(canvas, ax, ay)
        drawScaleBar(canvas, s)
    }

    /** 배경 면: 공원·하천 → 도로(실폭) → 건물 (모두 미터 좌표, 캔버스가 이미 미터 단위로 변환됨) */
    private fun drawBase(canvas: Canvas, bm: BaseMap, s: Float, left: Float, right: Float, bottom: Float, top: Float) {
        fun visible(a: BaseMap.Area) = !(a.maxX < left || a.minX > right || a.maxY < bottom || a.minY > top)

        areaFill.color = PARK
        for (a in bm.layer("park")) if (visible(a)) canvas.drawPath(a.path, areaFill)
        areaFill.color = WATER
        for (a in bm.layer("water")) if (visible(a)) canvas.drawPath(a.path, areaFill)

        val roadAreas = bm.layer("roadarea")
        areaEdge.color = ROAD_EDGE
        areaEdge.strokeWidth = u(3f) / s
        for (a in roadAreas) if (visible(a)) canvas.drawPath(a.path, areaEdge)
        areaFill.color = Palette.SURFACE
        for (a in roadAreas) if (visible(a)) canvas.drawPath(a.path, areaFill)

        // 건물: 아래로 살짝 그림자를 깔아 입체감을 준다 (화면 아래 = 미터 좌표 -y)
        val buildings = bm.layer("building")
        val off = u(3f) / s
        areaFill.color = BUILDING_SHADOW
        canvas.save()
        canvas.translate(off * 0.5f, -off)
        for (a in buildings) if (visible(a)) canvas.drawPath(a.path, areaFill)
        canvas.restore()
        areaFill.color = BUILDING
        areaEdge.color = BUILDING_EDGE
        areaEdge.strokeWidth = u(1.5f) / s
        for (a in buildings) {
            if (!visible(a)) continue
            canvas.drawPath(a.path, areaFill)
            canvas.drawPath(a.path, areaEdge)
        }
    }

    /** 배경 파일이 없을 때: 표준노드링크 도로를 차로 폭만큼 */
    private fun drawRoadNetwork(canvas: Canvas, road: RoadMap, s: Float, left: Float, right: Float, bottom: Float, top: Float) {
        for (pass in 0..1) {
            for (r in road.roads) {
                if (r.maxX < left || r.minX > right || r.maxY < bottom || r.minY > top) continue
                val widthM = max(r.lanes * 3.2f, u(6f) / s)
                if (pass == 0) {
                    roadCasing.color = if (r.rank <= 104) CASING_MAJOR else CASING_MINOR
                    roadCasing.strokeWidth = widthM + u(5f) / s
                    canvas.drawPath(r.path, roadCasing)
                } else {
                    roadFill.strokeWidth = widthM
                    canvas.drawPath(r.path, roadFill)
                }
            }
        }
    }

    private class LabelCand(val name: String, val priority: Int, val points: FloatArray, val lengthM: Float)

    private val placed = ArrayList<FloatArray>()
    private val runX = ArrayList<Float>()
    private val runY = ArrayList<Float>()

    /**
     * 도로 이름: 화면 안에 보이는 구간 중 가장 긴 곳에 글자를 얹는다.
     * 큰길(대로·로) 먼저, 남는 자리에 '길'. 글자끼리, 내 차와는 겹치지 않게.
     */
    private fun drawRoadNames(
        canvas: Canvas,
        bm: BaseMap?,
        left: Float, right: Float, bottom: Float, top: Float,
        sx: (Float) -> Float, sy: (Float) -> Float,
        carX: Float, carY: Float,
    ) {
        val cands = HashMap<String, LabelCand>()
        fun offer(c: LabelCand) {
            val cur = cands[c.name]
            if (cur == null || c.lengthM > cur.lengthM) cands[c.name] = c
        }
        if (bm != null && bm.lines.isNotEmpty()) {
            for (l in bm.lines) {
                if (l.name.isBlank() || l.maxX < left || l.minX > right || l.maxY < bottom || l.minY > top) continue
                offer(LabelCand(l.name, l.grade, l.points, l.lengthM))
            }
        } else {
            roads?.let { road ->
                for (r in road.roads) {
                    // 이름 없는 구간(-), 사이길(.)은 빼고 큰길 위주로
                    if (r.name.isBlank() || r.name == "-" || '.' in r.name || r.rank > 105) continue
                    if (r.maxX < left || r.minX > right || r.maxY < bottom || r.minY > top) continue
                    offer(LabelCand(r.name, if (r.rank <= 104) 2 else 3, r.points, r.lengthM))
                }
            }
        }

        placed.clear()
        placed += floatArrayOf(carX, carY, u(90f))
        val margin = u(30f)
        val step = u(36f)
        var drawn = 0
        for (c in cands.values.sortedWith(compareBy<LabelCand> { it.priority }.thenByDescending { it.lengthM })) {
            if (drawn >= MAX_LABELS) break
            val textW = label.measureText(c.name)

            // 화면 안 가장 긴 연속 구간 찾기 (긴 직선은 잘게 나눠서 본다)
            runX.clear(); runY.clear()
            var bestStart = 0
            var bestEnd = 0
            var bestLen = 0f
            var curStart = -1
            var curLen = 0f
            val n = c.points.size / 2
            for (i in 0 until n) {
                val x1 = sx(c.points[i * 2])
                val y1 = sy(c.points[i * 2 + 1])
                val pieces = if (i == 0) 1 else max(1, ceil(hypot(x1 - runX.last(), y1 - runY.last()) / step).toInt())
                val x0 = if (i == 0) x1 else runX.last()
                val y0 = if (i == 0) y1 else runY.last()
                for (k in 1..pieces) {
                    val x = x0 + (x1 - x0) * k / pieces
                    val y = y0 + (y1 - y0) * k / pieces
                    val inside = x in margin..(width - margin) && y in margin..(height - margin)
                    runX += x; runY += y
                    val idx = runX.size - 1
                    if (inside) {
                        if (curStart < 0) { curStart = idx; curLen = 0f }
                        else curLen += hypot(x - runX[idx - 1], y - runY[idx - 1])
                        if (curLen > bestLen) { bestLen = curLen; bestStart = curStart; bestEnd = idx }
                    } else curStart = -1
                }
            }
            if (bestLen < textW + u(60f)) continue

            // 겹침 검사: 글자 가운데 지점 기준
            val midIdx = (bestStart + bestEnd) / 2
            val mx = runX[midIdx]
            val my = runY[midIdx]
            val half = textW / 2 + u(20f)
            if (placed.any { hypot(it[0] - mx, it[1] - my) < it[2] + half }) continue

            // 왼쪽 → 오른쪽으로 읽히게
            val forward = runX[bestStart] <= runX[bestEnd]
            tmpPath.reset()
            for (k in 0..(bestEnd - bestStart)) {
                val idx = if (forward) bestStart + k else bestEnd - k
                if (k == 0) tmpPath.moveTo(runX[idx], runY[idx]) else tmpPath.lineTo(runX[idx], runY[idx])
            }
            val major = c.priority <= 3
            label.color = if (major) Palette.TEXT2 else Palette.GRAY_500
            label.textSize = u(if (major) 23f else 20f)
            halo.textSize = label.textSize
            val hOff = (bestLen - textW) / 2f
            val vOff = label.textSize * 0.36f
            canvas.drawTextOnPath(c.name, tmpPath, hOff, vOff, halo)
            canvas.drawTextOnPath(c.name, tmpPath, hOff, vOff, label)
            placed += floatArrayOf(mx, my, half)
            drawn++
        }
        label.color = Palette.GRAY_500
        label.textSize = u(22f)
    }

    /** 이번 화면에 이미 그린 말풍선 자리 (겹치면 위로 올린다) */
    private val tagRects = ArrayList<RectF>()

    /** 흰 말풍선 라벨. 먼저 그린 말풍선과 겹치면 겹치지 않을 때까지 위로 올린다. */
    private fun drawTag(canvas: Canvas, x: Float, y: Float, text: String, color: Int) {
        val w = tag.measureText(text) + u(36f)
        val h = u(48f)
        rect.set(x - w / 2, y - h, x + w / 2, y)
        var tries = 0
        while (tries < 4 && tagRects.any { RectF.intersects(it, rect) }) {
            rect.offset(0f, -(h + u(8f)))
            tries++
        }
        tagRects += RectF(rect)
        val bottomY = rect.bottom
        fill.color = 0x29000000
        rect.offset(0f, u(4f))
        canvas.drawRoundRect(rect, h / 2, h / 2, fill)
        rect.offset(0f, -u(4f))
        fill.color = Palette.SURFACE
        canvas.drawRoundRect(rect, h / 2, h / 2, fill)
        tag.color = color
        tag.textAlign = Paint.Align.CENTER
        canvas.drawText(text, x, bottomY - h / 2 + u(8.5f), tag)
    }

    private fun drawPin(canvas: Canvas, x: Float, y: Float, scale: Float, color: Int) {
        val r = u(22f) * scale
        val cyPin = y - r * 1.9f
        tmpPath.reset()
        tmpPath.moveTo(x, y)
        tmpPath.lineTo(x - r * 0.75f, cyPin + r * 0.6f)
        tmpPath.lineTo(x + r * 0.75f, cyPin + r * 0.6f)
        tmpPath.close()
        fill.color = color
        canvas.drawPath(tmpPath, fill)
        canvas.drawCircle(x, cyPin, r, fill)
        stroke.color = Palette.SURFACE
        stroke.strokeWidth = u(3f)
        canvas.drawCircle(x, cyPin, r, stroke)
        tag.color = Palette.SURFACE
        tag.textAlign = Paint.Align.CENTER
        val old = tag.textSize
        tag.textSize = u(24f) * scale
        canvas.drawText("P", x, cyPin + u(8.5f) * scale, tag)
        tag.textSize = old
    }

    private fun drawCar(canvas: Canvas, x: Float, y: Float) {
        val ring = when (mood) {
            Mood.WARN -> Palette.RED
            Mood.MAYBE -> Palette.ORANGE
            Mood.CLEAR -> Palette.GREEN
            Mood.NEUTRAL -> Palette.BLUE
        }
        fill.color = withAlpha(ring, 0x26)
        canvas.drawCircle(x, y, u(64f), fill)
        fill.color = 0x29000000
        canvas.drawCircle(x, y + u(3f), u(30f), fill)
        fill.color = Palette.SURFACE
        canvas.drawCircle(x, y, u(30f), fill)
        // 진행 방향 화살표
        val a = Math.toRadians(heading.toDouble() - 90.0)
        val len = u(22f)
        fun px(off: Double, l: Float) = (x + l * cos(a + off)).toFloat()
        fun py(off: Double, l: Float) = (y + l * sin(a + off)).toFloat()
        tmpPath.reset()
        tmpPath.moveTo(px(0.0, len), py(0.0, len))
        tmpPath.lineTo(px(2.4, len), py(2.4, len))
        tmpPath.lineTo(px(Math.PI, len * 0.35f), py(Math.PI, len * 0.35f))
        tmpPath.lineTo(px(-2.4, len), py(-2.4, len))
        tmpPath.close()
        fill.color = Palette.BLUE
        canvas.drawPath(tmpPath, fill)
    }

    private fun drawScaleBar(canvas: Canvas, s: Float) {
        // 지도 출처 (공공데이터 이용 조건: 출처 표시)
        // 왼쪽 아래 범례와 겹치지 않게 짧게, 자세한 출처는 README
        val credit = if (base != null) "지도 데이터 © 행정안전부 · 국토교통부" else "지도 데이터 © 국토교통부"
        label.textAlign = Paint.Align.RIGHT
        label.textSize = u(16f)
        label.color = Palette.TEXT3
        canvas.drawText(credit, width - u(28f), height - u(18f), label)
        label.textSize = u(22f)
        label.color = Palette.GRAY_500

        val meters = listOf(20, 50, 100, 200, 500).firstOrNull { it * s >= u(100f) } ?: 500
        val w = meters * s
        val x1 = width - u(40f)
        val x0 = x1 - w
        val y = height - u(52f)
        stroke.color = Palette.GRAY_500
        stroke.strokeWidth = u(4f)
        canvas.drawLine(x0, y, x1, y, stroke)
        canvas.drawLine(x0, y - u(10f), x0, y, stroke)
        canvas.drawLine(x1, y - u(10f), x1, y, stroke)
        label.textAlign = Paint.Align.CENTER
        canvas.drawText("${meters}m", (x0 + x1) / 2, y - u(14f), label)
        label.textAlign = Paint.Align.LEFT
    }

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    companion object {
        /** 화면 짧은 변에 담을 거리(m) */
        private const val VIEW_M = 200f
        /** 이 거리 안의 점 금지구역만 지도에 표시 */
        private const val NEAR_M = 90f
        private const val MAX_LABELS = 7

        // 배경이 없을 때
        private const val MAP_BG = 0xFFE9EBEF.toInt()
        private const val CASING_MAJOR = 0xFFC7C9CE.toInt()
        private const val CASING_MINOR = 0xFFD7D9DE.toInt()

        // 배경이 있을 때 (Pleos Light 회색 계열 + 낮은 채도의 물·녹지)
        private const val LAND = 0xFFF0F1F4.toInt()
        private const val ROAD_EDGE = 0xFFD3D6DB.toInt()
        private const val BUILDING = 0xFFE2E4E9.toInt()
        private const val BUILDING_EDGE = 0xFFC9CCD2.toInt()
        private const val BUILDING_SHADOW = 0x1F131417
        private const val PARK = 0xFFD8EEDB.toInt()
        private const val WATER = 0xFFCDE1F6.toInt()
    }
}
