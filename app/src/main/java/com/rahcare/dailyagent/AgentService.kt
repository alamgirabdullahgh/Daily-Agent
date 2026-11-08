package com.rahcare.dailyagent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** সার্বক্ষণিক কান: ফোনের পর্দা বন্ধ থাকলেও "My Agent" শোনে, তারপর কমান্ড শুনে কাজ করে। */
class AgentService : Service() {
    companion object {
        @Volatile var running = false
        @Volatile var status = ""
        private const val CH = "agent"
        fun start(c: Context) { ContextCompat.startForegroundService(c, Intent(c, AgentService::class.java)) }
        fun stop(c: Context) { c.stopService(Intent(c, AgentService::class.java)) }
    }

    private val main = Handler(Looper.getMainLooper())
    private var worker: Thread? = null
    private var wake: WakeWord? = null
    private var rec: SpeechRecognizer? = null

    private fun notif(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null) nm.createNotificationChannel(NotificationChannel(CH, "Daily Agent", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CH).setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Daily Agent").setContentText(text).setContentIntent(pi).setOngoing(true).build()
    }

    private fun note(t: String) {
        status = t
        try { getSystemService(NotificationManager::class.java).notify(1, notif(t)) } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notif("চালু হচ্ছে…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(1, n)
        if (worker == null) {
            running = true
            worker = Thread { loop() }.also { it.start() }
        }
        return START_STICKY
    }

    private fun loop() {
        val w = WakeWord(this); wake = w
        while (running) {
            try {
                if (!WakeWord.ready(this)) {
                    note("ভয়েস মডেল নামছে…")
                    WakeWord.download(this) { note(it) }
                }
                note("'My Agent' বলে ডাকুন")
                if (!w.waitForWake { !running }) break
                try { ToneGenerator(AudioManager.STREAM_MUSIC, 100).startTone(ToneGenerator.TONE_PROP_BEEP, 180) } catch (_: Exception) {}
                Thread.sleep(250)
                note("শুনছি… কমান্ড বলুন")
                val cmd = capture()
                if (cmd.isBlank()) { note("কিছু শুনতে পাইনি"); continue }
                note("কাজ চলছে: " + cmd.take(40))
                val res = UiAgent.run(this, cmd)
                note("শেষ: " + res.take(40))
                Say.say(this, res)
            } catch (e: Exception) {
                note("ত্রুটি: " + (e.message ?: e.javaClass.simpleName).take(70))
                try { Thread.sleep(4000) } catch (_: InterruptedException) {}
            }
        }
        w.close()
    }

    /** ডাকের পর কমান্ড শোনে (Google স্পিচ-রিকগনিশন; বাংলা/ইংরেজি) */
    private fun capture(): String {
        val latch = CountDownLatch(1)
        val out = arrayOf("")
        val lang = Cfg.lang(this)
        main.post {
            try {
                val r = SpeechRecognizer.createSpeechRecognizer(this)
                rec = r
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(p: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(v: Float) {}
                    override fun onBufferReceived(b: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(e: Int) { latch.countDown() }
                    override fun onResults(b: Bundle?) {
                        out[0] = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                        latch.countDown()
                    }
                    override fun onPartialResults(b: Bundle?) {}
                    override fun onEvent(t: Int, b: Bundle?) {}
                })
                r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                })
            } catch (_: Exception) { latch.countDown() }
        }
        latch.await(20, TimeUnit.SECONDS)
        main.post { try { rec?.destroy() } catch (_: Exception) {}; rec = null }
        return out[0]
    }

    override fun onDestroy() {
        running = false; status = ""
        worker?.interrupt(); worker = null
        wake?.close()
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}
