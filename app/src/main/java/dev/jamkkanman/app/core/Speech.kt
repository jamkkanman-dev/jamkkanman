package dev.jamkkanman.app.core

import kotlin.math.roundToInt

/** 음성·화면 문장. 운전 중에 듣는 말이라 짧게 쓴다. */
object Speech {

    fun won(amount: Int): String =
        if (amount % 10_000 == 0) "${amount / 10_000}만 원" else "%,d원".format(amount)

    /** 음성용 거리: 화면(metersShort)과 같은 숫자로 읽는다 (화면 8m인데 '10미터'라고 하지 않게) */
    fun meters(d: Double): String = "${d.roundToInt().coerceAtLeast(1)}미터"

    /** 화면용 거리 */
    fun metersShort(d: Double): String = "${d.roundToInt().coerceAtLeast(1)}m"

    /** 음성으로 읽을 이름: 괄호 안 설명은 빼고 읽는다 */
    fun spokenName(name: String): String = name.replace(Regex("\\s*\\([^)]*\\)"), "").trim()

    /**
     * 정차 순간 안내. [belt]는 안전벨트가 풀려서 멈춘 경우: 곧 내리려는 순간이라 문 열기 전에 확인하게 하고,
     * 벨트를 푼 채로 차를 움직이지 않도록 옮길 때는 벨트를 다시 매라고 말한다.
     */
    fun warning(hit: ZoneHit, spot: SpotHint?, hour: Int, risk: Risk? = null, belt: Boolean = false): String {
        val type = hit.zone.type
        val sb = StringBuilder()
        if (belt) sb.append("문 열기 전에 확인해 주세요. ")
        sb.append(if (hit.certain) "${type.spoken}이에요. " else "${type.spoken}일 수 있어요. ")
        sb.append("${type.risk}이고, 과태료는 ${won(type.fineAt(hour))}${type.fineNote?.let { ", $it" } ?: ""}이에요. ")
        if (risk?.cctv != null) sb.append("단속 카메라도 있는 곳이에요. ")
        if (hit.certain && hit.exitM in 0.5..12.0) {
            sb.append(
                if (belt) "옮기려면 안전벨트를 다시 맨 뒤 약 ${meters(hit.exitM + 1.0)} 옮겨 주세요. "
                else "약 ${meters(hit.exitM + 1.0)}만 옮기면 범위 밖이에요. "
            )
        }
        if (spot != null) {
            sb.append("${spokenName(spot.spot.name)}까지 ${meters(spot.distanceM)}예요.")
        }
        return sb.toString().trim()
    }

    fun moved(fine: Int): String = "금지구역 밖으로 옮겼어요. 과태료 ${won(fine)} 아꼈어요."

    /** 금지구역은 아니지만 단속 카메라가 있거나 단속이 잦은 곳 (노란 선 같은 지정 금지구간은 데이터에 없다) */
    fun busyHere(risk: Risk): String {
        val what = if (risk.cctv != null) "단속 카메라가 있는 곳" else "단속이 잦은 곳"
        return "주정차 금지구역은 아니지만 ${what}이에요. 노란 선이 있는지 확인해 주세요."
    }

    /** 화면용 한 줄: "단속 CCTV 34m · 2025년 10~12월 이 근처 단속 158건" */
    fun riskLine(risk: Risk): String? {
        val parts = mutableListOf<String>()
        if (risk.cctv != null) parts += "단속 CCTV ${metersShort(risk.cctvM)}"
        if (risk.recent > 0) parts += "${risk.period.ifBlank { "최근" }} 이 근처 단속 ${"%,d".format(risk.recent)}건"
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    fun beforeArrival(hit: ZoneHit, spot: SpotHint?, waypointAdded: Boolean): String {
        val sb = StringBuilder("목적지 앞은 ${hit.zone.type.spoken}이에요. ")
        when {
            spot != null && waypointAdded -> sb.append("${josa(spokenName(spot.spot.name), "을", "를")} 경유지로 넣었어요.")
            spot != null -> sb.append("${josa(spokenName(spot.spot.name), "이", "가")} 목적지에서 ${meters(spot.distanceM)} 거리에 있어요.")
            else -> sb.append("조금 떨어진 곳에 세워 주세요.")
        }
        return sb.toString()
    }

    /** 도착 전, 대답을 들을 수 있을 때: 경유지로 넣을지 묻는다 */
    fun arrivalQuestion(hit: ZoneHit, spot: SpotHint): String =
        "목적지 앞은 ${hit.zone.type.spoken}이에요. ${josa(spokenName(spot.spot.name), "을", "를")} 경유지로 넣을까요?"

    /** 물음에 대한 대답 뒤에 하는 말 */
    fun arrivalAnswer(spot: SpotHint, answer: Boolean?, added: Boolean): String = when {
        answer == true && added -> "${josa(spokenName(spot.spot.name), "을", "를")} 경유지로 넣었어요."
        answer == true -> "경유지를 넣지 못했어요. 화면에서 여기로 안내를 눌러 주세요."
        answer == false -> "알겠어요."
        else -> "대답을 듣지 못해서 경유지는 넣지 않았어요."
    }

    /** 받침 유무에 맞춰 조사를 붙인다. 한글이 아니면 받침 없는 쪽을 쓴다. */
    fun josa(word: String, withBatchim: String, withoutBatchim: String): String {
        val last = word.trimEnd().lastOrNull() ?: return word + withoutBatchim
        val hasBatchim = last in '가'..'힣' && (last - '가') % 28 != 0
        return word + if (hasBatchim) withBatchim else withoutBatchim
    }
}
