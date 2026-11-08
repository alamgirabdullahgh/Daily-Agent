package com.rahcare.dailyagent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** মস্তিষ্ক: Gemini। একটা মডেলের সীমা (429) শেষ হলে পরেরটায় যায়। */
object Brain {
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/"
    private val cool = HashMap<String, Long>()
    @Volatile var lastErr = ""

    private fun models(c: Context): List<String> {
        val p = Cfg.p(c)
        val saved = p.getString("models", "") ?: ""
        if (saved.isNotBlank() && System.currentTimeMillis() - p.getLong("models_at", 0L) < 6 * 3600_000L)
            return saved.split(",").filter { it.isNotBlank() }
        var list = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.0-flash")
        try {
            val cn = URL(BASE + "models?pageSize=200").openConnection() as HttpURLConnection
            cn.setRequestProperty("x-goog-api-key", Cfg.key(c)); cn.connectTimeout = 15000; cn.readTimeout = 20000
            if (cn.responseCode == 200) {
                val arr = JSONObject(cn.inputStream.bufferedReader().readText()).optJSONArray("models") ?: JSONArray()
                val names = ArrayList<String>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val n = o.optString("name").removePrefix("models/")
                    val ok = o.optJSONArray("supportedGenerationMethods")?.toString()?.contains("generateContent") == true
                    if (ok && n.startsWith("gemini") && listOf("tts", "image", "live", "embed", "audio", "robotics", "computer", "learnlm", "native").none { n.contains(it) }) names.add(n)
                }
                fun score(n: String) = when {
                    n.contains("flash") && !n.contains("lite") && !n.contains("preview") && !n.contains("exp") -> 0
                    n.contains("flash") && !n.contains("lite") -> 1
                    n.contains("lite") -> 2
                    else -> 3
                }
                if (names.isNotEmpty()) list = names.sortedWith(compareBy<String>({ score(it) }).thenByDescending { it }).take(5)
            }
            cn.disconnect()
        } catch (_: Exception) {}
        p.edit().putString("models", list.joinToString(",")).putLong("models_at", System.currentTimeMillis()).apply()
        return list
    }

    /** JSON-ভর্তি উত্তর (টেক্সট) দেয়; ব্যর্থ হলে null এবং lastErr-এ কারণ */
    fun ask(c: Context, system: String, user: String): String? {
        val key = Cfg.key(c)
        if (key.isBlank()) { lastErr = "Gemini কী দেওয়া নেই"; return null }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))))
            .put("generationConfig", JSONObject().put("responseMimeType", "application/json").put("temperature", 0.2))
            .toString()
        repeat(2) { round ->
            for (m in models(c)) {
                if ((cool[m] ?: 0L) > System.currentTimeMillis()) continue
                try {
                    val cn = URL(BASE + "models/$m:generateContent").openConnection() as HttpURLConnection
                    cn.requestMethod = "POST"; cn.doOutput = true
                    cn.setRequestProperty("Content-Type", "application/json"); cn.setRequestProperty("x-goog-api-key", key)
                    cn.connectTimeout = 20000; cn.readTimeout = 60000
                    cn.outputStream.use { it.write(body.toByteArray()) }
                    val code = cn.responseCode
                    if (code == 200) {
                        val j = JSONObject(cn.inputStream.bufferedReader().readText())
                        val t = j.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
                        cn.disconnect()
                        if (!t.isNullOrBlank()) return t
                        lastErr = "$m: খালি উত্তর"; continue
                    }
                    val err = try { cn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
                    cn.disconnect()
                    lastErr = "$m HTTP $code"
                    when (code) {
                        429 -> cool[m] = System.currentTimeMillis() + (if (err.contains("PerDay", true)) 3 * 3600_000L else 62_000L)
                        400, 403, 404 -> cool[m] = System.currentTimeMillis() + 10 * 60_000L
                    }
                } catch (e: Exception) { lastErr = m + ": " + (e.message ?: e.javaClass.simpleName).take(60) }
            }
            // সব মডেল বিরতিতে: সবচেয়ে কাছের বিরতি শেষ হওয়া পর্যন্ত (সর্বোচ্চ ৪০ সেকেন্ড) অপেক্ষা করে আবার একবার
            if (round == 0) {
                val soon = models(c).mapNotNull { cool[it] }.minOrNull() ?: return null
                val wait = soon - System.currentTimeMillis()
                if (wait in 1..40_000L) Thread.sleep(wait) else return null
            }
        }
        return null
    }
}
