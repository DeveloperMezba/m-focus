package com.focusguard.phone

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager

data class AppItem(val pkg: String, val label: String, val icon: Drawable?, val intent: Intent)

object Apps {
    /** AI apps that stay usable. Add more package names here if needed. */
    val AI_PRESETS = listOf(
        "com.google.android.apps.bard",   // Gemini
        "com.openai.chatgpt",             // ChatGPT
        "com.anthropic.claude",           // Claude
        "com.microsoft.copilot",          // Copilot
        "ai.perplexity.app.android",      // Perplexity
        "com.deepseek.chat",              // DeepSeek
    )

    /** Social media / messengers: no notifications during a session and can never be allowed. */
    val BLOCKED = setOf(
        "com.whatsapp", "com.whatsapp.w4b",
        "com.facebook.orca", "com.facebook.mlite", "com.facebook.katana", "com.facebook.lite",
        "com.instagram.android", "com.instagram.barcelona", "com.instagram.lite",
        "org.telegram.messenger", "org.telegram.messenger.web", "org.telegram.plus",
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
        "com.twitter.android", "com.snapchat.android", "com.viber.voip",
        "com.imo.android.imoim", "com.discord", "com.reddit.frontpage",
        "com.google.android.youtube", "com.linkedin.android", "com.pinterest",
        "com.tencent.mm", "jp.naver.line.android", "com.skype.raider",
    )

    /** System parts that must never be blocked (notification shade, call screens, dialogs). */
    private val SYSTEM_ALWAYS = setOf(
        "android", "com.android.systemui", "com.android.incallui",
        "com.android.server.telecom", "com.android.phone", "com.android.dialer",
        "com.google.android.dialer", "com.samsung.android.incallui",
        "com.samsung.android.dialer",
    )

    private fun prefs(c: Context) = c.getSharedPreferences("fg", Context.MODE_PRIVATE)

    fun getExtras(c: Context): Set<String> = prefs(c).getStringSet("extras", emptySet()) ?: emptySet()
    fun setExtras(c: Context, s: Set<String>) = prefs(c).edit().putStringSet("extras", s.toSet()).apply()

    /** Browsers can open social sites, so they can't be added as extras either. */
    fun browserPackages(c: Context): Set<String> = runCatching {
        c.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com")).addCategory(Intent.CATEGORY_BROWSABLE), 0
        ).map { it.activityInfo.packageName }.toSet()
    }.getOrDefault(emptySet())

    /** Extras that are really allowed: social apps and browsers are always removed. */
    fun effectiveExtras(c: Context): Set<String> = getExtras(c) - BLOCKED - browserPackages(c)

    private fun dialerPackages(c: Context): Set<String> {
        val out = mutableSetOf<String>()
        runCatching { c.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let { out += it } }
        runCatching {
            c.packageManager.queryIntentActivities(Intent(Intent.ACTION_DIAL), 0)
                .forEach { out += it.activityInfo.packageName }
        }
        return out
    }

    private fun imePackages(c: Context): Set<String> =
        runCatching {
            c.getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }.toSet()
        }.getOrDefault(emptySet())

    fun allowedPackages(c: Context): Set<String> =
        (SYSTEM_ALWAYS + c.packageName + dialerPackages(c) + imePackages(c) + AI_PRESETS + effectiveExtras(c)) - BLOCKED

    /** Apps shown on the lock screen: Phone + installed AI apps + extras. */
    fun launchItems(c: Context): List<AppItem> {
        val pm = c.packageManager
        val items = mutableListOf<AppItem>()
        val dialer = dialerPackages(c).firstOrNull()
        items += AppItem(
            "phone", "Phone",
            dialer?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() },
            Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        (AI_PRESETS + effectiveExtras(c)).distinct().forEach { pkg ->
            val intent = pm.getLaunchIntentForPackage(pkg) ?: return@forEach
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            items += AppItem(pkg, label, runCatching { pm.getApplicationIcon(pkg) }.getOrNull(),
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return items
    }
}
