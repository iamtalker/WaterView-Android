package kr.iamtalker.waterview

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A short diagnostic log kept on the phone, for finding why something went wrong. It is only ever
 * read in Settings, where the reader can see it or clear it. Kept in the no-backup folder, and cut
 * back to its last part once it grows past [MAX_BYTES].
 */
internal object Diag {

    private const val FILE = "diag.log"
    private const val MAX_BYTES = 64 * 1024

    private fun file(context: Context) = File(context.noBackupFilesDir, FILE)

    @Synchronized
    fun log(context: Context, message: String) {
        runCatching {
            val f = file(context)
            val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            f.appendText("$stamp $message\n")
            if (f.length() > MAX_BYTES) {
                val kept = f.readText().takeLast(MAX_BYTES / 2)
                f.writeText(kept)
            }
        }
    }

    fun read(context: Context): String = runCatching { file(context).readText() }.getOrDefault("")

    @Synchronized
    fun clear(context: Context) {
        runCatching { file(context).delete() }
    }
}
