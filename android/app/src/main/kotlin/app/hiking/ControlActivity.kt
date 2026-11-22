package app.hiking

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Phase 1 화면: 권한 단계 안내, 음성 테스트, 산행 시작/시뮬레이션/종료. 웹 UI(WebView)는 Phase 2. */
class ControlActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var info: TextView
    private var speaker: Speaker? = null

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speaker = Speaker(this)
        val pad = dp(16)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad * 2) }
        root.addView(Ui.title(this, "산행"), Ui.margins(this, bottom = 12))
        // 상태 카드: 권한·배터리·코스·업로드 상태가 한눈에 보이는 곳
        info = TextView(this).apply { textSize = 14f; setLineSpacing(0f, 1.2f); setTextColor(getColor(R.color.fg)) }
        root.addView(Ui.card(this).apply { addView(info) }, Ui.margins(this, bottom = 16))

        fun add(v: android.view.View, bottom: Int = 8) = root.addView(v, Ui.margins(this, bottom = bottom))
        add(Ui.primaryButton(this, "산행 시작 (실제 GPS)") { startService(HikeService.ACTION_START) })
        val row = LinearLayout(this)
        fun half(label: String, last: Boolean, f: () -> Unit) = row.addView(Ui.tonalButton(this, label, onClick = f),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { if (!last) marginEnd = dp(8) })
        half("일시정지 / 재개", false) { startService(HikeService.ACTION_PAUSE) }
        half("종료", true) { startService(HikeService.ACTION_STOP) }
        add(row, 24)

        add(Ui.section(this, "준비"), 8)
        add(Ui.tonalButton(this, "1) 권한 허용 (위치 → 알림 → 항상 허용 순서)") { requestNextPermission() })
        add(Ui.tonalButton(this, "2) 배터리 '제한 없음' 허용") { requestUnrestrictedBattery() })
        add(Ui.tonalButton(this, "음성 테스트 (음악 켜고 눌러 보세요)") {
            speaker?.speak("정상까지 2.3킬로, 약 55분 남았어요. 현재 페이스 킬로당 22분이에요.") { }
        }, 24)

        add(Ui.section(this, "테스트 · 기록"), 8)
        add(Ui.tonalButton(this, "시뮬레이션 (실내 테스트, 20배속)") { startService(HikeService.ACTION_SIM) })
        add(Ui.tonalButton(this, "기록 지금 업로드") { UploadWorker.enqueue(this) }, 24)

        add(Ui.section(this, "안내 항목 (다음 산행부터 적용)"), 8)
        val sp = getSharedPreferences("settings", MODE_PRIVATE)
        val toggles = Ui.card(this)
        fun toggle(key: String, label: String, default: Boolean) = toggles.addView(android.widget.Switch(this).apply {
            text = label; textSize = 15f; setTextColor(getColor(R.color.fg))
            minHeight = dp(48); isChecked = sp.getBoolean(key, default)
            setOnCheckedChangeListener { _, on -> sp.edit().putBoolean(key, on).apply() }
        })
        toggle("periodic", "정기 안내 (1킬로 또는 10분마다)", true)
        toggle("poi", "쉼터·명소 접근 안내", true)
        toggle("weather", "강수 예보 (비 오기 1시간 전)", true)
        toggle("sunset", "일몰 임박 하산 권고", true)
        toggle("relative_pace", "평소 대비 페이스 (\"평소보다 10퍼센트 빨라요\")", false)
        add(toggles, 0)
        setContentView(ScrollView(this).apply { addView(root) })

    }

    private fun startService(action: String) {
        val i = Intent(this, HikeService::class.java).setAction(action)
        if (action == HikeService.ACTION_START || action == HikeService.ACTION_SIM) startForegroundService(i) else startService(i)
    }

    /** Android 11+ 는 '항상 허용(백그라운드 위치)'을 포그라운드 위치와 따로, 나중에 요청해야 한다. */
    private fun requestNextPermission() {
        val first = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION); add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.ACTIVITY_RECOGNITION)
        }.filter { !granted(it) }
        if (first.isNotEmpty()) { requestPermissions(first.toTypedArray(), 1); return }
        if (!granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val bg = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val notif = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)
        info.text = buildString {
            appendLine("위치(정확): ${if (fine) "허용" else "필요"}")
            appendLine("항상 허용(백그라운드 위치): ${if (bg) "허용" else "미허용 (화면 끈 산행에 권장)"}")
            appendLine("알림: ${if (notif) "허용" else "필요"}")
            appendLine("음성: ${speaker?.statusText}")
            appendLine(BatterySaver.statusText(this@ControlActivity))
            appendLine("배터리 사용량 제한: ${if (isUnrestricted()) "제한 없음" else "제한됨 — 아래 2) 버튼으로 허용하세요"}")
            appendLine("코스: ${courseName()}")
            appendLine("상태: ${HikeService.status}")
            appendLine("업로드: ${UploadWorker.lastStatus(this@ControlActivity)}")
            appendLine()
            appendLine("※ 삼성은 설정 > 배터리 > 백그라운드 사용 제한의 '절전 앱/잠자는 앱' 목록에도 이 앱이 없어야 해요.")
        }
        handler.postDelayed({ refresh() }, 1000)
    }

    private fun isUnrestricted() =
        getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    /** 시스템 확인창(허용/거부)을 띄운다. 이미 허용돼 있으면 설정 목록으로 보낸다. */
    private fun requestUnrestrictedBattery() {
        val i = if (isUnrestricted()) Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        else Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:$packageName"))
        startActivity(i)
    }

    private fun courseName(): String {
        val f = java.io.File(filesDir, HikeService.PACKAGE_FILE)
        return runCatching {
            val text = if (f.exists()) f.readText() else assets.open("suraksan_package.json").use { it.readBytes().decodeToString() }
            val pkg = app.hiking.guidance.CoursePackage.parse(text)
            "${pkg.mountain.name} · ${pkg.course.name}" + if (f.exists()) "" else " (내장 시험 코스)"
        }.getOrDefault("알 수 없음")
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onPause() { handler.removeCallbacksAndMessages(null); super.onPause() }
    override fun onDestroy() { speaker?.shutdown(); super.onDestroy() }
}
