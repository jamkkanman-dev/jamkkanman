package dev.jamkkanman.app

import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import dev.jamkkanman.app.car.CarGearSource
import dev.jamkkanman.app.car.GleoBridge
import dev.jamkkanman.app.car.LocationHub
import dev.jamkkanman.app.car.NaviWatcher
import dev.jamkkanman.app.car.VehicleWatcher
import dev.jamkkanman.app.core.CheckResult
import dev.jamkkanman.app.core.Geo
import dev.jamkkanman.app.core.Ledger
import dev.jamkkanman.app.core.SafeSpot
import dev.jamkkanman.app.core.SpotHint
import dev.jamkkanman.app.core.Speech
import dev.jamkkanman.app.core.StopEngine
import dev.jamkkanman.app.core.Voice
import dev.jamkkanman.app.core.VoiceAnswer
import dev.jamkkanman.app.core.ZoneChecker
import dev.jamkkanman.app.core.ZoneData
import dev.jamkkanman.app.core.ZoneRepository
import dev.jamkkanman.app.core.ZoneType
import dev.jamkkanman.app.ui.Palette
import dev.jamkkanman.app.ui.UiKit
import dev.jamkkanman.app.ui.UiKit.Companion.MATCH
import dev.jamkkanman.app.ui.UiKit.Companion.WRAP
import dev.jamkkanman.app.core.BaseMap
import dev.jamkkanman.app.core.KoreaTime
import dev.jamkkanman.app.core.RoadMap
import dev.jamkkanman.app.ui.StopMapView
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import org.json.JSONArray
import org.json.JSONObject

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
    private val timeFormat = KoreaTime.format("HH:mm")

    private lateinit var data: ZoneData
    private lateinit var checker: ZoneChecker
    private lateinit var ledger: Ledger
    private lateinit var voice: Voice
    private lateinit var answer: VoiceAnswer
    private lateinit var locations: LocationHub
    private lateinit var vehicle: VehicleWatcher
    private lateinit var navi: NaviWatcher
    private lateinit var engine: StopEngine

    private var voiceStatus = "음성 준비 중"
    private var naviCaution: String? = null
    private var lastLog = ""
    private var demoOpen = false
    /** 확인 범위 밖에서 앱을 켰을 때 시연 줄을 한 번만 자동으로 열었는지 */
    private var demoAutoOpened = false
    /** 내비가 알려 준 진행 방향(도, 북쪽 0) */
    private var heading = 0f
    private var built = false

    // 화면 요소
    private lateinit var gearChip: TextView
    private lateinit var signalChip: TextView
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
    private val logHistory = ArrayDeque<String>()
    private var logExpanded = false
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
        answer = VoiceAnswer(this, BuildConfig.PLEOS_CLIENT_ID, BuildConfig.PLEOS_CLIENT_SECRET) { log(it) }
        answer.onListeningChanged = { render() }
        locations = LocationHub(this) { log(it) }
        navi = NaviWatcher(this, this)
        engine = StopEngine(
            checker = checker,
            ledger = ledger,
            voice = voice,
            location = { locations.current() },
            addWaypoint = { spot -> guideTo(spot) },
            onChange = { render() },
            log = { log(it) },
            ask = { question, onAnswer -> askAloud(question, onAnswer) },
        )
        vehicle = VehicleWatcher(this, this)

        setContentView(buildUi())
        built = true
        log("데이터: ${data.region} · 금지구역 ${data.zones.size}곳 · 세울 곳 ${data.spots.size}곳${if (data.isSample) " (표본)" else ""}")

        vehicle.start()
        navi.start()
        answer.start()
        locations.startFused()
        requestLocationPermission()
        handler.post(ticker)
        render()
        // Gleo AI가 앱을 처음 띄우며 부른 경우: 위치·차량 연결이 잡힐 시간을 조금 준다
        if (GleoBridge.isActivityAction(intent?.action)) {
            val first = intent
            handler.postDelayed({ handleGleo(first) }, GLEO_COLD_START_MS)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleGleo(intent)
    }

    // ------------------------------------------------------------------ Gleo AI 음성 요청

    private fun handleGleo(request: Intent?) {
        val action = request?.action ?: return
        if (!GleoBridge.isActivityAction(action)) return
        // 화면을 다시 만들 때 같은 요청을 또 처리하지 않도록 지운다
        request.action = Intent.ACTION_MAIN
        log("Gleo AI 요청: $action")
        when (action) {
            GleoBridge.CHECK_PARKING_HERE -> gleoCheckHere()
            GleoBridge.FIND_LEGAL_PARKING -> gleoFindParking()
        }
    }

    /**
     * "여기 세워도 돼?" — 앱이 직접 한국어 음성으로 답하고(StopEngine.checkNow), Gleo에는 영어 한 문장 + data를 silent로 돌려준다.
     */
    private fun gleoCheckHere() {
        val fn = GleoBridge.CHECK_PARKING_HERE
        val silent = GleoBridge.MODE_SILENT
        val result = engine.checkNow("음성 질문")
        if (result == null) {
            voice.say("지금 위치를 아직 찾지 못했어요. 잠시 뒤에 다시 물어봐 주세요.")
            GleoBridge.respond(this, fn, GleoBridge.FAIL, "The current location is not available yet.", mode = silent)
            return
        }
        if (!result.covered) {
            GleoBridge.respond(this, fn, GleoBridge.FAIL, "No-stopping zone data is not available for this area yet.", mode = silent)
            return
        }
        val top = result.top
        val spot = result.spot
        val item = JSONObject().put("stopAllowed", top == null)
        val message = if (top != null) {
            val type = top.zone.type
            val fine = type.fineAt(result.hour)
            item.put("zoneType", GleoBridge.english(type)).put("distanceM", top.distanceM.roundToInt())
                .put("fineWon", fine).put("certain", top.certain)
            if (top.certain && top.exitM in 0.5..12.0) item.put("moveM", (top.exitM + 1.0).roundToInt())
            val where = if (type == ZoneType.SCHOOL_ZONE) "inside a school zone" else "within ${type.radiusM.roundToInt()} m of a ${GleoBridge.english(type)}"
            "Stopping here is prohibited because the car is $where, and the fine is ${GleoBridge.wonText(fine)}."
        } else if (result.risk?.busy == true) {
            "Stopping here is not prohibited, but parking enforcement is frequent nearby, so the driver should check for yellow lines."
        } else {
            "There is no no-stopping zone near the car's current location."
        }
        result.risk?.let { r ->
            item.put("enforcementCctvNearby", r.cctv != null).put("nearbyEnforcementCount", r.recent)
        }
        spot?.let { item.put("parkingName", it.spot.name).put("parkingDistanceM", it.distanceM.roundToInt()) }
        GleoBridge.respond(this, fn, GleoBridge.SUCCESS, message, JSONArray().put(item), silent)
    }

    /** "근처에 세울 데 어디야?" — 가장 가까운 세울 곳으로 길 안내를 시작하고 앱이 직접 말한다. */
    private fun gleoFindParking() {
        val fn = GleoBridge.FIND_LEGAL_PARKING
        val silent = GleoBridge.MODE_SILENT
        val fix = locations.current()
        if (fix == null) {
            voice.say("지금 위치를 아직 찾지 못했어요.")
            GleoBridge.respond(this, fn, GleoBridge.FAIL, "The current location is not available yet.", mode = silent)
            return
        }
        val hint = checker.nearestSpot(fix.lat, fix.lon)
        if (hint == null) {
            voice.say("600미터 안에 세울 수 있는 곳이 없어요.")
            GleoBridge.respond(this, fn, GleoBridge.FAIL, "There is no legal place to stop within 600 m.", mode = silent)
            return
        }
        val started = guideTo(hint.spot)
        val name = Speech.spokenName(hint.spot.name)
        val meters = hint.distanceM.roundToInt()
        voice.say(
            if (started) "${Speech.josa(name, "으로", "로")} 안내할게요. ${Speech.meters(hint.distanceM)} 거리예요."
            else "${Speech.josa(name, "이", "가")} 가장 가까워요. ${Speech.meters(hint.distanceM)} 거리인데 길 안내는 시작하지 못했어요."
        )
        val message = if (started) "Started route guidance to $name, $meters m away."
        else "The nearest legal place to stop is $name, $meters m away, but route guidance could not be started."
        val item = JSONObject().put("parkingName", hint.spot.name).put("distanceM", meters)
            .put("guidanceStarted", started)
        GleoBridge.respond(this, fn, if (started) GleoBridge.SUCCESS else GleoBridge.FAIL, message, JSONArray().put(item), silent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        vehicle.release()
        navi.release()
        locations.stopDevice()
        locations.stopFused()
        answer.release()
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

    /** 실행 중 허용 권한: 위치(멈춘 자리), 차속(멈췄는지 판단) */
    private fun requestLocationPermission() {
        val wanted = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, CarGearSource.PERMISSION_SPEED)
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) locations.startDevice()
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), REQ_LOCATION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_LOCATION) return
        locations.startDevice()
        vehicle.onPermissionsChanged()
    }

    // ------------------------------------------------------------------ 차량·내비 신호

    override fun onGear(gear: VehicleGear?) = engine.onGear(gear)

    override fun onTurnSignal(value: TurnSignalValue?) = engine.onTurnSignal(value)

    override fun onHazardLights(on: Boolean) = engine.onHazard(on)

    override fun onDoor(door: DoorValue, open: Boolean) = engine.onDoor(door, open)

    override fun onSeatBelt(seat: String, buckled: Boolean) = engine.onSeatBelt(seat, buckled)

    override fun onBrake(pressed: Boolean) = engine.onBrake(pressed)

    override fun onSpeed(kph: Float) = engine.onSpeed(kph)

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

    /**
     * 묻고(음성) 다 읽은 뒤 대답을 듣는다. 음성 인식을 못 쓰면 false (StopEngine이 묻지 않고 처리).
     * 음성 안내가 끝났다는 신호가 안 와도 12초 뒤에는 듣기 시작한다.
     */
    private fun askAloud(question: String, onAnswer: (Boolean?) -> Unit): Boolean {
        if (!answer.ready) return false
        var started = false
        val listen = {
            if (!started) {
                started = true
                answer.listen(onAnswer)
            }
        }
        voice.say(question) { listen() }
        handler.postDelayed({ listen() }, ASK_LISTEN_FALLBACK_MS)
        return true
    }

    /** 경로 안내 중이면 경유지로, 아니면 목적지로 안내 */
    private fun guideTo(spot: SafeSpot): Boolean =
        if (engine.destination != null) navi.addWaypoint(spot) else navi.routeTo(spot)

    private fun log(message: String) {
        lastLog = "${timeFormat.format(Date())}  $message"
        logHistory.addFirst(lastLog)
        while (logHistory.size > LOG_KEEP) logHistory.removeLast()
        Log.i(TAG, message)
        if (built) showLog()
    }

    /** 맨 아래 기록 줄: 평소엔 마지막 한 줄, 누르면 최근 기록을 펼친다 (차량 신호 점검용) */
    private fun showLog() {
        logText.text = if (logExpanded) logHistory.joinToString("\n") else lastLog
        logText.maxLines = if (logExpanded) LOG_KEEP else 1
    }

    // ------------------------------------------------------------------ 화면 그리기

    private fun render() {
        if (!built) return
        // 주행 중(차속 5km/h 이상, 차속을 모르면 기어 D·R)에는 화면을 가리고 음성으로만 안내한다.
        // 기어 D에 브레이크를 밟고 멈춰 있으면 화면을 보여 준다 (내려 주는 순간 경고를 볼 수 있게)
        driveOverlay.visibility = if (engine.moving) View.VISIBLE else View.GONE
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

        // 안전벨트 칩: 비상등이 잡히면 비상등을 먼저 보여 준다
        signalChip.text = when {
            engine.hazardOn -> "비상등 켜짐"
            engine.beltReleased -> "벨트 풀림"
            engine.beltKnown -> "벨트 채움"
            else -> "안전벨트"
        }
        if (engine.hazardOn || engine.beltReleased) ui.styleChip(signalChip, Palette.SURFACE, Palette.ORANGE)
        else ui.styleChip(signalChip, Palette.TEXT3, Palette.SURFACE)

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
        val now = locations.current()
        val outsideNow = now != null && !checker.covers(now.lat, now.lon)
        if (outsideNow && !demoAutoOpened) {
            demoAutoOpened = true
            if (!demoOpen) {
                demoOpen = true
                demoStrip.visibility = View.VISIBLE
                ui.styleChip(demoToggle, Palette.SURFACE, Palette.TEXT)
            }
        }
        map.notice = if (outsideNow && (result == null || !result.covered)) "이 지역은 데이터가 없어요 · ${data.region}만 들어 있어요" else null
        val arrival = engine.arrival?.takeIf {
            it.state == StopEngine.ArrivalState.ASKING || SystemClock.elapsedRealtime() - it.atMs < ARRIVAL_CARD_MS
        }
        when {
            arrival != null -> {
                // 도착 전 안내: 묻는 중이면 무엇을 말하면 되는지 크게 보여 준다
                mood = StopMapView.Mood.MAYBE
                val asking = arrival.state == StopEngine.ArrivalState.ASKING
                stateChip.text = when (arrival.state) {
                    StopEngine.ArrivalState.ADDED -> "경유지 추가"
                    else -> "도착 전 확인"
                }
                if (arrival.state == StopEngine.ArrivalState.ADDED) ui.styleChip(stateChip, Palette.SURFACE, Palette.GREEN)
                else ui.styleChip(stateChip, Palette.SURFACE, Palette.BLUE)
                reasonText.text = "목적지 ${arrival.place}"
                titleText.text = "목적지 앞은 ${arrival.hit.zone.type.title}"
                titleText.setTextColor(Palette.TEXT)
                val spotName = arrival.spot?.let { Speech.spokenName(it.spot.name) }
                detailText.text = when (arrival.state) {
                    StopEngine.ArrivalState.ASKING ->
                        "${spotName?.let { Speech.josa(it, "을", "를") } ?: "세울 곳을"} 경유지로 넣을까요?\n" +
                            if (answer.listening) "듣는 중 · \u2018응\u2019 또는 \u2018아니\u2019로 답해 주세요" else "질문이 끝나면 \u2018응\u2019 또는 \u2018아니\u2019로 답해 주세요"
                    StopEngine.ArrivalState.ADDED -> "${spotName?.let { Speech.josa(it, "을", "를") } ?: "세울 곳을"} 경유지로 넣었어요"
                    StopEngine.ArrivalState.DECLINED -> "경유지는 넣지 않았어요"
                    StopEngine.ArrivalState.NO_ANSWER -> "대답을 듣지 못해 경유지는 넣지 않았어요\n아래 \u2018여기로 안내\u2019로 바로 넣을 수 있어요"
                    StopEngine.ArrivalState.INFORMED -> spotName?.let { "${Speech.josa(it, "이", "가")} 목적지에서 ${Speech.metersShort(arrival.spot.distanceM)} 거리에 있어요" } ?: "조금 떨어진 곳에 세워 주세요"
                }
                if (asking && answer.listening) titleText.setTextColor(Palette.BLUE)
            }
            result == null && outsideNow -> {
                // 데이터가 없는 곳에서 켠 경우 (에뮬레이터 기본 위치 등): 빈 지도 대신 무엇을 하면 되는지 알려 준다
                mood = StopMapView.Mood.NEUTRAL
                stateChip.text = "확인 범위 밖"
                ui.styleChip(stateChip, Palette.TEXT2, Palette.SURFACE_HIGH)
                reasonText.text = ""
                titleText.text = "지금 위치는 아직 확인할 수 없어요"
                titleText.setTextColor(Palette.TEXT)
                detailText.text = "금지구역 데이터는 ${data.region}만 들어 있어요.\n아래 시연 줄에서 ${data.demo.firstOrNull()?.label?.let { "‘$it’ 같은 " } ?: ""}지점을 눌러 보세요."
            }
            result == null -> {
                mood = StopMapView.Mood.NEUTRAL
                stateChip.text = "대기 중"
                ui.styleChip(stateChip, Palette.TEXT2, Palette.SURFACE_HIGH)
                reasonText.text = if (reason != null) "$reason · 위치를 찾는 중" else ""
                titleText.text = "멈추면 그 자리를 확인해요"
                titleText.setTextColor(Palette.TEXT)
                detailText.text = "기어 P · 안전벨트 풀림 · 문 열림 중 하나만 들어와도 바로 확인해요."
            }
            top != null -> {
                mood = if (top.certain) StopMapView.Mood.WARN else StopMapView.Mood.MAYBE
                stateChip.text = if (top.certain) "주정차 금지" else "금지구역 가능성"
                ui.styleChip(stateChip, Palette.SURFACE, if (top.certain) Palette.RED else Palette.ORANGE)
                reasonText.text = "${reason ?: "정차"} · ${checkedTime()}"
                titleText.text = if (top.certain) top.zone.type.title else "${top.zone.type.place} ${top.zone.type.range}일 수 있어요"
                titleText.setTextColor(if (top.certain) Palette.RED else Palette.TEXT)
                val lines = mutableListOf("${top.zone.type.risk}이에요 · 과태료 ${Speech.won(top.zone.type.fineAt(result.hour))}" +
                    (top.zone.type.fineNote?.let { " ($it)" } ?: ""))
                if (top.certain && top.exitM in 0.5..12.0) {
                    lines += if (reason == StopEngine.BELT_REASON) "옮기려면 안전벨트를 다시 매고 약 ${Speech.metersShort(top.exitM + 1.0)} 옮겨 주세요"
                    else "약 ${Speech.metersShort(top.exitM + 1.0)}만 옮기면 범위 밖이에요"
                }
                result.risk?.let { r -> Speech.riskLine(r)?.let { lines += it } }
                detailText.text = lines.joinToString("\n")
            }
            !result.covered -> {
                // 데이터가 없는 곳에서 '세워도 돼요'라고 하면 틀린 안내가 되니 범위 밖이라고 알린다
                mood = StopMapView.Mood.NEUTRAL
                stateChip.text = "확인 범위 밖"
                ui.styleChip(stateChip, Palette.TEXT2, Palette.SURFACE_HIGH)
                reasonText.text = "${reason ?: "정차"} · ${checkedTime()}"
                titleText.text = "이 지역은 아직 확인할 수 없어요"
                titleText.setTextColor(Palette.TEXT)
                detailText.text = "지금은 ${data.region} 금지구역 데이터만 들어 있어요.\n오른쪽 위 시연 → ${data.demo.firstOrNull()?.label?.let { "‘$it’ 같은 " } ?: ""}지점을 눌러 보세요."
            }
            result.risk?.busy == true && engine.lastCreditWon == 0 -> {
                // 금지구역은 아니지만 단속 CCTV가 있거나 단속이 잦은 곳: 노란 선 같은 지정 구간은 데이터에 없어 확인을 권한다
                val risk = requireNotNull(result.risk)
                mood = StopMapView.Mood.CLEAR
                stateChip.text = "단속 주의"
                ui.styleChip(stateChip, Palette.SURFACE, Palette.ORANGE)
                reasonText.text = "${reason ?: "정차"} · ${checkedTime()}"
                titleText.text = if (risk.cctv != null) "단속 카메라가 있는 곳이에요" else "단속이 잦은 곳이에요"
                titleText.setTextColor(Palette.TEXT)
                detailText.text = listOfNotNull("주정차 금지구역은 아니에요", Speech.riskLine(risk), "노란 선이 있으면 세우지 마세요")
                    .joinToString("\n")
            }
            else -> {
                // '세워도 된다'고 판정하지 않는다: 노란 선 같은 지정 구간은 데이터에 없어, 확인한 금지구역 밖이라는 사실만 알린다
                mood = StopMapView.Mood.CLEAR
                stateChip.text = "금지구역 밖"
                ui.styleChip(stateChip, Palette.SURFACE, Palette.GREEN)
                reasonText.text = "${reason ?: "정차"} · ${checkedTime()}"
                titleText.text = if (engine.lastCreditWon > 0) "금지구역 밖으로 옮겼어요" else "주변에 주정차 금지구역이 없어요"
                titleText.setTextColor(Palette.TEXT)
                detailText.text = if (engine.lastCreditWon > 0) "알림을 듣고 옮겨 세워서 과태료 ${Speech.won(engine.lastCreditWon)}을 아꼈어요."
                else listOfNotNull("소화전·교차로 모퉁이·버스정류장·횡단보도·어린이보호구역 기준", result.risk?.let { Speech.riskLine(it) })
                    .joinToString("\n")
            }
        }

        // 세울 곳: 도착 전 안내 중이면 목적지 근처, 확인 결과가 있으면 그 기준, 없으면 지금 위치 기준
        val fix = result?.fix ?: locations.current()
        val arrivalSpot = arrival?.spot
        val spot = arrivalSpot ?: result?.spot ?: fix?.let { checker.nearestSpot(it.lat, it.lon) }
        val spotOutside = fix != null && !checker.covers(fix.lat, fix.lon)
        if (spot == null) {
            spotLabel.text = "세울 수 있는 곳"
            spotName.text = if (spotOutside) "이 지역은 아직 찾을 수 없어요" else "600m 안에 없어요"
            spotDetail.text = if (spotOutside) "세울 곳 데이터도 ${data.region}만 들어 있어요" else "공공데이터 기준 · ${data.region}"
            guideButton.text = "여기로 안내"
            ui.setButtonEnabled(guideButton, false)
        } else {
            spotLabel.text = when {
                arrivalSpot != null -> "목적지 근처 세울 수 있는 곳"
                top != null -> "대신 세울 수 있는 곳"
                else -> "가까운 세울 수 있는 곳"
            }
            spotName.text = spot.spot.name
            val away = Speech.metersShort(spot.distanceM)
            spotDetail.text = listOf(if (arrivalSpot != null) "목적지에서 $away" else away, spot.spot.note)
                .filter { it.isNotBlank() }.joinToString(" · ")
            // 도착 전 안내에서 이미 경유지로 넣었으면 같은 곳을 또 넣지 않도록 버튼을 끈다
            val waypointDone = arrivalSpot != null && arrival?.state == StopEngine.ArrivalState.ADDED
            guideButton.text = if (waypointDone) "경유지에 넣음" else "여기로 안내"
            ui.setButtonEnabled(guideButton, !waypointDone)
        }
        guideButton.tag = spot?.spot
        ui.setButtonEnabled(repeatButton, engine.lastSpoken != null)
        spokenText.text = if (answer.listening) "듣는 중 · ‘응’ 또는 ‘아니’로 답해 주세요"
        else voiceLine(voiceStatus.substringAfterLast(" → "))

        // 지도 점선·거리 표시는 내 차 기준이라, 목적지 근처 세울 곳은 내 차에서 다시 잰다 (멀면 지도에 잇지 않는다)
        val mapSpot = if (arrivalSpot == null) spot else fix?.let {
            val d = Geo.distanceM(it.lat, it.lon, arrivalSpot.spot.lat, arrivalSpot.spot.lon)
            if (d <= MAP_SPOT_MAX_M) SpotHint(arrivalSpot.spot, d) else null
        }
        map.update(fix, heading, top?.zone?.id, mapSpot, mood)
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

    /** 음성 상태 한 줄: 개발용 빌드는 엔진 이름까지, 제출용은 운전자에게 필요한 말만 */
    private fun voiceLine(raw: String): String = when {
        BuildConfig.DEBUG -> raw
        raw.contains("화면으로만") -> raw
        raw.contains("안내 중") -> "음성으로 안내하는 중"
        raw.contains("오류") || raw.contains("실패") || raw.contains("불가") || raw.contains("설치 안 됨") -> "음성이 안 나오면 화면 안내를 봐 주세요"
        else -> ""
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
            // 세로 화면: 지도 위, 안내 아래. 비율을 고정하고 안내는 길어지면 스크롤한다
            content.addView(buildMapCard(), ui.lp(MATCH, 0, 1.1f))
            content.addView(buildPanel(), ui.lp(MATCH, 0, 1f, top = 24))
        } else {
            content.addView(buildMapCard(), ui.lp(0, MATCH, 1.25f))
            content.addView(buildPanel(), ui.lp(0, MATCH, 1f, start = 24))
        }
        page.addView(content, ui.lp(MATCH, 0, 1f))

        demoStrip = buildDemoStrip()
        page.addView(demoStrip, ui.lp(MATCH, WRAP, top = 16))

        logText = ui.text(18, Palette.TEXT4, value = lastLog).apply {
            maxLines = 1
            // 차량 신호 점검용 기록: 개발용 빌드에서만 바로 보이고, 제출용(release)에서는 설정에서 켠다
            visibility = if (BuildConfig.DEBUG) View.VISIBLE else View.GONE
            setOnClickListener {
                logExpanded = !logExpanded
                showLog()
            }
        }
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
            setData(RoadMap.load(this@MainActivity), data.zones, data.spots, data.cctvs)
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
        signalChip = ui.chip()
        doorChip = ui.chip()
        locationChip = ui.chip()
        for ((i, chip) in listOf(gearChip, signalChip, doorChip, locationChip).withIndex()) {
            chip.elevation = ui.pxf(3)
            chips.addView(chip, ui.lp(WRAP, WRAP, start = if (i == 0) 0 else 10))
        }
        card.addView(chips, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        val legend = ui.horizontal().apply {
            background = ui.rounded(Palette.SURFACE, 100)
            setPadding(ui.px(22), ui.px(12), ui.px(22), ui.px(12))
            elevation = ui.pxf(3)
        }
        for ((color, name) in listOf(Palette.RED to "금지구역", Palette.ORANGE to "어린이보호구역", Palette.GREEN to "세울 곳", Palette.FILLED to "단속 CCTV")) {
            legend.addView(View(this).apply { background = ui.rounded(color, 100) }, ui.lp(ui.px(16), ui.px(16), start = if (name == "금지구역") 0 else 18))
            legend.addView(ui.text(20, Palette.TEXT2, bold = true, value = name), ui.lp(WRAP, WRAP, start = 8))
        }
        card.addView(legend, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply {
            leftMargin = ui.px(24)
            bottomMargin = ui.px(24)
        })

        // 입체(3D) / 평면(2D) 보기 전환
        val viewToggle = ui.chip(sizePx = 24).apply {
            elevation = ui.pxf(3)
            isClickable = true
        }
        fun styleToggle() {
            viewToggle.text = if (map.tilted) "3D" else "2D"
            if (map.tilted) ui.styleChip(viewToggle, Palette.SURFACE, Palette.BLUE)
            else ui.styleChip(viewToggle, Palette.TEXT2, Palette.SURFACE)
        }
        styleToggle()
        viewToggle.setOnClickListener {
            map.tilted = !map.tilted
            styleToggle()
        }
        card.addView(viewToggle, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.END).apply {
            topMargin = ui.px(24)
            rightMargin = ui.px(24)
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
        card.addView(ui.text(24, Palette.TEXT3, value = "목적지 앞이 금지구역이면 도착 700m 전에 음성으로 알려 주고, 가까운 세울 곳을 경유지로 바로 넣어요. 주행 중에는 따로 누를 것이 없어요.").apply { setLineSpacing(ui.pxf(8), 1f) }, ui.lp(MATCH, WRAP, top = 10))
        destinationText = ui.text(22, Palette.TEXT4)
        card.addView(destinationText, ui.lp(MATCH, WRAP, top = 8))
        card.addView(ui.divider())

        val diagSwitch = Switch(this).apply {
            isChecked = BuildConfig.DEBUG
            setOnCheckedChangeListener { _, checked ->
                logText.visibility = if (checked) View.VISIBLE else View.GONE
            }
        }
        card.addView(switchRow("차량 신호 기록 보기", diagSwitch))
        card.addView(ui.text(24, Palette.TEXT3, value = "기어·안전벨트·문 신호가 들어오는지 화면 맨 아래에 보여 줘요. 점검할 때만 켜 두세요.").apply { setLineSpacing(ui.pxf(8), 1f) }, ui.lp(MATCH, WRAP, top = 10))

        card.addView(ui.button("닫기", UiKit.ButtonStyle.SECONDARY) { dim.visibility = View.GONE }, ui.lp(MATCH, WRAP, top = 32))
        // 화면이 낮으면 설정 카드가 잘리지 않게 스크롤한다
        val scroller = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFillViewport = false
            addView(card)
        }
        dim.addView(scroller, FrameLayout.LayoutParams(ui.px(860), WRAP, Gravity.CENTER).apply {
            topMargin = ui.px(24)
            bottomMargin = ui.px(24)
        })
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
        // 자주 누르는 버튼을 앞에 둔다 (창이 좁아도 바로 보이게)
        row.addView(ui.button("멈춤 신호", UiKit.ButtonStyle.PRIMARY, sizePx = 24, heightPx = 72) { engine.stopMoment("시연 정차") }, ui.lp(WRAP, WRAP, end = 10))
        // 에뮬레이터 Virtual Console에는 안전벨트 조작이 없어, 실제 차의 신호(뒷좌석 벨트 채움 → 풀림)를 그대로 넣는다
        row.addView(ui.button("안전벨트 풀림", UiKit.ButtonStyle.OUTLINE, sizePx = 24, heightPx = 72) {
            log("시연: 뒷좌석 오른쪽 안전벨트 채움 → 풀림")
            engine.demoBeltRelease()
        }, ui.lp(WRAP, WRAP, end = 10))
        row.addView(ui.button("초기화", UiKit.ButtonStyle.OUTLINE, sizePx = 24, heightPx = 72) {
            engine.resetSession()
            log("초기화: 같은 자리 안내 기록을 지움")
        }, ui.lp(WRAP, WRAP, end = 24))
        for (place in data.demo) {
            row.addView(ui.button(place.label, UiKit.ButtonStyle.OUTLINE, sizePx = 24, heightPx = 72) {
                locations.setDemo(place.lat, place.lon, place.label)
                log("시연 위치: ${place.label}")
                render()
            }, ui.lp(WRAP, WRAP, end = 10))
        }
        row.addView(ui.button("도착 전 안내", UiKit.ButtonStyle.OUTLINE, sizePx = 24, heightPx = 72) { demoArrival() }, ui.lp(WRAP, WRAP, end = 10))
        row.addView(ui.button("시연 위치 끄기", UiKit.ButtonStyle.OUTLINE, sizePx = 24, heightPx = 72) {
            locations.clearDemo()
            log("시연 위치 끔")
            render()
        }, ui.lp(WRAP, WRAP))
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
                name = "대치동 학원가 (시연)",
                address = "대치동 학원가 (시연)",
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
        private const val LOG_KEEP = 12
        private const val GLEO_COLD_START_MS = 2_500L
        private const val REQ_LOCATION = 2001
        private const val ASK_LISTEN_FALLBACK_MS = 12_000L
        /** 도착 전 안내 카드를 대답 뒤에도 이만큼 보여 준다 */
        private const val ARRIVAL_CARD_MS = 60_000L
        /** 지도에 내 차 → 세울 곳 점선을 그리는 최대 거리 */
        private const val MAP_SPOT_MAX_M = 800.0
    }
}
