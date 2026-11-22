package app.hiking

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.hiking.guidance.CoursePackage
import java.io.File
import kotlin.concurrent.thread

/**
 * 웹 UI(서버) 를 WebView 로 보여 주는 첫 화면. 코스 상세의 [이 코스로 출발] 이 JS 브리지(window.HikeApp)로
 * 코스 패키지를 내려받아 저장하고 산행 화면(ControlActivity)으로 넘어간다.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var msg: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (8 * resources.displayMetrics.density).toInt()
        val bar = LinearLayout(this).apply { setPadding(pad, pad * 3, pad, 0) }
        fun btn(label: String, f: () -> Unit) = bar.addView(Button(this).apply {
            text = label; setOnClickListener { f() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        btn("산행 화면") { startActivity(Intent(this, ControlActivity::class.java)) }
        btn("새로고침") { load() }
        btn("설정") { showSettings() }
        msg = TextView(this).apply { setPadding(pad * 2, pad, pad * 2, pad); visibility = android.view.View.GONE }

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            addJavascriptInterface(Bridge(), "HikeApp")
            webViewClient = object : WebViewClient() {
                // 우리 서버 밖으로는 나가지 않는다(JS 브리지 노출 범위 제한)
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest) =
                    !r.url.toString().startsWith(Api.baseUrl(this@MainActivity))
                override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) { msg.visibility = android.view.View.GONE }
                override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                    if (r.isForMainFrame) showMsg("서버에 연결하지 못했어요. 폰의 Tailscale이 켜져 있는지 확인하세요.\n(${e.description})")
                }
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar); addView(msg)
            addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        })
        if (Api.configured(this)) load() else showSettings()

        // 디버그 빌드 전용: `adb shell am start -n app.hiking/.MainActivity --ez sim true` 로 시뮬레이션 자동 시작
        val debuggable = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable && intent?.getBooleanExtra("sim", false) == true) {
            startForegroundService(Intent(this, HikeService::class.java).setAction(HikeService.ACTION_SIM))
        }
    }

    private fun showMsg(s: String) { msg.text = s; msg.visibility = android.view.View.VISIBLE }

    private fun load() {
        if (!Api.configured(this)) { showSettings(); return }
        val base = Api.baseUrl(this)
        // 토큰을 쿠키로 심어 서버의 웹 페이지 인증을 통과한다(로그인 화면을 거치지 않음)
        CookieManager.getInstance().setCookie(base, "hiking_token=${Api.token(this)}; Path=/; Secure; HttpOnly")
        CookieManager.getInstance().flush()
        web.loadUrl(base + "/")
    }

    private fun showSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val url = EditText(this).apply { hint = "https://….ts.net:8443"; setText(Api.baseUrl(this@MainActivity)) }
        val tok = EditText(this).apply { hint = "토큰"; setText(Api.token(this@MainActivity)) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, 0); addView(url); addView(tok) }
        AlertDialog.Builder(this).setTitle("서버 설정").setView(box)
            .setPositiveButton("저장") { _, _ -> Api.save(this, url.text.toString(), tok.text.toString()); load() }
            .setNegativeButton("취소", null).show()
    }

    /** 웹 페이지에서 호출되는 브리지. 서버 도메인 밖 페이지는 로드되지 않으므로 신뢰 범위는 우리 서버뿐이다. */
    inner class Bridge {
        @JavascriptInterface fun startCourse(courseId: String) {
            thread {
                try {
                    val bytes = Api.get(this@MainActivity, "/api/courses/" + java.net.URLEncoder.encode(courseId, "UTF-8") + "/package")
                    val pkg = CoursePackage.parse(bytes.decodeToString())   // 저장 전에 형식 검증
                    val tmp = File(filesDir, HikeService.PACKAGE_FILE + ".tmp")
                    tmp.writeBytes(bytes)
                    tmp.renameTo(File(filesDir, HikeService.PACKAGE_FILE))
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "코스 저장 완료: ${pkg.course.name}", Toast.LENGTH_SHORT).show()
                        startActivity(Intent(this@MainActivity, ControlActivity::class.java))
                    }
                } catch (e: Exception) {
                    runOnUiThread { showMsg("코스를 받지 못했어요: ${e.message}") }
                }
            }
        }
    }
}
