package it.xcc.findme.receiver

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object VideoSnapshotStorage {
    fun save(context: Context, bitmap: Bitmap, deviceName: String): String {
        val safeDeviceName = deviceName
            .replace(Regex("[^A-Za-z0-9_-]"), "_")
            .take(40)
            .ifBlank { "device" }
        val fileName = "FindMe_${safeDeviceName}_${FILE_TIME_FORMAT.format(LocalDateTime.now())}.jpg"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(context, bitmap, fileName)
        } else {
            saveLegacy(context, bitmap, fileName)
        }
    }

    private fun saveWithMediaStore(
        context: Context,
        bitmap: Bitmap,
        fileName: String,
    ): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/FindMe",
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values),
        ) { "Impossibile creare la foto nella galleria." }
        try {
            resolver.openOutputStream(uri)?.use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                    "Scrittura della foto non riuscita."
                }
            } ?: error("Impossibile aprire la foto nella galleria.")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "Pictures/FindMe/$fileName"
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(
        context: Context,
        bitmap: Bitmap,
        fileName: String,
    ): String {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "FindMe",
        )
        check(directory.exists() || directory.mkdirs()) {
            "Impossibile creare la cartella Pictures/FindMe."
        }
        val file = File(directory, fileName)
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                "Scrittura della foto non riuscita."
            }
        }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf("image/jpeg"),
            null,
        )
        return file.absolutePath
    }

    private val FILE_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
}
