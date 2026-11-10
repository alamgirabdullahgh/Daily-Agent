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
        /** শেষ যে অন্য-অ্যাপের ইভেন্ট এসেছে: সার্ভিস আসলে ইভেন্ট পাচ্ছে কি না দেখার জন্য (শুধু স্ট্যাটাসে) */
        @Volatile var lastEvt = ""
        private val INSTALLERS = setOf(
            "com.google.android.packageinstaller", "com.android.packageinstaller",
            "com.samsung.android.packageinstaller", "com.miui.packageinstaller",
            "com.oplus.appdetail", "com.coloros.packageinstaller", "com.oppo.packageinstaller",
            "com.vivo.packageinstaller", "com.android.permissioncontroller"
        )
        // পুরো মিল। "install anyway" ইচ্ছা করে বাদ — ওটা নিরাপত্তা-সতর্কতা, নিজে চাপা হয় না
        private val OK = setOf("install", "update", "ইনস্টল", "আপডেট", "ইনস্টল করুন", "আপডেট করুন", "update app")
        private val CANCEL = setOf("cancel", "বাতিল", "বাতিল করুন", "না", "no")
        private val PROMPTS = listOf("update this app", "install this app", "update this application", "install this application",
            "do you want to install", "do you want to update", "আপডেট করতে চান", "ইনস্টল করতে চান")
        private val OPEN = setOf("open", "খুলুন", "ওপেন", "চালু করুন", "খুলুন।")
    }

    private val main = Handler(Looper.getMainLooper())
    private var lastClick = 0L
    @Volatile private var updateClickAt = 0L
    @Volatile private var openedAt = 0L
    @Volatile private var attempts = 0          // একই ডায়ালগে কতবার চেপেছি (ফোন চাপ না নিলে আবার চেষ্টার জন্য)
    @Volatile private var lastDump = ""
    @Volatile private var learned: String? = null   // অচেনা নামের যে ইনস্টলারে আমরা আপডেট চেপেছি: পরের "Open" পর্দাও চেনার জন্য

    private val pkgRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val p = i.data?.schemeSpecificPart ?: return
            if (p == packageName) return
            // শুধু আমাদের চাপা আপডেটের পরে (৩ মিনিটের মধ্যে); ব্যাকগ্রাউন্ডে নিজে নিজে হওয়া আপডেটে কিছু খুলি না
            if (System.currentTimeMillis() - updateClickAt > 180_000L) return
            main.postDelayed({
                if (System.currentTimeMillis() - openedAt < 20_000L) return@postDelayed   // "Open" আগেই চাপা হয়েছে
                openAndVerify(p, 0)
            }, 2000)
        }
    }

    // কিছু ফোনে ইনস্টলার-ডায়ালগের ইভেন্ট সার্ভিসের কাছে পৌঁছায় না; তাই পর্দা জাগা থাকলে প্রতি ২.৫ সেকেন্ডে নিজে একবার দেখে নিই
    private val poll = object : Runnable {
        override fun run() {
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                if (Cfg.autoUpdate(this@AgentAccessibility) && pm.isInteractive) scan(false)
            } catch (_: Exception) {}
            main.postDelayed(this, 2500)
        }
    }

    override fun onServiceConnected() {
        inst = this
        main.removeCallbacks(poll); main.postDelayed(poll, 2500)
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
        main.removeCallbacks(poll)
        try { unregisterReceiver(pkgRx) } catch (_: Exception) {}
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        val pkg = e?.packageName?.toString() ?: return
        if (pkg == packageName) return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) lastEvt = pkg + " · " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        if (!Cfg.autoUpdate(this)) return
        // ব্যবহারকারী নিজে "Update/Install" চাপলেও পরে অ্যাপ খোলার জন্য সময় মনে রাখি
        if (e.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val src = e.source
            val l = (e.text?.joinToString(" ") ?: src?.text?.toString() ?: "").trim().lowercase()
            if (isInstallerPkg(pkg) && l in OK) updateClickAt = System.currentTimeMillis()
            return
        }
        // নির্মাতাভেদে ইনস্টলারের প্যাকেজ-নাম আলাদা: চেনা/নামে "installer" থাকা প্যাকেজের সব ইভেন্টে দেখি;
        // অচেনা প্যাকেজের ক্ষেত্রে শুধু নতুন জানালা খুললে (ডায়ালগের লেখা দেখে চিনি)
        val known = isInstallerPkg(pkg)
        if (!known && e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (scan(known)) return
        // পর্দা আঁকতে দেরি হলে বোতাম তখনো থাকে না: কিছুক্ষণ পরপর আবার দেখি
        if (known || e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            for (d in longArrayOf(350, 900, 1800, 3200)) main.postDelayed({ if (Cfg.autoUpdate(this)) scan(known) }, d)
        }
    }

    private fun isInstallerPkg(p: String) =
        p in INSTALLERS || p == learned || p.contains("packageinstaller") || p.contains("appdetail") || (p.contains("installer") && !p.contains("vending"))

    private fun norm(c: CharSequence?): String = c?.toString()?.trim()?.lowercase()?.trim('.', '।', '!', ' ', '\u00a0') ?: ""

    private class Lab(val node: AccessibilityNodeInfo, val label: String)

    /** পর্দার সব নোডের ছোট লেখা (নিজের লেখা বা contentDescription) — বোতামের লেখা ভেতরের আলাদা নোডে থাকলেও ধরা পড়ে */
    private fun collectLabels(n: AccessibilityNodeInfo?, out: MutableList<Lab>, depth: Int = 0) {
        if (n == null || depth > 40 || out.size > 400) return
        val l = norm(n.text ?: n.contentDescription)
        if (l.isNotEmpty() && l.length <= 30) out.add(Lab(n, l))
        for (i in 0 until n.childCount) collectLabels(n.getChild(i), out, depth + 1)
    }

    /** এই নোড বা তার কাছের (৪ ধাপ ওপরের) ক্লিকযোগ্য অভিভাবক; না থাকলে null (তখন এটা বোতাম নয়, শুধু লেখা) */
    private fun clickableOf(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var x: AccessibilityNodeInfo? = n; var k = 0
        while (x != null && k++ < 5) { if (x.isClickable && x.isEnabled) return x; x = x.parent }
        return if (n.className?.toString()?.endsWith("Button") == true && n.isEnabled) n else null
    }

    private fun tapNode(n: AccessibilityNodeInfo): Boolean {
        val c = clickableOf(n) ?: n
        val r = Rect(); c.getBoundsInScreen(r)
        return tap(r.centerX(), r.centerY())
    }

    private fun pressNode(n: AccessibilityNodeInfo): Boolean {
        val c = clickableOf(n) ?: return false
        if (c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect(); c.getBoundsInScreen(r)
        return tap(r.centerX(), r.centerY())
    }

    /** পর্দার সব জানালা দেখে ইনস্টল/আপডেটের নিশ্চিত-ডায়ালগ খুঁজে বোতাম চাপে। চাপা হলে true */
    private fun scan(knownPkg: Boolean): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastClick < 1200) return true
        val roots = ArrayList<AccessibilityNodeInfo>()
        try { windows?.forEach { w -> w.root?.let { roots.add(it) } } } catch (_: Exception) {}
        if (roots.isEmpty()) (try { rootInActiveWindow } catch (_: Exception) { null })?.let { roots.add(it) }
        for (root in roots) {
            val rp = root.packageName?.toString() ?: continue
            if (rp == packageName) continue
            val installer = isInstallerPkg(rp)
            val labs = ArrayList<Lab>()
            collectLabels(root, labs)
            if (labs.isEmpty()) continue
            if (!installer) {
                // অচেনা প্যাকেজ: শুধু "Do you want to update this app?" ধরনের লেখা + Cancel বোতাম থাকলে (Play Store বাদ)
                if (rp.contains("vending")) continue
                val t = labs.joinToString("\n") { it.label }
                val prompt = PROMPTS.any { t.contains(it) }
                val hasCancel = labs.any { it.label in CANCEL && clickableOf(it.node) != null }
                if (!prompt || !hasCancel) continue
            }
            Cfg.p(this).edit().putString("last_inst", rp + " · " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(now))).apply()
            // ডায়ালগে আসলে কী কী লেখা/বোতাম দেখা গেল — অ্যাপের মূল পর্দায় দেখানোর জন্য (কেন চাপছে না বুঝতে)
            val dump = rp + " | " + labs.take(14).joinToString(" / ") { it.label + if (clickableOf(it.node) != null) "*" else "" }
            if (dump != lastDump) { lastDump = dump; Cfg.p(this).edit().putString("last_dump", dump).apply() }
            val open = labs.firstOrNull { it.label in OPEN && clickableOf(it.node) != null }
            if (open != null && pressNode(open.node)) { lastClick = now; openedAt = now; note(true); return true }
            val ok = labs.firstOrNull { it.label in OK && clickableOf(it.node) != null }
            if (ok != null) {
                if (now - lastClick > 10_000L) attempts = 0
                if (attempts >= 5) return false
                // জোড় বার ACTION_CLICK, বিজোড় বার আঙুলের ট্যাপ: ফোন এক পথে চাপ না নিলে অন্য পথে
                val done = if (attempts % 2 == 1) tapNode(ok.node) else pressNode(ok.node)
                attempts++
                if (done) {
                    lastClick = now; updateClickAt = now; if (!installer) learned = rp
                    // ডায়ালগ স্থির থাকলে আর নতুন ইভেন্ট আসে না; তাই চাপের পরেও কিছুক্ষণ পরপর আবার দেখি (ডায়ালগ সরে গেলে কিছু হয় না)
                    for (d in longArrayOf(1500, 3000, 4500, 6500)) main.postDelayed({ if (Cfg.autoUpdate(this)) scan(true) }, d)
                }
                note(done)
                return done
            }
        }
        return false
    }

    private fun note(done: Boolean) { Cfg.p(this).edit().putBoolean("last_inst_ok", done).apply() }

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

    /** অ্যাপ খোলে; সামনে না এলে ২-৩ সেকেন্ড পরপর আরও ৩ বার চেষ্টা করে */
    private fun openAndVerify(p: String, n: Int) {
        launch(p)
        if (n >= 3) return
        main.postDelayed({
            if (currentPackage() != p && System.currentTimeMillis() - openedAt >= 20_000L) openAndVerify(p, n + 1)
        }, 2500)
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
