package kr.iamtalker.waterview

import android.net.Uri
import java.util.concurrent.ConcurrentHashMap

/**
 * The password that opened a zip's files, for as long as its list is open. Issue #30.
 *
 * Held here, in memory, and nowhere else. Not in the URI a viewer is given, because a viewer
 * passes its URI on when a file is shared, and not on storage, which would make the promise
 * that nothing in a zip is written to the phone untrue in the one place it matters most. The
 * list forgets it when the reader leaves, and a process Android reclaims forgets it too, which
 * at worst means typing it again.
 *
 * One per archive, since a zip nearly always has one password for everything in it. A file
 * that turns out to have another asks for that, and the newer one is kept.
 */
internal object ArchivePasswords {

    private val passwords = ConcurrentHashMap<String, String>()

    fun get(archive: Uri): String? = passwords[archive.toString()]

    fun remember(archive: Uri, password: String) {
        passwords[archive.toString()] = password
    }

    fun forget(archive: Uri) {
        passwords.remove(archive.toString())
    }

    /** Process-wide, so it outlives a test the way it outlives a screen. */
    @androidx.annotation.VisibleForTesting
    internal fun forgetAll() = passwords.clear()
}
