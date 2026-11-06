package dev.jamkkanman.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "경유지로 넣을까요?"에 대한 짧은 음성 대답 */
class YesNoTest {

    @Test
    fun `짧은 긍정`() {
        for (t in listOf("응", "응 넣어 줘", "네", "예.", "어", "그래", "좋아요", "넣어줘", "오케이", "OK")) {
            assertEquals(t, true, YesNo.parse(t))
        }
    }

    @Test
    fun `짧은 부정`() {
        for (t in listOf("아니", "아니요", "아니 괜찮아", "괜찮아", "됐어", "안 넣어도 돼", "넣지 마", "싫어", "취소")) {
            assertEquals(t, false, YesNo.parse(t))
        }
    }

    @Test
    fun `아니가 섞이면 경로를 바꾸지 않는다`() {
        assertEquals(false, YesNo.parse("응 아니 아니야"))
        assertEquals(false, YesNo.parse("어 아니"))
    }

    @Test
    fun `한 글자 대답은 낱말일 때만`() {
        // '어'·'네'가 다른 말 속에 들어 있다고 응으로 보지 않는다
        assertNull(YesNo.parse("어디야"))
        assertNull(YesNo.parse("네비 켜 줘"))
    }

    @Test
    fun `말이 없거나 상관없는 말이면 모름`() {
        assertNull(YesNo.parse(null))
        assertNull(YesNo.parse(""))
        assertNull(YesNo.parse("음…"))
        assertNull(YesNo.parse("오늘 날씨 어때"))
    }
}
