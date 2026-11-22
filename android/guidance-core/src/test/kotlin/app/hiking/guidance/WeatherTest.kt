package app.hiking.guidance

import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeatherTest {
    private val base = TestData.suraksan()

    private fun ms(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    private fun w(vararg p: Pair<String, Int>) = p.map { (h, pop) -> WeatherPoint("2026-11-21T$h:00+09:00", pop) }

    /** [startIso] 에 출발해 [minutes] 분 걷는 동안 나온 강수 안내 (분 단위 시각, 문구) */
    private fun rain(weather: List<WeatherPoint>, startIso: String, minutes: Int = 120, settings: GuidanceSettings = GuidanceSettings(), speed: Double = 1.0): List<Pair<Int, String>> {
        val pkg = base.copy(weather = weather)
        val e = GuidanceEngine(pkg, settings)
        val c = Course(pkg)
        val t0 = ms(startIso)
        val out = mutableListOf<Pair<Int, String>>()
        var d = 0.0; var t = t0
        while (t - t0 < minutes * 60_000L && d < c.summitDist - 200) {
            val (la, lo) = TestData.pointAt(c, d)
            e.onFix(Fix(t, la, lo, 5f)).filter { it.key.startsWith("rain") }.forEach { out += ((t - t0) / 60_000).toInt() to it.text }
            d += speed * 3; t += 3000
        }
        return out
    }

    @Test fun `강수확률 60퍼센트 이상 시간대 1시간 전에 한 번 알려준다`() {
        val ev = rain(w("13" to 20, "14" to 30, "15" to 70, "16" to 80, "17" to 20), "2026-11-21T13:30:00+09:00")
        assertEquals(1, ev.size, ev.toString())
        assertEquals("1시간 뒤 강수확률 70퍼센트예요.", ev[0].second)
        assertTrue(ev[0].first in 30..31, "14:00(출발 30분 뒤)에 나와야 함: ${ev[0].first}분")
    }

    @Test fun `연속된 비 시간대는 한 번만 말한다`() {
        val ev = rain(w("15" to 70, "16" to 80, "17" to 90), "2026-11-21T13:30:00+09:00", minutes = 200)
        assertEquals(1, ev.size, ev.toString())
    }

    @Test fun `떨어진 비 시간대는 각각 알려준다`() {
        val ev = rain(w("14" to 70, "15" to 10, "16" to 10, "17" to 80), "2026-11-21T12:30:00+09:00", minutes = 230, speed = 0.3)   // 느리게 걸어 4시간 가까이 산에 있는 경우
        assertEquals(2, ev.size, ev.toString())
        assertTrue(ev[1].second.contains("80퍼센트"))
    }

    @Test fun `이미 비 시간대에 출발하면 시작할 때 현재 확률을 말한다`() {
        val ev = rain(w("15" to 70, "16" to 85), "2026-11-21T16:20:00+09:00", minutes = 30)
        assertEquals(1, ev.size, ev.toString())
        assertEquals("지금 강수확률 85퍼센트 예보예요.", ev[0].second)
        assertEquals(0, ev[0].first)
    }

    @Test fun `이미 지나간 비 시간대는 말하지 않는다`() {
        assertTrue(rain(w("09" to 90, "10" to 90), "2026-11-21T13:00:00+09:00", minutes = 60).isEmpty())
    }

    @Test fun `임계값은 60퍼센트 이상`() {
        assertTrue(rain(w("15" to 59), "2026-11-21T14:10:00+09:00", minutes = 30).isEmpty())
        assertEquals(1, rain(w("15" to 60), "2026-11-21T14:10:00+09:00", minutes = 30).size)
    }

    @Test fun `임계값 설정을 바꿀 수 있다`() {
        val ev = rain(w("15" to 40), "2026-11-21T14:10:00+09:00", minutes = 30, settings = GuidanceSettings(rainPopThreshold = 40))
        assertEquals(1, ev.size)
    }

    @Test fun `설정에서 끄면 말하지 않는다`() {
        assertTrue(rain(w("15" to 90), "2026-11-21T14:10:00+09:00", minutes = 30, settings = GuidanceSettings(weatherEnabled = false)).isEmpty())
    }

    @Test fun `예보가 없으면 아무 말도 하지 않는다`() {
        assertTrue(rain(emptyList(), "2026-11-21T14:10:00+09:00", minutes = 30).isEmpty())
    }

    @Test fun `1시간 전보다 이르면 아직 말하지 않는다`() {
        // 14:00 출발, 비는 16:00 → 15:00 이전에는 조용
        val ev = rain(w("16" to 70), "2026-11-21T14:00:00+09:00", minutes = 55)
        assertTrue(ev.isEmpty(), ev.toString())
    }

    @Test fun `서버가 내려주는 weather JSON 을 읽는다`() {
        val json = """{"package_version":1,"mountain":{"id":"m","name":"산"},
            "course":{"id":"c","name":"c","polyline":[[37.0,127.0,10.0],[37.001,127.0,20.0]],"summit_index":1,"end_index":1},
            "weather":[{"time":"2026-11-21T13:00+09:00","pop":10},{"time":"2026-11-21T14:00+09:00","pop":70}],"weather_status":"ok"}"""
        val p = CoursePackage.parse(json)
        assertEquals(2, p.weather.size)
        assertEquals(70, p.weather[1].pop)
        assertEquals("ok", p.weatherStatus)
    }

    @Test fun `시각 형식이 틀린 점은 건너뛴다`() {
        val ev = rain(listOf(WeatherPoint("어제", 90), WeatherPoint("2026-11-21T15:00+09:00", 80)), "2026-11-21T14:10:00+09:00", minutes = 30)
        assertEquals(1, ev.size)
    }
}
