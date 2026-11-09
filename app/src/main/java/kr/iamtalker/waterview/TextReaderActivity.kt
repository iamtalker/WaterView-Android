package kr.iamtalker.waterview

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Spannable
import android.text.StaticLayout
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * A plain text file, on its own screen. The rows of the file are read from a copy as they are shown
 * (see [TextIndex]), so the file is never held in memory whole, and the list moves line by line with
 * no page snapping: a move is a plain scroll, which is what keeps it smooth.
 *
 * The place it was left at, the bookmarks and the search are byte offsets of the copy, so they agree
 * with one another and stay the same from one opening to the next.
 */
class TextReaderActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var list: RecyclerView
    private lateinit var seek: SeekBar
    private lateinit var percent: TextView
    private lateinit var bottomBar: View
    private var fullscreen = false

    private var index: TextIndex? = null
    private var key: String? = null
    /** The text being searched for, marked wherever it shows on the screen. */
    private var query: String? = null
    /** Where the last search found the query, so the next search goes on from there. */
    private var lastHit: Long? = null
    private var readAloud: ReadAloud? = null
    private var seeking = false
    private var textSizeSp = 18f
    /** The average height of a row on the screen, in pixels, for the width and text size [rowPxFor] names. */
    private var rowPx = 0f
    private var rowPxFor: Pair<Int, Float>? = null
    private var ink = Color.BLACK
    private var ground = Color.WHITE

    /** Indexing and searching, one at a time, off the main thread. */
    private val worker = Executors.newSingleThreadExecutor()
    private val adapter = RowAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = intent.data
        if (source == null) {
            finish()
            return
        }
        textSizeSp = getSharedPreferences(PREFS, MODE_PRIVATE).getFloat(KEY_SIZE, 18f)
        ground = Settings.groundColor(this)
        ink = if (Settings.night(this)) Color.parseColor("#E0E0E0") else Color.parseColor("#222222")
        setContentView(buildLayout())
        setSupportActionBar(toolbar)
        toolbar.title = nameOf(source)
        toolbar.subtitle = getString(R.string.text_loading)
        toolbar.setNavigationOnClickListener { finish() }
        // A short tap without much movement: the top third goes up a screen, the bottom third down one, and the
        // middle shows or hides the bars. A drag is left to the list to scroll.
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var downAt = 0L
        list.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    downAt = e.eventTime
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(e.x - downX) + abs(e.y - downY)
                    val held = e.eventTime - downAt
                    if (moved < slop && held < 400) list.post { tapAt(e.y) }
                }
            }
            false
        }
        load(source)
    }

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ground)
        }
        toolbar = MaterialToolbar(this).apply {
            setBackgroundColor(ground)
            navigationContentDescription = getString(R.string.back)
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
        }
        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@TextReaderActivity)
            adapter = this@TextReaderActivity.adapter
            setBackgroundColor(ground)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            clipToPadding = false
        }
        seek = SeekBar(this).apply { max = 1000 }
        percent = TextView(this).apply {
            setOnClickListener { askForPage() }
            setTextColor(ink)
            textSize = 13f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(12), 0)
        }
        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(percent, LinearLayout.LayoutParams(dp(110), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val previous = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "‹"
            setOnClickListener { scrollScreen(-1) }
        }
        val next = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "›"
            setOnClickListener { scrollScreen(1) }
        }
        val settingsButton = Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = getString(R.string.settings)
            setOnClickListener { startActivity(Intent(this@TextReaderActivity, SettingsActivity::class.java)) }
        }
        val controlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(previous, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(View(this@TextReaderActivity), LinearLayout.LayoutParams(0, 0, 1f))
            addView(next, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(settingsButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ground)
            addView(progressRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(controlRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        bottomBar = bottom

        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            ensureRowHeight()
            if (!seeking) showPosition()
        }
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (!seeking) showPosition()
            }
        })
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val idx = index ?: return
                if (fromUser) jumpToByte(idx.length * progress / 1000)
            }
            override fun onStartTrackingTouch(bar: SeekBar) { seeking = true }
            override fun onStopTrackingTouch(bar: SeekBar) { seeking = false }
        })
        return root
    }

    /** Opens the file: copies it and indexes its rows, then shows it at the place it was left. */
    private fun load(source: Uri) {
        worker.execute {
            val opened = runCatching { TextIndex.open(this, source) }.getOrNull()
            runOnUiThread {
                if (isDestroyed) {
                    opened?.close()
                    return@runOnUiThread
                }
                if (opened == null) {
                    Toast.makeText(this, R.string.text_unreadable, Toast.LENGTH_LONG).show()
                    finish()
                    return@runOnUiThread
                }
                index = opened
                key = Positions.keyFor(contentResolver, source, opened.length)?.let { "b:$it" }
                Diag.log(this, "text: indexed rows=${opened.rowCount} bytes=${opened.length}")
                toolbar.subtitle = null
                adapter.notifyDataSetChanged()
                val left = key?.let { Marks.leftAt(this, it) }
                if (left != null && left in 0 until opened.length) jumpToByte(left) else showPosition()
                ensureRowHeight()
            }
        }
    }

    /**
     * Measures the average height of a row for the width and text size on the screen, from a sample of
     * rows spread through the text. The page count is worked out from it, so no row is laid out at all
     * just to count pages: the count is an estimate, which is what keeps opening a long text quick.
     */
    private fun ensureRowHeight() {
        val idx = index ?: return
        if (idx.rowCount == 0 || list.width <= 0) return
        val width = list.width - list.paddingLeft - list.paddingRight
        val size = textSizeSp
        val key = Pair(width, size)
        if (rowPxFor == key) return
        val sample = TextView(this).apply {
            setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
            textSize = size
        }
        val paint = sample.paint
        val includePad = sample.includeFontPadding
        worker.execute {
            val samples = minOf(300, idx.rowCount)
            var total = 0f
            for (s in 0 until samples) {
                val row = (s.toLong() * idx.rowCount / samples).toInt()
                val line = idx.readRow(row)
                total += StaticLayout.Builder.obtain(line, 0, line.length, paint, maxOf(1, width))
                    .setIncludePad(includePad).build().height
            }
            val avg = maxOf(1f, total / samples)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                rowPx = avg
                rowPxFor = key
                Diag.log(this, "text: row height px=${"%.1f".format(avg)} width=$width size=$size pages=${totalPages()}")
                showPosition()
            }
        }
    }

    private fun pageHeight(): Float =
        (list.height - list.paddingTop - list.paddingBottom).toFloat().coerceAtLeast(1f)

    /** How many pages the text makes, estimated from the average row height. */
    private fun totalPages(): Int {
        val idx = index ?: return 0
        if (rowPx <= 0f || idx.rowCount == 0) return 0
        return kotlin.math.ceil(idx.rowCount * rowPx / pageHeight()).toInt().coerceAtLeast(1)
    }

    /** The page on the screen, estimated from the row at the top. */
    private fun currentPage(): Int {
        val total = totalPages()
        if (total == 0) return 0
        val at = kotlin.math.floor(topRow() * rowPx / pageHeight()).toInt() + 1
        return at.coerceIn(1, total)
    }

    /** The first row of page [page]. */
    private fun rowForPage(page: Int): Int {
        val idx = index ?: return 0
        return ((page - 1) * pageHeight() / rowPx).toInt().coerceIn(0, maxOf(0, idx.rowCount - 1))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun nameOf(source: Uri): String =
        contentResolver.query(source, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: source.lastPathSegment.orEmpty()

    /** The row at the top of the screen. */
    private fun topRow(): Int {
        val lm = list.layoutManager as LinearLayoutManager
        return lm.findFirstVisibleItemPosition().coerceAtLeast(0)
    }

    /** The byte offset of the row at the top of the screen. */
    private fun topByte(): Long {
        val idx = index ?: return 0
        if (idx.rowCount == 0) return 0
        return idx.rowStart(topRow().coerceAtMost(idx.rowCount - 1))
    }

    private fun showPosition() {
        val idx = index
        if (idx == null || idx.rowCount == 0) {
            percent.text = ""
            return
        }
        val top = topRow().coerceAtMost(idx.rowCount - 1)
        if (!seeking) seek.progress = (idx.rowStart(top) * 1000 / maxOf(1L, idx.length)).toInt()
        percent.text = if (rowPx <= 0f) "… / …" else "${currentPage()} / ${totalPages()}"
    }

    /** Scrolls so the row holding byte offset [at] is at the top. */
    private fun jumpToByte(at: Long) {
        val idx = index ?: return
        if (idx.rowCount == 0) return
        val row = idx.rowAt(at.coerceIn(0, maxOf(0L, idx.length - 1)))
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(row, 0)
        list.post { showPosition() }
        showPosition()
    }

    /** Moves the text one screen up ([direction] -1) or down (1). */
    private fun scrollScreen(direction: Int) {
        val h = list.height
        if (h <= 0) return
        if (direction > 0 && atEnd()) {
            openNextInFolder()
            return
        }
        list.smoothScrollBy(0, direction * (h * 0.9f).toInt())
    }

    /** Whether the last row is on the screen: the list cannot scroll any further down. */
    private fun atEnd(): Boolean {
        if (index == null) return false
        return !list.canScrollVertically(1)
    }

    /** Opens the file after this one in the folder it was opened from. Nothing happens when there is none. */
    private fun openNextInFolder() {
        val current = intent.data ?: return
        val id = runCatching { android.provider.DocumentsContract.getDocumentId(current) }.getOrNull() ?: return
        val files = siblingsOf(this, current)
        val at = files.indexOfFirst { android.provider.DocumentsContract.getDocumentId(it) == id }
        Diag.log(this, "text: next file at=$at of ${files.size}")
        val next = files.getOrNull(at + 1)?.takeIf { at >= 0 } ?: return
        startActivity(
            Intent()
                .setClassName(this, ViewerActivity.INTERNAL_VIEWER)
                .setData(next)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
        finish()
    }

    /** A tap at [y] in the list: the top third goes up a screen, the bottom third down one, the middle the bars. */
    private fun tapAt(y: Float) {
        val h = list.height.toFloat()
        if (h <= 0f) return
        when {
            y < h / 3 -> scrollScreen(-1)
            y > h * 2 / 3 -> scrollScreen(1)
            else -> toggleFullscreen()
        }
    }

    /** Hides or shows the title and the controls, and the system bars with them. */
    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        toolbar.visibility = if (fullscreen) View.GONE else View.VISIBLE
        bottomBar.visibility = if (fullscreen) View.GONE else View.VISIBLE
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }

    /** One row of the text, read from the copy when it is shown. */
    private inner class RowAdapter : RecyclerView.Adapter<RowHolder>() {
        override fun getItemCount(): Int = index?.rowCount ?: 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder =
            RowHolder(TextView(parent.context).apply {
                setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                setTextIsSelectable(false)
            })

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            val view = holder.view
            view.setTextColor(ink)
            view.textSize = textSizeSp
            view.text = highlighted(index?.readRow(position) ?: "")
        }
    }

    private class RowHolder(val view: TextView) : RecyclerView.ViewHolder(view)

    /** [row] with every place the search query is found marked. */
    private fun highlighted(row: String): CharSequence {
        val q = query
        if (q.isNullOrEmpty()) return row
        val span = SpannableString(row)
        var at = row.indexOf(q)
        while (at >= 0) {
            span.setSpan(
                BackgroundColorSpan(Color.parseColor("#FFEB3B")),
                at, at + q.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            at = row.indexOf(q, at + q.length)
        }
        return span
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, ID_SEARCH, 0, R.string.search_in_document).setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        menu.add(0, ID_BOOKMARK_ADD, 1, R.string.add_bookmark)
        menu.add(0, ID_BOOKMARKS, 2, R.string.bookmarks)
        menu.add(0, ID_TEXT_SIZE, 3, R.string.text_size)
        menu.add(0, ID_READ_ALOUD, 4, R.string.read_aloud)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(ID_READ_ALOUD)?.title =
            getString(if (readAloud?.isPlaying == true) R.string.stop_reading else R.string.read_aloud)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            ID_SEARCH -> askForSearch()
            ID_BOOKMARK_ADD -> addBookmark()
            ID_BOOKMARKS -> showBookmarks()
            ID_TEXT_SIZE -> askForTextSize()
            ID_READ_ALOUD -> toggleReadAloud()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun askForSearch() {
        if (index == null) return
        val input = EditText(this).apply { setSingleLine(true) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.search_in_document)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> find(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Finds the next place [q] occurs after the last hit, or after the top of the screen for a new query. */
    private fun find(q: String) {
        val idx = index ?: return
        if (q.isEmpty()) return
        val needle = q.toByteArray(idx.charset)
        val from = if (q == query && lastHit != null) lastHit!! + 1 else topByte() + 1
        worker.execute {
            val at = runCatching { idx.find(needle, from) }.getOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (at == null) {
                    Toast.makeText(this, R.string.text_search_none, Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                query = q
                lastHit = at
                adapter.notifyDataSetChanged()
                jumpToByte(at)
            }
        }
    }

    private fun addBookmark() {
        val k = key ?: return
        val idx = index ?: return
        val at = topByte()
        if (Marks.bookmarks(this, k).any { it.at == at }) {
            Toast.makeText(this, R.string.bookmark_exists, Toast.LENGTH_SHORT).show()
            return
        }
        val label = idx.readRow(topRow()).take(20).trim()
        Marks.add(this, k, at, label)
        Toast.makeText(this, R.string.bookmark_added, Toast.LENGTH_SHORT).show()
    }

    private fun showBookmarks() {
        val k = key ?: return
        val marks = Marks.bookmarks(this, k)
        if (marks.isEmpty()) {
            Toast.makeText(this, R.string.no_bookmarks, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = marks.mapIndexed { i, mark ->
            if (mark.label.isEmpty()) getString(R.string.bookmark_item, i + 1) else "${i + 1}. ${mark.label}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bookmarks)
            .setItems(labels) { _, which -> jumpToByte(marks[which].at) }
            .setNeutralButton(R.string.bookmark_remove) { _, _ -> removeBookmark(k) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun removeBookmark(k: String) {
        val marks = Marks.bookmarks(this, k)
        if (marks.isEmpty()) return
        val labels = marks.mapIndexed { i, mark ->
            if (mark.label.isEmpty()) getString(R.string.bookmark_item, i + 1) else "${i + 1}. ${mark.label}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bookmark_remove)
            .setItems(labels) { _, which -> Marks.remove(this, k, marks[which].at) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Asks for a page number and goes to its first row. The count is an estimate from the row height. */
    private fun askForPage() {
        val idx = index ?: return
        if (rowPx <= 0f || idx.rowCount == 0) return
        val total = totalPages()
        val entry = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(currentPage().toString())
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.go_to_page)
            .setMessage(getString(R.string.page_range, total))
            .setView(entry)
            .setPositiveButton(R.string.go) { _, _ ->
                val n = entry.text.toString().toIntOrNull()
                if (n != null && n in 1..total) {
                    (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(rowForPage(n), 0)
                    list.post { showPosition() }
                    showPosition()
                } else {
                    Toast.makeText(this, getString(R.string.page_range, total), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askForTextSize() {
        val sizes = intArrayOf(14, 16, 18, 20, 22, 24, 28)
        val labels = sizes.map { "$it" }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.text_size)
            .setItems(labels) { _, which ->
                val at = topByte()
                textSizeSp = sizes[which].toFloat()
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(KEY_SIZE, textSizeSp).apply()
                adapter.notifyDataSetChanged()
                jumpToByte(at)
                ensureRowHeight()
            }
            .show()
    }

    /** Reads the text from the top of the screen on, a few hundred thousand characters at a time. */
    private fun toggleReadAloud() {
        val idx = index ?: return
        val reader = readAloud
        if (reader?.isPlaying == true) {
            reader.stop()
            return
        }
        if (idx.rowCount == 0) return
        val sb = StringBuilder()
        var row = topRow()
        while (row < idx.rowCount && sb.length < READ_AHEAD_CHARS) {
            sb.append(idx.readRow(row)).append('\n')
            row++
        }
        val fresh = ReadAloud(this) { invalidateOptionsMenu() }
        readAloud = fresh
        fresh.start(sb.toString())
        invalidateOptionsMenu()
    }

    override fun onResume() {
        super.onResume()
        // The size may have been changed in Settings while this screen was away
        val size = getSharedPreferences(PREFS, MODE_PRIVATE).getFloat(KEY_SIZE, 18f)
        if (size != textSizeSp) {
            textSizeSp = size
            adapter.notifyDataSetChanged()
        }
        ensureRowHeight()
    }

    override fun onStop() {
        readAloud?.stop()
        val k = key
        if (k != null && index != null) Marks.setLeftAt(this, k, topByte())
        super.onStop()
    }

    override fun onDestroy() {
        readAloud?.release()
        readAloud = null
        worker.shutdownNow()
        index?.close()
        index = null
        super.onDestroy()
    }

    private companion object {
        const val PREFS = "text_reader"
        const val KEY_SIZE = "size_sp"
        const val READ_AHEAD_CHARS = 200_000
        const val ID_SEARCH = 1
        const val ID_BOOKMARK_ADD = 2
        const val ID_BOOKMARKS = 3
        const val ID_TEXT_SIZE = 4
        const val ID_READ_ALOUD = 5
    }
}
