package kr.iamtalker.waterview

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import kotlin.math.abs
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.util.concurrent.Executors

/**
 * A plain text file, read on its own screen. The text is kept whole, in memory, and drawn a piece at
 * a time in a list, so scrolling never waits on a read: the whole file is in hand once it has
 * opened. The place it was left at, its bookmarks, its search and its reading aloud all count
 * characters of that whole text, so they agree with one another.
 *
 * Files over [MAX_BYTES] are read from their start only, which is said on the screen.
 */
class TextReaderActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var list: RecyclerView
    private lateinit var seek: SeekBar
    private lateinit var percent: TextView
    private lateinit var bottomBar: View
    private var fullscreen = false

    private var text: String = ""
    /** Where each page starts, for the screen size and text size it was measured at. See ensurePages. */
    private var pageStarts = IntArray(0)
    private var pagesFor: Pair<Int, Float>? = null
    private val WINDOW = 4000
    private var key: String? = null
    private var highlight: IntRange? = null
    private var readAloud: ReadAloud? = null
    private var seeking = false
    private var textSizeSp = 18f

    private val loader = Executors.newSingleThreadExecutor()
    /** Page measuring, on its own thread so a long file never holds up the reading or the search. */
    private val pager = Executors.newSingleThreadExecutor()
    private val adapter = ChunkAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val source = intent.data
        if (source == null) {
            finish()
            return
        }
        textSizeSp = getSharedPreferences(PREFS, MODE_PRIVATE).getFloat(KEY_SIZE, 18f)
        val ground = Settings.groundColor(this)
        val ink = if (Settings.night(this)) Color.parseColor("#E0E0E0") else Color.parseColor("#222222")
        adapter.ink = ink
        setContentView(buildLayout(ground))
        setSupportActionBar(toolbar)
        toolbar.title = nameOf(source)
        toolbar.setNavigationOnClickListener { finish() }
        // A short tap without much movement: the top third of the text goes up a screen, the bottom third
        // down one, and the middle does nothing. A drag is left to the list to scroll.
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
                    val isTap = moved < slop && held < 400
                    // Posted rather than run now: the list's own touch handling for this release would cancel a scroll started here
                    if (isTap) list.post { tapAt(e.y) }
                }
            }
            false
        }
        load(source)
    }

    private fun buildLayout(ground: Int): View {
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
            setOnClickListener { askForPercent() }
            setTextColor(adapter.ink)
            textSize = 13f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(12), 0)
        }
        // Two rows, as the PDF viewer has them: the place along the text, then the controls
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

        // One page per item: the list settles on a whole page after each move
        PagerSnapHelper().attachToRecyclerView(list)
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (!seeking) showPosition()
            }
        })
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && text.isNotEmpty()) jumpTo(text.length.toLong() * progress / 1000)
            }
            override fun onStartTrackingTouch(bar: SeekBar) { seeking = true }
            override fun onStopTrackingTouch(bar: SeekBar) { seeking = false }
        })
        return root
    }

    /** Moves the text one screen up ([direction] -1) or down (1). */
    private fun scrollScreen(direction: Int) {
        if (pageStarts.isEmpty()) return
        val end = atEnd()
        Diag.log(this, "text: step dir=$direction atEnd=$end pages=${pageStarts.size}")
        if (direction > 0 && end) {
            openNextInFolder()
            return
        }
        goToPage(currentPageIndex() + direction)
    }

    /** The page on the screen: the list shows one page per item, snapped, so it is the item that is shown. */
    private fun currentPageIndex(): Int {
        val lm = list.layoutManager as LinearLayoutManager
        val at = lm.findFirstCompletelyVisibleItemPosition().takeIf { it >= 0 } ?: lm.findFirstVisibleItemPosition()
        return at.coerceAtLeast(0)
    }

    private fun goToPage(index: Int) {
        if (pageStarts.isEmpty()) return
        list.smoothScrollToPosition(index.coerceIn(0, pageStarts.size - 1))
    }

    /** Whether the page on the screen is the last one. */
    private fun atEnd(): Boolean {
        if (pageStarts.isEmpty()) return false
        return currentPageIndex() >= pageStarts.size - 1
    }

    /** Opens the file after this one in the folder it was opened from. Nothing happens when there is none. */
    private fun openNextInFolder() {
        val current = intent.data ?: return
        val id = runCatching { android.provider.DocumentsContract.getDocumentId(current) }.getOrNull() ?: return
        val files = siblingsOf(this, current)
        val at = files.indexOfFirst { android.provider.DocumentsContract.getDocumentId(it) == id }
        Diag.log(this, "text: next file at=$at of ${files.size} pages=${pageStarts.size}")
        val next = files.getOrNull(at + 1)?.takeIf { at >= 0 } ?: return
        startActivity(
            Intent()
                .setClassName(this, ViewerActivity.INTERNAL_VIEWER)
                .setData(next)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
        finish()
    }

    /** A tap at [y] in the list: the top third goes up a screen, the bottom third down one, the middle nothing. */
    private fun tapAt(y: Float) {
        val h = list.height.toFloat()
        if (h <= 0f) return
        when {
            y < h / 3 -> goToPage(currentPageIndex() - 1)
            y > h * 2 / 3 -> if (atEnd()) openNextInFolder() else goToPage(currentPageIndex() + 1)
            else -> toggleFullscreen()
        }
    }

    /** Hides or shows the title and the controls, and the system bars with them: the middle of the text, tapped. */
    private fun toggleFullscreen() {
        fullscreen = !fullscreen
        toolbar.visibility = if (fullscreen) View.GONE else View.VISIBLE
        bottomBar.visibility = if (fullscreen) View.GONE else View.VISIBLE
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun nameOf(source: Uri): String =
        contentResolver.query(source, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: source.lastPathSegment.orEmpty()

    /** Reads and decodes the file off the main thread, then shows it at the place it was left. */
    private fun load(source: Uri) {
        loader.execute {
            val bytes = runCatching {
                contentResolver.openInputStream(source)?.use { it.readNBytes(MAX_BYTES.toInt()) }
            }.getOrNull()
            val decoded = bytes?.let { decode(it) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (decoded == null) {
                    Toast.makeText(this, R.string.text_unreadable, Toast.LENGTH_LONG).show()
                    finish()
                    return@runOnUiThread
                }
                text = decoded

                Diag.log(this, "text: opened chars=${decoded.length} bytes=${bytes?.size}")
                key = bytes?.let { Positions.keyFor(contentResolver, source, it.size.toLong()) }
                adapter.notifyDataSetChanged()
                // The pages are measured first, so the place it was left at is found with the same page count
                ensurePages {
                    val left = key?.let { Marks.leftAt(this, it) }
                    if (left != null && left in 0 until text.length.toLong()) jumpTo(left)
                    else showPosition()
                }
                if ((bytes?.size?.toLong() ?: 0L) >= MAX_BYTES) {
                    Toast.makeText(this, getString(R.string.text_truncated, MAX_BYTES / (1024 * 1024)), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /** UTF-8 when the bytes are valid UTF-8, else the Korean code page. */
    private fun decode(bytes: ByteArray): String =
        try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, runCatching { Charset.forName("x-windows-949") }.getOrElse { Charset.forName("EUC-KR") })
        }


    /** The character at the top of the screen. */
    private fun topOffset(): Int {
        if (pageStarts.isEmpty()) return 0
        return pageStarts[currentPageIndex().coerceIn(0, pageStarts.size - 1)]
    }

    private fun showPosition() {
        if (text.isEmpty()) {
            percent.text = ""
            return
        }
        val at = topOffset()
        val fraction = at.toLong() * 1000 / text.length
        if (!seeking) seek.progress = fraction.toInt()
        val (current, total) = screenPages()
        // No pages yet while they are measured: say so rather than show a count that is wrong
        percent.text = if (total == 0) "… / …" else "$current / $total"
    }

    /** The screen the text is on, and how many screens the whole text makes, a screen counting as a page. */
    /**
     * Where each page starts, for the size of the screen and the text size: as many whole lines as fit
     * on a screen, measured once here so the page count is the same every time it is shown. Done off the
     * main thread; [then] runs on it once the pages are known, and again if the size changes.
     */
    private fun ensurePages(then: () -> Unit) {
        val width = list.width - dp(32)
        val height = list.height - dp(16)
        if (width <= 0 || height <= 0) {
            list.post { ensurePages(then) }
            return
        }
        if (pagesFor == Pair(width, textSizeSp) && pageStarts.isNotEmpty()) {
            then()
            return
        }
        val snapshot = text
        val size = textSizeSp
        val cacheKey = "${snapshot.hashCode()}:${snapshot.length}:$width:$size"
        // Kept for the process, so reopening the same text at the same size does not measure it again
        if (PageCache.key == cacheKey) {
            pageStarts = PageCache.starts
            adapter.notifyDataSetChanged()
            pagesFor = Pair(width, size)
            then()
            return
        }
        pager.execute {
            Diag.log(this, "text: pages start chars=${snapshot.length} w=$width h=$height size=$size")
            val breaks = runCatching { computePages(snapshot, width, height, size) }
                .onFailure { Diag.log(this, "text: pages failed ${it.javaClass.simpleName} ${it.message}") }
                .getOrDefault(IntArray(0))
            Diag.log(this, "text: pages done count=${breaks.size}")
            // Kept even if the screen has gone meanwhile, so the next opening has them at once
            if (breaks.isNotEmpty()) {
                PageCache.key = cacheKey
                PageCache.starts = breaks
            }
            runOnUiThread {
                if (isDestroyed || snapshot != text) {
                    Diag.log(this, "text: pages dropped destroyed=$isDestroyed changed=${snapshot != text}")
                    return@runOnUiThread
                }
                pageStarts = breaks
                adapter.notifyDataSetChanged()
                pagesFor = Pair(width, size)
                then()
            }
        }
    }

    /** Where each page starts: as many whole lines as fit in [height], measured a window at a time. */
    private fun computePages(s: String, width: Int, height: Int, size: Float): IntArray {
        val paint = TextPaint().apply {
            textSize = android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, size, resources.displayMetrics
            )
            typeface = Typeface.MONOSPACE
        }
        val out = ArrayList<Int>()
        var at = 0
        while (at < s.length) {
            out.add(at)
            val windowEnd = minOf(s.length, at + WINDOW)
            val layout = StaticLayout.Builder.obtain(s, at, windowEnd, paint, width).setIncludePad(false).build()
            var fit = 0
            for (line in 0 until layout.lineCount) {
                if (layout.getLineBottom(line) > height) break
                fit = line
            }
            var end = layout.getLineEnd(fit)
            if (end <= at) end = minOf(windowEnd, at + 1)
            at = end
        }
        return out.toIntArray()
    }

    /** The last of [points] at or before [offset], by a search, so a page or piece is found in a few steps. */
    /** Where page [i] ends: where the next starts, or the end of the text for the last. */
    private fun pageEnd(i: Int): Int = if (i + 1 < pageStarts.size) pageStarts[i + 1] else text.length

    private fun indexAtOrBefore(points: IntArray, offset: Int): Int {
        if (points.isEmpty()) return 0
        var lo = 0
        var hi = points.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (points[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    private fun screenPages(): Pair<Int, Int> {
        // Fixed once per text size from the screen's size (see ensurePages), so the total does not drift
        if (pageStarts.isEmpty()) return Pair(0, 0)
        return Pair(indexAtOrBefore(pageStarts, topOffset()) + 1, pageStarts.size)
    }

    private fun jumpTo(offset: Long) {
        if (pageStarts.isEmpty()) return
        val at = offset.coerceIn(0, text.length.toLong() - 1).toInt()
        // The page holding that character; the list snaps to whole pages
        list.scrollToPosition(indexAtOrBefore(pageStarts, at))
        showPosition()
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
        if (text.isEmpty()) return
        val input = EditText(this).apply { setSingleLine(true) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.search_in_document)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> find(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Finds the next place [query] occurs after the top of the screen, wrapping to the start. */
    private fun find(query: String) {
        if (query.isEmpty()) return
        val from = (highlight?.last ?: topOffset()) + 1
        var at = text.indexOf(query, from.coerceAtMost(text.length), ignoreCase = true)
        if (at < 0) at = text.indexOf(query, 0, ignoreCase = true)
        if (at < 0) {
            Toast.makeText(this, R.string.text_search_none, Toast.LENGTH_SHORT).show()
            return
        }
        highlight = at until at + query.length
        adapter.notifyDataSetChanged()
        jumpTo(at.toLong())
    }

    private fun addBookmark() {
        val k = key ?: return
        val at = topOffset().toLong()
        if (Marks.bookmarks(this, k).any { it.at == at }) {
            Toast.makeText(this, R.string.bookmark_exists, Toast.LENGTH_SHORT).show()
            return
        }
        Marks.add(this, k, at, snippetAt(at.toInt()))
        Toast.makeText(this, R.string.bookmark_added, Toast.LENGTH_SHORT).show()
    }

    private fun snippetAt(at: Int): String =
        text.substring(at, minOf(text.length, at + 20)).replace('\n', ' ').trim()

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
            .setItems(labels) { _, which -> jumpTo(marks[which].at) }
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

    /** The place along the text, as a percent, asked for and gone to. */
    private fun askForPercent() {
        if (text.isEmpty()) return
        val (current, total) = screenPages()
        val entry = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
            setSelection(text.length)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.go_to_page)
            .setMessage(getString(R.string.page_range, total))
            .setView(entry)
            .setPositiveButton(R.string.go) { _, _ ->
                val p = entry.text.toString().toIntOrNull()
                if (p != null && p in 1..total) {
                    // The page's own start, as measured for this screen
                    jumpTo(pageStarts.getOrElse(p - 1) { 0 }.toLong())
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
                textSizeSp = sizes[which].toFloat()
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(KEY_SIZE, textSizeSp).apply()
                adapter.size = textSizeSp
                adapter.notifyDataSetChanged()
                val at = topOffset().toLong()
                ensurePages { jumpTo(at) }
            }
            .show()
    }

    private fun toggleReadAloud() {
        val reader = readAloud
        if (reader?.isPlaying == true) {
            reader.stop()
            return
        }
        if (text.isEmpty()) return
        val from = topOffset()
        val fresh = ReadAloud(this) { invalidateOptionsMenu() }
        readAloud = fresh
        fresh.start(text.substring(from))
        invalidateOptionsMenu()
    }

    override fun onResume() {
        super.onResume()
        // The size may have been changed in Settings while this screen was away
        val size = getSharedPreferences(PREFS, MODE_PRIVATE).getFloat(KEY_SIZE, 18f)
        if (size != textSizeSp) {
            textSizeSp = size
            adapter.size = size
            adapter.notifyDataSetChanged()
            ensurePages { showPosition() }
        }
    }

    override fun onStop() {
        readAloud?.stop()
        val k = key
        if (k != null && text.isNotEmpty()) Marks.setLeftAt(this, k, topOffset().toLong())
        super.onStop()
    }

    override fun onDestroy() {
        readAloud?.release()
        readAloud = null
        loader.shutdown()
        pager.shutdownNow()
        super.onDestroy()
    }

    /** One piece of the text, drawn as a line of its own in the list. */
    private inner class ChunkAdapter : RecyclerView.Adapter<ChunkHolder>() {
        var ink: Int = Color.BLACK
        var size: Float = textSizeSp

        // One item is one page, as measured for the screen, so an item always fits on the screen
        override fun getItemCount(): Int = pageStarts.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChunkHolder =
            ChunkHolder(TextView(parent.context).apply {
                setTextColor(ink)
                setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
                setTextIsSelectable(false)
            })

        override fun onBindViewHolder(holder: ChunkHolder, position: Int) {
            val view = holder.view
            view.setTextColor(ink)
            view.textSize = textSizeSp
            val from = pageStarts[position]
            val to = pageEnd(position)
            val piece = SpannableString(text.substring(from, to))
            highlight?.let { h ->
                val a = maxOf(h.first, from)
                val b = minOf(h.last + 1, to)
                if (a < b) {
                    piece.setSpan(
                        BackgroundColorSpan(Color.parseColor("#FFEB3B")),
                        a - from, b - from, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            }
            view.text = piece
        }
    }

    private class ChunkHolder(val view: TextView) : RecyclerView.ViewHolder(view)

    /** The page starts of the last text measured, with the key it was measured under. See ensurePages. */
    private object PageCache {
        @Volatile var key: String? = null
        @Volatile var starts = IntArray(0)
    }

    private companion object {
        const val PREFS = "text_reader"
        const val KEY_SIZE = "size_sp"
        const val MAX_BYTES = 20L * 1024 * 1024
        const val PIECE = 1200
        const val ID_SEARCH = 1
        const val ID_BOOKMARK_ADD = 2
        const val ID_BOOKMARKS = 3
        const val ID_TEXT_SIZE = 4
        const val ID_READ_ALOUD = 5
    }
}
