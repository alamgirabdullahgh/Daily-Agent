package com.rahcare.dailyagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * দুই কাজ:
 * ১) ফোনের "Update/Install" নিশ্চিত-পর্দার বোতাম নিজে চাপা, তারপর ইনস্টল শেষে "Open" চাপা
 *    (না পেলে ৫ সেকেন্ড পরে নিজে অ্যাপ খোলা)।
 * ২) ভয়েস-এজেন্টের হাত-চোখ: পর্দা পড়া, চাপা, লেখা, স্ক্রল, ব্যাক/হোম।
 */
class AgentAccessibility : AccessibilityService() {
    companion object {
        @Volatile var inst: AgentAccessibility? = null
        private val INSTALLERS = setOf(
            "com.google.android.packageinstaller", "com.android.packageinstaller",
            "com.samsung.android.packageinstaller", "com.miui.packageinstaller",
            "com.oplus.appdetail", "com.coloros.packageinstaller", "com.oppo.packageinstaller",
            "com.vivo.packageinstaller", "com.android.permissioncontroller"
        )
        // পুরো মিল। "install anyway" ইচ্ছা করে বাদ — ওটা নিরাপত্তা-সতর্কতা, নিজে চাপা হয় না
        private val OK = setOf("install", "update", "ইনস্টল", "আপডেট", "ইনস্টল করুন", "আপডেট করুন", "update app")
        private val OPEN = setOf("open", "খুলুন", "ওপেন", "চালু করুন", "খুলুন।")
    }

    private val main = Handler(Looper.getMainLooper())
    private var lastClick = 0L
    @Volatile private var updateClickAt = 0L
    @Volatile private var openedAt = 0L

    private val pkgRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val p = i.data?.schemeSpecificPart ?: return
            if (p == packageName) return
            // শুধু আমাদের চাপা আপডেটের পরে (৩ মিনিটের মধ্যে); ব্যাকগ্রাউন্ডে নিজে নিজে হওয়া আপডেটে কিছু খুলি না
            if (System.currentTimeMillis() - updateClickAt > 180_000L) return
            main.postDelayed({
                if (System.currentTimeMillis() - openedAt < 20_000L) return@postDelayed   // "Open" আগেই চাপা হয়েছে
                launch(p)
            }, 5000)
        }
    }

    override fun onServiceConnected() {
        inst = this
        try {
            registerReceiver(pkgRx, IntentFilter(Intent.ACTION_PACKAGE_REPLACED).apply { addDataScheme("package") })
        } catch (_: Exception) {}
        // নিজে আপডেট হয়ে আবার চালু হলে অ্যাপ খুলে দেয়
        if (Cfg.p(this).getBoolean("selfupd", false)) {
            Cfg.p(this).edit().putBoolean("selfupd", false).apply()
            launch(packageName)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        inst = null
        try { unregisterReceiver(pkgRx) } catch (_: Exception) {}
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val pkg = e?.packageName?.toString() ?: return
        if (pkg !in INSTALLERS || !Cfg.autoUpdate(this)) return
        val now = System.currentTimeMillis()
        if (now - lastClick < 1200) return
        val root = (try { rootInActiveWindow } catch (_: Exception) { null }) ?: return
        val btns = ArrayList<AccessibilityNodeInfo>()
        collectButtons(root, btns)
        fun label(b: AccessibilityNodeInfo) = (b.text ?: b.contentDescription)?.toString()?.trim()?.lowercase() ?: ""
        val open = btns.firstOrNull { label(it) in OPEN }
        if (open != null && click(open)) { lastClick = now; openedAt = now; return }
        val ok = btns.firstOrNull { label(it) in OK } ?: return
        if (click(ok)) { lastClick = now; updateClickAt = now }
    }

    private fun collectButtons(n: AccessibilityNodeInfo?, out: MutableList<AccessibilityNodeInfo>) {
        if (n == null) return
        if ((n.isClickable || n.className?.toString()?.endsWith("Button") == true) && n.isEnabled) out.add(n)
        for (i in 0 until n.childCount) collectButtons(n.getChild(i), out)
    }

    // ───────── এজেন্টের হাত-চোখ ─────────
    fun currentPackage(): String = try { rootInActiveWindow?.packageName?.toString() ?: "" } catch (_: Exception) { "" }

    /** পর্দার লেখা-বোতামের তালিকা (প্রতি লাইনে নম্বর) + নোডগুলো */
    fun dump(): Pair<String, List<AccessibilityNodeInfo>> {
        val root = (try { rootInActiveWindow } catch (_: Exception) { null }) ?: return "(কোনো পর্দা পড়া যাচ্ছে না)" to emptyList()
        val list = ArrayList<AccessibilityNodeInfo>()
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || list.size >= 160 || depth > 40) return
            if (n.isVisibleToUser) {
                val t = if (n.isPassword) "(পাসওয়ার্ড ঘর)" else (n.text?.toString()?.take(70)?.replace("\n", " ") ?: "")
                val d = n.contentDescription?.toString()?.take(70)?.replace("\n", " ") ?: ""
                val h = n.hintText?.toString()?.take(40) ?: ""
                val id = n.viewIdResourceName?.substringAfter('/') ?: ""
                if (t.isNotBlank() || d.isNotBlank() || n.isClickable || n.isEditable || n.isScrollable) {
                    val fl = buildString {
                        if (n.isClickable) append('C'); if (n.isEditable) append('E')
                        if (n.isScrollable) append('S'); if (n.isChecked) append('K')
                    }
                    sb.append(list.size).append('|').append(t).append('|').append(d).append('|').append(h).append('|').append(id).append('|').append(fl).append('\n')
                    list.add(n)
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return sb.toString() to list
    }

    fun click(n: AccessibilityNodeInfo): Boolean {
        var x: AccessibilityNodeInfo? = n
        var k = 0
        while (x != null && k++ < 5) {
            if (x.isClickable && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            x = x.parent
        }
        val r = Rect(); n.getBoundsInScreen(r)
        return tap(r.centerX(), r.centerY())
    }

    fun tap(x: Int, y: Int): Boolean {
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 60)).build()
        return dispatchGesture(g, null, null)
    }

    fun setText(n: AccessibilityNodeInfo, text: String): Boolean {
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val b = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)
    }

    /** কিবোর্ডের Enter/Search (Android 11+) */
    fun submit(n: AccessibilityNodeInfo): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 30) n.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id) else false

    fun scroll(nodes: List<AccessibilityNodeInfo>, down: Boolean): Boolean {
        val s = nodes.firstOrNull { it.isScrollable } ?: return false
        return s.performAction(if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)

    /** নিচ থেকে ওপরে টানা (সাধারণ লক-স্ক্রিন সরাতে) */
    fun swipeUp() {
        val dm = resources.displayMetrics
        val p = Path().apply { moveTo(dm.widthPixels / 2f, dm.heightPixels * 0.85f); lineTo(dm.widthPixels / 2f, dm.heightPixels * 0.2f) }
        dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 300)).build(), null, null)
    }

    fun launch(pkg: String): Boolean {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try { startActivity(i); true } catch (_: Exception) { false }
    }

    fun openUrl(url: String): Boolean = try {
        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    } catch (_: Exception) { false }
}
