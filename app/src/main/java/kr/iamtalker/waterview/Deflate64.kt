package kr.iamtalker.waterview

import java.io.EOFException
import java.io.InputStream
import java.util.zip.ZipException

/**
 * Deflate64, which a zip records as method 9 and PKWARE calls enhanced deflating. Issue #30.
 *
 * It is deflate with three changes: a window of 64 KB rather than 32, deflate's two unused
 * distance codes given the reach into it, and the last length code taking sixteen extra bits
 * rather than meaning 258, so that one match can repeat up to 65,538 bytes. Windows' own Send
 * to compressed folder writes it for any file over 2 GB, and 7-Zip on request. zlib has never
 * read it, so neither can java.util.zip, and this is the whole of it: an inflater written
 * from RFC 1951 with those three changes, and nothing else.
 *
 * One table lookup per symbol. Each code's table is as wide as its longest code, so the next
 * that many bits index straight to the symbol and how many of the bits it used.
 */
internal class Deflate64(private val input: InputStream) : InputStream() {

    /** The last 64 KB out, which is as far back as a match can reach. */
    private val window = ByteArray(WINDOW)

    /** Where the next byte out goes in [window]. */
    private var at = 0

    /** How much has come out, up to the window's size: how far back a match may reach yet. */
    private var filled = 0

    private val buffer = ByteArray(BUFFER)
    private var bufferAt = 0
    private var bufferEnd = 0
    private var inputEnded = false

    /** Bits read and not yet used, the next one lowest, and how many there are. */
    private var bits = 0L
    private var bitCount = 0

    private var state = HEADER
    private var lastBlock = false
    private var storedLeft = 0
    private var copyLeft = 0
    private var copyDistance = 0
    private var literals: Huffman? = null
    private var distances: Huffman? = null

    /**
     * Where a dynamic block's codes are built. Every block has its own, and a large file has
     * thousands of blocks, so the tables are made once and built into again rather than
     * allocated each time: at the full fifteen bits they are 128 KB apiece.
     */
    private val lengthCode = Huffman(7)
    private val dynamicLiterals = Huffman(15)
    private val dynamicDistances = Huffman(15)

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var n = 0
        while (n < len && state != DONE) {
            when (state) {
                HEADER -> header()
                STORED -> n += stored(b, off + n, len - n)
                else -> n += codes(b, off + n, len - n)
            }
        }
        return if (n == 0) -1 else n
    }

    override fun close() = input.close()

    private fun header() {
        if (lastBlock) {
            state = DONE
            return
        }
        lastBlock = take(1) == 1
        when (take(2)) {
            0 -> {
                // Stored as it is: the rest of this byte is padding, then its length twice over,
                // the second time inverted
                val spare = bitCount % 8
                bits = bits ushr spare
                bitCount -= spare
                val length = take(16)
                if (take(16) != length.inv() and 0xFFFF) throw ZipException("damaged deflate64 data")
                storedLeft = length
                state = STORED
            }
            1 -> {
                literals = FIXED_LITERALS
                distances = FIXED_DISTANCES
                state = CODES
            }
            2 -> {
                readTables()
                state = CODES
            }
            else -> throw ZipException("damaged deflate64 data")
        }
    }

    private fun stored(b: ByteArray, off: Int, len: Int): Int {
        val want = minOf(len, storedLeft)
        var n = 0
        // Whole bytes already taken into the bit buffer come first
        while (n < want && bitCount >= 8) {
            put(b, off + n, take(8).toByte())
            n++
        }
        while (n < want) {
            if (bufferAt == bufferEnd && !refill()) throw EOFException("deflate64 data ends early")
            val run = minOf(want - n, bufferEnd - bufferAt)
            for (i in 0 until run) put(b, off + n + i, buffer[bufferAt + i])
            bufferAt += run
            n += run
        }
        storedLeft -= n
        if (storedLeft == 0) state = HEADER
        return n
    }

    private fun codes(b: ByteArray, off: Int, len: Int): Int {
        val literals = literals!!
        var n = 0
        while (n < len) {
            if (copyLeft > 0) {
                val run = minOf(copyLeft, len - n)
                var from = (at - copyDistance) and MASK
                for (i in 0 until run) {
                    put(b, off + n + i, window[from])
                    from = (from + 1) and MASK
                }
                n += run
                copyLeft -= run
                continue
            }
            val symbol = decode(literals)
            when {
                symbol < 256 -> put(b, off + n++, symbol.toByte())
                symbol == 256 -> {
                    state = HEADER
                    return n
                }
                symbol <= 285 -> {
                    val code = symbol - 257
                    val length = LENGTH_BASE[code] + take(LENGTH_EXTRA[code])
                    val d = decode(distances ?: throw ZipException("damaged deflate64 data"))
                    val distance = DISTANCE_BASE[d] + take(DISTANCE_EXTRA[d])
                    if (distance > filled) throw ZipException("damaged deflate64 data")
                    copyLeft = length
                    copyDistance = distance
                }
                else -> throw ZipException("damaged deflate64 data")
            }
        }
        return n
    }

    /** One byte out: to the reader, and into the window for matches to come. */
    private fun put(b: ByteArray, i: Int, value: Byte) {
        b[i] = value
        window[at] = value
        at = (at + 1) and MASK
        if (filled < WINDOW) filled++
    }

    /** The code lengths a dynamic block starts with, themselves Huffman coded. */
    private fun readTables() {
        val literalCount = take(5) + 257
        val distanceCount = take(5) + 1
        val lengthCodeCount = take(4) + 4
        if (literalCount > 286) throw ZipException("damaged deflate64 data")
        val lengthCodeLengths = IntArray(19)
        for (i in 0 until lengthCodeCount) lengthCodeLengths[ORDER[i]] = take(3)
        lengthCode.build(lengthCodeLengths)

        val lengths = IntArray(literalCount + distanceCount)
        var i = 0
        while (i < lengths.size) {
            val symbol = decode(lengthCode)
            if (symbol < 16) {
                lengths[i++] = symbol
                continue
            }
            val value: Int
            val repeat: Int
            when (symbol) {
                16 -> {
                    if (i == 0) throw ZipException("damaged deflate64 data")
                    value = lengths[i - 1]
                    repeat = 3 + take(2)
                }
                17 -> {
                    value = 0
                    repeat = 3 + take(3)
                }
                else -> {
                    value = 0
                    repeat = 11 + take(7)
                }
            }
            if (i + repeat > lengths.size) throw ZipException("damaged deflate64 data")
            repeat(repeat) { lengths[i++] = value }
        }
        if (lengths[256] == 0) throw ZipException("damaged deflate64 data")
        literals = dynamicLiterals.apply { build(lengths.copyOfRange(0, literalCount)) }
        distances = dynamicDistances.apply { build(lengths.copyOfRange(literalCount, lengths.size)) }
    }

    /** The next symbol in [code]. */
    private fun decode(code: Huffman): Int {
        pull(code.longest)
        val entry = code.table[(bits and code.mask).toInt()]
        val length = entry and 0xF
        // Nought is a pattern no code uses; more than there are is data that stopped short
        if (length == 0 || length > bitCount) throw ZipException("damaged deflate64 data")
        bits = bits ushr length
        bitCount -= length
        return entry ushr 4
    }

    /** The next [n] bits as a number, first bit lowest. */
    private fun take(n: Int): Int {
        if (n == 0) return 0
        pull(n)
        if (bitCount < n) throw EOFException("deflate64 data ends early")
        val value = (bits and ((1L shl n) - 1)).toInt()
        bits = bits ushr n
        bitCount -= n
        return value
    }

    /** At least [n] bits in hand, or as many as are left. */
    private fun pull(n: Int) {
        while (bitCount < n) {
            if (bufferAt == bufferEnd && !refill()) return
            bits = bits or ((buffer[bufferAt++].toLong() and 0xFF) shl bitCount)
            bitCount += 8
        }
    }

    private fun refill(): Boolean {
        if (inputEnded) return false
        var n = 0
        while (n == 0) n = input.read(buffer, 0, buffer.size)
        if (n < 0) {
            inputEnded = true
            return false
        }
        bufferAt = 0
        bufferEnd = n
        return true
    }

    /**
     * A canonical Huffman code, as RFC 1951 builds one from its lengths alone, laid out as a
     * table indexed by the next [longest] bits. Deflate writes codes first bit first, so each
     * is entered reversed, once for every value the bits after it could have. Room for codes
     * of up to [widest] bits, built into again for each block that brings its own.
     */
    private class Huffman(widest: Int) {
        var longest = 0
            private set
        var mask = 0L
            private set
        val table = IntArray(1 shl widest)

        fun build(lengths: IntArray) {
            longest = lengths.maxOrNull() ?: 0
            if (1 shl longest > table.size) throw ZipException("damaged deflate64 data")
            mask = (1L shl longest) - 1
            table.fill(0, 0, 1 shl longest)
            val perLength = IntArray(16)
            lengths.forEach { perLength[it]++ }
            perLength[0] = 0
            // More codes of some length than there are patterns for them is not a code at all
            var left = 1
            for (length in 1..15) {
                left = (left shl 1) - perLength[length]
                if (left < 0) throw ZipException("damaged deflate64 data")
            }
            val next = IntArray(16)
            var code = 0
            for (length in 1..15) {
                code = (code + perLength[length - 1]) shl 1
                next[length] = code
            }
            lengths.forEachIndexed { symbol, length ->
                if (length == 0) return@forEachIndexed
                var i = Integer.reverse(next[length]++) ushr (32 - length)
                while (i < table.size) {
                    table[i] = (symbol shl 4) or length
                    i += 1 shl length
                }
            }
        }
    }

    private companion object {
        const val WINDOW = 64 * 1024
        const val MASK = WINDOW - 1
        const val BUFFER = 64 * 1024

        const val HEADER = 0
        const val STORED = 1
        const val CODES = 2
        const val DONE = 3

        /** Lengths for codes 257 to 285. The last is where Deflate64 differs: 3 and sixteen bits, not 258. */
        val LENGTH_BASE = intArrayOf(
            3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
            35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 3,
        )
        val LENGTH_EXTRA = intArrayOf(
            0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
            3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 16,
        )

        /** Distances for codes 0 to 31. The last two are Deflate64's own, into the second 32 KB. */
        val DISTANCE_BASE = intArrayOf(
            1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
            257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577, 32769, 49153,
        )
        val DISTANCE_EXTRA = intArrayOf(
            0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
            7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13, 14, 14,
        )

        /** The order a dynamic block lists the lengths of its code length code in. */
        val ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

        val FIXED_LITERALS = Huffman(9).apply {
            build(IntArray(288) { if (it < 144) 8 else if (it < 256) 9 else if (it < 280) 7 else 8 })
        }
        val FIXED_DISTANCES = Huffman(5).apply { build(IntArray(32) { 5 }) }
    }
}
