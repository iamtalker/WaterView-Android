package kr.iamtalker.waterview

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

/**
 * What the home screen shows for a file, and in what order.
 *
 * The badge palette lives here rather than inside MainActivity because three
 * things draw from it: a row, the welcome grid, and the accessibility label
 * that stands in for the grid. Two hardcoded copies of a palette drift the
 * first time one is edited.
 */

/** WaterView's desktop blue family, kept cohesive across file-type badges. */
internal val PDF_BADGE = "PDF" to 0xFF155A96.toInt()
internal val DOC_BADGE = "DOC" to 0xFF1769A8.toInt()
internal val XLS_BADGE = "XLS" to 0xFF285D7A.toInt()
internal val PPT_BADGE = "PPT" to 0xFF315B88.toInt()
internal val IMG_BADGE = "IMG" to 0xFF176D95.toInt()
internal val VID_BADGE = "VID" to 0xFF285B8A.toInt()
internal val AUD_BADGE = "AUD" to 0xFF176879.toInt()
internal val MD_BADGE = "MD" to 0xFF455A73.toInt()
internal val TXT_BADGE = "TXT" to 0xFF53647A.toInt()

internal val MODEL_BADGE = "3D" to 0xFF314A70.toInt()

/** What an unsupported file falls back to. */
internal val FILE_BADGE = "FILE" to 0xFF586B82.toInt()

/**
 * The last tile of the welcome grid, standing for everything WaterView opens that has no tile of
 * its own: text and code, zips, and 3D models. In the grey the TXT tile had in that place, so
 * the grid reads exactly as it did and only the word has changed.
 *
 * Three letters, as every other tile is. The tile is a fixed square and its label grows with
 * the phone's font size, and a fourth letter wrapped it at 150% and cut it off at 200%.
 */
internal val ETC_BADGE = "ETC" to TXT_BADGE.second

/**
 * Draws the nine tiles of the welcome grid, in reading order.
 *
 * Kinds rather than formats, which is what makes the grid hold still: FileKind maps
 * about a hundred extensions onto its kinds, so adding .odt or .rst or another codec
 * changes nothing here. There are more kinds than tiles, since text files and zips both
 * open, and three rows of three is what fits the narrowest phone, so the last tile says
 * ETC rather than naming one of them. A new kind needs no tile: ETC already covers it.
 *
 * FILE is deliberately absent. It is what an unsupported file falls back to, and
 * this grid is a list of what WaterView opens.
 *
 * One thing here does not update itself: what_it_opens is the sentence a screen reader
 * hears in place of these tiles. It names every kind, the ones behind ETC included, and
 * FormatRegistryTest fails until a new kind is named there.
 */
internal val WELCOME_BADGES = listOf(
    PDF_BADGE, DOC_BADGE, XLS_BADGE,
    PPT_BADGE, IMG_BADGE, VID_BADGE,
    AUD_BADGE, MD_BADGE, ETC_BADGE,
)

internal val DIR_COLOR = 0xFF315A89.toInt()

/** A ZIP behaves as a folder in the browser. */
internal val ZIP_BADGE = "ZIP" to DIR_COLOR

/** The blue brand accent for the add-folder action. */
internal val ADD_COLOR = 0xFF1976B9.toInt()

/** The two or three letters, and the colour behind them, for a file. */
internal fun badgeFor(name: String, mime: String?): Pair<String, Int> {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (FileKind.detect(ext, mime)) {
        FileKind.PDF, FileKind.PDF_SIMPLE -> PDF_BADGE
        FileKind.DOCX, FileKind.PROSE, FileKind.EPUB -> DOC_BADGE
        FileKind.XLSX -> XLS_BADGE
        FileKind.PPTX -> PPT_BADGE
        FileKind.IMAGE, FileKind.IMAGE_WEB -> IMG_BADGE
        FileKind.PLAYER -> if (FileKind.isAudioExt(ext)) AUD_BADGE else VID_BADGE
        FileKind.MD -> MD_BADGE
        FileKind.TEXT -> TXT_BADGE
        FileKind.MODEL -> MODEL_BADGE
        FileKind.ARCHIVE -> ZIP_BADGE
        FileKind.UNSUPPORTED -> FILE_BADGE
    }
}

/** One entry of a folder, as the provider describes it. */
internal data class ChildDoc(
    val docId: String,
    val name: String,
    val mime: String,
    val size: Long,
    val modified: Long
)

internal const val MIME_DIR = "vnd.android.document/directory"

/**
 * What [folder] holds, read through the grant on the tree [tree] belongs to, or null where its
 * provider can't say. The home screen lists a folder with this, and the photo viewer the photos
 * beside the one it opened.
 */
internal fun childrenOf(resolver: ContentResolver, tree: Uri, folder: String): List<ChildDoc>? = runCatching {
    resolver.query(
        DocumentsContract.buildChildDocumentsUriUsingTree(tree, folder),
        arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        ),
        null, null, null
    )?.use { c ->
        buildList {
            while (c.moveToNext()) {
                add(ChildDoc(c.getString(0), c.getString(1) ?: "?", c.getString(2) ?: "", c.getLong(3), c.getLong(4)))
            }
        }
    }
}.getOrNull()

/**
 * A folder listing split into directories and files, sorted according to [sortOrder].
 *
 * Directories first, because that is where a reader looking for somewhere else
 * to go will look. Dotfiles are dropped from both: nothing WaterView opens is
 * hidden by convention, and a folder full of them reads as noise.
 *
 * Folders have no file size reported by the document provider, so size orders
 * keep directories alphabetically (A to Z).
 *
 * sortedWith and not sortedBy, for the reason homeRows gives: the selector runs
 * on every comparison, so lowercase() there allocated some sixteen thousand
 * strings on a folder of fifteen hundred files rather than none.
 */
internal fun orderChildren(
    children: List<ChildDoc>,
    sortOrder: Settings.SortOrder = Settings.SortOrder.NAME_AZ
): Pair<List<ChildDoc>, List<ChildDoc>> {
    val visible = children.filterNot { it.name.startsWith(".") }
    val (dirs, files) = visible.partition { it.mime == MIME_DIR }

    val nameCmp = compareBy(String.CASE_INSENSITIVE_ORDER) { c: ChildDoc -> c.name }
    val dateCmp = compareBy<ChildDoc> { it.modified }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    val sizeCmp = compareBy<ChildDoc> { it.size }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

    val dirComparator = when (sortOrder) {
        Settings.SortOrder.NAME_ZA -> nameCmp.reversed()
        Settings.SortOrder.NEWEST -> dateCmp.reversed()
        Settings.SortOrder.OLDEST -> dateCmp
        else -> nameCmp
    }

    val fileComparator = when (sortOrder) {
        Settings.SortOrder.NAME_AZ -> nameCmp
        Settings.SortOrder.NAME_ZA -> nameCmp.reversed()
        Settings.SortOrder.NEWEST -> dateCmp.reversed()
        Settings.SortOrder.OLDEST -> dateCmp
        Settings.SortOrder.LARGEST -> sizeCmp.reversed()
        Settings.SortOrder.SMALLEST -> sizeCmp
    }

    return dirs.sortedWith(dirComparator) to files.sortedWith(fileComparator)
}

internal data class SupportedFilterType(
    val badge: String,
    val color: Int,
    val labelRes: Int
)

internal val SUPPORTED_FILTER_TYPES = listOf(
    SupportedFilterType(PDF_BADGE.first, PDF_BADGE.second, R.string.filter_pdf),
    SupportedFilterType(DOC_BADGE.first, DOC_BADGE.second, R.string.filter_doc),
    SupportedFilterType(XLS_BADGE.first, XLS_BADGE.second, R.string.filter_xls),
    SupportedFilterType(PPT_BADGE.first, PPT_BADGE.second, R.string.filter_ppt),
    SupportedFilterType(IMG_BADGE.first, IMG_BADGE.second, R.string.filter_img),
    SupportedFilterType(VID_BADGE.first, VID_BADGE.second, R.string.filter_vid),
    SupportedFilterType(AUD_BADGE.first, AUD_BADGE.second, R.string.filter_aud),
    SupportedFilterType(MD_BADGE.first, MD_BADGE.second, R.string.filter_md),
    SupportedFilterType(TXT_BADGE.first, TXT_BADGE.second, R.string.filter_txt),
    SupportedFilterType(MODEL_BADGE.first, MODEL_BADGE.second, R.string.filter_model),
    SupportedFilterType(ZIP_BADGE.first, ZIP_BADGE.second, R.string.filter_archive),
)
