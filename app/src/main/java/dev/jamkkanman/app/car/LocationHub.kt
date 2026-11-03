package dev.jamkkanman.app.car

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import dev.jamkkanman.app.core.Fix

/**
 * 위치 출처를 한곳에 모은다.
 * 우선순위: 시연 위치 > 내비(Pleos NaviHelper) > 기기 GPS
 * 내비 위치가 10초 넘게 안 오면 GPS를 쓴다.
 */
class LocationHub(private val context: Context) {

    var demo: Fix? = null
        private set
    private var navi: Fix? = null
    private var device: Fix? = null
    private var listening = false

    private val deviceListener = LocationListener { location -> device = location.toFix() }

    fun current(): Fix? {
        demo?.let { return it }
        val now = SystemClock.elapsedRealtime()
        navi?.takeIf { now - it.atMs < FRESH_MS }?.let { return it }
        device?.takeIf { now - it.atMs < FRESH_MS }?.let { return it }
        return navi ?: device
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
    }
}
