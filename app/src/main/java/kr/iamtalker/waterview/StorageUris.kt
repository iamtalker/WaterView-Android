package kr.iamtalker.waterview

import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * Finding the folder a document sits in, so "Show in file manager" has
 * somewhere to point.
 *
 * A document arrives as one of four unrelated shapes depending on which app
 * handed it over, and only some of them can be turned back into a folder at
 * all. The rules are string manipulation over provider-specific id formats,
 * which is worth pinning: they are not documented anywhere, and a wrong answer
 * opens the file manager at somebody else's directory.
 */

internal const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
internal const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"

/**
 * An absolute filesystem path as an ExternalStorageProvider document id.
 *
 * "/storage/emulated/0/Docs" becomes "primary:Docs"; a removable volume like
 * "/storage/1234-5678/Docs" becomes "1234-5678:Docs". Null for a path under
 * neither, which is anything private to another app.
 *
 * [primaryRoot] is Environment.getExternalStorageDirectory(), passed in rather
 * than read here so this stays answerable without a device.
 */
internal fun folderDocId(path: String, primaryRoot: String): String? = when {
    path.startsWith(primaryRoot) ->
        "primary:" + path.removePrefix(primaryRoot).trimStart('/')
    path.startsWith("/storage/") -> {
        val rest = path.removePrefix("/storage/")
        rest.substringBefore('/') + ":" + rest.substringAfter('/', "")
    }
    else -> null
}

/**
 * The folder holding an ExternalStorageProvider document.
 *
 * The id is "volume:relative/path", so this drops the last segment. Null when
 * either half is missing, which is what a volume root looks like.
 */
internal fun parentDocId(docId: String): String? {
    val volume = docId.substringBefore(':', "")
    val path = docId.substringAfter(':', "")
    if (volume.isEmpty() || path.isEmpty()) return null
    return "$volume:${path.substringBeforeLast('/', "")}"
}

/**
 * The folder a document lives in, as a URI a file manager will open, or null
 * when there is no answering it.
 *
 * [primaryRoot] is the primary external storage path. [mediaPath] resolves a
 * MediaStore URI to the file behind it, which needs a ContentResolver and so is
 * supplied by the caller.
 */
internal fun parentDocUri(
    uri: Uri,
    primaryRoot: String,
    mediaPath: (Uri) -> String?
): Uri? = runCatching {
    fun folderUri(path: String): Uri? =
        folderDocId(path, primaryRoot)?.let {
            DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, it)
        }

    when {
        uri.scheme == "file" ->
            File(uri.path!!).parent?.let(::folderUri)

        uri.authority == EXTERNAL_STORAGE_AUTHORITY ->
            parentDocId(DocumentsContract.getDocumentId(uri))?.let {
                DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, it)
            }

        uri.authority == DOWNLOADS_AUTHORITY -> {
            val docId = DocumentsContract.getDocumentId(uri)
            if (docId.startsWith("raw:")) {
                File(docId.removePrefix("raw:")).parent?.let(::folderUri)
            } else {
                // Opaque ids (msf:42) say nothing about where the file is, but
                // everything this provider hands out is at least under Download
                DocumentsContract.buildDocumentUri(
                    EXTERNAL_STORAGE_AUTHORITY, "primary:Download"
                )
            }
        }

        // Our read grant lets us ask MediaStore for the backing path
        uri.authority == "media" ->
            mediaPath(uri)?.let { File(it).parent }?.let(::folderUri)

        else -> null
    }
}.getOrNull()
