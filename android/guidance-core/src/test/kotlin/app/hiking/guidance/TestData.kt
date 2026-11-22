package app.hiking.guidance

import java.io.File
import kotlin.math.cos

object TestData {
    fun suraksan(): CoursePackage {
        val dir = System.getProperty("testdata.dir") ?: error("testdata.dir not set")
        return CoursePackage.parse(File(dir, "suraksan_package.json").readText())
    }

    /** 코스 위 [dist]m 지점의 좌표 */
    fun pointAt(c: Course, dist: Double): Pair<Double, Double> {
        val d = dist.coerceIn(0.0, c.length)
        var i = 0
        while (i < c.cum.size - 2 && c.cum[i + 1] < d) i++
        val span = c.cum[i + 1] - c.cum[i]
        val f = if (span <= 0) 0.0 else (d - c.cum[i]) / span
        return (c.lat[i] + (c.lat[i + 1] - c.lat[i]) * f) to (c.lon[i] + (c.lon[i + 1] - c.lon[i]) * f)
    }

    fun offsetMeters(lat: Double, lon: Double, northM: Double, eastM: Double): Pair<Double, Double> {
        val mLat = Math.PI / 180 * Geo.EARTH_R
        return (lat + northM / mLat) to (lon + eastM / (mLat * cos(Math.toRadians(lat))))
    }
}
