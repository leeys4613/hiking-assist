package app.hiking.guidance

data class Fix(val timeMs: Long, val lat: Double, val lon: Double, val accuracyM: Float? = null)

/** 안내 항목별 on/off 와 임계값(명세 §5.2). */
data class GuidanceSettings(
    val periodicEnabled: Boolean = true,
    val periodicDistM: Double = 1000.0,
    val periodicIntervalS: Long = 600,
    val poiEnabled: Boolean = true,
    val poiLeadM: Double = 300.0,
    val summitRadiusM: Double = 30.0,
    val accuracyMaxM: Float = 50f,
    /** 코스에서 이만큼 벗어난 fix 는 진행 거리 갱신에 쓰지 않는다 (이탈 판정은 Phase 3) */
    val maxOffsetForProgressM: Double = 100.0,
    val paceWindowS: Long = 600,
    val minGapMs: Long = 20_000,
)

/**
 * Phase 1 안내 엔진: 시작 / 정기 / POI 접근 / 정상 / 하산 시작 / 종점.
 * 입력은 GPS fix 뿐이고 시계를 읽지 않으므로 JVM 에서 GPX 재생으로 검증한다.
 */
class GuidanceEngine(
    private val course: Course,
    private val mountainName: String,
    private val pois: List<Poi>,
    private val settings: GuidanceSettings = GuidanceSettings(),
) {
    val queue = AnnouncementQueue(minGapMs = settings.minGapMs)

    /** 코스 시작점부터 현재까지의 진행 거리(m). 첫 유효 fix 전에는 null */
    var progress: Double? = null
        private set
    var summitReached = false
        private set
    var descending = false
        private set
    var finished = false
        private set

    private val fired = HashSet<String>()
    private var startMs: Long? = null
    private var lastPeriodicDist = 0.0
    private var lastPeriodicMs = 0L

    private class Sample(val t: Long, val d: Double)
    private val samples = ArrayDeque<Sample>()

    /** fix 를 처리하고, 이번에 새로 만들어진 안내 목록을 돌려준다(대기열에도 들어간다). */
    fun onFix(fix: Fix): List<Announcement> {
        if (finished) return emptyList()
        if (fix.accuracyM != null && fix.accuracyM > settings.accuracyMaxM) return emptyList()

        // 정상 전에는 정상을 넘은 곳으로, 정상 후에는 올라가는 길로 투영되지 않게 범위를 제한한다
        val proj = if (summitReached) {
            course.project(fix.lat, fix.lon, progress, minDist = course.summitDist)
        } else {
            course.project(fix.lat, fix.lon, progress, maxDist = course.summitDist + 60)
        }
        if (progress == null && proj.offsetM > settings.maxOffsetForProgressM) return emptyList()
        if (proj.offsetM <= settings.maxOffsetForProgressM) progress = proj.dist
        val p = progress ?: return emptyList()
        val out = mutableListOf<Announcement>()
        fun emit(key: String, pr: Priority, text: String) {
            if (pr != Priority.PERIODIC && !fired.add(key)) return
            Announcement(key, pr, text, fix.timeMs).also { out += it; queue.offer(it) }
        }

        if (startMs == null) {
            startMs = fix.timeMs; lastPeriodicDist = p; lastPeriodicMs = fix.timeMs
            emit("start", Priority.EVENT,
                "$mountainName 안내를 시작해요. 정상까지 ${Fmt.distance(course.summitDist - p)}, " +
                    "약 ${Fmt.duration(course.etaSeconds(p, course.summitDist))} 걸려요.")
        }
        if (proj.offsetM <= settings.maxOffsetForProgressM) recordSample(fix.timeMs, p)

        // 정상: 반경 안으로 들어오고 진행 거리도 정상 근처일 때(왕복에서 다른 구간 오인 방지)
        if (!summitReached) {
            val dSummit = Geo.distance(fix.lat, fix.lon, course.summitLat, course.summitLon)
            if (dSummit <= settings.summitRadiusM && p >= course.summitDist - 100) {
                summitReached = true
                emit("summit", Priority.EVENT, "정상이에요. ${Fmt.duration((fix.timeMs - startMs!!) / 1000.0)} 걸렸어요.")
            } else if (p >= course.summitDist + settings.summitRadiusM) {
                summitReached = true // 정상 반경을 못 밟고 지나간 경우: 안내 없이 단계만 넘긴다
            }
        }
        if (summitReached && !descending && p >= course.summitDist + settings.summitRadiusM) {
            descending = true
            lastPeriodicDist = p; lastPeriodicMs = fix.timeMs // 하산 안내 직후 같은 내용의 정기 안내가 또 나오지 않게
            emit("descent", Priority.EVENT,
                "하산 시작해요. 입구까지 ${Fmt.distance(course.endDist - p)}, " +
                    "약 ${Fmt.duration(course.etaSeconds(p, course.endDist))} 남았어요.")
        }

        if (settings.poiEnabled) for (poi in pois) {
            val key = "poi:${poi.name}@${poi.courseDistM.toInt()}"
            if (key in fired) continue
            // 왕복 코스: 같은 장소가 올라갈 때/내려올 때 두 번 나온다. 각각 해당 단계에서만 안내한다
            val onDescentLeg = poi.courseDistM > course.summitDist
            if (onDescentLeg != summitReached) continue
            val ahead = poi.courseDistM - p
            if (ahead <= 0) { fired += key; continue } // 이미 지나친 POI 는 늦게 안내하지 않는다
            if (ahead <= settings.poiLeadM) {
                emit(key, Priority.EVENT, "${Fmt.distance(ahead)} 앞에 ${Fmt.subject(poi.name)} 있어요.")
            }
        }

        if (descending && p >= course.endDist - settings.summitRadiusM) {
            finished = true
            emit("end", Priority.EVENT, "입구에 도착했어요. 수고하셨어요.")
            return out
        }

        if (settings.periodicEnabled) {
            val target = if (descending) course.endDist else course.summitDist
            val remaining = target - p
            val due = p - lastPeriodicDist >= settings.periodicDistM ||
                fix.timeMs - lastPeriodicMs >= settings.periodicIntervalS * 1000
            if (due && remaining > 150) {
                lastPeriodicDist = p; lastPeriodicMs = fix.timeMs
                val sb = StringBuilder()
                sb.append(if (descending) "입구까지 " else "정상까지 ")
                sb.append("${Fmt.distance(remaining)}, 약 ${Fmt.duration(course.etaSeconds(p, target))} 남았어요.")
                currentPaceSecPerKm()?.let { sb.append(" 현재 페이스 ${Fmt.pace(it)}이에요.") }
                emit("periodic", Priority.PERIODIC, sb.toString())
            }
        }
        return out
    }

    private fun recordSample(t: Long, d: Double) {
        samples.addLast(Sample(t, d))
        while (samples.size > 1 && t - samples.first().t > settings.paceWindowS * 1000) samples.removeFirst()
    }

    /** 최근 [GuidanceSettings.paceWindowS] 초 동안 움직인 구간만으로 킬로당 초를 계산. 자료가 부족하면 null. */
    fun currentPaceSecPerKm(): Double? {
        var movingS = 0.0
        var movingM = 0.0
        val l = samples.toList()
        for (i in 1 until l.size) {
            val dt = (l[i].t - l[i - 1].t) / 1000.0
            val dd = l[i].d - l[i - 1].d
            if (dt <= 0 || dt > 30) continue
            if (dd / dt >= 0.3) { movingS += dt; movingM += dd }
        }
        if (movingS < 120 || movingM < 100) return null
        return movingS / movingM * 1000.0
    }
}
