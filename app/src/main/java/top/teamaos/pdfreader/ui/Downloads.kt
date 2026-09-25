package top.teamaos.pdfreader.ui

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

/**
 * Putting a generated file into the phone's Downloads folder.
 *
 * Two routes, because scoped storage changed the rules midway through the versions this app
 * supports: from Android 10 the media store owns Downloads and hands back a URI to write into,
 * while older versions just want a path.
 */
object Downloads {

    /** Copy [source] into Downloads and return the name it was saved under, or null on failure. */
    fun save(context: Context, source: File): String? = runCatching {
        val name = uniqueName(source.name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching null
            resolver.openOutputStream(target)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: return@runCatching null
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(target, values, null, null)
            name
        } else {
            @Suppress("DEPRECATION")
            val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            directory.mkdirs()
            val target = File(directory, name)
            FileOutputStream(target).use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            }
            name
        }
    }.getOrNull()

    /** Avoid silently replacing a file somebody already has. */
    private fun uniqueName(name: String): String {
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "pdf")
        val stamp = System.currentTimeMillis() % 100000
        return "$base-$stamp.$extension"
    }
}
