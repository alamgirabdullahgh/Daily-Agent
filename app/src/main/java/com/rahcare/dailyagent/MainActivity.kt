package com.rahcare.dailyagent

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val h = Handler(Looper.getMainLooper())
    private lateinit var st: TextView
    private lateinit var bListen: Button
    private lateinit var bLang: Button
    private lateinit var bAuto: Button
    private lateinit var bUpd: Button
    private var newer: SelfUpdate.Info? = null
    private val tick = object : Runnable { override fun run() { refresh(); h.postDelayed(this, 1000) } }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; val p = (16 * dp).toInt(); setPadding(p, p, p, p) }
        fun tv(t: String, size: Float = 14f) = TextView(this).apply { text = t; textSize = size }.also { root.addView(it) }
        fun btn(t: String, f: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } }.also { root.addView(it) }

        tv("Daily Agent v0.1.${SelfUpdate.curVer(this)}", 24f)
        tv("'My Agent' বলে ডাকুন, তারপর কমান্ড দিন। আপডেটের নিশ্চিত-বোতামও নিজে চাপে।")
        st = tv("")

        val key = EditText(this).apply {
            hint = "Gemini API কী (aistudio.google.com থেকে)"; setText(Cfg.key(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD; setSingleLine()
        }.also { root.addView(it) }
        btn("কী সংরক্ষণ") { Cfg.setKey(this, key.text.toString()); Toast.makeText(this, "সংরক্ষিত", Toast.LENGTH_SHORT).show() }

        btn("১) মাইক ও নোটিফিকেশন অনুমতি") {
            val p = ArrayList<String>(); p.add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(p.toTypedArray(), 1)
        }
        btn("২) অ্যাক্সেসিবিলিটি চালু করুন") {
            Toast.makeText(this, "Daily Agent বেছে চালু করুন। বন্ধ দেখালে: অ্যাপ তথ্য › ⋮ › Allow restricted settings", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btn("৩) ব্যাটারি সীমা বন্ধ (ব্যাকগ্রাউন্ডে শুনতে)") {
            try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
        bListen = btn("") {
            if (AgentService.running) { Cfg.setListening(this, false); AgentService.stop(this) }
            else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) Toast.makeText(this, "আগে ১) নম্বর বোতামে মাইকের অনুমতি দিন", Toast.LENGTH_LONG).show()
            else { Cfg.setListening(this, true); AgentService.start(this) }
            refresh()
        }
        bLang = btn("") { Cfg.setLang(this, if (Cfg.lang(this) == "bn-BD") "en-US" else "bn-BD"); refresh() }
        bAuto = btn("") { Cfg.setAutoUpdate(this, !Cfg.autoUpdate(this)); refresh() }
        bUpd = btn("") { onUpdateClick() }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun checkNewer() {
        if (SelfUpdate.busy) return
        Thread {
            val i = SelfUpdate.latest()
            runOnUiThread { newer = i?.takeIf { it.v > SelfUpdate.curVer(this) }; refresh() }
        }.start()
    }

    private fun onUpdateClick() {
        if (SelfUpdate.busy) return
        val n = newer
        if (n == null) { SelfUpdate.state = "খোঁজা হচ্ছে…"; refresh(); checkNewer(); h.postDelayed({ if (newer == null) { SelfUpdate.state = ""; Toast.makeText(this, "নতুন সংস্করণ নেই, আপনি সর্বশেষটায় আছেন", Toast.LENGTH_SHORT).show(); refresh() } }, 4000); return }
        if (!SelfUpdate.ensureAllowed(this)) { Toast.makeText(this, "\"অজানা উৎস\" অনুমতি দিয়ে ফিরে এসে আবার চাপুন", Toast.LENGTH_LONG).show(); return }
        SelfUpdate.busy = true; SelfUpdate.state = "নামছে 0%"; refresh()
        Thread {
            val err = SelfUpdate.install(this, n.url) { p -> SelfUpdate.state = if (p < 100) "নামছে $p%" else "ইনস্টল হচ্ছে…" }
            if (err != null) runOnUiThread { SelfUpdate.busy = false; SelfUpdate.state = ""; Toast.makeText(this, "আপডেট হয়নি: $err", Toast.LENGTH_LONG).show(); refresh() }
        }.start()
    }

    private fun refresh() {
        val a11y = (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains("$packageName/")
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val bat = (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)
        st.text = "মাইক অনুমতি: ${if (mic) "✔" else "✘"}\nঅ্যাক্সেসিবিলিটি: ${if (a11y) "✔ চালু" else "✘ বন্ধ"}\nসার্ভিস সংযুক্ত: ${if (AgentAccessibility.inst != null) "✔" else "✘ নয় (অ্যাক্সেসিবিলিটি বন্ধ করে আবার চালু করুন)"}\nশেষ দেখা জানালা: ${AgentAccessibility.lastEvt.ifBlank { "এখনো কিছু আসেনি" }}\n" +
            "ব্যাটারি সীমা মুক্ত: ${if (bat) "✔" else "✘"}\nভয়েস মডেল: ${if (WakeWord.ready(this)) "✔ আছে" else "এখনো নামেনি (শোনা চালু করলে নামবে)"}\n" +
            "আপডেট-ডায়ালগ শেষ দেখা: ${Cfg.p(this).getString("last_inst", null)?.let { it + if (Cfg.p(this).getBoolean("last_inst_ok", false)) " (চাপা ✔)" else " (চাপা যায়নি ✘)" } ?: "এখনো দেখা যায়নি"}\n" +
            "Gemini কী: ${if (Cfg.key(this).isNotBlank()) "✔" else "✘ নেই"}\nঅবস্থা: ${if (AgentService.running) AgentService.status.ifBlank { "চলছে" } else "বন্ধ"}\n"
        bListen.text = if (AgentService.running) "⏹ শোনা বন্ধ করুন" else "▶ শোনা চালু করুন ('My Agent')"
        bLang.text = "কমান্ডের ভাষা: " + if (Cfg.lang(this) == "bn-BD") "বাংলা (ট্যাপে ইংরেজি)" else "English (ট্যাপে বাংলা)"
        bUpd.text = when {
            SelfUpdate.state.isNotEmpty() -> SelfUpdate.state
            newer != null -> "⬇ নতুন সংস্করণ v0.1.${newer!!.v} — আপডেট করুন"
            else -> "🔄 আপডেট খুঁজুন"
        }
        bUpd.isEnabled = !SelfUpdate.busy
        bAuto.text = "আপডেট-বোতাম নিজে চাপা: " + if (Cfg.autoUpdate(this)) "চালু" else "বন্ধ"
    }

    override fun onResume() {
        super.onResume()
        h.post(tick)
        SelfUpdate.fg = true; checkNewer()
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (mic && Cfg.listening(this) && !AgentService.running) AgentService.start(this)
    }

    override fun onPause() { super.onPause(); SelfUpdate.fg = false; h.removeCallbacks(tick) }
}
