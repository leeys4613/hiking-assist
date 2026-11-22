package app.hiking

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * TTS 출력 + 오디오 포커스(명세 §5.5).
 * - 안내 전 AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK 로 음악 볼륨을 줄이고, 블루투스 지연을 위해 300ms 뒤에 발화한다.
 * - onDone / onError / 워치독 어느 경로로든 반드시 포커스를 반납한다.
 */
class Speaker(ctx: Context) : TextToSpeech.OnInitListener {
    private val handler = Handler(Looper.getMainLooper())
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attrs)
        .setOnAudioFocusChangeListener { }
        .build()
    private var tts: TextToSpeech? = TextToSpeech(ctx.applicationContext, this)
    private var onDone: (() -> Unit)? = null
    private var utteranceSeq = 0
    private var currentId: String? = null

    @Volatile var ready = false
        private set
    @Volatile var statusText = "음성 엔진 초기화 중…"
        private set
    @Volatile var busy = false
        private set

    override fun onInit(status: Int) {
        val t = tts ?: return
        if (status != TextToSpeech.SUCCESS) { statusText = "TTS 초기화 실패 — 설정에서 음성 엔진을 확인하세요"; return }
        val r = t.setLanguage(Locale.KOREA)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            statusText = "한국어 음성 데이터 없음 — 설정 > 일반 > 텍스트 음성 변환에서 한국어를 설치하세요"
            return
        }
        t.setAudioAttributes(attrs)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { handler.post { finish(utteranceId) } }
            @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { handler.post { finish(utteranceId) } }
            override fun onError(utteranceId: String?, errorCode: Int) { handler.post { finish(utteranceId) } }
        })
        val offline = t.voices?.any {
            it.locale.language == "ko" && !it.isNetworkConnectionRequired && !it.features.contains("notInstalled")
        } == true
        statusText = if (offline) "한국어 음성 OK (오프라인)" else "한국어 음성이 온라인 전용일 수 있음 — 오프라인 음성 설치를 권장"
        ready = true
    }

    /** [text] 를 말하고, 끝나면(성공/실패 무관) [done] 을 메인 스레드에서 호출한다. */
    fun speak(text: String, done: () -> Unit) {
        val t = tts
        if (!ready || t == null) { handler.post(done); return }
        busy = true
        onDone = done
        am.requestAudioFocus(focusRequest)
        val id = "u${++utteranceSeq}"
        currentId = id
        handler.postDelayed({
            if (currentId == id) t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        }, 300)
        handler.postDelayed({ finish(id) }, 30_000) // 워치독: 어떤 이유로 콜백이 없어도 포커스를 놓는다
    }

    private fun finish(id: String?) {
        if (id == null || id != currentId) return
        currentId = null
        am.abandonAudioFocusRequest(focusRequest)
        busy = false
        val d = onDone
        onDone = null
        d?.invoke()
    }

    fun stop() {
        tts?.stop()
        finish(currentId)
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }
}
