package com.focusguard.phone

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

data class UpdateInfo(val version: String, val notes: String, val url: String, val size: Long)

sealed class UpdateResult {
    object NoInternet : UpdateResult()
    data class UpToDate(val version: String) : UpdateResult()
    data class Available(val info: UpdateInfo) : UpdateResult()
    data class Failed(val message: String) : UpdateResult()
}

class UpdateException(message: String) : Exception(message)

/**
 * Update from the app's GitHub releases. Nothing happens in the background: the app only goes
 * online when the user taps "App update" in Settings.
 */
object Updater {
    const val REPO = "DeveloperMezba/m-focus"

    fun online(c: Context): Boolean {
        return try {
            val cm = c.getSystemService(ConnectivityManager::class.java)
            val net = cm?.activeNetwork
            val caps = if (cm != null && net != null) cm.getNetworkCapabilities(net) else null
            caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        } catch (e: Exception) {
            false
        }
    }

    /** Blocking. Call from a background thread. */
    fun check(c: Context): UpdateResult {
        if (!online(c)) return UpdateResult.NoInternet
        var conn: HttpURLConnection? = null
        return try {
            val http = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
            conn = http
            http.connectTimeout = 8000
            http.readTimeout = 8000
            http.setRequestProperty("Accept", "application/vnd.github+json")
            http.setRequestProperty("User-Agent", "M-Focus")
            when (val code = http.responseCode) {
                200 -> parse(c, http.inputStream.bufferedReader().use { it.readText() })
                404 -> UpdateResult.Failed("No update has been published yet.")
                403, 429 -> UpdateResult.Failed("GitHub is busy. Try again in a few minutes.")
                else -> UpdateResult.Failed("Could not check for updates (error $code).")
            }
        } catch (e: UnknownHostException) {
            UpdateResult.NoInternet
        } catch (e: ConnectException) {
            UpdateResult.NoInternet
        } catch (e: SocketTimeoutException) {
            UpdateResult.NoInternet
        } catch (e: IOException) {
            UpdateResult.Failed("Could not reach GitHub. Check your connection.")
        } catch (e: Exception) {
            UpdateResult.Failed("Something went wrong while checking.")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun parse(c: Context, body: String): UpdateResult {
        val json = JSONObject(body)
        val tag = json.optString("tag_name").trim().removePrefix("v").removePrefix("V")
        var url = ""
        var size = 0L
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    url = a.optString("browser_download_url")
                    size = a.optLong("size")
                    break
                }
            }
        }
        val current = versionName(c)
        if (tag.isEmpty()) return UpdateResult.Failed("The latest release has no version number.")
        if (!isNewer(tag, current)) return UpdateResult.UpToDate(current)
        if (url.isEmpty()) return UpdateResult.Failed("The new version has no APK file yet.")
        // only ever download from this project's own releases
        if (!url.startsWith("https://github.com/$REPO/releases/download/")) {
            return UpdateResult.Failed("The download address looks wrong, so it was not used.")
        }
        return UpdateResult.Available(UpdateInfo(tag, json.optString("body").trim(), url, size))
    }

    fun isNewer(a: String, b: String): Boolean {
        fun parts(v: String) = v.split('.', '-').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val p = x.getOrElse(i) { 0 }
            val q = y.getOrElse(i) { 0 }
            if (p != q) return p > q
        }
        return false
    }

    /** Blocking. Returns the downloaded APK, or throws UpdateException with a message for the user. */
    fun download(c: Context, info: UpdateInfo, onProgress: (Int) -> Unit, cancelled: () -> Boolean): File {
        val dir = File(c.cacheDir, "updates")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "m-focus-${info.version}.apk")
        var conn: HttpURLConnection? = null
        try {
            val http = URL(info.url).openConnection() as HttpURLConnection
            conn = http
            http.connectTimeout = 10_000
            http.readTimeout = 15_000
            http.setRequestProperty("User-Agent", "M-Focus")
            http.connect()
            if (http.responseCode !in 200..299) throw UpdateException("Download failed (error ${http.responseCode}).")
            val total = http.contentLengthLong.takeIf { it > 0 } ?: info.size
            http.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(32 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        if (cancelled()) { out.delete(); throw UpdateException("Cancelled.") }
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = ((done * 100) / total).toInt().coerceIn(0, 100)
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
            if (total > 0 && out.length() != total) throw UpdateException("The download was cut off. Please try again.")
        } catch (e: UpdateException) {
            out.delete()
            throw e
        } catch (e: Exception) {
            out.delete()
            throw UpdateException(if (online(c)) "Download failed. Please try again." else "No internet connection")
        } finally {
            runCatching { conn?.disconnect() }
        }
        checkApk(c, out)
        return out
    }

    /** The file must really be this app, and signed by the same key, or Android would refuse to update. */
    @Suppress("DEPRECATION")
    private fun checkApk(c: Context, apk: File) {
        val pm = c.packageManager
        val archive = pm.getPackageArchiveInfo(apk.path, 0)
        if (archive == null || archive.packageName != c.packageName) {
            apk.delete()
            throw UpdateException("The downloaded file is not a valid M Focus update.")
        }
        if (Build.VERSION.SDK_INT >= 28) {
            val same = runCatching {
                val flag = PackageManager.GET_SIGNING_CERTIFICATES
                val mine = pm.getPackageInfo(c.packageName, flag).signingInfo?.apkContentsSigners
                val theirs = pm.getPackageArchiveInfo(apk.path, flag)?.signingInfo?.apkContentsSigners
                if (mine == null || theirs == null) true
                else mine.map { it.toCharsString() }.toSet() == theirs.map { it.toCharsString() }.toSet()
            }.getOrDefault(true)
            if (!same) {
                apk.delete()
                throw UpdateException(
                    "This update is signed with a different key, so it cannot replace the installed app. " +
                        "Uninstall M Focus once and install the new APK by hand."
                )
            }
        }
    }

    fun canInstall(c: Context): Boolean = Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()

    /** "Install unknown apps" switch for this app (needed once, Android asks for it). */
    fun openInstallPermission(c: Context) {
        c.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(c: Context, apk: File) {
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.fileprovider", apk)
        c.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
