package com.example.cookierunbot.automation

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

/** Saves bot images into a public Pictures/CookieRunBot subfolder. */
class PublicImageStore(private val context: Context) {
    fun savePng(
        image: Mat,
        subdirectory: String,
        filename: String,
        replaceExisting: Boolean = false,
    ): String {
        val encoded = MatOfByte()
        val bytes = try {
            check(Imgcodecs.imencode(".png", image, encoded)) { "Unable to encode $filename" }
            encoded.toArray()
        } finally {
            encoded.release()
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(bytes, subdirectory, filename, replaceExisting)
        } else {
            saveLegacy(bytes, subdirectory, filename)
        }
    }

    private fun saveWithMediaStore(
        bytes: ByteArray,
        subdirectory: String,
        filename: String,
        replaceExisting: Boolean,
    ): String {
        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = "${Environment.DIRECTORY_PICTURES}/$ROOT_DIRECTORY/$subdirectory/"

        if (replaceExisting) {
            resolver.delete(
                collection,
                "${MediaStore.Images.Media.DISPLAY_NAME}=? AND " +
                    "${MediaStore.Images.Media.RELATIVE_PATH}=?",
                arrayOf(filename, relativePath),
            )
        }

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(collection, values)) {
            "Unable to create Pictures/$ROOT_DIRECTORY/$subdirectory/$filename"
        }
        try {
            resolver.openOutputStream(uri, "w").use { output ->
                checkNotNull(output) { "Unable to open $uri" }
                output.write(bytes)
                output.flush()
            }
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
        return "Pictures/$ROOT_DIRECTORY/$subdirectory/$filename"
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(bytes: ByteArray, subdirectory: String, filename: String): String {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "$ROOT_DIRECTORY/$subdirectory",
        ).apply { check(exists() || mkdirs()) { "Unable to create $absolutePath" } }
        val output = File(directory, filename)
        output.outputStream().use { stream ->
            stream.write(bytes)
            stream.flush()
        }
        MediaScannerConnection.scanFile(context, arrayOf(output.absolutePath), arrayOf("image/png"), null)
        return output.absolutePath
    }

    companion object {
        const val ROOT_DIRECTORY = "CookieRunBot"
    }
}
