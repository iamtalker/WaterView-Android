package kr.iamtalker.waterview

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.edit
import androidx.core.net.toUri
import org.json.JSONArray
import org.json.JSONObject

/**
 * Recently opened files that remain readable through either a persisted document grant
 * (picker selections) or the persisted tree grant of a WaterView folder. Stored as JSON in
 * SharedPreferences.
 *
 * A document grant owned by this list goes when the entry does. A folder grant remains
 * owned by the folder list, even when one of its recent files is removed.
 */
object Recents {

    data class Entry(val uri: String, val name: String, val time: Long)

    private const val PREFS = "recents"
    private const val KEY = "items"
    private const val MAX = 200

    fun all(context: Context): List<Entry> {
        val grants = context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }
        val granted = grants.map { it.uri.toString() }.toSet()
        return load(context).filter { entry ->
            entry.uri in granted || hasPersistedTreeAccess(entry.uri.toUri(), grants)
        }
    }

    fun add(context: Context, uri: Uri, name: String) {
        val key = uri.toString()
        val items = load(context).filter { it.uri != key }.toMutableList()
        items.add(0, Entry(key, name, System.currentTimeMillis()))
        save(context, items.take(MAX))
        items.drop(MAX).forEach { release(context, it.uri) }
    }

    fun remove(context: Context, uri: String) {
        save(context, load(context).filter { it.uri != uri })
        release(context, uri)
    }

    /** A file opened from an added folder inherits its persisted tree grant, not a file grant. */
    private fun hasPersistedTreeAccess(
        uri: Uri,
        grants: List<android.content.UriPermission>,
    ): Boolean {
        if (!DocumentsContract.isTreeUri(uri)) return false
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return false
        return grants.any { permission ->
            permission.uri.authority == uri.authority && DocumentsContract.isTreeUri(permission.uri) &&
                runCatching { DocumentsContract.getTreeDocumentId(permission.uri) == treeId }.getOrDefault(false)
        }
    }

    /** Gives back a document grant owned by this entry, never a parent folder's tree grant. */
    private fun release(context: Context, uri: String) {
        val target = uri.toUri()
        if (context.contentResolver.persistedUriPermissions.none { it.uri == target }) return
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                target,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
    }

    private fun load(context: Context): List<Entry> = runCatching {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "[]")!!
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Entry(o.getString("uri"), o.getString("name"), o.getLong("time"))
        }
    }.getOrDefault(emptyList())

    private fun save(context: Context, items: List<Entry>) {
        val arr = JSONArray()
        items.forEach { e ->
            arr.put(
                JSONObject()
                    .put("uri", e.uri)
                    .put("name", e.name)
                    .put("time", e.time)
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putString(KEY, arr.toString()) }
    }
}
