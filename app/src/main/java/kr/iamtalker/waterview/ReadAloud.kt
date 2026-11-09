package kr.iamtalker.waterview

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Reads text aloud with the phone's default text-to-speech voice, so no voice is bundled and nothing
 * leaves the phone. An engine takes only a few thousand characters at a time, so the text goes out in
 * pieces, one after the next. [onDone] runs on the main thread when the last piece has been read, or
 * when reading is stopped.
 */
internal class ReadAloud(context: Context, private val onDone: () -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var ready = false
    private var playing = false
    private val queue = ArrayDeque<String>()

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            val tts = engine ?: return@TextToSpeech
            ready = status == TextToSpeech.SUCCESS
            if (!ready) {
                finish()
                return@TextToSpeech
            }
            tts.language = Locale.getDefault()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    main.post { speakNext() }
                }
                @Deprecated("Deprecated in the framework")
                override fun onError(utteranceId: String?) {
                    main.post { finish() }
                }
            })
            if (playing) speakNext()
        }
    }

    /** Whether text is being read now. */
    val isPlaying: Boolean get() = playing

    /** Starts reading [text] from its beginning, stopping whatever was being read before. */
    fun start(text: String) {
        queue.clear()
        queue.addAll(pieces(text))
        if (queue.isEmpty()) return
        playing = true
        if (ready) speakNext()
    }

    /** Stops reading at once. */
    fun stop() {
        if (!playing) return
        finish()
    }

    /** Releases the engine. Call once the screen is gone. */
    fun release() {
        playing = false
        queue.clear()
        engine?.stop()
        engine?.shutdown()
        engine = null
    }

    private fun speakNext() {
        if (!playing) return
        val next = queue.removeFirstOrNull()
        if (next == null) {
            finish()
            return
        }
        engine?.speak(next, TextToSpeech.QUEUE_FLUSH, null, "waterview")
    }

    private fun finish() {
        val was = playing
        playing = false
        queue.clear()
        engine?.stop()
        if (was) onDone()
    }

    internal companion object {
        /** Cut at a line break near [size] characters where one is there, so no sentence is cut in half. */
        internal fun pieces(text: String, size: Int = 3000): List<String> {
            val out = mutableListOf<String>()
            var rest = text
            while (rest.length > size) {
                val cut = rest.lastIndexOf('\n', size).takeIf { it > 0 } ?: size
                out += rest.substring(0, cut)
                rest = rest.substring(cut).trimStart('\n')
            }
            if (rest.isNotBlank()) out += rest
            return out.filter { it.isNotBlank() }
        }
    }
}
