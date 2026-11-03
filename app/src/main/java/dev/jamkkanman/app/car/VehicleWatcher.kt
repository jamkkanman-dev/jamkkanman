package dev.jamkkanman.app.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.content.Context
import android.os.Handler
import android.os.Looper

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
        events.onVehicleLog("차량 신호 구독: 방향지시등 · 문 ${doors.size}개")
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
        }
        runCatching { vehicle.release() }
        subscribed = false
        usingPleosGear = false
        started = false
    }
}
