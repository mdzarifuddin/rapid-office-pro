package top.teamaos.pdfreader.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowCompat
import androidx.core.view.marginBottom
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import top.teamaos.pdfreader.R
import top.teamaos.pdfreader.data.ReaderDatabase
import top.teamaos.pdfreader.data.ScreenOrientationMode
import top.teamaos.pdfreader.data.Settings
import top.teamaos.pdfreader.databinding.ActivityOfficeBinding
import top.teamaos.pdfreader.office.OfficeDocument
import top.teamaos.pdfreader.office.OfficeFormats
import top.teamaos.pdfreader.office.OfficeLayout
import top.teamaos.pdfreader.view.ColorMode
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The reading screen for Word, Excel, PowerPoint and text files.
 *
 * A sibling of [ReaderActivity] rather than a mode inside it. The two share their look, their bar
 * and their gestures, and share nothing underneath: one drives pdfium and a tiled renderer, the
 * other drives a measured text layout. Folding both into one screen would mean every method
 * starting by asking which kind of document it was looking at, which is exactly the shape of code
 * that rots.
 */
class OfficeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOfficeBinding

    private var document: OfficeDocument? = null
    private var uri: Uri? = null
    private var documentId: Long = 0L
    private var chromeVisible = true
    private var colorMode = ColorMode.NORMAL

    private val matches = mutableListOf<Pair<Int, RectF>>()
    private var matchIndex = -1
    private var searchJob: Job? = null
    private var selectedText: String? = null

    private var adjustingZoomSlider = false
    private var settingSliderProgrammatically = false

    /**
     * This screen's orientation. Starts from the settings every time a document is opened; the
     * screen button changes only this, so turning one document sideways is not remembered.
     */
    private lateinit var orientationMode: ScreenOrientationMode

    private val inputMethodManager by lazy {
        getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }

    private val hideChipRunnable = Runnable { binding.pageChip.visibility = View.GONE }
    private val hideZoomSliderRunnable = Runnable { binding.zoomSliderBar.visibility = View.GONE }

    /**
     * singleTop: a second document opened while this one is on top replaces it instead of stacking
     * another viewer above, each with its whole parsed document in memory.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recreate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOfficeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        applyWindowInsets()
        BarSizing.attach(binding.bottomScroll, binding.bottomBar)
        wireCallbacks()
        orientationMode = Settings.get(this).orientationMode
        applyOrientation()
        applyKeepScreenOn()

        // Both keys: the file list hands documents to whichever reader they belong to using one
        // extra, and this screen has its own name for it as well.
        // A file shared to the app arrives as a stream, not as data; a bare piece of shared text
        // has neither, and is shown as a text document of its own rather than as an error.
        val target = intent.data
            ?: intent.getParcelableExtra<Uri>(ReaderActivity.EXTRA_URI)
            ?: intent.getParcelableExtra<Uri>(EXTRA_URI)
            ?: intent.takeIf { it.action == Intent.ACTION_SEND }?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            ?: sharedTextAsFile()
        if (target == null) {
            showError(getString(R.string.error_open_failed))
            return
        }
        takePersistablePermission(target)
        open(target)
    }

    private fun applyWindowInsets() {
        val barMargin = binding.bottomBarScroller.marginBottom
        val scrollMargin = binding.fastScroll.marginBottom
        val toolbarHeight = android.util.TypedValue().let { value ->
            if (theme.resolveAttribute(android.R.attr.actionBarSize, value, true)) {
                android.util.TypedValue.complexToDimensionPixelSize(value.data, resources.displayMetrics)
            } else {
                0
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            listOf(binding.toolbar, binding.searchBar).forEach { bar ->
                bar.updateLayoutParams<FrameLayout.LayoutParams> {
                    topMargin = 0
                    height = toolbarHeight + bars.top
                }
                bar.updatePadding(top = bars.top)
            }
            binding.limitationNote.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = toolbarHeight + bars.top
            }
            binding.bottomBarScroller.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = barMargin + bars.bottom
            }
            binding.textActionBar.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = barMargin + bars.bottom
            }
            binding.fastScroll.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = bars.top + toolbarHeight
                bottomMargin = scrollMargin + bars.bottom
            }
            headerHeight = toolbarHeight + bars.top
            applyHeaderInset()
            insets
        }
    }

    private var headerHeight = 0

    /** The document starts under the header while it is showing, and fills the screen when it is not. */
    private fun applyHeaderInset() {
        val note = if (binding.limitationNote.visibility == View.VISIBLE) {
            binding.limitationNote.height
        } else {
            0
        }
        val wanted = if (chromeVisible && !searchOpen) headerHeight + note else 0
        val current = (binding.officeView.layoutParams as? FrameLayout.LayoutParams)?.topMargin
        if (current == wanted) return
        binding.officeView.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = wanted }
    }

    private fun wireCallbacks() {
        binding.officeView.onSingleTap = { toggleChrome() }
        binding.officeView.onPageChanged = { page -> onPageChanged(page) }
        binding.officeView.onScaleChanged = { updateZoomLabel(reveal = true) }
        binding.officeView.onTextLongPress = { text, _ -> onTextSelected(text) }

        binding.zoomButton.setOnClickListener { toggleZoomSlider() }
        binding.zoomInButton.setOnClickListener { binding.officeView.zoomIn() }
        binding.zoomOutButton.setOnClickListener { binding.officeView.zoomOut() }
        binding.zoomResetButton.setOnClickListener { binding.officeView.resetZoom() }
        binding.zoomResetButton.setOnLongClickListener {
            binding.officeView.fitWidth()
            true
        }
        binding.zoomSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || settingSliderProgrammatically) return@addOnChangeListener
            adjustingZoomSlider = true
            binding.officeView.setZoomLevel(2f.pow(value))
            adjustingZoomSlider = false
            binding.zoomPercent.text =
                getString(R.string.zoom_level, (binding.officeView.zoomLevel * 100).roundToInt())
            revealZoomSlider()
        }

        binding.contentsButton.setOnClickListener { showContents() }
        binding.orientationButton.setOnClickListener { toggleOrientation() }
        binding.orientationButton.setOnLongClickListener {
            followPhoneOrientation()
            true
        }
        binding.toolColourButton.setOnClickListener { chooseColourMode() }
        binding.toolShareButton.setOnClickListener {
            uri?.let { Sharing.share(this, it, document?.displayName) }
        }
        binding.toolOpenWithButton.setOnClickListener { openWithAnotherApp() }
        binding.pageIndicator.setOnClickListener { promptGoToPage() }

        binding.textCopyButton.setOnClickListener { copySelectedText() }
        binding.textFindButton.setOnClickListener { findSelectedText() }
        binding.textTranslateButton.setOnClickListener { translateSelectedText() }
        binding.textShareButton.setOnClickListener { shareSelectedText() }

        binding.searchCloseButton.setOnClickListener { closeSearch() }
        binding.searchPrevButton.setOnClickListener { stepSearch(-1) }
        binding.searchNextButton.setOnClickListener { stepSearch(1) }
        binding.searchInput.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = view.text.toString()
                if (query.isNotBlank()) {
                    inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
                    runSearch(query)
                }
                true
            } else {
                false
            }
        }

        binding.fastScroll.onSeek = { fraction, settled ->
            val total = binding.officeView.pageCount
            if (total > 0) {
                val page = (fraction * (total - 1)).toInt().coerceIn(0, total - 1)
                updateScrollLabels(page, total)
                binding.officeView.goToPage(page)
                if (settled) showPageChip(page, total)
            }
        }
        binding.fastScroll.onDragStateChanged = { dragging, handleCentreY ->
            binding.scrollBubble.visibility = if (dragging) View.VISIBLE else View.GONE
            if (dragging) {
                val top = binding.fastScroll.top + handleCentreY - binding.scrollBubble.height / 2f
                binding.scrollBubble.translationY = top.coerceAtLeast(0f)
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            when {
                binding.officeView.isSlideShow -> stopSlideShow()
                binding.textActionBar.visibility == View.VISIBLE -> clearSelection()
                searchOpen -> closeSearch()
                else -> finish()
            }
        }
    }

    // ------------------------------------------------------------------ opening

    /**
     * Read and lay the document out, both off the main thread.
     *
     * Parsing and measuring are one background job rather than two. Measuring is the slow half —
     * every paragraph in the file gets a text layout built for it — and splitting them would only
     * mean showing an empty page for the second half of the wait.
     */
    private fun open(target: Uri) {
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.errorView.visibility = View.GONE

        lifecycleScope.launch {
            // Remembered by its real path where it has one, so the history entry keeps opening
            // after the app that sent it has taken its read grant back. Resolving it walks the
            // storage roots, which on a slow SD card is long enough to freeze the screen, so it
            // is done with the rest of the file work rather than on the main thread.
            val located = withContext(Dispatchers.IO) {
                runCatching { StorageAccess.resolveFile(this@OfficeActivity, target) }.getOrNull()
            }?.let(Uri::fromFile) ?: target
            uri = located
            val name = withContext(Dispatchers.IO) { OfficeFormats.displayNameOf(this@OfficeActivity, located) }
            var isPdf = false
            var unsupported: OfficeFormats.Opened.Unsupported? = null
            // Interruptible, so backing out of a huge file stops the parser and the layout where they
            // are (both check for it in their loops) instead of finishing for nobody.
            val result = try {
                runInterruptible(Dispatchers.IO) {
                    // Anything going wrong in a parser or in measuring — including running out of
                    // memory on a huge file — is this file not opening, not the app closing.
                    try {
                        val file = OfficeFormats.localCopy(this@OfficeActivity, located)
                            ?: return@runInterruptible null
                        val started = android.os.SystemClock.elapsedRealtime()
                        when (val opened = OfficeFormats.open(file, name)) {
                            is OfficeFormats.Opened.Document -> {
                                val parsed = android.os.SystemClock.elapsedRealtime()
                                val laidOut = OfficeLayout.paginate(opened.document)
                                // How long opening took, by stage: the first thing to look at when
                                // a big file feels slow.
                                android.util.Log.i(
                                    TAG,
                                    "Opened $name: read ${parsed - started} ms, laid out " +
                                        "${android.os.SystemClock.elapsedRealtime() - parsed} ms, " +
                                        "${laidOut.pages.size} pages",
                                )
                                Triple(opened.document, laidOut, file)
                            }
                            OfficeFormats.Opened.Pdf -> {
                                isPdf = true
                                null
                            }
                            is OfficeFormats.Opened.Unsupported -> {
                                unsupported = opened
                                null
                            }
                            OfficeFormats.Opened.Unreadable -> null
                        }
                    } catch (interrupted: InterruptedException) {
                        throw interrupted
                    } catch (failure: Throwable) {
                        null
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (interrupted: InterruptedException) {
                return@launch
            }
            if (isPdf) {
                openInPdfReader(located)
                return@launch
            }
            if (result == null) {
                binding.loadingOverlay.visibility = View.GONE
                showError(
                    unsupported?.let {
                        if (it.drm) {
                            getString(R.string.office_drm_protected, name, it.format)
                        } else {
                            getString(R.string.office_unsupported, name, it.format)
                        }
                    }
                        ?: getString(R.string.office_cannot_open, name),
                )
                return@launch
            }
            val parsed = result.first
            val laidOut = result.second
            document = parsed
            title = parsed.displayName
            binding.officeView.show(laidOut)
            invalidateOptionsMenu()
            // A wide sheet can be zoomed in further than a page; let the slider reach that far.
            binding.zoomSlider.valueTo = max(ZOOM_SLIDER_MAX_LOG2, log2(binding.officeView.maxZoomLevel))
            updateZoomLabel()
            binding.fastScroll.isUsable = laidOut.pages.size > 1

            listOfNotNull(parsed.limitationNote, laidOut.notice)
                .joinToString("\n")
                .ifEmpty { null }
                ?.let { note ->
                binding.limitationNote.text = note
                binding.limitationNote.visibility = View.VISIBLE
                binding.limitationNote.post { applyHeaderInset() }
            }

            binding.loadingOverlay.visibility = View.GONE
            updateOrientationIcon()
            onPageChanged(binding.officeView.currentPage)
            rememberAndRestore(result.third, laidOut.pages.size)
        }
    }

    /**
     * Put the reader back where they were, and record that the file was opened.
     *
     * The row is written before the position is read back so a file opened for the first time
     * still lands in the recent list, and so the id is available when the screen is left again.
     */
    private fun rememberAndRestore(local: java.io.File?, pageCount: Int) {
        val target = uri ?: return
        val name = document?.displayName ?: return
        lifecycleScope.launch {
            val database = ReaderDatabase.get(applicationContext)
            val existing = database.findDocument(target.toString())
            documentId = database.upsertDocument(
                uri = target.toString(),
                displayName = name,
                folder = local?.parent,
                sizeBytes = local?.length() ?: 0L,
                modifiedAt = local?.lastModified() ?: 0L,
                pageCount = pageCount,
                openedAt = System.currentTimeMillis(),
            )
            if (existing != null) {
                colorMode = ColorMode.entries.getOrElse(existing.colorMode) { ColorMode.NORMAL }
                binding.officeView.colorMode = colorMode
                if (existing.lastOpenedAt > 0) {
                    binding.officeView.restoreReadingPosition(
                        floatArrayOf(existing.lastPage.toFloat(), 0f, 0f, existing.lastZoom),
                    )
                }
            } else {
                colorMode = ColorMode.entries
                    .getOrElse(Settings.get(this@OfficeActivity).defaultColorMode) { ColorMode.NORMAL }
                binding.officeView.colorMode = colorMode
            }
            updateZoomLabel()
            onPageChanged(binding.officeView.currentPage)
            database.prune()
        }
    }

    /**
     * Write where the reader got to, and how they were reading it.
     *
     * Not on this screen's scope: the moment this matters most is when the screen is closing, and
     * a write tied to it would be cancelled before it reached the disk.
     */
    private fun savePosition() {
        if (documentId <= 0L) return
        val page = binding.officeView.currentPage
        val zoom = binding.officeView.zoomLevel
        val id = documentId
        val mode = colorMode.ordinal
        binding.officeView.removeCallbacks(savePositionRunnable)
        ReaderDatabase.writeInBackground {
            saveReadingPosition(
                documentId = id,
                page = page,
                fractionX = 0f,
                fractionY = 0f,
                zoom = zoom,
                viewMode = 0,
                colorMode = mode,
            )
        }
    }

    /**
     * Also written a couple of seconds after the reader settles on a page.
     *
     * Pausing is not the only way a screen ends: a phone can drop a background app without another
     * callback, and coming back to page one because Android reclaimed the process is still the app
     * having forgotten.
     */
    private val savePositionRunnable = Runnable { savePosition() }

    override fun onPause() {
        super.onPause()
        savePosition()
    }

    /**
     * The file turned out to be a PDF — sent as a generic attachment, or misnamed — so it goes to
     * the PDF reader, and this screen steps out of the way.
     *
     * A content URI travels as the intent's data so the read grant goes with it. A file URI goes
     * as an extra: one in the data trips Android's file-URI exposure check even between two
     * screens of the same app.
     */
    private fun openInPdfReader(target: Uri) {
        runCatching {
            startActivity(
                Intent(this, ReaderActivity::class.java).apply {
                    if (target.scheme == "content") data = target
                    putExtra(ReaderActivity.EXTRA_URI, target)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
            )
            finish()
        }.onFailure { showError(getString(R.string.error_open_failed)) }
    }

    /** Text shared on its own, written to a file so it opens like any other text document. */
    private fun sharedTextAsFile(): Uri? {
        if (intent.action != Intent.ACTION_SEND) return null
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: return null
        if (text.isBlank()) return null
        return runCatching {
            val directory = java.io.File(cacheDir, "shared").apply { mkdirs() }
            val file = java.io.File(directory, "Shared text.txt")
            file.writeText(text)
            Uri.fromFile(file)
        }.getOrNull()
    }

    private fun showError(message: String) {
        binding.errorText.text = message
        binding.errorView.visibility = View.VISIBLE
        binding.loadingOverlay.visibility = View.GONE
    }

    private fun takePersistablePermission(target: Uri) {
        if (target.scheme != "content") return
        runCatching {
            contentResolver.takePersistableUriPermission(target, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // ------------------------------------------------------------------ chrome

    private fun onPageChanged(page: Int) {
        val total = binding.officeView.pageCount
        if (total <= 0) return
        binding.pageIndicator.text = pageLabel(page, total)
        binding.fastScroll.progress = if (total > 1) page.toFloat() / (total - 1) else 0f
        updateScrollLabels(page, total)
        binding.fastScroll.flash()
        binding.officeView.removeCallbacks(savePositionRunnable)
        binding.officeView.postDelayed(savePositionRunnable, SAVE_POSITION_DELAY_MS)
        if (!chromeVisible) showPageChip(page, total)
    }

    /**
     * What the page pill says.
     *
     * A slide deck counts slides and a workbook names the sheet, because "page 4 of 9" tells the
     * reader of a spreadsheet nothing they wanted to know.
     */
    private fun pageLabel(page: Int, total: Int): String {
        val label = binding.officeView.pageLabel(page)
        val kindLabel = document?.kind
        return when (kindLabel) {
            top.teamaos.pdfreader.office.OfficeKind.SLIDES ->
                getString(R.string.slide_of, page + 1, total)
            top.teamaos.pdfreader.office.OfficeKind.SPREADSHEET ->
                label.ifEmpty { getString(R.string.page_of, page + 1, total) }
            else -> getString(R.string.page_of, page + 1, total)
        }
    }

    private fun updateScrollLabels(page: Int, total: Int) {
        val percent = if (total > 1) (page * 100) / (total - 1) else 100
        binding.scrollBubble.text = "${pageLabel(page, total)}  ·  ${getString(R.string.percent_read, percent)}"
    }

    private fun showPageChip(page: Int, total: Int) {
        binding.pageChip.text = pageLabel(page, total)
        binding.pageChip.visibility = View.VISIBLE
        binding.pageChip.removeCallbacks(hideChipRunnable)
        binding.pageChip.postDelayed(hideChipRunnable, PAGE_CHIP_MS)
    }

    private fun toggleChrome() {
        chromeVisible = !chromeVisible
        applyHeaderInset()
        val visibility = if (chromeVisible) View.VISIBLE else View.GONE
        binding.toolbar.visibility = if (searchOpen) View.GONE else visibility
        binding.bottomBarScroller.visibility =
            if (binding.textActionBar.visibility == View.VISIBLE) View.GONE else visibility
        if (!chromeVisible) binding.zoomSliderBar.visibility = View.GONE
    }

    private fun toggleZoomSlider() {
        if (binding.zoomSliderBar.visibility == View.VISIBLE) {
            binding.zoomSliderBar.visibility = View.GONE
            binding.zoomSliderBar.removeCallbacks(hideZoomSliderRunnable)
        } else {
            updateZoomLabel()
            revealZoomSlider()
        }
    }

    private fun revealZoomSlider() {
        binding.zoomSliderBar.visibility = View.VISIBLE
        binding.zoomSliderBar.removeCallbacks(hideZoomSliderRunnable)
        binding.zoomSliderBar.postDelayed(hideZoomSliderRunnable, ZOOM_SLIDER_MS)
    }

    private fun updateZoomLabel(reveal: Boolean = false) {
        val zoom = binding.officeView.zoomLevel
        binding.zoomPercent.text = getString(R.string.zoom_level, (zoom * 100).roundToInt())
        if (!adjustingZoomSlider) {
            settingSliderProgrammatically = true
            binding.zoomSlider.value = log2(zoom).coerceIn(
                binding.zoomSlider.valueFrom,
                binding.zoomSlider.valueTo,
            )
            settingSliderProgrammatically = false
        }
        if (reveal && binding.zoomSliderBar.visibility == View.VISIBLE) revealZoomSlider()
    }

    private fun applyKeepScreenOn() {
        val flag = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (Settings.get(this).keepScreenOn) window.addFlags(flag) else window.clearFlags(flag)
    }

    private fun toggleOrientation() {
        val landscapeNow = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        orientationMode =
            if (landscapeNow) ScreenOrientationMode.PORTRAIT else ScreenOrientationMode.LANDSCAPE
        applyOrientation()
        Toast.makeText(this, orientationLabel(), Toast.LENGTH_SHORT).show()
    }

    private fun followPhoneOrientation() {
        orientationMode = ScreenOrientationMode.FOLLOW_PHONE
        applyOrientation()
        Toast.makeText(this, orientationLabel(), Toast.LENGTH_SHORT).show()
    }

    private fun applyOrientation() {
        updateOrientationIcon()
        requestedOrientation = when (orientationMode) {
            ScreenOrientationMode.FOLLOW_PHONE -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ScreenOrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            ScreenOrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun updateOrientationIcon() {
        binding.orientationIcon.setImageResource(
            when (orientationMode) {
                ScreenOrientationMode.FOLLOW_PHONE -> R.drawable.ic_screen_auto
                ScreenOrientationMode.PORTRAIT -> R.drawable.ic_screen_portrait
                ScreenOrientationMode.LANDSCAPE -> R.drawable.ic_screen_landscape
            },
        )
    }

    private fun orientationLabel(): Int = when (orientationMode) {
        ScreenOrientationMode.FOLLOW_PHONE -> R.string.orientation_auto
        ScreenOrientationMode.PORTRAIT -> R.string.orientation_portrait
        ScreenOrientationMode.LANDSCAPE -> R.string.orientation_landscape
    }

    // ------------------------------------------------------------------ tools

    /** The contents list: headings for a document, sheets for a workbook, slides for a deck. */
    private fun showContents() {
        val active = document ?: return
        if (active.outline.isEmpty()) {
            Toast.makeText(this, R.string.no_outline, Toast.LENGTH_SHORT).show()
            return
        }
        val entries = active.outline.take(MAX_OUTLINE_ENTRIES)
        val labels = entries.map { entry ->
            "  ".repeat(entry.level.coerceIn(0, 4)) + entry.title
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_outline)
            .setItems(labels) { _, which ->
                val page = binding.officeView.firstPageOfSection(entries[which].sectionIndex)
                binding.officeView.goToPage(page)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptGoToPage() {
        val total = binding.officeView.pageCount
        if (total <= 0) return
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.page_of, 1, total)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_go_to_page)
            .setView(input)
            .setPositiveButton(R.string.go) { _, _ ->
                val page = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                binding.officeView.goToPage((page - 1).coerceIn(0, total - 1))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseColourMode() {
        val labels = arrayOf(
            getString(R.string.colour_normal),
            getString(R.string.colour_night),
            getString(R.string.colour_sepia),
            getString(R.string.colour_grayscale),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_colour_mode)
            .setSingleChoiceItems(labels, colorMode.ordinal) { dialog, which ->
                colorMode = ColorMode.entries[which]
                binding.officeView.colorMode = colorMode
                savePosition()
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Hand the file to a full Office app.
     *
     * This reader shows a document; it does not edit one. Offering the way out is more honest than
     * pretending the feature is missing, and it is the one thing a reader wants when they find a
     * file they need to change.
     */
    private fun openWithAnotherApp() {
        val target = uri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(target, contentResolver.getType(target) ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching {
            startActivity(Intent.createChooser(intent, getString(R.string.action_open_with)))
        }.onFailure {
            Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ text actions

    private fun onTextSelected(text: String) {
        selectedText = text
        binding.textActionBar.visibility = View.VISIBLE
        binding.bottomBarScroller.visibility = View.GONE
        binding.zoomSliderBar.visibility = View.GONE
    }

    private fun clearSelection() {
        selectedText = null
        binding.officeView.clearSelection()
        binding.textActionBar.visibility = View.GONE
        if (chromeVisible) binding.bottomBarScroller.visibility = View.VISIBLE
    }

    private fun copySelectedText() {
        val text = selectedText ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
        Toast.makeText(this, R.string.text_copied, Toast.LENGTH_SHORT).show()
        clearSelection()
    }

    private fun findSelectedText() {
        val text = selectedText ?: return
        clearSelection()
        openSearch()
        binding.searchInput.setText(text.take(MAX_SEARCH_FROM_SELECTION))
        inputMethodManager.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        runSearch(binding.searchInput.text.toString())
    }

    private fun translateSelectedText() {
        val text = selectedText ?: return
        val process = Intent(Intent.ACTION_PROCESS_TEXT).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        }
        val started = runCatching {
            startActivity(Intent.createChooser(process, getString(R.string.text_translate)))
            true
        }.getOrDefault(false)
        if (!started) {
            runCatching {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(
                            "https://translate.google.com/?sl=auto&tl=en&op=translate&text=" +
                                Uri.encode(text),
                        ),
                    ),
                )
            }.onFailure {
                Toast.makeText(this, R.string.text_no_translator, Toast.LENGTH_SHORT).show()
            }
        }
        clearSelection()
    }

    private fun shareSelectedText() {
        val text = selectedText ?: return
        runCatching {
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    },
                    getString(R.string.action_share),
                ),
            )
        }
        clearSelection()
    }

    // ------------------------------------------------------------------ search

    private val searchOpen: Boolean get() = binding.searchBar.visibility == View.VISIBLE

    private fun openSearch() {
        binding.searchBar.visibility = View.VISIBLE
        binding.toolbar.visibility = View.GONE
        binding.searchInput.setText("")
        binding.searchCount.text = ""
        binding.searchInput.requestFocus()
        inputMethodManager.showSoftInput(binding.searchInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeSearch() {
        searchJob?.cancel()
        searchJob = null
        matches.clear()
        matchIndex = -1
        binding.searchBar.visibility = View.GONE
        binding.toolbar.visibility = if (chromeVisible) View.VISIBLE else View.GONE
        binding.officeView.clearMatches()
        inputMethodManager.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    /**
     * Search the measured text.
     *
     * Everything is already laid out, so a search is a pass over the paragraphs that exist rather
     * than a second read of the file — which is why this can afford to be exact about where a match
     * is and highlight it in place.
     */
    private fun runSearch(query: String) {
        val needle = query.trim()
        if (needle.isEmpty()) return
        // A new query replaces the one still running rather than racing it to the screen.
        searchJob?.cancel()
        matches.clear()
        matchIndex = -1
        binding.officeView.clearMatches()
        binding.searchCount.text = ""
        searchJob = lifecycleScope.launch {
            // A pass over every paragraph of a long document is long enough to freeze the screen,
            // so it runs in the background. The layouts are finished and never change, which is
            // what makes reading them from another thread safe.
            val found = withContext(Dispatchers.Default) {
                val job = coroutineContext[Job]
                val hits = binding.officeView.findMatches(needle, MAX_MATCHES) { job?.isActive == false }
                ensureActive()
                hits
            }
            matches.clear()
            matches += found
            showSearchResult()
        }
    }

    private fun showSearchResult() {
        if (matches.isEmpty()) {
            binding.searchCount.text = getString(R.string.no_matches)
            binding.officeView.clearMatches()
            return
        }
        matchIndex = 0
        showMatch()
    }

    private fun stepSearch(direction: Int) {
        if (matches.isEmpty()) return
        matchIndex = (matchIndex + direction + matches.size) % matches.size
        showMatch()
    }

    private fun showMatch() {
        val (page, bounds) = matches[matchIndex]
        binding.officeView.setMatches(matches, matchIndex)
        binding.officeView.scrollToMatch(page, bounds)
        binding.searchCount.text = getString(R.string.match_of, matchIndex + 1, matches.size)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.office, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_slideshow)?.isVisible =
            document?.kind == top.teamaos.pdfreader.office.OfficeKind.SLIDES
        return super.onPrepareOptionsMenu(menu)
    }

    // ------------------------------------------------------------------ slide show

    /** What the slide show hid, and how each one was, so leaving it puts the screen back exactly. */
    private var hiddenForSlideShow: List<Pair<View, Int>> = emptyList()

    /**
     * Present the deck: sideways, full screen, no bars, one slide at a time from the one being
     * looked at. Tapping or swiping moves between slides; Back ends it on the slide reached.
     */
    private fun startSlideShow() {
        if (binding.officeView.pageCount == 0 || binding.officeView.isSlideShow) return
        if (searchOpen) closeSearch()
        if (binding.textActionBar.visibility == View.VISIBLE) clearSelection()
        hiddenForSlideShow = listOf(
            binding.toolbar,
            binding.bottomBarScroller,
            binding.zoomSliderBar,
            binding.limitationNote,
            binding.fastScroll,
            binding.pageChip,
        ).map { it to it.visibility }
        hiddenForSlideShow.forEach { (view, _) -> view.visibility = View.GONE }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        binding.officeView.startSlideShow(binding.officeView.currentPage)
        Toast.makeText(this, R.string.slideshow_hint, Toast.LENGTH_SHORT).show()
    }

    private fun stopSlideShow() {
        if (!binding.officeView.isSlideShow) return
        binding.officeView.stopSlideShow()
        WindowCompat.getInsetsController(window, window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
        hiddenForSlideShow.forEach { (view, visibility) -> view.visibility = visibility }
        hiddenForSlideShow = emptyList()
        applyHeaderInset()
        applyKeepScreenOn()
        applyOrientation()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_slideshow -> {
            startSlideShow()
            true
        }
        R.id.action_search -> {
            openSearch()
            true
        }
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }
        R.id.action_update -> {
            Updater.checkAndOffer(this, lifecycleScope)
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    companion object {
        const val EXTRA_URI = "top.teamaos.pdfreader.OFFICE_URI"
        private const val TAG = "OfficeActivity"

        /** The slider's top end for page-shaped documents: 2^3, so 800 %. */
        private const val ZOOM_SLIDER_MAX_LOG2 = 3f

        /** How long after the last page change the position is written. */
        private const val SAVE_POSITION_DELAY_MS = 2500L

        private const val PAGE_CHIP_MS = 900L
        private const val ZOOM_SLIDER_MS = 4000L
        private const val MAX_MATCHES = 2000
        private const val MAX_OUTLINE_ENTRIES = 400
        private const val MAX_SEARCH_FROM_SELECTION = 60

    }
}
