package com.rahcare.dailyagent

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** ফোন চালু হলে বা অ্যাপ আপডেট হলে: Android মাইক-সার্ভিস নিজে চালাতে দেয় না, তাই একটা নোটিফিকেশন দেখায় — চাপলেই শোনা শুরু */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_MY_PACKAGE_REPLACED) Cfg.p(c).edit().putBoolean("selfupd", true).apply()
        if (!Cfg.listening(c)) return
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("boot", "Daily Agent চালু করুন", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(c, 1, Intent(c, MainActivity::class.java).putExtra("autostart", true), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(2, android.app.Notification.Builder(c, "boot").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Daily Agent").setContentText("'My Agent' শোনা চালু করতে এখানে চাপুন").setContentIntent(pi).setAutoCancel(true).build())
    }
}
