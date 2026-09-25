package top.teamaos.pdfreader.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import top.teamaos.pdfreader.R
import java.io.File

/**
 * Sharing a document with other apps.
 *
 * Handing a raw `file://` URI to another app throws [android.os.FileUriStrictMode]'s
 * FileUriExposedException and takes the process down, so anything on the filesystem is republished
 * through this app's FileProvider first. URIs that already came from a content provider are passed
 * straight through, since they are somebody else's to grant.
 */
object Sharing {

    fun share(context: Context, uri: Uri, displayName: String?) {
        val shareable = shareableUri(context, uri)
        if (shareable == null) {
            Toast.makeText(context, R.string.share_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val mime = mimeTypeOf(context, uri, displayName)
        val label = displayName ?: shareable.lastPathSegment ?: "document"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, shareable)
            putExtra(Intent.EXTRA_TITLE, label)
            putExtra(Intent.EXTRA_SUBJECT, label)
            // The read grant travels with the *ClipData*, not with EXTRA_STREAM. Without this the
            // chooser cannot even read the file to show a preview, and the app picked out of it
            // gets a URI it is not allowed to open — which is exactly what "share does nothing"
            // looked like. The clip carries the MIME type too, so the sheet offers the right apps.
            clipData = ClipData(
                label,
                arrayOf(mime),
                ClipData.Item(shareable),
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.action_share)))
        }.onFailure {
            Toast.makeText(context, R.string.share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareableUri(context: Context, uri: Uri): Uri? = when (uri.scheme) {
        "content" -> uri
        null, "file" -> uri.path?.let { path ->
            runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
            }.getOrNull()
        }
        else -> null
    }

    private fun mimeTypeOf(context: Context, uri: Uri, displayName: String?): String {
        context.contentResolver.getType(uri)?.let { return it }
        val extension = (displayName ?: uri.lastPathSegment.orEmpty())
            .substringAfterLast('.', "")
            .lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }
}
