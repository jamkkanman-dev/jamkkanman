package dev.mylane.probe

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 확인 결과를 한곳에 모은다.
 * - log(): 화면 + 파일 + Logcat
 * - bulk(): 파일 + Logcat만 (메서드 목록처럼 긴 내용)
 */
class Report(private val context: Context) {

    private val all = StringBuilder()
    private val screen = ArrayDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.KOREA)

    /** 화면 갱신 요청. 어느 스레드에서든 불릴 수 있으므로 받는 쪽에서 메인 스레드로 넘긴다. */
    var onChanged: (() -> Unit)? = null

    fun log(message: String) {
        val line = "${timeFormat.format(Date())}  $message"
        synchronized(this) {
            all.appendLine(line)
            screen.addLast(line)
            while (screen.size > MAX_SCREEN_LINES) screen.removeFirst()
        }
        Log.i(TAG, line)
        onChanged?.invoke()
    }

    fun bulk(line: String) {
        synchronized(this) { all.appendLine(line) }
        Log.i(TAG, line)
    }

    fun screenText(): String = synchronized(this) { screen.joinToString("\n") }

    /** 앱 내부 저장소에 저장한다(맥에서 adb run-as로 꺼낼 수 있는 위치). 외부 저장소에도 한 부 남긴다. */
    fun save(): File {
        val text = synchronized(this) { all.toString() }
        val internal = File(context.filesDir, FILE_NAME)
        internal.writeText(text)
        try {
            context.getExternalFilesDir(null)?.let { File(it, FILE_NAME).writeText(text) }
        } catch (_: Throwable) {
        }
        return internal
    }

    /** 맥 터미널에서 보고서를 꺼내는 명령 */
    fun pullCommand(): String {
        val userId = android.os.Process.myUid() / 100_000
        return "~/Library/Android/sdk/platform-tools/adb exec-out run-as ${context.packageName} --user $userId " +
            "cat files/$FILE_NAME > ~/Desktop/pleos-app/$FILE_NAME"
    }

    companion object {
        const val TAG = "MyLaneProbe"
        const val FILE_NAME = "probe-report.txt"
        private const val MAX_SCREEN_LINES = 300
    }
}
