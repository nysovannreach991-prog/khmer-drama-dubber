package com.khmerdubber.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** Saves results where the user can find them: Movies/KhmerDubber and Download/KhmerDubber. */
object Output {
    const val FOLDER = "KhmerDubber"

    /** The name Android actually gave the file (it adds " (1)" if the name is taken). */
    fun actualName(ctx: Context, uri: Uri, fallback: String): String = runCatching {
        ctx.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull() ?: fallback

    fun saveVideo(ctx: Context, file: File, name: String): Uri {
        val r = ctx.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = r.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: throw IOException("cannot save video to Movies")
            try {
                r.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 16) } }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                r.update(uri, values, null, null)
            } catch (e: Exception) {
                r.delete(uri, null, null)
                throw e
            }
            return uri
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER)
        dir.mkdirs()
        val dest = unique(dir, name)
        file.copyTo(dest, overwrite = true)
        val values = ContentValues().apply {
            @Suppress("DEPRECATION")
            put(MediaStore.Video.Media.DATA, dest.absolutePath)
            put(MediaStore.Video.Media.DISPLAY_NAME, dest.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        }
        return r.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: Uri.fromFile(dest)
    }

    fun saveText(ctx: Context, text: String, name: String): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val r = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/x-subrip")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            }
            val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("cannot save subtitles")
            r.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
            return "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/${actualName(ctx, uri, name)}"
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        dir.mkdirs()
        val dest = unique(dir, name)
        dest.writeText(text)
        return "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/${dest.name}"
    }

    private fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        var n = 1
        while (f.exists()) {
            f = File(dir, "${name.substringBeforeLast('.')} ($n).${name.substringAfterLast('.')}")
            n++
        }
        return f
    }
}
