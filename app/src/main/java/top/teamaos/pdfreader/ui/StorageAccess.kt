package top.teamaos.pdfreader.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

/**
 * Reading documents that belong to other apps, and remembering them by where they really are.
 *
 * Two problems, one cause. A PDF handed over by a messenger or a file manager arrives as a
 * `content://` link whose read grant dies with the app that sent it, so the history entry for it
 * stops opening a day later. And without all-files access, neither the media database nor a walk of
 * the storage will show this app another app's PDFs, so searching the phone found almost nothing.
 */
object StorageAccess {

    /** True when every document on the phone and the memory card is readable by path. */
    fun hasAllFilesAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
        }

    /** The settings page where all-files access is switched on for this app (Android 11+). */
    fun allFilesAccessIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.parse("package:${context.packageName}"))

    /**
     * Every storage root worth searching: the built-in storage and any memory card or USB drive.
     *
     * Asked of the system rather than read from /storage, which newer Android versions no longer
     * let an app list — that alone was enough to leave the memory card out of every search.
     */
    fun storageRoots(context: Context): List<File> {
        val roots = LinkedHashMap<String, File>()
        fun add(file: File?) {
            if (file == null || !file.isDirectory) return
            val key = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            roots.putIfAbsent(key, file)
        }
        add(Environment.getExternalStorageDirectory())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = context.getSystemService(StorageManager::class.java)
            manager?.storageVolumes?.forEach { add(it.directory) }
        }
        // Each volume's app folder sits at <root>/Android/data/<package>/files.
        context.getExternalFilesDirs(null).forEach { dir ->
            val path = dir?.absolutePath ?: return@forEach
            val cut = path.indexOf("/Android/")
            if (cut > 0) add(File(path.substring(0, cut)))
        }
        File("/storage").listFiles()?.forEach { candidate ->
            if (candidate.name != "emulated" && candidate.name != "self") add(candidate)
        }
        return roots.values.toList()
    }

    /**
     * The real file behind [uri], if there is one this app can read; null otherwise.
     *
     * Opening by path is also the fast route: no provider in the middle, and pdfium can reopen the
     * file by name for New PDF and Split.
     */
    fun resolveFile(context: Context, uri: Uri): File? {
        val candidate = when (uri.scheme) {
            null, "file" -> uri.path?.let(::File)
            "content" -> runCatching { fromContentUri(context, uri) }.getOrNull()
            else -> null
        }
        return candidate?.takeIf { it.isFile && it.canRead() }
    }

    private fun fromContentUri(context: Context, uri: Uri): File? {
        if (DocumentsContract.isDocumentUri(context, uri)) {
            val docId = DocumentsContract.getDocumentId(uri)
            when (uri.authority) {
                "com.android.externalstorage.documents" -> {
                    // "primary:Download/book.pdf", or "1A2B-3C4D:Books/book.pdf" on a memory card.
                    val volume = docId.substringBefore(':')
                    val relative = docId.substringAfter(':', "")
                    val root = if (volume.equals("primary", ignoreCase = true)) {
                        Environment.getExternalStorageDirectory()
                    } else {
                        storageRoots(context).firstOrNull { it.name.equals(volume, ignoreCase = true) }
                            ?: File("/storage/$volume")
                    }
                    return File(root, relative)
                }
                "com.android.providers.downloads.documents" -> {
                    if (docId.startsWith("raw:")) return File(docId.removePrefix("raw:"))
                    val numeric = docId.substringAfter(':').toLongOrNull()
                    if (numeric != null) {
                        val mediaUri = if (docId.startsWith("msf:") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, numeric)
                        } else {
                            ContentUris.withAppendedId(Uri.parse("content://downloads/public_downloads"), numeric)
                        }
                        dataColumn(context, mediaUri)?.let { return File(it) }
                    }
                }
                "com.android.providers.media.documents" -> {
                    val id = docId.substringAfter(':').toLongOrNull()
                    if (id != null) {
                        val filesUri = MediaStore.Files.getContentUri("external")
                        dataColumn(context, ContentUris.withAppendedId(filesUri, id))?.let { return File(it) }
                    }
                }
            }
        }
        // Media store links, and plenty of file managers, still fill in the old path column.
        dataColumn(context, uri)?.let { path -> File(path).takeIf { it.isFile }?.let { return it } }
        return guessFromProviderPath(context, uri)
    }

    private fun dataColumn(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val column = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (column >= 0) cursor.getString(column) else null
            }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * File managers' own providers usually wrap a real path after a made-up first segment:
     * `content://…fileprovider/external_files/Download/book.pdf` or `…/root/storage/…/book.pdf`.
     * Try the path as it stands, then without its first segment against each storage root.
     */
    private fun guessFromProviderPath(context: Context, uri: Uri): File? {
        val segments = uri.pathSegments.orEmpty().map(Uri::decode)
        if (segments.isEmpty()) return null
        val whole = "/" + segments.joinToString("/")
        File(whole).takeIf { it.isFile }?.let { return it }
        if (segments.size < 2) return null
        val rest = segments.drop(1).joinToString("/")
        File("/$rest").takeIf { it.isFile }?.let { return it }
        for (root in storageRoots(context)) {
            File(root, rest).takeIf { it.isFile }?.let { return it }
        }
        return null
    }
}
