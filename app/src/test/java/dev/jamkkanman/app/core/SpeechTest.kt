package dev.jamkkanman.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 음성·화면 문장: 운전 중에 듣는 말이라 짧고 정확해야 한다 */
class SpeechTest {

    private fun hit(type: ZoneType, d: Double, certain: Boolean = true) =
        ZoneHit(Zone("t", type, type.place, 37.5, 127.05, "test"), d, certain)

    @Test
    fun `금액은 만 원 단위면 '만 원'으로 읽는다`() {
        assertEquals("4만 원", Speech.won(40_000))
        assertEquals("12만 원", Speech.won(120_000))
        assertEquals("45,000원", Speech.won(45_000))
    }

    @Test
    fun `음성 거리는 화면과 같은 숫자, 최소 1m`() {
        assertEquals("1미터", Speech.meters(0.4))
        assertEquals("8미터", Speech.meters(8.0))
        assertEquals("17미터", Speech.meters(17.2))
        assertEquals("17m", Speech.metersShort(17.2))
        assertEquals("3m", Speech.metersShort(3.4))
    }

    @Test
    fun `괄호 안 설명은 읽지 않는다`() {
        assertEquals("도곡로421 공영주차장", Speech.spokenName("도곡로421 공영주차장 (시간제)"))
    }

    @Test
    fun `받침에 맞춰 조사를 붙인다`() {
        assertEquals("주차장으로", Speech.josa("주차장", "으로", "로"))
        assertEquals("학교로", Speech.josa("학교", "으로", "로"))
        assertEquals("P1로", Speech.josa("P1", "으로", "로"))
    }

    @Test
    fun `확실한 경고는 장소·과태료·옮길 거리·세울 곳 순서`() {
        val spot = SpotHint(SafeSpot("p", SpotKind.PUBLIC_PARKING, "공영주차장", 0.0, 0.0, "", ""), 120.0)
        val text = Speech.warning(hit(ZoneType.BUS_STOP, 3.0), spot, hour = 14)
        assertEquals(
            "버스정류장 10미터 안이에요. 주정차 금지구역이고, 과태료는 4만 원이에요. " +
                "약 8미터만 옮기면 범위 밖이에요. 공영주차장까지 120미터예요.",
            text,
        )
    }

    @Test
    fun `안전벨트가 풀려 멈춘 경우 문 열기 전 확인, 옮길 때는 벨트를 다시 매라고 한다`() {
        val text = Speech.warning(hit(ZoneType.BUS_STOP, 3.0), null, hour = 14, belt = true)
        assertTrue(text.startsWith("문 열기 전에 확인해 주세요. 버스정류장 10미터 안이에요."))
        assertTrue(text.contains("옮기려면 안전벨트를 다시 맨 뒤 약 8미터 옮겨 주세요."))
        assertFalse(text.contains("만 옮기면"))
    }

    @Test
    fun `소화전은 '신고된다'고 하지 않고 안전표지가 있을 때 과태료를 덧붙인다`() {
        val text = Speech.warning(hit(ZoneType.HYDRANT, 2.0), null, hour = 14)
        assertTrue(text.contains("주정차 금지구역이고, 과태료는 4만 원, 안전표지가 있으면 8만 원이에요."))
        assertFalse(text.contains("신고"))
    }

    @Test
    fun `가능성 단계는 단정하지 않는다`() {
        val text = Speech.warning(hit(ZoneType.HYDRANT, 7.0, certain = false), null, hour = 14)
        assertTrue(text.startsWith("소화전 5미터 안일 수 있어요."))
        assertFalse(text.contains("옮기면"))
    }

    @Test
    fun `어린이보호구역 밤 시간은 일반 과태료로 안내`() {
        assertTrue(Speech.warning(hit(ZoneType.SCHOOL_ZONE, 30.0), null, hour = 9).contains("12만 원"))
        assertTrue(Speech.warning(hit(ZoneType.SCHOOL_ZONE, 30.0), null, hour = 21).contains("4만 원"))
    }

    @Test
    fun `단속 카메라가 있으면 경고에 덧붙인다`() {
        val risk = Risk(Cctv("단속 CCTV", 0.0, 0.0), 20.0, 5, "2025년 10~12월")
        assertTrue(Speech.warning(hit(ZoneType.CROSSWALK, 2.0), null, 14, risk).contains("단속 카메라도 있는 곳이에요."))
        assertEquals("단속 CCTV 20m · 2025년 10~12월 이 근처 단속 5건", Speech.riskLine(risk))
    }

    @Test
    fun `단속 정보가 없으면 한 줄도 만들지 않는다`() {
        assertNull(Speech.riskLine(Risk(null, Double.MAX_VALUE, 0, "")))
    }

    @Test
    fun `도착 전에는 경유지로 넣을지 묻고, 대답에 맞게 답한다`() {
        val spot = SpotHint(SafeSpot("p", SpotKind.PUBLIC_PARKING, "도곡로421 공영주차장", 0.0, 0.0, "", ""), 60.0)
        assertEquals(
            "목적지 앞은 버스정류장 10미터 안이에요. 도곡로421 공영주차장을 경유지로 넣을까요?",
            Speech.arrivalQuestion(hit(ZoneType.BUS_STOP, 3.0), spot),
        )
        assertEquals("도곡로421 공영주차장을 경유지로 넣었어요.", Speech.arrivalAnswer(spot, answer = true, added = true))
        assertEquals("알겠어요.", Speech.arrivalAnswer(spot, answer = false, added = false))
        assertEquals("대답을 듣지 못해서 경유지는 넣지 않았어요.", Speech.arrivalAnswer(spot, answer = null, added = false))
    }

    @Test
    fun `금지구역 밖은 '세워도 된다'고 판정하지 않는다`() {
        val busy = Speech.busyHere(Risk(null, Double.MAX_VALUE, 40, "2025년 10~12월"))
        assertTrue(busy.startsWith("주정차 금지구역은 아니지만"))
        assertFalse(busy.contains("세워도"))
        assertEquals("금지구역 밖으로 옮겼어요. 과태료 8만 원 아꼈어요.", Speech.moved(80_000))
    }
}
