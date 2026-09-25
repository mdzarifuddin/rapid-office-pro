package top.teamaos.pdfreader.ui

import android.os.Bundle
import android.text.format.Formatter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.teamaos.pdfreader.BuildConfig
import top.teamaos.pdfreader.R
import top.teamaos.pdfreader.core.RenderQuality
import top.teamaos.pdfreader.data.ReaderDatabase
import top.teamaos.pdfreader.data.ScreenOrientationMode
import top.teamaos.pdfreader.data.Settings
import top.teamaos.pdfreader.databinding.ActivitySettingsBinding
import top.teamaos.pdfreader.view.ColorMode
import java.io.File

/** App-wide preferences, and the two buttons that reclaim disk space. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private val settings by lazy { Settings.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.root.updatePadding(top = bars.top)
            binding.settingsContent.updatePadding(bottom = bars.bottom)
            insets
        }

        binding.colourModeRow.setOnClickListener { chooseColourMode() }
        binding.orientationRow.setOnClickListener { chooseOrientation() }
        binding.qualityRow.setOnClickListener { chooseQuality() }

        binding.dualLandscapeRow.setOnClickListener { binding.dualLandscapeSwitch.toggle() }
        binding.dualLandscapeSwitch.setOnCheckedChangeListener { _, checked ->
            settings.dualPageInLandscape = checked
        }

        binding.keepScreenOnRow.setOnClickListener { binding.keepScreenOnSwitch.toggle() }
        binding.keepScreenOnSwitch.setOnCheckedChangeListener { _, checked ->
            settings.keepScreenOn = checked
        }

        binding.clearCacheRow.setOnClickListener { clearCaches() }
        binding.clearHistoryRow.setOnClickListener { confirmClearHistory() }

        binding.aboutText.text = getString(R.string.about_format, BuildConfig.VERSION_NAME)
        binding.updateValue.text = getString(R.string.settings_update_detail, BuildConfig.VERSION_NAME)
        binding.updateRow.setOnClickListener { Updater.checkAndOffer(this, lifecycleScope) }
    }

    override fun onResume() {
        super.onResume()
        Updater.resumePendingInstall(this)
        refresh()
    }

    private fun refresh() {
        binding.colourModeValue.setText(colourModeLabels()[settings.defaultColorMode.coerceIn(0, 3)])
        binding.orientationValue.setText(orientationLabels()[settings.orientationMode.ordinal])
        binding.qualityValue.setText(qualityLabels()[settings.renderQuality.coerceIn(0, 2)])
        binding.dualLandscapeSwitch.isChecked = settings.dualPageInLandscape
        binding.keepScreenOnSwitch.isChecked = settings.keepScreenOn
        updateCacheSize()
    }

    private fun colourModeLabels() = intArrayOf(
        R.string.colour_normal,
        R.string.colour_night,
        R.string.colour_sepia,
        R.string.colour_grayscale,
    )

    private fun orientationLabels() = intArrayOf(
        R.string.orientation_auto,
        R.string.orientation_portrait,
        R.string.orientation_landscape,
    )

    private fun qualityLabels() = intArrayOf(
        R.string.quality_fast,
        R.string.quality_balanced,
        R.string.quality_high,
    )

    private fun chooseColourMode() = chooseFromList(
        title = R.string.settings_colour_mode,
        labels = colourModeLabels(),
        selected = settings.defaultColorMode,
    ) { settings.defaultColorMode = it.coerceIn(0, ColorMode.entries.lastIndex) }

    private fun chooseOrientation() = chooseFromList(
        title = R.string.settings_screen,
        labels = orientationLabels(),
        selected = settings.orientationMode.ordinal,
    ) { settings.orientationMode = ScreenOrientationMode.entries[it] }

    private fun chooseQuality() = chooseFromList(
        title = R.string.settings_quality,
        labels = qualityLabels(),
        selected = settings.renderQuality,
    ) { settings.renderQuality = it.coerceIn(0, RenderQuality.entries.lastIndex) }

    private fun chooseFromList(title: Int, labels: IntArray, selected: Int, onPick: (Int) -> Unit) {
        val text = labels.map(::getString).toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setSingleChoiceItems(text, selected.coerceIn(0, text.lastIndex)) { dialog, which ->
                onPick(which)
                refresh()
                dialog.dismiss()
            }
            .show()
    }

    private fun updateCacheSize() {
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) { directorySize(cacheDir) }
            binding.cacheSizeValue.text =
                getString(R.string.cache_size, Formatter.formatShortFileSize(this@SettingsActivity, bytes))
        }
    }

    private fun directorySize(directory: File): Long =
        directory.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    private fun clearCaches() {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            }
            Toast.makeText(this@SettingsActivity, R.string.settings_cleared, Toast.LENGTH_SHORT).show()
            updateCacheSize()
        }
    }

    private fun confirmClearHistory() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_clear_history)
            .setMessage(R.string.settings_clear_history_detail)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    ReaderDatabase.get(applicationContext).clearHistory()
                    Toast.makeText(this@SettingsActivity, R.string.settings_cleared, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
