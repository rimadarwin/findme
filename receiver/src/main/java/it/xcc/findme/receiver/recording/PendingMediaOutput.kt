package it.xcc.findme.receiver.recording

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File

class PendingMediaOutput private constructor(
    private val context: Context,
    val displayPath: String,
    val descriptor: ParcelFileDescriptor,
    private val uri: Uri?,
    private val legacyFile: File?,
    private val mimeType: String,
) {
    fun publish() {
        descriptor.close()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri != null) {
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
        } else if (legacyFile != null) {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(legacyFile.absolutePath),
                arrayOf(mimeType),
                null,
            )
        }
    }

    fun discard() {
        runCatching { descriptor.close() }
        if (uri != null) {
            context.contentResolver.delete(uri, null, null)
        } else {
            legacyFile?.delete()
        }
    }

    companion object {
        fun create(
            context: Context,
            kind: RecordingKind,
            deviceName: String,
        ): PendingMediaOutput {
            val fileName = RecordingFileNames.create(kind, deviceName)
            val directory = when (kind) {
                RecordingKind.VIDEO,
                RecordingKind.SCREEN,
                -> Environment.DIRECTORY_MOVIES
                RecordingKind.AUDIO -> Environment.DIRECTORY_MUSIC
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = when (kind) {
                    RecordingKind.VIDEO,
                    RecordingKind.SCREEN,
                    -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    RecordingKind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }
                val uri = checkNotNull(
                    context.contentResolver.insert(
                        collection,
                        ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, kind.mimeType)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/FindMe")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        },
                    ),
                ) { "Impossibile creare il file multimediale." }
                val descriptor = checkNotNull(
                    context.contentResolver.openFileDescriptor(uri, "w"),
                ) {
                    context.contentResolver.delete(uri, null, null)
                    "Impossibile aprire il file multimediale."
                }
                return PendingMediaOutput(
                    context = context,
                    displayPath = "$directory/FindMe/$fileName",
                    descriptor = descriptor,
                    uri = uri,
                    legacyFile = null,
                    mimeType = kind.mimeType,
                )
            }

            @Suppress("DEPRECATION")
            val folder = File(
                Environment.getExternalStoragePublicDirectory(directory),
                "FindMe",
            )
            check(folder.exists() || folder.mkdirs()) {
                "Impossibile creare la cartella $directory/FindMe."
            }
            val file = File(folder, fileName)
            val descriptor = ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_CREATE or
                    ParcelFileDescriptor.MODE_TRUNCATE or
                    ParcelFileDescriptor.MODE_READ_WRITE,
            )
            return PendingMediaOutput(
                context = context,
                displayPath = file.absolutePath,
                descriptor = descriptor,
                uri = null,
                legacyFile = file,
                mimeType = kind.mimeType,
            )
        }
    }
}
