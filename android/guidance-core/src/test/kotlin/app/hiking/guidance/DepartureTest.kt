package app.hiking.guidance

import kotlin.test.Test
import kotlin.test.assertEquals

class DepartureTest {
    /** 1초 간격 fix 로 [offsets] 를 먹이고 (초, 전환) 목록을 돌려준다 */
    private fun run(d: DepartureDetector, offsets: List<Double>, t0: Long = 0): List<Pair<Int, Transition>> =
        offsets.mapIndexedNotNull { i, off ->
            val tr = d.update(t0 + i * 1000L, off)
            if (tr == Transition.NONE) null else i to tr
        }

    private fun rep(v: Double, n: Int) = List(n) { v }

    @Test fun `코스 위에 있으면 상태가 변하지 않는다`() {
        val d = DepartureDetector()
        assertEquals(emptyList(), run(d, rep(5.0, 120)))
        assertEquals(DepartureState.ON_COURSE, d.state)
    }

    @Test fun `30m 초과가 15초 이어지면 그 시점에 이탈 한 번`() {
        val d = DepartureDetector()
        // 0초에 처음 31m, 15초째 fix 에서 확정
        val ev = run(d, rep(5.0, 3) + rep(31.0, 20))
        assertEquals(listOf(3 + 15 to Transition.BECAME_OFF), ev)
        assertEquals(DepartureState.OFF_COURSE, d.state)
    }

    @Test fun `14초만 넘으면 이탈이 아니다`() {
        val d = DepartureDetector()
        assertEquals(emptyList(), run(d, rep(31.0, 15) + rep(5.0, 30)))   // 0~14초 초과 후 복귀
        assertEquals(DepartureState.ON_COURSE, d.state)
    }

    @Test fun `순간 50m 점프는 이탈이 아니다`() {
        val d = DepartureDetector()
        assertEquals(emptyList(), run(d, rep(3.0, 10) + listOf(50.0, 55.0) + rep(3.0, 60)))
    }

    @Test fun `이탈 의심 중 코스로 돌아오면 타이머가 처음부터 다시 시작`() {
        val d = DepartureDetector()
        // 10초 초과 → 1초 복귀 → 다시 초과. 두 번째 초과 시작부터 15초 필요
        val ev = run(d, rep(40.0, 10) + listOf(5.0) + rep(40.0, 20))
        assertEquals(listOf(10 + 1 + 15 to Transition.BECAME_OFF), ev)
    }

    @Test fun `복귀는 15m 이내로 10초 이어져야 한다`() {
        val d = DepartureDetector()
        val ev = run(d, rep(40.0, 16) + rep(10.0, 12))
        assertEquals(listOf(15 to Transition.BECAME_OFF, 16 + 10 to Transition.BACK_ON), ev)
        assertEquals(DepartureState.ON_COURSE, d.state)
    }

    @Test fun `히스테리시스 - 15m 초과 30m 이하는 복귀로 치지 않는다`() {
        val d = DepartureDetector()
        val ev = run(d, rep(40.0, 16) + rep(20.0, 60))     // 이탈 후 20m 에 계속 머묾
        assertEquals(listOf(15 to Transition.BECAME_OFF), ev)
        assertEquals(DepartureState.OFF_COURSE, d.state)
    }

    @Test fun `복귀 중 9초만 가깝고 다시 멀어지면 복귀가 취소된다`() {
        val d = DepartureDetector()
        val ev = run(d, rep(40.0, 16) + rep(10.0, 9) + rep(25.0, 5) + rep(10.0, 12))
        // 첫 복귀 시도(9초)는 취소, 두 번째 시도에서 10초 채워 복귀
        assertEquals(listOf(15 to Transition.BECAME_OFF, 16 + 9 + 5 + 10 to Transition.BACK_ON), ev)
    }

    @Test fun `임계값은 설정으로 바꿀 수 있다`() {
        val d = DepartureDetector(DepartureSettings(offDistM = 50.0, offHoldS = 5, onDistM = 20.0, onHoldS = 3))
        val ev = run(d, rep(45.0, 10) + rep(60.0, 8) + rep(10.0, 6))
        assertEquals(listOf(10 + 5 to Transition.BECAME_OFF, 18 + 3 to Transition.BACK_ON), ev)
    }

    @Test fun `reset 하면 코스 위 상태로 시작`() {
        val d = DepartureDetector()
        run(d, rep(40.0, 16))
        d.reset()
        assertEquals(DepartureState.ON_COURSE, d.state)
    }
}
