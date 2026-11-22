package app.hiking.guidance

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 실제 수락산 코스 위를 걷는 GPS 스트림을 재생해 안내 이벤트를 검증한다(폰 불필요). */
class ReplayTest {
    private val pkg = TestData.suraksan()
    private val course = Course(pkg)

    private class Result(val all: List<Announcement>)

    /**
     * 시작→정상→휴식→하산→종점을 걷는다. [noiseM] 이내 랜덤 오차를 더하고, [jump] 가 있으면 해당 시각에 큰 오차 fix 를 끼운다.
     */
    private fun walk(noiseM: Double = 0.0, speed: Double = 0.9, jump: Boolean = false): Result {
        val engine = GuidanceEngine(course, pkg.mountain.name, pkg.pois)
        val rnd = Random(42)
        val out = mutableListOf<Announcement>()
        var t = 1_000_000L
        var d = 0.0
        var restLeftS = 300
        var i = 0
        while (true) {
            i++
            val (la0, lo0) = TestData.pointAt(course, d)
            val (la, lo) = TestData.offsetMeters(la0, lo0, (rnd.nextDouble() * 2 - 1) * noiseM, (rnd.nextDouble() * 2 - 1) * noiseM)
            out += engine.onFix(Fix(t, la, lo, 8f))
            if (jump && i % 400 == 0) { // 정확도 나쁜 50m 점프는 무시되어야 한다
                val (jla, jlo) = TestData.offsetMeters(la0, lo0, 50.0, 0.0)
                out += engine.onFix(Fix(t + 500, jla, jlo, 90f))
            }
            if (engine.finished) break
            val atSummit = d >= course.summitDist - 5 && d <= course.summitDist + 5 && restLeftS > 0
            if (atSummit) restLeftS -= 3 else d += speed * 3
            t += 3_000
            if (d > course.length + 50) break
        }
        return Result(out)
    }

    @Test fun `이상적인 GPS 한 번 완주 - 핵심 이벤트가 각각 정확히 한 번`() {
        val r = walk().all
        for (k in listOf("start", "summit", "descent", "end")) assertEquals(1, r.count { it.key == k }, "$k 횟수")
        val keys = r.map { it.key }.filter { it != "periodic" }
        assertEquals(keys.size, keys.toSet().size, "중복 안내 있음: $keys")
        assertTrue(keys.indexOf("start") < keys.indexOf("summit"))
        assertTrue(keys.indexOf("summit") < keys.indexOf("descent"))
        assertTrue(keys.indexOf("descent") < keys.indexOf("end"))
    }

    @Test fun `POI 8개가 각각 한 번씩 300m 안쪽에서 안내`() {
        val r = walk().all
        val poiMsgs = r.filter { it.key.startsWith("poi:") }
        assertEquals(pkg.pois.size, poiMsgs.size, poiMsgs.joinToString { it.text })
        assertTrue(poiMsgs.any { it.text.contains("벤치가 있어요") })
        assertTrue(poiMsgs.any { it.text.contains("영원암이 있어요") })
        assertTrue(poiMsgs.all { it.text.contains("미터 앞에") })
    }

    @Test fun `GPS 오차 5m 와 정확도 나쁜 점프가 있어도 이벤트 중복이 없다`() {
        val r = walk(noiseM = 5.0, jump = true).all
        for (k in listOf("start", "summit", "descent", "end")) assertEquals(1, r.count { it.key == k }, "$k 횟수")
        val keys = r.map { it.key }.filter { it != "periodic" }
        assertEquals(keys.size, keys.toSet().size, "중복 안내 있음")
        assertEquals(pkg.pois.size, r.count { it.key.startsWith("poi:") })
    }

    @Test fun `정기 안내는 1km 간격 안팎으로 나오고 문구 형식이 맞다`() {
        val r = walk().all.filter { it.key == "periodic" }
        // 0.9m/s 에서는 1km(약 18분)보다 10분 간격이 먼저 와서 10분마다 나온다 (명세: 1km 또는 10분). 약 16분 휴식 포함 총 2.7시간
        assertTrue(r.size in 12..18, "정기 안내 ${r.size}회")
        val re = Regex("^(정상|입구)까지 [0-9.]+(킬로|미터|미터)?.*남았어요\\.")
        assertTrue(r.all { re.containsMatchIn(it.text) }, r.joinToString("\n") { it.text })
        assertTrue(r.any { it.text.contains("현재 페이스 킬로당") })
        assertTrue(r.first { it.createdMs > 0 }.text.startsWith("정상까지"))
        assertTrue(r.last().text.startsWith("입구까지"))
    }

    @Test fun `정상 안내에 걸린 시간이 들어간다`() {
        val summit = walk().all.first { it.key == "summit" }
        assertTrue(summit.text.startsWith("정상이에요. ") && summit.text.endsWith("걸렸어요."), summit.text)
    }

    @Test fun `정확도 50m 초과 fix 는 완전히 무시`() {
        val e = GuidanceEngine(course, "수락산", pkg.pois)
        val (la, lo) = TestData.pointAt(course, 100.0)
        assertTrue(e.onFix(Fix(0, la, lo, 80f)).isEmpty())
        assertEquals(null, e.progress)
    }

    @Test fun `코스에서 멀리 떨어진 첫 fix 는 진행거리를 만들지 않는다`() {
        val e = GuidanceEngine(course, "수락산", pkg.pois)
        val (la, lo) = TestData.pointAt(course, 100.0)
        val (fa, fo) = TestData.offsetMeters(la, lo, 0.0, 5000.0) // 코스 어느 구간과도 100m 이상 떨어진 곳
        assertTrue(e.onFix(Fix(0, fa, fo, 10f)).isEmpty())
    }

    @Test fun `설정에서 끄면 해당 안내는 나오지 않는다`() {
        val e = GuidanceEngine(course, "수락산", pkg.pois, GuidanceSettings(periodicEnabled = false, poiEnabled = false))
        val out = mutableListOf<Announcement>()
        var d = 0.0; var t = 0L
        while (d < 3000) { val (la, lo) = TestData.pointAt(course, d); out += e.onFix(Fix(t, la, lo, 5f)); d += 3.0; t += 3000 }
        assertTrue(out.none { it.key == "periodic" || it.key.startsWith("poi:") })
        assertEquals(1, out.count { it.key == "start" })
    }

    @Test fun `일정한 속도로 걸으면 현재 페이스가 실제 속도와 5퍼센트 이내`() {
        val e = GuidanceEngine(course, "수락산", pkg.pois)
        var d = 0.0; var t = 0L
        while (d < 1500) { val (la, lo) = TestData.pointAt(course, d); e.onFix(Fix(t, la, lo, 5f)); d += 0.9 * 3; t += 3000 }
        val pace = e.currentPaceSecPerKm()!!
        val truth = 1000.0 / 0.9
        assertTrue(kotlin.math.abs(pace - truth) / truth < 0.05, "페이스 $pace vs 실제 $truth")
    }

    @Test fun `올라가는 길 POI 는 정상 전에 내려오는 길 POI 는 정상 후에만 안내`() {
        val r = walk().all
        val summitAt = r.indexOfFirst { it.key == "summit" }
        val poiIdx = r.withIndex().filter { it.value.key.startsWith("poi:") }
        for ((i, a) in poiIdx) {
            val dist = a.key.substringAfter('@').toDouble()
            if (dist <= course.summitDist) assertTrue(i < summitAt, "${a.key} 가 정상 뒤에 나옴")
            else assertTrue(i > summitAt, "${a.key} 가 정상 앞에 나옴")
        }
    }

    @Test fun `하산 안내 직후에는 같은 내용의 정기 안내가 바로 나오지 않는다`() {
        val r = walk().all
        val di = r.indexOfFirst { it.key == "descent" }
        assertTrue(r.drop(di + 1).takeWhile { it.createdMs - r[di].createdMs < 120_000 }.none { it.key == "periodic" })
    }
}
