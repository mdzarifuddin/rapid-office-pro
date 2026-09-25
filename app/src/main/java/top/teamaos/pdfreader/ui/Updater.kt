package top.teamaos.pdfreader.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.teamaos.pdfreader.BuildConfig
import top.teamaos.pdfreader.R
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * "Install latest version": new builds are published as GitHub releases, and the app installs
 * them itself.
 *
 * A release is found through GitHub's public API, its APK is downloaded into this app's cache,
 * and Android's own installer is handed the file. Every build is signed with the same key, so the
 * new one installs over this one and keeps all reading history. Nothing is installed without the
 * phone's own confirmation screen.
 */
object Updater {

    class Release(val version: String, val notes: String, val apkUrl: String, val sizeBytes: Long)

    /** The newest published release, or null if it cannot be reached or has no APK attached. */
    suspend fun latestRelease(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = open("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.use { if (it.responseCode != 200) null else it.inputStream.bufferedReader().readText() }
                ?.let { body ->
                    val json = JSONObject(body)
                    val assets = json.optJSONArray("assets")
                    var apkUrl: String? = null
                    var size = 0L
                    for (i in 0 until (assets?.length() ?: 0)) {
                        val asset = assets!!.getJSONObject(i)
                        if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url")
                            size = asset.optLong("size")
                            break
                        }
                    }
                    apkUrl?.let {
                        Release(
                            version = json.optString("tag_name").removePrefix("v").removePrefix("V"),
                            notes = json.optString("body").trim(),
                            apkUrl = it,
                            sizeBytes = size,
                        )
                    }
                }
        }.getOrNull()
    }

    /** True when [release] is newer than the version running now. "2.10" is newer than "2.9". */
    fun isNewer(release: Release): Boolean {
        val running = parts(BuildConfig.VERSION_NAME)
        val offered = parts(release.version)
        for (i in 0 until maxOf(running.size, offered.size)) {
            val a = running.getOrElse(i) { 0 }
            val b = offered.getOrElse(i) { 0 }
            if (a != b) return b > a
        }
        return false
    }

    private fun parts(version: String): List<Int> =
        version.substringBefore('-').split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }

    /**
     * The whole flow behind the menu item: check, say what was found, and on a yes download and
     * install. [quiet] is for the automatic check on the home screen: it says nothing at all unless
     * there is actually something new.
     */
    fun checkAndOffer(activity: Activity, scope: LifecycleCoroutineScope, quiet: Boolean = false) {
        scope.launch {
            if (!quiet) Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show()
            val release = latestRelease()
            if (activity.isFinishing) return@launch
            when {
                release == null -> if (!quiet) {
                    Toast.makeText(activity, R.string.update_unreachable, Toast.LENGTH_LONG).show()
                }
                !isNewer(release) -> if (!quiet) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.update_current, BuildConfig.VERSION_NAME),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                else -> offer(activity, scope, release)
            }
        }
    }

    private fun offer(activity: Activity, scope: LifecycleCoroutineScope, release: Release) {
        val sizeMb = if (release.sizeBytes > 0) " (%.1f MB)".format(release.sizeBytes / 1_048_576.0) else ""
        val message = buildString {
            append(activity.getString(R.string.update_available_detail, BuildConfig.VERSION_NAME, release.version))
            append(sizeMb)
            if (release.notes.isNotEmpty()) append("\n\n").append(release.notes.take(MAX_NOTES_CHARS))
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_available, release.version))
            .setMessage(message)
            .setPositiveButton(R.string.update_install) { _, _ -> download(activity, scope, release) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun download(activity: Activity, scope: LifecycleCoroutineScope, release: Release) {
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            val pad = (20 * activity.resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
        }
        var job: Job? = null
        val dialog: AlertDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.update_downloading, release.version))
            .setView(progress)
            .setCancelable(false)
            .setNegativeButton(R.string.cancel) { _, _ -> job?.cancel() }
            .show()

        job = scope.launch {
            val file = runCatching {
                downloadTo(activity, release) { percent -> progress.post { progress.progress = percent } }
            }.getOrNull()
            dialog.dismiss()
            if (file == null) {
                Toast.makeText(activity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            install(activity, file)
        }
    }

    private suspend fun downloadTo(context: Context, release: Release, onProgress: (Int) -> Unit): File? =
        withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "updates").apply {
                deleteRecursively()
                mkdirs()
            }
            val target = File(directory, "RapidOfficePro-${release.version}.apk")
            val partial = File(directory, target.name + ".part")
            open(release.apkUrl).use { connection ->
                if (connection.responseCode != 200) return@withContext null
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        var lastPercent = -1
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (total > 0) {
                                val percent = (copied * 100 / total).toInt()
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    onProgress(percent)
                                }
                            }
                        }
                    }
                }
                if (total > 0 && partial.length() != total) return@withContext null
            }
            if (!partial.renameTo(target)) return@withContext null
            target
        }

    /**
     * Hand the downloaded APK to the system installer. The first time, Android asks for this app to
     * be allowed to install apps; that screen is opened, and the install is offered again after.
     */
    private fun install(activity: Activity, apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_permission_title)
                .setMessage(R.string.update_permission_message)
                .setPositiveButton(R.string.update_permission_open) { _, _ ->
                    runCatching {
                        activity.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                                .setData(Uri.parse("package:${activity.packageName}")),
                        )
                    }
                    pendingInstall = apk
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { activity.startActivity(intent) }.onFailure {
            Toast.makeText(activity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
        }
    }

    /** A download waiting for the "install unknown apps" switch; offered again on return. */
    private var pendingInstall: File? = null

    /** Call from onResume of any screen that can start an update. */
    fun resumePendingInstall(activity: Activity) {
        val apk = pendingInstall ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !activity.packageManager.canRequestPackageInstalls()) return
        pendingInstall = null
        if (apk.isFile) install(activity, apk)
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "RapidOfficePro/${BuildConfig.VERSION_NAME}")
        }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T =
        try {
            block(this)
        } finally {
            disconnect()
        }

    private const val TIMEOUT_MS = 20_000
    private const val MAX_NOTES_CHARS = 1500
}
