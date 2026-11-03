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
 * 기어 레버 위치(GEAR_SELECTION).
 * Pleos Vehicle SDK 2.3.2의 기어 API는 "Google GEAR_SELECTION 속성을 쓰라"며 지원 중단(deprecated)되어,
 * SDK 안내대로 Android Automotive 표준 속성을 먼저 쓰고, 안 되면 Pleos 기어 API로 대신한다(VehicleWatcher).
 */
class CarGearSource(
    private val context: Context,
    private val onGear: (VehicleGear?) -> Unit,
    private val log: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var manager: CarPropertyManager? = null

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val raw = value.value as? Int ?: return
            main.post { onGear(toPleos(raw)) }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            main.post { log("기어 속성 오류 (area=$areaId)") }
        }
    }

    /** 구독에 성공하면 true */
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
        return try {
            @Suppress("DEPRECATION")
            val ok = cpm.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            if (ok) {
                car = connected
                manager = cpm
                runCatching { cpm.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0) }.getOrNull()?.let { onGear(toPleos(it)) }
            } else {
                runCatching { connected.disconnect() }
            }
            ok
        } catch (t: Throwable) {
            log("기어 속성 구독 실패: ${t.javaClass.simpleName}")
            runCatching { connected.disconnect() }
            false
        }
    }

    fun stop() {
        @Suppress("DEPRECATION")
        runCatching { manager?.unregisterCallback(callback) }
        runCatching { car?.disconnect() }
        manager = null
        car = null
    }

    private fun toPleos(raw: Int): VehicleGear = when (raw) {
        AaosGear.GEAR_PARK -> VehicleGear.GEAR_PARK
        AaosGear.GEAR_DRIVE -> VehicleGear.GEAR_DRIVE
        AaosGear.GEAR_REVERSE -> VehicleGear.GEAR_REVERSE
        AaosGear.GEAR_NEUTRAL -> VehicleGear.GEAR_NEUTRAL
        else -> VehicleGear.GEAR_UNKNOWN
    }
}
