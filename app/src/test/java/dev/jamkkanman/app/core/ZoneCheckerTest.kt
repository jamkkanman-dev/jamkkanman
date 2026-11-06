package dev.jamkkanman.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 멈춘 자리 판단: 반경, GPS 오차 '가능성' 단계, 우선순위, 확인 범위, 단속 위험, 세울 곳 */
class ZoneCheckerTest {

    private val lat0 = 37.4977
    private val lon0 = 127.0566
    private val kx = 111_320.0 * Math.cos(Math.toRadians(lat0))

    /** 기준점에서 동쪽 e m, 북쪽 n m */
    private fun lat(n: Double) = lat0 + n / 110_540.0
    private fun lon(e: Double) = lon0 + e / kx

    private val hydrant = Zone("h1", ZoneType.HYDRANT, "소화전", lat0, lon0, "test")
    private val busStop = Zone("b1", ZoneType.BUS_STOP, "정류장", lat(0.0), lon(6.0), "test")
    private val school = Zone("s1", ZoneType.SCHOOL_ZONE, "학교", lat(0.0), lon(400.0), "test", radiusM = 150.0)
    private val spot = SafeSpot("p1", SpotKind.PUBLIC_PARKING, "공영주차장", lat(200.0), lon(0.0), "", "test")
    private val cctv = Cctv("단속 CCTV", lat(0.0), lon(-30.0))

    private val data = ZoneData(
        region = "테스트",
        isSample = true,
        sources = emptyList(),
        zones = listOf(hydrant, busStop, school),
        spots = listOf(spot),
        demo = emptyList(),
        cctvs = listOf(cctv),
        enforcement = listOf(EnforcementCell(lat(0.0), lon(-20.0), 25), EnforcementCell(lat(0.0), lon(500.0), 99)),
        enforcementPeriod = "2025년 10~12월",
    )
    private val checker = ZoneChecker(data)

    private fun fix(e: Double, n: Double, acc: Float? = 0f) = Fix(lat(n), lon(e), acc, "test", 0)

    @Test
    fun `소화전 5m 안이면 확실한 금지`() {
        val r = checker.check(fix(-3.0, 0.0), hour = 14)
        assertEquals(ZoneType.HYDRANT, r.top?.zone?.type)
        assertTrue(r.top!!.certain)
        assertEquals(2.0, r.top!!.exitM, 0.1)
    }

    @Test
    fun `반경 밖이어도 GPS 오차 절반(최대 5m) 안이면 가능성으로 알린다`() {
        // 소화전에서 서쪽 8m: 반경 5m 밖, GPS 오차 10m → 여유 5m 안
        val maybe = checker.check(fix(-8.0, 0.0, acc = 10f), hour = 14)
        assertEquals(ZoneType.HYDRANT, maybe.top?.zone?.type)
        assertFalse(maybe.top!!.certain)
        // 같은 자리, GPS 오차 2m → 여유 1m라 금지구역 밖
        val clear = checker.check(fix(-8.0, 0.0, acc = 2f), hour = 14)
        assertNull(clear.top)
        assertTrue(clear.isClear)
    }

    @Test
    fun `GPS 오차가 아주 커도 여유는 5m까지만`() {
        val r = checker.check(fix(-11.0, 0.0, acc = 50f), hour = 14)
        assertNull(r.top)
    }

    @Test
    fun `여러 구역에 걸치면 확실한 것, 과태료 큰 것 순`() {
        // 소화전 3m, 버스정류장 3m 둘 다 안
        val r = checker.check(fix(3.0, 0.0), hour = 14)
        assertEquals(2, r.hits.size)
        assertEquals(ZoneType.HYDRANT, r.top?.zone?.type)
        assertEquals(ZoneType.BUS_STOP, r.hits[1].zone.type)
    }

    @Test
    fun `어린이보호구역은 시설마다 다른 반경을 쓴다`() {
        assertEquals(ZoneType.SCHOOL_ZONE, checker.check(fix(260.0, 0.0), hour = 9).top?.zone?.type)
        assertNull(checker.check(fix(240.0, 0.0), hour = 9).top)
    }

    @Test
    fun `데이터 범위 밖이면 금지구역이 없다고 말하지 않는다`() {
        val r = checker.check(Fix(37.4405, 127.0868, 5f, "test", 0), hour = 14)
        assertFalse(r.covered)
        assertFalse(r.isClear)
        assertNull(r.top)
    }

    @Test
    fun `단속 CCTV 50m 안이면 단속 주의`() {
        val near = checker.risk(lat(0.0), lon(-5.0))
        assertNotNull(near?.cctv)
        assertEquals(25.0, near!!.cctvM, 0.5)
        assertEquals(25, near.recent)
        assertTrue(near.busy)
    }

    @Test
    fun `CCTV가 없고 단속이 적으면 단속 주의가 아니다`() {
        val far = checker.risk(lat(0.0), lon(200.0))
        assertNull(far?.cctv)
        assertEquals(0, far!!.recent)
        assertFalse(far.busy)
    }

    @Test
    fun `반경 50m 단속이 30건 이상이면 단속이 잦은 곳`() {
        val busy = checker.risk(lat(0.0), lon(500.0))
        assertEquals(99, busy!!.recent)
        assertTrue(busy.busy)
    }

    @Test
    fun `세울 곳은 600m 안에서 가장 가까운 곳`() {
        val hint = checker.nearestSpot(lat(0.0), lon(0.0))
        assertEquals("p1", hint?.spot?.id)
        assertEquals(200.0, hint!!.distanceM, 2.0)
        assertNull(checker.nearestSpot(lat(-500.0), lon(0.0)))
    }

    @Test
    fun `목적지 주변은 반경에 여유를 더해 미리 본다`() {
        // 소화전에서 12m: 지금 세우면 밖이지만 목적지 앞(여유 10m)으로는 알린다
        val near = checker.zonesNear(lat(0.0), lon(-12.0), extraM = 10.0, hour = 14)
        assertEquals(ZoneType.HYDRANT, near.firstOrNull()?.zone?.type)
        assertFalse(near.first().certain)
    }
}
