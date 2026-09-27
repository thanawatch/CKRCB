package com.example.cookierunbot.update

import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/** No credentials are embedded in the APK. The owner configures a trusted HTTPS update feed. */
internal data class UpdateDescriptor(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val notes: String,
)

internal object UpdatePolicy {
    private val digestPattern = Regex("[0-9a-fA-F]{64}")
    private val versionPattern = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,47}")
    const val MAX_APK_BYTES = 250L * 1024L * 1024L
    const val MAX_FEED_BYTES = 64 * 1024

    fun secureUrl(raw: String): URL {
        require(raw.length in 1..2048) { "Enter an HTTPS URL." }
        val uri = URI(raw.trim())
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.fragment == null && uri.port in -1..65535
        ) { "Only HTTPS URLs without credentials or fragments are supported." }
        return uri.toURL()
    }

    fun descriptor(
        packageName: String,
        versionCode: Long,
        versionName: String,
        apkUrl: String,
        sha256: String,
        notes: String,
    ): UpdateDescriptor {
        require(packageName.matches(Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+"))) {
            "Invalid APK package name."
        }
        require(versionCode > 0L) { "Invalid version code." }
        require(versionPattern.matches(versionName)) { "Invalid version name." }
        secureUrl(apkUrl)
        require(digestPattern.matches(sha256)) { "A valid SHA-256 checksum is required." }
        require(notes.length <= 2000) { "Release notes are too long." }
        return UpdateDescriptor(packageName, versionCode, versionName, apkUrl,
            sha256.lowercase(Locale.US), notes)
    }

    fun hasNewerVersion(installedCode: Long, availableCode: Long): Boolean =
        availableCode > installedCode

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(Locale.US, it.toInt() and 0xff)
        }
}
