package dev.jamkkanman.app.car

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.jamkkanman.app.core.Fix

/**
 * 위치 출처를 한곳에 모은다.
 * 우선순위: 시연 위치 > Pleos 정밀 위치(FusedLocation: GPS·차량 센서 결합) > 내비(Pleos NaviHelper) > 기기 GPS
 * 앞 순위 위치가 10초 넘게 안 오면 다음 순위를 쓴다.
 *
 * 내비 위치는 도로 가운데에 맞춘 값이라, 길가에 선 차의 자리는 정밀 위치가 더 가깝다.
 * FusedLocation의 confidence 값은 단위가 문서에 없어 오차로 쓰지 않고, 일반 차량 GPS 오차(5m)로 본다.
 */
class LocationHub(private val context: Context, private val log: (String) -> Unit = {}) {

    var demo: Fix? = null
        private set
    private var fused: Fix? = null
    private var fusedClient: FusedLocation? = null
    private var fusedLogged = false
    private val main = Handler(Looper.getMainLooper())
    private var navi: Fix? = null
    private var device: Fix? = null
    private var listening = false

    private val deviceListener = LocationListener { location -> device = location.toFix() }

    fun current(): Fix? {
        demo?.let { return it }
        val now = SystemClock.elapsedRealtime()
        fused?.takeIf { now - it.atMs < FRESH_MS }?.let { return it }
        navi?.takeIf { now - it.atMs < FRESH_MS }?.let { return it }
        device?.takeIf { now - it.atMs < FRESH_MS }?.let { return it }
        return fused ?: navi ?: device
    }

    private val fusedListener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            location ?: return
            main.post { onFused(location) }
        }

        override fun onError(exception: Exception) {
            main.post { log("정밀 위치 오류: ${exception.javaClass.simpleName} ${exception.message.orEmpty()}") }
        }
    }

    private fun onFused(location: LocationData) {
        if (location.latitude == 0.0 && location.longitude == 0.0) return
        fused = Fix(location.latitude, location.longitude, FUSED_ACCURACY_M, "정밀 위치", SystemClock.elapsedRealtime())
        if (!fusedLogged) {
            fusedLogged = true
            log("정밀 위치: 첫 위치 받음 (confidence ${location.confidence})")
        }
    }

    /** Pleos FusedLocation 구독 (지원하지 않는 차면 기록만 남기고 내비·GPS 위치를 쓴다) */
    fun startFused() {
        if (fusedClient != null) return
        try {
            val client = FusedLocation(context.applicationContext)
            if (!client.isSupported()) {
                log("정밀 위치: 이 차는 지원하지 않음 → 내비·GPS 위치 사용")
                return
            }
            client.initialize()
            fusedClient = client
            // 서비스 연결에 시간이 조금 걸려서 잠시 뒤 구독한다 (Vehicle SDK와 같은 방식)
            main.postDelayed({
                val c = fusedClient ?: return@postDelayed
                try {
                    c.registerFusedLocationCallback(fusedListener)
                    c.getLastKnownLocation(fusedListener)
                    log("정밀 위치: Pleos FusedLocation 구독")
                } catch (t: Throwable) {
                    log("정밀 위치 구독 실패: ${t.javaClass.simpleName} → 내비·GPS 위치 사용")
                }
            }, FUSED_START_DELAY_MS)
        } catch (t: Throwable) {
            log("정밀 위치 사용 불가: ${t.javaClass.simpleName} → 내비·GPS 위치 사용")
        }
    }

    fun stopFused() {
        main.removeCallbacksAndMessages(null)
        fusedClient?.let {
            runCatching { it.unregisterFusedLocationCallback(fusedListener) }
            runCatching { it.release() }
        }
        fusedClient = null
    }

    fun updateFromNavi(lat: Double, lon: Double) {
        if (lat == 0.0 && lon == 0.0) return
        navi = Fix(lat, lon, NAVI_ACCURACY_M, "내비", SystemClock.elapsedRealtime())
    }

    fun setDemo(lat: Double, lon: Double, label: String) {
        demo = Fix(lat, lon, 0f, "시연: $label", SystemClock.elapsedRealtime())
    }

    fun clearDemo() {
        demo = null
    }

    fun startDevice() {
        if (listening) return
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = context.getSystemService(LocationManager::class.java) ?: return
        val providers = lm.getProviders(true)
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (provider !in providers) continue
            try {
                lm.requestLocationUpdates(provider, 1_000L, 0f, deviceListener, Looper.getMainLooper())
                lm.getLastKnownLocation(provider)?.let { device = it.toFix() }
                listening = true
            } catch (_: SecurityException) {
            }
        }
    }

    fun stopDevice() {
        runCatching { context.getSystemService(LocationManager::class.java)?.removeUpdates(deviceListener) }
        listening = false
    }

    private fun Location.toFix() = Fix(
        latitude,
        longitude,
        if (hasAccuracy()) accuracy else null,
        "GPS",
        SystemClock.elapsedRealtime(),
    )

    companion object {
        private const val FRESH_MS = 10_000L
        /** 내비 위치는 지도에 맞춘 값이라 오차를 작게 본다 */
        private const val NAVI_ACCURACY_M = 3f
        /** 정밀 위치 오차 (confidence 단위가 문서에 없어 일반 차량 GPS 오차로 둔다) */
        private const val FUSED_ACCURACY_M = 5f
        private const val FUSED_START_DELAY_MS = 1_500L
    }
}
