package dev.jamkkanman.app.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.jamkkanman.app.core.Ledger
import dev.jamkkanman.app.core.ZoneType
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gleo AI(Pleos 차량 음성 비서) 연동 — Pleos App Integration Guide 규격.
 * - assets/schema.json 에 음성으로 부를 수 있는 기능 3개를 적어 두면 Gleo AI가 부팅·설치 때 읽어 간다 (Pleos 검토·승인 후 사용 가능).
 * - Gleo AI는 기능 이름을 Intent action 으로, 인자를 ACTION_PARAM(JSON 문자열)으로 넣어 앱을 부른다.
 *   component="activity" → startActivity, component="service" → sendBroadcast
 * - 결과는 APP_ACTION_RESPONSE 방송으로 돌려준다 (functionName, status, message, mode, data).
 *   message는 가이드대로 영어 한 문장(장소 이름은 한글 그대로), data는 JSON 배열을 문자열로.
 *   앱이 직접 음성으로 답한 경우 mode="silent"로 Gleo가 겹쳐 말하지 않게 한다. Gleo는 7초 안에 응답이 없으면 취소한다.
 */
object GleoBridge {
    /** "여기 세워도 돼?" — 지금 자리 확인 */
    const val CHECK_PARKING_HERE = "CHECK_PARKING_HERE"
    /** "근처에 세울 데 어디야?" — 가장 가까운 세울 곳으로 안내 */
    const val FIND_LEGAL_PARKING = "FIND_LEGAL_PARKING"
    /** "이번 달 과태료 얼마 아꼈어?" — 화면 없이 답만 */
    const val GET_SAVED_FINES = "GET_SAVED_FINES"

    const val EXTRA_PARAM = "ACTION_PARAM"
    private const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
    private const val TAG = "Jamkkanman"

    const val SUCCESS = "success"
    const val FAIL = "fail"

    /** 앱이 직접 음성 안내를 했을 때: Gleo는 말하지 않는다 */
    const val MODE_SILENT = "silent"

    /** 화면을 여는(activity) 기능인지 */
    fun isActivityAction(action: String?): Boolean = action == CHECK_PARKING_HERE || action == FIND_LEGAL_PARKING

    /** Gleo 응답 문장용 영어 이름 */
    fun english(type: ZoneType): String = when (type) {
        ZoneType.HYDRANT -> "fire hydrant"
        ZoneType.CORNER -> "intersection corner"
        ZoneType.BUS_STOP -> "bus stop"
        ZoneType.CROSSWALK -> "crosswalk"
        ZoneType.SCHOOL_ZONE -> "school zone"
    }

    fun wonText(amount: Int): String = "%,d won".format(amount)

    /** Gleo AI에 결과를 돌려준다 */
    fun respond(
        context: Context,
        functionName: String,
        status: String,
        message: String,
        data: JSONArray? = null,
        mode: String? = null,
    ) {
        runCatching {
            val response = JSONObject()
                .put("functionName", functionName)
                .put("status", status)
                .put("message", message)
            if (mode != null) response.put("mode", mode)
            if (data != null) response.put("data", data.toString())
            context.sendBroadcast(
                Intent(RESPONSE_ACTION)
                    .putExtra("packageName", context.packageName)
                    .putExtra("response", response.toString())
            )
            Log.i(TAG, "Gleo AI 응답: $response")
        }.onFailure { Log.e(TAG, "Gleo AI 응답 실패: ${it.message}") }
    }
}

/** 화면 없이 처리하는 기능(component="service")을 받는 곳. 앱은 말하지 않고 Gleo가 data를 보고 답한다. */
class GleoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GleoBridge.GET_SAVED_FINES) return
        Log.i("Jamkkanman", "Gleo AI 요청: ${intent.action}")
        val ledger = Ledger(context)
        val saved = ledger.monthSavedWon
        val moved = ledger.monthMovedCount
        val warned = ledger.monthWarnCount
        val message = when {
            warned == 0 -> "There have been no no-stopping warnings this month, so no fines have been saved yet."
            moved == 0 -> "No fines have been saved this month because the car was not moved after any of the $warned warnings."
            else -> "This month the driver avoided ${GleoBridge.wonText(saved)} in fines by moving the car $moved of $warned times after a warning."
        }
        val data = JSONArray().put(
            JSONObject().put("savedWon", saved).put("movedCount", moved).put("warningCount", warned)
        )
        GleoBridge.respond(context, GleoBridge.GET_SAVED_FINES, GleoBridge.SUCCESS, message, data)
    }
}
