package kr.iamtalker.waterview

import android.annotation.SuppressLint
import java.io.EOFException
import java.io.InputStream
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The two ways a zip keeps a file from anyone without its password, read. Issue #30.
 *
 * Both written from PKWARE's APPNOTE and WinZip's published AES specification, on the
 * platform's own AES and HMAC: nothing added to the download. Nothing here ever writes: the
 * decrypted bytes go to whoever is reading, and the password stays in memory, see
 * ArchivePasswords.
 */
internal object ZipEncryption {

    /** Windows' own code pages for Western and Eastern European text, after the DOS ones. */
    private val WINDOWS = charsets("windows-1252", "windows-1250", "windows-1251")

    /**
     * Everything else, last: every code page a reader can choose names in, which takes in the
     * DOS ones for Central Europe, Greece and Turkey that are only guessed on a phone set to
     * one of their languages, then Windows' own for Greek, Turkish, Hebrew, Arabic, Baltic,
     * Vietnamese and Thai. Without them a password written in one opened only on a phone set
     * to its language, or on none.
     */
    private val REST: List<Charset> by lazy {
        ZipNames.CHOICES.map { it.second } + charsets(
            "windows-1253", "windows-1254", "windows-1255", "windows-1256",
            "windows-1257", "windows-1258", "windows-874",
        )
    }

    /** Those of [names] the runtime has. */
    private fun charsets(vararg names: String): List<Charset> =
        names.mapNotNull { runCatching { Charset.forName(it) }.getOrNull() }

    /**
     * Every way [password] can have been turned into bytes, each once, the likeliest first.
     *
     * The format never said. WinZip's AES and anything from a Mac or Linux uses UTF-8. The
     * older encryption on Windows took the password in the machine's own code page, the one
     * it wrote names in, so the phone's language comes next and then every other code page
     * ZipNames knows, and Windows' own. A password in plain ASCII is the same bytes in all of
     * them and is tried once, which is nearly every password.
     */
    fun passwordBytes(password: String, locale: Locale = Locale.getDefault()): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        for (charset in listOf(Charsets.UTF_8) + ZipNames.codePages(locale) + WINDOWS + REST) {
            val bytes = strict(charset, password) ?: continue
            if (bytes.isNotEmpty() && out.none { it.contentEquals(bytes) }) out += bytes
        }
        return out
    }

    private fun strict(charset: Charset, text: String): ByteArray? = try {
        val out = charset.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(text))
        ByteArray(out.remaining()).also { out.get(it) }
    } catch (_: CharacterCodingException) {
        null
    }
}

/**
 * PKWARE's original encryption: "traditional" in the APPNOTE, ZipCrypto everywhere else.
 *
 * Three 32 bit keys, stirred by every byte of the password and then by every byte of the
 * file as it is decrypted. The state is small enough to copy, which is how a password can be
 * tried against the start of a file and the file then read from that same point.
 */
internal class ZipCrypto private constructor(private var k0: Int, private var k1: Int, private var k2: Int) {

    fun copy() = ZipCrypto(k0, k1, k2)

    private fun update(plain: Int) {
        k0 = crc(k0, plain)
        k1 = (k1 + (k0 and 0xFF)) * 134775813 + 1
        k2 = crc(k2, k1 ushr 24)
    }

    private fun next(): Int {
        val t = (k2 or 2) and 0xFFFF
        return ((t * (t xor 1)) ushr 8) and 0xFF
    }

    fun decrypt(b: ByteArray, off: Int, len: Int) {
        for (i in off until off + len) {
            val plain = (b[i].toInt() xor next()) and 0xFF
            update(plain)
            b[i] = plain.toByte()
        }
    }

    /** Decrypts the header that goes in front of the file, and says whether it ends in [check]. */
    fun checks(header: ByteArray, check: Int): Boolean {
        val plain = header.copyOf()
        decrypt(plain, 0, plain.size)
        return plain.last().toInt() and 0xFF == check
    }

    companion object {
        const val HEADER_SIZE = 12

        /** CRC-32's table, which the keys are stirred with, without the inversions around it. */
        private val TABLE = IntArray(256) { n ->
            var c = n
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
            c
        }

        private fun crc(c: Int, b: Int) = TABLE[(c xor b) and 0xFF] xor (c ushr 8)

        fun keyed(password: ByteArray) = ZipCrypto(0x12345678, 0x23456789, 0x34567890).apply {
            password.forEach { update(it.toInt() and 0xFF) }
        }
    }
}

/** A file under ZipCrypto, decrypted as it is read. */
internal class ZipCryptoInput(private val input: InputStream, private val keys: ZipCrypto) : InputStream() {

    override fun read(): Int {
        val one = ByteArray(1)
        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = input.read(b, off, len)
        if (n > 0) keys.decrypt(b, off, n)
        return n
    }

    override fun close() = input.close()
}

/**
 * WinZip's AES, versions AE-1 and AE-2, at any of its three strengths.
 *
 * The password and a salt go through PBKDF2 with HMAC-SHA1, a thousand rounds, into three
 * things: the AES key, a second key that authenticates the file, and two bytes to check the
 * password by before anything is decrypted. The file is AES in counter mode with WinZip's own
 * counter, which counts up from one in little-endian order where the standard mode counts
 * big-endian, so the platform's CTR cannot be used and each counter block is encrypted here.
 */
internal object WinZipAes {

    const val VERIFIER_SIZE = 2
    const val CODE_SIZE = 10
    private const val ROUNDS = 1000
    private const val BLOCK = 16

    /** How much key stream is made at once: one call into the platform's AES rather than a thousand. */
    private const val STREAM_BYTES = 16 * 1024

    class Keys(val cipher: ByteArray, val mac: ByteArray, val verifier: ByteArray)

    /** What [password] and [salt] make, or null for an empty password, which is no key at all. */
    fun derive(password: ByteArray, salt: ByteArray, keySize: Int): Keys? {
        if (password.isEmpty()) return null
        val out = pbkdf2(password, salt, 2 * keySize + VERIFIER_SIZE)
        return Keys(
            out.copyOfRange(0, keySize),
            out.copyOfRange(keySize, 2 * keySize),
            out.copyOfRange(2 * keySize, out.size),
        )
    }

    /**
     * RFC 8018's PBKDF2 over HMAC-SHA1, written out rather than asked of the platform, because
     * the platform's takes the password as characters and picks their encoding itself, and a
     * password here has to be tried as each set of bytes it could have been.
     */
    private fun pbkdf2(password: ByteArray, salt: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(password, "HmacSHA1")) }
        val out = ByteArray(length)
        var block = 1
        var filled = 0
        while (filled < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            repeat(ROUNDS - 1) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            val n = minOf(t.size, length - filled)
            System.arraycopy(t, 0, out, filled, n)
            filled += n
            block++
        }
        return out
    }

    /**
     * A file under AES, decrypted as it is read. [input] is the encrypted bytes, [size] of
     * them, and then the authentication code; [finish] checks the code, and has to be called
     * once the file has been read, since a decompressor can stop short of the last bytes.
     */
    class Decrypting(private val input: InputStream, private val size: Long, keys: Keys) : InputStream() {
        // ECB here encrypts nothing but counter blocks, one at a time, which is what counter
        // mode is built from: the file itself is only ever XORed with what comes out. Lint's
        // warning is about ECB applied to data, which this never does.
        @SuppressLint("GetInstance")
        private val aes = Cipher.getInstance("AES/ECB/NoPadding")
            .apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.cipher, "AES")) }
        private val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(keys.mac, "HmacSHA1")) }
        private val counters = ByteArray(STREAM_BYTES)
        private val stream = ByteArray(STREAM_BYTES)
        private var streamAt = STREAM_BYTES
        private var counter = 0L
        private var done = 0L
        private var finished = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (done >= size) {
                finish()
                return -1
            }
            val n = input.read(b, off, minOf(len.toLong(), size - done).toInt())
            if (n < 0) throw EOFException("zip ends early")
            // The code is over the encrypted bytes, so they go to it before they are changed
            mac.update(b, off, n)
            for (i in off until off + n) {
                if (streamAt == STREAM_BYTES) refill()
                b[i] = (b[i].toInt() xor stream[streamAt++].toInt()).toByte()
            }
            done += n
            return n
        }

        /** The next stretch of key stream: the counter's next blocks, each encrypted. */
        private fun refill() {
            for (block in 0 until STREAM_BYTES / BLOCK) {
                counter++
                var c = counter
                val at = block * BLOCK
                // Little-endian, and the top eight bytes stay nought: no file has 2^64 blocks
                for (i in 0 until 8) {
                    counters[at + i] = c.toByte()
                    c = c ushr 8
                }
            }
            aes.doFinal(counters, 0, STREAM_BYTES, stream, 0)
            streamAt = 0
        }

        /**
         * Reads whatever of the file is left, then the code after it, which has to match. What
         * a decompressor left unread still counts, since the code covers every byte.
         */
        fun finish() {
            if (finished) return
            val rest = ByteArray(8 * 1024)
            while (done < size) {
                val n = input.read(rest, 0, minOf(rest.size.toLong(), size - done).toInt())
                if (n < 0) throw EOFException("zip ends early")
                mac.update(rest, 0, n)
                done += n
            }
            val code = ByteArray(CODE_SIZE)
            var got = 0
            while (got < CODE_SIZE) {
                val n = input.read(code, got, CODE_SIZE - got)
                if (n < 0) throw EOFException("zip ends early")
                got += n
            }
            if (!MessageDigest.isEqual(code, mac.doFinal().copyOf(CODE_SIZE))) {
                throw ZipException("entry is damaged")
            }
            finished = true
        }

        override fun close() = input.close()
    }
}
