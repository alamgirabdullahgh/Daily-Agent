package com.rahcare.dailyagent

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * "My Agent" ডাক শোনা: Vosk অফলাইন ইঞ্জিন, শুধু দুটো শব্দের তালিকা ("my agent") নিয়ে চলে।
 * আস্তে বললেও ধরার জন্য প্রতিটি টুকরোর শব্দ নিজে থেকে বাড়ানো হয় (AGC)।
 */
class WakeWord(private val ctx: Context) {
    companion object {
        private const val URL_MODEL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
        fun dir(c: Context) = File(c.filesDir, "vosk-model")
        fun ready(c: Context) = File(dir(c), "am/final.mdl").exists()

        /** ~40MB মডেল নামিয়ে খোলে (একবারই) */
        fun download(c: Context, note: (String) -> Unit) {
            val tmp = File(c.cacheDir, "vosk.zip")
            val cn = URL(URL_MODEL).openConnection() as HttpURLConnection
            cn.connectTimeout = 20000; cn.readTimeout = 30000
            val total = cn.contentLengthLong
            var got = 0L; var lastPct = -1
            cn.inputStream.use { ins -> tmp.outputStream().use { out ->
                val b = ByteArray(32768)
                while (true) {
                    val n = ins.read(b); if (n <= 0) break
                    out.write(b, 0, n); got += n
                    if (total > 0) { val p = (got * 100 / total).toInt(); if (p != lastPct && p % 5 == 0) { lastPct = p; note("ভয়েস মডেল নামছে $p%") } }
                }
            } }
            cn.disconnect()
            note("ভয়েস মডেল খোলা হচ্ছে…")
            val d = dir(c); d.deleteRecursively(); d.mkdirs()
            ZipInputStream(tmp.inputStream()).use { z ->
                while (true) {
                    val e = z.nextEntry ?: break
                    val rel = e.name.substringAfter('/', "")   // প্রথম ফোল্ডারের নাম বাদ
                    if (rel.isEmpty()) continue
                    val f = File(d, rel)
                    if (!f.canonicalPath.startsWith(d.canonicalPath)) continue
                    if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) } }
                }
            }
            tmp.delete()
        }
    }

    private var model: Model? = null

    /** "my agent" শোনা পর্যন্ত আটকে থাকে। শুনলে true; stop() true হলে false */
    fun waitForWake(stop: () -> Boolean): Boolean {
        val m = model ?: Model(dir(ctx).absolutePath).also { model = it }
        val rec = Recognizer(m, 16000f, "[\"my agent\", \"[unk]\"]")
        val minBuf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val ar = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 8192) * 2
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) { ar.release(); rec.close(); throw IllegalStateException("মাইক খোলা যায়নি (অন্য অ্যাপ মাইক ধরে আছে?)") }
        ar.startRecording()
        val data = ShortArray(2048)
        try {
            while (!stop()) {
                val n = ar.read(data, 0, data.size)
                if (n <= 0) { Thread.sleep(20); continue }
                var peak = 0
                for (i in 0 until n) { val a = Math.abs(data[i].toInt()); if (a > peak) peak = a }
                if (peak in 100..9000) {
                    val g = minOf(9000f / peak, 25f)
                    for (i in 0 until n) data[i] = (data[i] * g).toInt().coerceIn(-32768, 32767).toShort()
                }
                val fin = rec.acceptWaveForm(data, n)
                val txt = if (fin) rec.result else rec.partialResult
                if (txt.contains("my agent")) return true
            }
        } finally {
            try { ar.stop() } catch (_: Exception) {}
            ar.release(); rec.close()
        }
        return false
    }

    fun close() { try { model?.close() } catch (_: Exception) {}; model = null }
}
