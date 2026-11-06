package dev.jamkkanman.app.core

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
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
class VoiceAnswer(context: Context, private val log: (String) -> Unit) {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var stt: SpeechToText? = null
    private var pending: ((Boolean?) -> Unit)? = null
    private var heard = ""

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

        override fun onStartedRecognition() {}

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

        override fun onUpdatedRms(rms: Double) {}
    }

    private val timeout = Runnable {
        runCatching { stt?.stop() }
        finish(YesNo.parse(heard))
    }

    fun start() {
        if (stt != null) return
        try {
            val engine = SpeechToText(app, Mode.ON_DEVICE)
            stt = engine
            // TextToSpeech와 같이 initialize()로 내부 엔진을 만든 뒤 리스너를 붙인다
            engine.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    main.post {
                        if (engine !== stt) return@post
                        runCatching { engine.setLocale(Locale.KOREA) }
                        ready = true
                        log("음성 대답: Pleos SpeechToText 준비됨 (ON_DEVICE)")
                    }
                }

                override fun onFailure(errorType: ErrorType) {
                    main.post {
                        ready = false
                        log("음성 대답 사용 불가: ${errorType.name} → 묻지 않고 설정대로 처리")
                    }
                }
            })
            engine.addListener(listener)
        } catch (t: Throwable) {
            stt = null
            log("음성 대답 사용 불가: ${t.javaClass.simpleName} → 묻지 않고 설정대로 처리")
        }
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
        log("음성 대답: \"${heard.ifBlank { "(말 없음)" }}\" → $said")
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
    }
}
