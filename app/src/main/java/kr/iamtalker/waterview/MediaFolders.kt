package kr.iamtalker.waterview

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import java.io.File

/**
 * The photos beside a photo opened from outside WaterView, read from the phone's media index.
 *
 * A file another app hands over comes with a grant for that one file, so the folder it is in
 * cannot be listed through it. The media index can be read with the photo permission instead,
 * which a gallery asks for once and never again, and which lists every photo in the folder.
 * Only a photo the index knows can be found this way: one that is not indexed, or that comes
 * from a provider other than the phone's own storage, stays a single photo.
 */
internal object MediaFolders {

    /** The one permission the listing needs: photos and videos on Android 13 and later, storage before. */
    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    /** Whether all files access is on, which lets the folder be read by its path, not the index. */
    fun allFiles(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()

    /**
     * [photo] and the photos in its folder, read straight from the folder on the phone or the SD card
     * with all files access. Unlike the index this also finds photos in a .nomedia folder. Null when
     * the access is off, the photo is not on the storage, or the folder can't be read.
     */
    fun folderPhotos(photo: Uri): Pair<MediaPhoto, List<MediaPhoto>>? {
        if (!allFiles() || photo.authority != EXTERNAL_STORAGE) return null
        val docId = runCatching { DocumentsContract.getDocumentId(photo) }.getOrNull() ?: return null
        val volume = docId.substringBefore(':', "")
        if (volume.isEmpty()) return null
        val relative = docId.substringAfter(':', "")
        val dirRelative = relative.substringBeforeLast('/', "")
        val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        val files = File(root, dirRelative).listFiles()
            ?.filter { it.isFile && isImage(it.name) }
            ?.sortedBy { it.name.lowercase() }
            ?: return null
        // Read by path, not by document address: the storage provider refuses a document it was not
        // handed, even with all files access, so the neighbours can't be opened through it.
        val pages = files.map { f ->
            MediaPhoto(
                Uri.fromFile(f),
                f.name,
                MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()).orEmpty(),
            )
        }
        val target = File(root, relative).path
        val at = pages.indexOfFirst { it.uri.path == target }
        if (at < 0) return null
        return pages[at] to pages
    }

    private fun isImage(name: String): Boolean {
        val kind = FileKind.detect(name.substringAfterLast('.', "").lowercase(), null)
        return kind == FileKind.IMAGE || kind == FileKind.IMAGE_WEB
    }

    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** A photo the index knows: its index URI, name and type. */
    data class MediaPhoto(val uri: Uri, val name: String, val mime: String)

    /**
     * [photo] as the index knows it, and the photos in its folder in name order with it among
     * them. Null when the photo is not in the index, or the permission is missing.
     */
    fun photosBeside(context: Context, photo: Uri): Pair<MediaPhoto, List<MediaPhoto>>? {
        if (!granted(context)) return null
        val resolver = context.contentResolver
        val id = indexId(context, photo) ?: return null
        val media = ContentUris.withAppendedId(IMAGES, id)
        val bucket = resolver.query(media, arrayOf(MediaStore.Images.Media.BUCKET_ID), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            ?: return null
        val pages = mutableListOf<MediaPhoto>()
        resolver.query(
            IMAGES,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE),
            "${MediaStore.Images.Media.BUCKET_ID} = ?",
            arrayOf(bucket.toString()),
            "${MediaStore.Images.Media.DISPLAY_NAME} COLLATE NOCASE ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                pages += MediaPhoto(
                    ContentUris.withAppendedId(IMAGES, c.getLong(0)),
                    c.getString(1).orEmpty(),
                    c.getString(2).orEmpty(),
                )
            }
        }
        val at = pages.indexOfFirst { it.uri == media }
        if (at < 0) return null
        return pages[at] to pages
    }

    /** The index's id of [photo]: its own id when it is an index URI, else found by its path on the phone. */
    private fun indexId(context: Context, photo: Uri): Long? {
        if (photo.authority == MediaStore.AUTHORITY) {
            return photo.takeIf { "images" in it.pathSegments }?.lastPathSegment?.toLongOrNull()
        }
        val path = storagePath(photo) ?: return null
        return context.contentResolver.query(
            IMAGES,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DATA} = ?",
            arrayOf(path),
            null,
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    }

    /** The path a file has on the phone's own storage, from a file URI or the storage provider's document id. */
    private fun storagePath(photo: Uri): String? {
        if (photo.scheme == "file") return photo.path
        if (photo.authority != EXTERNAL_STORAGE) return null
        val docId = runCatching { DocumentsContract.getDocumentId(photo) }.getOrNull() ?: return null
        val volume = docId.substringBefore(':', "")
        val relative = docId.substringAfter(':', "")
        if (volume.isEmpty()) return null
        val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return "$root/$relative"
    }

    private val IMAGES: Uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"
}
