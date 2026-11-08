package com.rahcare.dailyagent

import android.content.Context

/** ছোট সেটিংস (শুধু এই ফোনে, অ্যাপের নিজস্ব জায়গায়) */
object Cfg {
    fun p(c: Context) = c.getSharedPreferences("da", Context.MODE_PRIVATE)
    fun key(c: Context): String = p(c).getString("key", "")?.trim().orEmpty()
    fun setKey(c: Context, v: String) { p(c).edit().putString("key", v.trim()).apply() }
    fun listening(c: Context) = p(c).getBoolean("on", false)
    fun setListening(c: Context, v: Boolean) { p(c).edit().putBoolean("on", v).apply() }
    fun autoUpdate(c: Context) = p(c).getBoolean("autoupd", true)
    fun setAutoUpdate(c: Context, v: Boolean) { p(c).edit().putBoolean("autoupd", v).apply() }
    fun lang(c: Context): String = p(c).getString("lang", "bn-BD") ?: "bn-BD"
    fun setLang(c: Context, v: String) { p(c).edit().putString("lang", v).apply() }
}
