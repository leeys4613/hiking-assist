package app.hiking.guidance

import java.io.File
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

/**
 * GPX 재생 시뮬레이터(명세 §9): GPX 를 시간순으로 guidance-core 에 넣고 안내 이벤트 목록을 만든다.
 * CLI:  ./gradlew :guidance-core:replay --args="<package.json> <track.gpx> [sunset-ISO]"
 */
object Replay {
    data class Event(val tS: Double, val key: String, val text: String) {
        val type: String get() = key.substringBefore('#')
    }

    /** <trkpt lat lon><time/><hdop/> → Fix. hdop 은 정확도(m)로 쓴다. */
    fun readGpx(file: File): List<Fix> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val pts = doc.getElementsByTagName("trkpt")
        return (0 until pts.length).map { i ->
            val e = pts.item(i) as org.w3c.dom.Element
            fun tag(n: String) = e.getElementsByTagName(n).item(0)?.textContent
            Fix(
                Instant.parse(tag("time")!!).toEpochMilli(),
                e.getAttribute("lat").toDouble(), e.getAttribute("lon").toDouble(),
                tag("hdop")?.toFloat(),
            )
        }
    }

    fun run(pkg: CoursePackage, fixes: List<Fix>, settings: GuidanceSettings = GuidanceSettings()): List<Event> {
        val engine = GuidanceEngine(pkg, settings)
        val t0 = fixes.first().timeMs
        return fixes.flatMap { f -> engine.onFix(f).map { Event((f.timeMs - t0) / 1000.0, it.key, it.text) } }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 2) { "usage: replay <package.json> <track.gpx> [sunset]" }
        var pkg = CoursePackage.parse(File(args[0]).readText())
        if (args.size > 2) pkg = pkg.copy(sunset = args[2])
        val fixes = readGpx(File(args[1]))
        println("${fixes.size} fixes, ${(fixes.last().timeMs - fixes.first().timeMs) / 1000}s")
        run(pkg, fixes).forEach { println("%8.1fs  %-14s %s".format(it.tS, it.key, it.text)) }
    }
}
