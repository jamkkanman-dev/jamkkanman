package dev.jamkkanman.app.core

import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import android.os.SystemClock

/**
 * '멈추는 순간'을 판단하는 곳. 모든 호출은 메인 스레드에서 한다.
 *
 * 멈춤 신호: 기어 P / 비상등 / 문 열림 (Pleos Vehicle SDK)
 * - 같은 자리에서 신호가 여러 번 와도 안내는 한 번만 한다.
 * - 알림 뒤 금지구역 밖으로 옮겨 세우면 그 과태료를 '아낀 과태료'로 더한다.
 *
 * 도착 전 안내: 내비 목적지가 금지구역 안이면 미리 알려 주고, 원하면 세울 곳을 경유지로 넣는다.
 */
class StopEngine(
    private val checker: ZoneChecker,
    private val ledger: Ledger,
    private val voice: Voice,
    private val location: () -> Fix?,
    private val addWaypoint: (SafeSpot) -> Boolean,
    private val onChange: () -> Unit,
    private val log: (String) -> Unit,
) {
    private data class Session(val lat: Double, val lon: Double, var lastAtMs: Long)
    private data class Pending(val type: ZoneType, val lat: Double, val lon: Double, val atMs: Long)

    var gear: VehicleGear? = null
        private set
    var hazardOn = false
        private set
    private val openDoors = mutableSetOf<DoorValue>()
    val doorOpen: Boolean get() = openDoors.isNotEmpty()

    /** 화면에 보여 줄 마지막 확인 결과 */
    var lastResult: CheckResult? = null
        private set
    var lastReason: String? = null
        private set
    var lastSpoken: String? = null
        private set
    /** 방금 옮겨 세워 아낀 금액 (화면 강조용, 0이면 없음) */
    var lastCreditWon: Int = 0
        private set
    var destination: DestinationInfo? = null
        private set
    /** 마지막으로 자리를 확인한 시각 (벽시계 ms) */
    var lastCheckedAtMs: Long = 0
        private set

    private var session: Session? = null
    private var pending: Pending? = null
    private var turnSignalSeen = false
    private var hazardDirect = false
    private var announcedDestination: String? = null

    // ------------------------------------------------------------------ 차량 신호

    fun onGear(value: VehicleGear?) {
        val before = gear
        gear = value
        // 앱을 켰을 때 이미 P인 경우(처음 읽은 값)는 '멈추는 순간'이 아니라 안내하지 않는다
        if (before != null && value == VehicleGear.GEAR_PARK && before != VehicleGear.GEAR_PARK) stopMoment("기어 P")
        onChange()
    }

    /**
     * 비상등을 직접 알려 주는 신호 (Android Automotive 표준 속성 HAZARD_LIGHTS_STATE).
     * 이 신호를 한 번이라도 받으면 아래 방향지시등 추정은 쓰지 않는다.
     */
    fun onHazard(on: Boolean) {
        hazardDirect = true
        val before = hazardOn
        hazardOn = on
        if (on && !before) stopMoment("비상등")
        onChange()
    }

    /**
     * 비상등 표준 속성을 못 받는 차를 위한 예비 판단.
     * Pleos의 방향지시등 값은 끔/왼쪽/오른쪽 세 가지뿐이라 비상등(좌우 동시)은 null로 들어오는 것으로 보고,
     * 정상값을 한 번이라도 받은 뒤에 null이 오면 비상등으로 판단한다.
     */
    fun onTurnSignal(value: TurnSignalValue?) {
        log("방향지시등 값: ${value ?: "없음(null)"}")
        if (hazardDirect) return
        val hazard = value == null && turnSignalSeen
        if (value != null) turnSignalSeen = true
        val before = hazardOn
        hazardOn = hazard
        if (hazard && !before) stopMoment("비상등")
        onChange()
    }

    /** 문이 하나라도 새로 열리면 멈춤으로 본다. 기어 D에 브레이크만 밟고 아이를 내려 주는 경우도 잡는다. */
    fun onDoor(door: DoorValue, open: Boolean) {
        val anyBefore = openDoors.isNotEmpty()
        if (open) openDoors += door else openDoors -= door
        if (open && !anyBefore) stopMoment("문 열림")
        onChange()
    }

    // ------------------------------------------------------------------ 멈춤 판단

    fun stopMoment(reason: String) {
        val now = SystemClock.elapsedRealtime()
        lastReason = reason
        val fix = location()
        if (fix == null) {
            log("$reason: 위치를 몰라 확인하지 못함")
            onChange()
            return
        }

        val current = session
        if (current != null && now - current.lastAtMs < SESSION_MS &&
            Geo.distanceM(current.lat, current.lon, fix.lat, fix.lon) < SAME_SPOT_M
        ) {
            current.lastAtMs = now
            log("$reason: 같은 자리라 다시 알리지 않음")
            onChange()
            return
        }

        val result = checker.check(fix)
        lastResult = result
        lastCheckedAtMs = System.currentTimeMillis()
        session = Session(fix.lat, fix.lon, now)
        lastCreditWon = 0

        val moved = settlePending(result, fix, now)
        val top = result.top
        if (top != null) {
            val text = Speech.warning(top, result.spot, result.hour)
            speak(text)
            ledger.recordWarning()
            ledger.appendDemand("warn", top.zone.type, fix.lat, fix.lon)
            pending = Pending(top.zone.type, fix.lat, fix.lon, now)
            log("$reason: ${top.zone.type.title} (${Speech.metersShort(top.distanceM)}) → 안내")
        } else if (!result.covered) {
            log("$reason: 금지구역 데이터가 없는 지역이라 확인하지 않음")
        } else {
            if (moved != null) speak(Speech.moved(moved))
            log("$reason: 금지구역 아님${if (moved != null) " · 옮겨 세움 ${Speech.won(moved)}" else ""}")
        }
        onChange()
    }

    /** 앞선 알림 뒤 금지구역 밖으로 옮겨 세웠는지 확인한다. 옮겼으면 아낀 금액을 돌려준다. */
    private fun settlePending(result: CheckResult, fix: Fix, now: Long): Int? {
        val p = pending ?: return null
        pending = null
        if (now - p.atMs > PENDING_MS) return null
        val movedFar = Geo.distanceM(p.lat, p.lon, fix.lat, fix.lon) >= SAME_SPOT_M
        if (!movedFar || !result.covered || result.hits.any { it.certain }) return null
        val fine = p.type.fineAt(result.hour)
        ledger.recordMoved(fine)
        ledger.appendDemand("moved", p.type, p.lat, p.lon)
        lastCreditWon = fine
        return fine
    }

    // ------------------------------------------------------------------ 도착 전 안내

    fun onDestination(info: DestinationInfo) {
        destination = info
        if (info.remainDistance !in 1..ARRIVAL_NOTICE_M) return
        val key = "%.5f,%.5f".format(info.latitude, info.longitude)
        if (key == announcedDestination) return
        announcedDestination = key

        val top = checker.zonesNear(info.latitude, info.longitude, extraM = 10.0).firstOrNull()
        if (top == null) {
            log("도착 전 확인: ${info.name} 주변 금지구역 없음")
            return
        }
        val spot = checker.nearestSpot(info.latitude, info.longitude)
        val added = spot != null && ledger.autoWaypoint && addWaypoint(spot.spot)
        speak(Speech.beforeArrival(top, spot, added))
        log("도착 전 확인: ${info.name} → ${top.zone.type.title}${if (added) " · 경유지 추가" else ""}")
        onChange()
    }

    fun onArrived() {
        announcedDestination = null
    }

    // ------------------------------------------------------------------

    /** 시연용: 저장된 상태를 지우고 처음처럼 */
    fun resetSession() {
        session = null
        pending = null
        lastResult = null
        lastReason = null
        lastSpoken = null
        lastCreditWon = 0
        lastCheckedAtMs = 0
        announcedDestination = null
        onChange()
    }

    private fun speak(text: String) {
        lastSpoken = text
        voice.say(text)
    }

    companion object {
        /** 이 거리 안에서 다시 오는 멈춤 신호는 같은 정차로 본다 */
        const val SAME_SPOT_M = 6.0
        /** 같은 정차로 묶는 시간 */
        const val SESSION_MS = 10 * 60_000L
        /** 알림 뒤 이 시간 안에 옮겨 세우면 '아낀 과태료'로 인정 */
        const val PENDING_MS = 10 * 60_000L
        /** 목적지까지 이 거리(m) 안으로 들어오면 도착 전 안내 */
        const val ARRIVAL_NOTICE_M = 700
    }
}
