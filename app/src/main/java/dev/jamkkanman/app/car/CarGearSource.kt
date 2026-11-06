package dev.jamkkanman.app.car

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.car.VehicleGear as AaosGear

/**
 * Android Automotive 표준 차량 속성
 * - 기어 레버 위치(GEAR_SELECTION, 권한 CAR_POWERTRAIN): Pleos Vehicle SDK 2.3.2의 기어 API는 "Google GEAR_SELECTION 속성을 쓰라"며
 *   지원 중단(deprecated)되어, SDK 안내대로 표준 속성을 먼저 쓰고 안 되면 Pleos 기어 API로 대신한다(VehicleWatcher).
 * - 차속(PERF_VEHICLE_SPEED, 실행 중 허용 권한 CAR_SPEED): Pleos SDK에는 차속 API가 없어 표준 속성으로 읽는다.
 *   멈췄는지 판단과 주행 중 화면 가림에 쓴다. 권한을 나중에 허용하면 retrySpeed()로 다시 구독한다.
 *
 * 표준 비상등 속성(HAZARD_LIGHTS_STATE)은 시스템 앱 전용 권한(CAR_EXTERIOR_LIGHTS)이 필요해 쓰지 않는다.
 */
class CarGearSource(
    private val context: Context,
    private val onGear: (VehicleGear?) -> Unit,
    private val log: (String) -> Unit,
    private val onSpeed: ((Float) -> Unit)? = null,
) {
    private val main = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    private var speedOn = false

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            when (value.propertyId) {
                VehiclePropertyIds.GEAR_SELECTION -> (value.value as? Int)?.let { raw -> main.post { onGear(toPleos(raw)) } }
                // 차속은 m/s로 온다
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> (value.value as? Float)?.let { mps -> main.post { onSpeed?.invoke(mps * 3.6f) } }
            }
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            main.post { log("차량 속성 오류 (id=$propertyId, area=$areaId)") }
        }
    }

    /** 기어 구독에 성공하면 true (차속 구독 결과는 기록으로만 남긴다) */
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
        car = connected
        manager = cpm
        val ok = try {
            @Suppress("DEPRECATION")
            val registered = cpm.registerCallback(callback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
            if (registered) runCatching { cpm.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0) }.getOrNull()?.let { onGear(toPleos(it)) }
            registered
        } catch (t: Throwable) {
            log("기어 속성 구독 실패: ${t.javaClass.simpleName}")
            false
        }
        retrySpeed()
        return ok
    }

    /** 차속을 아직 못 받고 있으면 다시 구독한다 (실행 중 권한을 방금 허용한 경우) */
    fun retrySpeed() {
        val cpm = manager ?: return
        if (onSpeed == null || speedOn) return
        if (context.checkSelfPermission(PERMISSION_SPEED) != PackageManager.PERMISSION_GRANTED) {
            log("차속: 권한을 허용하면 차속으로 정차를 판단해요 (그전에는 기어·브레이크로 판단)")
            return
        }
        speedOn = try {
            @Suppress("DEPRECATION")
            cpm.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, SPEED_RATE_HZ)
        } catch (t: Throwable) {
            false
        }
        log(if (speedOn) "차속: Android Automotive 표준 속성 PERF_VEHICLE_SPEED" else "차속 속성을 못 씀 → 기어·브레이크로 정차 판단")
    }

    fun stop() {
        @Suppress("DEPRECATION")
        runCatching { manager?.unregisterCallback(callback) }
        runCatching { car?.disconnect() }
        manager = null
        car = null
        speedOn = false
    }

    private fun toPleos(raw: Int): VehicleGear = when (raw) {
        AaosGear.GEAR_PARK -> VehicleGear.GEAR_PARK
        AaosGear.GEAR_DRIVE -> VehicleGear.GEAR_DRIVE
        AaosGear.GEAR_REVERSE -> VehicleGear.GEAR_REVERSE
        AaosGear.GEAR_NEUTRAL -> VehicleGear.GEAR_NEUTRAL
        else -> VehicleGear.GEAR_UNKNOWN
    }

    companion object {
        /** 차속 권한 (Android Automotive, 실행 중 허용) */
        const val PERMISSION_SPEED = "android.car.permission.CAR_SPEED"
        /** 차속 갱신 빈도 (초당 2번이면 멈춤 판단에 충분) */
        private const val SPEED_RATE_HZ = 2f
    }
}
