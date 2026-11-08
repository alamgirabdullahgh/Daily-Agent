package com.rahcare.dailyagent

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import org.json.JSONObject

/**
 * ভয়েস-কমান্ড চালক: পর্দার অবস্থা Gemini-কে দেখায়, Gemini পরের একটি কাজ বলে (চাপো/লেখো/স্ক্রল/অ্যাপ খোলো...),
 * আমরা সেটা করি; কাজ শেষ না হওয়া পর্যন্ত এভাবে ঘুরে।
 */
object UiAgent {
    private const val MAX_STEPS = 30

    private val SYSTEM = """
You control an Android phone for the user through an accessibility service. The user speaks Bengali or English.
Each turn you get: the command, the list of installed apps (label=package), whether the phone is locked, the foreground app, and the visible screen as lines:
index|text|contentDescription|hint|viewId|flags   (flags: C=clickable E=editable S=scrollable K=checked)
Reply with ONLY one JSON object, the single next action:
{"a":"open_app","pkg":"<package from the list>"}
{"a":"open_url","url":"https://..."}
{"a":"click","id":<index>}
{"a":"type","id":<index of editable>,"text":"...","submit":true|false}
{"a":"scroll","dir":"down|up"}
{"a":"back"} {"a":"home"} {"a":"wait"}
{"a":"say","text":"short progress message"}
{"a":"done","text":"final reply to the user in the user's language"}
Rules:
- Work step by step. After opening an app or clicking, the next screen is shown to you next turn.
- If the command is just a question/chat/translation that needs no phone action, answer immediately with done (full helpful answer in the user's language).
- Choose apps only from the installed list. Match names loosely (Bengali/English, e.g. "ইউটিউব" = YouTube, "হোয়াটসঅ্যাপ" = WhatsApp).
- For search boxes: click or type into the editable field, use type with submit=true, otherwise click the search button.
- To send something to a contact in WhatsApp: open WhatsApp, search the contact name, open the chat, attach/send as asked. For a downloaded file/audio, use the attach (paperclip) option and pick the newest file/audio.
- Never type or reveal passwords, never make payments, never delete data, never change phone security settings. If something like that is needed, finish with done and say so.
- If the phone is locked with a PIN/pattern, you cannot use the screen: finish with done and ask the user to unlock and say the command again (answering plain questions is still fine).
- If you are stuck (same screen after several tries) or a login/permission/captcha wall blocks you, finish with done and explain briefly.
- Keep "done"/"say" text short and natural. Never output anything except the JSON object.
""".trimIndent()

    private fun apps(c: Context): String {
        val pm = c.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0)
            .map { it.loadLabel(pm).toString().replace("\n", " ") + "=" + it.activityInfo.packageName }
            .distinct().sorted().take(220).joinToString("; ")
    }

    private fun wakeScreen(c: Context): PowerManager.WakeLock? = try {
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        pm.newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "dailyagent:wake").also { it.acquire(3 * 60_000L) }
    } catch (_: Exception) { null }

    fun run(c: Context, cmd: String): String {
        val svc = AgentAccessibility.inst
        val wl = wakeScreen(c)
        try {
            val km = c.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (svc != null && km.isKeyguardLocked && !km.isKeyguardSecure) { svc.swipeUp(); Thread.sleep(1200) }
            val locked = km.isKeyguardLocked
            val appList = apps(c)
            val history = ArrayList<String>()
            var lastSig = ""; var same = 0
            for (step in 1..MAX_STEPS) {
                val (screen, nodes) = if (svc != null && !locked) svc.dump() else ("(পর্দা পাওয়া যাচ্ছে না)" to emptyList())
                val prompt = "Command: $cmd\nPhone locked: $locked\nAccessibility service on: ${svc != null}\nForeground app: ${svc?.currentPackage() ?: ""}\n" +
                    "Installed apps: $appList\nActions so far:\n${history.takeLast(10).joinToString("\n")}\nScreen:\n${screen.take(9000)}"
                val raw = Brain.ask(c, SYSTEM, prompt) ?: return "Gemini থেকে উত্তর আনা যায়নি: " + Brain.lastErr
                val j = try {
                    val t = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                    if (t.startsWith("[")) org.json.JSONArray(t).getJSONObject(0) else JSONObject(t)
                } catch (_: Exception) { return "কথাটা বুঝতে পারিনি" }
                val a = j.optString("a")
                val sig = j.toString()
                if (sig == lastSig) same++ else { same = 0; lastSig = sig }
                if (same >= 3) return "একই কাজে আটকে গেছি, থামলাম"
                history.add("$step: $sig")
                when (a) {
                    "done" -> return j.optString("text").ifBlank { "হয়ে গেছে" }
                    "say" -> Say.say(c, j.optString("text"), wait = false)
                    "wait" -> Thread.sleep(1500)
                    "back" -> { svc?.back() }
                    "home" -> { svc?.home() }
                    "open_app" -> {
                        val ok = svc?.launch(j.optString("pkg")) == true
                        if (!ok) history.add("  (এই অ্যাপ খোলা যায়নি)")
                        Thread.sleep(2200)
                    }
                    "open_url" -> { svc?.openUrl(j.optString("url")); Thread.sleep(2200) }
                    "click" -> {
                        val n = nodes.getOrNull(j.optInt("id", -1))
                        if (n == null || svc?.click(n) != true) history.add("  (চাপা যায়নি)")
                    }
                    "type" -> {
                        val n = nodes.getOrNull(j.optInt("id", -1))
                        if (n == null || svc?.setText(n, j.optString("text")) != true) history.add("  (লেখা যায়নি)")
                        else if (j.optBoolean("submit")) { Thread.sleep(500); svc.submit(n) }
                    }
                    "scroll" -> { svc?.scroll(nodes, j.optString("dir") != "up") }
                    else -> return "অচেনা কাজ: $a"
                }
                if (a != "open_app" && a != "open_url") Thread.sleep(1200)
            }
            return "কাজটা শেষ করা গেল না, ধাপ বেশি হয়ে গেছে"
        } finally {
            try { if (wl?.isHeld == true) wl.release() } catch (_: Exception) {}
        }
    }
}
