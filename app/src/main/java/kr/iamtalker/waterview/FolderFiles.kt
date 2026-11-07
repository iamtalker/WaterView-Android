package kr.iamtalker.waterview

import android.net.Uri

/**
 * The files of the folder a file was opened from, in the order the folder lists them, so the
 * viewer can offer the one before and the one after it.
 *
 * Set by the home screen when it opens a file out of a folder, and read by the viewer. It is
 * kept here rather than sent in the intent, since a folder can hold far more files than an intent
 * can carry. A viewer started some other way, or after the process has been restarted, finds it
 * empty and offers neither.
 */
internal object FolderFiles {
    @Volatile
    var uris: List<Uri> = emptyList()
}
