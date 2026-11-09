package kr.iamtalker.waterview

import android.content.Context
import androidx.core.content.edit

/**
 * The reader's own preferences, as opposed to [Recents], which is a record of what
 * they opened.
 *
 * Reader preferences persist across opens so the reading direction and page layout are
 * chosen once, then stay out of the way.
 *
 * Not backed up: `allowBackup="false"` in the manifest covers this file along with the
 * recents list, for the reason given there.
 */
object Settings {

    private const val PREFS = "viewer"
    private const val KEY_NIGHT = "night"
    private const val KEY_SORT_ORDER = "folder_sort_order"
    private const val KEY_BOOK_TEXT = "book_text_size"
    private const val KEY_PDF_ZOOM = "pdf_zoom_percent"
    private const val KEY_MANGA_RTL = "manga_rtl"
    private const val KEY_SPLIT_WIDE_PAGES = "split_wide_pages"
    private const val KEY_PDF_HORIZONTAL = "pdf_horizontal_pages"
    private const val KEY_TEXT_HORIZONTAL = "text_horizontal_pages"
    private const val KEY_PDF_SPLIT_WIDE = "pdf_split_wide_pages"
    private const val KEY_TEXT_REFLOW = "text_reflow"
    private const val KEY_APP_NIGHT = "app_night"
    private const val KEY_TAP_EDGE = "tap_edge_percent"

    /** The tap zone on each side of a page, in percent of the width: any whole value from 10 to 40. */
    const val TAP_EDGE_MIN = 10
    const val TAP_EDGE_MAX = 40

    fun tapEdgePercent(context: Context): Int {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_TAP_EDGE, 28)
        return saved.coerceIn(TAP_EDGE_MIN, TAP_EDGE_MAX)
    }

    fun setTapEdgePercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt(KEY_TAP_EDGE, percent) }
    }

    fun mangaRightToLeft(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_MANGA_RTL, false)

    fun setMangaRightToLeft(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_MANGA_RTL, value) }
    }

    fun splitWidePages(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SPLIT_WIDE_PAGES, true)

    fun setSplitWidePages(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_SPLIT_WIDE_PAGES, value) }
    }

    fun pdfHorizontalPages(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PDF_HORIZONTAL, false)

    fun setPdfHorizontalPages(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_PDF_HORIZONTAL, value) }
    }

    /** Whether plain text lays out as sideways columns rather than a vertical scroll. */
    fun textHorizontalPages(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_TEXT_HORIZONTAL, pdfHorizontalPages(context))

    fun setTextHorizontalPages(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_TEXT_HORIZONTAL, value) }
    }

    /** The app's own screen mode: true for night, false for day. Applied at start-up and on change. */
    fun appNight(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_APP_NIGHT, false)

    fun setAppNight(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_APP_NIGHT, on) }
    }

    /**
     * Whether a text file's line breaks are joined into its paragraphs, for text that was broken
     * into short lines by hand or by a converter. Blank lines are kept as paragraph breaks.
     */
    fun textReflow(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TEXT_REFLOW, false)

    fun setTextReflow(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_TEXT_REFLOW, value) }
    }

    fun pdfSplitWidePages(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_PDF_SPLIT_WIDE, false)

    fun setPdfSplitWidePages(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_PDF_SPLIT_WIDE, value) }
    }

    /**
     * The sizes a book's text can be set to, as a percentage of the size it opens at. Nine
     * steps, each about a seventh larger than the one before, from a little under the book's
     * own size to two and a half times it. Past that a phone holds three words to a line,
     * and pinching is the better tool.
     */
    val BOOK_TEXT_SIZES = intArrayOf(80, 90, 100, 115, 130, 150, 175, 200, 250)

    enum class SortOrder(val labelRes: Int) {
        NAME_AZ(R.string.sort_name_az),
        NAME_ZA(R.string.sort_name_za),
        NEWEST(R.string.sort_newest),
        OLDEST(R.string.sort_oldest),
        LARGEST(R.string.sort_largest),
        SMALLEST(R.string.sort_smallest)
    }

    /** Whether PDF pages are drawn turned over. See issue #19 and `pdf.html`. */
    /** The paper colours a reader can choose, as hex without the #, in the order the settings list them. */
    val GROUNDS = listOf("FFFFFF", "F5EFE0", "E0E0E0", "E3EEF7")

    private const val KEY_GROUND = "ground_index"

    fun groundIndex(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_GROUND, 0).coerceIn(0, GROUNDS.size - 1)

    fun setGroundIndex(context: Context, index: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt(KEY_GROUND, index) }
    }

    /** The background of every viewer in day mode; night keeps its own black. */
    fun groundColor(context: Context): Int =
        if (appNight(context)) android.graphics.Color.BLACK
        else android.graphics.Color.parseColor("#" + GROUNDS[groundIndex(context)])

    /** The one screen mode for the whole app and every viewer: the setting in Settings. */
    fun night(context: Context): Boolean = appNight(context)

    fun setNight(context: Context, on: Boolean) = setAppNight(context, on)

    /** The size of a book's text, one of [BOOK_TEXT_SIZES]. The same for every book. */
    fun bookTextSize(context: Context): Int {
        val kept = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_BOOK_TEXT, 100)
        return if (kept in BOOK_TEXT_SIZES) kept else 100
    }

    fun setBookTextSize(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putInt(KEY_BOOK_TEXT, percent) }
    }

    /** The whole-page zoom for PDFs; PDF text itself has fixed page layout. */
    fun pdfZoomPercent(context: Context): Int {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PDF_ZOOM, 100)
        return if (saved in PDF_ZOOM_SIZES) saved else 100
    }

    fun setPdfZoomPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putInt(KEY_PDF_ZOOM, percent) }
    }

    val PDF_ZOOM_SIZES = intArrayOf(80, 90, 100, 115, 130, 150, 175, 200)

    fun sortOrder(context: Context): SortOrder {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SORT_ORDER, SortOrder.NAME_AZ.name)
        return runCatching { SortOrder.valueOf(name ?: SortOrder.NAME_AZ.name) }
            .getOrDefault(SortOrder.NAME_AZ)
    }

    fun setSortOrder(context: Context, order: SortOrder) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY_SORT_ORDER, order.name) }
    }
}
