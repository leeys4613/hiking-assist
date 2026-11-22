package app.hiking.guidance

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CourseTest {
    private val pkg = TestData.suraksan()
    private val course = Course(pkg)

    @Test fun `패키지 파싱과 기본 수치`() {
        assertEquals("수락산", pkg.mountain.name)
        assertTrue(course.length in 8600.0..8900.0, "왕복 길이 ${course.length}")
        assertTrue(abs(course.summitDist - course.length / 2) < 30, "정상은 코스 중간")
        assertEquals(course.length, course.endDist, 0.5)
    }

    @Test fun `왕복 겹침 구간에서도 hint 로 올라가는 길과 내려오는 길을 구분`() {
        var hint: Double? = null
        var worst = 0.0
        var worstAt = 0.0
        var worstGot = 0.0
        var d = 0.0
        while (d <= course.length) {
            val (la, lo) = TestData.pointAt(course, d)
            val reached = d >= course.summitDist
            val pr = if (reached) course.project(la, lo, hint, minDist = course.summitDist)
                     else course.project(la, lo, hint, maxDist = course.summitDist + 60)
            if (abs(pr.dist - d) > worst) { worst = abs(pr.dist - d); worstAt = d; worstGot = pr.dist }
            hint = pr.dist
            d += 4.0
        }
        assertTrue(worst < 8.0, "최대 오차 ${worst}m @d=$worstAt got=$worstGot summit=${course.summitDist}")
    }

    @Test fun `코스에서 20m 떨어진 점의 offset`() {
        val (la, lo) = TestData.pointAt(course, 1500.0)
        val (la2, lo2) = TestData.offsetMeters(la, lo, 0.0, 20.0)
        val pr = course.project(la2, lo2, 1500.0)
        assertTrue(pr.offsetM in 5.0..21.0, "offset ${pr.offsetM}")
        assertTrue(abs(pr.dist - 1500.0) < 40)
    }

    @Test fun `예상 시간은 경사 기반 - 오르막이 같은 길이의 내리막보다 오래 걸림`() {
        val up = course.etaSeconds(0.0, course.summitDist)
        val down = course.etaSeconds(course.summitDist, course.endDist)
        assertTrue(up > down, "오르막 $up 초 vs 내리막 $down 초")
        // 4.4km, 누적상승 약 640m: 대략 1시간 10분~2시간
        assertTrue(up in 4200.0..7200.0, "오르막 소요 ${up / 60}분")
    }

    @Test fun `페이스 계수가 시간에 비례`() {
        val base = course.etaSeconds(0.0, 2000.0)
        assertEquals(base * 1.5, course.etaSeconds(0.0, 2000.0, 1.5), 1e-6)
        assertEquals(0.0, course.etaSeconds(2000.0, 1000.0))
    }
}
