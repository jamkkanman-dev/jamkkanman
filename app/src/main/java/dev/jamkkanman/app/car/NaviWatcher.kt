package dev.jamkkanman.app.car

import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.jamkkanman.app.core.SafeSpot

/**
 * Pleos NaviHelper SDK 연동.
 * - 현재 위치와 목적지를 받아 '멈춘 자리'와 '세울 자리'를 확인한다.
 * - 내비가 알려 주는 주의 지점 중 주정차 금지구역·어린이보호구역을 기록한다.
 * - 세울 곳을 경유지로 넣는다.
 */
class NaviWatcher(context: Context, private val events: Events) {

    interface Events {
        fun onNaviLocation(info: CurrentLocationInfo)
        fun onNaviDestination(info: DestinationInfo)
        fun onNaviArrived()
        fun onNaviCaution(noParkingM: Int?, schoolZoneM: Int?)
        fun onNaviLog(message: String)
    }

    private val navi = NaviHelper(context.applicationContext)
    private val main = Handler(Looper.getMainLooper())
    var connected = false
        private set
    private var started = false

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            main.post { events.onNaviLocation(currentLocationInfo) }
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) {
            main.post { events.onNaviDestination(destinationInfo) }
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            main.post { events.onNaviArrived() }
        }

        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) {
            val noParking = cautionPointList.filter { it.type == CautionPointType.NO_PARKING_ZONE }.minOfOrNull { it.remainDistance }
            val school = cautionPointList.filter { it.type == CautionPointType.SCHOOL_ZONE }.minOfOrNull { it.remainDistance }
            if (noParking != null || school != null) main.post { events.onNaviCaution(noParking, school) }
        }

        override fun onError(errorCode: NaviErrorCode) {
            main.post { events.onNaviLog("내비 오류: ${errorCode.name}") }
        }
    }

    /** 내비가 위치를 먼저 보내 주지 않는 경우를 대비해 주기적으로 물어본다. */
    private val poller = object : Runnable {
        override fun run() {
            if (connected) {
                runCatching { navi.getCurrentLocationInfo() }
                runCatching { navi.getDestinationInfo() }
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    fun start() {
        if (started) return
        started = true
        navi.addListener(listener)
        try {
            navi.initialize(
                {
                    main.post {
                        connected = true
                        events.onNaviLog("내비 연결됨")
                    }
                },
                { t ->
                    main.post {
                        connected = false
                        events.onNaviLog("내비 연결 실패: ${t.message}")
                    }
                },
            )
        } catch (t: Throwable) {
            events.onNaviLog("내비 초기화 실패: ${t.message}")
        }
        main.postDelayed(poller, POLL_MS)
    }

    /** 경로 안내 중이 아닐 때: 세울 곳을 목적지로 바로 안내한다. */
    fun routeTo(spot: SafeSpot): Boolean {
        if (!connected) {
            events.onNaviLog("안내 못 함: 내비 연결 전")
            return false
        }
        return try {
            navi.requestRoute(
                RouteInfo(
                    latitude = spot.lat,
                    longitude = spot.lon,
                    poiName = spot.name,
                    poiId = "",
                    address = spot.name,
                    poiSubId = "",
                )
            )
            events.onNaviLog("길 안내 요청: ${spot.name}")
            true
        } catch (t: Throwable) {
            events.onNaviLog("길 안내 실패: ${t.message}")
            false
        }
    }

    /** 세울 곳을 첫 번째 경유지로 넣는다. 성공적으로 요청했으면 true */
    fun addWaypoint(spot: SafeSpot): Boolean {
        if (!connected) {
            events.onNaviLog("경유지 추가 못 함: 내비 연결 전")
            return false
        }
        return try {
            navi.addWaypoint(
                RequestWaypointInfo(
                    latitude = spot.lat,
                    longitude = spot.lon,
                    poiName = spot.name,
                    waypointIndex = WaypointIndex.FIRST,
                    poiId = "",
                    address = spot.name,
                    poiSubId = "",
                )
            )
            events.onNaviLog("경유지 추가 요청: ${spot.name}")
            true
        } catch (t: Throwable) {
            events.onNaviLog("경유지 추가 실패: ${t.message}")
            false
        }
    }

    fun release() {
        main.removeCallbacksAndMessages(null)
        runCatching { navi.removeListener(listener) }
        runCatching { navi.release() }
        connected = false
        started = false
    }

    companion object {
        private const val POLL_MS = 3_000L
    }
}
