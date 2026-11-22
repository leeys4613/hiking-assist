package app.hiking.guidance

import kotlin.math.abs
import kotlin.math.exp

/** Tobler 보행 함수. 경사(상승/수평거리, 오르막 +)에서 걷는 속도(m/s). server/app/pace.py 와 같은 식. */
object Tobler {
    fun mps(grade: Double): Double = 6.0 * exp(-3.5 * abs(grade + 0.05)) / 3.6
}
