package dev.jamkkanman.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 거리 계산, 과태료 시간대, 구역 모양, 확인 범위 */
class ZoneBasicsTest {

    private val lat = 37.4977
    private val lon = 127.0566

    @Test
    fun `위도 0_001도는 약 111m`() {
        assertEquals(111.2, Geo.distanceM(lat, lon, lat + 0.001, lon), 0.5)
    }

    @Test
    fun `같은 점의 거리는 0`() {
        assertEquals(0.0, Geo.distanceM(lat, lon, lat, lon), 1e-9)
    }

    @Test
    fun `과태료는 승용차 기준 (소화전은 안전표지가 없을 때 기준, 8만 원은 따로 알림)`() {
        assertEquals(40_000, ZoneType.HYDRANT.fineAt(14))
        assertEquals("안전표지가 있으면 8만 원", ZoneType.HYDRANT.fineNote)
        assertEquals(40_000, ZoneType.CORNER.fineAt(14))
        assertEquals(40_000, ZoneType.BUS_STOP.fineAt(14))
        assertEquals(40_000, ZoneType.CROSSWALK.fineAt(14))
    }

    @Test
    fun `어린이보호구역은 오전 8시부터 오후 8시 전까지만 12만 원`() {
        assertEquals(40_000, ZoneType.SCHOOL_ZONE.fineAt(7))
        assertEquals(120_000, ZoneType.SCHOOL_ZONE.fineAt(8))
        assertEquals(120_000, ZoneType.SCHOOL_ZONE.fineAt(19))
        assertEquals(40_000, ZoneType.SCHOOL_ZONE.fineAt(20))
    }

    @Test
    fun `모양이 없는 구역은 중심점까지 거리`() {
        val z = zone(ZoneType.HYDRANT, lat, lon)
        assertEquals(Geo.distanceM(lat + 0.00003, lon, lat, lon), z.distanceTo(lat + 0.00003, lon), 1e-6)
    }

    @Test
    fun `모양이 있는 구역은 안이면 0, 밖이면 테두리까지`() {
        // 가로 약 20m, 세로 약 4m 횡단보도
        val dLat = 2.0 / 110_540
        val dLon = 10.0 / (111_320 * Math.cos(Math.toRadians(lat)))
        val shape = doubleArrayOf(
            lat - dLat, lon - dLon,
            lat - dLat, lon + dLon,
            lat + dLat, lon + dLon,
            lat + dLat, lon - dLon,
        )
        val z = zone(ZoneType.CROSSWALK, lat, lon, shape)
        assertEquals(0.0, z.distanceTo(lat, lon + dLon * 0.9), 1e-9)
        // 위쪽 테두리에서 북쪽으로 5m
        assertEquals(5.0, z.distanceTo(lat + dLat + 5.0 / 110_540, lon), 0.05)
    }

    @Test
    fun `확인 범위 격자`() {
        // 100m 칸 3x2, 그중 (행 0, 열 1)과 (행 1, 열 2)만 들어 있음
        val cov = Coverage(lat0 = lat, lon0 = lon, cellM = 100.0, cols = 3, cells = setOf(1, 5))
        val kx = 111_320.0 * Math.cos(Math.toRadians(lat))
        fun at(eastM: Double, northM: Double) = cov.contains(lat + northM / 110_540.0, lon + eastM / kx)
        assertTrue(at(150.0, 50.0))
        assertTrue(at(250.0, 150.0))
        assertFalse(at(50.0, 50.0))
        assertFalse(at(-10.0, 50.0))
        assertFalse(at(350.0, 50.0))
    }

    private fun zone(type: ZoneType, lat: Double, lon: Double, shape: DoubleArray? = null) =
        Zone("t", type, type.place, lat, lon, "test", shape = shape)
}
