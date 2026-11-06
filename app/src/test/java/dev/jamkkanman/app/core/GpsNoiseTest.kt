package dev.jamkkanman.app.core

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

/**
 * GPS 오차 시험 (몬테카를로): 위치가 틀리게 잡혀도 안내가 얼마나 맞는지 숫자로 본다.
 *
 * 방법
 * - 대치동 학원가(첫 시연 지점) 반경 2km 안의 실제 금지구역(어린이보호구역 제외)에서 하나를 고르고,
 *   중심에서 0~(반경+15m) 떨어진 '진짜 위치'를 만든다. 진짜 위치로 판단한 결과를 정답으로 둔다.
 * - 진짜 위치에 동서·남북으로 각각 표준편차 σ인 오차를 더한 '잡힌 위치'로 앱이 판단한다.
 *   GPS가 알려 주는 정확도 값은 1.5σ(오차가 이 안에 들 확률 약 68%)로 넣는다.
 * - 결과를 확실한 경고 / 가능성 / 조용 세 가지로 나눠 센다. '가능성' 단계를 없앤 경우(여유 0m)도 같이 센다.
 *
 * 숫자는 결과 표(표준 출력)로 남고, 기획서의 정확도 근거로 쓴다. 같은 씨앗값이라 매번 같은 숫자가 나온다.
 */
class GpsNoiseTest {

    private data class Tally(var n: Int = 0, var certain: Int = 0, var maybe: Int = 0, var quiet: Int = 0, var quietNoMargin: Int = 0) {
        fun pct(x: Int) = if (n == 0) 0.0 else 100.0 * x / n
    }

    private fun classify(r: CheckResult): Int = when {
        r.top == null -> 0
        r.top!!.certain -> 2
        else -> 1
    }

    @Test
    fun `GPS 오차에 따른 놓침과 오경보`() {
        val all = ZoneRepository.parse(DemoDataTest.assetFile("zones_daechi.json").readText())
        val center = all.demo.first()
        val local = all.zones.filter { Geo.distanceM(center.lat, center.lon, it.lat, it.lon) < 2_000 }
        val data = all.copy(zones = local)
        val checker = ZoneChecker(data)
        val candidates = local.filter { it.type != ZoneType.SCHOOL_ZONE }
        val kx = 111_320.0 * cos(Math.toRadians(center.lat))

        val report = StringBuilder("\n[GPS 오차 시험] 대치동 학원가 반경 2km · 금지구역 ${candidates.size}곳에서 표본 추출\n")
        report.append("σ(m) | 진짜 안: 확실 / 가능성 / 놓침 (가능성 단계 없으면 놓침) | 진짜 밖: 오경보 / 가능성 / 조용\n")

        val rows = mutableMapOf<Double, Pair<Tally, Tally>>()
        for (sigma in listOf(2.0, 3.0, 5.0)) {
            val rnd = Random(20_261_106L + sigma.toLong())
            val inside = Tally()
            val outside = Tally()
            repeat(TRIALS) {
                val z = candidates[rnd.nextInt(candidates.size)]
                val angle = rnd.nextDouble() * 2 * Math.PI
                val dist = rnd.nextDouble() * (z.radiusM + 15.0)
                val tLat = z.lat + dist * sin(angle) / 110_540.0
                val tLon = z.lon + dist * cos(angle) / kx
                val truth = checker.check(Fix(tLat, tLon, 0f, "정답", 0), hour = 14)
                if (!truth.covered) return@repeat
                val nLat = tLat + rnd.nextGaussian() * sigma / 110_540.0
                val nLon = tLon + rnd.nextGaussian() * sigma / kx
                val seen = classify(checker.check(Fix(nLat, nLon, (1.5 * sigma).toFloat(), "잡힌 위치", 0), hour = 14))
                val seenNoMargin = classify(checker.check(Fix(nLat, nLon, 0f, "잡힌 위치", 0), hour = 14))
                val t = if (truth.hits.any { it.certain }) inside else outside
                t.n++
                when (seen) {
                    2 -> t.certain++
                    1 -> t.maybe++
                    else -> t.quiet++
                }
                if (seenNoMargin == 0) t.quietNoMargin++
            }
            rows[sigma] = inside to outside
            report.append(
                "%.0f | %.1f%% / %.1f%% / %.1f%% (%.1f%%) | %.1f%% / %.1f%% / %.1f%%   (표본 안 %d · 밖 %d)\n".format(
                    sigma,
                    inside.pct(inside.certain), inside.pct(inside.maybe), inside.pct(inside.quiet), inside.pct(inside.quietNoMargin),
                    outside.pct(outside.certain), outside.pct(outside.maybe), outside.pct(outside.quiet),
                    inside.n, outside.n,
                )
            )
        }
        println(report)

        // 오차 3m(도심 차량 GPS 수준)에서 '가능성' 단계가 놓침을 줄이는지, 놓침이 충분히 낮은지 본다
        val (inside3, outside3) = rows.getValue(3.0)
        assertTrue("가능성 단계가 놓침을 줄여야 함", inside3.quiet < inside3.quietNoMargin)
        assertTrue("σ=3m 놓침 ${inside3.pct(inside3.quiet)}%", inside3.pct(inside3.quiet) < 10.0)
        assertTrue("σ=3m 오경보 ${outside3.pct(outside3.certain)}%", outside3.pct(outside3.certain) < 20.0)
    }

    private companion object {
        const val TRIALS = 3_000
    }
}
