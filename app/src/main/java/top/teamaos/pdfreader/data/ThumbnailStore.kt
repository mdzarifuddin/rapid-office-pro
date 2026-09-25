package top.teamaos.pdfreader.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.collection.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * First-page thumbnails for the file list, cached on disk.
 *
 * Rendering a cover every time the list scrolls would mean opening the document again, so they are
 * written out once and read back as small JPEGs afterwards.
 *
 * The cache is capped, hard, in both directions: a small in-memory LRU for the visible rows, and a
 * bounded directory on disk pruned oldest-first. Somebody who opens thousands of documents over
 * years ends up with the same few megabytes as somebody who opened ten.
 */
class ThumbnailStore private constructor(context: Context) {

    private val directory = File(context.cacheDir, "thumbs").apply { mkdirs() }

    private val memory = object : LruCache<String, Bitmap>(MEMORY_BUDGET_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    fun cached(key: String): Bitmap? = memory[key]

    suspend fun load(key: String): Bitmap? {
        memory[key]?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = fileFor(key)
            if (!file.exists()) return@withContext null
            val bitmap = runCatching { BitmapFactory.decodeFile(file.path) }.getOrNull()
            bitmap?.also {
                memory.put(key, it)
                // Touch it so pruning treats it as recently used.
                file.setLastModified(System.currentTimeMillis())
            }
        }
    }

    suspend fun save(key: String, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        runCatching {
            FileOutputStream(fileFor(key)).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            memory.put(key, bitmap)
        }
        prune()
    }

    fun has(key: String): Boolean = memory[key] != null || fileFor(key).exists()

    fun forget(key: String) {
        memory.remove(key)
        fileFor(key).delete()
    }

    /** Keep the directory within its caps, dropping the least recently touched files first. */
    private fun prune() {
        val files = directory.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        var kept = 0
        var bytes = 0L
        files.forEach { file ->
            kept++
            bytes += file.length()
            if (kept > MAX_FILES || bytes > MAX_BYTES) file.delete()
        }
    }

    /** Hash the URI so any path, however long or awkward, becomes a safe filename. */
    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(directory, "$name.jpg")
    }

    companion object {
        const val THUMBNAIL_WIDTH_PX = 220

        private const val JPEG_QUALITY = 82
        private const val MEMORY_BUDGET_BYTES = 6 * 1024 * 1024
        private const val MAX_FILES = 300
        private const val MAX_BYTES = 24L * 1024L * 1024L

        @Volatile
        private var instance: ThumbnailStore? = null

        fun get(context: Context): ThumbnailStore =
            instance ?: synchronized(this) {
                instance ?: ThumbnailStore(context.applicationContext).also { instance = it }
            }
    }
}
