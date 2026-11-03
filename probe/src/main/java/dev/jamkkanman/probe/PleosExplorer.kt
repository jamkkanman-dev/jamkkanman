package dev.jamkkanman.probe

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * Pleos SDK를 '공개 API 이름'으로만 찾아 호출한다(공식 샘플 앱의 API 카탈로그와 같은 방식).
 * - Vehicle SDK: 어떤 API 묶음과 메서드가 있는지 목록화, 브레이크·기어 관련 값 읽기/구독
 * - NaviHelper SDK: 경로 옵션 관련 클래스와 값 목록화
 * 타입을 직접 쓰지 않아서, 이름이 예상과 달라도 빌드는 깨지지 않고 결과만 기록된다.
 */
class PleosExplorer(
    private val context: Context,
    private val report: Report,
    private val onBrake: (Boolean) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var vehicle: Any? = null
    private val apis = linkedMapOf<String, Pair<Any, Class<*>>>()
    private val registrations = mutableListOf<Triple<Any, Method, Array<Any?>>>()

    fun start() {
        if (vehicle != null) return
        val vehicleClass = try {
            Class.forName("ai.pleos.playground.vehicle.Vehicle")
        } catch (t: Throwable) {
            report.log("Vehicle SDK 클래스를 찾지 못했어요: ${t.javaClass.simpleName}")
            return
        }
        try {
            val instance = vehicleClass.getConstructor(Context::class.java).newInstance(context.applicationContext)
            vehicleClass.getMethod("initialize").invoke(instance)
            vehicle = instance
            report.log("── Pleos Vehicle SDK ── 초기화 요청 완료")
        } catch (t: Throwable) {
            report.log("Vehicle 초기화 실패: ${t.rootMessage()}")
            return
        }

        // API 묶음(getBrake() 등) 목록
        vehicleClass.methods
            .filter { it.parameterCount == 0 && it.name.startsWith("get") && it.returnType.name.startsWith("ai.pleos") }
            .sortedBy { it.name }
            .forEach { getter ->
                val result: Any? = try {
                    getter.invoke(vehicle)
                } catch (t: Throwable) {
                    report.log("${getter.name}() 실패: ${t.rootMessage()}")
                    null
                }
                val api = result ?: return@forEach
                apis[getter.name] = api to getter.returnType
            }
        report.log("Vehicle API 묶음 ${apis.size}개: ${apis.keys.joinToString { it.removePrefix("get") }}")

        // 전체 메서드 목록은 파일에만 기록
        report.bulk("")
        report.bulk("===== Vehicle SDK 공개 메서드 목록 =====")
        for ((getter, pair) in apis) {
            val methods = publicMethods(pair.second)
            report.bulk("[${getter.removePrefix("get")}] ${pair.second.name} (${methods.size}개)")
            methods.forEach { report.bulk("    ${signature(it)}") }
        }

        val gearMethods = apis.flatMap { (getter, pair) ->
            publicMethods(pair.second).filter { it.name.contains("Gear", ignoreCase = true) }.map { "${getter.removePrefix("get")}.${it.name}" }
        }
        report.log(
            if (gearMethods.isEmpty()) "기어 관련 Pleos 메서드: 없음 (기어는 Android 표준 GEAR_SELECTION으로 읽음)"
            else "기어 관련 Pleos 메서드: ${gearMethods.joinToString()}"
        )

        // 서비스 연결 시간을 조금 준 뒤 값 읽기·구독
        handler.postDelayed({ readAndSubscribe() }, 1_500)
    }

    /** 브레이크·기어 관련 '현재값 읽기'와 '구독'을 시도한다. */
    fun readAndSubscribe() {
        if (vehicle == null) {
            report.log("먼저 Vehicle SDK를 초기화해야 해요.")
            return
        }
        unregisterAll()
        var tried = 0
        for ((getter, pair) in apis) {
            val (api, type) = pair
            publicMethods(type)
                .filter { m ->
                    val n = m.name
                    (n.contains("BrakePedal", ignoreCase = true) || n.contains("Gear", ignoreCase = true)) &&
                        (n.startsWith("getCurrent") || n.startsWith("register"))
                }
                .forEach { m ->
                    tried++
                    invokeWithCallbacks("${getter.removePrefix("get")}.${m.name}", api, m, type)
                }
        }
        if (tried == 0) report.log("브레이크·기어 관련 읽기/구독 메서드를 찾지 못했어요.")
    }

    /** NaviHelper SDK에서 경로 옵션 관련 클래스·값을 찾는다. */
    fun exploreNavi() {
        val names = try {
            @Suppress("DEPRECATION")
            dalvik.system.DexFile(context.packageCodePath).entries().toList()
        } catch (t: Throwable) {
            report.log("앱 클래스 목록을 읽지 못했어요: ${t.rootMessage()}")
            return
        }
        val pleosNames = names.filter { it.startsWith("ai.pleos") && !it.startsWith("ai.pleos.playground.vehicle") }
        report.log("── NaviHelper 등 Pleos SDK ── 클래스 ${pleosNames.size}개 (전체 목록은 보고서 파일)")
        report.bulk("")
        report.bulk("===== Vehicle 외 Pleos SDK 클래스·공개 메서드 =====")
        val interesting = mutableListOf<String>()
        for (name in pleosNames.sorted()) {
            val cls = try {
                Class.forName(name, false, context.classLoader)
            } catch (_: Throwable) {
                continue
            }
            if (!Modifier.isPublic(cls.modifiers)) continue
            report.bulk("[${cls.name}]${if (cls.isEnum) " (enum)" else ""}")
            if (cls.isEnum) {
                val constants = cls.enumConstants?.joinToString { it.toString() } ?: ""
                report.bulk("    값: $constants")
                if (name.contains("Route", true) || name.contains("Option", true) || name.contains("Avoid", true)) {
                    interesting += "${cls.simpleName}: $constants"
                }
            } else {
                publicMethods(cls).forEach { report.bulk("    ${signature(it)}") }
                cls.fields.filter { Modifier.isStatic(it.modifiers) && it.name.contains("ROUTE", true) }.forEach {
                    val v = runCatching { it.get(null) }.getOrNull()
                    report.bulk("    상수 ${it.name} = $v")
                    interesting += "${cls.simpleName}.${it.name} = $v"
                }
            }
        }
        report.log(
            if (interesting.isEmpty()) "경로 옵션 관련 값: 찾지 못함 (보고서 파일의 전체 목록 확인 필요)"
            else "경로 옵션 관련 값:\n  " + interesting.joinToString("\n  ")
        )
    }

    fun release() {
        unregisterAll()
        val v = vehicle ?: return
        try {
            v.javaClass.getMethod("release").invoke(v)
        } catch (_: Throwable) {
        }
        vehicle = null
        apis.clear()
    }

    // ---------------------------------------------------------------------------------------

    private fun invokeWithCallbacks(label: String, api: Any, method: Method, apiType: Class<*>) {
        val args = arrayOfNulls<Any>(method.parameterCount)
        method.parameterTypes.forEachIndexed { i, t ->
            args[i] = when {
                t.name == "kotlin.jvm.functions.Function0" -> { val f: () -> Unit = { handle(label, emptyList()) }; f }
                t.name == "kotlin.jvm.functions.Function1" -> { val f: (Any?) -> Unit = { a -> handle(label, listOf(a)) }; f }
                t.name == "kotlin.jvm.functions.Function2" -> { val f: (Any?, Any?) -> Unit = { a, b -> handle(label, listOf(a, b)) }; f }
                t.isInterface -> listenerProxy(label, t)
                else -> {
                    report.log("$label: 건너뜀 (영역 등 추가 값 필요: ${t.simpleName})")
                    return
                }
            }
        }
        try {
            method.invoke(api, *args)
            report.log("$label: 호출함")
            if (method.name.startsWith("register")) {
                val unregister = apiType.methods.firstOrNull {
                    it.name == method.name.replaceFirst("register", "unregister") &&
                        it.parameterTypes.contentEquals(method.parameterTypes)
                }
                if (unregister != null) registrations += Triple(api, unregister, args)
            }
        } catch (t: Throwable) {
            report.log("$label: 실패 ${t.rootMessage()}")
        }
    }

    private fun listenerProxy(label: String, type: Class<*>): Any =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, m, margs ->
            when (m.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === margs?.getOrNull(0)
                "toString" -> "JamkkanmanProbe.$label"
                else -> {
                    handle("$label.${m.name}", margs?.toList() ?: emptyList())
                    null
                }
            }
        }

    private fun handle(label: String, values: List<Any?>) {
        val text = values.joinToString { v ->
            when (v) {
                is Throwable -> "오류(${v.javaClass.simpleName}: ${v.message})"
                null -> "null"
                else -> "$v (${v.javaClass.simpleName})"
            }
        }
        report.log("Pleos $label → $text")
        if (label.contains("Brake", ignoreCase = true)) {
            values.firstOrNull { it is Boolean }?.let { onBrake(it as Boolean) }
        }
    }

    private fun unregisterAll() {
        registrations.forEach { (api, method, args) ->
            try {
                method.invoke(api, *args)
            } catch (_: Throwable) {
            }
        }
        registrations.clear()
    }

    private fun publicMethods(type: Class<*>): List<Method> =
        type.methods
            .filter { it.declaringClass.name.startsWith("ai.pleos") && !it.isSynthetic }
            .distinctBy { signature(it) }
            .sortedBy { it.name }

    private fun signature(m: Method): String =
        "${m.name}(${m.parameterTypes.joinToString { it.simpleName }}): ${m.returnType.simpleName}"

    private fun Throwable.rootMessage(): String {
        var t: Throwable = this
        while (t is InvocationTargetException && t.targetException != null) t = t.targetException
        while (t.cause != null && t.cause !== t) t = t.cause!!
        return "${t.javaClass.simpleName}: ${t.message}"
    }
}
