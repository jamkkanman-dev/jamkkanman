package dev.jamkkanman.app.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatBeltBuckledStateListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Pleos Vehicle SDK로 '멈추는 순간'의 신호를 받는다.
 * - Pleos 가이드대로 각 신호는 check…Capabilities로 이 차가 지원하는지 먼저 확인하고, 지원하는 것만 구독한다.
 * - SDK 콜백은 다른 스레드에서 올 수 있어 모두 메인 스레드로 넘긴다.
 *
 * 신호와 권한 (모두 3rd party 앱용 권한만 쓴다)
 * - 기어: Android Automotive 표준 GEAR_SELECTION (CAR_POWERTRAIN), 안 되면 Pleos 기어 API (CAR_DRIVE)
 * - 차속: Android Automotive 표준 PERF_VEHICLE_SPEED (CAR_SPEED). Pleos SDK에는 차속 API가 없다
 * - 브레이크 페달 (CAR_DRIVE), 방향지시등 (CAR_LIGHTS), 문 (CAR_DOORS), 안전벨트 (CAR_SEATS)
 * - 계기판 경고등 조회 (CAR_SAFETY): 좌우 방향지시등 표시가 함께 켜지면 비상등으로 본다
 */
class VehicleWatcher(context: Context, private val events: Events) {

    interface Events {
        fun onGear(gear: VehicleGear?)
        fun onTurnSignal(value: TurnSignalValue?)
        fun onHazardLights(on: Boolean)
        fun onDoor(door: DoorValue, open: Boolean)
        /** seat: "운전석"·"뒷좌석 오른쪽" 같은 좌석 이름 */
        fun onSeatBelt(seat: String, buckled: Boolean)
        fun onBrake(pressed: Boolean)
        /** 차속 (km/h) */
        fun onSpeed(kph: Float)
        fun onVehicleLog(message: String)
    }

    private val vehicle = Vehicle(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private val aaosGear = CarGearSource(
        context.applicationContext,
        onGear = { g -> events.onGear(g) },
        log = { m -> events.onVehicleLog(m) },
        onSpeed = { kph -> events.onSpeed(kph) },
    )
    private var usingPleosGear = false
    private var started = false
    private var subscribed = false

    // 실제로 구독한 것만 기억했다가 release()에서 푼다
    private var turnSignalOn = false
    private var brakeOn = false
    private val doorsOn = mutableListOf<DoorValue>()
    private val beltsOn = mutableListOf<Pair<SeatArea, SeatBeltBuckledStateListener>>()

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) {
            main.post { events.onGear(gear) }
        }

        override fun onFailed(e: Exception) {
            main.post { events.onVehicleLog("기어 신호 오류: ${e.message}") }
        }
    }

    private val turnSignalListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) {
            main.post { events.onTurnSignal(state) }
        }

        override fun onFailed(e: Exception) {
            main.post { events.onVehicleLog("방향지시등 신호 오류: ${e.message}") }
        }
    }

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(pressed: Boolean?) {
            if (pressed != null) main.post { events.onBrake(pressed) }
        }

        override fun onFailed(e: Exception) {
            main.post { events.onVehicleLog("브레이크 신호 오류: ${e.message}") }
        }
    }

    private val doors = listOf(DoorValue.ROW_1_LEFT, DoorValue.ROW_1_RIGHT, DoorValue.ROW_2_LEFT, DoorValue.ROW_2_RIGHT)

    private val doorListeners: Map<DoorValue, DoorPositionListener> = doors.associateWith { door ->
        object : DoorPositionListener {
            override fun onPositionUpdated(pos: Int?) {
                main.post { events.onDoor(door, (pos ?: 0) > 0) }
            }

            override fun onFailed(e: Exception) {
                main.post { events.onVehicleLog("문 신호 오류(${door.name}): ${e.message}") }
            }
        }
    }

    // ------------------------------------------------------------------ 비상등 (계기판 경고등 조회)

    private var hazardFromTelltale = false
    private var lastBothOnAt = 0L
    private var lastTurnLamps = ""
    private var telltaleDenied = false

    /**
     * 경고등 실시간 구독(registerTelltales)과 지원 확인(checkTelltalesCapabilities)은 시스템 앱 전용 권한
     * (PRIVATE_CAR_SAFETY)이라 쓰지 않고, 3rd party 앱이 쓸 수 있는 조회(getTelltalesInfo, CAR_SAFETY)를 1초마다 부른다.
     * 한 번이라도 실패하면 멈추고, 비상등은 방향지시등 값으로 추정하는 방식만 남긴다.
     */
    private val telltalePoll = object : Runnable {
        override fun run() {
            if (!subscribed || telltaleDenied) return
            try {
                vehicle.getSafety().getTelltalesInfo(
                    { t -> main.post { onTelltales(t) } },
                    { e -> main.post { onTelltaleError(e) } },
                )
            } catch (t: Throwable) {
                onTelltaleError(t)
                return
            }
            main.postDelayed(this, TELLTALE_POLL_MS)
        }
    }

    private fun onTelltaleError(e: Throwable) {
        if (telltaleDenied) return
        telltaleDenied = true
        main.removeCallbacks(telltalePoll)
        events.onVehicleLog("비상등: 경고등 조회 불가(${e.javaClass.simpleName}) → 방향지시등 값으로 추정")
    }

    private fun onTelltales(info: Telltales) {
        val list = info.telltales
        fun lit(type: TelltaleType) = list.any { it.type == type && it.state != TelltaleState.OFF }
        val left = lit(TelltaleType.TURN_SIGNAL_LEFT)
        val right = lit(TelltaleType.TURN_SIGNAL_RIGHT)
        val lamps = "왼쪽 ${if (left) "켜짐" else "꺼짐"} · 오른쪽 ${if (right) "켜짐" else "꺼짐"}"
        if (lamps != lastTurnLamps) {
            lastTurnLamps = lamps
            events.onVehicleLog("경고등(방향지시등): $lamps")
        }
        if (left && right) {
            lastBothOnAt = SystemClock.elapsedRealtime()
            if (!hazardFromTelltale) {
                hazardFromTelltale = true
                events.onHazardLights(true)
            }
        } else if (hazardFromTelltale && !left && !right) {
            // 깜빡이는 사이에 잠깐 꺼진 것일 수 있어 조금 기다렸다가 다시 본다
            main.postDelayed({
                if (hazardFromTelltale && SystemClock.elapsedRealtime() - lastBothOnAt >= HAZARD_OFF_MS) {
                    hazardFromTelltale = false
                    events.onHazardLights(false)
                }
            }, HAZARD_OFF_MS + 100)
        }
    }

    // ------------------------------------------------------------------ 연결·구독

    fun start() {
        if (started) return
        started = true
        try {
            vehicle.initialize()
            events.onVehicleLog("차량 연결 요청")
        } catch (t: Throwable) {
            events.onVehicleLog("차량 연결 실패: ${t.message}")
            return
        }
        // 서비스 연결에 시간이 조금 걸려서 잠시 뒤 구독한다.
        main.postDelayed({ subscribe() }, 1_500)
    }

    private fun subscribe() {
        if (subscribed) return
        subscribed = true
        subscribeGear()
        subscribeTurnSignal()
        subscribeBrake()
        subscribeDoors()
        subscribeBelts()
        main.post(telltalePoll)
    }

    private fun subscribeGear() {
        if (aaosGear.start()) {
            events.onVehicleLog("기어: Android Automotive 표준 속성 GEAR_SELECTION (Pleos SDK 권장 방식)")
            return
        }
        usingPleosGear = true
        attempt("기어") {
            val gear = vehicle.getGear()
            @Suppress("DEPRECATION")
            gear.getCurrentGearSelectionState({ g -> main.post { events.onGear(g) } }, { e -> main.post { events.onVehicleLog("기어 읽기 실패: ${e.message}") } })
            @Suppress("DEPRECATION")
            gear.registerGearSelection(gearListener)
        }
        events.onVehicleLog("기어: 표준 속성을 못 써서 Pleos 기어 API로 대신함")
    }

    private fun subscribeTurnSignal() = capability(
        "방향지시등",
        check = { ok, fail -> vehicle.getTurnSignal().checkTurnSignalStateCapabilities({ ok() }, fail) },
    ) {
        val turnSignal = vehicle.getTurnSignal()
        turnSignal.getCurrentTurnSignalState({ v -> main.post { events.onTurnSignal(v) } }, { e -> main.post { events.onVehicleLog("방향지시등 읽기 실패: ${e.message}") } })
        turnSignal.registerTurnSignalState(turnSignalListener)
        turnSignalOn = true
    }

    /** 브레이크를 밟고 있는지: 기어 D에 브레이크만 밟고 내려 주는 정차를 안전벨트 신호와 함께 판단한다 */
    private fun subscribeBrake() = capability(
        "브레이크",
        check = { ok, fail -> vehicle.getBrake().checkBrakePedalStatusCapabilities({ ok() }, fail) },
    ) {
        val brake = vehicle.getBrake()
        brake.getCurrentBrakePedalStatus({ b: Boolean? -> if (b != null) main.post { events.onBrake(b) } }, { e -> main.post { events.onVehicleLog("브레이크 읽기 실패: ${e.message}") } })
        brake.registerBrakePedalStatus(brakeListener)
        brakeOn = true
    }

    private fun subscribeDoors() {
        val door = vehicle.getDoor()
        try {
            door.checkDoorPositionCapabilities({ _, areas: Map<DoorArea, *> ->
                val supported = areas.keys.flatMap { it.values.toList() }.toSet()
                main.post { registerDoors(doors.filter { it in supported }, "지원 확인") }
            }, { e -> main.post { registerDoors(doors, "지원 확인 실패(${e.javaClass.simpleName}), 기본 문으로 시도") } })
        } catch (t: Throwable) {
            registerDoors(doors, "지원 확인 불가(${t.javaClass.simpleName}), 기본 문으로 시도")
        }
    }

    private fun registerDoors(list: List<DoorValue>, how: String) {
        if (!subscribed) return
        val door = vehicle.getDoor()
        for (value in list) {
            if (value in doorsOn) continue
            val listener = doorListeners.getValue(value)
            attempt("문(${value.name})") {
                door.registerDoorPosition(DoorArea(value), listener)
                doorsOn += value
            }
        }
        events.onVehicleLog("문: $how → ${doorsOn.size}개 구독")
    }

    /**
     * 안전벨트: 채워져 있던 벨트가 풀리면 곧 내린다는 뜻이라 문이 열리기 몇 초 전에 확인할 수 있다.
     * (비상등은 3rd party 앱이 바로 받을 수 있는 신호가 없어, 이 신호로 '내려 주는 정차'를 더 일찍 잡는다)
     */
    private fun subscribeBelts() {
        val seat = vehicle.getSeat()
        val fallback = listOf(SeatValue.ROW_1_LEFT, SeatValue.ROW_1_RIGHT, SeatValue.ROW_2_LEFT, SeatValue.ROW_2_RIGHT).map { SeatArea(it) }
        try {
            seat.checkSeatBeltBuckledCapabilities({ _, areas: List<SeatArea> ->
                main.post { registerBelts(areas, "지원 확인") }
            }, { e -> main.post { registerBelts(fallback, "지원 확인 실패(${e.javaClass.simpleName}), 기본 좌석으로 시도") } })
        } catch (t: Throwable) {
            registerBelts(fallback, "지원 확인 불가(${t.javaClass.simpleName}), 기본 좌석으로 시도")
        }
    }

    private fun registerBelts(areas: List<SeatArea>, how: String) {
        if (!subscribed) return
        val seat = vehicle.getSeat()
        for (area in areas) {
            if (beltsOn.any { it.first == area }) continue
            val name = area.values.joinToString("·") { seatName(it) }
            val listener = object : SeatBeltBuckledStateListener {
                override fun onSeatBeltBuckledStateUpdated(buckled: Boolean?) {
                    if (buckled != null) main.post { events.onSeatBelt(name, buckled) }
                }

                override fun onFailed(e: Exception) {
                    main.post { events.onVehicleLog("안전벨트 신호 오류($name): ${e.message}") }
                }
            }
            attempt("안전벨트($name)") {
                seat.getCurrentSeatBeltBuckledState(area, { b: Boolean? -> if (b != null) main.post { events.onSeatBelt(name, b) } }, { })
                seat.registerSeatBeltBuckledState(area, listener)
                beltsOn += area to listener
            }
        }
        events.onVehicleLog("안전벨트: $how → ${beltsOn.size}개 좌석 구독")
    }

    private fun seatName(value: SeatValue): String = when (value) {
        SeatValue.ROW_1_LEFT -> "운전석"
        SeatValue.ROW_1_RIGHT -> "조수석"
        SeatValue.ROW_2_LEFT -> "뒷좌석 왼쪽"
        SeatValue.ROW_2_CENTER -> "뒷좌석 가운데"
        SeatValue.ROW_2_RIGHT -> "뒷좌석 오른쪽"
        else -> value.name
    }

    /**
     * 지원 확인(check…Capabilities)이 성공하면 구독하고, 지원하지 않으면 기록만 남긴다.
     * 확인 콜백이 다른 스레드에서 와도 구독은 메인 스레드에서 한다.
     */
    private fun capability(
        label: String,
        check: (ok: () -> Unit, fail: (Exception) -> Unit) -> Unit,
        subscribe: () -> Unit,
    ) {
        try {
            check(
                { main.post { if (subscribed) attempt(label) { subscribe(); events.onVehicleLog("$label: 지원 확인 → 구독") } } },
                { e -> main.post { events.onVehicleLog("$label: 이 차는 지원하지 않음(${e.javaClass.simpleName})") } },
            )
        } catch (t: Throwable) {
            events.onVehicleLog("$label: 지원 확인 실패(${t.javaClass.simpleName})")
        }
    }

    private inline fun attempt(label: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            events.onVehicleLog("$label 구독 실패: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    /** 실행 중 권한(차속)을 방금 허용했을 때 다시 구독한다 */
    fun onPermissionsChanged() {
        if (subscribed) aaosGear.retrySpeed()
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        aaosGear.stop()
        if (subscribed) {
            @Suppress("DEPRECATION")
            if (usingPleosGear) runCatching { vehicle.getGear().unregisterGearSelection(gearListener) }
            if (turnSignalOn) runCatching { vehicle.getTurnSignal().unregisterTurnSignalState(turnSignalListener) }
            if (brakeOn) runCatching { vehicle.getBrake().unregisterBrakePedalStatus(brakeListener) }
            for (value in doorsOn) {
                runCatching { vehicle.getDoor().unregisterDoorPosition(DoorArea(value), doorListeners.getValue(value)) }
            }
            for ((area, listener) in beltsOn) {
                runCatching { vehicle.getSeat().unregisterSeatBeltBuckledState(area, listener) }
            }
        }
        doorsOn.clear()
        beltsOn.clear()
        turnSignalOn = false
        brakeOn = false
        hazardFromTelltale = false
        lastTurnLamps = ""
        telltaleDenied = false
        runCatching { vehicle.release() }
        subscribed = false
        usingPleosGear = false
        started = false
    }

    private companion object {
        /** 좌우 표시가 이만큼 계속 꺼져 있어야 비상등이 꺼진 것으로 본다 (깜빡임 간격보다 길게) */
        const val HAZARD_OFF_MS = 1_500L
        /** 경고등 조회 간격 */
        const val TELLTALE_POLL_MS = 1_000L
    }
}
