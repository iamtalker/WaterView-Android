package kr.iamtalker.waterview

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * A plain text file, read by row from a copy on the phone, so the whole text is never held in memory.
 *
 * On opening, the file is copied to the app's cache and scanned once for where each row starts. A row
 * is a line, or a piece of a line too long to show at once. After that a row is read from the copy only
 * when it is shown, so opening and scrolling cost the same whatever the size of the file. Every place in
 * the text is a byte offset into the copy, which is the same from one opening to the next.
 */
internal class TextIndex private constructor(
    private val path: File,
    private val file: RandomAccessFile,
    val charset: Charset,
    private val starts: LongArray,
    val rowCount: Int,
    val length: Long,
) {
    /** Byte offset where row [i] starts. */
    fun rowStart(i: Int): Long = starts[i]

    /** The row holding byte offset [at]: the last row that starts at or before it. */
    fun rowAt(at: Long): Int {
        if (rowCount == 0) return 0
        var lo = 0
        var hi = rowCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= at) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** The text of row [i], without its line break. Only called on the thread that owns the reader. */
    @Synchronized
    fun readRow(i: Int): String {
        if (i !in 0 until rowCount) return ""
        val from = starts[i]
        val to = if (i + 1 < rowCount) starts[i + 1] else length
        val buf = ByteArray((to - from).toInt())
        file.seek(from)
        file.readFully(buf)
        return String(buf, charset).trimEnd('\n', '\r')
    }

    /**
     * The byte offset of the first match of [needle] at or after [from], or else from the start of the
     * text. Read through its own file handle, so a search never waits on the rows being shown.
     */
    fun find(needle: ByteArray, from: Long): Long? {
        if (needle.isEmpty()) return null
        RandomAccessFile(path, "r").use { raf ->
            return scan(raf, needle, from, length) ?: scan(raf, needle, 0, from)
        }
    }

    /** Matches that start in [from, to), read a megabyte at a time. */
    private fun scan(raf: RandomAccessFile, needle: ByteArray, from: Long, to: Long): Long? {
        val chunk = 1 shl 20
        val overlap = needle.size - 1
        val buf = ByteArray(chunk + overlap)
        var pos = from
        while (pos < to && pos < length) {
            val n = minOf((chunk + overlap).toLong(), length - pos).toInt()
            raf.seek(pos)
            raf.readFully(buf, 0, n)
            val limit = minOf(to - pos, (n - needle.size + 1).toLong(), chunk.toLong()).toInt()
            for (i in 0 until limit) {
                var same = true
                for (j in needle.indices) {
                    if (buf[i + j] != needle[j]) {
                        same = false
                        break
                    }
                }
                if (same) return pos + i
            }
            pos += chunk
        }
        return null
    }

    @Synchronized
    fun close() {
        runCatching { file.close() }
    }

    companion object {
        /** A line longer than this is shown in pieces of about this size, so one row never gets huge. */
        private const val MAX_ROW = 16 * 1024
        private const val COPY_NAME = "text-reader.txt"

        /**
         * Copies [source] to the cache and indexes its rows. Null when it cannot be read. Runs off the main
         * thread: the copy and the scan both read the whole file once.
         */
        fun open(context: Context, source: Uri): TextIndex? {
            val copy = File(context.cacheDir, COPY_NAME)
            val copied = runCatching {
                context.contentResolver.openInputStream(source)?.use { input ->
                    copy.outputStream().use { input.copyTo(it, 1 shl 20) }
                    true
                } ?: false
            }.getOrDefault(false)
            if (!copied) return null

            val length = copy.length()
            val head = ByteArray(minOf(length, 1L shl 20).toInt())
            FileInputStream(copy).use { input ->
                var got = 0
                while (got < head.size) {
                    val n = input.read(head, got, head.size - got)
                    if (n < 0) break
                    got += n
                }
            }
            val bom = head.size >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()
            // A sample cut in the middle of a character is not a mistake: only the sample's whole part is checked
            val wholeHead = head.size.toLong() == length
            val checked = if (wholeHead) head.size else maxOf(0, head.size - 3)
            val utf8 = isUtf8(head, checked)
            val charset = if (utf8) Charsets.UTF_8
            else runCatching { Charset.forName("x-windows-949") }.getOrElse { Charset.forName("EUC-KR") }

            var starts = LongArray(1024)
            var count = 0
            fun add(at: Long) {
                if (count == starts.size) starts = starts.copyOf(count * 2)
                starts[count++] = at
            }
            if (length > 0) {
                add(if (bom && utf8) 3L else 0L)
                var rowBytes = 0
                var pos = 0L
                BufferedInputStream(FileInputStream(copy), 1 shl 20).use { input ->
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        for (k in 0 until n) {
                            val b = buf[k].toInt()
                            val at = pos + k
                            if (b == '\n'.code) {
                                rowBytes = 0
                                if (at + 1 < length) add(at + 1)
                                continue
                            }
                            // A long line is cut before a character's first byte, never inside one
                            if (rowBytes >= MAX_ROW && (!utf8 || (b and 0xC0) != 0x80)) {
                                add(at)
                                rowBytes = 0
                            }
                            rowBytes++
                        }
                        pos += n
                    }
                }
            }
            val raf = RandomAccessFile(copy, "r")
            return TextIndex(copy, raf, charset, starts.copyOf(count), count, length)
        }

        /** Whether the first [checked] bytes are valid UTF-8. */
        private fun isUtf8(head: ByteArray, checked: Int): Boolean {
            if (checked <= 0) return true
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            // Not the end of the input: a character cut at the sample's end is left unread, not called wrong
            val result = decoder.decode(ByteBuffer.wrap(head, 0, checked), CharBuffer.allocate(checked + 1), false)
            return !result.isError
        }
    }
}
