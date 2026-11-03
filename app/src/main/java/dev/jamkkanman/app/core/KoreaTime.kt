package dev.jamkkanman.app.core

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 과태료 시간대(어린이보호구역 08~20시)는 한국 시각 기준이라 기기 시간대 설정과 상관없이 서울 시각을 쓴다.
 * (에뮬레이터·심사 환경의 시간대가 해외로 잡혀 있어도 결과가 같게)
 */
object KoreaTime {
    val ZONE: TimeZone = TimeZone.getTimeZone("Asia/Seoul")

    fun calendar(): Calendar = Calendar.getInstance(ZONE, Locale.KOREA)

    fun hour(): Int = calendar().get(Calendar.HOUR_OF_DAY)

    fun format(pattern: String): SimpleDateFormat = SimpleDateFormat(pattern, Locale.KOREA).apply { timeZone = ZONE }
}
