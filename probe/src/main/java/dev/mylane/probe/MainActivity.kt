package dev.mylane.probe

import android.Manifest
import android.app.Activity
import android.car.VehiclePropertyIds
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 신호 확인(테스트용).
 * 에뮬레이터가 실제로 어떤 차량 신호를 주는지 확인하고, 결과를 보고서 파일로 남긴다.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var report: Report
    private lateinit var detector: EventDetector
    private lateinit var carSignals: CarSignals
    private lateinit var pleos: PleosExplorer

    private lateinit var liveView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView

    private val latest = mutableMapOf<Int, Any?>()
    private var lastLocation: Location? = null
    private var speedEventsAtLastTick = 0
    private var lastTickMs = 0L
    private var speedHz = 0.0
    private var logRefreshPending = false

    private val locationListener = LocationListener { location -> lastLocation = location }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        report = Report(this)
        detector = EventDetector(report)
        carSignals = CarSignals(this, report, ::onCarValue)
        pleos = PleosExplorer(this, report) { pressed -> detector.brakePressed = pressed }
        report.onChanged = { scheduleLogRefresh() }

        setContentView(buildUi())
        report.log("신호 확인 시작 (기준값은 모두 시연용)")

        carSignals.start()
        pleos.start()
        requestRuntimePermissions()
        handler.post(liveTicker)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopLocation()
        carSignals.stop()
        pleos.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- 권한

    private val runtimePermissions = arrayOf(
        "android.car.permission.CAR_SPEED",
        "android.car.permission.READ_CAR_PEDALS",
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    private fun requestRuntimePermissions() {
        val missing = runtimePermissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            startLocation()
            return
        }
        requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        permissions.forEachIndexed { i, p ->
            val ok = grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            report.log("권한 ${p.substringAfterLast('.')}: ${if (ok) "허용" else "거부"}")
        }
        carSignals.start() // 권한이 바뀌었으니 다시 구독
        startLocation()
    }

    // ---------------------------------------------------------------- 위치

    private fun startLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val lm = getSystemService(LocationManager::class.java) ?: return
        val providers = lm.getProviders(true)
        report.log("위치 제공자: ${providers.joinToString().ifEmpty { "없음" }}")
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (provider !in providers) continue
            try {
                lm.requestLocationUpdates(provider, 1_000L, 0f, locationListener, Looper.getMainLooper())
                lm.getLastKnownLocation(provider)?.let { lastLocation = it }
            } catch (t: SecurityException) {
                report.log("위치 구독 실패: ${t.message}")
            }
        }
    }

    private fun stopLocation() {
        try {
            getSystemService(LocationManager::class.java)?.removeUpdates(locationListener)
        } catch (_: Throwable) {
        }
    }

    // ---------------------------------------------------------------- 차량 값

    private fun onCarValue(propertyId: Int, value: Any?, timestampMs: Long) {
        synchronized(latest) { latest[propertyId] = value }
        val t = if (timestampMs > 0) timestampMs else SystemClock.elapsedRealtime()
        when (propertyId) {
            VehiclePropertyIds.PERF_VEHICLE_SPEED -> (value as? Float)?.let { detector.onSpeed(it * 3.6, t) }
            VehiclePropertyIds.GEAR_SELECTION -> (value as? Int)?.let { detector.onGear(it, t) }
        }
    }

    private val liveTicker = object : Runnable {
        override fun run() {
            updateLive()
            handler.postDelayed(this, 500)
        }
    }

    private fun updateLive() {
        val now = SystemClock.elapsedRealtime()
        val speedCount = synchronized(carSignals.eventCounts) { carSignals.eventCounts[VehiclePropertyIds.PERF_VEHICLE_SPEED] ?: 0 }
        if (lastTickMs > 0 && now - lastTickMs >= 1_000) {
            speedHz = (speedCount - speedEventsAtLastTick) * 1000.0 / (now - lastTickMs)
            speedEventsAtLastTick = speedCount
            lastTickMs = now
        } else if (lastTickMs == 0L) {
            lastTickMs = now
            speedEventsAtLastTick = speedCount
        }

        val values = synchronized(latest) { latest.toMap() }
        fun v(id: Int) = CarSignals.format(id, values[id])
        val loc = lastLocation
        val brake = when (detector.brakePressed) {
            true -> "밟음"
            false -> "안 밟음"
            null -> "모름"
        }
        val sb = StringBuilder()
        sb.appendLine("차속     ${v(VehiclePropertyIds.PERF_VEHICLE_SPEED)}   (초당 %.1f회 갱신)".format(speedHz))
        sb.appendLine("기어     ${v(VehiclePropertyIds.GEAR_SELECTION)}   실제 ${v(VehiclePropertyIds.CURRENT_GEAR)}")
        sb.appendLine("브레이크 $brake   (Pleos SDK)")
        sb.appendLine("주차브레이크 ${v(VehiclePropertyIds.PARKING_BRAKE_ON)}   시동 ${v(VehiclePropertyIds.IGNITION_STATE)}")
        sb.appendLine("전조등   ${v(VehiclePropertyIds.HEADLIGHTS_STATE)}   야간모드 ${v(VehiclePropertyIds.NIGHT_MODE)}")
        sb.appendLine("주행거리 ${v(VehiclePropertyIds.PERF_ODOMETER)}")
        sb.appendLine(
            "위치     " + if (loc == null) "없음" else "%.5f, %.5f (±%.0fm)".format(loc.latitude, loc.longitude, loc.accuracy)
        )
        sb.appendLine()
        sb.appendLine("[이벤트 · 시연용 기준값]")
        sb.appendLine("저속 급가속 ${detector.lowSpeedSurgeCount}회")
        sb.appendLine("기어 전환 직후 급출발 ${detector.gearSurgeCount}회")
        sb.appendLine("급제동 ${detector.harshBrakeCount}회")
        synchronized(detector) { detector.recentEvents.toList() }.forEach { sb.appendLine("  $it") }
        liveView.text = sb.toString()
    }

    // ---------------------------------------------------------------- 화면

    private fun scheduleLogRefresh() {
        if (logRefreshPending) return
        logRefreshPending = true
        handler.postDelayed({
            logRefreshPending = false
            if (::logView.isInitialized) {
                logView.text = report.screenText()
                logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            }
        }, 200)
    }

    private fun buildUi(): View {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#11161C"))
            setPadding(px(24), px(16), px(24), px(16))
        }
        root.addView(TextView(this).apply {
            text = "신호 확인 · 테스트용"
            textSize = 26f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            textSize = 18f
            isAllCaps = false
            setOnClickListener { action() }
        }
        buttons.addView(button("권한 다시 요청") { requestRuntimePermissions() })
        buttons.addView(button("차량 신호 다시 연결") { carSignals.start() })
        buttons.addView(button("Pleos 브레이크·기어 구독") { pleos.start(); pleos.readAndSubscribe() })
        buttons.addView(button("내비 SDK 경로 옵션 찾기") { pleos.exploreNavi() })
        buttons.addView(button("이벤트 초기화") { detector.reset() })
        buttons.addView(button("보고서 저장") { saveReport() })
        root.addView(HorizontalScrollView(this).apply { addView(buttons) })

        val content = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        liveView = TextView(this).apply {
            textSize = 22f
            setTextColor(Color.parseColor("#E8EEF4"))
            typeface = Typeface.MONOSPACE
            setPadding(0, px(12), px(24), 0)
        }
        logView = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#A9B7C6"))
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        logScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#0B0F13"))
            setPadding(px(12), px(12), px(12), px(12))
            addView(logView)
        }
        content.addView(ScrollView(this).apply { addView(liveView) }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        content.addView(logScroll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.3f))
        root.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun saveReport() {
        try {
            val file = report.save()
            report.log("보고서 저장: ${file.absolutePath}")
            report.log("맥 터미널에서 가져오기: ${report.pullCommand()}")
        } catch (t: Throwable) {
            report.log("보고서 저장 실패: ${t.message}")
        }
    }

    companion object {
        private const val REQ_PERMISSIONS = 1001
    }
}
