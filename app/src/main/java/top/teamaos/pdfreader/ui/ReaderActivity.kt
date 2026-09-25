package top.teamaos.pdfreader.ui

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.marginBottom
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import top.teamaos.pdfreader.R
import top.teamaos.pdfreader.core.DocumentController
import top.teamaos.pdfreader.core.DocumentTabs
import top.teamaos.pdfreader.core.PdfNative
import top.teamaos.pdfreader.core.PdfPasswordException
import top.teamaos.pdfreader.core.PdfLinkTarget
import top.teamaos.pdfreader.core.PdfSearch
import top.teamaos.pdfreader.core.Stroke
import top.teamaos.pdfreader.core.PdfTextSelector
import top.teamaos.pdfreader.core.TextSelection
import top.teamaos.pdfreader.core.RenderQuality
import top.teamaos.pdfreader.core.SearchHit
import top.teamaos.pdfreader.core.ViewMode
import top.teamaos.pdfreader.data.ReaderDatabase
import top.teamaos.pdfreader.data.ScreenOrientationMode
import top.teamaos.pdfreader.data.Settings
import top.teamaos.pdfreader.databinding.ActivityReaderBinding
import top.teamaos.pdfreader.view.ColorMode

/**
 * The reading screen.
 *
 * Deliberately thin: everything that decides *what* to draw lives in [top.teamaos.pdfreader.view.PdfView]
 * and the core package. This class opens the document, keeps the chrome in sync with the page, and
 * saves the reading position.
 */
class ReaderActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReaderBinding

    private var controller: DocumentController? = null
    private var bookmarkedPages = mutableSetOf<Int>()
    private var chromeVisible = true
    private var colorMode = ColorMode.NORMAL
    private var tabCountLabel: TextView? = null

    private var pdfSearch: PdfSearch? = null
    private var searchJob: Job? = null
    private val searchHits = mutableListOf<SearchHit>()
    private var searchIndex = -1

    private val inputMethodManager by lazy {
        getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }

    /** Opening a second document from the tab sheet keeps the reader on screen. */
    private val pickDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            takePersistablePermission(it)
            openDocument(located(it), password = null)
        }
    }

    private val hideChipRunnable = Runnable { binding.pageChip.visibility = View.GONE }
    private val hideZoomSliderRunnable = Runnable { binding.zoomSliderBar.visibility = View.GONE }

    private var adjustingZoomSlider = false
    private var settingSliderProgrammatically = false

    /**
     * This screen's orientation. Starts from the settings every time a document is opened; the
     * screen button changes only this, so turning one document sideways is not remembered.
     */
    private lateinit var orientationMode: ScreenOrientationMode

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(true)
        binding.toolbar.setNavigationOnClickListener { confirmClose() }

        applyWindowInsets()
        BarSizing.attach(binding.bottomScroll, binding.bottomBar)
        wireReaderCallbacks()
        orientationMode = Settings.get(this).orientationMode
        applyOrientation()
        applyKeepScreenOn()

        val uri = intent.data ?: intent.getParcelableExtra<Uri>(EXTRA_URI)
        if (uri == null) {
            showError(getString(R.string.error_open_failed))
            return
        }
        takePersistablePermission(uri)
        openDocument(located(uri), password = null)
    }

    /**
     * The document by its real path where it has one. A link lent by another app stops working
     * once that app takes its grant back, and the history entry with it; a path keeps opening, and
     * opens faster, with nothing between pdfium and the file.
     */
    private fun located(uri: Uri): Uri = StorageAccess.resolveFile(this, uri)?.let(Uri::fromFile) ?: uri

    private fun applyWindowInsets() {
        val barMargin = binding.bottomBarScroller.marginBottom
        val scrollMargin = binding.fastScroll.marginBottom
        // The toolbar has not been measured when insets first arrive, so its height comes from the
        // theme. Without this the drag handle starts underneath the title bar.
        val toolbarHeight = TypedValue().let { value ->
            if (theme.resolveAttribute(android.R.attr.actionBarSize, value, true)) {
                TypedValue.complexToDimensionPixelSize(value.data, resources.displayMetrics)
            } else {
                0
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // The header runs right to the top edge, with its own background behind the status bar,
            // rather than starting below it — otherwise page content shows through the strip above
            // it and the header looks detached.
            binding.toolbar.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = 0
                height = toolbarHeight + bars.top
            }
            binding.toolbar.updatePadding(top = bars.top)
            binding.searchBar.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = 0
                height = toolbarHeight + bars.top
            }
            binding.searchBar.updatePadding(top = bars.top)
            binding.bottomBarScroller.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = barMargin + bars.bottom
            }
            // The handle runs from just under the title bar to just above the floating toolbar and
            // the phone's navigation bar, so it never overlaps either.
            binding.fastScroll.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = bars.top + toolbarHeight
                bottomMargin = scrollMargin + bars.bottom
            }
            headerHeight = toolbarHeight + bars.top
            applyHeaderInset()
            insets
        }
    }

    /** Height of the title bar including the status bar, once the insets have arrived. */
    private var headerHeight = 0

    /**
     * Start the page under the title bar, not behind it.
     *
     * With the chrome up, the first line of a document was hidden by the header. The page now
     * begins where the header ends, and takes the whole screen the moment the chrome is tapped
     * away — which is what full screen is for.
     */
    private fun applyHeaderInset() {
        val wanted = if (chromeVisible && !searchOpen) headerHeight else 0
        val current = (binding.pdfView.layoutParams as? FrameLayout.LayoutParams)?.topMargin
        if (current == wanted) return
        binding.pdfView.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = wanted }
    }

    private fun wireReaderCallbacks() {
        binding.pdfView.onSingleTap = { toggleChrome() }
        binding.pdfView.onPageChanged = { page -> onPageChanged(page) }
        binding.pdfView.onScaleChanged = { updateZoomLabel(reveal = true) }

        binding.zoomButton.setOnClickListener { toggleZoomSlider() }
        binding.zoomInButton.setOnClickListener { binding.pdfView.zoomIn() }
        binding.zoomOutButton.setOnClickListener { binding.pdfView.zoomOut() }
        binding.zoomResetButton.setOnClickListener { binding.pdfView.resetZoom() }
        // Holding Fit gives the other useful fit: the page as wide as the screen.
        binding.zoomResetButton.setOnLongClickListener {
            binding.pdfView.fitWidth()
            true
        }
        binding.zoomSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || settingSliderProgrammatically) return@addOnChangeListener
            adjustingZoomSlider = true
            binding.pdfView.setZoomLevel(2f.pow(value))
            adjustingZoomSlider = false
            binding.zoomPercent.text =
                getString(R.string.zoom_level, (binding.pdfView.zoom * 100).roundToInt())
            revealZoomSlider()
        }

        binding.unlockButton.setOnClickListener { setControlsLocked(false) }
        binding.lockButton.setOnClickListener { setControlsLocked(true) }
        binding.orientationButton.setOnClickListener { toggleOrientation() }
        binding.orientationButton.setOnLongClickListener {
            followPhoneOrientation()
            true
        }
        binding.toolContentsButton.setOnClickListener { showOutline() }
        binding.toolThumbnailsButton.setOnClickListener { showThumbnails() }
        binding.toolEditButton.setOnClickListener { enterEditMode(binding.pdfView.currentPage) }
        binding.toolColourButton.setOnClickListener { chooseColourMode() }
        binding.viewModeButton.setOnClickListener { chooseViewMode() }
        // The page pill is the quickest route to both "go somewhere" and "remember this".
        binding.pageIndicator.setOnClickListener { promptGoToPage() }
        binding.pageIndicator.setOnLongClickListener {
            toggleBookmark()
            true
        }

        binding.pdfView.onTextLongPress = { page, x, y -> selectWordAt(page, x, y) }
        binding.pdfView.onTapLink = { page, x, y -> followLinkAt(page, x, y) }
        binding.pdfView.onTextHandleDragged = { page, isStart, x, y -> dragSelection(page, isStart, x, y) }
        binding.pdfView.onTextHandleReleased = { showTextActions() }
        binding.pdfView.onTextSelectionCleared = { hideTextActions() }
        binding.textCopyButton.setOnClickListener { copySelectedText() }
        binding.textSelectAllButton.setOnClickListener { selectWholePage() }
        binding.textFindButton.setOnClickListener { findSelectedText() }
        binding.textTranslateButton.setOnClickListener { translateSelectedText() }
        binding.textShareButton.setOnClickListener { shareSelectedText() }
        binding.pdfView.onSelectionChanged = { selected -> updateSelectionCount(selected) }
        binding.rotateLeftButton.setOnClickListener { rotateSelection(-1) }
        binding.rotateRightButton.setOnClickListener { rotateSelection(1) }
        // One button both ways: with every page already picked, it clears the selection instead.
        binding.selectAllButton.setOnClickListener {
            if (allPagesSelected()) binding.pdfView.clearSelection() else binding.pdfView.selectAllPages()
        }
        binding.extractButton.setOnClickListener { extractSelection() }
        binding.cropButton.setOnClickListener { startCrop() }
        binding.splitButton.setOnClickListener { promptSplit() }
        binding.drawButton.setOnClickListener { enterDrawMode() }
        // Undo lives on both bars. Cropping and rotating happen here, and having to open the
        // brush to take one back off would be a silly journey.
        binding.editUndoButton.setOnClickListener { undo() }
        binding.editRedoButton.setOnClickListener { redo() }
        binding.moveButton.setOnClickListener { selectTool(DrawTool.MOVE) }
        binding.brushButton.setOnClickListener { selectTool(DrawTool.PEN) }
        binding.eraserButton.setOnClickListener { selectTool(DrawTool.ERASER) }
        binding.undoButton.setOnClickListener { undo() }
        binding.redoButton.setOnClickListener { redo() }
        binding.clearMarksButton.setOnClickListener { promptClearMarks() }
        binding.burnMarksButton.setOnClickListener { promptBurnMarks() }
        binding.drawDoneButton.setOnClickListener { exitDrawMode() }
        binding.drawCloseButton.setOnClickListener { exitDrawMode() }
        binding.brushSizeSlider.addOnChangeListener { _, value, _ ->
            binding.pdfView.brushWidthPts = value
            binding.brushSizeLabel.text = getString(R.string.brush_size, value.toInt())
        }
        binding.pdfView.onStrokeDrawn = { page, points -> addStroke(page, points) }
        binding.pdfView.onStrokesErased = { strokes -> eraseStrokes(strokes) }
        binding.cropCancelButton.setOnClickListener { finishCrop() }
        binding.cropResetButton.setOnClickListener { binding.cropOverlay.reset() }
        binding.cropApplyButton.setOnClickListener { applyCrop() }
        binding.editCancelButton.setOnClickListener { exitEditMode() }
        binding.editDoneButton.setOnClickListener { exitEditMode() }

        binding.searchCloseButton.setOnClickListener { closeSearch() }
        binding.searchPrevButton.setOnClickListener { stepSearch(-1) }
        binding.searchNextButton.setOnClickListener { stepSearch(1) }
        binding.searchInput.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val query = view.text.toString()
                if (query.isNotBlank()) {
                    inputMethodManager.hideSoftInputFromWindow(view.windowToken, 0)
                    startSearch(query)
                }
                true
            } else {
                false
            }
        }

        onBackPressedDispatcher.addCallback(this) {
            when {
                cropping -> finishCrop()
                drawing -> exitDrawMode()
                binding.pdfView.hasTextSelection -> clearTextSelection()
                searchOpen -> closeSearch()
                editing -> exitEditMode()
                else -> confirmClose()
            }
        }

        binding.fastScroll.onSeek = { fraction, settled ->
            val total = binding.pdfView.pageCount
            if (total > 0) {
                val page = (fraction * (total - 1)).toInt().coerceIn(0, total - 1)
                updateScrollLabels(page, total)
                // Mid-drag the cheap seek keeps the handle at the speed of the finger; the sharp
                // render is worth doing once, when the handle is let go.
                if (settled) {
                    binding.pdfView.goToPage(page)
                    showPageChip(page, total)
                } else {
                    binding.pdfView.seekToPage(page)
                }
            }
        }
        // The bubble follows the handle rather than being drawn by it, which keeps the handle a
        // thin strip instead of a full-screen view.
        binding.fastScroll.onDragStateChanged = { dragging, handleCentreY ->
            binding.scrollBubble.visibility = if (dragging) View.VISIBLE else View.GONE
            if (dragging) {
                val top = binding.fastScroll.top + handleCentreY - binding.scrollBubble.height / 2f
                binding.scrollBubble.translationY = top.coerceAtLeast(0f)
            }
        }
    }

    private fun openDocument(uri: Uri, password: String?) {
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.errorView.visibility = View.GONE

        lifecycleScope.launch {
            // Remember where the outgoing tab was before anything replaces it.
            controller?.let { it.lastPosition = binding.pdfView.readingPosition() }

            val newController = try {
                DocumentTabs.openOrSwitchTo(applicationContext, uri, password)
            } catch (e: PdfPasswordException) {
                binding.loadingOverlay.visibility = View.GONE
                promptForPassword(uri)
                return@launch
            } catch (e: Exception) {
                Log.w("RapidPDF", "Could not open $uri", e)
                binding.loadingOverlay.visibility = View.GONE
                showError("${getString(R.string.error_open_failed)}\n\n${e.javaClass.simpleName}: ${e.message}")
                return@launch
            }

            showController(newController)
        }
    }

    /** Put [newController] on screen. Shared by first open and by switching tabs. */
    private fun showController(newController: DocumentController) {
        lifecycleScope.launch {
            controller?.takeIf { it !== newController }?.let {
                it.lastPosition = binding.pdfView.readingPosition()
                it.onInvalidate = null
                it.onGeometryChanged = null
            }
            controller = newController
            newController.renderer.quality = RenderQuality.entries
                .getOrElse(Settings.get(this@ReaderActivity).renderQuality) { RenderQuality.BALANCED }
            newController.onInvalidate = { binding.pdfView.invalidate() }
            newController.onGeometryChanged = { binding.pdfView.onGeometryUpdated() }

            // Through the activity, not the toolbar: a tab that is already open gets here during
            // onCreate, and AppCompat then re-applies the activity title — the app name — over any
            // title set on the toolbar directly.
            title = newController.displayName.ifBlank { getString(R.string.app_name) }
            binding.pdfView.attach(
                newController.geometry,
                newController.renderer,
                newController.cache,
                newController.measurer,
            )
            restorePreferences(newController)
            clearTextSelection()
            exitDrawMode()
            linksByPage.clear()
            loadMarks(newController)
            textSelector = PdfTextSelector(newController.session)
            binding.fastScroll.isUsable = newController.pageCount > 1
            binding.loadingOverlay.visibility = View.GONE
            updateZoomLabel()
            updateViewModeIcon()
            onPageChanged(binding.pdfView.currentPage)

            loadBookmarks(newController)
            ReaderDatabase.get(applicationContext).prune()
        }
    }

    /**
     * Reopen exactly where and how this document was last read. A tab switched away from and back
     * to uses its in-memory position, which is newer than anything written to the database.
     */
    private fun restorePreferences(active: DocumentController) {
        val record = active.record
        if (record == null || record.lastOpenedAt <= 0L) {
            colorMode = ColorMode.entries.getOrElse(Settings.get(this).defaultColorMode) { ColorMode.NORMAL }
            binding.pdfView.colorMode = colorMode
        }
        if (record != null) {
            colorMode = ColorMode.entries.getOrElse(record.colorMode) { ColorMode.NORMAL }
            binding.pdfView.colorMode = colorMode
            binding.pdfView.viewMode = ViewMode.entries.getOrElse(record.viewMode) { ViewMode.VERTICAL }
        }
        // A phone held sideways shows a spread, unless that was turned off.
        if (record == null || record.lastOpenedAt <= 0L) {
            val landscape = resources.configuration.orientation ==
                android.content.res.Configuration.ORIENTATION_LANDSCAPE
            if (landscape && Settings.get(this).dualPageInLandscape) {
                binding.pdfView.viewMode = ViewMode.DUAL
            }
        }
        val position = active.lastPosition ?: record?.takeIf { it.lastOpenedAt > 0 }?.let {
            floatArrayOf(it.lastPage.toFloat(), it.lastFractionX, it.lastFractionY, it.lastZoom)
        }
        position?.let { binding.pdfView.restoreReadingPosition(it) }
    }

    private fun loadBookmarks(active: DocumentController) {
        lifecycleScope.launch {
            val marks = ReaderDatabase.get(applicationContext).bookmarks(active.documentId)
            bookmarkedPages = marks.map { it.page }.toMutableSet()
            invalidateOptionsMenu()
        }
    }

    private fun promptForPassword(uri: Uri) {
        val input = EditText(this).apply {
            hint = getString(R.string.password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.error_password)
            .setView(input)
            .setPositiveButton(R.string.unlock) { _, _ -> openDocument(uri, input.text.toString()) }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    private fun onPageChanged(page: Int) {
        val total = binding.pdfView.pageCount
        if (total <= 0) return
        binding.pageIndicator.text = getString(R.string.page_of, page + 1, total)
        binding.fastScroll.progress = if (total > 1) page.toFloat() / (total - 1) else 0f
        updateScrollLabels(page, total)
        binding.fastScroll.flash()
        prefetchLinks(page)
        binding.pdfView.removeCallbacks(savePositionRunnable)
        binding.pdfView.postDelayed(savePositionRunnable, SAVE_POSITION_DELAY_MS)
        if (!chromeVisible) showPageChip(page, total)
        // Only rebuild the menu when the bookmark icon would actually change. This fires on every
        // page crossed while flinging, and invalidateOptionsMenu is not cheap.
        val bookmarked = page in bookmarkedPages
        if (bookmarked != bookmarkIconShown) {
            bookmarkIconShown = bookmarked
            invalidateOptionsMenu()
        }
    }

    /** What the bookmark menu icon is currently showing, to avoid pointless menu rebuilds. */
    private var bookmarkIconShown = false

    /** Keep the handle bubble and the bottom bar telling the same story. */
    private fun updateScrollLabels(page: Int, total: Int) {
        val percent = if (total > 1) (page * 100) / (total - 1) else 100
        binding.scrollBubble.text =
            "${getString(R.string.page_of, page + 1, total)}  ·  ${getString(R.string.percent_read, percent)}"
    }

    /** Brief page-number flash when the chrome is hidden, so you always know where you are. */
    private fun showPageChip(page: Int, total: Int) {
        binding.pageChip.text = getString(R.string.page_of, page + 1, total)
        binding.pageChip.visibility = View.VISIBLE
        binding.pageChip.removeCallbacks(hideChipRunnable)
        binding.pageChip.postDelayed(hideChipRunnable, PAGE_CHIP_MS)
    }

    private fun toggleChrome() {
        if (binding.pdfView.interactionLocked) return
        chromeVisible = !chromeVisible
        applyHeaderInset()
        val visibility = if (chromeVisible) View.VISIBLE else View.GONE
        binding.toolbar.visibility = if (searchOpen) View.GONE else visibility
        binding.bottomBarScroller.visibility = visibility
        if (!chromeVisible) binding.zoomSliderBar.visibility = View.GONE
    }

    /**
     * Lock the controls for one-handed reading.
     *
     * Everything that moves the page stays live — scrolling, flinging, the drag handle, pinch and
     * double-tap zoom — while taps and long presses stop reaching the reader, so a resting thumb
     * cannot keep summoning toolbars. The small padlock in the corner is the only way back.
     */
    private fun setControlsLocked(locked: Boolean) {
        binding.pdfView.interactionLocked = locked
        binding.unlockButton.visibility = if (locked) View.VISIBLE else View.GONE
        if (locked) {
            chromeVisible = false
            binding.toolbar.visibility = View.GONE
            binding.bottomBarScroller.visibility = View.GONE
            binding.zoomSliderBar.visibility = View.GONE
            binding.editBar.visibility = View.GONE
            Toast.makeText(this, R.string.locked_hint, Toast.LENGTH_SHORT).show()
        } else {
            chromeVisible = true
            binding.toolbar.visibility = if (searchOpen) View.GONE else View.VISIBLE
            binding.bottomBarScroller.visibility = View.VISIBLE
        }
    }

    /**
     * Turn the screen, and lock it that way.
     *
     * The old version stepped through follow-phone, portrait, landscape in order, which meant the
     * first press on a phone already held upright moved from "follow phone" to "locked portrait" —
     * nothing visibly happened, and it took two presses to actually turn the page sideways. This
     * asks what is on screen *now* and goes to the other one, so one press always turns it.
     *
     * Holding the button gives the phone control back; see [wireReaderCallbacks].
     */
    private fun toggleOrientation() {
        val landscapeNow = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        orientationMode =
            if (landscapeNow) ScreenOrientationMode.PORTRAIT else ScreenOrientationMode.LANDSCAPE
        applyOrientation()
        Toast.makeText(this, orientationLabel(), Toast.LENGTH_SHORT).show()
    }

    /** Hand the screen back to the phone's own rotation. Reached by holding the screen button. */
    private fun followPhoneOrientation() {
        orientationMode = ScreenOrientationMode.FOLLOW_PHONE
        applyOrientation()
        Toast.makeText(this, orientationLabel(), Toast.LENGTH_SHORT).show()
    }

    /** The bar icon is the state readout: you can see which of the three modes is on. */
    private fun updateOrientationIcon() {
        binding.orientationIcon.setImageResource(
            when (orientationMode) {
                ScreenOrientationMode.FOLLOW_PHONE -> R.drawable.ic_screen_auto
                ScreenOrientationMode.PORTRAIT -> R.drawable.ic_screen_portrait
                ScreenOrientationMode.LANDSCAPE -> R.drawable.ic_screen_landscape
            }
        )
    }

    /** Opt-in, because keeping the screen awake is the single biggest drain on the battery. */
    private fun applyKeepScreenOn() {
        val flag = android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (Settings.get(this).keepScreenOn) window.addFlags(flag) else window.clearFlags(flag)
    }

    private fun applyOrientation() {
        updateOrientationIcon()
        requestedOrientation = when (orientationMode) {
            ScreenOrientationMode.FOLLOW_PHONE -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ScreenOrientationMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            ScreenOrientationMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun orientationLabel(): Int = when (orientationMode) {
        ScreenOrientationMode.FOLLOW_PHONE -> R.string.orientation_auto
        ScreenOrientationMode.PORTRAIT -> R.string.orientation_portrait
        ScreenOrientationMode.LANDSCAPE -> R.string.orientation_landscape
    }

    /** Tap flips between normal and night; long-press opens the full set. */
    private fun toggleNightMode() {
        colorMode = if (colorMode == ColorMode.NIGHT) ColorMode.NORMAL else ColorMode.NIGHT
        binding.pdfView.colorMode = colorMode
    }

    /** [reveal] only when the reader actually changed the zoom; not when a document opens. */
    private fun updateZoomLabel(reveal: Boolean = false) {
        val zoom = binding.pdfView.zoom
        binding.zoomInButton.isEnabled = binding.pdfView.canZoomIn
        binding.zoomOutButton.isEnabled = binding.pdfView.canZoomOut
        binding.zoomInButton.alpha = if (binding.pdfView.canZoomIn) 1f else 0.4f
        binding.zoomOutButton.alpha = if (binding.pdfView.canZoomOut) 1f else 0.4f
        binding.zoomPercent.text = getString(R.string.zoom_level, (zoom * 100).roundToInt())

        // Keep the slider in step with pinch gestures, without it echoing back into the zoom.
        if (!adjustingZoomSlider) {
            settingSliderProgrammatically = true
            binding.zoomSlider.value = log2(zoom).coerceIn(
                binding.zoomSlider.valueFrom,
                binding.zoomSlider.valueTo,
            )
            settingSliderProgrammatically = false
        }
        // A pinch only zooms. The slider bar opens from the Zoom button alone; while it is already
        // open, zooming by hand just keeps it from timing out underneath the fingers.
        if (reveal && binding.zoomSliderBar.visibility == View.VISIBLE) revealZoomSlider()
    }

    private fun toggleZoomSlider() {
        if (binding.zoomSliderBar.visibility == View.VISIBLE) {
            binding.zoomSliderBar.removeCallbacks(hideZoomSliderRunnable)
            binding.zoomSliderBar.visibility = View.GONE
        } else {
            updateZoomLabel()
            revealZoomSlider()
        }
    }

    /** The zoom slider earns its space only while zooming, then gets out of the way. */
    private fun revealZoomSlider() {
        if (!chromeVisible) return
        binding.zoomSliderBar.visibility = View.VISIBLE
        binding.zoomSliderBar.removeCallbacks(hideZoomSliderRunnable)
        binding.zoomSliderBar.postDelayed(hideZoomSliderRunnable, ZOOM_SLIDER_MS)
    }

    private fun flashChip(text: String) {
        binding.pageChip.text = text
        binding.pageChip.visibility = View.VISIBLE
        binding.pageChip.removeCallbacks(hideChipRunnable)
        binding.pageChip.postDelayed(hideChipRunnable, PAGE_CHIP_MS)
    }

    /** Keep the toolbar icon showing which view mode is actually active. */
    private fun updateViewModeIcon() {
        binding.viewModeIcon.setImageResource(
            when (binding.pdfView.viewMode) {
                ViewMode.VERTICAL -> R.drawable.ic_view_day
                ViewMode.HORIZONTAL -> R.drawable.ic_menu_book
                ViewMode.DUAL -> R.drawable.ic_view_column
            },
        )
    }

    private fun showError(message: String) {
        binding.loadingOverlay.visibility = View.GONE
        binding.errorView.text = message
        binding.errorView.visibility = View.VISIBLE
    }

    // ------------------------------------------------------------- page editing

    /**
     * Page editing, reached by holding a page down or from the toolbar.
     *
     * Rotation happens immediately in the open document so you can see the result, but nothing
     * touches the file until Save is pressed.
     */
    private fun enterEditMode(startPage: Int?) {
        if (!PdfNative.isAvailable) {
            Toast.makeText(this, R.string.rotation_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        binding.pdfView.selectionMode = true
        startPage?.let { binding.pdfView.toggleSelection(it) }
        binding.editBar.visibility = View.VISIBLE
        binding.bottomBarScroller.visibility = View.GONE
        binding.zoomSliderBar.visibility = View.GONE
        updateSelectionCount(binding.pdfView.selectedPages)
        updateUndoButtons()
    }

    private fun exitEditMode() {
        binding.pdfView.selectionMode = false
        binding.editBar.visibility = View.GONE
        binding.bottomBarScroller.visibility = if (chromeVisible) View.VISIBLE else View.GONE
    }

    private val editing: Boolean get() = binding.editBar.visibility == View.VISIBLE

    private fun updateSelectionCount(selected: Set<Int>) {
        binding.selectionCount.text = if (selected.isEmpty()) {
            getString(R.string.select_pages_hint)
        } else {
            getString(R.string.selected_count, selected.size)
        }
        val enabled = selected.isNotEmpty()
        listOf(
            binding.rotateLeftButton,
            binding.rotateRightButton,
            binding.cropButton,
            binding.splitButton,
            binding.extractButton,
        ).forEach {
            it.isEnabled = enabled
            it.alpha = if (enabled) 1f else 0.4f
        }
        val all = allPagesSelected()
        binding.selectAllLabel.setText(if (all) R.string.label_select_none else R.string.label_select_all)
        binding.selectAllButton.contentDescription =
            getString(if (all) R.string.action_select_none else R.string.action_select_all)
    }

    private fun allPagesSelected(): Boolean {
        val total = binding.pdfView.pageCount
        return total > 0 && binding.pdfView.selectedPages.size >= total
    }

    /**
     * Build a new PDF from the selected pages, then offer to share it or keep it.
     *
     * The extracted file is written to the cache first, so "share now" costs nothing extra and
     * nothing is left behind on storage unless the reader asks to keep it.
     */
    private fun extractSelection() {
        val active = controller ?: return
        val pages = binding.pdfView.selectedPages.toList()
        if (pages.isEmpty()) return
        if (!PdfNative.canExtractPages) {
            Toast.makeText(this, R.string.extract_failed, Toast.LENGTH_SHORT).show()
            return
        }
        binding.selectionCount.setText(R.string.extract_working)
        // Extraction reads the document from disk, so anything rotated but unsaved has to be
        // written out first or the new PDF would come out with the old orientation.
        // saveChanges is asynchronous, so the extraction waits for it rather than reading a file
        // that is being rewritten underneath it.
        saveChanges { extractPagesNow(active, pages) }
    }

    private fun extractPagesNow(active: DocumentController, pages: List<Int>) {
        active.extractPages(pages) { file ->
            updateSelectionCount(binding.pdfView.selectedPages)
            if (file == null) {
                Toast.makeText(this, R.string.extract_failed, Toast.LENGTH_LONG).show()
                return@extractPages
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.extract_title, pages.size))
                .setItems(
                    arrayOf(getString(R.string.extract_share), getString(R.string.extract_save)),
                ) { _, which ->
                    if (which == 0) {
                        Sharing.share(this, Uri.fromFile(file), file.name)
                    } else {
                        copyToDownloads(file)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    // ------------------------------------------------------------- brush and undo

    /**
     * A step the reader can take back.
     *
     * One history for everything that changes the document, not one per tool. A reader who crops a
     * page, draws over it and then presses Undo twice expects the drawing and then the crop to go,
     * in that order — which only works if both went onto the same stack.
     */
    private sealed class EditStep {
        class AddStroke(val stroke: Stroke) : EditStep()
        class RemoveStrokes(val strokes: List<Stroke>) : EditStep()
        class Crop(
            val previousBoxes: Map<Int, FloatArray>,
            val pages: List<Int>,
            val fractions: RectF,
        ) : EditStep()

        class Rotate(val pages: List<Int>, val quarterTurns: Int) : EditStep()
    }

    private val undoStack = ArrayDeque<EditStep>()
    private val redoStack = ArrayDeque<EditStep>()

    /** Strokes on screen, by page. The database holds the same thing; this is what gets drawn. */
    private val marksByPage = mutableMapOf<Int, MutableList<Stroke>>()

    /** Ids for strokes that have not been given a row yet; always negative, never reused. */
    private var provisionalId = -1L

    private fun pushUndo(step: EditStep) {
        undoStack.addLast(step)
        while (undoStack.size > MAX_UNDO_STEPS) undoStack.removeFirst()
        redoStack.clear()
        updateUndoButtons()
    }

    private fun updateUndoButtons() {
        val canUndo = undoStack.isNotEmpty()
        val canRedo = redoStack.isNotEmpty()
        listOf(
            binding.undoButton to canUndo,
            binding.editUndoButton to canUndo,
            binding.redoButton to canRedo,
            binding.editRedoButton to canRedo,
        ).forEach { (button, enabled) ->
            button.isEnabled = enabled
            button.alpha = if (enabled) 1f else 0.35f
        }
    }

    private fun enterDrawMode() {
        if (drawing) return
        exitEditMode()
        binding.pdfView.drawMode = true
        binding.pdfView.brushColour = brushColour
        binding.pdfView.brushWidthPts = binding.brushSizeSlider.value
        binding.brushSizeLabel.text =
            getString(R.string.brush_size, binding.brushSizeSlider.value.toInt())
        binding.drawBar.visibility = View.VISIBLE
        binding.bottomBarScroller.visibility = View.GONE
        binding.zoomSliderBar.visibility = View.GONE
        buildColourRow()
        selectTool(DrawTool.PEN)
        updateUndoButtons()
        Toast.makeText(this, R.string.draw_hint, Toast.LENGTH_SHORT).show()
    }

    private fun exitDrawMode() {
        if (!drawing) return
        binding.pdfView.drawMode = false
        binding.drawBar.visibility = View.GONE
        if (chromeVisible) binding.bottomBarScroller.visibility = View.VISIBLE
    }

    private val drawing: Boolean get() = binding.drawBar.visibility == View.VISIBLE

    /** What one finger does while the brush bar is open. */
    private enum class DrawTool { MOVE, PEN, ERASER }

    /**
     * Pick what one finger does. [DrawTool.MOVE] is the pen put down: the page scrolls and zooms
     * as normal and the marks stay on screen, so reaching the next spot to write no longer means
     * drawing a line across what is already there.
     */
    private fun selectTool(tool: DrawTool) {
        binding.pdfView.drawMode = tool != DrawTool.MOVE
        binding.pdfView.erasing = tool == DrawTool.ERASER
        val active = 0xFF7FC0FF.toInt()
        val idle = 0xFFFFFFFF.toInt()
        listOf(
            Triple(DrawTool.MOVE, binding.moveButton, binding.moveIcon to binding.moveLabel),
            Triple(DrawTool.PEN, binding.brushButton, binding.brushIcon to binding.brushLabel),
            Triple(DrawTool.ERASER, binding.eraserButton, binding.eraserIcon to binding.eraserLabel),
        ).forEach { (candidate, button, views) ->
            val on = candidate == tool
            button.isSelected = on
            views.first.imageTintList = android.content.res.ColorStateList.valueOf(if (on) active else idle)
            views.second.setTextColor(if (on) active else idle)
        }
    }

    /**
     * The colour swatches.
     *
     * White is first and is the default, because the commonest reason to draw on a document is to
     * cover something on it rather than to write on it.
     */
    private fun buildColourRow() {
        if (binding.colourRow.childCount > 0) return
        val density = resources.displayMetrics.density
        val size = (22 * density).toInt()
        val gap = (5 * density).toInt()
        BRUSH_COLOURS.forEach { colour ->
            val swatch = View(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(size, size).also {
                    it.marginEnd = gap
                }
                background = androidx.core.content.ContextCompat.getDrawable(
                    this@ReaderActivity,
                    R.drawable.bg_swatch,
                )
                backgroundTintList = android.content.res.ColorStateList.valueOf(colour)
                contentDescription = String.format("#%06X", colour and 0xFFFFFF)
                setOnClickListener { pickColour(colour) }
            }
            binding.colourRow.addView(swatch)
        }
        pickColour(brushColour)
    }

    private fun pickColour(colour: Int) {
        brushColour = colour
        binding.pdfView.brushColour = colour
        // Picking a colour means you are about to draw with it.
        selectTool(DrawTool.PEN)
        BRUSH_COLOURS.forEachIndexed { index, candidate ->
            val swatch = binding.colourRow.getChildAt(index) ?: return@forEachIndexed
            swatch.background = androidx.core.content.ContextCompat.getDrawable(
                this,
                if (candidate == colour) R.drawable.bg_swatch_selected else R.drawable.bg_swatch,
            )
            swatch.backgroundTintList = android.content.res.ColorStateList.valueOf(candidate)
        }
    }

    private var brushColour: Int = BRUSH_COLOURS.first()

    // ------------------------------------------------------------- strokes

    private fun addStroke(page: Int, points: FloatArray) {
        val stroke = Stroke(provisionalId--, page, brushColour, binding.pdfView.brushWidthPts, points)
        marksByPage.getOrPut(page) { mutableListOf() }.add(stroke)
        pushMarksToView()
        pushUndo(EditStep.AddStroke(stroke))
        val id = documentRowId
        if (id > 0) {
            lifecycleScope.launch {
                val rowId = ReaderDatabase.get(applicationContext)
                    .addMark(id, page, stroke.colour, stroke.widthPts, stroke.toBytes())
                if (rowId > 0) stroke.id = rowId
            }
        }
    }

    private fun eraseStrokes(strokes: List<Stroke>) {
        if (strokes.isEmpty()) return
        removeStrokes(strokes)
        pushUndo(EditStep.RemoveStrokes(strokes))
    }

    private fun removeStrokes(strokes: List<Stroke>) {
        strokes.forEach { stroke -> marksByPage[stroke.page]?.remove(stroke) }
        pushMarksToView()
        val ids = strokes.map { it.id }.filter { it > 0 }
        if (ids.isNotEmpty()) {
            lifecycleScope.launch { ReaderDatabase.get(applicationContext).removeMarks(ids) }
        }
    }

    private fun restoreStrokes(strokes: List<Stroke>) {
        strokes.forEach { stroke ->
            val here = marksByPage.getOrPut(stroke.page) { mutableListOf() }
            if (here.none { it === stroke }) here.add(stroke)
        }
        pushMarksToView()
        val id = documentRowId
        if (id <= 0) return
        lifecycleScope.launch {
            val database = ReaderDatabase.get(applicationContext)
            strokes.forEach { stroke ->
                if (stroke.id > 0) {
                    database.restoreMark(
                        id, stroke.id, stroke.page, stroke.colour, stroke.widthPts, stroke.toBytes(),
                    )
                } else {
                    val rowId = database.addMark(
                        id, stroke.page, stroke.colour, stroke.widthPts, stroke.toBytes(),
                    )
                    if (rowId > 0) stroke.id = rowId
                }
            }
        }
    }

    private fun pushMarksToView() {
        binding.pdfView.setMarks(marksByPage.filterValues { it.isNotEmpty() }.mapValues { it.value.toList() })
    }

    private fun loadMarks(active: DocumentController) {
        marksByPage.clear()
        undoStack.clear()
        redoStack.clear()
        pushMarksToView()
        val id = active.documentId
        if (id <= 0) return
        lifecycleScope.launch {
            val rows = ReaderDatabase.get(applicationContext).marks(id)
            if (documentRowId != id) return@launch
            rows.forEach { row ->
                Stroke.fromBytes(row.id, row.page, row.colour, row.widthPts, row.points)?.let {
                    marksByPage.getOrPut(row.page) { mutableListOf() }.add(it)
                }
            }
            pushMarksToView()
        }
    }

    private val documentRowId: Long get() = controller?.documentId ?: -1L

    // ------------------------------------------------------------- undo and redo

    private fun undo() {
        val step = undoStack.removeLastOrNull()
        if (step == null) {
            Toast.makeText(this, R.string.nothing_to_undo, Toast.LENGTH_SHORT).show()
            return
        }
        when (step) {
            is EditStep.AddStroke -> removeStrokes(listOf(step.stroke))
            is EditStep.RemoveStrokes -> restoreStrokes(step.strokes)
            is EditStep.Crop -> controller?.restoreCropBoxes(step.previousBoxes) { changed ->
                if (changed) binding.pdfView.onGeometryUpdated()
            }
            is EditStep.Rotate -> controller?.rotatePages(step.pages, -step.quarterTurns) { changed ->
                if (changed) binding.pdfView.onGeometryUpdated()
            }
        }
        redoStack.addLast(step)
        updateUndoButtons()
    }

    private fun redo() {
        val step = redoStack.removeLastOrNull()
        if (step == null) {
            Toast.makeText(this, R.string.nothing_to_redo, Toast.LENGTH_SHORT).show()
            return
        }
        when (step) {
            is EditStep.AddStroke -> restoreStrokes(listOf(step.stroke))
            is EditStep.RemoveStrokes -> removeStrokes(step.strokes)
            // Cropping again from the box that has just been restored lands in the same place,
            // because the frame was recorded as fractions of the page, not as absolute points.
            is EditStep.Crop -> controller?.cropPages(step.pages, step.fractions) { changed, _ ->
                if (changed) binding.pdfView.onGeometryUpdated()
            }
            is EditStep.Rotate -> controller?.rotatePages(step.pages, step.quarterTurns) { changed ->
                if (changed) binding.pdfView.onGeometryUpdated()
            }
        }
        undoStack.addLast(step)
        updateUndoButtons()
    }

    private fun promptClearMarks() {
        if (marksByPage.values.all { it.isEmpty() }) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.marks_clear_title)
            .setPositiveButton(R.string.label_clear) { _, _ ->
                val all = marksByPage.values.flatten()
                removeStrokes(all)
                pushUndo(EditStep.RemoveStrokes(all))
                Toast.makeText(this, R.string.marks_cleared, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Make the marks part of the document.
     *
     * Asked about first, and firmly, because it is the one action here that cannot be taken back:
     * once a mask is page content, whatever was under it is gone from the file.
     */
    private fun promptBurnMarks() {
        val active = controller ?: return
        val marks = marksByPage.filterValues { it.isNotEmpty() }
        if (marks.isEmpty()) return
        if (!PdfNative.canDraw) {
            Toast.makeText(this, R.string.marks_burn_failed, Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.marks_burn)
            .setMessage(R.string.marks_burn_explain)
            .setPositiveButton(R.string.label_burn) { _, _ ->
                val snapshot = marks.mapValues { it.value.toList() }
                active.burnMarks(snapshot) { ok ->
                    if (!ok) {
                        Toast.makeText(this, R.string.marks_burn_failed, Toast.LENGTH_LONG).show()
                        return@burnMarks
                    }
                    // They are page content now, so the overlay must stop drawing them and the
                    // history must forget them: there is nothing left to undo.
                    marksByPage.clear()
                    undoStack.clear()
                    redoStack.clear()
                    updateUndoButtons()
                    pushMarksToView()
                    lifecycleScope.launch {
                        ReaderDatabase.get(applicationContext).clearMarks(active.documentId)
                    }
                    invalidateOptionsMenu()
                    Toast.makeText(this, R.string.marks_burned, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------- links

    /**
     * Links for pages that have been looked at, keyed by page.
     *
     * Read ahead rather than on demand. Working out what is under a finger has to answer inside
     * the tap, and pdfium serialises every call behind one lock — asking it mid-tap would put the
     * question behind whatever page is being rendered and turn a tap into a visible stall. The
     * pages around the one being read are cheap to prepare and are almost always the ones tapped.
     */
    private val linksByPage = mutableMapOf<Int, List<PdfLinkTarget>>()

    private fun prefetchLinks(page: Int) {
        val active = controller ?: return
        val wanted = (page - 1..page + 1)
            .filter { it in 0 until binding.pdfView.pageCount && it !in linksByPage }
        if (wanted.isEmpty()) return
        active.loadLinks(wanted) { found ->
            linksByPage += found
            // A document nobody stops scrolling through should not accumulate every page's links.
            if (linksByPage.size > MAX_CACHED_LINK_PAGES) {
                val here = binding.pdfView.currentPage
                linksByPage.keys.filter { kotlin.math.abs(it - here) > 4 }
                    .toList()
                    .forEach { linksByPage.remove(it) }
            }
        }
    }

    /**
     * Follow whatever was tapped, and say whether anything was.
     *
     * A web address opens in the browser; a link into the document jumps to its page. Anything
     * else — a link to a file, an embedded action — is left alone rather than guessed at.
     */
    private fun followLinkAt(page: Int, xPts: Float, yPts: Float): Boolean {
        val links = linksByPage[page] ?: return false
        val hit = links.firstOrNull { link ->
            xPts >= link.bounds.left - LINK_SLOP_PTS && xPts <= link.bounds.right + LINK_SLOP_PTS &&
                yPts <= link.bounds.top + LINK_SLOP_PTS && yPts >= link.bounds.bottom - LINK_SLOP_PTS
        } ?: return false

        hit.destinationPage?.let { destination ->
            if (destination in 0 until binding.pdfView.pageCount) {
                binding.pdfView.goToPage(destination, animate = true)
                showPageChip(destination, binding.pdfView.pageCount)
                return true
            }
        }
        val url = hit.url?.trim().orEmpty()
        if (url.isEmpty()) return false
        return openLink(url)
    }

    private fun openLink(raw: String) : Boolean {
        val url = when {
            raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
            raw.startsWith("mailto:", true) || raw.startsWith("tel:", true) -> raw
            raw.contains('@') && !raw.contains(' ') -> "mailto:$raw"
            else -> "https://" + raw.removePrefix("//")
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.link_open_title)
            .setMessage(url)
            .setPositiveButton(R.string.link_open) { _, _ ->
                runCatching {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }.onFailure {
                    Toast.makeText(this, R.string.link_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton(R.string.link_copy) { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText(url, url))
                Toast.makeText(this, R.string.text_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
        return true
    }

    // ------------------------------------------------------------- text selection

    private var textSelector: PdfTextSelector? = null
    private var textSelection: TextSelection? = null

    /** The character the selection is anchored on while the other end is being dragged. */
    private var selectionAnchor = -1

    /**
     * Hold text to select the word under the finger.
     *
     * The text layer is read on a worker: pdfium is behind one global lock, and a page render in
     * flight would otherwise make a long press feel like a freeze. A scan with no text layer says
     * so rather than doing nothing, since "nothing happened" is indistinguishable from a bug.
     */
    private fun selectWordAt(page: Int, xPts: Float, yPts: Float) {
        val selector = textSelector ?: return
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                selector.wordAt(page, xPts.toDouble(), yPts.toDouble())
            }
            if (found == null || found.isEmpty) {
                Toast.makeText(this@ReaderActivity, R.string.text_none, Toast.LENGTH_SHORT).show()
                return@launch
            }
            selectionAnchor = -1
            applySelection(found)
        }
    }

    private fun selectWholePage() {
        val selector = textSelector ?: return
        val page = textSelection?.page ?: binding.pdfView.currentPage
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { selector.wholePage(page) }
            if (found == null || found.isEmpty) {
                Toast.makeText(this@ReaderActivity, R.string.text_none, Toast.LENGTH_SHORT).show()
                return@launch
            }
            applySelection(found)
        }
    }

    /**
     * Extend the selection to wherever a handle has been dragged.
     *
     * The end that is *not* being dragged is the anchor, captured on the first move, so dragging
     * the start handle past the end flips the selection instead of collapsing it.
     */
    private fun dragSelection(page: Int, draggingStart: Boolean, xPts: Float, yPts: Float) {
        val selector = textSelector ?: return
        val current = textSelection ?: return
        if (current.page != page) return
        if (selectionAnchor < 0) selectionAnchor = if (draggingStart) current.end else current.start
        val anchor = selectionAnchor
        binding.textActionBar.visibility = View.GONE
        lifecycleScope.launch {
            val moved = withContext(Dispatchers.IO) {
                selector.nearestCharacter(page, xPts.toDouble(), yPts.toDouble())
                    ?.let { selector.between(page, anchor, it) }
            } ?: return@launch
            textSelection = moved
            binding.pdfView.setTextSelection(moved.page, moved.rects)
        }
    }

    private fun applySelection(selection: TextSelection) {
        textSelection = selection
        binding.pdfView.setTextSelection(selection.page, selection.rects)
        showTextActions()
    }

    private fun showTextActions() {
        selectionAnchor = -1
        if (textSelection == null) return
        binding.textActionBar.visibility = View.VISIBLE
        binding.bottomBarScroller.visibility = View.GONE
        binding.zoomSliderBar.visibility = View.GONE
    }

    private fun hideTextActions() {
        binding.textActionBar.visibility = View.GONE
        if (!editing && !cropping && chromeVisible) {
            binding.bottomBarScroller.visibility = View.VISIBLE
        }
    }

    private fun clearTextSelection() {
        textSelection = null
        selectionAnchor = -1
        binding.pdfView.clearTextSelection()
        hideTextActions()
    }

    private fun selectedText(): String? = textSelection?.text?.trim()?.takeIf { it.isNotEmpty() }

    private fun copySelectedText() {
        val text = selectedText() ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.app_name), text))
        Toast.makeText(this, R.string.text_copied, Toast.LENGTH_SHORT).show()
        clearTextSelection()
    }

    private fun findSelectedText() {
        val text = selectedText() ?: return
        clearTextSelection()
        openSearch()
        binding.searchInput.setText(text.take(MAX_SEARCH_FROM_SELECTION))
        inputMethodManager.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
        startSearch(binding.searchInput.text.toString())
    }

    /**
     * Hand the text to whatever can translate it.
     *
     * ACTION_PROCESS_TEXT is what a translator app registers for, so this offers whichever ones
     * are installed. With none installed there is still Google Translate on the web, which beats
     * telling the reader their selection went nowhere.
     */
    private fun translateSelectedText() {
        val text = selectedText() ?: return
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
            val web = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(
                    "https://translate.google.com/?sl=auto&tl=en&op=translate&text=" + Uri.encode(text),
                ),
            )
            runCatching { startActivity(web) }.onFailure {
                Toast.makeText(this, R.string.text_no_translator, Toast.LENGTH_SHORT).show()
            }
        }
        clearTextSelection()
    }

    private fun shareSelectedText() {
        val text = selectedText() ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.action_share))) }
        clearTextSelection()
    }

    // ------------------------------------------------------------- splitting

    /**
     * Offer to cut each selected page down the middle into two.
     *
     * This is for scans of books, where two facing pages were photographed as one wide sheet. The
     * reading order has to be asked about rather than assumed: a Bengali or English book reads
     * left half first, an Arabic or Urdu one reads right half first, and getting it wrong shuffles
     * the whole document.
     *
     * The result is a new file rather than a change to this one, because the page count changes —
     * which would strand every bookmark and reading position in the document if it were done in
     * place, for something the reader may well want to undo.
     */
    private fun promptSplit() {
        val active = controller ?: return
        val pages = binding.pdfView.selectedPages.toList()
        if (pages.isEmpty()) return
        if (!PdfNative.canCrop || !PdfNative.canExtractPages) {
            Toast.makeText(this, R.string.crop_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.split_title, pages.size))
            .setMessage(R.string.split_explain)
            .setPositiveButton(R.string.split_left_first) { _, _ -> runSplit(active, pages, false) }
            .setNeutralButton(R.string.split_right_first) { _, _ -> runSplit(active, pages, true) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runSplit(active: DocumentController, pages: List<Int>, rightFirst: Boolean) {
        // Every page is copied, so this is the one page operation that is worth a wait indicator.
        binding.loadingOverlay.visibility = View.VISIBLE
        saveChanges { splitPagesNow(active, pages, rightFirst) }
    }

    private fun splitPagesNow(active: DocumentController, pages: List<Int>, rightFirst: Boolean) {
        active.splitPages(pages, rightFirst) { file ->
            binding.loadingOverlay.visibility = View.GONE
            if (file == null) {
                Toast.makeText(this, R.string.split_failed, Toast.LENGTH_LONG).show()
                return@splitPages
            }
            exitEditMode()
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.split_done, pages.size * 2))
                .setItems(
                    arrayOf(
                        getString(R.string.split_open),
                        getString(R.string.extract_save),
                        getString(R.string.extract_share),
                    ),
                ) { _, which ->
                    when (which) {
                        0 -> openDocument(Uri.fromFile(file), password = null)
                        1 -> copyToDownloads(file)
                        else -> Sharing.share(this, Uri.fromFile(file), file.name)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    // ------------------------------------------------------------- cropping

    private val cropping: Boolean get() = binding.cropLayer.visibility == View.VISIBLE

    /**
     * Choose a crop on one page and apply it to every selected page.
     *
     * One frame for the whole selection rather than one per page: a scan is cropped because every
     * page has the same margins or the same scanner shadow down one side, and asking for the same
     * rectangle to be drawn forty times would make the feature useless on exactly the documents
     * that need it. The frame is drawn over the first selected page, which is scrolled to first so
     * there is something to line it up against.
     */
    private fun startCrop() {
        val pages = binding.pdfView.selectedPages
        if (pages.isEmpty()) return
        if (!PdfNative.canCrop) {
            Toast.makeText(this, R.string.crop_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        val target = pages.min()
        val count = pages.size
        zoomBeforeCrop = binding.pdfView.zoom
        val rect = binding.pdfView.prepareCrop(target)
        if (rect == null) {
            Toast.makeText(this, R.string.crop_failed, Toast.LENGTH_SHORT).show()
            return
        }
        binding.cropOverlay.startFor(rect)
        binding.cropApplyLabel.text = getString(R.string.crop_apply, count)
        binding.cropLayer.visibility = View.VISIBLE
        binding.editBar.visibility = View.GONE
        binding.toolbar.visibility = View.GONE
        binding.bottomBarScroller.visibility = View.GONE
        binding.zoomSliderBar.visibility = View.GONE
    }

    /** The reading zoom, put back when the crop screen closes. */
    private var zoomBeforeCrop: Float? = null

    private fun applyCrop() {
        val active = controller ?: return
        val pages = binding.pdfView.selectedPages.toList()
        if (pages.isEmpty() || binding.cropOverlay.isWholePage) {
            finishCrop()
            return
        }
        val fractions = binding.cropOverlay.fractions()
        finishCrop()
        active.cropPages(pages, fractions) { changed, previous ->
            Toast.makeText(
                this,
                if (changed) R.string.crop_done else R.string.crop_failed,
                Toast.LENGTH_SHORT,
            ).show()
            if (changed) {
                // Remembered so the crop can be taken back off and the page restored exactly.
                pushUndo(EditStep.Crop(previous, pages, fractions))
                binding.pdfView.onGeometryUpdated()
                invalidateOptionsMenu()
            }
        }
    }

    private fun finishCrop() {
        if (!cropping) return
        binding.cropLayer.visibility = View.GONE
        binding.toolbar.visibility = if (searchOpen) View.GONE else View.VISIBLE
        binding.editBar.visibility = View.VISIBLE
        zoomBeforeCrop?.let { binding.pdfView.restoreZoomAfterCrop(it) }
        zoomBeforeCrop = null
    }

    private fun copyToDownloads(file: File) {
        lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) { Downloads.save(this@ReaderActivity, file) }
            Toast.makeText(
                this@ReaderActivity,
                if (saved != null) getString(R.string.extract_saved, saved) else getString(R.string.extract_failed),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun rotateSelection(quarterTurns: Int) {
        val active = controller ?: return
        val pages = binding.pdfView.selectedPages.toList()
        if (pages.isEmpty()) return
        active.rotatePages(pages, quarterTurns) { changed ->
            if (changed) {
                pushUndo(EditStep.Rotate(pages, quarterTurns))
                binding.pdfView.onGeometryUpdated()
                invalidateOptionsMenu()
            }
        }
    }

    private fun saveChanges(then: (() -> Unit)? = null) {
        val active = controller ?: return
        if (!active.hasUnsavedChanges) {
            then?.invoke()
            return
        }
        binding.statusText.setText(R.string.saving)
        binding.statusText.visibility = View.VISIBLE
        lifecycleScope.launch {
            val ok = active.saveChanges()
            binding.statusText.visibility = View.GONE
            Toast.makeText(
                this@ReaderActivity,
                if (ok) R.string.saved else R.string.save_failed,
                Toast.LENGTH_SHORT,
            ).show()
            invalidateOptionsMenu()
            onPageChanged(binding.pdfView.currentPage)
            if (ok) {
                then?.invoke()
            } else {
                // A New PDF or Split waiting on this save will not run; do not leave it looking busy.
                binding.loadingOverlay.visibility = View.GONE
                updateSelectionCount(binding.pdfView.selectedPages)
            }
        }
    }

    /** Offer to save before leaving, so an accidental back press cannot lose a rotation. */
    private fun confirmClose() {
        val active = controller
        if (active == null || !active.hasUnsavedChanges) {
            finish()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.discard_changes_title)
            .setMessage(R.string.discard_changes_message)
            .setPositiveButton(R.string.action_save) { _, _ -> saveChanges { finish() } }
            .setNegativeButton(R.string.discard) { _, _ -> finish() }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ search

    /**
     * Search is incremental: hits arrive one page at a time and the first one is jumped to as soon
     * as it is found, so a 3000-page document is usable a moment after you press enter rather than
     * after the whole sweep finishes.
     */
    private fun startSearch(query: String) {
        val active = controller ?: return
        searchJob?.cancel()
        searchHits.clear()
        searchIndex = -1
        binding.pdfView.clearSearchHighlights()

        val search = PdfSearch(active.session)
        pdfSearch = search
        binding.searchCount.text = ""

        searchJob = lifecycleScope.launch {
            search.run(
                query = query,
                startPage = binding.pdfView.currentPage,
                onHit = { hit ->
                    if (searchHits.size < MAX_SEARCH_HITS) {
                        searchHits += hit
                        if (searchHits.size == 1) showHit(0) else updateSearchCount()
                    }
                },
                onProgress = { searched, total ->
                    if (searchHits.isEmpty()) {
                        binding.searchCount.text =
                            getString(R.string.search_searching, searched * 100 / total.coerceAtLeast(1))
                    }
                },
            )
            if (searchHits.isEmpty()) {
                binding.searchCount.setText(R.string.search_no_results)
            } else {
                updateSearchCount()
            }
        }
    }

    private fun updateSearchCount() {
        binding.searchCount.text =
            getString(R.string.search_result_of, (searchIndex + 1).coerceAtLeast(1), searchHits.size)
    }

    private fun stepSearch(delta: Int) {
        if (searchHits.isEmpty()) return
        val next = (searchIndex + delta).let {
            when {
                it < 0 -> searchHits.lastIndex
                it > searchHits.lastIndex -> 0
                else -> it
            }
        }
        showHit(next)
    }

    /** Scroll to a match and paint every match on the same page, with this one emphasised. */
    private fun showHit(index: Int) {
        val hit = searchHits.getOrNull(index) ?: return
        val search = pdfSearch ?: return
        searchIndex = index

        lifecycleScope.launch {
            val samePage = searchHits.withIndex().filter { it.value.page == hit.page }
            var activeRectIndex = 0
            val rects = withContext(Dispatchers.IO) {
                val collected = mutableListOf<RectF>()
                samePage.forEach { (globalIndex, pageHit) ->
                    if (globalIndex == index) activeRectIndex = collected.size
                    collected += search.rectsFor(pageHit)
                }
                collected
            }
            binding.pdfView.setSearchHighlights(mapOf(hit.page to rects), hit.page to activeRectIndex)
            val target = rects.getOrNull(activeRectIndex)
            if (target != null) {
                binding.pdfView.scrollToPageRect(hit.page, target)
            } else {
                binding.pdfView.goToPage(hit.page)
            }
            updateSearchCount()
        }
    }

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
        searchHits.clear()
        searchIndex = -1
        binding.searchBar.visibility = View.GONE
        binding.toolbar.visibility = if (chromeVisible) View.VISIBLE else View.GONE
        binding.pdfView.clearSearchHighlights()
        inputMethodManager.hideSoftInputFromWindow(binding.searchInput.windowToken, 0)
    }

    private val searchOpen: Boolean get() = binding.searchBar.visibility == View.VISIBLE

    // -------------------------------------------------------------------- menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.reader, menu)
        menu.findItem(R.id.action_tabs)?.actionView?.let { view ->
            tabCountLabel = view.findViewById(R.id.tabCount)
            view.setOnClickListener { showTabs() }
        }
        updateTabCount()
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val bookmarked = binding.pdfView.currentPage in bookmarkedPages
        menu.findItem(R.id.action_bookmark)?.setIcon(
            if (bookmarked) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border,
        )
        // The save icon only appears when there is something to save.
        menu.findItem(R.id.action_save)?.isVisible = controller?.hasUnsavedChanges == true
        updateTabCount()
        return super.onPrepareOptionsMenu(menu)
    }

    private fun updateTabCount() {
        tabCountLabel?.text = DocumentTabs.count.coerceAtLeast(1).toString()
    }

    private fun showTabs() {
        TabsSheet.show(
            context = this,
            onSwitch = { showController(it).also { _ -> DocumentTabs.makeActive(it) } },
            onClosed = {
                updateTabCount()
                // Closing the visible tab hands the reader to whatever was open before it; closing
                // the last one leaves nothing to read, so the reader steps aside.
                val next = DocumentTabs.active
                if (next == null) {
                    controller = null
                    finish()
                } else if (next !== controller) {
                    showController(next)
                }
            },
            onOpenAnother = { pickDocument.launch(SUPPORTED_MIME_TYPES) },
        )
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_bookmark -> toggleBookmark()
            R.id.action_share -> shareDocument()
            R.id.action_document_info -> showDocumentInfo()
            R.id.action_search -> openSearch()
            R.id.action_save -> saveChanges()
            R.id.action_update -> Updater.checkAndOffer(this, lifecycleScope)
            R.id.action_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun toggleBookmark() {
        val active = controller ?: return
        val page = binding.pdfView.currentPage
        lifecycleScope.launch {
            val database = ReaderDatabase.get(applicationContext)
            if (bookmarkedPages.remove(page)) {
                database.removeBookmark(active.documentId, page)
            } else {
                bookmarkedPages.add(page)
                database.addBookmark(active.documentId, page, null, System.currentTimeMillis())
            }
            invalidateOptionsMenu()
        }
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
                binding.pdfView.colorMode = colorMode
                Settings.get(this).defaultColorMode = which
                dialog.dismiss()
            }
            .show()
    }

    /**
     * Each way of reading shown with the same icon the View button wears for it, so the button
     * and the choice read as one thing; the one in use is ticked.
     */
    private fun chooseViewMode() {
        val choices = listOf(
            Triple(ViewMode.VERTICAL, R.string.view_mode_vertical, R.drawable.ic_view_day),
            Triple(ViewMode.HORIZONTAL, R.string.view_mode_horizontal, R.drawable.ic_menu_book),
            Triple(ViewMode.DUAL, R.string.view_mode_dual, R.drawable.ic_view_column),
        )
        val current = binding.pdfView.viewMode
        val density = resources.displayMetrics.density
        val textColour = com.google.android.material.color.MaterialColors.getColor(
            binding.root, com.google.android.material.R.attr.colorOnSurface,
        )
        val accent = com.google.android.material.color.MaterialColors.getColor(
            binding.root, androidx.appcompat.R.attr.colorPrimary,
        )
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = choices.size
            override fun getItem(position: Int) = choices[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val (mode, label, icon) = choices[position]
                val selected = mode == current
                val tint = if (selected) accent else textColour
                fun drawable(res: Int) = androidx.core.content.ContextCompat.getDrawable(this@ReaderActivity, res)
                    ?.mutate()?.apply { setTint(tint) }
                return (convertView as? android.widget.TextView ?: android.widget.TextView(this@ReaderActivity)).apply {
                    text = getString(label)
                    setTextColor(tint)
                    textSize = 16f
                    minHeight = (56 * density).toInt()
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding((24 * density).toInt(), 0, (24 * density).toInt(), 0)
                    compoundDrawablePadding = (20 * density).toInt()
                    setCompoundDrawablesRelativeWithIntrinsicBounds(
                        drawable(icon),
                        null,
                        if (selected) drawable(R.drawable.ic_check) else null,
                        null,
                    )
                }
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_view_mode)
            .setAdapter(adapter) { dialog, which ->
                binding.pdfView.viewMode = choices[which].first
                updateViewModeIcon()
                updateZoomLabel()
                dialog.dismiss()
            }
            .show()
    }

    private fun promptGoToPage() {
        val total = binding.pdfView.pageCount
        if (total <= 0) return
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.page_of, binding.pdfView.currentPage + 1, total)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.action_go_to_page)
            .setView(input)
            .setPositiveButton(R.string.go) { _, _ ->
                val page = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                binding.pdfView.goToPage((page - 1).coerceIn(0, total - 1))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showThumbnails() {
        val active = controller ?: return
        ThumbnailsSheet.show(this, active, binding.pdfView.currentPage) { page ->
            binding.pdfView.goToPage(page)
        }
    }

    private fun showOutline() {
        val active = controller ?: return
        lifecycleScope.launch {
            val entries = mutableListOf<Pair<String, Int>>()
            fun flatten(items: List<io.legere.pdfiumandroid.api.Bookmark>, depth: Int) {
                items.forEach { bookmark ->
                    entries += "${"    ".repeat(depth)}${bookmark.title}" to bookmark.pageIdx.toInt()
                    flatten(bookmark.children, depth + 1)
                }
            }
            flatten(active.session.tableOfContents(), 0)

            if (entries.isEmpty()) {
                Toast.makeText(this@ReaderActivity, "This document has no contents", Toast.LENGTH_SHORT).show()
                return@launch
            }
            MaterialAlertDialogBuilder(this@ReaderActivity)
                .setTitle(R.string.action_outline)
                .setItems(entries.map { it.first }.toTypedArray()) { _, which ->
                    binding.pdfView.goToPage(entries[which].second.coerceAtLeast(0))
                }
                .show()
        }
    }

    private fun shareDocument() {
        val active = controller ?: return
        Sharing.share(this, active.uri, active.displayName)
    }

    private fun showDocumentInfo() {
        val active = controller ?: return
        lifecycleScope.launch {
            val meta = active.session.metadata()
            val text = buildString {
                appendLine(active.displayName)
                appendLine()
                appendLine("Pages: ${active.pageCount}")
                meta?.title?.takeIf { it.isNotBlank() }?.let { appendLine("Title: $it") }
                meta?.author?.takeIf { it.isNotBlank() }?.let { appendLine("Author: $it") }
                meta?.producer?.takeIf { it.isNotBlank() }?.let { appendLine("Producer: $it") }
                val measured = active.geometry.measuredCount
                appendLine("Measured pages: $measured / ${active.pageCount}")
            }
            MaterialAlertDialogBuilder(this@ReaderActivity)
                .setTitle(R.string.action_document_info)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    // --------------------------------------------------------------- lifecycle

    private fun takePersistablePermission(uri: Uri) {
        if (uri.scheme != "content") return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    override fun onResume() {
        super.onResume()
        Updater.resumePendingInstall(this)
    }

    override fun onPause() {
        super.onPause()
        saveReadingPosition()
    }

    private fun saveReadingPosition() {
        val active = controller ?: return
        binding.pdfView.removeCallbacks(savePositionRunnable)
        active.savePosition(binding.pdfView.readingPosition(), binding.pdfView.viewMode, colorMode.ordinal)
    }

    /**
     * Write the position a couple of seconds after the reader settles on a new page.
     *
     * Pausing is the obvious moment to save and is not the only one: a phone can kill a background
     * app without another callback, and a reader who comes back to page one because Android
     * reclaimed the process will not care whose fault that was.
     */
    private val savePositionRunnable = Runnable { saveReadingPosition() }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            controller?.onBackgrounded()
            DocumentTabs.trimBackgroundTabs()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.pdfView.removeCallbacks(savePositionRunnable)
        binding.pdfView.detach()
        controller?.let { it.lastPosition = null }
        controller?.onInvalidate = null
        controller?.onGeometryChanged = null
        // Documents belong to DocumentTabs, not to this activity: leaving the reader must not close
        // the open tabs. They are released when the user closes them, or when a tab is evicted.
        if (isFinishing && DocumentTabs.count == 0) DocumentTabs.closeAll()
        controller = null
        tabCountLabel = null
        // Nothing here should outlive the screen: the selector holds the open document.
        textSelector = null
        textSelection = null
    }

    companion object {
        const val EXTRA_URI = "top.teamaos.pdfreader.EXTRA_URI"
        /** A whole paragraph is not a search term; long selections are trimmed before searching. */
        private const val MAX_SEARCH_FROM_SELECTION = 60

        /** How long after the last page change the position is written. */
        private const val SAVE_POSITION_DELAY_MS = 2500L

        /** How far outside a link's own box a tap still counts, in points. */
        private const val LINK_SLOP_PTS = 3f

        /** Links are kept for this many pages before the far ones are dropped. */
        private const val MAX_CACHED_LINK_PAGES = 24

        /** Deep enough for a long session of marking up, shallow enough to stay cheap. */
        private const val MAX_UNDO_STEPS = 200

        /** White first: covering something up is what a brush in a document reader is for. */
        private val BRUSH_COLOURS = listOf(
            0xFFFFFFFF.toInt(),
            0xFF000000.toInt(),
            0xFFE53935.toInt(),
            0xFFFFD54F.toInt(),
            0xFF43A047.toInt(),
            0xFF1E88E5.toInt(),
        )

        private const val PAGE_CHIP_MS = 900L
        private const val ZOOM_SLIDER_MS = 2600L

        /** An accidental long-press should not leave the reader stuck in editing mode. */


        /** A search matching tens of thousands of times is not useful; stop collecting past this. */
        private const val MAX_SEARCH_HITS = 2000
        private val SUPPORTED_MIME_TYPES = arrayOf("application/pdf")
    }
}
