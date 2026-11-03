package dev.jamkkanman.app.core

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 한국어 음성 안내. 엔진이 준비되기 전에 들어온 말은 모아 두었다가 준비되면 읽는다.
 * 내비 안내 음성과 같은 용도(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)로 내보내 음악 소리를 잠깐 줄인다.
 */
class Voice(context: Context, private val onStatus: (String) -> Unit) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context.applicationContext, this)
    private val waiting = ArrayDeque<String>()
    private var ready = false

    /** null: 준비 중, true: 사용 가능, false: 사용 불가(화면으로만 안내) */
    var available: Boolean? = null
        private set

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            available = false
            onStatus("음성 엔진을 쓸 수 없어 화면으로만 알려요")
            return
        }
        val lang = tts.setLanguage(Locale.KOREAN)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            available = false
            onStatus("한국어 음성이 없어 화면으로만 알려요")
            return
        }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        ready = true
        available = true
        onStatus("음성 안내 준비됨")
        while (waiting.isNotEmpty()) speakNow(waiting.removeFirst())
    }

    fun say(text: String) {
        when {
            ready -> speakNow(text)
            available != false -> {
                waiting.clear() // 준비 전에 여러 번 들어오면 마지막 말만 남긴다
                waiting.addLast(text)
            }
        }
    }

    private fun speakNow(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jamkkanman-${System.nanoTime()}")
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
