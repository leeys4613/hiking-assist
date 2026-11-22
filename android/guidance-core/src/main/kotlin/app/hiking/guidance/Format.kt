package app.hiking.guidance

import kotlin.math.roundToInt

/** TTS 가 자연스럽게 읽도록 만드는 문구 포맷터(명세 §5.2 포맷 규칙). */
object Fmt {
    /** 1km 미만은 50m 단위, 1km 이상은 0.1km 단위. 50m 미만은 "50미터" 로 올린다. */
    fun distance(meters: Double): String {
        val m50 = ((meters / 50.0).roundToInt() * 50).coerceAtLeast(50)
        if (m50 < 1000) return "${m50}미터"
        val tenths = (meters / 100.0).roundToInt()
        val whole = tenths / 10
        val frac = tenths % 10
        return if (frac == 0) "${whole}킬로" else "${whole}.${frac}킬로"
    }

    /** 5분 단위 반올림(최소 5분), 1시간 이상은 "1시간 20분". */
    fun duration(seconds: Double): String {
        val m = ((seconds / 60.0 / 5.0).roundToInt() * 5).coerceAtLeast(5)
        if (m < 60) return "${m}분"
        val h = m / 60
        val mm = m % 60
        return if (mm == 0) "${h}시간" else "${h}시간 ${mm}분"
    }

    /** 킬로당 N분. 1분 단위 반올림. */
    fun pace(secPerKm: Double): String = "킬로당 ${(secPerKm / 60.0).roundToInt().coerceAtLeast(1)}분"

    /** 받침 유무에 따라 주격 조사(이/가)를 붙인다. */
    fun subject(word: String): String = word + if (hasBatchim(word)) "이" else "가"

    private fun hasBatchim(word: String): Boolean {
        val c = word.lastOrNull { !it.isWhitespace() } ?: return false
        if (c in '가'..'힣') return (c - '가') % 28 != 0
        return false
    }
}
