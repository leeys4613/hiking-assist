package app.hiking.guidance

import java.time.OffsetDateTime

data class Fix(val timeMs: Long, val lat: Double, val lon: Double, val accuracyM: Float? = null)

/** 안내 항목별 on/off 와 임계값(명세 §5.2, §5.3). */
data class GuidanceSettings(
    val periodicEnabled: Boolean = true,
    val periodicDistM: Double = 1000.0,
    val periodicIntervalS: Long = 600,
    val poiEnabled: Boolean = true,
    val poiLeadM: Double = 300.0,
    val summitRadiusM: Double = 30.0,
    val accuracyMaxM: Float = 50f,
    /** 코스에서 이만큼 벗어난 fix 는 진행 거리 갱신에 쓰지 않는다 */
    val maxOffsetForProgressM: Double = 100.0,
    val paceWindowS: Long = 600,
    val minGapMs: Long = 20_000,
    val departure: DepartureSettings = DepartureSettings(),
    /** 등산로 밖에 있을 때 방향 안내를 되풀이하는 간격(명세 §5.4: 30초) */
    val offTrailRepeatS: Long = 30,
    /** 정기 안내에 "평소보다 N퍼센트 빨라요"를 덧붙인다(개인 페이스 모델이 있을 때만). 명세에서 선택 항목이라 기본 꺼짐 */
    val relativePaceEnabled: Boolean = false,
    /** 저장된 예보에서 강수확률이 높은 시간대 1시간 전에 알려준다(명세 §5.2) */
    val weatherEnabled: Boolean = true,
    val rainPopThreshold: Int = 60,
    val rainLeadMin: Long = 60,
    val sunsetEnabled: Boolean = true,
    /** 예상 하산 완료가 일몰 − 이 시간보다 늦으면 하산 권고(명세 §5.2: 30분) */
    val sunsetMarginMin: Long = 30,
)

/**
 * 안내 엔진: 시작 / 정기 / POI 접근 / 정상 / 하산 시작 / 종점(Phase 1)과
 * 이탈·복귀 / 대안 경로 / 등산로 밖 방향 안내 / 일몰 하산 권고(Phase 3).
 * 입력은 GPS fix 뿐이고 시계를 읽지 않으므로 JVM 에서 GPX 재생으로 검증한다.
 * 안내 key 는 종류와 회차를 `종류#N` 으로 표기한다(예: off_course#1).
 */
class GuidanceEngine(
    course: Course,
    private val mountainName: String,
    pois: List<Poi>,
    private val settings: GuidanceSettings = GuidanceSettings(),
    /** 검증된 그래프가 있을 때만 재경로를 켠다(AC8). null 이면 이탈 시 "코스를 벗어났어요" 만 말한다 */
    graph: Graph? = null,
    /** 일몰 시각(epoch ms). null 이면 하산 권고를 하지 않는다 */
    private val sunsetMs: Long? = null,
    /** 저장된 시간별 강수확률 예보 */
    pkgWeather: List<WeatherPoint> = emptyList(),
) {
    /** 패키지에서 바로 만든다. */
    constructor(pkg: CoursePackage, settings: GuidanceSettings = GuidanceSettings()) : this(
        Course(pkg), pkg.mountain.name, pkg.pois, settings, pkg.graph,
        parseSunset(pkg.sunset), pkg.weather,
    )

    val queue = AnnouncementQueue(minGapMs = settings.minGapMs)

    /** 지금 따라가는 경로. 대안 경로로 갈아타면 새 경로로 바뀐다. */
    var course: Course = course
        private set
    private var pois: List<Poi> = pois

    /** 현재 경로 위 진행 거리(m). 첫 유효 fix 전에는 null */
    var progress: Double? = null
        private set
    var summitReached = false
        private set
    var descending = false
        private set
    var finished = false
        private set
    val departureState: DepartureState get() = detector.state

    private val fired = HashSet<String>()
    private val counters = HashMap<String, Int>()
    private var startMs: Long? = null
    private var lastPeriodicDist = 0.0
    private var lastPeriodicMs = 0L
    private var lastSunsetCheckMs: Long? = null   // 오버플로를 피하려고 MIN_VALUE 센티넬을 쓰지 않는다

    /** 강수확률이 임계값 이상인 연속 시간대(시작 ms, 끝 ms, 시작 시간대 pop). 시간대 하나는 [t, t+1시간) */
    private class RainRun(val startMs: Long, val endMs: Long, val firstPop: Int, val slots: List<Pair<Long, Int>>)
    private val rainRuns: List<RainRun> = buildRainRuns(pkgWeather, settings.rainPopThreshold)
    private val rainDone = HashSet<Int>()
    private var lastWeatherCheckMs: Long? = null

    private val detector = DepartureDetector(settings.departure)
    private val rerouter: Rerouter? = graph?.let {
        Rerouter(it, course.summitLat, course.summitLon, course.endLat, course.endLon)
    }
    private var nextOffTrailMs = Long.MAX_VALUE     // 등산로 밖 안내를 되풀이할 다음 시각
    private var noRouteSaid = false

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
        fun emit(type: String, pr: Priority, text: String, once: Boolean = false) {
            val key = if (once) type else "$type#${counters.merge(type, 1, Int::plus)}"
            if (pr != Priority.PERIODIC && !fired.add(key)) return
            Announcement(key, pr, text, fix.timeMs).also { out += it; queue.offer(it) }
        }

        if (startMs == null) {
            startMs = fix.timeMs; lastPeriodicDist = p; lastPeriodicMs = fix.timeMs
            emit("start", Priority.EVENT,
                "$mountainName 안내를 시작해요. 정상까지 ${Fmt.distance(course.summitDist - p)}, " +
                    "약 ${Fmt.duration(course.etaSeconds(p, course.summitDist))} 걸려요.", once = true)
        }

        // ---- 이탈 판정(§5.3) ----
        when (detector.update(fix.timeMs, proj.offsetM)) {
            Transition.BECAME_OFF -> replan(fix, proj.offsetM, first = true, ::emit)
            Transition.BACK_ON -> { nextOffTrailMs = Long.MAX_VALUE; emit("back", Priority.URGENT, "코스로 돌아왔어요.") }
            Transition.NONE -> if (detector.state == DepartureState.OFF_COURSE && fix.timeMs >= nextOffTrailMs) replan(fix, proj.offsetM, first = false, ::emit)
        }
        val onCourse = detector.state == DepartureState.ON_COURSE || detector.state == DepartureState.SUSPECT
        val pNow = progress ?: p   // 대안 경로로 갈아탔다면 새 경로 기준

        if (proj.offsetM <= settings.maxOffsetForProgressM) recordSample(fix.timeMs, pNow)

        // 정상: 반경 안으로 들어오고 진행 거리도 정상 근처일 때(왕복에서 다른 구간 오인 방지)
        if (!summitReached) {
            val dSummit = Geo.distance(fix.lat, fix.lon, course.summitLat, course.summitLon)
            if (dSummit <= settings.summitRadiusM && pNow >= course.summitDist - 100) {
                summitReached = true
                val elapsedS = (fix.timeMs - startMs!!) / 1000.0
                emit("summit", Priority.EVENT, summitText(elapsedS), once = true)
            } else if (pNow >= course.summitDist + settings.summitRadiusM) {
                summitReached = true // 정상 반경을 못 밟고 지나간 경우: 안내 없이 단계만 넘긴다
            }
        }
        if (summitReached && !descending && pNow >= course.summitDist + settings.summitRadiusM) {
            descending = true
            lastPeriodicDist = pNow; lastPeriodicMs = fix.timeMs // 하산 안내 직후 같은 내용의 정기 안내가 또 나오지 않게
            emit("descent", Priority.EVENT,
                "하산 시작해요. 입구까지 ${Fmt.distance(course.endDist - pNow)}, " +
                    "약 ${Fmt.duration(course.etaSeconds(pNow, course.endDist))} 남았어요.", once = true)
        }

        // 이탈 중에는 코스 기준 안내(POI/정기)가 틀리므로 하지 않는다
        if (settings.poiEnabled && onCourse) for (poi in pois) {
            val key = "poi:${poi.name}@${poi.courseDistM.toInt()}"
            if (key in fired) continue
            // 왕복 코스: 같은 장소가 올라갈 때/내려올 때 두 번 나온다. 각각 해당 단계에서만 안내한다
            val onDescentLeg = poi.courseDistM > course.summitDist
            if (onDescentLeg != summitReached) continue
            val ahead = poi.courseDistM - pNow
            if (ahead <= 0) { fired += key; continue } // 이미 지나친 POI 는 늦게 안내하지 않는다
            if (ahead <= settings.poiLeadM) {
                emit(key, Priority.EVENT, "${Fmt.distance(ahead)} 앞에 ${Fmt.subject(poi.name)} 있어요.", once = true)
            }
        }

        if (descending && pNow >= course.endDist - settings.summitRadiusM) {
            finished = true
            emit("end", Priority.EVENT, "입구에 도착했어요. 수고하셨어요.", once = true)
            return out
        }

        // ---- 강수 예보 안내(§5.2): 강수확률이 높은 시간대 1시간 전(이미 그 시간대면 시작할 때) 한 번 ----
        if (settings.weatherEnabled && rainRuns.isNotEmpty() && !descending &&
            (lastWeatherCheckMs.let { it == null || fix.timeMs - it >= 60_000 })
        ) {
            lastWeatherCheckMs = fix.timeMs
            rainRuns.forEachIndexed { i, run ->
                if (i in rainDone || fix.timeMs >= run.endMs) { if (fix.timeMs >= run.endMs) rainDone += i; return@forEachIndexed }
                if (fix.timeMs < run.startMs - settings.rainLeadMin * 60_000) return@forEachIndexed
                rainDone += i
                if (fix.timeMs < run.startMs) {
                    emit("rain", Priority.EVENT, "${Fmt.duration((run.startMs - fix.timeMs) / 1000.0)} 뒤 강수확률 ${run.firstPop}퍼센트예요.")
                } else {
                    val pop = run.slots.lastOrNull { it.first <= fix.timeMs }?.second ?: run.firstPop
                    emit("rain", Priority.EVENT, "지금 강수확률 ${pop}퍼센트 예보예요.")
                }
            }
        }

        // ---- 일몰 하산 권고(§5.2): 아직 오르는 중이고 예상 하산 완료가 일몰 − 30분보다 늦을 때 한 번 ----
        if (settings.sunsetEnabled && sunsetMs != null && !descending && "sunset" !in fired &&
            (lastSunsetCheckMs.let { it == null || fix.timeMs - it >= 60_000 })
        ) {
            lastSunsetCheckMs = fix.timeMs
            val etaS = if (summitReached) course.etaSeconds(pNow, course.endDist)
            else course.etaSeconds(pNow, course.summitDist) + course.etaSeconds(course.summitDist, course.endDist)
            if (fix.timeMs + etaS * 1000 > sunsetMs - settings.sunsetMarginMin * 60_000) {
                emit("sunset", Priority.URGENT, "일몰까지 남은 시간이 빠듯해요. 지금 하산을 권해요.", once = true)
            }
        }

        if (settings.periodicEnabled && onCourse) {
            val target = if (descending) course.endDist else course.summitDist
            val remaining = target - pNow
            val due = pNow - lastPeriodicDist >= settings.periodicDistM ||
                fix.timeMs - lastPeriodicMs >= settings.periodicIntervalS * 1000
            if (due && remaining > 150) {
                lastPeriodicDist = pNow; lastPeriodicMs = fix.timeMs
                val sb = StringBuilder()
                sb.append(if (descending) "입구까지 " else "정상까지 ")
                sb.append("${Fmt.distance(remaining)}, 약 ${Fmt.duration(course.etaSeconds(pNow, target))} 남았어요.")
                currentPaceSecPerKm()?.let { sb.append(" 현재 페이스 ${Fmt.pace(it)}이에요.") }
                if (settings.relativePaceEnabled) relativePaceText()?.let { sb.append(" $it") }
                emit("periodic", Priority.PERIODIC, sb.toString(), once = true)
            }
        }
        return out
    }

    /** "정상이에요. 2시간 10분 걸렸고, 평소보다 5분 빨라요." (평소 기록이 있을 때) */
    private fun summitText(elapsedS: Double): String {
        val typical = course.paceModel?.typicalSummitS ?: return "정상이에요. ${Fmt.duration(elapsedS)} 걸렸어요."
        val diffMin = Math.round((typical - elapsedS) / 60.0 / 5.0).toInt() * 5     // 5분 단위, 양수 = 평소보다 빠름
        val cmp = when {
            diffMin >= 5 -> "평소보다 ${diffMin}분 빨라요"
            diffMin <= -5 -> "평소보다 ${-diffMin}분 느려요"
            else -> "평소와 비슷해요"
        }
        return "정상이에요. ${Fmt.duration(elapsedS)} 걸렸고, $cmp."
    }

    /** 이탈이 확정됐을 때(또는 등산로 밖에서 30초마다) 재경로를 계산해 안내한다(§5.4). */
    private fun replan(fix: Fix, offsetM: Double, first: Boolean, emit: (String, Priority, String, Boolean) -> Unit) {
        // 되풀이 중 코스에서 이탈 임계값 안으로 돌아왔으면 복귀를 기다린다(원래 길을 대안 경로로 오인하지 않게)
        if (!first && offsetM <= settings.departure.offDistM) return
        val toSummit = !summitReached
        val plan = rerouter?.plan(fix.lat, fix.lon, toSummit)
        val goal = if (toSummit) "정상" else "입구"
        when (plan) {
            null -> if (first) emit("off_course", Priority.URGENT, "코스를 벗어났어요.", false)
            is ReroutePlan.Alt -> {
                switchTo(plan, toSummit, fix.timeMs)
                emit("alt_route", Priority.URGENT,
                    "다른 코스로 들어섰어요. 이 길로 ${goal}까지 ${Fmt.distance(plan.distM)}, 약 ${Fmt.duration(plan.etaS)}이에요.", false)
            }
            is ReroutePlan.OffTrail -> {
                val dir = plan.bearingDeg?.let { Rerouter.compass8(it) }
                val text = if (dir != null && plan.distM != null)
                    "등산로를 벗어났어요. ${dir}쪽 ${Fmt.distance(plan.distM)}에 등산로가 있어요." else "등산로를 벗어났어요."
                emit("off_trail", Priority.URGENT, text, false)
                nextOffTrailMs = fix.timeMs + settings.offTrailRepeatS * 1000
            }
            ReroutePlan.NoRoute -> {
                if (!noRouteSaid) { noRouteSaid = true; emit("no_route", Priority.URGENT, "이 길로는 갈 수 없어요. 코스로 돌아가세요.", false) }
                nextOffTrailMs = fix.timeMs + settings.offTrailRepeatS * 1000   // 복귀하거나 갈 수 있는 길이 생기는지 계속 본다
            }
        }
    }

    /** 대안 경로를 "현재 코스"로 바꾼다. 정상 전이면 (대안 경로 → 정상) + 원래 하산 구간으로 이어 붙인다. */
    private fun switchTo(plan: ReroutePlan.Alt, toSummit: Boolean, nowMs: Long) {
        val old = course
        val alt = plan.polyline
        val poly: List<List<Double>>
        val summitIdx: Int
        val newPois: List<Poi>
        if (toSummit) {
            val descent = (old.summitIndex until old.lat.size).map { listOf(old.lat[it], old.lon[it], old.ele[it]) }
            poly = alt + descent
            summitIdx = alt.size - 1
            val shift = Course.lengthOf(alt) - old.summitDist
            newPois = pois.filter { it.courseDistM > old.summitDist }.map { it.copy(courseDistM = it.courseDistM + shift) }
        } else {
            poly = alt
            summitIdx = 0
            newPois = emptyList()
            descending = true            // 이미 정상을 지났으니 하산 안내가 다시 나오지 않게 한다
            summitReached = true
        }
        course = Course(CoursePackage(1, Mountain("", mountainName),
            CourseDef("alt", "대안 경로", poly, summitIdx, poly.size - 1), newPois, paceModel = old.paceModel))
        pois = newPois
        progress = 0.0
        samples.clear()
        lastPeriodicDist = 0.0; lastPeriodicMs = nowMs
        detector.reset()
        nextOffTrailMs = Long.MAX_VALUE
        noRouteSaid = false
    }

    companion object {
        private const val HOUR_MS = 3_600_000L

        /** 임계값 이상인 연속 시간대를 하나의 구간으로 묶는다. 시각 형식이 틀린 점은 건너뛴다. */
        private fun buildRainRuns(weather: List<WeatherPoint>, threshold: Int): List<RainRun> {
            val slots = weather.mapNotNull { w ->
                runCatching { OffsetDateTime.parse(w.time).toInstant().toEpochMilli() to w.pop }.getOrNull()
            }.sortedBy { it.first }.filter { it.second >= threshold }
            val runs = mutableListOf<RainRun>()
            var cur = mutableListOf<Pair<Long, Int>>()
            for (s in slots) {
                if (cur.isNotEmpty() && s.first != cur.last().first + HOUR_MS) {
                    runs += RainRun(cur.first().first, cur.last().first + HOUR_MS, cur.first().second, cur); cur = mutableListOf()
                }
                cur += s
            }
            if (cur.isNotEmpty()) runs += RainRun(cur.first().first, cur.last().first + HOUR_MS, cur.first().second, cur)
            return runs
        }

        /** ISO-8601(오프셋 포함) → epoch ms. 형식이 틀리면 null(하산 권고만 꺼진다) */
        fun parseSunset(s: String?): Long? =
            s?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }
    }

    /** 최근 구간을 개인 모델이 예상한 시간 / 실제 걸린 시간. 1.1 이상이면 평소보다 빠름. 개인 모델이 없으면 null */
    private fun relativePaceText(): String? {
        if (course.paceModel == null) return null
        var expected = 0.0
        var actual = 0.0
        val l = samples.toList()
        for (i in 1 until l.size) {
            val dt = (l[i].t - l[i - 1].t) / 1000.0
            val dd = l[i].d - l[i - 1].d
            if (dt <= 0 || dt > 30 || dd / dt < 0.3) continue
            expected += course.etaSeconds(l[i - 1].d, l[i].d); actual += dt
        }
        if (actual < 120) return null
        val pct = Math.round((expected / actual - 1.0) * 100.0 / 5.0).toInt() * 5
        return when {
            pct >= 10 -> "평소보다 ${pct}퍼센트 빨라요."
            pct <= -10 -> "평소보다 ${-pct}퍼센트 느려요."
            else -> null
        }
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
