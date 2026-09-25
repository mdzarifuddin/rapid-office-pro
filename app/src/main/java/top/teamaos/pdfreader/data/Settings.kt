package top.teamaos.pdfreader.data

import android.content.Context
import android.content.SharedPreferences

/** How the reader should sit on screen. */
enum class ScreenOrientationMode {
    /** Whatever the phone's own rotation says. */
    FOLLOW_PHONE,
    /** The default. */
    PORTRAIT,
    LANDSCAPE,
    ;

    fun next(): ScreenOrientationMode = entries[(ordinal + 1) % entries.size]
}

/** App-wide preferences, kept deliberately small — per-document state lives in [ReaderDatabase]. */
class Settings private constructor(private val preferences: SharedPreferences) {

    /**
     * How a reader starts when a document is opened. Only the settings screen changes this; the
     * screen button on the reader's bar turns the current document and is forgotten on close, so
     * one sideways read can never leave every later document opening sideways.
     */
    var orientationMode: ScreenOrientationMode
        get() = ScreenOrientationMode.entries.getOrElse(
            preferences.getInt(KEY_ORIENTATION, ScreenOrientationMode.PORTRAIT.ordinal),
        ) { ScreenOrientationMode.PORTRAIT }
        set(value) = preferences.edit().putInt(KEY_ORIENTATION, value.ordinal).apply()

    /** Keep the screen awake while reading. Off by default: it is the biggest battery cost there is. */
    var keepScreenOn: Boolean
        get() = preferences.getBoolean(KEY_KEEP_SCREEN_ON, false)
        set(value) = preferences.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    /** Render quality, as the ordinal of [top.teamaos.pdfreader.core.RenderQuality]. */
    var renderQuality: Int
        get() = preferences.getInt(KEY_RENDER_QUALITY, 1)
        set(value) = preferences.edit().putInt(KEY_RENDER_QUALITY, value).apply()

    /**
     * Colour mode a document opens in, as the ordinal of
     * [top.teamaos.pdfreader.view.ColorMode]. A document that has been read before keeps whatever
     * it was last read in; this is only the starting point for new ones.
     */
    var defaultColorMode: Int
        get() = preferences.getInt(KEY_DEFAULT_COLOUR_MODE, 0)
        set(value) = preferences.edit().putInt(KEY_DEFAULT_COLOUR_MODE, value).apply()

    /** Show two pages side by side automatically when the phone is turned sideways. */
    var dualPageInLandscape: Boolean
        get() = preferences.getBoolean(KEY_DUAL_IN_LANDSCAPE, true)
        set(value) = preferences.edit().putBoolean(KEY_DUAL_IN_LANDSCAPE, value).apply()

    /** When the home screen last looked for a new version, so it asks GitHub at most once a day. */
    var lastUpdateCheck: Long
        get() = preferences.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = preferences.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()

    companion object {
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"

        // A new key: the old one was also written by the reader's screen button, so whatever it
        // holds is most likely a leftover landscape rather than a real choice.
        private const val KEY_ORIENTATION = "start_orientation_mode"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_RENDER_QUALITY = "render_quality"
        private const val KEY_DUAL_IN_LANDSCAPE = "dual_in_landscape"
        private const val KEY_DEFAULT_COLOUR_MODE = "default_colour_mode"

        @Volatile
        private var instance: Settings? = null

        fun get(context: Context): Settings =
            instance ?: synchronized(this) {
                instance ?: Settings(
                    context.applicationContext.getSharedPreferences("reader", Context.MODE_PRIVATE),
                ).also { instance = it }
            }
    }
}
