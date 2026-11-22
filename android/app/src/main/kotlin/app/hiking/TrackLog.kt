package app.hiking

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 산행 중 위치와 안내 이벤트를 파일로 남긴다(메모리 버퍼 → 주기적 일괄 저장, 명세 §5.6).
 * 파일: Android/data/app.hiking/files/hike-YYYYMMDD-HHMMSS.log
 *   H,courseId,startEpochMs  (첫 줄)
 *   S,steps                   (끝)
 *   T,epochMs,lat,lon,accuracy,progressM
 *   E,epochMs,key,text
 */
class TrackLog(ctx: Context, courseId: String, sim: Boolean) {
    private val file = File(
        ctx.getExternalFilesDir(null) ?: ctx.filesDir,
        (if (sim) "sim-" else "hike-") + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".log",
    )
    private val buf = StringBuilder().append("H,").append(courseId).append(',').append(System.currentTimeMillis()).append('\n')

    val path: String get() = file.absolutePath

    @Synchronized fun track(t: Long, lat: Double, lon: Double, acc: Float?, progress: Double?) {
        buf.append("T,").append(t).append(',').append(lat).append(',').append(lon).append(',')
            .append(acc ?: "").append(',').append(progress?.let { "%.1f".format(Locale.US, it) } ?: "").append('\n')
    }

    @Synchronized fun event(t: Long, key: String, text: String) {
        buf.append("E,").append(t).append(',').append(key).append(',').append(text.replace('\n', ' ')).append('\n')
    }

    @Synchronized fun steps(n: Long) { buf.append("S,").append(n).append('\n') }

    @Synchronized fun flush() {
        if (buf.isEmpty()) return
        runCatching { file.appendText(buf.toString()) }
        buf.setLength(0)
    }
}
