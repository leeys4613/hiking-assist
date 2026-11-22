package app.hiking.guidance

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    @Test fun `1km 미만은 50m 단위로 반올림`() {
        assertEquals("300미터", Fmt.distance(300.0))
        assertEquals("300미터", Fmt.distance(322.0))
        assertEquals("350미터", Fmt.distance(326.0))
        assertEquals("950미터", Fmt.distance(974.0))
    }

    @Test fun `50m 미만은 50미터로 올림`() {
        assertEquals("50미터", Fmt.distance(10.0))
        assertEquals("50미터", Fmt.distance(0.0))
    }

    @Test fun `1km 이상은 0점1km 단위`() {
        assertEquals("2.3킬로", Fmt.distance(2300.0))
        assertEquals("2.3킬로", Fmt.distance(2349.0))
        assertEquals("2.4킬로", Fmt.distance(2351.0))
        assertEquals("2킬로", Fmt.distance(2000.0))
        assertEquals("1킬로", Fmt.distance(1000.0))
    }

    @Test fun `경계 근처 반올림이 1킬로로 넘어감`() {
        assertEquals("1킬로", Fmt.distance(990.0)) // 50m 단위로 1000 → 킬로 표기
    }

    @Test fun `시간은 5분 단위 반올림`() {
        assertEquals("55분", Fmt.duration(55 * 60.0))
        assertEquals("55분", Fmt.duration(56 * 60.0))
        assertEquals("1시간 20분", Fmt.duration(80 * 60.0))
        assertEquals("1시간", Fmt.duration(60 * 60.0))
        assertEquals("2시간 10분", Fmt.duration(131 * 60.0))
        assertEquals("5분", Fmt.duration(30.0))
    }

    @Test fun `페이스 문구`() {
        assertEquals("킬로당 22분", Fmt.pace(22 * 60.0))
        assertEquals("킬로당 22분", Fmt.pace(22.4 * 60))
    }

    @Test fun `주격 조사 이와 가`() {
        assertEquals("벤치가", Fmt.subject("벤치"))
        assertEquals("영원암이", Fmt.subject("영원암"))
        assertEquals("철모바위가", Fmt.subject("철모바위"))
        assertEquals("쉼터가", Fmt.subject("쉼터"))
        assertEquals("정상이", Fmt.subject("정상"))
    }
}
