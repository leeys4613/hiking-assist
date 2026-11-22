package app.hiking.guidance

enum class DepartureState { ON_COURSE, SUSPECT, OFF_COURSE, RECOVERING }

enum class Transition { NONE, BECAME_OFF, BACK_ON }

/** 이탈 판정 파라미터(명세 §5.3). */
data class DepartureSettings(
    /** 정확도가 이보다 나쁜 fix 는 판정에서 제외 (엔진이 입력 단계에서 걸러낸다) */
    val accuracyMaxM: Float = 50f,
    val offDistM: Double = 30.0,
    val offHoldS: Long = 15,
    val onDistM: Double = 15.0,
    val onHoldS: Long = 10,
)

/**
 * 코스 이탈 상태 머신: ON_COURSE → SUSPECT → OFF_COURSE → RECOVERING → ON_COURSE.
 * 임계값을 넘은 상태가 지속 시간 이상 이어져야 전환하고(디바운스), 복귀 임계값은 이탈 임계값보다 작아서
 * 경계에서 상태가 떨리지 않는다(히스테리시스). 시간은 fix 의 시각만 쓴다.
 */
class DepartureDetector(private val s: DepartureSettings = DepartureSettings()) {
    var state = DepartureState.ON_COURSE
        private set
    private var since = 0L

    /** [offsetM]: 코스까지의 거리. 전환이 일어났으면 알려준다. */
    fun update(timeMs: Long, offsetM: Double): Transition {
        when (state) {
            DepartureState.ON_COURSE -> if (offsetM > s.offDistM) { state = DepartureState.SUSPECT; since = timeMs }
            DepartureState.SUSPECT -> when {
                offsetM <= s.offDistM -> state = DepartureState.ON_COURSE
                timeMs - since >= s.offHoldS * 1000 -> { state = DepartureState.OFF_COURSE; return Transition.BECAME_OFF }
            }
            DepartureState.OFF_COURSE -> if (offsetM <= s.onDistM) { state = DepartureState.RECOVERING; since = timeMs }
            DepartureState.RECOVERING -> when {
                offsetM > s.onDistM -> state = DepartureState.OFF_COURSE
                timeMs - since >= s.onHoldS * 1000 -> { state = DepartureState.ON_COURSE; return Transition.BACK_ON }
            }
        }
        return Transition.NONE
    }

    /** 새 경로로 갈아탄 뒤에는 그 경로 위에 있는 것으로 시작한다. */
    fun reset() { state = DepartureState.ON_COURSE }
}
