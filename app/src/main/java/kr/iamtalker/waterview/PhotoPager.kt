package kr.iamtalker.waterview

import android.content.ContentResolver
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.provider.DocumentsContract
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.davemorrissey.labs.subscaleview.ImageSource

/**
 * A photo from a folder added in WaterView, with the folder's other photos a swipe away, in the order
 * the home screen sorts the folder in. Issue #75. A photo another app opens in WaterView comes alone,
 * since Android shares only that one file.
 *
 * Only the photo on screen may play, if it moves, since that takes the viewer's one WebView.
 */
internal class PhotoPager(
    context: Context,
    private val opened: Uri,
    first: ChildDoc,
    private val host: Host,
    private val uriOverride: ((ChildDoc) -> Uri)? = null,
    private val rightToLeft: Boolean = false,
    private val splitWidePages: Boolean = false,
) {

    /** What the viewer does for the pages. */
    interface Host {
        /** Hands [then] whether the picture at [uri] moves and its turn, asked off the main thread. */
        fun look(uri: Uri, ext: String, then: (Look) -> Unit)

        /** The tiling view for the still photo at [uri], which calls [failed] when it can't draw it. */
        fun still(uri: Uri, name: String, rotation: Int, ready: (Int, Int) -> Unit, failed: () -> Unit): StillPhotoView

        /** The photo on screen has come to be drawn by the tiling view, or has stopped being. */
        fun stillChanged()

        /** Puts the page that plays a moving picture into [frame]. */
        fun play(frame: FrameLayout, uri: Uri, name: String, ext: String)

        /** Takes that page away again. */
        fun stopPlaying()

        /** The photo on screen is now [photo], number [at] from 0 of [of]. */
        fun shown(photo: ChildDoc, uri: Uri, at: Int, of: Int)
    }

    /** Whether a picture moves, and the turn its EXIF asks for. See [pictureLook]. */
    data class Look(val moves: Boolean, val rotation: Int)

    private var photos = listOf(first)
    private val splitSources = HashMap<String, ChildDoc>()
    private val splitRegions = HashMap<String, Rect>()
    private val pages = Pages()

    /** The page playing a moving picture, if one is. */
    private var playing: Page? = null

    val view = ViewPager2(context).apply {
        setBackgroundColor(Color.BLACK)
        // Page turns accept both sideways swipes and taps on the page edges.
        isUserInputEnabled = true
        layoutDirection = if (rightToLeft) android.view.View.LAYOUT_DIRECTION_RTL
        else android.view.View.LAYOUT_DIRECTION_LTR
        // The photos either side are drawn before they are reached, so one slides in whole
        offscreenPageLimit = 1
        adapter = pages
        (getChildAt(0) as RecyclerView).apply {
            // A page further off is drawn again on the way back, rather than kept with its bitmap
            setItemViewCacheSize(0)
            itemAnimator = null
        }
        registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                announce(position)
                // A jump straight to a page reports no change of scroll state, so no rest
                // either. Settled after the layout that brings the page in.
                if (scrollState == ViewPager2.SCROLL_STATE_IDLE) post(::settle)
            }

            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager2.SCROLL_STATE_IDLE) settle()
            }
        })
    }

    val count: Int get() = photos.size

    /** Whether the page on show is the last one in the list the pager holds. */
    val onLastPage: Boolean get() = view.currentItem >= (view.adapter?.itemCount ?: 0) - 1

    fun previous() {
        if (view.currentItem > 0) view.setCurrentItem(view.currentItem - 1, true)
    }

    fun next() {
        if (view.currentItem < photos.lastIndex) view.setCurrentItem(view.currentItem + 1, true)
    }

    /** Goes to the photo at [index], counted from nought, without an animation across the ones between. */
    fun goTo(index: Int) {
        if (index in photos.indices && index != view.currentItem) view.setCurrentItem(index, false)
    }

    /** The photos' names, in order. Read by tests. */
    val names: List<String> get() = photos.map { it.name }

    /** The index in the folder's list of the page on show, a split photo counting as its first half. */
    val currentIndex: Int get() = firstIndex(view.currentItem)

    /** The address of the folder's first photo, which names the folder for its bookmarks. */
    val firstUri: Uri? get() = photos.firstOrNull()?.let(::uriOf)

    /** The photo on screen. */
    private val step get() = 1
    private val pageCount get() = (photos.size + step - 1) / step
    private fun firstIndex(position: Int) = (position * step).coerceIn(photos.indices)
    private fun sourceOf(photo: ChildDoc) = splitSources[photo.docId] ?: photo

    val shown: Uri get() = uriOf(photos[firstIndex(view.currentItem)])

    /** The tiling view drawing the photo on screen, or null where it isn't drawn by one. */
    val stillShown: StillPhotoView? get() = pageAt(view.currentItem)?.still

    /** Puts the folder's other photos either side of the one opened, which stays on screen. */
    fun fill(all: List<ChildDoc>, at: Int) {
        if (photos.size != 1 || all.size < 2 || all.getOrNull(at)?.docId != photos[0].docId) return
        photos = all
        pages.notifyDataSetChanged()
        view.setCurrentItem(at / step, false)
        // Said again for the first photo, which no page change announces
        announce(at / step)
    }

    /**
     * Takes [photo] out once Android has moved it to its trash, and shows the photo after it, or
     * the one before where it was the last. False where it was the only photo, or isn't here,
     * which leaves nothing to show.
     */
    fun drop(photo: Uri): Boolean {
        val docId = DocumentsContract.getDocumentId(photo)
        val at = photos.indexOfFirst { it.docId == docId }
        if (at < 0 || photos.size < 2) return false
        photos = photos.filterIndexed { i, _ -> i != at }
        pages.notifyDataSetChanged()
        // The photo after it takes its number, so no page change comes to name it, or to play
        // it if it moves
        view.post {
            announce(view.currentItem)
            settle()
        }
        return true
    }

    private fun uriOf(photo: ChildDoc): Uri {
        val source = sourceOf(photo)
        return uriOverride?.invoke(source) ?: DocumentsContract.buildDocumentUriUsingTree(opened, source.docId)
    }

    private fun announce(position: Int) {
        val photo = photos.getOrNull(firstIndex(position)) ?: return
        host.shown(photo, uriOf(photo), position, pageCount)
    }

    private fun onScreen(photo: ChildDoc): Boolean {
        val first = firstIndex(view.currentItem)
        return photos.getOrNull(first)?.docId == photo.docId ||
            (step == 2 && photos.getOrNull(first + 1)?.docId == photo.docId)
    }

    private fun pageAt(position: Int) =
        (view.getChildAt(0) as RecyclerView).findViewHolderForAdapterPosition(position) as? Page

    /** Once the pages have come to rest, the one on screen plays if it moves, and only that one. */
    private fun settle() {
        if (view.scrollState != ViewPager2.SCROLL_STATE_IDLE) return
        val here = pageAt(view.currentItem)
        if (playing != null && playing !== here) stop()
        val photo = here?.photo ?: return
        if (here.moves && playing == null && onScreen(photo)) {
            playing = here
            host.play(here.frame, uriOf(photo), photo.name, extOf(photo))
        }
    }

    private fun stop() {
        val page = playing ?: return
        playing = null
        host.stopPlaying()
        // And the card that says a page's renderer has gone, if that is what it shows
        page.frame.removeAllViews()
    }

    private inner class Page(val frame: FrameLayout) : RecyclerView.ViewHolder(frame) {
        private val row = android.widget.LinearLayout(frame.context).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutDirection = if (rightToLeft) android.view.View.LAYOUT_DIRECTION_RTL
            else android.view.View.LAYOUT_DIRECTION_LTR
        }
        private val primaryFrame = FrameLayout(frame.context)
        private val secondaryFrame = FrameLayout(frame.context)
        var photo: ChildDoc? = null
        private var secondPhoto: ChildDoc? = null

        /** Whether its picture needs the page that plays it, rather than the tiling view. */
        var moves = false

        /** The tiling view drawing its photo, once there is one. */
        var still: StillPhotoView? = null
        private var secondStill: StillPhotoView? = null

        init {
            frame.addView(row, MATCH)
            row.addView(primaryFrame, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            row.addView(secondaryFrame, android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }

        fun bind(next: ChildDoc, second: ChildDoc?) {
            clear()
            photo = next
            secondPhoto = second
            secondaryFrame.visibility = if (second == null) android.view.View.GONE else android.view.View.VISIBLE
            bindOne(next, primaryFrame, primary = true)
            second?.let { bindOne(it, secondaryFrame, primary = false) }
        }

        private fun bindOne(next: ChildDoc, target: FrameLayout, primary: Boolean) {
            val uri = uriOf(next)
            val ext = extOf(next)
            if (FileKind.detect(ext, next.mime.ifEmpty { null }) != FileKind.IMAGE) {
                if (primary) needsPlaying()
                return
            }
            host.look(uri, ext) { look ->
                if (photo !== next && secondPhoto !== next) return@look
                if (look.moves) {
                    if (primary && step == 1) needsPlaying()
                    return@look
                }
                var drawn: StillPhotoView? = null
                drawn = host.still(uri, next.name, look.rotation, ready = { width, height ->
                    // A wide scan is one image containing two manga pages. Split it before
                    // pairing pages as well: otherwise double-page mode pairs two whole spreads,
                    // squeezing four printed pages into the phone's width.
                    if (primary && splitWidePages && width > height * WIDE_PAGE_RATIO &&
                        next.docId !in splitRegions && photo?.docId == next.docId
                    ) splitWide(next, width, height, uri, drawn)
                }) {
                    if (photo === next || secondPhoto === next) {
                        clearStill(target, primary)
                        if (primary && step == 1) needsPlaying()
                    }
                }
                if (primary) still = drawn else secondStill = drawn
                target.addView(drawn, MATCH)
                val source = ImageSource.uri(uri)
                drawn.setImage(splitRegions[next.docId]?.let(source::region) ?: source)
                if (primary && onScreen(next)) host.stillChanged()
            }
        }

        private fun splitWide(next: ChildDoc, width: Int, height: Int, uri: Uri, drawn: StillPhotoView?) {
            val current = photos.indexOfFirst { it.docId == next.docId }
            if (current < 0 || next.docId in splitRegions) return
            val source = sourceOf(next)
            val middle = width / 2
            val firstRect = if (rightToLeft) Rect(middle, 0, width, height) else Rect(0, 0, middle, height)
            val secondRect = if (rightToLeft) Rect(0, 0, middle, height) else Rect(middle, 0, width, height)
            val first = next.copy(docId = "${source.docId}#wvhalf-a")
            val second = next.copy(docId = "${source.docId}#wvhalf-b")
            splitSources[first.docId] = source
            splitSources[second.docId] = source
            splitRegions[first.docId] = firstRect
            splitRegions[second.docId] = secondRect
            photos = photos.toMutableList().apply {
                this[current] = first
                add(current + 1, second)
            }
            photo = first
            // Keep the page already on screen in place. A full data-set refresh tears down every
            // decoded image; since wide scans are common, that made each page turn flash black.
            drawn?.setImage(ImageSource.uri(uri).region(firstRect))
            // The insertion changes how later pairs are grouped. Rebind only the pages after
            // this one so the visible pair and its already decoded first half stay undisturbed.
            val position = bindingAdapterPosition
            val following = pageCount - position - 1
            if (position != RecyclerView.NO_POSITION && following > 0) {
                pages.notifyItemRangeChanged(position + 1, following)
            }
            view.post { if (onScreen(first)) announce(view.currentItem) }
        }

        private fun needsPlaying() {
            moves = true
            // After the layout this may be part of, which a page put in mid-way would upset
            view.post(::settle)
        }

        fun clear() {
            if (playing === this) stop()
            clearStill(primaryFrame, primary = true)
            clearStill(secondaryFrame, primary = false)
            moves = false
            photo = null
            secondPhoto = null
        }

        private fun clearStill(target: FrameLayout, primary: Boolean) {
            val old = if (primary) still else secondStill
            if (primary) still = null else secondStill = null
            old?.recycle()
            target.removeAllViews()
            if (old != null && primary && photo?.let(::onScreen) == true) host.stillChanged()
        }
    }

    private inner class Pages : RecyclerView.Adapter<Page>() {
        override fun getItemCount() = pageCount

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            // A page has to fill the pager
            Page(FrameLayout(parent.context).apply { layoutParams = MATCH })

        override fun onBindViewHolder(holder: Page, position: Int) {
            val at = firstIndex(position)
            holder.bind(photos[at], photos.getOrNull(at + 1).takeIf { step == 2 })
        }

        override fun onViewRecycled(holder: Page) = holder.clear()
    }

    private companion object {
        // Real scans often include white margins and cover art, so a modestly landscape
        // ratio catches a two-page spread without splitting ordinary portrait pages.
        const val WIDE_PAGE_RATIO = 1.15f
        val MATCH get() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        fun extOf(photo: ChildDoc) = photo.name.substringAfterLast('.', "").lowercase()
    }
}

/**
 * The photos beside [photo] in the folder it was opened from, in the order the home screen lists
 * that folder in, and where [photo] is among them. Null when it wasn't opened from a folder added
 * in WaterView, or the folder can't be read.
 */
internal fun photosAround(
    resolver: ContentResolver,
    photo: Uri,
    sortOrder: Settings.SortOrder,
): Pair<List<ChildDoc>, Int>? {
    if (!DocumentsContract.isTreeUri(photo)) return null
    val folder = runCatching { DocumentsContract.findDocumentPath(resolver, photo)?.path }
        .getOrNull()?.dropLast(1)?.lastOrNull() ?: return null
    val photos = photosIn(childrenOf(resolver, photo, folder) ?: return null, sortOrder)
    val docId = DocumentsContract.getDocumentId(photo)
    val at = photos.indexOfFirst { it.docId == docId }
    return if (at < 0) null else photos to at
}

/** The pictures in a folder's listing, in the order the home screen shows them. */
internal fun photosIn(children: List<ChildDoc>, sortOrder: Settings.SortOrder): List<ChildDoc> =
    orderChildren(children, sortOrder).second.filter {
        val kind = FileKind.detect(it.name.substringAfterLast('.', "").lowercase(), it.mime.ifEmpty { null })
        kind == FileKind.IMAGE || kind == FileKind.IMAGE_WEB
    }

/**
 * Whether the picture at [uri] moves, which of the formats the tiling view draws only a WebP or a
 * PNG can, and if not, the turn its EXIF asks for.
 */
internal fun pictureLook(resolver: ContentResolver, uri: Uri, ext: String): PhotoPager.Look {
    val moves = (ext == "webp" || ext == "png") &&
        runCatching { resolver.openInputStream(uri)?.use(::isAnimated) }.getOrNull() == true
    return PhotoPager.Look(moves, if (moves) 0 else Thumbs.exifRotation(resolver, uri))
}

/** Whether [a] and [b] are documents under the same tree grant. */
internal fun inSameTree(a: Uri, b: Uri): Boolean = runCatching {
    DocumentsContract.isTreeUri(a) && DocumentsContract.isTreeUri(b) && a.authority == b.authority &&
        DocumentsContract.getTreeDocumentId(a) == DocumentsContract.getTreeDocumentId(b)
}.getOrDefault(false)
