package com.rahcare.dailyagent

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL

/** Daily Agent-এর নিজের আপডেট: GitHub-এর সর্বশেষ রিলিজ দেখে, নামিয়ে অ্যাপের ভেতর থেকেই ইনস্টল করে (Daily Browser-এর মতো) */
object SelfUpdate {
    private const val REPO = "alamgirabdullahgh/Daily-Agent"
    private const val APK = "DailyAgent.apk"
    class Info(val v: Int, val tag: String, val url: String)

    /** অ্যাপের পর্দা সামনে আছে কি না (MainActivity ঠিক করে) */
    @Volatile var fg = false
    /** ইনস্টলের অবস্থা: "" = কিছু না, নইলে পর্দায় দেখানোর লেখা */
    @Volatile var state = ""
    @Volatile var busy = false

    fun curVer(c: Context): Int {
        val pi = c.packageManager.getPackageInfo(c.packageName, 0)
        @Suppress("DEPRECATION")
        return if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else pi.versionCode
    }

    /** ব্যাকগ্রাউন্ড থ্রেডে ডাকুন। ট্যাগের শেষ সংখ্যাই সংস্করণ। নেট না থাকলে null */
    fun latest(): Info? = try {
        val cn = URL("https://github.com/$REPO/releases/latest").openConnection() as HttpURLConnection
        cn.instanceFollowRedirects = false; cn.connectTimeout = 10000; cn.readTimeout = 10000
        val tag = (cn.getHeaderField("Location") ?: "").substringAfterLast('/')
        cn.disconnect()
        Info(tag.substringAfterLast('.').toInt(), tag, "https://github.com/$REPO/releases/download/$tag/$APK")
    } catch (e: Exception) { null }

    private fun openOk(url: String): HttpURLConnection {
        var last = "?"
        for (i in 1..6) {
            try {
                val cn = URL(url).openConnection() as HttpURLConnection
                cn.connectTimeout = 15000; cn.readTimeout = 30000
                if (cn.responseCode == 200) return cn
                last = "HTTP ${cn.responseCode}"; cn.disconnect()
            } catch (e: java.io.IOException) { last = e.javaClass.simpleName }
            try { Thread.sleep(1500L * i) } catch (_: Exception) {}
        }
        throw Exception("$last (৬ বার চেষ্টা)")
    }

    /** "অজানা উৎস" অনুমতি আছে কি না; না থাকলে সেটিংসের পর্দা খুলে দেয় */
    fun ensureAllowed(c: Context): Boolean {
        if (c.packageManager.canRequestPackageInstalls()) return true
        try {
            c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {}
        return false
    }

    /** APK নামিয়ে PackageInstaller-এ দেয়; ব্যাকগ্রাউন্ড থ্রেডে ডাকুন। ব্যর্থ হলে বার্তা ফেরত */
    fun install(c: Context, url: String, progress: (Int) -> Unit): String? {
        val inst = c.packageManager.packageInstaller
        val p = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = inst.createSession(p)
        return try {
            val s = inst.openSession(id)
            val cn = openOk(url)
            val total = cn.contentLengthLong
            try {
                cn.inputStream.use { ins ->
                    s.openWrite("a", 0, -1).use { out ->
                        val buf = ByteArray(32 * 1024); var done = 0L; var last = -1
                        while (true) {
                            val n = ins.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (total > 0) { val pct = (done * 100 / total).toInt(); if (pct != last) { last = pct; progress(pct) } }
                        }
                        s.fsync(out)
                    }
                }
            } finally { cn.disconnect() }
            val pi = PendingIntent.getBroadcast(c, id, Intent(c, SelfUpdateResult::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            // আপডেটের পর BootReceiver "selfupd" ধরে অ্যাপ আবার খোলে (AgentAccessibility)
            s.commit(pi.intentSender); null
        } catch (e: Exception) {
            try { inst.abandonSession(id) } catch (_: Exception) {}
            "${e.javaClass.simpleName}: ${e.message?.take(70)}"
        }
    }
}

/** ইনস্টলারের ফল: নিশ্চিত-পর্দা লাগলে খোলে (আপডেট-বোতাম AgentAccessibility নিজে চাপে) */
class SelfUpdateResult : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val ui = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (ui != null) {
                    ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    // অ্যাপ ব্যাকগ্রাউন্ডে থাকলে রিসিভার থেকে সরাসরি পর্দা খোলা আটকায়; অ্যাক্সেসিবিলিটি সার্ভিস চালু থাকলে তার মাধ্যমে খুলি
                    val svc = AgentAccessibility.inst
                    try { if (svc != null) svc.startActivity(ui) else c.startActivity(ui) }
                    catch (_: Exception) { try { c.startActivity(ui) } catch (_: Exception) {} }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> {
                SelfUpdate.busy = false; SelfUpdate.state = ""
                Toast.makeText(c, "আপডেট হয়নি: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }
}
