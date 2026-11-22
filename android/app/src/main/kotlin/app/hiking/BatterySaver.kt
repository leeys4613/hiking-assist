package app.hiking

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 산행 중에는 절전 모드를 끄고(절전 모드는 화면이 꺼지면 GPS 를 끄고 백그라운드 네트워크를 막는다),
 * 끝난 뒤 원래 상태로 되돌린다. WRITE_SECURE_SETTINGS 가 필요하며 개인용 앱이므로 adb 로 한 번 부여한다:
 *   adb shell pm grant app.hiking android.permission.WRITE_SECURE_SETTINGS
 * 권한이 없으면 아무것도 하지 않는다(화면에 안내만 표시).
 */
object BatterySaver {
    private const val KEY = "low_power"
    private fun sp(ctx: Context) = ctx.getSharedPreferences("saver", Context.MODE_PRIVATE)

    fun canControl(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isOn(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, KEY, 0) == 1

    private fun put(ctx: Context, on: Boolean): Boolean =
        runCatching { Settings.Global.putInt(ctx.contentResolver, KEY, if (on) 1 else 0) }.getOrDefault(false)

    /** 산행 시작: 켜져 있었다면 "복원 필요"를 기록하고 끈다. 이미 복원 대기 중이면 기록을 덮어쓰지 않는다. */
    fun suspendForHike(ctx: Context) {
        if (!canControl(ctx) || !isOn(ctx)) return
        if (put(ctx, false)) sp(ctx).edit().putBoolean("restore", true).apply()
    }

    /** 산행 중: 배터리가 낮아져 시스템이 절전 모드를 다시 켜면 다시 끈다. */
    fun keepOff(ctx: Context) {
        if (canControl(ctx) && sp(ctx).getBoolean("restore", false) && isOn(ctx)) put(ctx, false)
    }

    /** 원래 켜져 있던 경우에만 다시 켠다. 켜는 데 실패하면 기록을 남겨 다음에 다시 시도한다. */
    fun restoreNow(ctx: Context) {
        if (!sp(ctx).getBoolean("restore", false)) return
        if (!canControl(ctx) || put(ctx, true)) sp(ctx).edit().putBoolean("restore", false).apply()
    }

    /** 업로드가 안 끝나더라도 일정 시간 뒤에는 복원한다(산속에서 네트워크가 없을 때를 대비). */
    fun scheduleRestore(ctx: Context, delayMin: Long = 20) {
        val req = OneTimeWorkRequestBuilder<RestoreWorker>().setInitialDelay(delayMin, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("saver-restore", ExistingWorkPolicy.REPLACE, req)
    }

    fun statusText(ctx: Context): String = when {
        !canControl(ctx) -> "절전 모드: ${if (isOn(ctx)) "켜짐" else "꺼짐"} (자동 제어 권한 없음 — 산행 전에 직접 끄세요)"
        else -> "절전 모드: ${if (isOn(ctx)) "켜짐" else "꺼짐"} (산행 중 자동 해제, 종료 후 복원)"
    }
}

class RestoreWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result { BatterySaver.restoreNow(applicationContext); return Result.success() }
}
