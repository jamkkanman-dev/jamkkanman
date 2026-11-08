package dev.jamkkanman.app.core

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.OnServerConnectionListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.Locale

/**
 * 짧은 음성 대답 듣기 (Pleos SpeechToText SDK, 기기 내 인식 ON_DEVICE: 인터넷·앱 등록 없이 동작).
 * "경유지로 넣을까요?"처럼 묻고 난 뒤 한 번만 듣고, 응/아니/모름으로 돌려준다.
 * 인식이 안 되는 환경이면 ready가 false로 남고, 부르는 쪽은 예전처럼 묻지 않고 처리한다.
 */
class VoiceAnswer(
    context: Context,
    private val clientId: String,
    private val clientSecret: String,
    private val log: (String) -> Unit,
) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var stt: SpeechToText? = null
    private var pending: ((Boolean?) -> Unit)? = null
    private var heard = ""
    /**
     * 시도할 인식 방식. 서버를 쓰는 HYBRID·SERVER는 Pleos My Project에서 STT SDK 사용 승인이 필요한데,
     * 2026.11 현재 STT는 신청 버튼이 없는 'Coming Soon' 상태라 기기 안 인식(ON_DEVICE)만 쓴다.
     * 승인이 열리면 SERVER_MODES_OPEN을 true로 바꾸면 HYBRID를 먼저 시도한다.
     */
    private val modes = ArrayDeque(
        listOfNotNull(
            Mode.HYBRID.takeIf { SERVER_MODES_OPEN && clientId.isNotBlank() && clientSecret.isNotBlank() },
            Mode.ON_DEVICE,
        )
    )
    private var mode: Mode? = null
    /** 점검용: 듣는 동안 마이크 소리가 들어왔는지 (소리 크기 최댓값·횟수, 인식 시작 여부) */
    @Volatile private var maxRms = 0.0
    @Volatile private var rmsCount = 0
    @Volatile private var recognitionStarted = false

    /** 인식 엔진이 준비됐는지 */
    var ready = false
        private set
    /** 지금 대답을 듣는 중인지 (화면에 '듣는 중' 표시) */
    var listening = false
        private set
    /** 듣기 시작·끝날 때 화면을 다시 그리게 알린다 */
    var onListeningChanged: (() -> Unit)? = null

    private val listener = object : ResultListener {
        override fun onUpdated(text: String, isFinal: Boolean) {
            main.post { onText(text, isFinal) }
        }

        override fun onUpdatedEpdData(startTime: Long, endTime: Long) {}

        override fun onStartedRecognition() {
            recognitionStarted = true
        }

        override fun onEndedRecognition() {
            main.post { finish(YesNo.parse(heard)) }
        }

        override fun onError(errorType: ErrorType) {
            main.post {
                log("음성 대답 오류: ${errorType.name}")
                finish(null)
            }
        }

        override fun onReady() {}

        override fun onUpdatedRms(rms: Double) {
            rmsCount++
            if (rms > maxRms) maxRms = rms
        }
    }

    private val timeout = Runnable {
        runCatching { stt?.stop() }
        finish(YesNo.parse(heard))
    }

    fun start() {
        if (stt != null) return
        if (!ASK_BY_VOICE) {
            log("음성 대답: 기본 꺼짐 (에뮬레이터에서 인식을 확인하기 전) → 묻지 않고 설정대로 경유지를 넣음")
            return
        }
        val next = modes.removeFirstOrNull() ?: run {
            log("음성 대답 사용 불가: 쓸 수 있는 인식 방식이 없음 → 묻지 않고 설정대로 처리")
            return
        }
        mode = next
        try {
            val engine = SpeechToText(app, next)
            stt = engine
            // TextToSpeech와 같이 initialize()로 내부 엔진을 만든 뒤 리스너를 붙인다
            engine.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    main.post {
                        if (engine !== stt) return@post
                        runCatching { engine.setLocale(Locale.KOREA) }
                        if (next == Mode.ON_DEVICE) markReady(next) else register(engine, next)
                    }
                }

                override fun onFailure(errorType: ErrorType) {
                    main.post {
                        if (engine !== stt) return@post
                        log("음성 대답: ${next.name} 초기화 실패(${errorType.name}) → 다음 방식")
                        switchToNextMode()
                    }
                }
            })
            engine.addListener(listener)
        } catch (t: Throwable) {
            log("음성 대답: ${next.name} 사용 불가(${t.javaClass.simpleName}) → 다음 방식")
            stt = null
            switchToNextMode()
        }
    }

    /** 서버를 쓰는 방식은 앱 등록(Client ID/Secret)이 끝나야 쓸 수 있다 */
    private fun register(engine: SpeechToText, next: Mode) {
        engine.registerApp(clientId, clientSecret, object : OnServerConnectionListener {
            override fun onConnected() {
                main.post { if (engine === stt) markReady(next) }
            }

            override fun onFailed(errorType: ErrorType) {
                main.post {
                    if (engine !== stt) return@post
                    log("음성 대답: ${next.name} 앱 등록 실패(${errorType.name}) → 다음 방식")
                    switchToNextMode()
                }
            }
        })
    }

    private fun markReady(next: Mode) {
        ready = true
        log("음성 대답: Pleos SpeechToText 준비됨 (${next.name})")
    }

    /** 지금 엔진을 놓고 다음 인식 방식으로 다시 준비한다 */
    private fun switchToNextMode() {
        stt?.let {
            runCatching { it.removeListener(listener) }
            runCatching { it.release() }
        }
        stt = null
        ready = false
        start()
    }

    /** 한 번 듣고 응(true)·아니(false)·모름(null)을 돌려준다. 준비가 안 됐으면 바로 null */
    fun listen(onAnswer: (Boolean?) -> Unit) {
        val engine = stt
        if (!ready || engine == null) {
            onAnswer(null)
            return
        }
        cancel()
        pending = onAnswer
        heard = ""
        maxRms = 0.0
        rmsCount = 0
        recognitionStarted = false
        listening = true
        onListeningChanged?.invoke()
        try {
            engine.request()
        } catch (t: Throwable) {
            log("음성 대답 요청 실패: ${t.javaClass.simpleName}")
            finish(null)
            return
        }
        main.postDelayed(timeout, LISTEN_MS)
    }

    private fun onText(text: String, isFinal: Boolean) {
        if (!listening) return
        heard = text
        // 중간 결과("어…")로 섣불리 정하지 않고, 끝난 결과만 쓴다 (끝이 안 오면 시간이 지나 마지막 말로 정함)
        if (isFinal) finish(YesNo.parse(text))
    }

    private fun finish(answer: Boolean?) {
        val callback = pending ?: return
        pending = null
        listening = false
        main.removeCallbacks(timeout)
        val said = when (answer) {
            true -> "응"
            false -> "아니"
            null -> "못 알아들음"
        }
        log("음성 대답(${mode?.name}): \"${heard.ifBlank { "(말 없음)" }}\" → $said · 인식 시작 ${if (recognitionStarted) "됨" else "안 됨"} · 마이크 소리 최대 ${"%.1f".format(maxRms)} (${rmsCount}번)")
        // 인식이 아예 시작되지 않았으면(시작 신호·소리 둘 다 없음) 다음 대답부터는 다른 방식으로 듣는다
        // 남은 방식이 없으면, 이 차에서는 음성 대답을 쓸 수 없다고 보고 다음부터는 묻지 않는다 (화면 버튼으로 안내)
        if (!recognitionStarted && rmsCount == 0 && heard.isBlank()) {
            if (modes.isNotEmpty()) {
                log("음성 대답: ${mode?.name} 인식이 시작되지 않아 다음부터 ${modes.first().name} 방식으로 들음")
                main.post { switchToNextMode() }
            } else {
                ready = false
                log("음성 대답: 인식이 시작되지 않아 다음부터는 묻지 않고 화면 버튼으로 안내")
            }
        }
        onListeningChanged?.invoke()
        callback(answer)
    }

    /** 듣는 중이면 그만둔다 (대답은 돌려주지 않는다) */
    fun cancel() {
        if (pending == null) return
        runCatching { stt?.stop() }
        pending = null
        listening = false
        main.removeCallbacks(timeout)
        onListeningChanged?.invoke()
    }

    fun release() {
        cancel()
        stt?.let {
            runCatching { it.removeListener(listener) }
            runCatching { it.release() }
        }
        stt = null
        ready = false
    }

    private companion object {
        /** 이 시간 안에 대답이 없으면 그만 듣는다 */
        const val LISTEN_MS = 6_000L
        /** 서버 인식(HYBRID) 사용 여부: Pleos STT SDK 승인 전이라 꺼 둔다 */
        const val SERVER_MODES_OPEN = false

        /**
         * 도착 전에 "경유지로 넣을까요?"라고 묻고 대답을 들을지. 에뮬레이터에서 인식이 시작되지 않아 확인하지 못했으므로
         * 기본은 끄고, 주행 중 조작이 없도록 설정(도착 전에 세울 곳을 경유지로)대로 바로 넣는다. 실차에서 확인되면 켠다.
         */
        const val ASK_BY_VOICE = false
    }
}
