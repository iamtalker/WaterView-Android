package kr.iamtalker.waterview

import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
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

    private var text: String = ""
    private var starts = IntArray(0)
    private var key: String? = null
    private var highlight: IntRange? = null
    private var readAloud: ReadAloud? = null
    private var seeking = false
    private var textSizeSp = 18f

    private val loader = Executors.newSingleThreadExecutor()
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
        // A tap in the top third of the text goes up a screen, in the bottom third down one; the middle does nothing
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            // A tap is only reported when the press itself is taken, which the default does not do
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val h = list.height.toFloat()
                val step = (h * 0.9f).toInt()
                when {
                    e.y < h / 3 -> list.smoothScrollBy(0, -step)
                    e.y > h * 2 / 3 -> list.smoothScrollBy(0, step)
                }
                return true
            }
        })
        list.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                taps.onTouchEvent(e)
                return false
            }
        })
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
            setTextColor(adapter.ink)
            textSize = 13f
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(12), 0)
        }
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(ground)
            addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(percent, LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(toolbar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

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
                starts = pieceStarts(decoded)
                key = bytes?.let { Positions.keyFor(contentResolver, source, it.size.toLong()) }
                adapter.notifyDataSetChanged()
                val left = key?.let { Marks.leftAt(this, it) }
                if (left != null && left in 0 until text.length.toLong()) jumpTo(left)
                else showPosition()
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

    /** Where each piece starts, cut at a line break near [PIECE] characters where one is there. */
    private fun pieceStarts(s: String): IntArray {
        val out = ArrayList<Int>()
        var start = 0
        while (start < s.length) {
            out.add(start)
            var end = minOf(s.length, start + PIECE)
            if (end < s.length) {
                val nl = s.lastIndexOf('\n', end)
                if (nl > start) end = nl + 1
            }
            start = end
        }
        return out.toIntArray()
    }

    private fun pieceEnd(i: Int): Int = if (i + 1 < starts.size) starts[i + 1] else text.length

    /** The piece holding the character at [offset]. */
    private fun pieceOf(offset: Int): Int {
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** The character at the top of the screen. */
    private fun topOffset(): Int {
        if (starts.isEmpty()) return 0
        val first = (list.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
        return starts[first.coerceIn(0, starts.size - 1)]
    }

    private fun showPosition() {
        if (text.isEmpty()) {
            percent.text = ""
            return
        }
        val at = topOffset()
        val fraction = at.toLong() * 1000 / text.length
        if (!seeking) seek.progress = fraction.toInt()
        percent.text = "${fraction / 10}.${fraction % 10}%"
    }

    private fun jumpTo(offset: Long) {
        if (starts.isEmpty()) return
        val at = offset.coerceIn(0, text.length.toLong() - 1).toInt()
        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pieceOf(at), 0)
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
        super.onDestroy()
    }

    /** One piece of the text, drawn as a line of its own in the list. */
    private inner class ChunkAdapter : RecyclerView.Adapter<ChunkHolder>() {
        var ink: Int = Color.BLACK
        var size: Float = textSizeSp

        override fun getItemCount(): Int = starts.size

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
            val from = starts[position]
            val to = pieceEnd(position)
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
