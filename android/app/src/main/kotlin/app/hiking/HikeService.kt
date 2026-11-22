package app.hiking

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import app.hiking.guidance.Course
import app.hiking.guidance.Fix
import app.hiking.guidance.Fmt
import app.hiking.guidance.GuidanceEngine
import app.hiking.guidance.CoursePackage
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * 화면이 꺼져도 도는 산행 서비스(명세 §5.7): 위치 → GuidanceEngine → 대기열 → Speaker.
 * [ACTION_SIM] 은 같은 경로를 가상 GPS 로 빠르게 재생해 실내에서 전체 흐름(TTS, 음악 ducking)을 확인하는 모드다.
 */
class HikeService : Service() {
    companion object {
        const val ACTION_START = "app.hiking.START"
        const val ACTION_SIM = "app.hiking.SIM"
        const val ACTION_PAUSE = "app.hiking.PAUSE"
        const val ACTION_STOP = "app.hiking.STOP"
        /** 서버에서 받은 코스 패키지(filesDir). 없으면 assets 의 내장 시험 코스를 쓴다. */
        const val PACKAGE_FILE = "course-package.json"
        private const val CHANNEL = "hike"
        private const val NOTIF_ID = 1

        /** 화면(MainActivity) 표시용 상태. 서비스가 갱신한다. */
        @Volatile var status: String = "대기 중"
        @Volatile var running: Boolean = false

        private const val MOVING_INTERVAL_MS = 4_000L
        private const val REST_INTERVAL_MS = 30_000L
        private const val SIM_SPEED = 20       // 가상 시간 배속
        private const val SIM_STEP_S = 3       // fix 간격(가상 초)
        private const val SIM_WALK_MPS = 1.2
    }

    private val handler = Handler(Looper.getMainLooper())
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var engine: GuidanceEngine? = null
    private var course: Course? = null
    private var speaker: Speaker? = null
    private var log: TrackLog? = null

    private var simulating = false
    private var paused = false
    private var simDist = 0.0
    private var simClockMs = 0L
    private var simRestLeftS = 60

    private var intervalMs = MOVING_INTERVAL_MS
    private var lastMoveProgress = -1.0
    private var lastMoveRealMs = 0L
    private var hasLocationCallback = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(r: LocationResult) {
            for (l in r.locations) {
                val acc = if (l.hasAccuracy()) l.accuracy else null
                onFix(Fix(l.time, l.latitude, l.longitude, acc))
            }
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            pump(); updateNotification(); log?.flush(); BatterySaver.keepOff(this@HikeService)
            handler.postDelayed(this, 2_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> begin(sim = false)
            ACTION_SIM -> begin(sim = true)
            ACTION_PAUSE -> togglePause()
            ACTION_STOP -> shutDown()
        }
        return START_NOT_STICKY
    }

    private fun begin(sim: Boolean) {
        if (running) return
        if (!sim && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            status = "위치 권한이 없어 시작하지 못했어요"; stopSelf(); return
        }
        createChannel()
        val n = buildNotification("안내 준비 중")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else startForeground(NOTIF_ID, n)

        val saved = java.io.File(filesDir, PACKAGE_FILE)
        val pkgText = if (saved.exists()) saved.readText()
        else assets.open("suraksan_package.json").use { it.readBytes().decodeToString() }
        val pkg = CoursePackage.parse(pkgText)
        val c = Course(pkg)
        course = c
        engine = GuidanceEngine(c, pkg.mountain.name, pkg.pois)
        speaker = Speaker(this)
        log = TrackLog(this, pkg.course.id, sim)
        if (!sim) startStepCounter()
        BatterySaver.suspendForHike(this)
        running = true; simulating = sim; paused = false
        lastMoveRealMs = SystemClock.elapsedRealtime()
        status = if (sim) "시뮬레이션 중" else "GPS 신호 기다리는 중"
        handler.post(ticker)
        if (sim) {
            simDist = 0.0; simClockMs = 1_000_000L; simRestLeftS = 60
            handler.post(simStep)
        } else requestUpdates(MOVING_INTERVAL_MS)
    }

    private val simStep = object : Runnable {
        override fun run() {
            val c = course ?: return
            if (!simulating || !running) return
            if (!paused) {
                val (la, lo) = pointAt(c, simDist)
                onFix(Fix(simClockMs, la, lo, 8f))
                val atSummit = simDist in (c.summitDist - 5)..(c.summitDist + 5) && simRestLeftS > 0
                if (atSummit) simRestLeftS -= SIM_STEP_S else simDist += SIM_WALK_MPS * SIM_STEP_S
                simClockMs += SIM_STEP_S * 1000L
                if (engine?.finished == true || simDist > c.length + 50) { status = "시뮬레이션 끝"; return }
            }
            handler.postDelayed(this, (SIM_STEP_S * 1000L) / SIM_SPEED)
        }
    }

    private fun pointAt(c: Course, dist: Double): Pair<Double, Double> {
        val d = dist.coerceIn(0.0, c.length)
        var i = 0
        while (i < c.cum.size - 2 && c.cum[i + 1] < d) i++
        val span = c.cum[i + 1] - c.cum[i]
        val f = if (span <= 0) 0.0 else (d - c.cum[i]) / span
        return (c.lat[i] + (c.lat[i + 1] - c.lat[i]) * f) to (c.lon[i] + (c.lon[i + 1] - c.lon[i]) * f)
    }

    /** 안내 대기열 시계: 시뮬레이션은 가상 시간, 실제 산행은 시스템 시간 */
    private fun now(): Long = if (simulating) simClockMs else System.currentTimeMillis()

    private fun onFix(fix: Fix) {
        val e = engine ?: return
        if (paused) return
        val made = e.onFix(fix)
        log?.track(fix.timeMs, fix.lat, fix.lon, fix.accuracyM, e.progress)
        made.forEach { log?.event(fix.timeMs, it.key, it.text) }
        val p = e.progress
        if (p != null) status = (if (simulating) "시뮬레이션 " else "") + "진행 ${Fmt.distance(p)} / ${Fmt.distance(course!!.length)}"
        if (!simulating) adaptInterval(p)
        pump()
    }

    /** 배터리 정책(명세 §5.6): 움직이면 4초, 60초 이상 제자리면 30초 */
    private fun adaptInterval(progress: Double?) {
        val nowReal = SystemClock.elapsedRealtime()
        if (progress != null && (lastMoveProgress < 0 || kotlin.math.abs(progress - lastMoveProgress) >= 5.0)) {
            lastMoveProgress = progress; lastMoveRealMs = nowReal
            if (intervalMs != MOVING_INTERVAL_MS) requestUpdates(MOVING_INTERVAL_MS)
        } else if (nowReal - lastMoveRealMs > 60_000 && intervalMs != REST_INTERVAL_MS) {
            requestUpdates(REST_INTERVAL_MS)
        }
    }

    @Suppress("MissingPermission")
    private fun requestUpdates(ms: Long) {
        if (hasLocationCallback) fused.removeLocationUpdates(callback)
        intervalMs = ms
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, ms)
            .setMinUpdateIntervalMillis(ms / 2).build()
        fused.requestLocationUpdates(req, callback, Looper.getMainLooper())
        hasLocationCallback = true
    }

    private fun pump() {
        val e = engine ?: return
        val s = speaker ?: return
        if (paused || s.busy) return
        val a = e.queue.next(now()) ?: return
        s.speak(a.text) { e.queue.onSpeechEnded(now()); pump() }
    }

    private fun togglePause() {
        if (!running) return
        paused = !paused
        if (paused) {
            speaker?.stop()
            if (hasLocationCallback) { fused.removeLocationUpdates(callback); hasLocationCallback = false }
            status = "일시정지"
        } else {
            status = "재개"
            if (!simulating) requestUpdates(MOVING_INTERVAL_MS)
        }
        updateNotification()
    }

    private fun shutDown() {
        handler.removeCallbacksAndMessages(null)
        if (hasLocationCallback) { fused.removeLocationUpdates(callback); hasLocationCallback = false }
        speaker?.shutdown(); speaker = null
        stopStepCounter()
        val wasReal = !simulating
        log?.flush()
        running = false; simulating = false
        status = "종료됨" + (log?.let { " (기록: ${it.path})" } ?: "")
        // 하산 후 자동 업로드: 네트워크가 있을 때(WorkManager) /health 확인 뒤 올린다. 시뮬레이션은 올리지 않는다.
        if (wasReal) {
            UploadWorker.enqueue(applicationContext)           // 업로드가 끝나면 워커가 절전 모드를 복원한다
            BatterySaver.scheduleRestore(applicationContext)   // 업로드가 안 끝나도 20분 뒤에는 복원
        } else BatterySaver.restoreNow(applicationContext)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ---- 걸음 수 (명세 §5.8): 걸음 센서는 부팅 후 누적값이라 시작/끝 차이를 쓴다 ----
    private var stepSensorFirst = -1f
    private var stepSensorLast = -1f
    private val stepListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            if (stepSensorFirst < 0) stepSensorFirst = e.values[0]
            stepSensorLast = e.values[0]
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
    }

    private fun startStepCounter() {
        if (checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED) return
        val sm = getSystemService(android.hardware.SensorManager::class.java)
        sm.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER)?.let {
            sm.registerListener(stepListener, it, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun stopStepCounter() {
        getSystemService(android.hardware.SensorManager::class.java).unregisterListener(stepListener)
        if (stepSensorFirst >= 0) log?.steps((stepSensorLast - stepSensorFirst).toLong())
    }

    override fun onDestroy() {
        if (running) shutDown()
        super.onDestroy()
    }

    // ---- 알림 ----

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "산행 안내", NotificationManager.IMPORTANCE_LOW))
    }

    private fun action(act: String, label: String, req: Int): Notification.Action {
        val pi = PendingIntent.getService(
            this, req, Intent(this, HikeService::class.java).setAction(act),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, label, pi).build()
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("등산 안내")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(action(ACTION_PAUSE, if (paused) "재개" else "일시정지", 1))
            .addAction(action(ACTION_STOP, "종료", 2))
            .build()
    }

    private fun updateNotification() {
        val c = course ?: return
        val p = engine?.progress
        val text = when {
            paused -> "일시정지"
            p == null -> status
            else -> {
                val target = if (engine?.descending == true) c.endDist else c.summitDist
                "진행 ${Fmt.distance(p)} · ${if (engine?.descending == true) "입구" else "정상"}까지 ${Fmt.distance(target - p)}"
            }
        }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}
