package dev.jamkkanman.app.core

/**
 * 짧은 음성 대답을 응(true) / 아니(false) / 모름(null)으로 나눈다.
 * 운전 중 대답이라 '아니'가 섞이면 아니로 본다 (경로를 함부로 바꾸지 않는 쪽).
 */
object YesNo {

    /** 이 말이 들어 있으면 아니 */
    private val NO = listOf(
        "아니", "아냐", "아녀", "싫어", "싫다", "됐어", "됐다", "괜찮아", "괜찮습니다", "필요 없", "필요없",
        "넣지 마", "넣지마", "안 넣", "안넣", "하지 마", "하지마", "취소", "그만",
    )

    /** 낱말 하나로 말했을 때만 응 (다른 말 속에 흔히 섞이는 한 글자 대답) */
    private val YES_WORDS = setOf("응", "웅", "어", "네", "넵", "예", "콜", "ok", "okay", "오케이", "yes")

    /** 이 말이 들어 있으면 응 */
    private val YES = listOf("그래", "좋아", "넣어", "넣자", "해 줘", "해줘", "해 주세요", "해주세요", "부탁", "그렇게", "오케이")

    fun parse(text: String?): Boolean? {
        val t = text.orEmpty().lowercase().replace(Regex("[\\s.,!?~…]+"), " ").trim()
        if (t.isEmpty()) return null
        if (NO.any { t.contains(it) }) return false
        if (t.split(" ").any { it in YES_WORDS }) return true
        if (YES.any { t.contains(it) }) return true
        return null
    }
}
