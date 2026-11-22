package app.hiking.guidance

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/** 코스 위 투영 결과. [dist] 는 코스 시작점부터의 누적 거리(m), [offsetM] 는 코스까지의 수직 거리(m). */
data class Projection(val dist: Double, val offsetM: Double)

class Course(pkg: CoursePackage) {
    val lat: DoubleArray
    val lon: DoubleArray
    val ele: DoubleArray
    /** i번째 점까지의 누적 거리(m) */
    val cum: DoubleArray
    val length: Double
    val summitIndex: Int
    val summitDist: Double
    val endDist: Double
    val summitLat: Double
    val summitLon: Double
    val endLat: Double
    val endLon: Double

    /** i번째 점까지의 누적 예상 소요 시간(초), 페이스 계수 1.0 기준 */
    private val timeCum: DoubleArray

    init {
        val pl = pkg.course.polyline
        require(pl.size >= 2) { "polyline needs at least 2 points" }
        lat = DoubleArray(pl.size) { pl[it][0] }
        lon = DoubleArray(pl.size) { pl[it][1] }
        ele = DoubleArray(pl.size) { pl[it].getOrElse(2) { 0.0 } }
        cum = DoubleArray(pl.size)
        for (i in 1 until pl.size) cum[i] = cum[i - 1] + Geo.distance(lat[i - 1], lon[i - 1], lat[i], lon[i])
        length = cum.last()
        val si = pkg.course.summitIndex.coerceIn(0, pl.size - 1)
        summitIndex = si
        val ei = pkg.course.endIndex.coerceIn(0, pl.size - 1)
        summitDist = cum[si]; summitLat = lat[si]; summitLon = lon[si]
        endDist = cum[ei]; endLat = lat[ei]; endLon = lon[ei]

        timeCum = DoubleArray(pl.size)
        for (i in 1 until pl.size) {
            val segLen = cum[i] - cum[i - 1]
            val mid = (cum[i] + cum[i - 1]) / 2
            // DEM 노이즈를 피하려고 구간 중앙 ±25m 의 평균 경사를 쓴다
            val g = (eleAt(mid + 25) - eleAt(mid - 25)) / (min(mid + 25, length) - max(mid - 25, 0.0)).coerceAtLeast(1.0)
            timeCum[i] = timeCum[i - 1] + segLen / Tobler.mps(g)
        }
    }

    private fun interp(arr: DoubleArray, dist: Double): Double {
        val d = dist.coerceIn(0.0, length)
        var lo = 0
        var hi = cum.size - 1
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (cum[m] <= d) lo = m else hi = m }
        val span = cum[hi] - cum[lo]
        val f = if (span <= 0) 0.0 else (d - cum[lo]) / span
        return arr[lo] + (arr[hi] - arr[lo]) * f
    }

    fun eleAt(dist: Double): Double = interp(ele, dist)

    /** [from] 에서 [to] 까지(from<to) 걷는 데 걸리는 예상 시간(초). 구간별 경사(Tobler) 합산. */
    fun etaSeconds(from: Double, to: Double, paceFactor: Double = 1.0): Double {
        if (to <= from) return 0.0
        return (interp(timeCum, to) - interp(timeCum, from)) * paceFactor
    }

    /**
     * 좌표를 코스에 투영한다. 왕복 코스는 올라가는 길과 내려오는 길이 겹치고 정상 반환점에서는
     * 지오메트리만으로 방향을 알 수 없으므로, 호출자가 진행 단계에 맞는 탐색 범위 [minDist]..[maxDist] 를 준다
     * (정상 전: 0..정상+60, 정상 후: 정상..끝). [hintDist] 근처(뒤 [back]m ~ 앞 [fwd]m)를 먼저 찾고,
     * 거기서 코스 50m 안에 없을 때만 범위 전체에서 찾는다.
     */
    fun project(
        pLat: Double, pLon: Double, hintDist: Double?,
        back: Double = 30.0, fwd: Double = 250.0,
        minDist: Double = 0.0, maxDist: Double = length,
    ): Projection {
        if (hintDist != null) {
            val w = search(pLat, pLon, max(minDist, hintDist - back), min(maxDist, hintDist + fwd), hintDist)
            if (w != null && w.offsetM <= 50.0) return w
        }
        return search(pLat, pLon, minDist, maxDist, hintDist) ?: search(pLat, pLon, 0.0, length, hintDist)!!
    }

    private fun search(pLat: Double, pLon: Double, d0: Double, d1: Double, hint: Double?): Projection? {
        val cosLat = cos(Math.toRadians(pLat))
        val mPerDegLat = Math.PI / 180.0 * Geo.EARTH_R
        val mPerDegLon = mPerDegLat * cosLat
        var lo = 0
        var hi = cum.size - 1
        while (hi - lo > 1) { val m = (lo + hi) / 2; if (cum[m] <= d0) lo = m else hi = m }
        var bestOff = Double.MAX_VALUE
        var bestDist = 0.0
        var i = lo
        // 같은 오프셋(겹친 왕복 구간)은 진행 방향(hint 이상)에서 가장 가까운 쪽을 고른다.
        // 단순히 hint 에 가까운 쪽을 고르면 정상 반환점에서 올라가는 길의 거울상을 따라가 버린다.
        fun tieCost(dist: Double): Double =
            if (hint == null) dist else if (dist >= hint - 15.0) abs(dist - hint) else abs(dist - hint) + 1000.0
        while (i < cum.size - 1 && cum[i] <= d1) {
            val ax = (lon[i] - pLon) * mPerDegLon; val ay = (lat[i] - pLat) * mPerDegLat
            val bx = (lon[i + 1] - pLon) * mPerDegLon; val by = (lat[i + 1] - pLat) * mPerDegLat
            val dx = bx - ax; val dy = by - ay
            val l2 = dx * dx + dy * dy
            val span = cum[i + 1] - cum[i]
            // 탐색 범위 [d0, d1] 밖의 점이 후보가 되지 않도록 구간 안에서 t 를 자른다
            val tLo = if (span <= 0) 0.0 else ((d0 - cum[i]) / span).coerceIn(0.0, 1.0)
            val tHi = if (span <= 0) 1.0 else ((d1 - cum[i]) / span).coerceIn(0.0, 1.0)
            val t = (if (l2 <= 0) 0.0 else -(ax * dx + ay * dy) / l2).coerceIn(tLo, tHi)
            val px = ax + dx * t; val py = ay + dy * t
            val off = Math.sqrt(px * px + py * py)
            val dist = cum[i] + (cum[i + 1] - cum[i]) * t
            // 같은 길(30m 이내) 위의 후보는 오프셋 차 0.5m 초과일 때만 작은 쪽이 이기고, 멀리 떨어진 후보(왕복 겹침)는
            // 3m 이내면 같은 오프셋으로 보고 tieCost(진행 방향 우선)로 정한다
            val sameStretch = bestOff != Double.MAX_VALUE && abs(dist - bestDist) <= 30.0
            val tol = if (sameStretch) 0.5 else 3.0
            val better = off < bestOff - tol || (abs(off - bestOff) <= tol && tieCost(dist) < tieCost(bestDist))
            if (better) { bestOff = off; bestDist = dist }
            i++
        }
        return if (bestOff == Double.MAX_VALUE) null else Projection(bestDist, bestOff)
    }

    companion object {
        /** [lat, lon, ele] 점들의 길이(m) */
        fun lengthOf(pts: List<List<Double>>): Double {
            var d = 0.0
            for (i in 1 until pts.size) d += Geo.distance(pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1])
            return d
        }
    }
}
