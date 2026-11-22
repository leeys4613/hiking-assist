package app.hiking.guidance

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertIs

/**
 * 합성 산: x=동쪽(m), y=북쪽(m).
 *   N0(0,0) ─E1─ J(0,1000) ─E2(지름길)─ S(0,2000)
 *                 J ─E3─ B1(300,1000) ─E4─ B2(300,2000) ─E5─ S    (돌아가는 길 1600m)
 */
class RerouterTest {
    private val lat0 = 37.69
    private val lon0 = 127.08
    private val mLat = Math.PI / 180 * Geo.EARTH_R
    private fun ll(x: Double, y: Double) = (lat0 + y / mLat) to (lon0 + x / (mLat * Math.cos(Math.toRadians(lat0))))

    /** 직선 엣지 (50m 간격 점), 고도는 y 에 비례해 오르막 */
    private fun line(x0: Double, y0: Double, x1: Double, y1: Double): List<List<Double>> {
        val n = maxOf(2, (Math.hypot(x1 - x0, y1 - y0) / 50).toInt() + 1)
        return (0 until n).map { i ->
            val f = i / (n - 1.0); val x = x0 + (x1 - x0) * f; val y = y0 + (y1 - y0) * f
            val (la, lo) = ll(x, y); listOf(la, lo, 100 + y * 0.1)
        }
    }

    private fun graph(closed: Set<Int> = emptySet()): Graph {
        val nodes = listOf(
            0 to (0.0 to 0.0), 1 to (0.0 to 1000.0), 2 to (0.0 to 2000.0), 3 to (300.0 to 1000.0), 4 to (300.0 to 2000.0),
        ).map { (id, p) -> val (la, lo) = ll(p.first, p.second); GNode(id, la, lo, 100 + p.second * 0.1) }
        fun e(id: Int, a: Int, b: Int, g: List<List<Double>>) = GEdge(id, a, b, g, closed = id in closed)
        val edges = listOf(
            e(1, 0, 1, line(0.0, 0.0, 0.0, 1000.0)), e(2, 1, 2, line(0.0, 1000.0, 0.0, 2000.0)),
            e(3, 1, 3, line(0.0, 1000.0, 300.0, 1000.0)), e(4, 3, 4, line(300.0, 1000.0, 300.0, 2000.0)),
            e(5, 4, 2, line(300.0, 2000.0, 0.0, 2000.0)),
        )
        return Graph(nodes, edges)
    }

    private fun rerouter(g: Graph) = Rerouter(g, ll(0.0, 2000.0).first, ll(0.0, 2000.0).second, ll(0.0, 0.0).first, ll(0.0, 0.0).second)
    private fun plan(g: Graph, x: Double, y: Double, toSummit: Boolean = true) = rerouter(g).plan(ll(x, y).first, ll(x, y).second, toSummit)

    private fun near(a: List<Double>, x: Double, y: Double, tol: Double = 3.0): Boolean {
        val (la, lo) = ll(x, y); return Geo.distance(a[0], a[1], la, lo) <= tol
    }

    @Test fun `지름길 위에서는 앞으로 가는 짧은 길을 안내`() {
        val p = assertIs<ReroutePlan.Alt>(plan(graph(), 3.0, 1100.0)!!)
        assertTrue(abs(p.distM - 900) < 10, "거리 ${p.distM}")
        assertTrue(near(p.polyline.first(), 0.0, 1100.0, 5.0))
        assertTrue(near(p.polyline.last(), 0.0, 2000.0))
        assertTrue(p.polyline.none { it[1] > ll(100.0, 0.0).second }, "돌아가는 길(동쪽)을 쓰면 안 됨")
        assertTrue(p.etaS > 0)
    }

    @Test fun `갈림길에서 반대로 들어섰으면 되돌아가는 길이 더 짧으면 그쪽`() {
        val p = assertIs<ReroutePlan.Alt>(plan(graph(), 100.0, 1003.0)!!)   // E3 위 동쪽 100m
        assertTrue(abs(p.distM - 1100) < 15, "J 로 돌아가 지름길 ${p.distM}")
        assertTrue(p.polyline.any { near(it, 0.0, 1000.0, 5.0) })
    }

    @Test fun `지름길이 폐쇄면 돌아가는 길을 쓰고 폐쇄 구간 좌표는 하나도 없다`() {
        val g = graph(closed = setOf(2))
        val p = assertIs<ReroutePlan.Alt>(plan(g, 100.0, 1003.0)!!)
        assertTrue(abs(p.distM - 1500) < 20, "돌아가는 길 ${p.distM}")
        val closedPts = g.edges.first { it.id == 2 }.geometry.drop(1).dropLast(1)   // 양 끝 노드는 다른 엣지와 공유
        assertTrue(p.polyline.none { pt -> closedPts.any { c -> Geo.distance(pt[0], pt[1], c[0], c[1]) < 2.0 } })
    }

    @Test fun `폐쇄 엣지 위에 서 있으면 등산로 위가 아닌 것으로 보고 가까운 열린 등산로 방향을 안내`() {
        val p = assertIs<ReroutePlan.OffTrail>(plan(graph(closed = setOf(2)), 2.0, 1100.0)!!)
        assertEquals("남", Rerouter.compass8(p.bearingDeg!!))
        assertTrue(abs(p.distM!! - 100) < 5)
    }

    @Test fun `목표까지 열린 길이 없으면 NoRoute`() {
        // 지름길(2)과 돌아가는 길의 마지막(5)이 모두 폐쇄 → 정상에 닿을 수 없다
        assertIs<ReroutePlan.NoRoute>(plan(graph(closed = setOf(2, 5)), 100.0, 1003.0)!!)
    }

    @Test fun `등산로에서 멀면 가장 가까운 등산로의 방위와 거리`() {
        val p = assertIs<ReroutePlan.OffTrail>(plan(graph(), -80.0, 1500.0)!!)
        assertEquals("동", Rerouter.compass8(p.bearingDeg!!))
        assertTrue(abs(p.distM!! - 80) < 3)
        val q = assertIs<ReroutePlan.OffTrail>(plan(graph(), 150.0, 1500.0)!!)   // E2(x=0) 와 E4(x=300) 한가운데
        assertTrue(q.distM!! in 140.0..160.0)
    }

    @Test fun `하산 중 목표는 종점`() {
        val p = assertIs<ReroutePlan.Alt>(plan(graph(), 3.0, 1500.0, toSummit = false)!!)
        assertTrue(near(p.polyline.last(), 0.0, 0.0))
        assertTrue(abs(p.distM - 1500) < 15)
    }

    @Test fun `내리막이 오르막보다 빠르다`() {
        val up = assertIs<ReroutePlan.Alt>(plan(graph(), 3.0, 1500.0, toSummit = true)!!)
        val down = assertIs<ReroutePlan.Alt>(plan(graph(), 3.0, 1500.0, toSummit = false)!!)
        assertTrue(up.etaS > 0 && down.etaS > 0 && up.distM < down.distM)
    }

    @Test fun `목표 지점 근처에 그래프 노드가 없으면 재경로 불가(null)`() {
        val (la, lo) = ll(5000.0, 5000.0)
        val r = Rerouter(graph(), la, lo, ll(0.0, 0.0).first, ll(0.0, 0.0).second)
        assertNull(r.plan(ll(0.0, 1100.0).first, ll(0.0, 1100.0).second, toSummit = true))
        assertNotNull(r.plan(ll(0.0, 1100.0).first, ll(0.0, 1100.0).second, toSummit = false))
    }

    @Test fun `8방위 변환`() {
        val t = mapOf(0.0 to "북", 22.0 to "북", 23.0 to "북동", 45.0 to "북동", 90.0 to "동", 135.0 to "남동", 180.0 to "남",
            225.0 to "남서", 270.0 to "서", 315.0 to "북서", 337.0 to "북서", 338.0 to "북", 359.9 to "북")
        t.forEach { (deg, name) -> assertEquals(name, Rerouter.compass8(deg), "$deg 도") }
    }
}
