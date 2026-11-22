package app.hiking

import android.content.Context
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 서버(tailnet HTTPS) 접속 설정과 최소 HTTP 클라이언트. URL/토큰은 앱에 하드코딩하지 않고 SharedPreferences 에 저장한다. */
object Api {
    class HttpError(val code: Int, msg: String) : IOException("HTTP $code $msg")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    fun baseUrl(ctx: Context): String = prefs(ctx).getString("url", "")!!.trim().trimEnd('/')
    fun token(ctx: Context): String = prefs(ctx).getString("token", "")!!.trim()
    fun configured(ctx: Context) = baseUrl(ctx).startsWith("https://") && token(ctx).isNotEmpty()

    fun save(ctx: Context, url: String, token: String) {
        prefs(ctx).edit().putString("url", url.trim()).putString("token", token.trim()).apply()
    }

    private fun open(ctx: Context, path: String, method: String): HttpURLConnection {
        val c = URL(baseUrl(ctx) + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10_000
        c.readTimeout = 30_000
        c.setRequestProperty("Authorization", "Bearer " + token(ctx))
        return c
    }

    private fun read(c: HttpURLConnection): ByteArray {
        val code = c.responseCode
        val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        if (code !in 200..299) throw HttpError(code, body.decodeToString().take(200))
        return body
    }

    @Throws(IOException::class)
    fun get(ctx: Context, path: String): ByteArray {
        val c = open(ctx, path, "GET")
        try { return read(c) } finally { c.disconnect() }
    }

    /** 성공(2xx)이면 응답 본문, 아니면 [HttpError]. 네트워크 오류는 IOException. */
    @Throws(IOException::class)
    fun postJson(ctx: Context, path: String, json: String): ByteArray {
        val c = open(ctx, path, "POST")
        try {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(json.toByteArray()) }
            return read(c)
        } finally { c.disconnect() }
    }
}
