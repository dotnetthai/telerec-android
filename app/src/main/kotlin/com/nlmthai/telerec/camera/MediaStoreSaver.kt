package com.nlmthai.telerec.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.nlmthai.telerec.core.MediaSaving
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Saves to the gallery under Movies/TeleRec and Pictures/TeleRec. No storage
 * permission is needed on Android 10+. Each file is written into a pending
 * MediaStore entry, published when complete and deleted if the copy fails.
 */
class MediaStoreSaver(private val context: Context) : MediaSaving {
    override suspend fun saveVideo(file: File) = withContext(Dispatchers.IO) {
        file.inputStream().use {
            insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                "${name()}.mp4", "video/mp4", "${Environment.DIRECTORY_MOVIES}/TeleRec", it)
        }
    }

    override suspend fun savePhoto(data: ByteArray) = withContext(Dispatchers.IO) {
        insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            "${name()}.jpg", "image/jpeg", "${Environment.DIRECTORY_PICTURES}/TeleRec", data.inputStream())
    }

    private fun name() = "TeleRec_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"))

    private fun insert(collection: Uri, displayName: String, mime: String, relativePath: String, input: InputStream) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("the gallery refused the file")
        try {
            val out = resolver.openOutputStream(uri) ?: throw IOException("the gallery file couldn't be opened")
            out.use { input.copyTo(it, 1 shl 20) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }
}
