package dev.jamkkanman.app.core

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.Date
import kotlin.math.roundToLong

/**
 * 이 차 안에만 남는 기록.
 * - 이번 달 아낀 과태료: 알림을 듣고 금지구역 밖으로 옮겨 세웠을 때만 더한다.
 * - 정차 수요 기록: 운전자가 동의했을 때만, 정확한 위치 대신 약 50m 칸과 시간대·요일만 남긴다.
 */
class Ledger(context: Context) {

    private val prefs = context.getSharedPreferences("ledger", Context.MODE_PRIVATE)
    private val demandFile = File(context.filesDir, DEMAND_FILE)

    private fun monthKey(): String = KoreaTime.format("yyyy-MM").format(Date())

    val monthSavedWon: Int get() = prefs.getInt("saved_${monthKey()}", 0)
    val monthMovedCount: Int get() = prefs.getInt("moved_${monthKey()}", 0)
    val monthWarnCount: Int get() = prefs.getInt("warn_${monthKey()}", 0)
    val demandCount: Int get() = prefs.getInt("demand_count", 0)

    var shareConsent: Boolean
        get() = prefs.getBoolean("share_consent", false)
        set(value) = prefs.edit().putBoolean("share_consent", value).apply()

    /** 도착 전 안내 때 '세울 곳'을 내비 경유지로 바로 넣을지 */
    var autoWaypoint: Boolean
        get() = prefs.getBoolean("auto_waypoint", true)
        set(value) = prefs.edit().putBoolean("auto_waypoint", value).apply()

    fun recordWarning() {
        val k = "warn_${monthKey()}"
        prefs.edit().putInt(k, prefs.getInt(k, 0) + 1).apply()
    }

    fun recordMoved(fineWon: Int) {
        val m = monthKey()
        prefs.edit()
            .putInt("saved_$m", prefs.getInt("saved_$m", 0) + fineWon)
            .putInt("moved_$m", prefs.getInt("moved_$m", 0) + 1)
            .apply()
    }

    /** 동의한 경우에만 익명 칸 단위로 한 줄 남긴다. event: warn(알림) / moved(옮겨 세움) */
    fun appendDemand(event: String, type: ZoneType, lat: Double, lon: Double) {
        if (!shareConsent) return
        val cal = KoreaTime.calendar()
        val line = JSONObject()
            .put("event", event)
            .put("type", type.name)
            .put("cellLat", snap(lat))
            .put("cellLon", snap(lon))
            .put("hour", cal.get(Calendar.HOUR_OF_DAY))
            .put("weekday", cal.get(Calendar.DAY_OF_WEEK))
            .toString()
        runCatching {
            demandFile.appendText(line + "\n")
            prefs.edit().putInt("demand_count", demandCount + 1).apply()
        }
    }

    /** 약 50m 칸 (위도 0.0005도 ≈ 55m, 경도 0.0005도 ≈ 44m) */
    private fun snap(v: Double): Double = (v / CELL_DEG).roundToLong() * 5 / 10_000.0

    companion object {
        const val DEMAND_FILE = "demand.jsonl"
        private const val CELL_DEG = 0.0005
    }
}
