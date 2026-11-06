package dev.jamkkanman.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 앱에 실제로 들어가는 데이터(assets/zones_daechi.json, 서울 강남구)로 확인한다.
 * 시연 줄의 지점들이 늘 같은 결과를 내는지 고정해 두어, 데이터를 다시 만들어도 시연이 깨지지 않게 한다.
 */
class DemoDataTest {

    private val data: ZoneData by lazy { ZoneRepository.parse(assetFile("zones_daechi.json").readText()) }
    private val checker by lazy { ZoneChecker(data) }

    @Test
    fun `강남구 데이터가 다 들어 있다`() {
        assertEquals("서울 강남구", data.region)
        val byType = data.zones.groupingBy { it.type }.eachCount()
        assertTrue("소화전 ${byType[ZoneType.HYDRANT]}", (byType[ZoneType.HYDRANT] ?: 0) > 7_000)
        assertTrue("횡단보도 ${byType[ZoneType.CROSSWALK]}", (byType[ZoneType.CROSSWALK] ?: 0) > 5_000)
        assertTrue("버스정류장 ${byType[ZoneType.BUS_STOP]}", (byType[ZoneType.BUS_STOP] ?: 0) > 1_000)
        assertTrue("교차로 모퉁이 ${byType[ZoneType.CORNER]}", (byType[ZoneType.CORNER] ?: 0) > 800)
        assertTrue("어린이보호구역 ${byType[ZoneType.SCHOOL_ZONE]}", (byType[ZoneType.SCHOOL_ZONE] ?: 0) > 50)
        assertTrue(data.spots.size > 100)
        assertTrue(data.cctvs.size > 300)
        assertTrue(data.enforcement.isNotEmpty())
        assertTrue(data.coverage != null)
    }

    @Test
    fun `좌표가 모두 강남구 근처에 있다`() {
        for (z in data.zones) {
            assertTrue("${z.id} ${z.lat}", z.lat in 37.44..37.55)
            assertTrue("${z.id} ${z.lon}", z.lon in 126.98..127.13)
            assertTrue("${z.id} 반경 ${z.radiusM}", z.radiusM > 0)
        }
    }

    @Test
    fun `시연 지점은 늘 같은 결과`() {
        val expected = mapOf(
            "학원가 버스정류장" to ZoneType.BUS_STOP,
            "학원가 어린이보호구역" to ZoneType.SCHOOL_ZONE,
            "학원가 횡단보도" to ZoneType.CROSSWALK,
            "교차로 모퉁이" to ZoneType.CORNER,
            "초등학교 앞" to ZoneType.SCHOOL_ZONE,
            "소화전 앞" to ZoneType.HYDRANT,
        )
        for (place in data.demo) {
            val r = checker.check(Fix(place.lat, place.lon, 5f, "시연", 0), hour = 16)
            assertTrue(place.label, r.covered)
            val want = expected[place.label]
            if (want != null) {
                assertEquals(place.label, want, r.top?.zone?.type)
                assertTrue(place.label, r.top!!.certain)
            } else {
                // '단속 잦은 곳'·'공영주차장 앞': 금지구역은 아니지만 단속 주의
                assertNull(place.label, r.top)
                assertTrue(place.label, r.risk?.busy == true)
            }
            assertTrue("${place.label}: 세울 곳 없음", r.spot != null)
        }
        assertTrue(data.demo.map { it.label }.containsAll(expected.keys))
    }

    @Test
    fun `강남구 밖(에뮬레이터 기본 위치 성남시)은 확인 범위 밖`() {
        assertFalse(checker.covers(37.4405, 127.0868))
        // 바로 옆 서초구(서초역 부근)도 범위 밖
        assertFalse(checker.covers(37.4919, 127.0076))
        assertTrue(checker.covers(37.4977, 127.0566))
    }

    companion object {
        fun assetFile(name: String): File =
            listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name")).firstOrNull { it.exists() }
                ?: error("$name 을(를) 찾을 수 없음 (작업 폴더: ${File(".").absolutePath})")
    }
}
