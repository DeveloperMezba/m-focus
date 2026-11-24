package com.focusguard.phone

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.SystemClock
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager

data class AppItem(val pkg: String, val label: String, val icon: Drawable?, val intent: Intent)

object Apps {
    /** AI apps users may explicitly choose as focus-session extras. */
    val AI_PRESETS = listOf(
        "com.google.android.apps.bard",   // Gemini
        "com.openai.chatgpt",             // ChatGPT
        "com.anthropic.claude",           // Claude
        "com.microsoft.copilot",          // Copilot
        "ai.perplexity.app.android",      // Perplexity
        "com.deepseek.chat",              // DeepSeek
    )

    /** AI packages are recognized separately so they are never mistaken for an in-call screen. */
    val AI_ALL: Set<String> = AI_PRESETS.toSet() +
        setOf("com.google.android.googlequicksearchbox", "com.google.android.apps.googleassistant")

    /** Chrome variants may be chosen explicitly; other browsers remain excluded from focus extras. */
    val CHROME_PACKAGES: Set<String> = setOf(
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
    )

    /** Explicit package visibility and fallback lookup for commonly requested extras. */
    val EXTRA_APP_PACKAGE_HINTS: Set<String> = CHROME_PACKAGES +
        setOf("com.google.android.apps.docs") + AI_ALL

    const val MAX_EXTRAS = 4

    /** Apps that open their UI through another package (none right now). */
    val HELPERS: Map<String, Set<String>> = emptyMap()

    /** Screens that can uninstall the app or switch its permissions off. Sent Home even during breaks. */
    val TAMPER_PACKAGES = setOf(
        "com.android.settings",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller", "com.miui.packageinstaller",
        "com.google.android.permissioncontroller", "com.android.permissioncontroller",
        "com.android.vending",
        "com.miui.securitycenter", "com.iqoo.secure", "com.coloros.safecenter", "com.transsion.phonemaster",
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

    /** Notification shade, keyboard, permission dialogs: they never change the lock state. */
    private val BASE_IGNORED = setOf(
        "android", "com.android.systemui",
        "com.google.android.permissioncontroller", "com.android.permissioncontroller",
        "com.google.android.gms",
    )

    /** Call screens: allowed while a session runs. */
    private val CALL_PKGS = setOf(
        "com.android.incallui", "com.android.server.telecom", "com.android.phone",
        "com.android.dialer", "com.google.android.dialer",
        "com.samsung.android.incallui", "com.samsung.android.dialer",
    )

    /** Parts of a package name that only in-call / dialer / telecom windows use. */
    private val CALL_HINTS = listOf("incall", "dialer", "telecom", "callui", "callservice", "telephonyui")

    private var cachedDialers: Set<String>? = null
    private var cachedDialersAt = 0L
    private var cachedAllowed: Set<String>? = null
    private var cachedAllowedAt = 0L
    private var cachedLaunchers: Set<String>? = null
    private var cachedLaunchersAt = 0L
    private var cachedIgnored: Set<String>? = null
    private var cachedIgnoredAt = 0L
    private var cachedBrowsers: Set<String>? = null
    private var cachedBrowsersAt = 0L

    private fun prefs(c: Context) = c.getSharedPreferences("fg", Context.MODE_PRIVATE)

    fun getExtras(c: Context): Set<String> = prefs(c).getStringSet("extras", emptySet()) ?: emptySet()
    fun setExtras(c: Context, s: Set<String>) {
        prefs(c).edit().putStringSet("extras", s.take(MAX_EXTRAS).toSet()).apply()
        cachedAllowed = null
    }

    /** Browsers can open social sites, so they can't be added as extras either. */
    fun browserPackages(c: Context): Set<String> {
        val now = SystemClock.uptimeMillis()
        cachedBrowsers?.let { if (now - cachedBrowsersAt < 60_000) return it }
        val out = runCatching {
            c.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com")).addCategory(Intent.CATEGORY_BROWSABLE), 0
            ).map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())
        cachedBrowsers = out
        cachedBrowsersAt = now
        return out
    }

    /**
     * Extras explicitly selected by the user. Social apps stay blocked; Chrome is selectable,
     * while other browsers remain excluded because they can open social websites.
     */
    fun effectiveExtras(c: Context): Set<String> =
        (getExtras(c) - BLOCKED - (browserPackages(c) - CHROME_PACKAGES)).take(MAX_EXTRAS).toSet()

    /** Pre-installed packages. Used to stay away from system screens we don't recognise. */
    fun isSystemPackage(c: Context, pkg: String): Boolean = runCatching {
        val ai = c.packageManager.getApplicationInfo(pkg, 0)
        (ai.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or
            android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    }.getOrDefault(false)

    private fun dialerPackages(c: Context): Set<String> {
        val now = SystemClock.uptimeMillis()
        cachedDialers?.let { if (now - cachedDialersAt < 60_000) return it }
        val out = mutableSetOf<String>()
        runCatching { c.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let { out += it } }
        runCatching {
            c.packageManager.queryIntentActivities(Intent(Intent.ACTION_DIAL), 0)
                .forEach { out += it.activityInfo.packageName }
        }
        cachedDialers = out
        cachedDialersAt = now
        return out
    }

    /**
     * True for the in-call screen and the dialer itself: these are the only windows that may
     * stay usable during a call. Matched by package name hints so it also works on phones whose
     * in-call UI lives in an unexpected package.
     */
    fun isCallUi(c: Context, pkg: String, cls: String): Boolean {
        if (pkg in CALL_PKGS || pkg in dialerPackages(c)) return true
        val p = pkg.lowercase()
        if (CALL_HINTS.any { p.contains(it) }) return true
        val k = cls.lowercase()
        return k.contains("incall") || k.contains("callactivity") || k.contains("dialtacts")
    }

    private fun imePackages(c: Context): Set<String> =
        runCatching {
            c.getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }.toSet()
        }.getOrDefault(emptySet())

    /** Home screen / recents apps. Going there ends any temporary permission. */
    fun launcherPackages(c: Context): Set<String> {
        val now = SystemClock.uptimeMillis()
        cachedLaunchers?.let { if (now - cachedLaunchersAt < 60_000) return it }
        val out = runCatching {
            c.packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
            ).map { it.activityInfo.packageName }.toSet()
        }.getOrDefault(emptySet())
        cachedLaunchers = out
        cachedLaunchersAt = now
        return out
    }

    /** 5 slots: [side][side][Phone][side][side]. Extras fill the slots nearest the Phone first. */
    fun dockSlots(c: Context): List<AppItem?> {
        val items = launchItems(c)                 // Phone first, then the chosen extras
        val slots = arrayOfNulls<AppItem>(5)
        slots[2] = items.firstOrNull()
        val order = intArrayOf(1, 3, 0, 4)
        items.drop(1).take(4).forEachIndexed { i, app -> slots[order[i]] = app }
        return slots.toList()
    }

    fun ignoredPackages(c: Context): Set<String> {
        val now = SystemClock.uptimeMillis()
        cachedIgnored?.let { if (now - cachedIgnoredAt < 60_000) return it }
        val out = BASE_IGNORED + imePackages(c)
        cachedIgnored = out
        cachedIgnoredAt = now
        return out
    }

    /** Apps that may be open during a session (this app is deliberately NOT in the list). */
    fun allowedApps(c: Context): Set<String> {
        val now = SystemClock.uptimeMillis()
        cachedAllowed?.let { if (now - cachedAllowedAt < 60_000) return it }
        // AI apps and Chrome are usable only when the user explicitly selected them as extras.
        val out = (CALL_PKGS + dialerPackages(c) + effectiveExtras(c)) - BLOCKED
        cachedAllowed = out
        cachedAllowedAt = now
        return out
    }

    /** Apps shown on the lock screen: Phone + up to 4 extras. */
    fun launchItems(c: Context): List<AppItem> {
        val pm = c.packageManager
        val items = mutableListOf<AppItem>()
        val dialer = dialerPackages(c).firstOrNull()
        items += AppItem(
            "phone", "Phone",
            dialer?.let { runCatching { pm.getApplicationIcon(it) }.getOrNull() },
            Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        effectiveExtras(c).forEach { pkg ->
            val intent = pm.getLaunchIntentForPackage(pkg) ?: return@forEach
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
            items += AppItem(pkg, label, runCatching { pm.getApplicationIcon(pkg) }.getOrNull(),
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return items
    }
}
