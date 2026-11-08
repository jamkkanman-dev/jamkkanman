package dev.jamkkanman.app.core

import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import android.os.SystemClock

/**
 * '멈추는 순간'을 판단하는 곳. 모든 호출은 메인 스레드에서 한다.
 *
 * 멈춤 신호: 기어 P / 안전벨트 풀림(멈춰 있을 때) / 문 열림 / 비상등 (Pleos Vehicle SDK)
 * - 같은 자리에서 신호가 여러 번 와도 안내는 한 번만 한다.
 * - 알림 뒤 금지구역 밖으로 옮겨 세우면 그 과태료를 '아낀 과태료'로 더한다.
 *
 * 도착 전 안내: 내비 목적지가 금지구역 안이면 미리 알려 주고, 세울 곳을 경유지로 넣을지 음성으로 묻는다
 * (음성 대답을 못 쓰는 환경이면 설정대로 바로 넣는다).
 *
 * @param ask 묻고 대답을 듣는다. 물을 수 없으면 false를 돌려준다 (그러면 묻지 않고 처리)
 */
class StopEngine(
    private val checker: ZoneChecker,
    private val ledger: Ledger,
    private val voice: Voice,
    private val location: () -> Fix?,
    private val addWaypoint: (SafeSpot) -> Boolean,
    private val onChange: () -> Unit,
    private val log: (String) -> Unit,
    private val ask: ((question: String, onAnswer: (Boolean?) -> Unit) -> Boolean)? = null,
) {
    private data class Session(val lat: Double, val lon: Double, var lastAtMs: Long)

    /** 도착 전 안내의 진행 상태 (화면 카드에 쓴다) */
    enum class ArrivalState { ASKING, ADDED, DECLINED, NO_ANSWER, INFORMED }

    data class Arrival(val place: String, val hit: ZoneHit, val spot: SpotHint?, val state: ArrivalState, val atMs: Long)
    private data class Pending(val type: ZoneType, val lat: Double, val lon: Double, val atMs: Long)

    var gear: VehicleGear? = null
        private set
    var hazardOn = false
        private set
    private val openDoors = mutableSetOf<DoorValue>()
    val doorOpen: Boolean get() = openDoors.isNotEmpty()

    /** 좌석별 안전벨트 (true = 채움). 빈자리는 처음부터 풀려 있으니 '채움 → 풀림'으로 바뀔 때만 본다 */
    private val belts = mutableMapOf<String, Boolean>()
    /** 채워져 있다가 풀린 좌석 */
    private val releasedSeats = mutableSetOf<String>()
    private var releasedAtMs = 0L
    /** 안전벨트 신호를 한 번이라도 받았는지 */
    val beltKnown: Boolean get() = belts.isNotEmpty()
    val beltReleased: Boolean get() = releasedSeats.isNotEmpty()
    /** 브레이크 페달 (null = 신호 없음) */
    var brakeOn: Boolean? = null
        private set
    /** 차속 km/h (null = 신호 없음, Android Automotive 표준 PERF_VEHICLE_SPEED) */
    var speedKph: Float? = null
        private set

    /** 움직이는 중인지: 차속을 알면 차속으로, 모르면 기어 D·R로 본다 (주행 중 화면 가림에 쓴다) */
    val moving: Boolean
        get() = speedKph?.let { it >= MOVING_KPH } ?: (gear == VehicleGear.GEAR_DRIVE || gear == VehicleGear.GEAR_REVERSE)

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
    /** 마지막 도착 전 안내. 새로 멈춰 자리를 확인하면 지운다 */
    var arrival: Arrival? = null
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
     * 비상등을 직접 알려 주는 신호 (계기판 경고등의 좌우 방향지시등 표시).
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

    /**
     * 안전벨트가 풀리면 곧 내린다는 뜻이라, 문이 열리기 몇 초 전에 확인할 수 있다.
     * 달리는 중에 푼 것과 구별하려고 멈춰 있을 때만 정차로 본다 (차속 3km/h 미만, 차속을 모르면 기어 P·N·브레이크·문).
     * 풀린 뒤 15초 안에 차가 멈추거나 브레이크를 밟아도 정차로 본다.
     */
    fun onSeatBelt(seat: String, buckled: Boolean) {
        val before = belts.put(seat, buckled)
        if (buckled) {
            releasedSeats -= seat
        } else if (before == true) {
            releasedSeats += seat
            releasedAtMs = SystemClock.elapsedRealtime()
            if (stoppedNow()) stopMoment(BELT_REASON)
            else log("안전벨트 풀림($seat): 아직 움직이는 중일 수 있어 브레이크·기어를 기다림")
        }
        onChange()
    }

    fun onSpeed(kph: Float) {
        val wasMoving = moving
        val wasStopped = speedKph?.let { it < STOP_KPH }
        speedKph = kph
        val stopped = kph < STOP_KPH
        if (stopped && wasStopped == false && beltReleasedRecently()) stopMoment(BELT_REASON)
        // 초당 몇 번씩 오므로 상태가 바뀔 때만 화면을 다시 그린다
        if (moving != wasMoving || stopped != wasStopped) onChange()
    }

    /** 시연용: 뒷좌석 안전벨트를 채웠다가 푸는 신호를 넣는다 (에뮬레이터 Virtual Console에는 안전벨트 조작이 없다) */
    fun demoBeltRelease(seat: String = "뒷좌석 오른쪽") {
        onSeatBelt(seat, true)
        onSeatBelt(seat, false)
    }

    fun onBrake(pressed: Boolean) {
        val before = brakeOn
        brakeOn = pressed
        if (pressed && before != true && beltReleasedRecently()) stopMoment(BELT_REASON)
        onChange()
    }

    /** 지금 멈춰 있다고 볼 수 있는지: 차속을 알면 차속으로, 모르면 기어·브레이크·문으로 */
    private fun stoppedNow(): Boolean {
        val speed = speedKph
        return when {
            gear == VehicleGear.GEAR_PARK || gear == VehicleGear.GEAR_NEUTRAL -> true
            speed != null -> speed < STOP_KPH
            else -> stoppedWithoutSpeed()
        }
    }

    private fun stoppedWithoutSpeed(): Boolean = when {
        brakeOn == true || doorOpen -> true
        // 기어도 브레이크도 모르면 확인하는 쪽을 고른다 (안내는 금지구역일 때만 나간다)
        brakeOn == null && (gear == null || gear == VehicleGear.GEAR_UNKNOWN) -> true
        else -> false
    }

    private fun beltReleasedRecently(): Boolean =
        releasedSeats.isNotEmpty() && SystemClock.elapsedRealtime() - releasedAtMs < BELT_WAIT_MS

    // ------------------------------------------------------------------ 멈춤 판단

    fun stopMoment(reason: String) {
        val now = SystemClock.elapsedRealtime()
        lastReason = reason
        arrival = null
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
            val text = Speech.warning(top, result.spot, result.hour, result.risk, belt = reason == BELT_REASON)
            speak(text)
            ledger.recordWarning()
            ledger.appendDemand("warn", top.zone.type, fix.lat, fix.lon)
            pending = Pending(top.zone.type, fix.lat, fix.lon, now)
            log("$reason: ${top.zone.type.title} (${Speech.metersShort(top.distanceM)}) → 안내")
        } else if (!result.covered) {
            log("$reason: 금지구역 데이터가 없는 지역이라 확인하지 않음")
        } else {
            val risk = result.risk
            when {
                moved != null -> speak(Speech.moved(moved))
                risk != null && risk.busy -> speak(Speech.busyHere(risk))
            }
            log("$reason: 금지구역 아님${if (moved != null) " · 옮겨 세움 ${Speech.won(moved)}" else ""}" +
                (risk?.let { r -> Speech.riskLine(r)?.let { " · $it" } } ?: ""))
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
        if (spot != null && ledger.autoWaypoint) {
            val question = Speech.arrivalQuestion(top, spot)
            val asked = ask?.invoke(question) { answer -> onArrivalAnswer(info.name, spot, answer) } == true
            if (asked) {
                lastSpoken = question
                arrival = Arrival(info.name, top, spot, ArrivalState.ASKING, SystemClock.elapsedRealtime())
                log("도착 전 확인: ${info.name} → ${top.zone.type.title} · 경유지 물어봄")
                onChange()
                return
            }
        }
        val added = spot != null && ledger.autoWaypoint && addWaypoint(spot.spot)
        arrival = Arrival(info.name, top, spot, if (added) ArrivalState.ADDED else ArrivalState.INFORMED, SystemClock.elapsedRealtime())
        speak(Speech.beforeArrival(top, spot, added))
        log("도착 전 확인: ${info.name} → ${top.zone.type.title}${if (added) " · 경유지 추가" else ""}")
        onChange()
    }

    private fun onArrivalAnswer(place: String, spot: SpotHint, answer: Boolean?) {
        val added = answer == true && addWaypoint(spot.spot)
        arrival = arrival?.copy(
            state = when {
                added -> ArrivalState.ADDED
                answer == false -> ArrivalState.DECLINED
                else -> ArrivalState.NO_ANSWER
            },
            atMs = SystemClock.elapsedRealtime(),
        )
        speak(Speech.arrivalAnswer(spot, answer, added))
        log("도착 전 확인: $place → 대답 ${when (answer) { true -> "응"; false -> "아니"; null -> "없음" }}${if (added) " · 경유지 추가" else ""}")
        onChange()
    }

    fun onArrived() {
        announcedDestination = null
    }

    // ------------------------------------------------------------------

    /**
     * 운전자가 직접 물었을 때(Gleo AI 음성 질문): 같은 자리여도 다시 확인하고, 금지구역이 아니어도 말로 답한다.
     * 위치를 몰라 확인하지 못했으면 null.
     */
    fun checkNow(reason: String): CheckResult? {
        val before = lastCheckedAtMs
        session = null
        stopMoment(reason)
        if (lastCheckedAtMs == before) return null
        val result = lastResult ?: return null
        when {
            !result.covered -> speak("이 지역은 아직 금지구역 데이터가 없어서 확인할 수 없어요.")
            // 단속이 잦은 곳이면 stopMoment가 이미 그 안내를 했다
            result.top == null && lastCreditWon == 0 && result.risk?.busy != true ->
                speak("주변에 주정차 금지구역이 없어요.")
        }
        return result
    }

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
        arrival = null
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
        /** 안전벨트가 풀린 뒤 이 시간 안에 브레이크를 밟으면 내려 주는 정차로 본다 */
        const val BELT_WAIT_MS = 15_000L
        const val BELT_REASON = "안전벨트 풀림"
        /** 이보다 느리면 멈춘 것으로 본다 (km/h) */
        const val STOP_KPH = 3f
        /** 이보다 빠르면 주행 중으로 보고 화면을 가린다 (km/h) */
        const val MOVING_KPH = 5f
    }
}
