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
import dev.jamkkanman.app.core.Cctv
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
import kotlin.math.sqrt

/**
 * 밝은 지도 (Pleos Connect Light 테마 톤).
 * - 배경: 행정안전부 도로명주소 도형의 건물·실폭도로·하천·공원 (BaseMap)
 *   배경 파일이 없으면 국토교통부 표준노드링크 도로망(RoadMap)을 차로 폭만큼 그린다.
 * - 위에: 내 차 주변 금지구역, 세울 곳, 내 차
 *
 * 입체 보기(tilted): 내비처럼 비스듬히 내려다보는 원근 투영. 진행 방향이 위로 오고, 건물을 세워 그린다.
 * 평면 보기: 위에서 내려다본 지도(북쪽이 위).
 * 두 보기 모두 같은 투영 함수(project)로 그린다. 평면 보기는 카메라를 아주 멀리 둔 경우와 같다.
 */
class StopMapView(context: Context, private val unit: Float) : View(context) {

    enum class Mood { NEUTRAL, WARN, MAYBE, CLEAR }

    /** 디자인 기준 px → 실제 px */
    private fun u(v: Float) = v * unit

    private var roads: RoadMap? = null
    private var base: BaseMap? = null
    private var zones: List<Zone> = emptyList()
    private var spots: List<SafeSpot> = emptyList()
    private var cctvs: List<Cctv> = emptyList()

    /** 지도 가운데에 띄울 안내 (데이터가 없는 지역일 때). null이면 없음 */
    var notice: String? = null
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private var center: Fix? = null
    private var heading = 0f
    private var highlightId: String? = null
    private var target: SpotHint? = null
    private var mood = Mood.NEUTRAL

    /** 입체 보기 여부 (끄면 위에서 내려다본 평면 지도) */
    var tilted = true
        set(value) {
            field = value
            invalidate()
        }

    // ------------------------------------------------------------------ 투영 (화면 하나 그릴 때마다 정한다)
    private var camX = 0f          // 내 차 위치 (m)
    private var camY = 0f
    private var anchorX = 0f       // 내 차가 놓이는 화면 위치
    private var anchorY = 0f
    private var focal = 1f
    private var camDist = 1f       // 카메라에서 내 차까지 거리 (m)
    private var sinA = 0f          // 수직에서 기운 각도
    private var cosA = 1f
    private var sinH = 0f          // 진행 방향 (위쪽으로 돌린다)
    private var cosH = 1f
    private var camGroundX = 0f    // 카메라를 땅에 내린 위치 (벽이 보이는 쪽 판단)
    private var camGroundY = 0f
    private var viewMinX = 0f      // 화면에 들어오는 땅의 범위 (m)
    private var viewMaxX = 0f
    private var viewMinY = 0f
    private var viewMaxY = 0f
    private val pt = FloatArray(2)

    /** 땅 위 점 (x, y, 높이 z) → 화면 좌표(pt). 깊이(카메라에서의 거리)를 돌려준다. */
    private fun project(x: Float, y: Float, z: Float): Float {
        val dx = x - camX
        val dy = y - camY
        val rx = dx * cosH - dy * sinH
        val ry = dx * sinH + dy * cosH
        var depth = ry * sinA - z * cosA + camDist
        if (depth < camDist * NEAR) depth = camDist * NEAR
        val up = ry * cosA + z * sinA
        pt[0] = anchorX + focal * rx / depth
        pt[1] = anchorY - focal * up / depth
        return depth
    }

    /** 화면 좌표 → 땅 위 점 (화면에 보이는 범위를 구할 때만 쓴다) */
    private fun unproject(sx: Float, sy: Float): Pair<Float, Float> {
        val k = (anchorY - sy) / focal
        val denom = cosA - k * sinA
        val ry = if (denom <= 1e-3f) FAR_M else min(k * camDist / denom, FAR_M)
        val depth = ry * sinA + camDist
        val rx = (sx - anchorX) * depth / focal
        return (camX + rx * cosH + ry * sinH) to (camY - rx * sinH + ry * cosH)
    }

    private fun inView(minX: Float, minY: Float, maxX: Float, maxY: Float) =
        !(maxX < viewMinX || minX > viewMaxX || maxY < viewMinY || minY > viewMaxY)

    // ------------------------------------------------------------------ 붓
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
    /** 금지구역 모양 둘레의 반경 띠 (선 굵기 = 반경 × 2, 바깥 절반이 보인다) */
    private val band = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
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
    private val wallPaths = Array(3) { Path() }
    private val rect = RectF()

    fun setData(roadMap: RoadMap?, zoneList: List<Zone>, spotList: List<SafeSpot>, cctvList: List<Cctv> = emptyList()) {
        roads = roadMap
        zones = zoneList
        spots = spotList
        cctvs = cctvList
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
        val c = center
        val here = c?.let { meters(it.lat, it.lon) }
        // 건물 배경(시연용 빌드)은 시연 동네만 있어, 그 밖에서는 도로망으로 그린다
        val bm = base?.takeIf { b -> here == null || b.covers(here.first, here.second, BASE_EDGE_M) }
        canvas.drawColor(if (bm != null) LAND else MAP_BG)
        if (here == null) {
            label.textAlign = Paint.Align.CENTER
            label.textSize = u(28f)
            canvas.drawText(if (c == null) "위치를 찾는 중" else "지도 데이터 없음", width / 2f, height / 2f, label)
            label.textSize = u(22f)
            label.textAlign = Paint.Align.LEFT
            return
        }
        val scale = setupCamera(here.first, here.second)

        if (bm != null) {
            drawGround(canvas, bm)
            drawBuildings(canvas, bm)
        } else {
            roads?.let { drawRoadNetwork(canvas, it) }
        }
        drawRoadNames(canvas, bm)
        drawZones(canvas)
        drawCctvs(canvas)

        // 내 차 → 세울 곳 점선
        target?.let { t ->
            meters(t.spot.lat, t.spot.lon)?.let { (tx, ty) ->
                project(tx, ty, 0f)
                stroke.pathEffect = dash
                stroke.color = Palette.GREEN
                stroke.strokeWidth = u(6f)
                canvas.drawLine(anchorX, anchorY, pt[0], pt[1] - u(24f), stroke)
                stroke.pathEffect = null
            }
        }

        // 세울 곳 (공영주차장)
        for (sp in spots) {
            val (mx, my) = meters(sp.lat, sp.lon) ?: continue
            project(mx, my, 0f)
            val px = pt[0]
            val py = pt[1]
            if (px < -u(40f) || px > width + u(40f) || py < -u(60f) || py > height + u(40f)) continue
            val isTarget = target?.spot?.id == sp.id
            drawPin(canvas, px, py, if (isTarget) 1f else 0.72f, if (isTarget) Palette.GREEN else withAlpha(Palette.GREEN, 0xB0))
            if (isTarget) drawTag(canvas, px, py - u(70f), "세울 곳 ${target!!.distanceM.roundToInt()}m", Palette.GREEN)
        }

        drawCar(canvas, anchorX, anchorY, if (tilted) 0f else heading)
        notice?.let { drawTag(canvas, width / 2f, height * 0.38f, it, Palette.TEXT2) }
        drawCredit(canvas)
        if (!tilted) drawScaleBar(canvas, scale)
    }

    /** 이번 화면의 카메라를 정한다. 내 차 근처의 축척(px/m)을 돌려준다. */
    private fun setupCamera(cx: Float, cy: Float): Float {
        camX = cx
        camY = cy
        val short = min(width, height).toFloat()
        var s = short / (if (tilted) VIEW_M_TILTED else VIEW_M)
        target?.let { t ->
            meters(t.spot.lat, t.spot.lon)?.let { (tx, ty) ->
                val d = hypot(tx - cx, ty - cy)
                if (d in 1f..800f) s = min(s, short * 0.40f / d)
            }
        }
        s = max(s, short / 1600f)

        anchorX = width / 2f
        anchorY = height * (if (tilted) ANCHOR_TILTED else ANCHOR_FLAT)
        val a = if (tilted) Math.toRadians(TILT_DEG.toDouble()) else 0.0
        sinA = sin(a).toFloat()
        cosA = cos(a).toFloat()
        // 입체 보기에서만 진행 방향을 위로 (평면 보기는 북쪽이 위)
        val h = if (tilted) Math.toRadians(heading.toDouble()) else 0.0
        sinH = sin(h).toFloat()
        cosH = cos(h).toFloat()
        camDist = if (tilted) CAM_DIST_M else 1_000_000f
        focal = s * camDist
        val back = -camDist * sinA
        camGroundX = camX + back * sinH
        camGroundY = camY + back * cosH

        // 화면 네 모서리를 땅으로 되돌려 보이는 범위를 구한다
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for ((sx, sy) in listOf(0f to 0f, width.toFloat() to 0f, 0f to height.toFloat(), width.toFloat() to height.toFloat())) {
            val (wx, wy) = unproject(sx, sy)
            if (wx < x0) x0 = wx
            if (wx > x1) x1 = wx
            if (wy < y0) y0 = wy
            if (wy > y1) y1 = wy
        }
        viewMinX = x0 - VIEW_MARGIN_M
        viewMaxX = x1 + VIEW_MARGIN_M
        viewMinY = y0 - VIEW_MARGIN_M
        viewMaxY = y1 + VIEW_MARGIN_M
        return s
    }

    // ------------------------------------------------------------------ 배경

    /** 면(고리 여러 개)을 높이 z에서 투영해 tmpPath에 담는다 */
    private fun buildAreaPath(rings: List<FloatArray>, z: Float): Path {
        tmpPath.rewind()
        tmpPath.fillType = Path.FillType.EVEN_ODD
        for (ring in rings) {
            var i = 0
            while (i + 1 < ring.size) {
                project(ring[i], ring[i + 1], z)
                if (i == 0) tmpPath.moveTo(pt[0], pt[1]) else tmpPath.lineTo(pt[0], pt[1])
                i += 2
            }
            tmpPath.close()
        }
        return tmpPath
    }

    /** 공원·하천 → 도로(실폭) */
    private fun drawGround(canvas: Canvas, bm: BaseMap) {
        areaFill.color = PARK
        for (a in bm.layer("park")) if (inView(a.minX, a.minY, a.maxX, a.maxY)) canvas.drawPath(buildAreaPath(a.rings, 0f), areaFill)
        areaFill.color = WATER
        for (a in bm.layer("water")) if (inView(a.minX, a.minY, a.maxX, a.maxY)) canvas.drawPath(buildAreaPath(a.rings, 0f), areaFill)

        val roadAreas = bm.layer("roadarea")
        areaEdge.color = ROAD_EDGE
        areaEdge.strokeWidth = u(3f)
        for (a in roadAreas) if (inView(a.minX, a.minY, a.maxX, a.maxY)) canvas.drawPath(buildAreaPath(a.rings, 0f), areaEdge)
        areaFill.color = Palette.SURFACE
        for (a in roadAreas) if (inView(a.minX, a.minY, a.maxX, a.maxY)) canvas.drawPath(buildAreaPath(a.rings, 0f), areaFill)
    }

    private class VisibleBuilding(val area: BaseMap.Area, val depth: Float)

    private fun drawBuildings(canvas: Canvas, bm: BaseMap) {
        val visible = ArrayList<VisibleBuilding>()
        for (a in bm.layer("building")) {
            if (!inView(a.minX, a.minY, a.maxX, a.maxY)) continue
            visible += VisibleBuilding(a, project(a.cx, a.cy, 0f))
        }
        if (!tilted) {
            // 평면: 아래로 살짝 그림자를 깔아 입체감만 준다
            areaFill.color = BUILDING_SHADOW
            canvas.save()
            canvas.translate(u(1.5f), u(3f))
            for (v in visible) canvas.drawPath(buildAreaPath(v.area.rings, 0f), areaFill)
            canvas.restore()
            areaEdge.color = BUILDING_EDGE
            areaEdge.strokeWidth = u(1.5f)
            for (v in visible) {
                val p = buildAreaPath(v.area.rings, 0f)
                areaFill.color = BUILDING
                canvas.drawPath(p, areaFill)
                canvas.drawPath(p, areaEdge)
            }
            return
        }
        // 입체: 먼 건물부터 그려야 가까운 건물이 앞을 가린다
        visible.sortByDescending { it.depth }
        for (v in visible) drawExtruded(canvas, v.area)
    }

    /**
     * 건물 하나를 세워 그린다. 카메라 쪽을 보는 벽만 그리고, 벽 방향에 따라 밝기를 세 단계로 나눈 뒤 지붕을 덮는다.
     * 건물 도형에는 층수 정보가 없어 높이는 바닥 넓이로 정한 '입체감용 표현'이다 (실제 층수 아님).
     */
    private fun drawExtruded(canvas: Canvas, a: BaseMap.Area) {
        val h = displayHeight(a)
        val ring = a.rings.firstOrNull() ?: return
        val n = ring.size / 2
        if (n < 3) return
        for (p in wallPaths) p.rewind()
        for (i in 0 until n) {
            val j = (i + 1) % n
            val x0 = ring[i * 2]
            val y0 = ring[i * 2 + 1]
            val x1 = ring[j * 2]
            val y1 = ring[j * 2 + 1]
            val ex = x1 - x0
            val ey = y1 - y0
            val len = sqrt(ex * ex + ey * ey)
            if (len < 0.3f) continue
            // 바깥쪽을 향하는 법선
            val nx = if (a.ccw) ey / len else -ey / len
            val ny = if (a.ccw) -ex / len else ex / len
            val mx = (x0 + x1) / 2
            val my = (y0 + y1) / 2
            if (nx * (camGroundX - mx) + ny * (camGroundY - my) <= 0f) continue
            val light = nx * LIGHT_X + ny * LIGHT_Y
            val p = wallPaths[if (light > 0.3f) 0 else if (light > -0.3f) 1 else 2]
            project(x0, y0, 0f); p.moveTo(pt[0], pt[1])
            project(x1, y1, 0f); p.lineTo(pt[0], pt[1])
            project(x1, y1, h); p.lineTo(pt[0], pt[1])
            project(x0, y0, h); p.lineTo(pt[0], pt[1])
            p.close()
        }
        areaFill.color = WALL_LIGHT
        canvas.drawPath(wallPaths[0], areaFill)
        areaFill.color = WALL_MID
        canvas.drawPath(wallPaths[1], areaFill)
        areaFill.color = WALL_DARK
        canvas.drawPath(wallPaths[2], areaFill)
        val roof = buildAreaPath(a.rings, h)
        areaFill.color = ROOF
        canvas.drawPath(roof, areaFill)
        areaEdge.color = ROOF_EDGE
        areaEdge.strokeWidth = u(1.5f)
        canvas.drawPath(roof, areaEdge)
    }

    /** 바닥 넓이로 정하는 표시 높이(m). 같은 건물은 늘 같은 높이가 나오도록 위치로 약간만 다르게 한다. */
    private fun displayHeight(a: BaseMap.Area): Float {
        // 길과 금지구역 표시를 가리지 않도록 실제보다 낮게 세운다
        val base = when {
            a.areaM2 < 100f -> 5f
            a.areaM2 < 250f -> 7f
            a.areaM2 < 600f -> 9f
            a.areaM2 < 1500f -> 12f
            else -> 14f
        }
        val jitter = ((a.cx * 7f + a.cy * 13f).toInt() and 3) * 0.7f
        return base + jitter
    }

    /** 배경 파일이 없을 때: 표준노드링크 도로를 차로 폭만큼 */
    private fun drawRoadNetwork(canvas: Canvas, road: RoadMap) {
        for (pass in 0..1) {
            for (r in road.roads) {
                if (!inView(r.minX, r.minY, r.maxX, r.maxY)) continue
                val n = r.points.size / 2
                if (n < 2) continue
                tmpPath.rewind()
                var nearest = Float.MAX_VALUE
                for (i in 0 until n) {
                    val d = project(r.points[i * 2], r.points[i * 2 + 1], 0f)
                    if (d < nearest) nearest = d
                    if (i == 0) tmpPath.moveTo(pt[0], pt[1]) else tmpPath.lineTo(pt[0], pt[1])
                }
                val k = focal / nearest
                val widthPx = max(r.lanes * 3.2f * k, u(6f))
                if (pass == 0) {
                    roadCasing.color = if (r.rank <= 104) CASING_MAJOR else CASING_MINOR
                    roadCasing.strokeWidth = widthPx + u(5f)
                    canvas.drawPath(tmpPath, roadCasing)
                } else {
                    roadFill.strokeWidth = widthPx
                    canvas.drawPath(tmpPath, roadFill)
                }
            }
        }
    }

    // ------------------------------------------------------------------ 금지구역

    private fun drawZones(canvas: Canvas) {
        // 어린이보호구역 (영역): 땅 위 원을 투영해 그린다
        for (z in zones) {
            if (z.type != ZoneType.SCHOOL_ZONE) continue
            val (zx, zy) = meters(z.lat, z.lon) ?: continue
            val r = z.radiusM.toFloat()
            if (!inView(zx - r, zy - r, zx + r, zy + r)) continue
            val hl = z.id == highlightId
            tmpPath.rewind()
            for (k in 0..SCHOOL_SEGMENTS) {
                val t = (k * 2 * Math.PI / SCHOOL_SEGMENTS)
                project(zx + r * cos(t).toFloat(), zy + r * sin(t).toFloat(), 0f)
                if (k == 0) tmpPath.moveTo(pt[0], pt[1]) else tmpPath.lineTo(pt[0], pt[1])
            }
            tmpPath.close()
            fill.color = withAlpha(Palette.ORANGE, if (hl) 0x2A else 0x18)
            canvas.drawPath(tmpPath, fill)
            stroke.pathEffect = dash
            stroke.strokeWidth = u(if (hl) 4f else 2.5f)
            stroke.color = withAlpha(Palette.ORANGE, if (hl) 0xFF else 0x99)
            canvas.drawPath(tmpPath, stroke)
            stroke.pathEffect = null
        }

        // 교차로 모퉁이: 교차로 자체를 칠하면 지도가 온통 빨개지므로 테두리 둘레 5m 띠만 옅게. 횡단보도보다 먼저(아래) 그린다
        for (z in zones) {
            if (z.type != ZoneType.CORNER) continue
            val s = z.shape ?: continue
            val (zx, zy) = meters(z.lat, z.lon) ?: continue
            val hl = z.id == highlightId
            if (!hl && hypot(zx - camX, zy - camY) > NEAR_M) continue
            val k = focal / project(zx, zy, 0f)
            val cx = pt[0]
            val cy = pt[1]
            shapePath(s)
            band.strokeWidth = z.radiusM.toFloat() * 2f * k
            band.color = withAlpha(Palette.RED, if (hl) 0x30 else 0x16)
            canvas.drawPath(tmpPath, band)
            if (hl) {
                fill.color = withAlpha(Palette.RED, 0x14)
                canvas.drawPath(tmpPath, fill)
            }
            stroke.pathEffect = dash
            stroke.strokeWidth = u(if (hl) 2.5f else 1.5f)
            stroke.color = withAlpha(Palette.RED, if (hl) 0xE0 else 0x70)
            canvas.drawPath(tmpPath, stroke)
            stroke.pathEffect = null
            if (hl) drawTag(canvas, cx, cy - u(30f), "${z.type.place} ${z.type.range}", Palette.RED)
        }

        // 모양이 있는 금지구역(횡단보도): 실제 모양과 그 둘레 반경(10m) 띠. 내 차 주변과 지금 걸린 곳만
        for (z in zones) {
            val s = z.shape ?: continue
            if (z.type == ZoneType.SCHOOL_ZONE || z.type == ZoneType.CORNER) continue
            val (zx, zy) = meters(z.lat, z.lon) ?: continue
            val hl = z.id == highlightId
            if (!hl && hypot(zx - camX, zy - camY) > NEAR_M) continue
            val k = focal / project(zx, zy, 0f)
            val cx = pt[0]
            val cy = pt[1]
            shapePath(s)
            band.strokeWidth = z.radiusM.toFloat() * 2f * k
            band.color = withAlpha(Palette.RED, if (hl) 0x26 else 0x14)
            canvas.drawPath(tmpPath, band)
            fill.color = withAlpha(Palette.RED, if (hl) 0x55 else 0x30)
            canvas.drawPath(tmpPath, fill)
            stroke.strokeWidth = u(if (hl) 3f else 2f)
            stroke.color = withAlpha(Palette.RED, if (hl) 0xFF else 0xB0)
            canvas.drawPath(tmpPath, stroke)
            if (hl) drawTag(canvas, cx, cy - u(30f), "${z.type.place} ${z.type.range}", Palette.RED)
        }

        // 점 금지구역: 내 차 주변(반경 NEAR_M)과 지금 걸린 곳만
        for (z in zones) {
            if (z.type == ZoneType.SCHOOL_ZONE || z.shape != null) continue
            val (zx, zy) = meters(z.lat, z.lon) ?: continue
            val hl = z.id == highlightId
            if (!hl && hypot(zx - camX, zy - camY) > NEAR_M) continue
            val depth = project(zx, zy, 0f)
            val px = pt[0]
            val py = pt[1]
            val k = focal / depth
            val rx = max(z.radiusM.toFloat() * k, u(if (hl) 20f else 12f))
            val ry = rx * max(cosA, 0.45f) // 땅에 누운 원은 비스듬히 보면 납작해진다
            rect.set(px - rx, py - ry, px + rx, py + ry)
            fill.color = withAlpha(Palette.RED, if (hl) 0x33 else 0x1F)
            canvas.drawOval(rect, fill)
            stroke.strokeWidth = u(if (hl) 4f else 2.5f)
            stroke.color = withAlpha(Palette.RED, if (hl) 0xFF else 0xB0)
            canvas.drawOval(rect, stroke)
            fill.color = Palette.RED
            canvas.drawCircle(px, py, u(if (hl) 8f else 5f), fill)
            if (hl) drawTag(canvas, px, py - ry - u(14f), "${z.type.place} ${z.type.range}", Palette.RED)
        }
    }

    /** 고정형 단속 CCTV: 내 차 주변만 작은 카메라 표시 (어두운 네모 + 흰 렌즈) */
    private fun drawCctvs(canvas: Canvas) {
        for (c in cctvs) {
            val (cx, cy) = meters(c.lat, c.lon) ?: continue
            if (hypot(cx - camX, cy - camY) > NEAR_M * 1.5f) continue
            project(cx, cy, 0f)
            val x = pt[0]
            val y = pt[1]
            val h = u(13f)
            rect.set(x - h, y - h, x + h, y + h)
            fill.color = Palette.FILLED
            canvas.drawRoundRect(rect, u(6f), u(6f), fill)
            fill.color = Palette.SURFACE
            canvas.drawCircle(x, y, u(6f), fill)
            fill.color = Palette.FILLED
            canvas.drawCircle(x, y, u(3f), fill)
        }
    }

    /** 위도·경도 목록(shape)을 땅 위에 투영해 tmpPath에 담는다 */
    private fun shapePath(s: DoubleArray) {
        tmpPath.rewind()
        var first = true
        var i = 0
        while (i + 1 < s.size) {
            val m = meters(s[i], s[i + 1])
            i += 2
            if (m == null) continue
            project(m.first, m.second, 0f)
            if (first) tmpPath.moveTo(pt[0], pt[1]) else tmpPath.lineTo(pt[0], pt[1])
            first = false
        }
        tmpPath.close()
    }

    // ------------------------------------------------------------------ 도로 이름

    private class LabelCand(val name: String, val priority: Int, val points: FloatArray, val lengthM: Float)

    private val placed = ArrayList<FloatArray>()
    private val runX = ArrayList<Float>()
    private val runY = ArrayList<Float>()

    /**
     * 도로 이름: 화면 안에 보이는 구간 중 가장 긴 곳에 글자를 얹는다.
     * 큰길(대로·로) 먼저, 남는 자리에 '길'. 글자끼리, 내 차와는 겹치지 않게.
     */
    private fun drawRoadNames(canvas: Canvas, bm: BaseMap?) {
        val cands = HashMap<String, LabelCand>()
        fun offer(c: LabelCand) {
            val cur = cands[c.name]
            if (cur == null || c.lengthM > cur.lengthM) cands[c.name] = c
        }
        if (bm != null && bm.lines.isNotEmpty()) {
            for (l in bm.lines) {
                if (l.name.isBlank() || !inView(l.minX, l.minY, l.maxX, l.maxY)) continue
                offer(LabelCand(l.name, l.grade, l.points, l.lengthM))
            }
        } else {
            roads?.let { road ->
                for (r in road.roads) {
                    // 이름 없는 구간(-), 사이길(.)은 빼고 큰길 위주로
                    if (r.name.isBlank() || r.name == "-" || '.' in r.name || r.rank > 105) continue
                    if (!inView(r.minX, r.minY, r.maxX, r.maxY)) continue
                    offer(LabelCand(r.name, if (r.rank <= 104) 2 else 3, r.points, r.lengthM))
                }
            }
        }

        placed.clear()
        placed += floatArrayOf(anchorX, anchorY, u(90f))
        val margin = u(30f)
        val step = u(36f)
        var drawn = 0
        for (c in cands.values.sortedWith(compareBy<LabelCand> { it.priority }.thenByDescending { it.lengthM })) {
            if (drawn >= MAX_LABELS) break
            val major = c.priority <= 3
            label.textSize = u(if (major) 23f else 20f)
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
                project(c.points[i * 2], c.points[i * 2 + 1], 0f)
                val x1 = pt[0]
                val y1 = pt[1]
                val pieces = if (i == 0) 1 else max(1, ceil(hypot(x1 - runX.last(), y1 - runY.last()) / step).toInt())
                val x0 = if (i == 0) x1 else runX.last()
                val y0 = if (i == 0) y1 else runY.last()
                for (k in 1..pieces) {
                    val x = x0 + (x1 - x0) * k / pieces
                    val y = y0 + (y1 - y0) * k / pieces
                    // 위쪽 상태 칩 줄과 아래쪽 범례·출처 자리는 비운다
                    val inside = x in margin..(width - margin) && y in (margin + u(LABEL_TOP_PX))..(height - margin - u(LABEL_BOTTOM_PX))
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
            tmpPath.rewind()
            for (k in 0..(bestEnd - bestStart)) {
                val idx = if (forward) bestStart + k else bestEnd - k
                if (k == 0) tmpPath.moveTo(runX[idx], runY[idx]) else tmpPath.lineTo(runX[idx], runY[idx])
            }
            label.color = if (major) Palette.TEXT2 else Palette.GRAY_500
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

    // ------------------------------------------------------------------ 표시물

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
        tmpPath.rewind()
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

    /** 내 차. arrowDeg: 화살표 방향 (입체 보기에서는 지도가 돌아가므로 늘 위쪽) */
    private fun drawCar(canvas: Canvas, x: Float, y: Float, arrowDeg: Float) {
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
        val a = Math.toRadians(arrowDeg.toDouble() - 90.0)
        val len = u(22f)
        fun px(off: Double, l: Float) = (x + l * cos(a + off)).toFloat()
        fun py(off: Double, l: Float) = (y + l * sin(a + off)).toFloat()
        tmpPath.rewind()
        tmpPath.moveTo(px(0.0, len), py(0.0, len))
        tmpPath.lineTo(px(2.4, len), py(2.4, len))
        tmpPath.lineTo(px(Math.PI, len * 0.35f), py(Math.PI, len * 0.35f))
        tmpPath.lineTo(px(-2.4, len), py(-2.4, len))
        tmpPath.close()
        fill.color = Palette.BLUE
        canvas.drawPath(tmpPath, fill)
    }

    /** 지도 출처 (공공데이터 이용 조건: 출처 표시). 왼쪽 아래 범례와 겹치지 않게 짧게, 자세한 출처는 README */
    private fun drawCredit(canvas: Canvas) {
        val credit = if (base != null) "지도 데이터 © 행정안전부 · 국토교통부" else "지도 데이터 © 국토교통부"
        label.textAlign = Paint.Align.RIGHT
        label.textSize = u(16f)
        label.color = Palette.TEXT3
        canvas.drawText(credit, width - u(28f), height - u(18f), label)
        label.textSize = u(22f)
        label.color = Palette.GRAY_500
        label.textAlign = Paint.Align.LEFT
    }

    /** 평면 보기에서만 (입체 보기는 거리마다 축척이 달라 표시하지 않는다) */
    private fun drawScaleBar(canvas: Canvas, s: Float) {
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
        /** 평면 보기: 화면 짧은 변에 담을 거리(m) */
        private const val VIEW_M = 200f
        /** 입체 보기: 내 차 근처 축척 기준 거리(m). 앞쪽은 원근 때문에 더 멀리까지 보인다 */
        private const val VIEW_M_TILTED = 170f
        /** 입체 보기 기울기 (0 = 바로 위에서) */
        private const val TILT_DEG = 45f
        /** 카메라에서 내 차까지 거리(m). 작을수록 원근이 세진다 */
        private const val CAM_DIST_M = 360f
        /** 내 차가 놓이는 화면 높이 비율 */
        private const val ANCHOR_FLAT = 0.54f
        private const val ANCHOR_TILTED = 0.70f
        private const val NEAR = 0.2f
        private const val FAR_M = 1_200f
        private const val VIEW_MARGIN_M = 30f
        private const val SCHOOL_SEGMENTS = 48

        /** 이 거리 안의 점 금지구역만 지도에 표시 */
        private const val NEAR_M = 90f
        /** 내 차가 건물 배경 범위 끝에서 이만큼 안쪽일 때만 배경으로 그린다 (화면 가장자리가 비지 않게) */
        private const val BASE_EDGE_M = 120f
        private const val MAX_LABELS = 7
        /** 도로 이름을 넣지 않는 위·아래 띠 (디자인 기준 px) */
        private const val LABEL_TOP_PX = 70f
        private const val LABEL_BOTTOM_PX = 60f

        /** 벽 밝기를 정하는 빛의 방향 (남서쪽에서 비춤, 단위 벡터) */
        private const val LIGHT_X = -0.6f
        private const val LIGHT_Y = -0.8f

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
        private const val ROOF = 0xFFECEEF2.toInt()
        private const val ROOF_EDGE = 0xFFC6CAD1.toInt()
        private const val WALL_LIGHT = 0xFFDADDE3.toInt()
        private const val WALL_MID = 0xFFCDD1D8.toInt()
        private const val WALL_DARK = 0xFFBFC4CC.toInt()
        private const val PARK = 0xFFD8EEDB.toInt()
        private const val WATER = 0xFFCDE1F6.toInt()
    }
}
