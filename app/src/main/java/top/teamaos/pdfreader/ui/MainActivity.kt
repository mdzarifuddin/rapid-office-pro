package top.teamaos.pdfreader.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.teamaos.pdfreader.R
import top.teamaos.pdfreader.data.DocumentRecord
import top.teamaos.pdfreader.data.ReaderDatabase
import top.teamaos.pdfreader.databinding.ActivityMainBinding
import top.teamaos.pdfreader.office.OfficeFormats

/** Home screen: recent and favourite documents, with a search filter and a picker. */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val adapter = DocumentAdapter(
        scope = lifecycleScope,
        onOpen = { record -> openRecord(record) },
        onToggleFavourite = { record -> toggleFavourite(record) },
        onShare = { record -> shareDocument(record) },
        onRemove = { record -> removeDocument(record) },
    )

    /** Everything loaded for the current tab, before the search box narrows it down. */
    private var loaded: List<DocumentRecord> = emptyList()
    private var query: String = ""

    /** Files found elsewhere on the phone for the current query, and the job that found them. */
    private var deviceResults: List<DocumentRecord> = emptyList()
    private var deviceSearchJob: Job? = null
    private var searchRunning = false

    /** The Phone tab's folders; its documents go through [adapter] like every other list. */
    private val folderAdapter = FolderAdapter { entry -> openFolder(entry) }

    /** The folder the Phone tab is showing, or null for the list of storages. */
    private var browseDir: java.io.File? = null
    private var folderJob: Job? = null
    private var folderLoading = false

    /** Recent and favourite together: what the search box looks through, whichever tab is open. */
    private var history: List<DocumentRecord> = emptyList()

    /** Asked at most once per launch; the switch itself lives on the system settings screen. */
    private var askedForAccess = false

    private val requestReadPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) onAccessGranted() }

    private val pickDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(::openDocument) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()

        binding.documentList.layoutManager = LinearLayoutManager(this)
        binding.documentList.adapter = ConcatAdapter(folderAdapter, adapter)
        binding.openFab.setOnClickListener { pickDocument.launch(SUPPORTED_MIME_TYPES) }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.tab_recent))
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.tab_favourites))
        binding.tabs.addTab(binding.tabs.newTab().setText(R.string.tab_device))
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = refresh()
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        clearLeftovers()

        // In the file browser, Back climbs one folder before it leaves the app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val dir = browseDir
                if (browsing() && dir != null) {
                    openFolder(upEntry(dir))
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        binding.searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val wasSearching = query.isNotBlank()
                query = s?.toString().orEmpty()
                // Back from a search on the Phone tab: put the folder that was open back.
                if (wasSearching && browsing()) loadFolder(browseDir) else applyFilter()
                startDeviceSearch(query)
            }
        })
    }

    /**
     * A save writes a whole copy of the PDF to the cache first. If the app is killed partway
     * through, that copy — as big as the document — is never deleted, and over weeks they pile up
     * until the phone is short of space and everything slows. Anything that old is abandoned.
     */
    private fun clearLeftovers() {
        lifecycleScope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - LEFTOVER_AGE_MS
            cacheDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name.startsWith("save-") && file.lastModified() < cutoff) {
                    file.delete()
                }
            }
        }
    }

    private fun applyWindowInsets() {
        val fabMargin = resources.getDimensionPixelSize(R.dimen.fab_margin)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // The header's colour runs behind the status bar rather than stopping under it, so the
            // top of the screen is one block and not a pale strip above a coloured one.
            binding.header.updatePadding(top = bars.top)
            binding.documentList.updatePadding(bottom = bars.bottom + LIST_BOTTOM_PADDING_DP)
            binding.openFab.updateLayoutParams<androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams> {
                bottomMargin = fabMargin + bars.bottom
            }
            insets
        }
    }

    override fun onResume() {
        super.onResume()
        hadAccess = StorageAccess.hasAllFilesAccess(this)
        refresh()
        Updater.resumePendingInstall(this)
        checkForUpdateDaily()
        if (!hadAccess && !askedForAccess) {
            askedForAccess = true
            askForAccess()
        }
    }

    private var hadAccess = false

    /** Once a day, silently: only speaks up when a newer version is actually out. */
    private fun checkForUpdateDaily() {
        val settings = top.teamaos.pdfreader.data.Settings.get(this)
        val now = System.currentTimeMillis()
        if (now - settings.lastUpdateCheck < UPDATE_CHECK_INTERVAL_MS) return
        settings.lastUpdateCheck = now
        Updater.checkAndOffer(this, lifecycleScope, quiet = true)
    }

    /**
     * Without this, the phone search and the Phone tab can only see a fraction of the documents on
     * the phone, and a file opened from history by its path cannot be read at all.
     */
    private fun askForAccess() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.access_title)
            .setMessage(R.string.access_message)
            .setPositiveButton(R.string.access_allow) { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    runCatching { startActivity(StorageAccess.allFilesAccessIntent(this)) }.onFailure {
                        runCatching {
                            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    }
                } else {
                    requestReadPermission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }
            .setNegativeButton(R.string.access_later, null)
            .show()
    }

    private fun onAccessGranted() {
        hadAccess = true
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            val database = ReaderDatabase.get(applicationContext)
            val recent = database.recentDocuments()
            val favourites = database.favouriteDocuments()
            history = (recent + favourites).distinctBy { it.id }
            loaded = when (binding.tabs.selectedTabPosition) {
                TAB_FAVOURITES -> favourites
                TAB_RECENT -> recent
                else -> loaded
            }
            if (binding.tabs.selectedTabPosition == TAB_DEVICE) loadFolder(browseDir) else applyFilter()
        }
    }

    /** The Phone tab with nothing typed: a file manager, folder by folder. */
    private fun browsing(): Boolean = binding.tabs.selectedTabPosition == TAB_DEVICE && query.isBlank()

    private fun openFolder(entry: FolderEntry) {
        browseDir = if (entry.isUp && isRoot(entry.dir)) null else entry.dir
        loadFolder(browseDir)
        binding.documentList.scrollToPosition(0)
    }

    /** The row that climbs out of [dir]: to its parent, or to the storages list from a root. */
    private fun upEntry(dir: java.io.File) = FolderEntry(
        dir = if (isRoot(dir)) dir else (dir.parentFile ?: dir),
        name = "..",
        detail = null,
        isUp = true,
    )

    private fun isRoot(dir: java.io.File): Boolean {
        val path = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        return StorageAccess.storageRoots(this).any {
            runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) == path
        }
    }

    /**
     * List one folder: its subfolders first, then the documents this app can open, both by name.
     * Documents already read come with their history entry, so their progress bar and star show.
     * Hidden folders and ones with nothing but app data in them are left out, as file managers do.
     */
    private fun loadFolder(dir: java.io.File?) {
        folderJob?.cancel()
        folderLoading = true
        applyFilter()
        folderJob = lifecycleScope.launch {
            val known = history
            val (folders, documents) = withContext(Dispatchers.IO) {
                if (dir == null) {
                    storageEntries() to emptyList()
                } else {
                    val children = dir.listFiles().orEmpty().filterNot { it.name.startsWith(".") }
                    val folders = children.filter { it.isDirectory }
                        .sortedBy { it.name.lowercase() }
                        .map { folder ->
                            val count = folder.list()?.count { !it.startsWith(".") } ?: 0
                            FolderEntry(folder, folder.name, resources.getQuantityString(R.plurals.folder_items, count, count))
                        }
                    val byKey = known.associateBy { fileKey(it.uri) }
                    val documents = children.filter { it.isFile && isOpenable(it.name) }
                        .sortedBy { it.name.lowercase() }
                        .map { file ->
                            val found = FoundFile(file.name, file.absolutePath, file.length(), file.lastModified())
                            byKey[fileKey(found.uri.toString())] ?: found.toRecord()
                        }
                    (listOf(upEntry(dir)) + folders) to documents
                }
            }
            folderLoading = false
            if (!browsing() || browseDir != dir) return@launch
            folderAdapter.submitList(folders)
            loaded = documents
            applyFilter()
        }
    }

    /** The top of the browser: built-in storage and any memory card, named the way the phone names them. */
    private fun storageEntries(): List<FolderEntry> {
        val manager = getSystemService(android.os.storage.StorageManager::class.java)
        return StorageAccess.storageRoots(this).mapIndexed { index, root ->
            val described = runCatching { manager?.getStorageVolume(root)?.getDescription(this) }.getOrNull()
            val name = described?.takeIf { it.isNotBlank() }
                ?: getString(if (index == 0) R.string.storage_internal else R.string.storage_card)
            val free = runCatching { android.text.format.Formatter.formatShortFileSize(this, root.freeSpace) }
                .getOrNull()
            FolderEntry(root, name, free?.let { getString(R.string.storage_free, it) })
        }
    }

    private fun isOpenable(name: String): Boolean =
        name.endsWith(".pdf", ignoreCase = true) || OfficeFormats.isOffice(name)

    /** Where the browser is, as a trail: "Internal storage › Download › Books". */
    private fun breadcrumb(dir: java.io.File?): String {
        if (dir == null) return getString(R.string.browse_storages)
        val path = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
        val roots = storageEntries()
        val root = roots.firstOrNull {
            val rootPath = runCatching { it.dir.canonicalPath }.getOrDefault(it.dir.absolutePath)
            path == rootPath || path.startsWith("$rootPath/")
        } ?: return path
        val rootPath = runCatching { root.dir.canonicalPath }.getOrDefault(root.dir.absolutePath)
        val rest = path.removePrefix(rootPath).trim('/').split('/').filter { it.isNotEmpty() }
        return (listOf(root.name) + rest).joinToString("  ›  ")
    }

    /**
     * Show what matches.
     *
     * With something typed, the search is the same whichever tab is open: everything read before
     * or starred comes first — a name someone half-remembers is usually a file they have read —
     * then everything else on the phone and the memory card, without anything listed twice.
     */
    private fun applyFilter() {
        val searching = query.isNotBlank()
        val onDevice = binding.tabs.selectedTabPosition == TAB_DEVICE
        if (!browsing()) folderAdapter.submitList(emptyList())

        val fromHistory = if (!searching) {
            loaded
        } else {
            history.filter { it.displayName.contains(query, ignoreCase = true) }
        }
        // Matched by the file they point at, not by the text of the URI: history records a file
        // as /sdcard/..., storage reports it as /storage/emulated/0/..., and the same file would
        // otherwise be listed twice.
        val known = fromHistory.map { fileKey(it.uri) }.toHashSet()
        val fromDevice = if (!searching) emptyList() else deviceResults.filter { fileKey(it.uri) !in known }
        if (searching) {
            // PDFs first — they are what this app is mostly for — keeping read-before files ahead
            // within each kind. Then back to the top: results landing above the first visible row
            // used to leave the list scrolled down, with the best matches out of sight.
            val results = (fromHistory + fromDevice).sortedBy { !it.displayName.endsWith(".pdf", ignoreCase = true) }
            adapter.submitList(results) { binding.documentList.scrollToPosition(0) }
        } else {
            adapter.submitList(fromHistory + fromDevice)
        }

        binding.searchStatus.visibility = if (searching || onDevice) View.VISIBLE else View.GONE
        if (browsing()) {
            binding.searchStatus.text = if (!StorageAccess.hasAllFilesAccess(this)) {
                getString(R.string.access_needed)
            } else {
                breadcrumb(browseDir)
            }
        }
        if (searching) {
            val total = fromHistory.size + fromDevice.size
            binding.searchStatus.text = when {
                searchRunning ->
                    getString(R.string.search_looking, total)
                total == 0 -> getString(R.string.search_none, query)
                else -> getString(R.string.search_found, total, fromDevice.size)
            }
        }

        val favourites = binding.tabs.selectedTabPosition == TAB_FAVOURITES
        val empty = fromHistory.isEmpty() && fromDevice.isEmpty() &&
            (!browsing() || folderAdapter.itemCount == 0)
        val stillLoading = browsing() && folderLoading
        binding.emptyView.visibility = if (empty && !searching && !stillLoading) View.VISIBLE else View.GONE
        binding.emptyTitle.setText(
            when {
                onDevice -> R.string.empty_device
                favourites -> R.string.empty_starred
                else -> R.string.empty_recent
            },
        )
        binding.emptyDetail.setText(
            when {
                onDevice -> R.string.empty_device_detail
                favourites -> R.string.empty_starred_detail
                else -> R.string.empty_recent_detail
            },
        )
    }

    /** What two URIs have to agree on to be the same document. */
    private fun fileKey(uri: String): String {
        if (!uri.startsWith("file:")) return uri
        val path = Uri.parse(uri).path ?: return uri
        return runCatching { java.io.File(path).canonicalPath }.getOrDefault(path)
    }

    /**
     * Look for the name across the whole phone, a moment after the typing stops.
     *
     * Debounced, and cancelled on every keystroke: the indexed part of the search is instant but
     * the fallback walks storage, and starting one of those per letter typed would leave several
     * running over each other.
     */
    private fun startDeviceSearch(text: String) {
        deviceSearchJob?.cancel()
        deviceResults = emptyList()
        val needle = text.trim()
        if (needle.length < MIN_DEVICE_QUERY) {
            searchRunning = false
            applyFilter()
            return
        }
        searchRunning = true
        deviceSearchJob = lifecycleScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val found = DeviceSearch.search(applicationContext, needle)
            searchRunning = false
            deviceResults = found.map { it.toRecord() }
            applyFilter()
        }
    }

    private fun FoundFile.toRecord() = DocumentRecord(
        id = 0L,
        uri = uri.toString(),
        displayName = name,
        folder = folder,
        sizeBytes = sizeBytes,
        modifiedAt = modifiedAt,
        pageCount = 0,
        lastPage = 0,
        lastFractionX = 0f,
        lastFractionY = 0f,
        lastZoom = 1f,
        viewMode = 0,
        colorMode = 0,
        isFavorite = false,
        lastOpenedAt = 0L,
    )

    /**
     * Open something from the list, finding it again if the link it was saved under has died.
     *
     * History made before paths were remembered holds links lent by other apps, which stop working
     * once those apps take them back — that was "it often will not open from the list". Such an
     * entry is resolved to its real path, or failing that found on storage by name and size, and
     * the entry is repointed so its reading position and bookmarks come along.
     */
    private fun openRecord(record: DocumentRecord) {
        lifecycleScope.launch {
            val original = Uri.parse(record.uri)
            val target = withContext(Dispatchers.IO) { locate(record, original) }
            if (target == null) {
                Toast.makeText(this@MainActivity, R.string.file_missing, Toast.LENGTH_LONG).show()
                return@launch
            }
            if (target != original && record.id > 0) {
                ReaderDatabase.get(applicationContext).relocateDocument(record.id, target.toString())
            }
            openDocument(target)
        }
    }

    private suspend fun locate(record: DocumentRecord, original: Uri): Uri? {
        StorageAccess.resolveFile(applicationContext, original)?.let { return Uri.fromFile(it) }
        if (original.scheme == "content" && canRead(original)) return original
        if (record.displayName.length < 2) return null
        return DeviceSearch.search(applicationContext, record.displayName, limit = 50)
            .firstOrNull { found ->
                found.name.equals(record.displayName, ignoreCase = true) &&
                    (record.sizeBytes <= 0L || found.sizeBytes == record.sizeBytes)
            }
            ?.uri
    }

    private fun canRead(uri: Uri): Boolean = runCatching {
        contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    /**
     * Send the file to whichever reader understands it.
     *
     * The decision is made once, here, rather than by each reader checking whether the file is
     * really theirs — so there is exactly one place that knows which screen a format opens on.
     */
    private fun openDocument(uri: Uri) {
        val target = if (isPdf(uri)) ReaderActivity::class.java else OfficeActivity::class.java
        startActivity(
            Intent(this, target).apply {
                putExtra(ReaderActivity.EXTRA_URI, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
        )
    }

    private fun isPdf(uri: Uri): Boolean {
        val name = uri.lastPathSegment.orEmpty()
        if (name.endsWith(".pdf", ignoreCase = true)) return true
        val extension = OfficeFormats.extensionOf(name)
        if (extension.isNotEmpty() && extension in OfficeFormats.EXTENSIONS) return false
        // The name told us nothing — a content URI is often just a number — so ask the provider.
        val type = runCatching { contentResolver.getType(uri) }.getOrNull().orEmpty()
        if (type == "application/pdf") return true
        if (type.isNotEmpty() && OfficeFormats.isOfficeMimeType(type)) return false
        val displayName = OfficeFormats.displayNameOf(this, uri)
        return !OfficeFormats.isOffice(displayName)
    }

    private fun toggleFavourite(record: DocumentRecord) {
        lifecycleScope.launch {
            val database = ReaderDatabase.get(applicationContext)
            // A file found by searching storage has no row yet; starring it is what creates one.
            val id = if (record.id > 0) {
                record.id
            } else {
                database.upsertDocument(
                    uri = record.uri,
                    displayName = record.displayName,
                    folder = record.folder,
                    sizeBytes = record.sizeBytes,
                    modifiedAt = record.modifiedAt,
                    pageCount = record.pageCount,
                    openedAt = 0L,
                )
            }
            database.setFavourite(id, !record.isFavorite)
            refresh()
        }
    }

    private fun shareDocument(record: DocumentRecord) {
        Sharing.share(this, Uri.parse(record.uri), record.displayName)
    }

    private fun removeDocument(record: DocumentRecord) {
        // Nothing to forget about a file that was only found by searching for it.
        if (record.id <= 0) {
            deviceResults = deviceResults.filterNot { it.uri == record.uri }
            applyFilter()
            return
        }
        lifecycleScope.launch {
            ReaderDatabase.get(applicationContext).removeDocument(record.id)
            refresh()
        }
    }

    private companion object {
        const val TAB_RECENT = 0
        const val TAB_FAVOURITES = 1
        const val TAB_DEVICE = 2
        const val LEFTOVER_AGE_MS = 30L * 60L * 1000L
        const val UPDATE_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L

        /** Below this the whole phone would match, so only the history is filtered. */
        const val MIN_DEVICE_QUERY = 2
        const val SEARCH_DEBOUNCE_MS = 260L
        const val LIST_BOTTOM_PADDING_DP = 96
        /**
         * What the Open button offers.
         *
         * Listed one by one rather than as a wildcard: a picker showing every file on the phone
         * is a worse experience than one showing the documents this app can actually open. The
         * octet-stream entry is there because plenty of providers report that for a .docx.
         */
        val SUPPORTED_MIME_TYPES = arrayOf(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/rtf",
            "text/plain",
            "text/csv",
            "text/comma-separated-values",
            "application/octet-stream",
        )
    }
}
