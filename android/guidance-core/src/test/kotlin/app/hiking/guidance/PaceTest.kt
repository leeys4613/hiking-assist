package app.hiking.guidance

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PaceTest {
    private val pkg = TestData.suraksan()
    private val ref = Json.parseToJsonElement(
        File(System.getProperty("testdata.dir"), "pace_reference.json").readText()
    ).jsonObject

    private fun referenceModel(): PaceModel =
        Json { ignoreUnknownKeys = true }.decodeFromString(PaceModel.serializer(), ref["model"].toString())

    // ---- AC7: Python 기준 모델 ↔ Kotlin 남은 시간 (명세 §9 선택 항목 채택) ----

    @Test fun `AC7 기본 Tobler 남은 시간이 Python 과 1분 이내`() {
        val c = Course(pkg)
        for (case in ref["cases"]!!.jsonArray) {
            val o = case.jsonObject
            val a = o["from"]!!.jsonPrimitive.doubleOrNull!!; val b = o["to"]!!.jsonPrimitive.doubleOrNull!!
            val py = o["default_s"]!!.jsonPrimitive.doubleOrNull!!
            val kt = c.etaSeconds(a, b)
            assertTrue(abs(kt - py) <= 60.0, "$a→$b Python ${py}s vs Kotlin ${kt}s")
            assertTrue(abs(kt - py) <= 2.0, "같은 알고리즘이므로 사실상 일치해야 함: $a→$b 차이 ${kt - py}s")
        }
    }

    @Test fun `AC7 개인 모델 남은 시간이 Python 과 1분 이내`() {
        val c = Course(pkg.copy(paceModel = referenceModel()))
        for (case in ref["cases"]!!.jsonArray) {
            val o = case.jsonObject
            val a = o["from"]!!.jsonPrimitive.doubleOrNull!!; val b = o["to"]!!.jsonPrimitive.doubleOrNull!!
            val py = o["personal_s"]!!.jsonPrimitive.doubleOrNull!!
            val kt = c.etaSeconds(a, b)
            assertTrue(abs(kt - py) <= 60.0, "$a→$b Python ${py}s vs Kotlin ${kt}s")
            assertTrue(abs(kt - py) <= 2.0, "$a→$b 차이 ${kt - py}s")
        }
    }

    @Test fun `개인 모델은 시간을 바꾼다`() {
        val base = Course(pkg).etaSeconds(0.0, Course(pkg).length)
        val personal = Course(pkg.copy(paceModel = referenceModel())).etaSeconds(0.0, Course(pkg).length)
        assertNotEquals(base, personal)
        assertTrue(personal > base * 1.2)
    }

    @Test fun `경사 bin 은 Python bisect_right 와 같다`() {
        val b = GradeBins(listOf(-0.30, -0.15, -0.05, 0.05, 0.15, 0.30), List(7) { it.toDouble() })
        val got = listOf(-0.5, -0.30, -0.2, -0.1, 0.0, 0.1, 0.2, 0.5).map { b.binOf(it) }
        assertEquals(listOf(0, 1, 1, 2, 3, 4, 5, 6), got)
    }

    @Test fun `서버가 내려주는 JSON 모양을 그대로 읽는다`() {
        val json = """{"package_version":1,"mountain":{"id":"m","name":"산"},
            "course":{"id":"c","name":"c","polyline":[[37.0,127.0,10.0],[37.001,127.0,20.0]],"summit_index":1,"end_index":1},
            "sunset":"2026-11-21T17:16:48+09:00",
            "pace_model":{"type":"tobler_personal","factor":1.26,"grade_bins":{"edges":[-0.3,-0.15,-0.05,0.05,0.15,0.3],
              "factors":[1.37,1.33,1.23,1.12,1.22,1.32,1.36]},"samples_s":[747,1276,1466,1357,1881,1619,847],"n_hikes":1,"typical_summit_s":4400}}"""
        val p = CoursePackage.parse(json)
        assertEquals(1.26, p.paceModel!!.factor)
        assertEquals(4400.0, p.paceModel!!.typicalSummitS)
        assertEquals(1.12, p.paceModel!!.factorFor(0.0))
        assertEquals(1.37, p.paceModel!!.factorFor(-0.4))
        assertEquals(GuidanceEngine.parseSunset(p.sunset), java.time.OffsetDateTime.parse("2026-11-21T17:16:48+09:00").toInstant().toEpochMilli())
    }

    // ---- 정상 안내: 평소와 비교 ----

    private fun summitText(typicalS: Double?): String {
        val p = pkg.copy(paceModel = typicalS?.let { PaceModel(typicalSummitS = it) })
        val e = GuidanceEngine(p)
        val c = Course(p)
        var d = 0.0; var t = 0L
        while (d <= c.summitDist + 5) {
            val (la, lo) = TestData.pointAt(c, d)
            e.onFix(Fix(t, la, lo, 5f)).firstOrNull { it.key == "summit" }?.let { return it.text }
            d += 0.9 * 3; t += 3000
        }
        error("정상 안내가 나오지 않음")
    }

    @Test fun `평소 기록이 없으면 비교 없이 걸린 시간만`() {
        val t = summitText(null)
        assertTrue(t.startsWith("정상이에요. ") && t.endsWith("걸렸어요.") && !t.contains("평소"), t)
    }

    @Test fun `평소보다 빠르면 5분 단위로 알려준다`() {
        // 0.9m/s 로 정상(4365m)까지 약 4850초(80분)에 도착. 평소 5400초(90분) → 약 10분 빠름
        val t = summitText(5400.0)
        assertTrue(Regex("^정상이에요\\. .* 걸렸고, 평소보다 (5|10|15)분 빨라요\\.$").matches(t), t)
    }

    @Test fun `평소보다 느리면 느리다고`() {
        val t = summitText(3600.0)
        assertTrue(Regex("^정상이에요\\. .* 걸렸고, 평소보다 \\d+분 느려요\\.$").matches(t), t)
    }

    @Test fun `평소와 5분 이내 차이면 비슷하다고`() {
        val c = Course(pkg)
        val arriveS = (c.summitDist - 30) / 0.9      // 정상 반경 30m 에 들어서는 시각
        val t = summitText(arriveS + 60)
        assertTrue(t.endsWith("걸렸고, 평소와 비슷해요."), t)
    }

    // ---- 정기 안내: 상대 페이스(선택, 기본 꺼짐) ----

    private fun periodics(model: PaceModel?, enabled: Boolean): List<String> {
        val p = pkg.copy(paceModel = model)
        val e = GuidanceEngine(p, GuidanceSettings(relativePaceEnabled = enabled))
        val c = Course(p)
        val out = mutableListOf<String>()
        var d = 0.0; var t = 0L
        while (d < 2500) {
            val (la, lo) = TestData.pointAt(c, d)
            out += e.onFix(Fix(t, la, lo, 5f)).filter { it.key == "periodic" }.map { it.text }
            d += 0.9 * 3; t += 3000
        }
        return out
    }

    @Test fun `상대 페이스는 기본으로 꺼져 있다`() {
        assertTrue(periodics(PaceModel(factor = 2.0), enabled = false).none { it.contains("평소") })
    }

    @Test fun `모델이 예상보다 실제가 빠르면 빨라요`() {
        val txt = periodics(PaceModel(factor = 2.0), enabled = true)   // 모델은 2배 느리게 예상 → 실제는 훨씬 빠름
        assertTrue(txt.any { Regex("평소보다 \\d+퍼센트 빨라요\\.").containsMatchIn(it) }, txt.joinToString("\n"))
    }

    @Test fun `모델이 예상보다 실제가 느리면 느려요`() {
        val txt = periodics(PaceModel(factor = 0.4), enabled = true)
        assertTrue(txt.any { Regex("평소보다 \\d+퍼센트 느려요\\.").containsMatchIn(it) }, txt.joinToString("\n"))
    }

    @Test fun `개인 모델이 없으면 켜도 말하지 않는다`() {
        assertTrue(periodics(null, enabled = true).none { it.contains("평소") })
    }
}
