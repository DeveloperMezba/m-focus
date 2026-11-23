package com.focusguard.phone

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
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
import java.security.MessageDigest

data class UpdateInfo(
    val version: String,
    val notes: String,
    val url: String,
    val size: Long,
    val sha256: String? = null,
)

sealed class UpdateResult {
    object NoInternet : UpdateResult()
    data class UpToDate(val version: String) : UpdateResult()
    data class Available(val info: UpdateInfo) : UpdateResult()
    data class Failed(val message: String) : UpdateResult()
}

class UpdateException(message: String) : Exception(message)

/**
 * Checks the latest stable GitHub Release only when the user opens App Update in Settings.
 * Downloads one exact APK asset and verifies digest, package, versionCode and signing identity.
 * All network methods are blocking and must be called from a background thread.
 */
object Updater {
    const val REPO = "DeveloperMezba/m-focus"
    private const val APK_ASSET_NAME = "m-focus.apk"
    private const val RELEASE_API = "https://api.github.com/repos/$REPO/releases/latest"
    private const val MAX_APK_BYTES = 150L * 1024L * 1024L
    private val versionPattern =
        Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:\\.(0|[1-9][0-9]*))?$")
    private val sha256Pattern = Regex("^[0-9a-fA-F]{64}$")

    fun online(c: Context): Boolean = try {
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val network = cm?.activeNetwork
        val capabilities = if (cm != null && network != null) cm.getNetworkCapabilities(network) else null
        capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) {
        false
    }

    fun check(c: Context): UpdateResult {
        if (!online(c)) return UpdateResult.NoInternet
        var connection: HttpURLConnection? = null
        return try {
            val http = URL(RELEASE_API).openConnection() as HttpURLConnection
            connection = http
            http.connectTimeout = 8_000
            http.readTimeout = 8_000
            http.instanceFollowRedirects = true
            http.setRequestProperty("Accept", "application/vnd.github+json")
            http.setRequestProperty("User-Agent", "M-Focus-Android-Updater")

            when (val code = http.responseCode) {
                HttpURLConnection.HTTP_OK ->
                    parse(c, http.inputStream.bufferedReader().use { it.readText() })
                HttpURLConnection.HTTP_NOT_FOUND ->
                    UpdateResult.Failed("No stable update has been published yet.")
                403, 429 ->
                    UpdateResult.Failed("GitHub is busy. Try again in a few minutes.")
                else ->
                    UpdateResult.Failed("Could not check for updates (error $code).")
            }
        } catch (_: UnknownHostException) {
            UpdateResult.NoInternet
        } catch (_: ConnectException) {
            UpdateResult.NoInternet
        } catch (_: SocketTimeoutException) {
            UpdateResult.NoInternet
        } catch (_: IOException) {
            UpdateResult.Failed("Could not reach GitHub. Check your connection.")
        } catch (_: Exception) {
            UpdateResult.Failed("Something went wrong while checking for updates.")
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private fun parse(c: Context, body: String): UpdateResult {
        val release = JSONObject(body)
        if (release.optBoolean("draft", false) || release.optBoolean("prerelease", false)) {
            return UpdateResult.Failed("The latest GitHub Release is not a stable published release.")
        }

        val tag = release.optString("tag_name").trim()
        val version = normalizeVersion(tag)
            ?: return UpdateResult.Failed("The latest stable Release has an invalid version tag.")

        val current = versionName(c).trim()
        if (!isSupportedVersion(current)) {
            return UpdateResult.Failed("The installed app version could not be read.")
        }
        if (!isNewer(version, current)) return UpdateResult.UpToDate(current)

        val assets = release.optJSONArray("assets")
        var apk: JSONObject? = null
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val item = assets.optJSONObject(i) ?: continue
                if (item.optString("name") == APK_ASSET_NAME) {
                    apk = item
                    break
                }
            }
        }

        val asset = apk
            ?: return UpdateResult.Failed("Version $version is being prepared. Please check again shortly.")

        val url = asset.optString("browser_download_url").trim()
        if (!isTrustedDownloadUrl(url, version)) {
            return UpdateResult.Failed("The APK download address does not match the official M-Focus Release.")
        }

        val rawDigest = asset.optString("digest").trim()
        val digest = when {
            rawDigest.isEmpty() -> null
            rawDigest.startsWith("sha256:", ignoreCase = true) ->
                rawDigest.substringAfter(':').takeIf { sha256Pattern.matches(it) }
                    ?: return UpdateResult.Failed("GitHub returned an invalid APK checksum.")
            else -> return UpdateResult.Failed("GitHub returned an unsupported APK checksum format.")
        }

        return UpdateResult.Available(
            UpdateInfo(
                version = version,
                notes = release.optString("body").trim(),
                url = url,
                size = asset.optLong("size", 0L).coerceAtLeast(0L),
                sha256 = digest,
            )
        )
    }

    fun isNewer(a: String, b: String): Boolean {
        val newer = versionParts(a) ?: return false
        val current = versionParts(b) ?: return false
        for (index in 0 until maxOf(newer.size, current.size)) {
            val left = newer.getOrElse(index) { 0L }
            val right = current.getOrElse(index) { 0L }
            if (left != right) return left > right
        }
        return false
    }

    private fun normalizeVersion(value: String): String? {
        val normalized = value.trim().removePrefix("v").removePrefix("V")
        return normalized.takeIf { versionPattern.matches(it) && versionParts(it) != null }
    }

    private fun isSupportedVersion(value: String): Boolean = versionParts(value) != null

    private fun versionParts(value: String): List<Long>? {
        val normalized = value.trim().removePrefix("v").removePrefix("V")
        if (!versionPattern.matches(normalized)) return null
        return normalized.split('.').map { it.toLongOrNull() ?: return null }
    }

    /** Exact host, repository, tag and asset match; query strings and alternate ports are rejected. */
    private fun isTrustedDownloadUrl(value: String, expectedVersion: String): Boolean {
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return false
        val path = uri.pathSegments
        return uri.scheme.equals("https", ignoreCase = true) &&
            uri.encodedAuthority.equals("github.com", ignoreCase = true) &&
            uri.query == null && uri.fragment == null &&
            path.size == 6 &&
            path[0] == "DeveloperMezba" &&
            path[1] == "m-focus" &&
            path[2] == "releases" &&
            path[3] == "download" &&
            normalizeVersion(path[4]) == expectedVersion &&
            path[5] == APK_ASSET_NAME
    }

    fun download(
        c: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean,
    ): File {
        if (!isTrustedDownloadUrl(info.url, info.version)) {
            throw UpdateException("The APK download address does not match the selected M-Focus Release.")
        }
        if (info.sha256 != null && !sha256Pattern.matches(info.sha256)) {
            throw UpdateException("The APK checksum is invalid.")
        }
        if (info.size > MAX_APK_BYTES) {
            throw UpdateException("The update file is unexpectedly large and was rejected.")
        }

        val dir = File(c.cacheDir, "updates")
        if (!dir.exists() && !dir.mkdirs()) {
            throw UpdateException("Could not prepare storage for the update.")
        }
        dir.listFiles()?.filter { it.isFile }?.forEach { it.delete() }

        val outputFile = File(dir, "m-focus-update.apk")
        var connection: HttpURLConnection? = null
        try {
            val http = URL(info.url).openConnection() as HttpURLConnection
            connection = http
            http.connectTimeout = 10_000
            http.readTimeout = 20_000
            http.instanceFollowRedirects = true
            http.setRequestProperty("User-Agent", "M-Focus-Android-Updater")
            http.connect()

            if (http.responseCode !in 200..299) {
                throw UpdateException("Download failed (error ${http.responseCode}).")
            }

            val total = http.contentLengthLong.takeIf { it > 0L }
                ?: info.size.takeIf { it > 0L }
                ?: -1L
            if (total > MAX_APK_BYTES) {
                throw UpdateException("The update file is unexpectedly large and was rejected.")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            http.inputStream.use { input ->
                outputFile.outputStream().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var downloaded = 0L
                    var lastPercent = -1
                    while (true) {
                        if (cancelled()) throw UpdateException("Download cancelled.")
                        val count = input.read(buffer)
                        if (count < 0) break
                        downloaded += count
                        if (downloaded > MAX_APK_BYTES) {
                            throw UpdateException("The update file is unexpectedly large and was rejected.")
                        }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)

                        if (total > 0L) {
                            val percent = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                }
            }

            if (total > 0L && outputFile.length() != total) {
                throw UpdateException("The download was incomplete. Please try again.")
            }

            val actualDigest = toHex(digest.digest())
            if (info.sha256 != null && !actualDigest.equals(info.sha256, ignoreCase = true)) {
                throw UpdateException("The downloaded APK checksum does not match GitHub. The file was rejected.")
            }
            onProgress(100)
        } catch (e: UpdateException) {
            outputFile.delete()
            throw e
        } catch (_: UnknownHostException) {
            outputFile.delete()
            throw UpdateException("No internet connection.")
        } catch (_: SocketTimeoutException) {
            outputFile.delete()
            throw UpdateException("The download timed out. Please try again.")
        } catch (_: Exception) {
            outputFile.delete()
            throw UpdateException(if (online(c)) "Download failed. Please try again." else "No internet connection.")
        } finally {
            runCatching { connection?.disconnect() }
        }

        try {
            checkApk(c, outputFile, info.version)
        } catch (e: UpdateException) {
            outputFile.delete()
            throw e
        }
        return outputFile
    }

    @Suppress("DEPRECATION")
    private fun checkApk(c: Context, apk: File, expectedVersion: String) {
        try {
            val pm = c.packageManager
            val flags = if (Build.VERSION.SDK_INT >= 28) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            }
            val installed = packageInfo(pm, c.packageName, flags)
                ?: throw UpdateException("Could not verify the installed M-Focus app.")
            val downloaded = archiveInfo(pm, apk.absolutePath, flags)
                ?: throw UpdateException("The downloaded file is not a valid Android APK.")

            if (downloaded.packageName != c.packageName) {
                throw UpdateException("The downloaded APK is not the M-Focus app.")
            }
            if (normalizeVersion(downloaded.versionName.orEmpty()) != expectedVersion) {
                throw UpdateException("The APK version does not match the GitHub Release tag.")
            }
            if (longVersionCode(downloaded) <= longVersionCode(installed)) {
                throw UpdateException("This APK is not newer than the installed version and was rejected.")
            }

            val installedSigners = signerDigests(installed)
            val downloadedSigners = signerDigests(downloaded)
            if (installedSigners.isEmpty() || downloadedSigners.isEmpty()) {
                throw UpdateException("Could not verify the APK signing certificate. The update was rejected.")
            }
            if (installedSigners != downloadedSigners) {
                throw UpdateException(
                    "This update is signed with a different key, so it cannot safely replace the installed app. " +
                        "The APK was rejected."
                )
            }
        } catch (e: UpdateException) {
            throw e
        } catch (_: Exception) {
            throw UpdateException("Could not securely verify the downloaded APK. The update was rejected.")
        }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(pm: PackageManager, name: String, flags: Int): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageInfo(name, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageInfo(name, flags)
        }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, path: String, flags: Int): PackageInfo? =
        if (Build.VERSION.SDK_INT >= 33) {
            pm.getPackageArchiveInfo(path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            pm.getPackageArchiveInfo(path, flags)
        }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        } ?: return emptySet()

        return signatures.map {
            toHex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray()))
        }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun longVersionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    private fun toHex(bytes: ByteArray): String {
        val alphabet = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(alphabet[value ushr 4])
                append(alphabet[value and 0x0f])
            }
        }
    }

    fun canInstall(c: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()

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
