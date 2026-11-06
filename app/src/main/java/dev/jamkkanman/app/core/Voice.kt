package dev.jamkkanman.app.core

import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.tts.listener.OnServerConnectionListener
import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import java.util.Locale
import ai.pleos.playground.tts.TextToSpeech as PleosTts
import android.speech.tts.TextToSpeech as AndroidTts

/**
 * 한국어 음성 안내.
 * Pleos TextToSpeech SDK를 기기 내 합성(ON_DEVICE) → 서버 합성(SERVER, 앱 등록 정보가 있을 때) → HYBRID 순서로 시도하고,
 * 모두 안 되면 안드로이드 기본 음성 엔진을 쓴다.
 * 엔진이 준비되기 전에 들어온 말은 마지막 한 문장만 남겨 두었다가 준비되면 읽는다.
 *
 * @param clientId, clientSecret Pleos Playground My Project의 앱 등록 정보 (local.properties에서 읽음, 저장소에는 올리지 않음)
 */
class Voice(
    context: Context,
    private val clientId: String,
    private val clientSecret: String,
    private val onStatus: (String) -> Unit,
) {

    private enum class Engine { NONE, PLEOS, ANDROID }

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var engine = Engine.NONE
    private var pleos: PleosTts? = null
    private var android: AndroidTts? = null
    private var waiting: String? = null
    /** 지금 읽는 말이 끝나면 부를 일 (새 말이 들어오면 이전 것은 버린다: 그 말은 끊겼으므로) */
    private var doneCallback: (() -> Unit)? = null
    private var lastText: String? = null
    private var fellBack = false
    private val modes: ArrayDeque<Mode> = ArrayDeque(
        listOfNotNull(Mode.ON_DEVICE, Mode.SERVER.takeIf { clientId.isNotBlank() && clientSecret.isNotBlank() }, Mode.HYBRID)
    )
    private var currentMode: Mode? = null
    private val history = ArrayDeque<String>()

    /** null: 준비 중, true: 사용 가능, false: 사용 불가(화면으로만 안내) */
    var available: Boolean? = null
        private set

    /** 내비 안내 음성과 같은 용도로 내보내 음악 소리를 잠깐 줄인다 */
    private val guidance: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val pleosEvents = object : EventListener {
        override fun onStart() {
            main.post { status("음성 안내 중 (Pleos ${currentMode?.name})") }
        }

        override fun onReady() {}

        override fun onDone() {
            main.post {
                status("음성 안내 끝 (Pleos ${currentMode?.name})")
                finishDone()
            }
        }

        override fun onError(errorType: ErrorType) {
            main.post {
                status("Pleos ${currentMode?.name} 오류: ${errorType.name}")
                // 이 방식으로 읽지 못하면 다음 방식으로 바꿔 같은 말을 다시 읽는다
                tryNextMode(repeatLast = true)
            }
        }

        override fun onStop() {}

        override fun onUpdatedRms(rms: Double) {}
    }

    init {
        status("Pleos 음성 서비스: ${speechServiceVersion() ?: "설치 안 됨"}")
        tryNextMode(repeatLast = false)
    }

    /** Pleos 음성 서비스 앱(ai.pleos.playground.caas) 버전. 없으면 null */
    private fun speechServiceVersion(): String? = try {
        @Suppress("DEPRECATION")
        app.packageManager.getPackageInfo(SPEECH_SERVICE_PACKAGE, 0).versionName ?: "버전 모름"
    } catch (_: Throwable) {
        null
    }

    private fun status(message: String) {
        history.addLast(message)
        while (history.size > 4) history.removeFirst()
        onStatus(history.joinToString(" → "))
    }

    /** 다음 Pleos 방식을 시도한다. 남은 방식이 없으면 안드로이드 음성으로 넘어간다. */
    private fun tryNextMode(repeatLast: Boolean) {
        releasePleos()
        engine = Engine.NONE
        if (repeatLast) waiting = lastText
        val mode = modes.removeFirstOrNull()
        if (mode == null) {
            fallBackToAndroid()
            return
        }
        currentMode = mode
        try {
            val tts = PleosTts(app, mode)
            pleos = tts
            // initialize()가 내부 엔진을 만든 뒤에야 리스너를 붙일 수 있다 (순서를 바꾸면 예외)
            tts.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    main.post { onPleosInitialized(tts, mode) }
                }

                override fun onFailure(errorType: ErrorType) {
                    main.post {
                        status("Pleos ${mode.name} 초기화 실패: ${errorType.name}")
                        tryNextMode(repeatLast = false)
                    }
                }
            })
            tts.addEventListener(pleosEvents)
        } catch (t: Throwable) {
            status("Pleos ${mode.name} 사용 불가: ${t.javaClass.simpleName}")
            tryNextMode(repeatLast = false)
        }
    }

    private fun onPleosInitialized(tts: PleosTts, mode: Mode) {
        if (tts !== pleos) return
        runCatching { tts.setLocale(Locale.KOREA) }
        runCatching { tts.setAudioAttributes(guidance) }
        if (mode == Mode.SERVER) {
            // 서버 합성은 앱 등록(Client ID/Secret)이 끝난 뒤에 쓸 수 있다
            tts.registerApp(clientId, clientSecret, object : OnServerConnectionListener {
                override fun onConnected() {
                    main.post { markPleosReady(mode) }
                }

                override fun onFailed(errorType: ErrorType) {
                    main.post {
                        status("Pleos 앱 등록 실패: ${errorType.name}")
                        tryNextMode(repeatLast = false)
                    }
                }
            })
        } else {
            markPleosReady(mode)
        }
    }

    private fun markPleosReady(mode: Mode) {
        engine = Engine.PLEOS
        available = true
        status("음성 안내 준비됨 (Pleos ${mode.name})")
        flushWaiting()
    }

    private fun fallBackToAndroid() {
        if (fellBack) return
        fellBack = true
        releasePleos()
        engine = Engine.NONE
        android = AndroidTts(app) { status ->
            main.post { onAndroidInit(status) }
        }
    }

    private fun onAndroidInit(status: Int) {
        val tts = android ?: return
        if (status != AndroidTts.SUCCESS) {
            available = false
            status("안드로이드 음성 엔진도 없어 화면으로만 알려요")
            finishDone()
            return
        }
        val lang = tts.setLanguage(Locale.KOREAN)
        if (lang == AndroidTts.LANG_MISSING_DATA || lang == AndroidTts.LANG_NOT_SUPPORTED) {
            available = false
            status("안드로이드 한국어 음성이 없어 화면으로만 알려요")
            finishDone()
            return
        }
        tts.setAudioAttributes(guidance)
        tts.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) {
                main.post { finishDone() }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { finishDone() }
            }
        })
        engine = Engine.ANDROID
        available = true
        status("음성 안내 준비됨 (안드로이드)")
        flushWaiting()
    }

    /** @param onDone 다 읽은 뒤 부른다 (음성을 못 내면 바로 부른다). 묻고 나서 대답을 들을 때 쓴다 */
    fun say(text: String, onDone: (() -> Unit)? = null) {
        lastText = text
        doneCallback = onDone
        when (engine) {
            Engine.PLEOS -> speakPleos(text)
            Engine.ANDROID -> android?.speak(text, AndroidTts.QUEUE_FLUSH, null, "jamkkanman-${System.nanoTime()}")
            Engine.NONE -> if (available == false) finishDone() else waiting = text // 준비 전에는 마지막 말만 남긴다
        }
    }

    private fun finishDone() {
        val callback = doneCallback ?: return
        doneCallback = null
        callback()
    }

    private fun speakPleos(text: String) {
        val tts = pleos ?: return
        try {
            tts.stop() // 이전 안내가 남아 있으면 끊고 새 안내를 읽는다
            tts.speak(text, true)
        } catch (t: Throwable) {
            status("Pleos 음성 요청 실패: ${t.javaClass.simpleName}")
            tryNextMode(repeatLast = true)
        }
    }

    private fun flushWaiting() {
        val text = waiting ?: return
        waiting = null
        val callback = doneCallback
        say(text, callback)
    }

    private fun releasePleos() {
        val tts = pleos ?: return
        runCatching { tts.removeEventListener(pleosEvents) }
        runCatching { tts.release() }
        pleos = null
    }

    fun shutdown() {
        releasePleos()
        android?.let {
            runCatching { it.stop() }
            runCatching { it.shutdown() }
        }
        android = null
    }

    companion object {
        private const val SPEECH_SERVICE_PACKAGE = "ai.pleos.playground.caas"
    }
}
