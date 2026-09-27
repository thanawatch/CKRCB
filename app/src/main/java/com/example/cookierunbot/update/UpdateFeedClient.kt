package com.example.cookierunbot.update

import android.content.Context
import android.net.Uri
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

internal data class VerifiedLocalUpdate(val file: File, val versionName: String, val versionCode: Long)

/** Synchronous I/O; callers must use a dedicated background executor. */
internal class UpdateFeedClient(private val context: Context) {
    fun check(feedUrl: String): UpdateDescriptor {
        val connection = openHttps(feedUrl)
        val bytes = try {
            connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("Update check cancelled.")
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > UpdatePolicy.MAX_FEED_BYTES) {
                        throw IOException("Update feed is too large.")
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        val candidate = UpdatePolicy.descriptor(
            packageName = json.getString("packageName"),
            versionCode = json.getLong("versionCode"),
            versionName = json.getString("versionName"),
            apkUrl = json.getString("apkUrl"),
            sha256 = json.getString("sha256"),
            notes = json.optString("notes", ""),
        )
        require(candidate.packageName == context.packageName) {
            "The update feed is for a different Android package."
        }
        return candidate
    }

    fun download(
        candidate: UpdateDescriptor,
        destinationDirectory: File = File(context.cacheDir, "updates"),
        maxBytes: Long = UpdatePolicy.MAX_APK_BYTES,
    ): File {
        require(maxBytes in 1L..UpdatePolicy.MAX_APK_BYTES) { "Invalid download size limit." }
        require(candidate.packageName == context.packageName) { "Wrong package name." }
        val directory = destinationDirectory
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create update cache." }
        val temporary = File(directory, "incoming.apk")
        val verified = File(directory, "RobotRun-update.apk")
        temporary.delete()
        val connection = openHttps(candidate.apkUrl)
        try {
            if (connection.contentLengthLong > maxBytes) {
                throw IOException("APK exceeds maximum download size.")
            }
            val digest = MessageDigest.getInstance("SHA-256")
            var received = 0L
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw IOException("Download cancelled.")
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > maxBytes) {
                            throw IOException("APK exceeds maximum download size.")
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            require(received > 0L) { "Downloaded APK is empty." }
            val actualHash = digest.digest().joinToString("") {
                "%02x".format(Locale.US, it.toInt() and 0xff)
            }
            require(actualHash.equals(candidate.sha256, ignoreCase = true)) {
                "APK SHA-256 verification failed."
            }
            verifyPackageAndSigner(temporary, candidate)
            if (verified.exists() && !verified.delete()) throw IOException("Cannot replace cached update.")
            if (!temporary.renameTo(verified)) throw IOException("Cannot finalize verified update.")
            return verified
        } finally {
            connection.disconnect()
            temporary.delete()
        }
    }

    /** Manual SAF import works even when the private GitHub repository has no public feed. */
    fun importLocal(uri: Uri): VerifiedLocalUpdate {
        val directory = File(context.cacheDir, "updates")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create update cache." }
        val temporary = File(directory, "selected.apk")
        val verified = File(directory, "RobotRun-update.apk")
        temporary.delete()
        try {
            var received = 0L
            (context.contentResolver.openInputStream(uri)
                ?: throw IOException("Could not open selected APK.")).use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw IOException("APK import cancelled.")
                        val count = input.read(buffer)
                        if (count < 0) break
                        received += count
                        if (received > UpdatePolicy.MAX_APK_BYTES) {
                            throw IOException("Selected APK exceeds maximum update size.")
                        }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            require(received > 0L) { "Selected APK is empty." }
            val archive = verifyPackageAndSigner(temporary, null)
            if (verified.exists() && !verified.delete()) throw IOException("Cannot replace cached update.")
            if (!temporary.renameTo(verified)) throw IOException("Cannot finalize verified APK.")
            return VerifiedLocalUpdate(verified, archive.versionName ?: "unknown", packageCode(archive))
        } finally {
            temporary.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackageAndSigner(apk: File, candidate: UpdateDescriptor?): PackageInfo {
        val pm = context.packageManager
        val flag = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flag)
            ?: throw IOException("Downloaded file is not a readable APK.")
        val installed = pm.getPackageInfo(context.packageName, flag)
        require(archive.packageName == context.packageName) { "APK package name mismatch." }
        if (candidate != null) {
            require(packageCode(archive) == candidate.versionCode) { "APK version code mismatch." }
            require(archive.versionName == candidate.versionName) { "APK version name mismatch." }
        }
        require(packageCode(archive) > packageCode(installed)) { "APK is not newer than installed app." }
        val expectedSigners = signerHashes(installed)
        val actualSigners = signerHashes(archive)
        require(expectedSigners.isNotEmpty() && actualSigners == expectedSigners) {
            "APK signing certificate differs from the installed RobotRun app."
        }
        return archive
    }

    private fun signerHashes(info: PackageInfo): Set<String> {
        @Suppress("DEPRECATION")
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        return signatures.orEmpty().map { UpdatePolicy.sha256(it.toByteArray()) }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun packageCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    /** Explicitly follow HTTPS redirects only, never a downgrade to plain HTTP. */
    private fun openHttps(url: String): HttpURLConnection {
        var current: URL = UpdatePolicy.secureUrl(url)
        for (redirect in 0..5) {
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "RobotRun-InAppUpdater")
            connection.setRequestProperty("Accept-Encoding", "identity")
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?: throw IOException("Update redirect has no destination.")
                    val next = URL(current, location)
                    current = UpdatePolicy.secureUrl(next.toExternalForm())
                    connection.disconnect()
                    continue
                }
                if (status !in 200..299) throw IOException("Update server returned HTTP $status.")
                return connection
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
        }
        throw IOException("Too many update redirects.")
    }
}
