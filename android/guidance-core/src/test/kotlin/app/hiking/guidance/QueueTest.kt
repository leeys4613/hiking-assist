package app.hiking.guidance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class QueueTest {
    private fun a(key: String, p: Priority, t: Long = 0) = Announcement(key, p, key, t)

    @Test fun `우선순위 순으로 나온다`() {
        val q = AnnouncementQueue()
        q.offer(a("periodic", Priority.PERIODIC)); q.offer(a("poi", Priority.EVENT)); q.offer(a("junc", Priority.JUNCTION))
        assertEquals("junc", q.next(0)?.key)
    }

    @Test fun `비긴급 안내는 직전 안내 종료 후 20초가 지나야 나온다`() {
        val q = AnnouncementQueue()
        q.offer(a("e1", Priority.EVENT, 0)); q.offer(a("e2", Priority.EVENT, 0))
        assertEquals("e1", q.next(1_000)?.key)
        q.onSpeechEnded(5_000)
        assertNull(q.next(24_999))
        assertEquals("e2", q.next(25_000)?.key)
    }

    @Test fun `긴급은 간격을 무시한다`() {
        val q = AnnouncementQueue()
        q.onSpeechEnded(1_000)
        q.offer(a("off", Priority.URGENT, 1_500))
        assertEquals("off", q.next(2_000)?.key)
    }

    @Test fun `같은 key 중복은 한 번만, 정기 안내는 최신 하나만 유지`() {
        val q = AnnouncementQueue()
        q.offer(a("e", Priority.EVENT)); q.offer(a("e", Priority.EVENT))
        q.offer(Announcement("periodic", Priority.PERIODIC, "old", 0)); q.offer(Announcement("periodic", Priority.PERIODIC, "new", 1))
        assertEquals(2, q.size)
        q.next(0); assertEquals("new", q.next(10_000)?.text)
    }

    @Test fun `오래된 정기 안내는 버린다`() {
        val q = AnnouncementQueue()
        q.offer(a("periodic", Priority.PERIODIC, 0))
        assertNull(q.next(60_000))
        assertEquals(0, q.size)
    }
}
