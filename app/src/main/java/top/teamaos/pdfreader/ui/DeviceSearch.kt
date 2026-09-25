package top.teamaos.pdfreader.ui

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import top.teamaos.pdfreader.office.OfficeFormats
import java.io.File
import kotlin.coroutines.coroutineContext

/** One document found somewhere on the phone. */
data class FoundFile(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val modifiedAt: Long,
) {
    val uri: Uri get() = Uri.fromFile(File(path))
    val folder: String? get() = File(path).parent
}

/**
 * Finding documents anywhere on the phone by name.
 *
 * Two ways, in this order, because neither alone is enough. The media database knows about almost
 * everything and answers instantly, but it only knows what the media scanner has been told about —
 * a file just copied over USB, or one in a folder the scanner skips, is invisible to it. Walking
 * the storage directly finds those, and is slow enough that it only runs after the indexed answer
 * is already on screen.
 *
 * The walk is bounded on purpose: a phone with a full SD card has hundreds of thousands of files,
 * and someone typing three letters into a search box is not asking to have all of them read.
 */
object DeviceSearch {

    /** Files whose names contain [query], newest first. */
    suspend fun search(context: Context, query: String, limit: Int = 400): List<FoundFile> {
        val needle = query.trim()
        if (needle.length < MIN_QUERY) return emptyList()
        return find(context, needle, limit)
    }

    /** Every document on the phone and any memory card, newest first, for the Phone tab. */
    suspend fun everything(context: Context, limit: Int = 3000): List<FoundFile> = find(context, "", limit)

    /** An empty [needle] matches every supported file. */
    private suspend fun find(context: Context, needle: String, limit: Int): List<FoundFile> {
        val found = LinkedHashMap<String, FoundFile>()

        withContext(Dispatchers.IO) {
            fromMediaStore(context, needle, limit).forEach { found[canonical(it.path)] = it }
            coroutineContext.ensureActive()
            // The media database misses files it has not been told about, so the storage is
            // walked as well — always, not only when the database came up short, or a PDF copied
            // onto the memory card would never be found once the database had enough others.
            walkStorage(context, needle, limit, found)
        }
        return found.values
            .sortedWith(compareByDescending<FoundFile> { it.modifiedAt }.thenBy { it.name.lowercase() })
            .take(limit)
    }

    private fun fromMediaStore(context: Context, needle: String, limit: Int): List<FoundFile> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Files.getContentUri("external")
        }
        val projection = arrayOf(
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
        )
        // Narrowed to the extensions this app opens in the query itself; without that, listing
        // everything would read a row for every photo and song on the phone.
        val name = MediaStore.Files.FileColumns.DISPLAY_NAME
        val extensions = listOf("pdf") + OfficeFormats.EXTENSIONS
        val byType = extensions.joinToString(" OR ", "(", ")") { "$name LIKE ?" }
        val selection = if (needle.isEmpty()) byType else "$name LIKE ? AND $byType"
        val args = buildList {
            if (needle.isNotEmpty()) add("%$needle%")
            extensions.forEach { add("%.$it") }
        }.toTypedArray()
        val results = mutableListOf<FoundFile>()
        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                args,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC",
            )?.use { cursor -> results += cursor.toFiles(limit) }
        }
        return results
    }

    private fun Cursor.toFiles(limit: Int): List<FoundFile> {
        val out = mutableListOf<FoundFile>()
        val nameColumn = getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
        val pathColumn = getColumnIndex(MediaStore.Files.FileColumns.DATA)
        val sizeColumn = getColumnIndex(MediaStore.Files.FileColumns.SIZE)
        val modifiedColumn = getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
        while (moveToNext() && out.size < limit) {
            val path = if (pathColumn >= 0) getString(pathColumn) else null ?: continue
            if (path.isNullOrEmpty()) continue
            val name = if (nameColumn >= 0) getString(nameColumn) else File(path).name
            if (name.isNullOrEmpty() || !isSupported(name)) continue
            val file = File(path)
            if (!file.isFile) continue
            out += FoundFile(
                name = name,
                path = path,
                sizeBytes = if (sizeColumn >= 0) getLong(sizeColumn) else file.length(),
                // The media store keeps this in seconds; everything else here uses milliseconds.
                modifiedAt = if (modifiedColumn >= 0) getLong(modifiedColumn) * 1000L else file.lastModified(),
            )
        }
        return out
    }

    private suspend fun walkStorage(
        context: Context,
        needle: String,
        limit: Int,
        into: MutableMap<String, FoundFile>,
    ) {
        // The built-in storage and every memory card, as the system reports them.
        val roots = StorageAccess.storageRoots(context)

        var visited = 0
        val pending = ArrayDeque<File>()
        roots.forEach { pending.addLast(it) }

        while (pending.isNotEmpty() && into.size < limit && visited < MAX_VISITED) {
            coroutineContext.ensureActive()
            val directory = pending.removeFirst()
            if (directory.name.startsWith(".") || directory.name in SKIPPED) continue
            val children = directory.listFiles() ?: continue
            for (child in children) {
                visited++
                if (visited > MAX_VISITED) break
                if (child.isDirectory) {
                    pending.addLast(child)
                    continue
                }
                val name = child.name
                if (!name.contains(needle, ignoreCase = true) || !isSupported(name)) continue
                val key = canonical(child.absolutePath)
                if (into.containsKey(key)) continue
                into[key] = FoundFile(
                    name = name,
                    path = child.absolutePath,
                    sizeBytes = child.length(),
                    modifiedAt = child.lastModified(),
                )
                if (into.size >= limit) break
            }
        }
    }

    /** /sdcard/x and /storage/emulated/0/x are one file; key both the same way. */
    private fun canonical(path: String): String =
        runCatching { File(path).canonicalPath }.getOrDefault(path)

    private fun isSupported(name: String): Boolean =
        name.endsWith(".pdf", ignoreCase = true) || OfficeFormats.isOffice(name)

    /** Nothing worth searching lives in these, and they are enormous. */
    private val SKIPPED = setOf("Android", "cache", "LOST.DIR", "data", "obb")

    private const val MIN_QUERY = 2
    private const val MAX_VISITED = 120_000
}
