package kr.iamtalker.waterview

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

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

/**
 * The files in the folder a document sits in, in name order (1권, 2권, 10권), as documents under the
 * folder the reader granted. Empty when the document is not in a folder the reader granted.
 */
internal fun siblingsOf(context: Context, uri: Uri, keep: (String) -> Boolean = { true }): List<Uri> {
    val resolver = context.contentResolver
    val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return emptyList()
    val tree = resolver.persistedUriPermissions
        .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
        .map { it.uri }
        .firstOrNull { folder ->
            val treeDoc = runCatching { DocumentsContract.getTreeDocumentId(folder) }.getOrNull() ?: return@firstOrNull false
            val prefix = if (treeDoc.endsWith(":") || treeDoc.endsWith("/")) treeDoc else "$treeDoc/"
            docId.startsWith(prefix)
        } ?: return emptyList()
    val parent = if (docId.contains('/')) docId.substringBeforeLast('/') else docId.substringBefore(':') + ":"
    return childrenOf(resolver, tree, parent).orEmpty()
        .filter { it.mime != MIME_DIR && keep(it.name) }
        .sortedWith { a, b -> naturalCompare(a.name, b.name) }
        .map { DocumentsContract.buildDocumentUriUsingTree(tree, it.docId) }
}

/** Orders names so that a number counts as a number: "2권" comes before "10권", and case does not matter. */
internal fun naturalCompare(a: String, b: String): Int {
    val pa = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList()
    val pb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(pa.size, pb.size)) {
        val x = pa[i]
        val y = pb[i]
        val c = if (x[0].isDigit() && y[0].isDigit()) {
            val nx = x.trimStart('0')
            val ny = y.trimStart('0')
            if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
        } else {
            x.compareTo(y)
        }
        if (c != 0) return c
    }
    return pa.size - pb.size
}
