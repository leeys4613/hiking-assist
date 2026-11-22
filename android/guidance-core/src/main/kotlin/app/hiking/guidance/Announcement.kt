package app.hiking.guidance

enum class Priority { URGENT, JUNCTION, EVENT, PERIODIC }

data class Announcement(
    val key: String,
    val priority: Priority,
    val text: String,
    val createdMs: Long,
)

/**
 * 안내 대기열(명세 §5.2): 우선순위 순, 비긴급 안내 간 최소 간격, 같은 key 중복 금지.
 * 시간은 모두 호출자가 넣어 주므로(테스트 가능) 내부에서 시계를 읽지 않는다.
 */
class AnnouncementQueue(
    private val minGapMs: Long = 20_000,
    private val periodicExpiryMs: Long = 45_000,
    private val eventExpiryMs: Long = 180_000,
) {
    private val items = mutableListOf<Announcement>()
    private var lastEndMs: Long = Long.MIN_VALUE / 2

    val size: Int get() = items.size

    fun offer(a: Announcement) {
        // 대기 중인 정기 안내는 최신 것 하나만 유지
        if (a.priority == Priority.PERIODIC) items.removeAll { it.priority == Priority.PERIODIC }
        if (items.none { it.key == a.key }) items += a
    }

    /** 지금 말할 안내를 꺼낸다. 없으면 null. 호출자는 말하기가 끝나면 [onSpeechEnded] 를 불러야 한다. */
    fun next(nowMs: Long): Announcement? {
        items.removeAll {
            val age = nowMs - it.createdMs
            (it.priority == Priority.PERIODIC && age > periodicExpiryMs) ||
                (it.priority != Priority.URGENT && it.priority != Priority.PERIODIC && age > eventExpiryMs)
        }
        val a = items.minWithOrNull(compareBy({ it.priority.ordinal }, { it.createdMs })) ?: return null
        if (a.priority != Priority.URGENT && nowMs - lastEndMs < minGapMs) return null
        items.remove(a)
        return a
    }

    fun onSpeechEnded(nowMs: Long) { lastEndMs = nowMs }
}
