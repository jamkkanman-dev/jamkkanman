package dev.jamkkanman.app.car

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.car.VehicleGear as AaosGear

/**
 * Android Automotive 표준 차량 속성.
 * - 기어 레버 위치(GEAR_SELECTION): Pleos Vehicle SDK 2.3.2의 기어 API는 "Google GEAR_SELECTION 속성을 쓰라"며
 *   지원 중단(deprecated)되어, SDK 안내대로 표준 속성을 먼저 쓰고 안 되면 Pleos 기어 API로 대신한다(VehicleWatcher).
 * - 비상등(HAZARD_LIGHTS_STATE): Pleos Light API에는 비상등 항목이 없어 표준 속성으로 읽는다.
 *   권한이 없거나 지원하지 않는 차면 조용히 건너뛰고, 방향지시등 값으로 추정하는 방식이 남는다.
 */
class CarGearSource(
    private val context: Context,
    private val onGear: (VehicleGear?) -> Unit,
    private val log: (String) -> Unit,
    private val onHazard: ((Boolean) -> Unit)? = null,
) {
    private val main = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    private var hazardSubscribed = false

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val raw = value.value as? Int ?: return
            when (value.propertyId) {
                VehiclePropertyIds.GEAR_SELECTION -> main.post { onGear(toPleos(raw)) }
                VehiclePropertyIds.HAZARD_LIGHTS_STATE -> main.post { onHazard?.invoke(raw == LIGHT_ON) }
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            main.post { log("차량 속성 오류 (id=$propertyId, area=$areaId)") }
        }
    }

    /** 기어 구독에 성공하면 true (비상등 구독 결과는 로그로만 남긴다) */
    fun start(): Boolean {
        val connected = try {
            Car.createCar(context)
        } catch (t: Throwable) {
            null
        } ?: return false
        val cpm = connected.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        if (cpm == null) {
            runCatching { connected.disconnect() }
            return false
        }
        val gearOk = try {
            @Suppress("DEPRECATION")
            val ok = cpm.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            if (ok) runCatching { cpm.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0) }.getOrNull()?.let { onGear(toPleos(it)) }
            ok
        } catch (t: Throwable) {
            log("기어 속성 구독 실패: ${t.javaClass.simpleName}")
            false
        }
        if (onHazard != null) {
            hazardSubscribed = try {
                @Suppress("DEPRECATION")
                val ok = cpm.registerCallback(callback, VehiclePropertyIds.HAZARD_LIGHTS_STATE, CarPropertyManager.SENSOR_RATE_ONCHANGE)
                if (ok) runCatching { cpm.getIntProperty(VehiclePropertyIds.HAZARD_LIGHTS_STATE, 0) }.getOrNull()?.let { onHazard.invoke(it == LIGHT_ON) }
                log(if (ok) "비상등: Android Automotive 표준 속성 HAZARD_LIGHTS_STATE" else "비상등 표준 속성 없음 → 방향지시등 값으로 추정")
                ok
            } catch (t: Throwable) {
                log("비상등 표준 속성 사용 불가(${t.javaClass.simpleName}) → 방향지시등 값으로 추정")
                false
            }
        }
        if (gearOk || hazardSubscribed) {
            car = connected
            manager = cpm
        } else {
            runCatching { connected.disconnect() }
        }
        return gearOk
    }

    fun stop() {
        @Suppress("DEPRECATION")
        runCatching { manager?.unregisterCallback(callback) }
        runCatching { car?.disconnect() }
        manager = null
        car = null
        hazardSubscribed = false
    }

    private fun toPleos(raw: Int): VehicleGear = when (raw) {
        AaosGear.GEAR_PARK -> VehicleGear.GEAR_PARK
        AaosGear.GEAR_DRIVE -> VehicleGear.GEAR_DRIVE
        AaosGear.GEAR_REVERSE -> VehicleGear.GEAR_REVERSE
        AaosGear.GEAR_NEUTRAL -> VehicleGear.GEAR_NEUTRAL
        else -> VehicleGear.GEAR_UNKNOWN
    }

    private companion object {
        /** VehicleLightState.ON */
        const val LIGHT_ON = 1
    }
}
