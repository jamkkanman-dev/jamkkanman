package dev.jamkkanman.probe

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyConfig
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager

/**
 * Android Automotive 표준 차량 속성(CarPropertyManager)을 읽고 구독한다.
 * 속성마다 지원 여부, 권한, 업데이트 속도를 기록한다.
 */
class CarSignals(
    private val context: Context,
    private val report: Report,
    private val onValue: (propertyId: Int, value: Any?, timestampMs: Long) -> Unit,
) {
    data class Prop(val id: Int, val label: String, val permission: String)

    val props: List<Prop> = listOfNotNull(
        Prop(VehiclePropertyIds.PERF_VEHICLE_SPEED, "차속 PERF_VEHICLE_SPEED", "android.car.permission.CAR_SPEED"),
        Prop(VehiclePropertyIds.GEAR_SELECTION, "기어 레버 GEAR_SELECTION", "android.car.permission.CAR_POWERTRAIN"),
        Prop(VehiclePropertyIds.CURRENT_GEAR, "실제 기어 CURRENT_GEAR", "android.car.permission.CAR_POWERTRAIN"),
        Prop(VehiclePropertyIds.PARKING_BRAKE_ON, "주차 브레이크 PARKING_BRAKE_ON", "android.car.permission.CAR_POWERTRAIN"),
        Prop(VehiclePropertyIds.IGNITION_STATE, "시동 IGNITION_STATE", "android.car.permission.CAR_POWERTRAIN"),
        // 페달 속성은 Android 14에 추가돼 SDK 버전에 따라 상수가 없을 수 있어, 기기에서 이름으로 찾는다
        runtimeProp("ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE", "가속 페달 % ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE", "android.car.permission.READ_CAR_PEDALS"),
        runtimeProp("BRAKE_PEDAL_COMPRESSION_PERCENTAGE", "브레이크 페달 % BRAKE_PEDAL_COMPRESSION_PERCENTAGE", "android.car.permission.READ_CAR_PEDALS"),
        Prop(VehiclePropertyIds.HEADLIGHTS_STATE, "전조등 HEADLIGHTS_STATE", "android.car.permission.CAR_EXTERIOR_LIGHTS"),
        Prop(VehiclePropertyIds.NIGHT_MODE, "야간 모드 NIGHT_MODE", "android.car.permission.CAR_EXTERIOR_ENVIRONMENT"),
        Prop(VehiclePropertyIds.PERF_ODOMETER, "누적 주행거리 PERF_ODOMETER", "android.car.permission.CAR_MILEAGE"),
    )

    private fun runtimeProp(fieldName: String, label: String, permission: String): Prop? = try {
        Prop(VehiclePropertyIds::class.java.getField(fieldName).getInt(null), label, permission)
    } catch (_: Throwable) {
        report.log("$label: 이 기기의 차량 라이브러리에 정의되지 않음")
        null
    }

    /** 속성별 수신 횟수(업데이트 속도 계산용) */
    val eventCounts = mutableMapOf<Int, Int>()

    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    private val subscribed = mutableListOf<Int>()

    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            synchronized(eventCounts) {
                eventCounts[value.propertyId] = (eventCounts[value.propertyId] ?: 0) + 1
            }
            onValue(value.propertyId, value.value, value.timestamp / 1_000_000L)
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            report.log("속성 오류 이벤트: ${labelOf(propertyId)} (area=$areaId)")
        }
    }

    fun start() {
        stop()
        val connected = try {
            Car.createCar(context)
        } catch (t: Throwable) {
            report.log("차량 서비스 연결 실패: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        if (connected == null) {
            report.log("차량 서비스(Car)에 연결하지 못했어요. Pleos 에뮬레이터에서 실행 중인지 확인해 주세요.")
            return
        }
        car = connected
        val cpm = connected.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        if (cpm == null) {
            report.log("CarPropertyManager를 가져오지 못했어요.")
            return
        }
        manager = cpm
        report.log("── Android Automotive 표준 차량 속성 ──")

        for (prop in props) {
            val granted = context.checkSelfPermission(prop.permission) == PackageManager.PERMISSION_GRANTED
            val config: CarPropertyConfig<*>? = try {
                cpm.getCarPropertyConfig(prop.id)
            } catch (t: Throwable) {
                report.log("${prop.label}: 설정 조회 오류 ${t.javaClass.simpleName}: ${t.message}")
                null
            }
            if (config == null) {
                report.log("${prop.label}: ✗ 사용 불가 (권한 ${if (granted) "있음" else "없음"} — 미지원이거나 권한 부족)")
                continue
            }
            val areaId = config.areaIds.firstOrNull() ?: 0
            val current = try {
                cpm.getProperty<Any>(prop.id, areaId).value
            } catch (t: Throwable) {
                "읽기 실패(${t.javaClass.simpleName})"
            }
            val mode = when (config.changeMode) {
                CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS -> "연속"
                CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_ONCHANGE -> "변경 시"
                else -> "고정"
            }
            report.log(
                "${prop.label}: ✓ 사용 가능 | 권한 ${if (granted) "있음" else "없음"} | $mode | " +
                    "최대 ${config.maxSampleRate}Hz | 현재값 ${format(prop.id, current)}"
            )
            val rate = if (config.changeMode == CarPropertyConfig.VEHICLE_PROPERTY_CHANGE_MODE_CONTINUOUS) {
                config.maxSampleRate.coerceAtMost(CarPropertyManager.SENSOR_RATE_FASTEST)
            } else {
                CarPropertyManager.SENSOR_RATE_ONCHANGE
            }
            val ok = try {
                @Suppress("DEPRECATION")
                cpm.registerCallback(callback, prop.id, rate)
            } catch (t: Throwable) {
                report.log("${prop.label}: 구독 실패 ${t.javaClass.simpleName}: ${t.message}")
                false
            }
            if (ok) subscribed += prop.id
        }
        report.log("구독 중인 속성 ${subscribed.size}개")
    }

    fun stop() {
        val cpm = manager
        if (cpm != null && subscribed.isNotEmpty()) {
            try {
                @Suppress("DEPRECATION")
                cpm.unregisterCallback(callback)
            } catch (_: Throwable) {
            }
        }
        subscribed.clear()
        manager = null
        try {
            car?.disconnect()
        } catch (_: Throwable) {
        }
        car = null
    }

    fun labelOf(id: Int): String = props.firstOrNull { it.id == id }?.label ?: "속성($id)"

    companion object {
        fun format(id: Int, value: Any?): String = when {
            value == null -> "없음"
            id == VehiclePropertyIds.PERF_VEHICLE_SPEED && value is Float -> "%.1f km/h".format(value * 3.6f)
            (id == VehiclePropertyIds.GEAR_SELECTION || id == VehiclePropertyIds.CURRENT_GEAR) && value is Int -> Gears.name(value)
            id == VehiclePropertyIds.HEADLIGHTS_STATE && value is Int -> when (value) {
                0 -> "꺼짐"; 1 -> "켜짐"; 2 -> "주간등"; else -> value.toString()
            }
            id == VehiclePropertyIds.IGNITION_STATE && value is Int -> when (value) {
                1 -> "LOCK"; 2 -> "OFF"; 3 -> "ACC"; 4 -> "ON"; 5 -> "START"; else -> "모름($value)"
            }
            value is Boolean -> if (value) "켜짐" else "꺼짐"
            else -> value.toString()
        }
    }
}
