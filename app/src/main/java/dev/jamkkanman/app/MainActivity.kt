package dev.jamkkanman.app

import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import dev.jamkkanman.app.core.BaseMap
import dev.jamkkanman.app.core.RoadMap
import dev.jamkkanman.app.ui.StopMapView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 잠깐만 메인 화면 (Pleos Connect 디자인 가이드 Light 테마·타이포그래피 기준).
 * - 왼쪽: 지도 카드 (국토부 표준노드링크 도로망 위에 내 차, 주변 금지구역, 세울 곳)
 * - 오른쪽: 확인 결과 → 대신 세울 곳과 안내 버튼 → 이번 달 아낀 과태료. 설정은 버튼으로 연다.
 * - 세로 창(화면 분할)에서는 지도 위, 결과 아래로 쌓는다.
 * - 주행 중: 화면 대신 음성으로만 안내하고, 마지막 안내 문장만 크게 보여 준다.
 * 운전자는 1~2초 안에 읽어야 하므로 핵심 정보만 크게 보여 준다 (가이드의 Glanceability 원칙).
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
    /** 내비가 알려 준 진행 방향(도, 북쪽 0) */
    private var heading = 0f
    private var built = false

    // 화면 요소
    private lateinit var gearChip: TextView
    private lateinit var hazardChip: TextView
    private lateinit var doorChip: TextView
    private lateinit var locationChip: TextView
    private lateinit var stateChip: TextView
    private lateinit var reasonText: TextView
    private lateinit var titleText: TextView
    private lateinit var detailText: TextView
    private lateinit var spotLabel: TextView
    private lateinit var spotName: TextView
    private lateinit var spotDetail: TextView
    private lateinit var guideButton: TextView
    private lateinit var repeatButton: TextView
    private lateinit var spokenText: TextView
    private lateinit var map: StopMapView
    private lateinit var savedAmount: TextView
    private lateinit var savedDetail: TextView
    private lateinit var creditText: TextView
    private lateinit var shareSwitch: Switch
    private lateinit var shareCount: TextView
    private lateinit var waypointSwitch: Switch
    private lateinit var destinationText: TextView
    private lateinit var demoToggle: TextView
    private lateinit var demoStrip: View
    private lateinit var settingsOverlay: View
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

    override fun onNaviLocation(info: CurrentLocationInfo) {
        locations.updateFromNavi(info.latitude, info.longitude)
        heading = info.heading.toFloat()
    }

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
        driveSpoken.text = engine.lastSpoken ?: "멈추면 그 자리를 확인해\n한 번만 음성으로 알려 드려요."

        renderChips()
        renderStatus(engine.lastResult)
        renderSide()
    }

    private fun renderChips() {
        val gear = engine.gear
        gearChip.text = "기어 ${gearLabel(gear)}"
        if (gear == VehicleGear.GEAR_PARK) ui.styleChip(gearChip, Palette.SURFACE, Palette.TEXT)
        else ui.styleChip(gearChip, Palette.TEXT3, Palette.SURFACE)

        hazardChip.text = if (engine.hazardOn) "비상등 켜짐" else "비상등"
        if (engine.hazardOn) ui.styleChip(hazardChip, Palette.SURFACE, Palette.ORANGE)
        else ui.styleChip(hazardChip, Palette.TEXT3, Palette.SURFACE)

        doorChip.text = if (engine.doorOpen) "문 열림" else "문 닫힘"
        if (engine.doorOpen) ui.styleChip(doorChip, Palette.SURFACE, Palette.ORANGE)
        else ui.styleChip(doorChip, Palette.TEXT3, Palette.SURFACE)

        val fix = locations.current()
        locationChip.text = when {
            fix == null -> "위치 없음"
            fix.source.startsWith("시연") -> "시연 위치"
            else -> "위치 ${fix.source}"
        }
        if (fix == null) ui.styleChip(locationChip, Palette.SURFACE, Palette.RED)
        else ui.styleChip(locationChip, Palette.BLUE, Palette.SURFACE)

        if (demoOpen) ui.styleChip(demoToggle, Palette.SURFACE, Palette.TEXT)
        else ui.styleChip(demoToggle, Palette.TEXT2, Palette.SURFACE_HIGH)
    }

    private fun renderStatus(result: CheckResult?) {
        val reason = engine.lastReason
        val top = result?.top
        val mood: StopMapView.Mood
        when {
            result == null -> {
                mood = StopMapView.Mood.NEUTRAL
                stateChip.text = "대기 중"
                ui.styleChip(stateChip, Palette.TEXT2, Palette.SURFACE_HIGH)
                reasonText.text = if (reason != null) "$reason · 위치를 찾는 중" else ""
                titleText.text = "멈추면 그 자리를 확인해요"
                titleText.setTextColor(Palette.TEXT)
                detailText.text = "기어 P · 비상등 · 문 열림 중 하나만 들어와도 바로 확인해요."
            }
            top != null -> {
                mood = if (top.certain) StopMapView.Mood.WARN else StopMapView.Mood.MAYBE
                stateChip.text = if (top.certain) "주정차 금지" else "금지구역 가능성"
                ui.styleChip(stateChip, Palette.SURFACE, if (top.certain) Palette.RED else Palette.ORANGE)
                reasonText.text = "${reason ?: "정차"} · ${checkedTime()}"
                titleText.text = if (top.certain) top.zone.type.title else "${top.zone.type.place} ${top.zone.type.range}일 수 있어요"
                titleText.setTextColor(if (top.certain) Palette.RED else Palette.TEXT)
                val lines = mutableListOf("${top.zone.type.risk}이에요 · 과태료 ${Speech.won(top.zone.type.fineAt(result.hour))}")
                if (top.certain && top.exitM in 0.5..12.0) lines += "약 ${Speech.metersShort(top.exitM + 1.0)}만 옮기면 범위 밖이에요"
                detailText.text = lines.joinToString("\n")
            }
            else -> {
                mood = StopMapView.Mood.CLEAR
                stateChip.text = "정차 가능"
                ui.styleChip(stateChip, Palette.SURFACE, Palette.GREEN)
                reasonText.text = "${reason ?: "정차"} · ${checkedTime()}"
                titleText.text = "여기는 세워도 돼요"
                titleText.setTextColor(Palette.TEXT)
                detailText.text = if (engine.lastCreditWon > 0) "알림을 듣고 옮겨 세워서 과태료 ${Speech.won(engine.lastCreditWon)}을 아꼈어요."
                else "주변 주정차 금지구역 밖이에요."
            }
        }

        // 세울 곳: 확인 결과가 있으면 그 기준, 없으면 지금 위치 기준
        val fix = result?.fix ?: locations.current()
        val spot = result?.spot ?: fix?.let { checker.nearestSpot(it.lat, it.lon) }
        if (spot == null) {
            spotLabel.text = "세울 수 있는 곳"
            spotName.text = "600m 안에 없어요"
            spotDetail.text = "공공데이터 기준 · ${data.region}"
            ui.setButtonEnabled(guideButton, false)
        } else {
            spotLabel.text = if (top != null) "대신 세울 수 있는 곳" else "가까운 세울 수 있는 곳"
            spotName.text = spot.spot.name
            spotDetail.text = listOf(Speech.metersShort(spot.distanceM), spot.spot.note)
                .filter { it.isNotBlank() }.joinToString(" · ")
            ui.setButtonEnabled(guideButton, true)
        }
        guideButton.tag = spot?.spot
        ui.setButtonEnabled(repeatButton, engine.lastSpoken != null)
        spokenText.text = voiceStatus.substringAfterLast(" → ")

        map.update(fix, heading, top?.zone?.id, spot, mood)
    }

    private fun renderSide() {
        savedAmount.text = "%,d원".format(ledger.monthSavedWon)
        savedDetail.text = "옮겨 세움 ${ledger.monthMovedCount}번 · 받은 알림 ${ledger.monthWarnCount}번"
        if (engine.lastCreditWon > 0) {
            creditText.visibility = View.VISIBLE
            creditText.text = "+${Speech.won(engine.lastCreditWon)}"
        } else {
            creditText.visibility = View.GONE
        }
        if (shareSwitch.isChecked != ledger.shareConsent) shareSwitch.isChecked = ledger.shareConsent
        shareCount.text = if (ledger.shareConsent) "보탠 기록 ${ledger.demandCount}건 · 정확한 위치 대신 약 50m 칸과 시간대만 남겨요" else "꺼져 있어요 · 아무것도 남기지 않아요"
        if (waypointSwitch.isChecked != ledger.autoWaypoint) waypointSwitch.isChecked = ledger.autoWaypoint
        val dest = engine.destination
        destinationText.text = listOfNotNull(
            if (dest == null) "지금은 경로 안내 중이 아니에요" else "목적지 ${dest.name} · ${Speech.metersShort(dest.remainDistance.toDouble())}",
            naviCaution?.takeIf { it.isNotBlank() }?.let { "내비: $it" },
        ).joinToString("\n")
        demoStrip.visibility = if (demoOpen) View.VISIBLE else View.GONE
    }

    private fun checkedTime(): String =
        if (engine.lastCheckedAtMs > 0) "${timeFormat.format(Date(engine.lastCheckedAtMs))} 확인" else ""

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
        val portrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT

        val page = ui.vertical().apply { setPadding(ui.px(28), ui.px(24), ui.px(28), ui.px(16)) }
        val content = if (portrait) ui.vertical() else ui.horizontal().apply { gravity = Gravity.TOP }
        if (portrait) {
            content.addView(buildMapCard(), ui.lp(MATCH, 0, 1f))
            content.addView(buildPanel(), ui.lp(MATCH, WRAP, top = 24))
        } else {
            content.addView(buildMapCard(), ui.lp(0, MATCH, 1.25f))
            content.addView(buildPanel(), ui.lp(0, MATCH, 1f, start = 24))
        }
        page.addView(content, ui.lp(MATCH, 0, 1f))

        demoStrip = buildDemoStrip()
        page.addView(demoStrip, ui.lp(MATCH, WRAP, top = 16))

        logText = ui.text(18, Palette.TEXT4, value = lastLog).apply { maxLines = 1 }
        page.addView(logText, ui.lp(MATCH, WRAP, top = 8))

        root.addView(page, FrameLayout.LayoutParams(MATCH, MATCH))
        settingsOverlay = buildSettingsOverlay()
        root.addView(settingsOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        driveOverlay = buildDriveOverlay()
        root.addView(driveOverlay, FrameLayout.LayoutParams(MATCH, MATCH))
        return root
    }

    /** 지도 카드: 둥근 흰 카드 안에 지도, 왼쪽 위에 차량 상태 칩 */
    private fun buildMapCard(): View {
        val card = FrameLayout(this).apply {
            background = ui.rounded(Palette.SURFACE, 32)
            clipToOutline = true
            elevation = ui.pxf(6)
        }
        map = StopMapView(this, ui.scale).apply {
            setData(RoadMap.load(this@MainActivity), data.zones, data.spots)
        }
        // 건물·실폭도로 배경은 파일이 커서 화면 스레드 밖에서 읽는다
        val appContext = applicationContext
        Thread {
            val base = BaseMap.load(appContext)
            map.post { map.setBaseMap(base) }
        }.apply { name = "basemap-load" }.start()
        card.addView(map, FrameLayout.LayoutParams(MATCH, MATCH))

        val chips = ui.horizontal().apply { setPadding(ui.px(24), ui.px(24), ui.px(24), 0) }
        gearChip = ui.chip()
        hazardChip = ui.chip()
        doorChip = ui.chip()
        locationChip = ui.chip()
        for ((i, chip) in listOf(gearChip, hazardChip, doorChip, locationChip).withIndex()) {
            chip.elevation = ui.pxf(3)
            chips.addView(chip, ui.lp(WRAP, WRAP, start = if (i == 0) 0 else 10))
        }
        card.addView(chips, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        val legend = ui.horizontal().apply {
            background = ui.rounded(Palette.SURFACE, 100)
            setPadding(ui.px(22), ui.px(12), ui.px(22), ui.px(12))
            elevation = ui.pxf(3)
        }
        for ((color, name) in listOf(Palette.RED to "금지구역", Palette.ORANGE to "어린이보호구역", Palette.GREEN to "세울 곳")) {
            legend.addView(View(this).apply { background = ui.rounded(color, 100) }, ui.lp(ui.px(16), ui.px(16), start = if (name == "금지구역") 0 else 18))
            legend.addView(ui.text(20, Palette.TEXT2, bold = true, value = name), ui.lp(WRAP, WRAP, start = 8))
        }
        card.addView(legend, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = ui.px(24)
            bottomMargin = ui.px(24)
        })
        return card
    }

    /** 오른쪽 패널: 결과 → 세울 곳 → 아낀 과태료 */
    private fun buildPanel(): View {
        val panel = ui.vertical()

        // 머리: 앱 이름과 시연·설정
        val header = ui.horizontal()
        header.addView(ui.text(32, Palette.TEXT, bold = true, value = "잠깐만"))
        header.addView(ui.text(22, Palette.TEXT3, value = "멈추는 순간, 한 번만 알려 드려요"), ui.lp(0, WRAP, 1f, start = 16))
        demoToggle = ui.chip("시연").apply {
            isClickable = true
            setOnClickListener {
                demoOpen = !demoOpen
                render()
            }
        }
        header.addView(demoToggle, ui.lp(WRAP, WRAP, start = 10))
        header.addView(ui.chip("설정").apply {
            isClickable = true
            setOnClickListener { settingsOverlay.visibility = View.VISIBLE }
        }, ui.lp(WRAP, WRAP, start = 10))
        panel.addView(header, ui.lp(MATCH, WRAP, bottom = 20))

        // 확인 결과
        val status = ui.card()
        val statusTop = ui.horizontal()
        stateChip = ui.chip(sizePx = 24)
        reasonText = ui.text(22, Palette.TEXT3)
        statusTop.addView(stateChip)
        statusTop.addView(reasonText, ui.lp(WRAP, WRAP, start = 16))
        status.addView(statusTop)
        titleText = ui.text(48, Palette.TEXT, bold = true).apply {
            maxLines = 2
            setLineSpacing(ui.pxf(14), 1f)
        }
        status.addView(titleText, ui.lp(MATCH, WRAP, top = 20))
        detailText = ui.text(28, Palette.TEXT3).apply { setLineSpacing(ui.pxf(10), 1f) }
        status.addView(detailText, ui.lp(MATCH, WRAP, top = 14))
        panel.addView(status, ui.lp(MATCH, WRAP))

        // 대신 세울 곳
        val spotCard = ui.card()
        spotLabel = ui.text(24, Palette.GREEN, bold = true)
        spotName = ui.text(36, Palette.TEXT, bold = true).apply { maxLines = 1 }
        spotDetail = ui.text(26, Palette.TEXT3)
        spotCard.addView(spotLabel)
        spotCard.addView(spotName, ui.lp(MATCH, WRAP, top = 10))
        spotCard.addView(spotDetail, ui.lp(MATCH, WRAP, top = 8))
        val buttons = ui.horizontal()
        guideButton = ui.button("여기로 안내", UiKit.ButtonStyle.PRIMARY) {
            val spot = guideButton.tag as? SafeSpot ?: return@button
            if (guideTo(spot)) voice.say("${Speech.josa(Speech.spokenName(spot.name), "으로", "로")} 안내할게요.")
        }
        repeatButton = ui.button("다시 듣기", UiKit.ButtonStyle.SECONDARY) { engine.lastSpoken?.let { voice.say(it) } }
        buttons.addView(guideButton, ui.lp(0, WRAP, 1.4f, end = 14))
        buttons.addView(repeatButton, ui.lp(0, WRAP, 1f))
        spotCard.addView(buttons, ui.lp(MATCH, WRAP, top = 26))
        spokenText = ui.text(20, Palette.TEXT4).apply { maxLines = 1 }
        spotCard.addView(spokenText, ui.lp(MATCH, WRAP, top = 14))
        panel.addView(spotCard, ui.lp(MATCH, WRAP, top = 20))

        // 이번 달 아낀 과태료
        val saved = ui.card(paddingPx = 30)
        val savedRow = ui.horizontal()
        val savedTexts = ui.vertical()
        savedTexts.addView(ui.text(24, Palette.TEXT3, bold = true, value = "이번 달 아낀 과태료"))
        savedDetail = ui.text(22, Palette.TEXT4)
        savedTexts.addView(savedDetail, ui.lp(WRAP, WRAP, top = 6))
        savedRow.addView(savedTexts, ui.lp(0, WRAP, 1f))
        creditText = ui.chip(sizePx = 22).also { ui.styleChip(it, Palette.SURFACE, Palette.GREEN) }
        savedRow.addView(creditText, ui.lp(WRAP, WRAP, end = 14))
        savedAmount = ui.text(40, Palette.TEXT, bold = true)
        savedRow.addView(savedAmount)
        saved.addView(savedRow)
        panel.addView(saved, ui.lp(MATCH, WRAP, top = 20))

        return ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(panel)
        }
    }

    /** 설정: 흐린 배경 위 가운데 카드 */
    private fun buildSettingsOverlay(): View {
        val dim = FrameLayout(this).apply {
            setBackgroundColor(0x80131417.toInt())
            visibility = View.GONE
            isClickable = true
            setOnClickListener { visibility = View.GONE }
        }
        val card = ui.card(paddingPx = 44).apply { isClickable = true }
        card.addView(ui.text(36, Palette.TEXT, bold = true, value = "설정"))

        shareSwitch = Switch(this).apply {
            setOnCheckedChangeListener { _, checked ->
                if (ledger.shareConsent != checked) {
                    ledger.shareConsent = checked
                    render()
                }
            }
        }
        card.addView(switchRow("정차 수요 지도에 익명으로 보태기", shareSwitch), ui.lp(MATCH, WRAP, top = 28))
        card.addView(ui.text(24, Palette.TEXT3, value = "지자체가 안심승하차구역을 어디에 더 만들지 정할 때 쓰여요.").apply { setLineSpacing(ui.pxf(8), 1f) }, ui.lp(MATCH, WRAP, top = 10))
        shareCount = ui.text(22, Palette.TEXT4)
        card.addView(shareCount, ui.lp(MATCH, WRAP, top = 8))
        card.addView(ui.divider())

        waypointSwitch = Switch(this).apply {
            setOnCheckedChangeListener { _, checked ->
                if (ledger.autoWaypoint != checked) {
                    ledger.autoWaypoint = checked
                    render()
                }
            }
        }
        card.addView(switchRow("도착 전에 세울 곳을 경유지로", waypointSwitch))
        card.addView(ui.text(24, Palette.TEXT3, value = "목적지 앞이 금지구역이면 도착 700m 전에 알려 주고, 가까운 세울 곳을 내비 경유지로 넣어요.").apply { setLineSpacing(ui.pxf(8), 1f) }, ui.lp(MATCH, WRAP, top = 10))
        destinationText = ui.text(22, Palette.TEXT4)
        card.addView(destinationText, ui.lp(MATCH, WRAP, top = 8))

        card.addView(ui.button("닫기", UiKit.ButtonStyle.SECONDARY) { dim.visibility = View.GONE }, ui.lp(MATCH, WRAP, top = 32))
        dim.addView(card, FrameLayout.LayoutParams(ui.px(860), WRAP, Gravity.CENTER))
        return dim
    }

    private fun switchRow(label: String, switch: Switch): View {
        val row = ui.horizontal()
        row.addView(ui.text(28, Palette.TEXT, bold = true, value = label), ui.lp(0, WRAP, 1f))
        switch.scaleX = 1.4f
        switch.scaleY = 1.4f
        row.addView(switch, ui.lp(WRAP, WRAP, start = 20, end = 10))
        return row
    }

    /** 시연 모드: 데이터에 들어 있는 대치동 지점으로 옮겨서 확인해 볼 수 있다. */
    private fun buildDemoStrip(): View {
        val row = ui.horizontal().apply {
            setPadding(ui.px(20), ui.px(14), ui.px(20), ui.px(14))
            background = ui.rounded(Palette.SURFACE, 28)
        }
        row.addView(ui.text(24, Palette.TEXT, bold = true, value = "시연"), ui.lp(WRAP, WRAP, end = 16))
        for (place in data.demo) {
            row.addView(ui.button(place.label, UiKit.ButtonStyle.OUTLINE, sizePx = 24, heightPx = 72) {
                locations.setDemo(place.lat, place.lon, place.label)
                log("시연 위치: ${place.label}")
                render()
            }, ui.lp(WRAP, WRAP, end = 10))
        }
        row.addView(ui.button("멈춤 신호", UiKit.ButtonStyle.PRIMARY, sizePx = 24, heightPx = 72) { engine.stopMoment("시연 정차") }, ui.lp(WRAP, WRAP, end = 10))
        row.addView(ui.button("도착 전 안내", UiKit.ButtonStyle.OUTLINE, sizePx = 24, heightPx = 72) { demoArrival() }, ui.lp(WRAP, WRAP, end = 10))
        row.addView(ui.button("시연 위치 끄기", UiKit.ButtonStyle.OUTLINE, sizePx = 24, heightPx = 72) {
            locations.clearDemo()
            log("시연 위치 끔")
            render()
        }, ui.lp(WRAP, WRAP, end = 10))
        row.addView(ui.button("초기화", UiKit.ButtonStyle.OUTLINE, sizePx = 24, heightPx = 72) { engine.resetSession() }, ui.lp(WRAP, WRAP))
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
            setPadding(ui.px(80), ui.px(40), ui.px(80), ui.px(40))
            isClickable = true // 뒤 화면을 누르지 못하게
        }
        overlay.addView(ui.text(56, Palette.TEXT, bold = true, value = "주행 중").apply { gravity = Gravity.CENTER }, ui.lp(MATCH, WRAP))
        overlay.addView(
            ui.text(30, Palette.TEXT3, value = "화면 대신 음성으로 알려 드려요").apply { gravity = Gravity.CENTER },
            ui.lp(MATCH, WRAP, top = 20),
        )
        driveSpoken = ui.text(40, Palette.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            setLineSpacing(ui.pxf(14), 1f)
        }
        overlay.addView(driveSpoken, ui.lp(MATCH, WRAP, top = 48))
        return overlay
    }

    companion object {
        private const val TAG = "Jamkkanman"
        private const val REQ_LOCATION = 2001
    }
}
