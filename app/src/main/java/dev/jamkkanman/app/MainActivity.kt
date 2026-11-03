package dev.jamkkanman.app

import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import dev.jamkkanman.app.car.LocationHub
import dev.jamkkanman.app.car.NaviWatcher
import dev.jamkkanman.app.car.VehicleWatcher
import dev.jamkkanman.app.core.CheckResult
import dev.jamkkanman.app.core.Ledger
import dev.jamkkanman.app.core.SafeSpot
import dev.jamkkanman.app.core.Speech
import dev.jamkkanman.app.core.StopEngine
import dev.jamkkanman.app.core.Voice
import dev.jamkkanman.app.core.ZoneChecker
import dev.jamkkanman.app.core.ZoneData
import dev.jamkkanman.app.core.ZoneRepository
import dev.jamkkanman.app.ui.Palette
import dev.jamkkanman.app.ui.UiKit
import dev.jamkkanman.app.ui.UiKit.Companion.MATCH
import dev.jamkkanman.app.ui.UiKit.Companion.WRAP
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 잠깐만 메인 화면.
 * - 기어 P(정차)일 때: 멈춘 자리 확인 결과, 세울 곳, 이번 달 아낀 과태료, 동의 설정
 * - 주행 중: 화면 대신 음성으로만 안내하고, 마지막 안내 문장만 크게 보여 준다
 */
class MainActivity : Activity(), VehicleWatcher.Events, NaviWatcher.Events {

    private val handler = Handler(Looper.getMainLooper())
    private val ui by lazy { UiKit(this) }
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.KOREA)

    private lateinit var data: ZoneData
    private lateinit var checker: ZoneChecker
    private lateinit var ledger: Ledger
    private lateinit var voice: Voice
    private lateinit var locations: LocationHub
    private lateinit var vehicle: VehicleWatcher
    private lateinit var navi: NaviWatcher
    private lateinit var engine: StopEngine

    private var voiceStatus = "음성 준비 중"
    private var naviCaution: String? = null
    private var lastLog = ""
    private var demoOpen = false
    private var built = false

    // 화면 요소
    private lateinit var gearPill: TextView
    private lateinit var hazardPill: TextView
    private lateinit var doorPill: TextView
    private lateinit var locationPill: TextView
    private lateinit var band: View
    private lateinit var reasonText: TextView
    private lateinit var titleText: TextView
    private lateinit var detailText: TextView
    private lateinit var spotLabel: TextView
    private lateinit var spotName: TextView
    private lateinit var spotDetail: TextView
    private lateinit var guideButton: TextView
    private lateinit var repeatButton: TextView
    private lateinit var spokenText: TextView
    private lateinit var savedAmount: TextView
    private lateinit var savedDetail: TextView
    private lateinit var creditText: TextView
    private lateinit var shareSwitch: Switch
    private lateinit var shareCount: TextView
    private lateinit var waypointSwitch: Switch
    private lateinit var destinationText: TextView
    private lateinit var demoStrip: View
    private lateinit var logText: TextView
    private lateinit var driveOverlay: View
    private lateinit var driveSpoken: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        data = runCatching { ZoneRepository.load(this) }.getOrElse {
            log("데이터 읽기 실패: ${it.message}")
            ZoneData.EMPTY
        }
        checker = ZoneChecker(data)
        ledger = Ledger(this)
        voice = Voice(this, BuildConfig.PLEOS_CLIENT_ID, BuildConfig.PLEOS_CLIENT_SECRET) { status ->
            voiceStatus = status
            log(status)
        }
        locations = LocationHub(this)
        navi = NaviWatcher(this, this)
        engine = StopEngine(
            checker = checker,
            ledger = ledger,
            voice = voice,
            location = { locations.current() },
            addWaypoint = { spot -> guideTo(spot) },
            onChange = { render() },
            log = { log(it) },
        )
        vehicle = VehicleWatcher(this, this)

        setContentView(buildUi())
        built = true
        log("데이터: ${data.region} · 금지구역 ${data.zones.size}곳 · 세울 곳 ${data.spots.size}곳${if (data.isSample) " (표본)" else ""}")

        vehicle.start()
        navi.start()
        requestLocationPermission()
        handler.post(ticker)
        render()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        vehicle.release()
        navi.release()
        locations.stopDevice()
        voice.shutdown()
        super.onDestroy()
    }

    private val ticker = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 1_000)
        }
    }

    // ------------------------------------------------------------------ 권한

    private fun requestLocationPermission() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locations.startDevice()
        } else {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) locations.startDevice()
    }

    // ------------------------------------------------------------------ 차량·내비 신호

    override fun onGear(gear: VehicleGear?) = engine.onGear(gear)

    override fun onTurnSignal(value: TurnSignalValue?) = engine.onTurnSignal(value)

    override fun onDoor(door: DoorValue, open: Boolean) = engine.onDoor(door, open)

    override fun onVehicleLog(message: String) = log(message)

    override fun onNaviLocation(info: CurrentLocationInfo) = locations.updateFromNavi(info.latitude, info.longitude)

    override fun onNaviDestination(info: DestinationInfo) = engine.onDestination(info)

    override fun onNaviArrived() = engine.onArrived()

    override fun onNaviCaution(noParkingM: Int?, schoolZoneM: Int?) {
        naviCaution = listOfNotNull(
            noParkingM?.let { "주정차 금지구역 ${it}m 앞" },
            schoolZoneM?.let { "어린이보호구역 ${it}m 앞" },
        ).joinToString(" · ")
        render()
    }

    override fun onNaviLog(message: String) = log(message)

    /** 경로 안내 중이면 경유지로, 아니면 목적지로 안내 */
    private fun guideTo(spot: SafeSpot): Boolean =
        if (engine.destination != null) navi.addWaypoint(spot) else navi.routeTo(spot)

    private fun log(message: String) {
        lastLog = "${timeFormat.format(Date())}  $message"
        Log.i(TAG, message)
        if (built) logText.text = lastLog
    }

    // ------------------------------------------------------------------ 화면 그리기

    private fun render() {
        if (!built) return
        val gear = engine.gear
        val driving = gear == VehicleGear.GEAR_DRIVE || gear == VehicleGear.GEAR_REVERSE
        driveOverlay.visibility = if (driving) View.VISIBLE else View.GONE
        driveSpoken.text = engine.lastSpoken ?: "멈추면 그 자리를 확인해 한 번만 음성으로 알려 드려요."

        renderSignals()
        renderStopCard(engine.lastResult)
        renderSide()
    }

    private fun renderSignals() {
        val gear = engine.gear
        gearPill.text = "기어 ${gearLabel(gear)}"
        if (gear == VehicleGear.GEAR_PARK) ui.stylePill(gearPill, Palette.AMBER, Palette.AMBER_BG)
        else ui.stylePill(gearPill, Palette.SUB, Palette.CARD_HI)

        hazardPill.text = if (engine.hazardOn) "비상등 켜짐" else "비상등"
        if (engine.hazardOn) ui.stylePill(hazardPill, Palette.AMBER, Palette.AMBER_BG)
        else ui.stylePill(hazardPill, Palette.FAINT, Palette.CARD_HI)

        doorPill.text = if (engine.doorOpen) "문 열림" else "문"
        if (engine.doorOpen) ui.stylePill(doorPill, Palette.AMBER, Palette.AMBER_BG)
        else ui.stylePill(doorPill, Palette.FAINT, Palette.CARD_HI)

        val fix = locations.current()
        locationPill.text = if (fix == null) "위치 없음" else "위치 · ${fix.source}"
        if (fix == null) ui.stylePill(locationPill, Palette.RED, Palette.RED_BG)
        else ui.stylePill(locationPill, Palette.BLUE, Palette.CARD_HI)
    }

    private fun renderStopCard(result: CheckResult?) {
        val reason = engine.lastReason
        val top = result?.top
        when {
            result == null -> {
                band.setBackgroundColor(Palette.LINE)
                reasonText.text = if (reason != null) "$reason · 위치를 찾는 중" else "대기 중"
                titleText.text = "멈추면 그 자리를 확인해요"
                titleText.setTextColor(Palette.TEXT)
                detailText.text = "기어 P, 비상등, 문 열림 중 하나가 들어오면 소화전·버스정류장·횡단보도·교차로 모퉁이·어린이보호구역인지 바로 확인해요."
            }
            top != null -> {
                val color = if (top.certain) Palette.RED else Palette.AMBER
                band.setBackgroundColor(color)
                reasonText.text = "${reason ?: "정차"} · ${checkedTime()} 확인"
                titleText.text = if (top.certain) top.zone.type.title else "${top.zone.type.place} ${top.zone.type.range}일 수 있어요"
                titleText.setTextColor(color)
                val parts = mutableListOf("${top.zone.type.risk}이에요", "과태료 ${Speech.won(top.zone.type.fineAt(result.hour))}")
                if (top.certain && top.exitM in 0.5..12.0) parts += "약 ${Speech.metersShort(top.exitM + 1.0)}만 옮기면 범위 밖"
                if (result.hits.size > 1) parts += "겹친 금지구역 ${result.hits.size - 1}곳 더"
                detailText.text = parts.joinToString(" · ")
            }
            else -> {
                band.setBackgroundColor(Palette.GREEN)
                reasonText.text = "${reason ?: "정차"} · ${checkedTime()} 확인"
                titleText.text = "여기는 세워도 되는 자리예요"
                titleText.setTextColor(Palette.GREEN)
                detailText.text = if (engine.lastCreditWon > 0) "알림을 듣고 옮겨 세워서 과태료 ${Speech.won(engine.lastCreditWon)}을 아꼈어요."
                else "데이터에 있는 주정차 금지구역(${data.region}) 밖이에요."
            }
        }

        // 세울 곳: 확인 결과가 있으면 그 기준, 없으면 지금 위치 기준
        val spot = result?.spot ?: locations.current()?.let { checker.nearestSpot(it.lat, it.lon) }
        if (spot == null) {
            spotLabel.text = "세울 수 있는 곳"
            spotName.text = "600m 안에 등록된 곳이 없어요"
            spotDetail.text = "데이터: ${data.region}"
            ui.setButtonEnabled(guideButton, false)
        } else {
            spotLabel.text = if (top != null) "대신 세울 수 있는 곳" else "가까운 세울 수 있는 곳"
            spotName.text = spot.spot.name
            spotDetail.text = listOf(Speech.metersShort(spot.distanceM), spot.spot.kind.label, spot.spot.note)
                .filter { it.isNotBlank() }.joinToString(" · ")
            ui.setButtonEnabled(guideButton, true)
        }
        guideButton.tag = spot?.spot
        ui.setButtonEnabled(repeatButton, engine.lastSpoken != null)
        spokenText.text = listOfNotNull(engine.lastSpoken?.let { "음성: $it" }, voiceStatus).joinToString("\n")
    }

    private fun renderSide() {
        savedAmount.text = "%,d원".format(ledger.monthSavedWon)
        savedDetail.text = "옮겨 세운 횟수 ${ledger.monthMovedCount}번 · 받은 알림 ${ledger.monthWarnCount}번"
        if (engine.lastCreditWon > 0) {
            creditText.visibility = View.VISIBLE
            creditText.text = "방금 ${Speech.won(engine.lastCreditWon)} 아꼈어요"
        } else {
            creditText.visibility = View.GONE
        }
        if (shareSwitch.isChecked != ledger.shareConsent) shareSwitch.isChecked = ledger.shareConsent
        shareCount.text = if (ledger.shareConsent) "보탠 기록 ${ledger.demandCount}건 · 이 차 안에만 저장" else "꺼져 있어요 · 아무것도 남기지 않아요"
        if (waypointSwitch.isChecked != ledger.autoWaypoint) waypointSwitch.isChecked = ledger.autoWaypoint
        val dest = engine.destination
        destinationText.text = listOfNotNull(
            if (dest == null) "경로 안내 중이 아니에요" else "목적지 ${dest.name} · ${Speech.metersShort(dest.remainDistance.toDouble())}",
            naviCaution?.takeIf { it.isNotBlank() }?.let { "내비 알림: $it" },
        ).joinToString("\n")
        demoStrip.visibility = if (demoOpen) View.VISIBLE else View.GONE
    }

    private fun checkedTime(): String =
        if (engine.lastCheckedAtMs > 0) timeFormat.format(Date(engine.lastCheckedAtMs)) else ""

    private fun gearLabel(gear: VehicleGear?): String = when (gear) {
        VehicleGear.GEAR_PARK -> "P"
        VehicleGear.GEAR_DRIVE -> "D"
        VehicleGear.GEAR_REVERSE -> "R"
        VehicleGear.GEAR_NEUTRAL -> "N"
        null, VehicleGear.GEAR_UNKNOWN -> "-"
        else -> gear.name.removePrefix("GEAR_")
    }

    // ------------------------------------------------------------------ 화면 만들기

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Palette.BG) }

        val page = ui.vertical().apply { setPadding(ui.dp(32), ui.dp(24), ui.dp(32), ui.dp(16)) }
        page.addView(buildHeader(), ui.lp(MATCH, WRAP))

        val content = ui.horizontal().apply { gravity = Gravity.TOP }
        content.addView(buildStopCard(), ui.lp(0, MATCH, 1.45f, end = 20))
        content.addView(ScrollView(this).apply { addView(buildSideColumn()) }, ui.lp(0, MATCH, 1f))
        page.addView(content, ui.lp(MATCH, 0, 1f, top = 20))

        demoStrip = buildDemoStrip()
        page.addView(demoStrip, ui.lp(MATCH, WRAP, top = 14))

        logText = ui.text(13f, Palette.FAINT, value = lastLog).apply { maxLines = 1 }
        page.addView(logText, ui.lp(MATCH, WRAP, top = 10))

        root.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
        driveOverlay = buildDriveOverlay()
        root.addView(driveOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        return root
    }

    private fun buildHeader(): View {
        val row = ui.horizontal()
        val titles = ui.vertical()
        titles.addView(ui.text(30f, Palette.TEXT, bold = true, value = "잠깐만"))
        titles.addView(ui.text(16f, Palette.SUB, value = "멈추는 순간, 한 번만 알려 드려요"))
        row.addView(titles, ui.lp(0, WRAP, 1f))

        gearPill = ui.pill()
        hazardPill = ui.pill()
        doorPill = ui.pill()
        locationPill = ui.pill()
        for (pill in listOf(gearPill, hazardPill, doorPill, locationPill)) row.addView(pill, ui.lp(WRAP, WRAP, start = 8))
        if (data.isSample) {
            val sample = ui.pill("표본 데이터")
            ui.stylePill(sample, Palette.RED, Palette.RED_BG)
            row.addView(sample, ui.lp(WRAP, WRAP, start = 8))
        }
        val demoToggle = ui.pill("시연").apply {
            isClickable = true
            setOnClickListener {
                demoOpen = !demoOpen
                render()
            }
        }
        row.addView(demoToggle, ui.lp(WRAP, WRAP, start = 8))
        return row
    }

    private fun buildStopCard(): View {
        val card = ui.card().apply { setPadding(0, 0, 0, 0) }
        band = View(this).apply { setBackgroundColor(Palette.LINE) }
        card.addView(band, ui.lp(MATCH, ui.dp(8)))

        val body = ui.vertical().apply { setPadding(ui.dp(30), ui.dp(22), ui.dp(30), ui.dp(24)) }
        reasonText = ui.text(16f, Palette.FAINT, bold = true)
        titleText = ui.text(36f, Palette.TEXT, bold = true)
        detailText = ui.text(20f, Palette.SUB).apply { setLineSpacing(0f, 1.2f) }
        body.addView(reasonText)
        body.addView(titleText, ui.lp(MATCH, WRAP, top = 8))
        body.addView(detailText, ui.lp(MATCH, WRAP, top = 10))
        body.addView(ui.divider())

        spotLabel = ui.text(15f, Palette.FAINT, bold = true)
        spotName = ui.text(26f, Palette.TEXT, bold = true)
        spotDetail = ui.text(18f, Palette.SUB)
        body.addView(spotLabel)
        body.addView(spotName, ui.lp(MATCH, WRAP, top = 6))
        body.addView(spotDetail, ui.lp(MATCH, WRAP, top = 4))

        val buttons = ui.horizontal()
        guideButton = ui.button("여기로 안내", filled = true) {
            val spot = guideButton.tag as? SafeSpot ?: return@button
            if (guideTo(spot)) voice.say("${Speech.josa(Speech.spokenName(spot.name), "으로", "로")} 안내할게요.")
        }
        repeatButton = ui.button("다시 듣기", filled = false) { engine.lastSpoken?.let { voice.say(it) } }
        buttons.addView(guideButton, ui.lp(WRAP, WRAP, end = 12))
        buttons.addView(repeatButton, ui.lp(WRAP, WRAP))
        body.addView(buttons, ui.lp(MATCH, WRAP, top = 20))

        spokenText = ui.text(15f, Palette.FAINT).apply { maxLines = 3 }
        body.addView(spokenText, ui.lp(MATCH, WRAP, top = 16))

        card.addView(ScrollView(this).apply { addView(body) }, ui.lp(MATCH, 0, 1f))
        return card
    }

    private fun buildSideColumn(): View {
        val column = ui.vertical()

        val saved = ui.card()
        saved.addView(ui.text(17f, Palette.SUB, bold = true, value = "이번 달 아낀 과태료"))
        savedAmount = ui.text(46f, Palette.AMBER, bold = true)
        savedDetail = ui.text(16f, Palette.FAINT)
        creditText = ui.text(17f, Palette.GREEN, bold = true).apply {
            setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(6))
            background = ui.rounded(Palette.GREEN_BG, 100)
        }
        saved.addView(savedAmount, ui.lp(WRAP, WRAP, top = 4))
        saved.addView(savedDetail)
        saved.addView(creditText, ui.lp(WRAP, WRAP, top = 12))
        saved.addView(ui.text(13f, Palette.FAINT, value = "알림을 듣고 금지구역 밖으로 옮겨 세웠을 때만 더해요."), ui.lp(MATCH, WRAP, top = 10))
        column.addView(saved, ui.lp(MATCH, WRAP))

        val share = ui.card()
        shareSwitch = Switch(this).apply {
            setOnCheckedChangeListener { _, checked ->
                if (ledger.shareConsent != checked) {
                    ledger.shareConsent = checked
                    render()
                }
            }
        }
        share.addView(switchRow("정차 수요 지도에 익명으로 보태기", shareSwitch))
        share.addView(
            ui.text(15f, Palette.SUB, value = "정확한 위치 대신 약 50m 칸과 시간대만 남겨요. 지자체가 안심승하차구역을 어디에 더 만들지 정할 때 쓰여요.")
                .apply { setLineSpacing(0f, 1.15f) },
            ui.lp(MATCH, WRAP, top = 8),
        )
        shareCount = ui.text(14f, Palette.FAINT)
        share.addView(shareCount, ui.lp(MATCH, WRAP, top = 8))
        column.addView(share, ui.lp(MATCH, WRAP, top = 16))

        val arrival = ui.card()
        waypointSwitch = Switch(this).apply {
            setOnCheckedChangeListener { _, checked ->
                if (ledger.autoWaypoint != checked) {
                    ledger.autoWaypoint = checked
                    render()
                }
            }
        }
        arrival.addView(switchRow("도착 전에 세울 곳을 경유지로", waypointSwitch))
        arrival.addView(
            ui.text(15f, Palette.SUB, value = "목적지 앞이 금지구역이면 도착 700m 전에 알려 주고, 가까운 세울 곳을 내비 경유지로 넣어요.")
                .apply { setLineSpacing(0f, 1.15f) },
            ui.lp(MATCH, WRAP, top = 8),
        )
        destinationText = ui.text(14f, Palette.FAINT)
        arrival.addView(destinationText, ui.lp(MATCH, WRAP, top = 8))
        column.addView(arrival, ui.lp(MATCH, WRAP, top = 16))

        return column
    }

    private fun switchRow(label: String, switch: Switch): View {
        val row = ui.horizontal()
        row.addView(ui.text(19f, Palette.TEXT, bold = true, value = label), ui.lp(0, WRAP, 1f))
        row.addView(switch, ui.lp(WRAP, WRAP, start = 12))
        return row
    }

    /** 시연 모드: 데이터에 들어 있는 대치동 지점으로 옮겨서 확인해 볼 수 있다. */
    private fun buildDemoStrip(): View {
        val row = ui.horizontal().apply { setPadding(0, ui.dp(4), 0, ui.dp(4)) }
        row.addView(ui.text(15f, Palette.FAINT, bold = true, value = "시연 위치"), ui.lp(WRAP, WRAP, end = 12))
        for (place in data.demo) {
            row.addView(ui.button(place.label, filled = false) {
                locations.setDemo(place.lat, place.lon, place.label)
                log("시연 위치: ${place.label}")
                render()
            }, ui.lp(WRAP, WRAP, end = 10))
        }
        row.addView(ui.button("멈춤 신호 보내기", filled = true) { engine.stopMoment("시연 정차") }, ui.lp(WRAP, WRAP, end = 10))
        row.addView(ui.button("도착 전 안내 시연", filled = false) { demoArrival() }, ui.lp(WRAP, WRAP, end = 10))
        row.addView(ui.button("시연 위치 끄기", filled = false) {
            locations.clearDemo()
            log("시연 위치 끔")
            render()
        }, ui.lp(WRAP, WRAP, end = 10))
        row.addView(ui.button("화면 초기화", filled = false) { engine.resetSession() }, ui.lp(WRAP, WRAP))
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    /** 첫 번째 시연 지점을 목적지로 가정하고, 남은 거리 500m일 때의 도착 전 안내를 재현한다. */
    private fun demoArrival() {
        val target = data.demo.firstOrNull() ?: return
        engine.onArrived()
        engine.onDestination(
            DestinationInfo(
                latitude = target.lat,
                longitude = target.lon,
                name = target.label,
                address = target.label,
                remainDistance = 500,
                remainTime = 90,
                tollFare = 0,
                poiId = "",
                poiSubId = null,
            )
        )
    }

    private fun buildDriveOverlay(): View {
        val overlay = ui.vertical().apply {
            setBackgroundColor(Palette.BG)
            gravity = Gravity.CENTER
            setPadding(ui.dp(64), ui.dp(32), ui.dp(64), ui.dp(32))
            isClickable = true // 뒤 화면을 누르지 못하게
        }
        overlay.addView(ui.text(44f, Palette.TEXT, bold = true, value = "주행 중").apply { gravity = Gravity.CENTER }, ui.lp(MATCH, WRAP))
        overlay.addView(
            ui.text(22f, Palette.SUB, value = "화면 대신 음성으로 알려 드려요").apply { gravity = Gravity.CENTER },
            ui.lp(MATCH, WRAP, top = 12),
        )
        driveSpoken = ui.text(24f, Palette.AMBER, bold = true).apply {
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
        }
        overlay.addView(driveSpoken, ui.lp(MATCH, WRAP, top = 36))
        return overlay
    }

    companion object {
        private const val TAG = "Jamkkanman"
        private const val REQ_LOCATION = 2001
    }
}
