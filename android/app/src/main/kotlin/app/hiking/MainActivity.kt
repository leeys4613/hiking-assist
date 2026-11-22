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
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Phase 1 화면: 권한 단계 안내, 음성 테스트, 산행 시작/시뮬레이션/종료. 웹 UI(WebView)는 Phase 2. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var info: TextView
    private var speaker: Speaker? = null

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        speaker = Speaker(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad) }
        info = TextView(this).apply { textSize = 15f }
        root.addView(TextView(this).apply { text = "등산 안내 (수락산 시험 코스)"; textSize = 20f; gravity = Gravity.START })
        root.addView(info)
        fun button(label: String, onClick: () -> Unit) {
            root.addView(Button(this).apply { text = label; setOnClickListener { onClick() } })
        }
        button("1) 권한 허용 (위치 → 알림 → 항상 허용 순서)") { requestNextPermission() }
        button("2) 배터리 '제한 없음' 설정 열기") { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        button("음성 테스트 (음악 켜고 눌러 보세요)") {
            speaker?.speak("정상까지 2.3킬로, 약 55분 남았어요. 현재 페이스 킬로당 22분이에요.") { }
        }
        button("산행 시작 (실제 GPS)") { startService(HikeService.ACTION_START) }
        button("시뮬레이션 (실내 테스트, 20배속)") { startService(HikeService.ACTION_SIM) }
        button("일시정지 / 재개") { startService(HikeService.ACTION_PAUSE) }
        button("종료") { startService(HikeService.ACTION_STOP) }
        setContentView(ScrollView(this).apply { addView(root) })

        // 디버그 빌드 전용: `adb shell am start -n app.hiking/.MainActivity --ez sim true` 로 시뮬레이션 자동 시작
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable && intent?.getBooleanExtra("sim", false) == true) startService(HikeService.ACTION_SIM)
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
            appendLine("상태: ${HikeService.status}")
            appendLine()
            appendLine("※ 삼성: 설정 > 배터리 > 백그라운드 사용 제한에서 이 앱을 '제한 없음'으로 두세요.")
        }
        handler.postDelayed({ refresh() }, 1000)
    }

    override fun onResume() { super.onResume(); refresh() }
    override fun onPause() { handler.removeCallbacksAndMessages(null); super.onPause() }
    override fun onDestroy() { speaker?.shutdown(); super.onDestroy() }
}
