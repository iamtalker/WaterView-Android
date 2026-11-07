package kr.iamtalker.waterview

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Where a text file was left, and the bookmarks put in it. Kept the way [Positions] keeps a
 * PDF's page: under a fingerprint of the file's own bytes, in the no-backup folder, through
 * AtomicFile. The reasons are written out there and hold here too.
 *
 * A place is a count of characters from the start of the text as it is decoded, not of bytes,
 * so it is the same at any text size and whichever way the phone is held. It is only good for
 * as long as the file reads the same way, which the fingerprint's length and head already
 * ensure: a file saved again with changes starts from the top.
 *
 * One line per document: the key, the place it was left at (-1 for none), and its bookmarks,
 * comma separated and in the order they were added. A bookmark is its place, and after a tilde
 * the words that were there, encoded so that they cannot break the line.
 */
object Marks {

    private const val FILE_NAME = "marks"

    /** More documents than anybody reads at once, and a few kilobytes at most. */
    private const val MAX_DOCUMENTS = 100

    /** Bookmarks kept for one document. */
    private const val MAX_BOOKMARKS = 100

    /** A bookmark: the character it is at, and the words there, which name it in the list. */
    data class Bookmark(val at: Long, val label: String = "")

    private class Entry(val key: String, val left: Long, val bookmarks: List<Bookmark>)

    /** The character the document under [key] was left at, or null for its start. */
    fun leftAt(context: Context, key: String): Long? =
        load(context).firstOrNull { it.key == key }?.left?.takeIf { it > 0 }

    /** The bookmarks in the document under [key], oldest first. */
    fun bookmarks(context: Context, key: String): List<Bookmark> =
        load(context).firstOrNull { it.key == key }?.bookmarks.orEmpty()

    /** Remember that the document under [key] was left at [at]. Zero is the start, and kept as such. */
    fun setLeftAt(context: Context, key: String, at: Long) {
        val all = load(context)
        val old = all.firstOrNull { it.key == key }
        if (old == null && at <= 0) return
        write(context, replace(all, key, Entry(key, at, old?.bookmarks.orEmpty())))
    }

    /** Add a bookmark at [at], named by [label], in the document under [key]. */
    fun add(context: Context, key: String, at: Long, label: String = "") {
        val all = load(context)
        val old = all.firstOrNull { it.key == key }
        val marks = (old?.bookmarks.orEmpty() + Bookmark(at, label)).takeLast(MAX_BOOKMARKS)
        write(context, replace(all, key, Entry(key, old?.left ?: -1, marks)))
    }

    /** Take the bookmark at [at] out of the document under [key]. */
    fun remove(context: Context, key: String, at: Long) {
        val all = load(context)
        val old = all.firstOrNull { it.key == key } ?: return
        write(context, replace(all, key, Entry(key, old.left, old.bookmarks.filterNot { it.at == at })))
    }

    /**
     * The list with [entry] in place of the one for its document, moved to the end so the
     * most recently changed is last. Once there are [MAX_DOCUMENTS], the first one goes. An entry
     * with nothing left in it is not kept at all.
     */
    private fun replace(all: List<Entry>, key: String, entry: Entry): List<Entry> {
        val others = all.filter { it.key != key }
        val kept = if (entry.left <= 0 && entry.bookmarks.isEmpty()) others else others + entry
        return kept.takeLast(MAX_DOCUMENTS)
    }

    private fun write(context: Context, entries: List<Entry>) {
        val text = entries.joinToString("") { entry ->
            val marks = entry.bookmarks.joinToString(",") { mark ->
                if (mark.label.isEmpty()) "${mark.at}"
                else "${mark.at}~" + URLEncoder.encode(mark.label, "UTF-8")
            }
            "${entry.key} ${entry.left} $marks\n"
        }
        val file = file(context)
        val out = runCatching { file.startWrite() }.getOrNull() ?: return
        runCatching {
            out.write(text.toByteArray())
            file.finishWrite(out)
        }.onFailure { file.failWrite(out) }
    }

    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))

    private fun load(context: Context): List<Entry> = runCatching {
        String(file(context).readFully()).lines().mapNotNull { line ->
            val parts = line.split(' ')
            if (parts.size < 2 || parts[0].isEmpty()) return@mapNotNull null
            val left = parts[1].toLongOrNull() ?: return@mapNotNull null
            val marks = parts.getOrNull(2).orEmpty().split(',').mapNotNull { token ->
                val at = token.substringBefore('~').toLongOrNull()?.takeIf { it >= 0 }
                    ?: return@mapNotNull null
                val label = if (token.contains('~')) {
                    runCatching { URLDecoder.decode(token.substringAfter('~'), "UTF-8") }
                        .getOrDefault("")
                } else ""
                Bookmark(at, label)
            }
            Entry(parts[0], left, marks)
        }
    }.getOrDefault(emptyList())
}
