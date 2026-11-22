package app.hiking.guidance

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/** 재경로 계산 결과(명세 §5.4). */
sealed interface ReroutePlan {
    /** 다른 등산로 위에 있고 목표까지 길이 있음. [polyline] 은 현재 위치부터 목표 지점까지 [lat, lon, ele] */
    data class Alt(val polyline: List<List<Double>>, val distM: Double, val etaS: Double) : ReroutePlan
    /** 어떤 등산로에도 있지 않음. 가장 가까운 등산로의 방위(0=북, 시계방향)와 거리. 등산로가 없으면 null */
    data class OffTrail(val bearingDeg: Double?, val distM: Double?) : ReroutePlan
    /** 등산로 위에 있지만 폐쇄 구간 때문에 목표까지 갈 수 없음 */
    data object NoRoute : ReroutePlan
}

/**
 * 오프라인 재경로: 현재 위치를 그래프 엣지에 투영(20m 이내)하고, 폐쇄 엣지를 제외한 그래프에서
 * 목표(정상 또는 종점)까지 예상 시간(Tobler) 가중치 다익스트라로 최단 경로를 구한다.
 */
class Rerouter(graph: Graph, summitLat: Double, summitLon: Double, endLat: Double, endLon: Double) {
    private class Edge(val id: Int, val from: Int, val to: Int, g: List<List<Double>>) {
        val lat = DoubleArray(g.size) { g[it][0] }
        val lon = DoubleArray(g.size) { g[it][1] }
        val ele = DoubleArray(g.size) { g[it].getOrElse(2) { 0.0 } }
        val cum = DoubleArray(g.size)
        init { for (i in 1 until g.size) cum[i] = cum[i - 1] + Geo.distance(lat[i - 1], lon[i - 1], lat[i], lon[i]) }
        val length get() = cum.last()

        fun at(arr: DoubleArray, d: Double): Double {
            val x = d.coerceIn(0.0, length)
            var lo = 0; var hi = cum.size - 1
            while (hi - lo > 1) { val m = (lo + hi) / 2; if (cum[m] <= x) lo = m else hi = m }
            val span = cum[hi] - cum[lo]
            val f = if (span <= 0) 0.0 else (x - cum[lo]) / span
            return arr[lo] + (arr[hi] - arr[lo]) * f
        }

        /** a 에서 b 까지 걷는 시간(초). a>b 면 반대 방향(경사 부호가 뒤집힌다). 10m 단위로 적분 */
        fun seconds(a: Double, b: Double): Double {
            val step = 10.0
            val n = maxOf(1, (abs(b - a) / step).toInt() + 1)
            var t = 0.0
            for (i in 0 until n) {
                val d0 = a + (b - a) * i / n; val d1 = a + (b - a) * (i + 1) / n
                val len = abs(d1 - d0)
                if (len <= 0) continue
                t += len / Tobler.mps((at(ele, d1) - at(ele, d0)) / len)
            }
            return t
        }

        /** a→b 구간 점들(양 끝은 보간). [lat, lon, ele] */
        fun slice(a: Double, b: Double): List<List<Double>> {
            fun pt(d: Double) = listOf(at(lat, d), at(lon, d), at(ele, d))
            val out = mutableListOf(pt(a))
            if (a <= b) { for (i in cum.indices) if (cum[i] > a && cum[i] < b) out += listOf(lat[i], lon[i], ele[i]) }
            else { for (i in cum.indices.reversed()) if (cum[i] < a && cum[i] > b) out += listOf(lat[i], lon[i], ele[i]) }
            out += pt(b)
            return out
        }

        /** 점에서 이 엣지까지의 (엣지 위 거리, 수직 거리) */
        fun project(pLat: Double, pLon: Double): Pair<Double, Double> {
            val mLat = Math.PI / 180.0 * Geo.EARTH_R
            val mLon = mLat * cos(Math.toRadians(pLat))
            var bestOff = Double.MAX_VALUE; var bestD = 0.0
            for (i in 0 until cum.size - 1) {
                val ax = (lon[i] - pLon) * mLon; val ay = (lat[i] - pLat) * mLat
                val dx = (lon[i + 1] - lon[i]) * mLon; val dy = (lat[i + 1] - lat[i]) * mLat
                val l2 = dx * dx + dy * dy
                val t = if (l2 <= 0) 0.0 else (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0)
                val off = hypot(ax + dx * t, ay + dy * t)
                if (off < bestOff) { bestOff = off; bestD = cum[i] + (cum[i + 1] - cum[i]) * t }
            }
            return bestD to bestOff
        }
    }

    private val nodeLat = HashMap<Int, Double>()
    private val nodeLon = HashMap<Int, Double>()
    private val edges: List<Edge>          // 폐쇄 엣지는 아예 만들지 않는다 → 투영/경로 어디에도 쓰이지 않는다
    private val adj = HashMap<Int, MutableList<Pair<Edge, Boolean>>>()   // 노드 → (엣지, 이 노드에서 from→to 방향인가)
    private val summitNode: Int?
    private val endNode: Int?

    init {
        graph.nodes.forEach { nodeLat[it.id] = it.lat; nodeLon[it.id] = it.lon }
        edges = graph.edges.filter { !it.closed && it.geometry.size >= 2 && it.from in nodeLat && it.to in nodeLat }
            .map { Edge(it.id, it.from, it.to, it.geometry) }
        for (e in edges) {
            adj.getOrPut(e.from) { mutableListOf() } += e to true
            adj.getOrPut(e.to) { mutableListOf() } += e to false
        }
        fun nearestNode(lat: Double, lon: Double): Int? =
            nodeLat.keys.minByOrNull { Geo.distance(lat, lon, nodeLat[it]!!, nodeLon[it]!!) }
                ?.takeIf { Geo.distance(lat, lon, nodeLat[it]!!, nodeLon[it]!!) <= TARGET_SNAP_M }
        summitNode = nearestNode(summitLat, summitLon)
        endNode = nearestNode(endLat, endLon)
    }

    /** 이 그래프로 목표까지 재경로를 계산할 수 있는가(목표 지점이 그래프 노드와 이어져 있는가) */
    fun usable(toSummit: Boolean) = (if (toSummit) summitNode else endNode) != null

    fun plan(lat: Double, lon: Double, toSummit: Boolean): ReroutePlan? {
        val target = (if (toSummit) summitNode else endNode) ?: return null
        // 가장 가까운 (폐쇄 아닌) 엣지
        var near: Edge? = null; var nearD = 0.0; var nearOff = Double.MAX_VALUE
        for (e in edges) { val (d, off) = e.project(lat, lon); if (off < nearOff) { near = e; nearD = d; nearOff = off } }
        if (near == null) return ReroutePlan.OffTrail(null, null)
        if (nearOff > ON_TRAIL_M) {
            val la = near.at(near.lat, nearD); val lo = near.at(near.lon, nearD)
            return ReroutePlan.OffTrail(bearing(lat, lon, la, lo), nearOff)
        }
        return route(near, nearD, target) ?: ReroutePlan.NoRoute
    }

    private fun route(e: Edge, s: Double, target: Int): ReroutePlan.Alt? {
        // 시작: 엣지 위 투영점 → 양 끝 노드
        val dist = HashMap<Int, Double>()
        val prev = HashMap<Int, Pair<Edge, Boolean>?>()   // 노드에 도착한 마지막 엣지(null = 시작 엣지에서 직접)
        val startVia = HashMap<Int, Boolean>()            // 시작 엣지의 어느 쪽 끝에서 출발했나(true = to 쪽)
        val pq = PriorityQueue<Pair<Int, Double>>(compareBy { it.second })
        dist[e.from] = e.seconds(s, 0.0); prev[e.from] = null; startVia[e.from] = false
        dist[e.to] = e.seconds(s, e.length); prev[e.to] = null; startVia[e.to] = true
        pq.add(e.from to dist[e.from]!!); pq.add(e.to to dist[e.to]!!)
        while (pq.isNotEmpty()) {
            val (u, du) = pq.poll()
            if (du > (dist[u] ?: Double.MAX_VALUE)) continue
            if (u == target) break
            for ((ed, fwd) in adj[u] ?: continue) {
                val v = if (fwd) ed.to else ed.from
                val w = if (fwd) ed.seconds(0.0, ed.length) else ed.seconds(ed.length, 0.0)
                if (du + w < (dist[v] ?: Double.MAX_VALUE)) { dist[v] = du + w; prev[v] = ed to fwd; pq.add(v to dist[v]!!) }
            }
        }
        val total = dist[target] ?: return null
        // 경로 복원 (목표 → 시작)
        val legs = ArrayDeque<Pair<Edge, Boolean>>()
        var cur = target
        while (true) { val p = prev[cur] ?: break; legs.addFirst(p); cur = if (p.second) p.first.from else p.first.to }
        val poly = mutableListOf<List<Double>>()
        if (startVia[cur] == true) poly += e.slice(s, e.length) else poly += e.slice(s, 0.0)
        for ((ed, fwd) in legs) {
            val pts = if (fwd) ed.slice(0.0, ed.length) else ed.slice(ed.length, 0.0)
            poly += pts.drop(1)
        }
        var d = 0.0
        for (i in 1 until poly.size) d += Geo.distance(poly[i - 1][0], poly[i - 1][1], poly[i][0], poly[i][1])
        return ReroutePlan.Alt(poly, d, total)
    }

    companion object {
        const val ON_TRAIL_M = 20.0     // 명세 §5.4: 20m 이내 엣지
        const val TARGET_SNAP_M = 60.0  // 정상/종점과 그래프 노드가 이만큼 이내여야 재경로 사용

        /** 0=북, 시계방향 도 */
        fun bearing(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
            val dy = toLat - fromLat
            val dx = (toLon - fromLon) * cos(Math.toRadians(fromLat))
            return (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
        }

        fun compass8(deg: Double): String =
            arrayOf("북", "북동", "동", "남동", "남", "남서", "서", "북서")[(((deg + 22.5) % 360.0) / 45.0).toInt()]
    }
}
