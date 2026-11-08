package com.rahcare.dailyagent

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** মুখ: ফোনের নিজস্ব Text-to-speech (বাংলা/ইংরেজি) */
object Say {
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    private fun init(c: Context) {
        if (tts != null) return
        tts = TextToSpeech(c.applicationContext) { st -> ready = st == TextToSpeech.SUCCESS }
    }

    fun say(c: Context, text: String, wait: Boolean = true) {
        if (text.isBlank()) return
        init(c)
        val t = tts ?: return
        var n = 0
        while (!ready && n++ < 40) Thread.sleep(100)
        if (!ready) return
        val bn = text.any { it in '\u0980'..'\u09FF' }
        try { t.language = if (bn) Locale("bn", "BD") else Locale.US } catch (_: Exception) {}
        val latch = CountDownLatch(1)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) { latch.countDown() }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { latch.countDown() }
        })
        t.speak(text.take(1500), TextToSpeech.QUEUE_FLUSH, null, "u" + System.nanoTime())
        if (wait) latch.await(40, TimeUnit.SECONDS)
    }
}
