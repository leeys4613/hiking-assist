package app.hiking

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 하산 후 기록 업로드(명세 §5.8): 네트워크 제약 + /health 확인 후 올리고, 서버가 받은 경우에만 "업로드 완료"로 표시한다.
 * 서버가 업로드를 멱등으로 처리하므로 재시도가 안전하다.
 */
class UploadWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val sp = ctx.getSharedPreferences("upload", Context.MODE_PRIVATE)
        val done = sp.getStringSet("done", emptySet())!!.toMutableSet()
        val pending = dir.listFiles { f -> f.name.startsWith("hike-") && f.name.endsWith(".log") && f.name !in done }
            ?.sortedBy { it.name } ?: emptyList()
        if (pending.isEmpty()) { BatterySaver.restoreNow(ctx); return Result.success() }
        if (!Api.configured(ctx)) { setStatus(ctx, "서버 주소/토큰이 설정되지 않았어요"); return Result.failure() }

        try { Api.get(ctx, "/health") } catch (e: Api.HttpError) {
            setStatus(ctx, if (e.code == 401) "토큰이 맞지 않아요" else "서버 오류 ${e.code}")
            return if (e.code == 401) Result.failure() else Result.retry()
        } catch (e: IOException) {
            setStatus(ctx, "서버에 닿지 않아요 (Tailscale 켜졌는지 확인) — 자동 재시도")
            return Result.retry()
        }

        for (f in pending) {
            val body = runCatching { toUploadJson(f) }.getOrNull()
            if (body == null) { done += f.name; setStatus(ctx, "기록이 너무 짧아 건너뜀: ${f.name}"); continue }
            try {
                Api.postJson(ctx, "/api/hikes", body)
                done += f.name
                setStatus(ctx, "업로드 완료: ${f.name}")
            } catch (e: Api.HttpError) {
                if (e.code in 500..599) { setStatus(ctx, "서버 오류 ${e.code} — 자동 재시도"); sp.edit().putStringSet("done", done).apply(); return Result.retry() }
                // 4xx: 다시 보내도 같은 결과이므로 완료 처리하지 않고 사유만 남긴다
                setStatus(ctx, "업로드 거부(${e.code}): ${f.name}")
                sp.edit().putStringSet("done", done).apply()
                return Result.failure()
            } catch (e: IOException) {
                setStatus(ctx, "업로드 중 연결이 끊김 — 자동 재시도")
                sp.edit().putStringSet("done", done).apply()
                return Result.retry()
            }
        }
        sp.edit().putStringSet("done", done).apply()
        BatterySaver.restoreNow(ctx)   // 올릴 기록이 모두 끝났으니 절전 모드를 원래대로
        return Result.success()
    }

    /** hike-*.log (H/T/E/S 줄) → POST /api/hikes 본문. 위치가 2개 미만이면 null. */
    private fun toUploadJson(f: File): String? {
        var course = ""
        val track = JSONArray()
        val events = JSONArray()
        var steps: Long? = null
        for (line in f.readLines()) {
            val p = line.split(",")
            when (p.firstOrNull()) {
                "H" -> course = p.getOrElse(1) { "" }
                "T" -> track.put(JSONArray().apply {
                    put(p[1].toLong()); put(p[2].toDouble()); put(p[3].toDouble())
                    put(p.getOrNull(4)?.toDoubleOrNull() ?: JSONObject.NULL)
                    put(p.getOrNull(5)?.toDoubleOrNull() ?: JSONObject.NULL)
                })
                "E" -> { val q = line.split(",", limit = 4); events.put(JSONObject().put("t", q[1].toLong()).put("key", q[2]).put("text", q.getOrElse(3) { "" })) }
                "S" -> steps = p.getOrNull(1)?.toLongOrNull()
            }
        }
        if (course.isEmpty() || track.length() < 2) return null
        return JSONObject().put("course_id", course).put("track", track).put("events", events)
            .put("steps", steps ?: JSONObject.NULL).toString()
    }

    companion object {
        private fun setStatus(ctx: Context, s: String) =
            ctx.getSharedPreferences("upload", Context.MODE_PRIVATE).edit().putString("last", s).apply()

        fun lastStatus(ctx: Context): String =
            ctx.getSharedPreferences("upload", Context.MODE_PRIVATE).getString("last", "아직 없음")!!

        fun enqueue(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork("upload", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }
    }
}
