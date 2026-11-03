package dev.jamkkanman.app.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Pleos Vehicle SDK로 '멈추는 순간'의 신호를 받는다.
 * SDK 콜백은 다른 스레드에서 올 수 있어 모두 메인 스레드로 넘긴다.
 */
class VehicleWatcher(context: Context, private val events: Events) {

    interface Events {
        fun onGear(gear: VehicleGear?)
        fun onTurnSignal(value: TurnSignalValue?)
        fun onHazardLights(on: Boolean)
        fun onDoor(door: DoorValue, open: Boolean)
        fun onVehicleLog(message: String)
    }

    private val vehicle = Vehicle(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    private val aaosGear = CarGearSource(
        context.applicationContext,
        onGear = { g -> events.onGear(g) },
        log = { m -> events.onVehicleLog(m) },
        onHazard = { on -> events.onHazardLights(on) },
    )
    private var usingPleosGear = false
    private var started = false
    private var subscribed = false

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

    /**
     * 비상등: Pleos SDK에는 비상등 항목이 없어서, 계기판 경고등(Telltales)의 왼쪽·오른쪽 방향지시등 표시가
     * 동시에 켜져 있으면 비상등으로 본다. (Safety 도메인, pleos.car.permission.CAR_SAFETY)
     */
    private val telltaleListener = object : TelltaleInfoListener {
        override fun onTelltaleInfoUpdated(info: Telltales?) {
            if (info != null) main.post { onTelltales(info) }
        }

        override fun onFailed(e: Exception) {
            main.post { events.onVehicleLog("경고등 신호 오류: ${e.message}") }
        }
    }
    private var hazardFromTelltale = false
    private var lastBothOnAt = 0L
    private var lastTurnLamps = ""
    private var telltaleRegistered = false
    private var telltaleDenied = false

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

    /** 조회 권한까지 없으면 멈추고, 비상등은 방향지시등 값으로 추정하는 방식만 남긴다 */
    private fun onTelltaleError(e: Throwable) {
        if (telltaleDenied) return
        telltaleDenied = true
        main.removeCallbacks(telltalePoll)
        events.onVehicleLog("경고등 조회 실패(${e.message}) → 비상등은 방향지시등 값으로 추정")
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
        val gear = vehicle.getGear()
        val turnSignal = vehicle.getTurnSignal()
        val door = vehicle.getDoor()

        if (aaosGear.start()) {
            events.onVehicleLog("기어: Android Automotive 표준 속성 GEAR_SELECTION (Pleos SDK 권장 방식)")
        } else {
            usingPleosGear = true
            attempt("기어") {
                @Suppress("DEPRECATION")
                gear.getCurrentGearSelectionState({ g -> main.post { events.onGear(g) } }, { e -> main.post { events.onVehicleLog("기어 읽기 실패: ${e.message}") } })
                @Suppress("DEPRECATION")
                gear.registerGearSelection(gearListener)
            }
            events.onVehicleLog("기어: 표준 속성을 못 써서 Pleos 기어 API로 대신함")
        }
        attempt("방향지시등") {
            turnSignal.getCurrentTurnSignalState({ v -> main.post { events.onTurnSignal(v) } }, { e -> main.post { events.onVehicleLog("방향지시등 읽기 실패: ${e.message}") } })
            turnSignal.registerTurnSignalState(turnSignalListener)
        }
        for ((value, listener) in doorListeners) {
            attempt("문(${value.name})") { door.registerDoorPosition(DoorArea(value), listener) }
        }
        // 경고등 실시간 구독(registerTelltales)은 시스템 앱 전용 권한(PRIVATE_CAR_SAFETY)이 필요해서,
        // 3rd party 앱이 쓸 수 있는 조회(getTelltalesInfo, CAR_SAFETY)를 1초마다 부른다.
        try {
            vehicle.getSafety().registerTelltales(telltaleListener)
            telltaleRegistered = true
            events.onVehicleLog("경고등: 실시간 구독")
        } catch (t: Throwable) {
            events.onVehicleLog("경고등: 실시간 구독 불가 → 1초마다 조회")
            main.post(telltalePoll)
        }
        events.onVehicleLog("차량 신호 구독: 방향지시등 · 문 ${doors.size}개 · 경고등(비상등)")
    }

    private inline fun attempt(label: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            events.onVehicleLog("$label 구독 실패: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        aaosGear.stop()
        if (subscribed) {
            @Suppress("DEPRECATION")
            if (usingPleosGear) runCatching { vehicle.getGear().unregisterGearSelection(gearListener) }
            runCatching { vehicle.getTurnSignal().unregisterTurnSignalState(turnSignalListener) }
            for ((value, listener) in doorListeners) {
                runCatching { vehicle.getDoor().unregisterDoorPosition(DoorArea(value), listener) }
            }
            if (telltaleRegistered) runCatching { vehicle.getSafety().unregisterTelltales(telltaleListener) }
        }
        hazardFromTelltale = false
        lastTurnLamps = ""
        telltaleRegistered = false
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
