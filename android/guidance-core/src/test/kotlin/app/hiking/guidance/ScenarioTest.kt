package app.hiking.guidance

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GPX 재생 회귀 테스트(명세 §9, AC1·AC2). 시나리오(GPX + 기대 이벤트 JSON)는
 * server/tools/gen_scenarios.py 가 만든다. 기대 이벤트는 코드와 독립적으로 계산한 값이다.
 */
class ScenarioTest {
    private val testdata = File(System.getProperty("testdata.dir") ?: error("testdata.dir not set"))
    private val dir = File(testdata, "scenarios")

    private fun check(name: String) {
        val meta = Json.parseToJsonElement(File(dir, "$name.json").readText()).jsonObject
        val pkgName = meta["package"]!!.jsonPrimitive.content
        val pkgFile = File(dir, pkgName).takeIf { it.exists() } ?: File(testdata, pkgName)
        var pkg = CoursePackage.parse(pkgFile.readText())
        meta["sunset"]?.jsonPrimitive?.contentOrNull?.let { pkg = pkg.copy(sunset = it) }
        val fixes = Replay.readGpx(File(dir, meta["gpx"]!!.jsonPrimitive.content))
        val watch = (meta["watch"] as JsonArray).map { it.jsonPrimitive.content }.toSet()
        val actual = Replay.run(pkg, fixes).filter { it.type in watch }
        val expect = meta["expect"]!!.jsonArray.map { it as JsonObject }
        val timeline = actual.joinToString("\n") { "  %7.1fs %-12s %s".format(it.tS, it.key, it.text) }

        assertEquals(expect.map { it["type"]!!.jsonPrimitive.content }, actual.map { it.type },
            "$name: 이벤트 종류/순서가 다름\n실제:\n$timeline")
        expect.zip(actual).forEachIndexed { i, (e, a) ->
            val at = e["atS"]!!.jsonPrimitive.doubleOrNull!!
            val tol = e["tolS"]?.jsonPrimitive?.doubleOrNull ?: 2.0
            assertTrue(abs(a.tS - at) <= tol, "$name #$i ${a.type}: 기대 ${at}s ±$tol, 실제 ${a.tS}s\n실제:\n$timeline")
            e["textContains"]?.jsonPrimitive?.contentOrNull?.let {
                assertTrue(a.text.contains(it), "$name #$i ${a.type}: '${a.text}' 에 '$it' 없음")
            }
        }
    }

    @Test fun `1 정상 완주 - 이탈 오경보 0건 하산 권고 없음`() = check("clean_full")
    @Test fun `2 GPS 튐 - 순간 점프와 부정확한 fix 로는 이탈이 일어나지 않는다`() = check("gps_jump")
    @Test fun `3 실제 이탈 - 30m 넘게 20초면 15초 시점에 이탈 1회 그리고 복귀`() = check("real_departure")
    @Test fun `4 다른 코스로 진입 - 대안 경로 안내 1회 이후 그 경로로 정상 하산`() = check("alt_route")
    @Test fun `5 등산로 밖 - 30초마다 방향 안내 그리고 복귀`() = check("off_trail")
    @Test fun `6 폐쇄 구간 - 폐쇄 길로 안내하지 않고 열린 등산로 방향을 안내`() = check("closed_edge")
    @Test fun `7 일몰 임박 - 하산 권고 1회`() = check("sunset_late")

    @Test fun `시나리오 파일은 모두 테스트가 있다`() {
        val names = dir.listFiles { f -> f.name.endsWith(".json") && !f.name.endsWith("_package.json") }!!.map { it.nameWithoutExtension }.toSet()
        assertEquals(setOf("clean_full", "gps_jump", "real_departure", "alt_route", "off_trail", "closed_edge", "sunset_late"), names)
    }
}
