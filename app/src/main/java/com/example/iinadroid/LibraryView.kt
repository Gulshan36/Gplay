package com.example.iinadroid

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.concurrent.Executors

/**
 * Permission prompt + library: Folders / All videos / Network tabs, search, sort,
 * NEW badges, watched-progress bars and long-press menus.
 */
class LibraryView(
    context: Context,
    private val onPermissionButton: () -> Unit,
    private val onOpenFile: () -> Unit,
    private val onOpenUrl: () -> Unit,
    private val onPlay: (List<Video>, Int) -> Unit,
    private val onVideoMenu: (Video) -> Unit,
    private val progressOf: (Video) -> Float,
    private val isNew: (Video) -> Boolean,
    private val streams: () -> List<NetworkStore.Stream>,
    private val onStreamPlay: (NetworkStore.Stream) -> Unit,
    private val onStreamMenu: (NetworkStore.Stream) -> Unit,
    private val onSortClicked: (current: Int, apply: (Int) -> Unit) -> Unit,
    private val onRefresh: () -> Unit,
    private val onAbout: () -> Unit,
    private val onThemeChanged: () -> Unit,
    private val downloadInfo: (String) -> Downloads.Info?,
) : FrameLayout(context) {

    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private val bg = Ui.BG
    private val accent = Ui.ACCENT
    private val muted = Ui.MUTED
    private val ui = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3)
    private val thumbs = object : LruCache<Long, Bitmap>(96) {}

    private sealed class Item {
        class Folder(val name: String, val count: Int, val newCount: Int, val cover: Video?) : Item()
        class FolderGroup(val folders: List<Folder>) : Item()
        class ClipPair(val a: Video, val b: Video?) : Item()
        class Clip(val video: Video) : Item()
        class Net(val stream: NetworkStore.Stream) : Item()
        object Add : Item()
    }

    private var all: List<Video> = emptyList()
    private var items: List<Item> = emptyList()
    private var shownVideos: List<Video> = emptyList()
    private val prefs = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private var tab = prefs.getInt("tab", 0) // 0 folders, 1 all videos, 2 network
    private var folder: String? = null
    private var query = ""
    var sortMode = prefs.getInt("sort", 0) // 0 newest, 1 name, 2 size, 3 duration
    private var folderLayout = prefs.getInt("folderLayout2", 2)  // 0 big cards, 1 compact grid, 2 list (default)
    private var showCovers = prefs.getBoolean("covers", false)
    private var sortReverse = prefs.getBoolean("sortReverse", false)
    private var videoLayout = prefs.getInt("videoLayout", 0)          // 0 list, 1 grid
    private var filterMode = prefs.getInt("filter", 0)                // 0 all, 1 new, 2 in progress
    private var showInfo = prefs.getBoolean("showInfo", true)         // size and type line
    private var showDuration = prefs.getBoolean("showDuration", true) // duration badge on thumbnails
    private var sheetTab = 0

    private val list = ListView(context)
    private val empty = TextView(context)
    private val permission = LinearLayout(context)
    private val permissionText = TextView(context)
    private val permissionButton = TextView(context)
    private val title = TextView(context)
    private val count = TextView(context)
    private val backArrow = ImageView(context)
    private val logo = ImageView(context)
    private val tabViews = listOf(TextView(context), TextView(context), TextView(context))
    private val search = EditText(context)
    private val searchBar = LinearLayout(context)
    private val adapterImpl = Adapter()

    init {
        setBackgroundColor(bg)
        addView(View(context).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Ui.alpha(Ui.ACCENT, 0x4D), Ui.alpha(Ui.ACCENT, 0x1A), 0x00000000))
        }, LayoutParams(-1, dp(300)))
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // ---- header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(4), dp(4))
        }
        backArrow.apply {
            setImageResource(R.drawable.ic_back)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            visibility = View.GONE
            setOnClickListener { handleBack() }
        }
        logo.setImageDrawable(Ui.logo(context))
        header.addView(logo, LinearLayout.LayoutParams(dp(36), dp(36)).apply { leftMargin = dp(4) })
        header.addView(backArrow, LinearLayout.LayoutParams(dp(40), dp(40)))
        val titles = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        title.apply {
            text = "Videos"; setTextColor(Ui.TEXT); textSize = 28f
            setTypeface(typeface, Typeface.BOLD); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        count.apply { setTextColor(muted); textSize = 13f }
        titles.addView(title); titles.addView(count)
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8) })
        header.addView(headerIcon(R.drawable.ic_search, "Search") { toggleSearch() })
        header.addView(headerIcon(R.drawable.ic_tune, "View and sort") { showLayoutSheet() })
        header.addView(headerIcon(R.drawable.ic_more, "More") { showMoreMenu(it) })
        col.addView(header, LinearLayout.LayoutParams(-1, -2))

        // ---- search
        searchBar.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dp(16), 0, dp(16), dp(4))
        }
        search.apply {
            hint = "Search"; setHintTextColor(muted); setTextColor(Color.WHITE)
            textSize = 15f; isSingleLine = true
            background = Ui.rect(context, Ui.SURFACE, 14)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim().orEmpty(); rebuild() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        searchBar.addView(search, LinearLayout.LayoutParams(-1, -2))
        col.addView(searchBar, LinearLayout.LayoutParams(-1, -2))

        // ---- tabs: one segmented control
        val tabs = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.rect(context, Ui.SURFACE, 16)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        listOf("Folders", "All videos", "Network").forEachIndexed { i, label ->
            tabViews[i].apply {
                text = label; textSize = 14f; gravity = Gravity.CENTER
                setPadding(0, dp(9), 0, dp(9))
                setOnClickListener { if (tab != i) { tab = i; folder = null; prefs.edit().putInt("tab", i).apply(); fadeRebuild() } }
            }
            tabs.addView(tabViews[i], LinearLayout.LayoutParams(0, -2, 1f))
        }
        col.addView(tabs, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(8), dp(16), dp(6)) })

        list.apply {
            divider = null
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT) // rows draw their own ripple
            setBackgroundColor(Color.TRANSPARENT)
            setCacheColorHint(Color.TRANSPARENT)
            clipToPadding = false
            setPadding(dp(12), dp(4), dp(12), dp(24))
            adapter = adapterImpl
            setOnItemClickListener { _, _, pos, _ -> onItem(items[pos]) }
            setOnItemLongClickListener { _, _, pos, _ ->
                when (val it = items[pos]) {
                    is Item.Clip -> { onVideoMenu(it.video); true }
                    is Item.Net -> { onStreamMenu(it.stream); true }
                    else -> false
                }
            }
        }
        col.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(TextView(context).apply {
            text = "Developed by Gulshan Kumar"
            setTextColor(0xFF6E6E73.toInt()); textSize = 11f; gravity = Gravity.CENTER
            letterSpacing = 0.04f
            setPadding(0, dp(6), 0, dp(10))
        }, LinearLayout.LayoutParams(-1, -2))
        addView(col, LayoutParams(-1, -1))

        empty.apply {
            text = "No videos found."
            setTextColor(muted); textSize = 16f; gravity = Gravity.CENTER
            visibility = View.GONE
        }
        addView(empty, LayoutParams(-2, -2, Gravity.CENTER))

        // ---- permission screen
        permission.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
            setPadding(dp(32), dp(32), dp(32), dp(32))
            visibility = View.GONE
            isClickable = true
        }
        permission.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_video)
            setColorFilter(accent)
        }, LinearLayout.LayoutParams(dp(72), dp(72)))
        permission.addView(TextView(context).apply {
            text = "Allow access to your videos"
            setTextColor(Color.WHITE); textSize = 22f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(20), 0, dp(8))
        })
        permissionText.apply {
            text = "Gplay needs media permission to find and play the videos stored on your device."
            setTextColor(muted); textSize = 15f; gravity = Gravity.CENTER
        }
        permission.addView(permissionText)
        permissionButton.apply {
            text = "Allow access"
            setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = GradientDrawable().apply { cornerRadius = dp(24).toFloat(); setColor(accent) }
            setPadding(dp(32), dp(12), dp(32), dp(12))
            setOnClickListener { onPermissionButton() }
        }
        permission.addView(permissionButton, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(24) })
        permission.addView(TextView(context).apply {
            text = "Open a file instead"
            setTextColor(accent); textSize = 14f; gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { onOpenFile() }
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(12) })
        permission.addView(TextView(context).apply {
            text = "Developed by Gulshan Kumar"
            setTextColor(0xFF6E6E73.toInt()); textSize = 11f; gravity = Gravity.CENTER
            letterSpacing = 0.04f
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(36) })
        addView(permission, LayoutParams(-1, -1))

        styleTabs()
    }

    private fun headerIcon(res: Int, tip: String, onClick: (View) -> Unit) = ImageView(context).apply {
        setImageResource(res)
        setPadding(dp(11), dp(11), dp(11), dp(11))
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x14FFFFFF) }
        layoutParams = LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(6) }
        tooltipText = tip
        contentDescription = tip
        setOnClickListener { onClick(this) }
    }

    private fun showMoreMenu(anchor: View) {
        val popup = android.widget.PopupMenu(context, anchor)
        popup.menu.add(0, 1, 0, "Open file…")
        popup.menu.add(0, 2, 1, "Add network stream…")
        popup.menu.add(0, 3, 2, "Refresh")
        popup.menu.add(0, 5, 3, "Theme & layout…")
        popup.menu.add(0, 4, 4, "About Gplay")
        popup.setOnMenuItemClickListener {
            when (it.itemId) { 1 -> onOpenFile(); 2 -> onOpenUrl(); 3 -> onRefresh(); 5 -> showLayoutSheet(); else -> onAbout() }
            true
        }
        popup.show()
    }

    private fun toggleSearch() {
        val show = searchBar.visibility != View.VISIBLE
        searchBar.visibility = if (show) View.VISIBLE else View.GONE
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (show) {
            search.requestFocus(); imm.showSoftInput(search, 0)
        } else {
            search.setText(""); imm.hideSoftInputFromWindow(search.windowToken, 0)
        }
    }

    private fun styleTabs() {
        tabViews.forEachIndexed { i, tv ->
            val on = i == tab
            tv.setTextColor(if (on) Color.WHITE else muted)
            tv.setTypeface(Typeface.DEFAULT, if (on) Typeface.BOLD else Typeface.NORMAL)
            tv.background = if (on) Ui.accentFill(context, 13) else null
        }
    }

    // ---------------------------------------------------------------- state

    /** Returns true if the back press was consumed (closing a folder or the search box). */
    fun handleBack(): Boolean {
        if (searchBar.visibility == View.VISIBLE) { toggleSearch(); return true }
        if (folder != null) { folder = null; fadeRebuild(); return true }
        return false
    }

    fun showPermissionNeeded(permanentlyDenied: Boolean) {
        permission.visibility = View.VISIBLE
        permissionButton.text = if (permanentlyDenied) "Open settings" else "Allow access"
        permissionText.text = if (permanentlyDenied)
            "Permission was denied. Enable \"Videos\" / \"Storage\" in the app settings to see your library."
        else "Gplay needs media permission to find and play the videos stored on your device."
    }

    fun setVideos(v: List<Video>) {
        permission.visibility = View.GONE
        all = v
        rebuild()
    }

    /** Re-read saved streams / progress / NEW state without reloading the video list. */
    fun refreshProgress() = rebuild()

    private fun sorted(v: List<Video>): List<Video> {
        val base = when (sortMode) {
            1 -> v.sortedBy { it.name.lowercase() }
            2 -> v.sortedByDescending { it.size }
            3 -> v.sortedByDescending { it.durationMs }
            else -> v.sortedByDescending { it.dateAdded }
        }
        return if (sortReverse) base.reversed() else base
    }

    private fun passesFilter(v: Video) = when (filterMode) {
        1 -> isNew(v)
        2 -> progressOf(v) in 0.0001f..0.999f
        else -> true
    }

    private fun filterName() = when (filterMode) { 1 -> "New"; 2 -> "In progress"; else -> "" }

    /** Rebuild the list with a short cross-fade so tab / folder changes feel smooth. */
    private fun fadeRebuild() {
        list.animate().cancel()
        list.alpha = 0f
        rebuild()
        list.animate().alpha(1f).setDuration(170).start()
    }

    private fun rebuild() {
        styleTabs()
        val q = query.lowercase()
        backArrow.visibility = if (folder != null) View.VISIBLE else View.GONE
        logo.visibility = if (folder != null) View.GONE else View.VISIBLE
        empty.text = "No videos found.\nTap ⋮ → Open file, or add a network stream."

        if (tab == 2) {
            title.text = "Network"
            val saved = streams().filter { q.isEmpty() || it.title.lowercase().contains(q) || it.url.lowercase().contains(q) }
            items = listOf<Item>(Item.Add) + saved.map { Item.Net(it) }
            count.text = "${saved.size} saved stream" + if (saved.size == 1) "" else "s"
            empty.visibility = View.GONE
            adapterImpl.notifyDataSetChanged()
            return
        }

        val searched = if (q.isEmpty()) all else all.filter { it.name.lowercase().contains(q) || it.folder.lowercase().contains(q) }
        val matching = if (filterMode == 0) searched else searched.filter { passesFilter(it) }
        val tag = if (filterMode == 0) "" else " · ${filterName()}"
        if (tab == 0 && folder == null && q.isEmpty()) {
            title.text = "Videos"
            val groups = matching.groupBy { it.folder }
            val folders = groups.entries.map { (name, vids) ->
                Item.Folder(name, vids.size, vids.count(isNew), vids.maxByOrNull { v -> v.dateAdded })
            }
            val byKey: (Item.Folder) -> Comparable<*> = { f ->
                val vids = groups[f.name].orEmpty()
                when (sortMode) {
                    1 -> f.name.lowercase()
                    2 -> -vids.sumOf { it.size }
                    3 -> -vids.sumOf { it.durationMs }
                    else -> -(vids.maxOfOrNull { it.dateAdded } ?: 0L)
                }
            }
            @Suppress("UNCHECKED_CAST")
            val ordered = folders.sortedWith(compareBy(byKey as (Item.Folder) -> Comparable<Any>))
                .let { if (sortReverse) it.reversed() else it }
            items = ordered.chunked(folderColumns()).map { Item.FolderGroup(it) }
            shownVideos = emptyList()
            val fresh = matching.count(isNew)
            count.text = "${groups.size} folders · ${matching.size} videos" + (if (fresh > 0 && filterMode == 0) " · $fresh new" else tag)
        } else {
            val src = if (folder != null) matching.filter { it.folder == folder } else matching
            shownVideos = sorted(src)
            items = if (videoLayout == 1) shownVideos.chunked(2).map { Item.ClipPair(it[0], it.getOrNull(1)) }
                    else shownVideos.map { Item.Clip(it) }
            title.text = folder ?: "All videos"
            count.text = "${shownVideos.size} videos$tag"
        }
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        adapterImpl.notifyDataSetChanged()
    }

    private fun onItem(item: Item) {
        when (item) {
            is Item.Folder -> { folder = item.name; fadeRebuild() }
            is Item.FolderGroup, is Item.ClipPair -> {}
            is Item.Clip -> onPlay(shownVideos, shownVideos.indexOf(item.video))
            is Item.Net -> onStreamPlay(item.stream)
            Item.Add -> onOpenUrl()
        }
    }

    // ---------------------------------------------------------------- rows

    private inner class Adapter : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(p: Int) = items[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 4
        override fun getItemViewType(p: Int) = when (items[p]) { is Item.FolderGroup -> 1; is Item.ClipPair -> 2; Item.Add -> 3; else -> 0 }
        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val item = items[p]
            if (item is Item.FolderGroup) {
                val row = (convert as? FolderRow) ?: FolderRow(context)
                row.bind(item)
                return row
            }
            if (item is Item.ClipPair) {
                val row = (convert as? ClipGridRow) ?: ClipGridRow(context)
                row.bind(item)
                return row
            }
            if (item === Item.Add) return (convert as? AddRow) ?: AddRow(context)
            val row = (convert as? Row) ?: Row(context)
            row.bind(item)
            return row
        }
    }

    /** Top of the Network tab: add a stream, and the formats that can be played. */
    private inner class AddRow(ctx: Context) : LinearLayout(ctx) {
        init {
            orientation = VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), Ui.rect(ctx, Ui.SURFACE, 20), null)
            val head = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(FrameLayout(ctx).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.ACCENT_HI, Ui.ACCENT_LO)).apply { shape = GradientDrawable.OVAL }
                addView(ImageView(ctx).apply { setImageResource(R.drawable.ic_add); setColorFilter(Color.WHITE) }, LayoutParams(dp(26), dp(26), Gravity.CENTER))
            }, LayoutParams(dp(46), dp(46)))
            head.addView(TextView(ctx).apply {
                text = "Add network stream"; textSize = 17f; setTextColor(Ui.TEXT); setTypeface(typeface, Typeface.BOLD)
            }, LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) })
            head.addView(TextView(ctx).apply { text = "\u203A"; textSize = 26f; setTextColor(Ui.MUTED) })
            addView(head)
            setOnClickListener { onOpenUrl() }
        }
    }

    /** Frame that always keeps a 16:9 shape from its width. */
    private class AspectFrame(ctx: Context) : FrameLayout(ctx) {
        override fun onMeasure(w: Int, h: Int) {
            val width = MeasureSpec.getSize(w)
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY))
        }
    }

    /** Grid mode: two video cards per row (big thumbnail, then name and details underneath). */
    private inner class ClipGridRow(ctx: Context) : LinearLayout(ctx) {
        private val cards = listOf(ClipCard(ctx), ClipCard(ctx))
        init {
            orientation = HORIZONTAL
            setPadding(dp(2), dp(6), dp(2), dp(6))
            addView(cards[0], LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
            addView(cards[1], LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
        }
        fun bind(p: Item.ClipPair) {
            cards[0].bind(p.a)
            if (p.b != null) { cards[1].visibility = View.VISIBLE; cards[1].bind(p.b) } else cards[1].visibility = View.INVISIBLE
        }
    }

    private inner class ClipCard(ctx: Context) : LinearLayout(ctx) {
        private val box = AspectFrame(ctx)
        private val thumb = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val duration = TextView(ctx).apply {
            setTextColor(Color.WHITE); textSize = 10.5f
            setPadding(dp(5), dp(1), dp(5), dp(1))
            background = Ui.rect(ctx, 0xB0000000.toInt(), 5)
        }
        private val fresh = TextView(ctx).apply {
            text = "NEW"; setTextColor(Color.WHITE); textSize = 9f; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(5), dp(1), dp(5), dp(1)); background = Ui.rect(ctx, 0xFFFF5A5F.toInt(), 5)
        }
        private val track = View(ctx).apply { setBackgroundColor(0x66000000) }
        private val fill = View(ctx).apply { setBackgroundColor(Ui.TEAL); pivotX = 0f }
        private val title = TextView(ctx).apply {
            setTextColor(Ui.TEXT); textSize = 13.5f; maxLines = 2; ellipsize = TextUtils.TruncateAt.MIDDLE
            setTypeface(typeface, Typeface.BOLD); setPadding(dp(2), dp(7), dp(2), 0)
        }
        private val meta = TextView(ctx).apply { setTextColor(muted); textSize = 11.5f; setPadding(dp(2), dp(1), dp(2), 0) }
        private var cur = -1L
        private var video: Video? = null

        init {
            orientation = VERTICAL
            box.background = Ui.rect(ctx, Ui.SURFACE2, 16)
            box.clipToOutline = true
            box.addView(thumb, FrameLayout.LayoutParams(-1, -1))
            box.addView(duration, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(6), dp(8)) })
            box.addView(fresh, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(dp(6), dp(6), 0, 0) })
            box.addView(track, FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM))
            box.addView(fill, FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM))
            addView(box, LayoutParams(-1, -2))
            addView(title); addView(meta)
            foreground = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, ColorDrawable(Color.WHITE))
            setOnClickListener { video?.let { v -> onPlay(shownVideos, shownVideos.indexOf(v)) } }
            setOnLongClickListener { video?.let { v -> onVideoMenu(v) }; true }
        }

        fun bind(v: Video) {
            video = v
            cur = v.id
            title.text = v.name.substringBeforeLast('.')
            meta.text = listOfNotNull(v.name.substringAfterLast('.', "").uppercase().takeIf { it.isNotEmpty() }, VideoRepo.fmtSize(v.size)).joinToString(" · ")
            meta.visibility = if (showInfo) View.VISIBLE else View.GONE
            duration.text = VideoRepo.fmtDuration(v.durationMs)
            duration.visibility = if (showDuration) View.VISIBLE else View.GONE
            fresh.visibility = if (isNew(v)) View.VISIBLE else View.GONE
            val pr = progressOf(v)
            track.visibility = if (pr > 0f) View.VISIBLE else View.GONE
            fill.visibility = track.visibility
            fill.scaleX = pr
            thumb.setImageDrawable(null)
            thumbs.get(v.id)?.let { thumb.setImageBitmap(it); return }
            pool.execute {
                val bmp = loadThumb(v) ?: return@execute
                thumbs.put(v.id, bmp)
                ui.post { if (cur == v.id) thumb.setImageBitmap(bmp) }
            }
        }
    }

    private fun folderColumns() = when (folderLayout) { 0 -> 2; 1 -> 3; else -> 1 }

    /** One row of folder cards: 2 big cards, 3 compact tiles or a single list row, depending on the chosen layout. */
    private inner class FolderRow(ctx: Context) : LinearLayout(ctx) {
        private val cards = List(3) { FolderCard(ctx) }
        init {
            orientation = HORIZONTAL
            setPadding(dp(2), dp(5), dp(2), dp(5))
            cards.forEach { addView(it, LayoutParams(0, dp(128), 1f)) }
        }
        fun bind(g: Item.FolderGroup) {
            val cols = folderColumns()
            val h = when (folderLayout) { 0 -> 128; 1 -> 104; else -> 58 }
            val pv = if (folderLayout == 2) 1 else 5
            setPadding(dp(2), dp(pv), dp(2), dp(pv))
            cards.forEachIndexed { i, card ->
                val lp = card.layoutParams as LayoutParams
                lp.height = dp(h)
                lp.leftMargin = if (i == 0) 0 else dp(if (cols == 3) 5 else 6)
                lp.rightMargin = 0
                card.layoutParams = lp
                when {
                    i >= cols -> card.visibility = View.GONE
                    i >= g.folders.size -> card.visibility = View.INVISIBLE
                    else -> { card.visibility = View.VISIBLE; card.bind(g.folders[i]) }
                }
            }
        }
    }

    private inner class FolderCard(ctx: Context) : FrameLayout(ctx) {
        private val cover = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.55f }
        private val shade = View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE60B0D14.toInt(), 0x000B0D14))
        }
        private val glyph = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_folder); setColorFilter(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }
        }
        private val name = TextView(ctx).apply {
            setTextColor(Ui.TEXT); setTypeface(typeface, Typeface.BOLD)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        private val meta = TextView(ctx).apply { setTextColor(0xCCFFFFFF.toInt()); textSize = 12f }
        private val fresh = TextView(ctx).apply {
            setTextColor(Color.WHITE); textSize = 10f; setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(7), dp(2), dp(7), dp(2))
            background = Ui.rect(ctx, 0xFFFF5A5F.toInt(), 8)
        }
        private val txt = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        private var cur = -1L
        private var folderName = ""

        init {
            clipToOutline = true
            addView(cover, LayoutParams(-1, -1))
            addView(shade, LayoutParams(-1, -1))
            addView(glyph, LayoutParams(dp(30), dp(30)))
            addView(fresh, LayoutParams(-2, -2))
            txt.addView(name); txt.addView(meta)
            addView(txt, LayoutParams(-1, -2))
            foreground = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null, ColorDrawable(Color.WHITE))
            setOnClickListener { folder = folderName; fadeRebuild() }
        }

        /** Re-place the pieces for the current layout (big card, compact tile or MX-style list row). */
        private fun arrange() {
            fun lp(v: View) = v.layoutParams as LayoutParams
            val list = folderLayout == 2
            val compact = folderLayout == 1
            shade.visibility = if (list) View.GONE else View.VISIBLE
            if (list) {
                // plain list row: big muted folder, name and count, red count badge on the folder corner
                glyph.setImageResource(R.drawable.ic_folder_mx)
                glyph.setColorFilter(Ui.ACCENT)
                glyph.alpha = 0.9f
                glyph.background = null
                glyph.setPadding(0, 0, 0, 0)
                lp(glyph).apply { width = dp(46); height = dp(38); gravity = Gravity.CENTER_VERTICAL or Gravity.START; setMargins(dp(8), 0, 0, 0) }
                lp(fresh).apply { gravity = Gravity.TOP or Gravity.START; setMargins(dp(42), dp(5), 0, 0) }
                lp(txt).apply { gravity = Gravity.CENTER_VERTICAL; setMargins(dp(70), 0, dp(12), 0) }
                name.textSize = 16f; name.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
                meta.textSize = 13f; meta.setTextColor(Ui.MUTED)
            } else {
                glyph.setImageResource(R.drawable.ic_folder)
                glyph.alpha = 1f
                glyph.setColorFilter(0xFFFFFFFF.toInt())
                glyph.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0x33FFFFFF) }
                val gs = if (compact) 26 else 30
                glyph.setPadding(dp(6), dp(6), dp(6), dp(6))
                lp(glyph).apply { width = dp(gs); height = dp(gs); gravity = Gravity.TOP or Gravity.START; setMargins(dp(12), dp(12), 0, 0) }
                lp(fresh).apply { gravity = Gravity.TOP or Gravity.END; setMargins(0, dp(12), dp(12), 0) }
                lp(txt).apply { gravity = Gravity.BOTTOM; setMargins(dp(if (compact) 10 else 14), 0, dp(8), dp(if (compact) 9 else 12)) }
                name.textSize = if (compact) 14f else 16f
                name.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                meta.textSize = if (compact) 11f else 12f; meta.setTextColor(0xCCFFFFFF.toInt())
            }
            fresh.minWidth = if (list) dp(20) else 0
            fresh.gravity = Gravity.CENTER
            fresh.setPadding(dp(if (list) 5 else 7), dp(2), dp(if (list) 5 else 7), dp(2))
            fresh.background = if (list) Ui.rect(context, 0xFFFF3B5C.toInt(), 10) else Ui.rect(context, 0xFFFF5A5F.toInt(), 8)
            glyph.requestLayout(); fresh.requestLayout(); txt.requestLayout()
        }

        fun bind(f: Item.Folder) {
            folderName = f.name
            arrange()
            val list = folderLayout == 2
            background = if (list) null else
                GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.FOLDER_1, Ui.FOLDER_2))
                    .apply { cornerRadius = dp(22).toFloat() }
            name.text = f.name
            meta.text = "${f.count} video" + if (f.count == 1) "" else "s"
            fresh.visibility = if (f.newCount > 0) View.VISIBLE else View.GONE
            fresh.text = if (folderLayout == 1 || list) "${f.newCount}" else "${f.newCount} NEW"
            val v = f.cover
            cur = v?.id ?: -1
            cover.setImageDrawable(null)
            cover.visibility = if (showCovers && !list) View.VISIBLE else View.GONE
            if (v == null || !showCovers || list) return
            thumbs.get(v.id)?.let { cover.setImageBitmap(it); return }
            pool.execute {
                val bmp = loadThumb(v) ?: return@execute
                thumbs.put(v.id, bmp)
                ui.post { if (cur == v.id) cover.setImageBitmap(bmp) }
            }
        }
    }

    /** Bottom sheet with three tabs: Layout, Sort & filter, Look. Changes apply instantly behind the sheet. */
    private fun showLayoutSheet() {
        val dlg = android.app.Dialog(context, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
        val sheet = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.topRounded(context, 0xFF11141F.toInt(), 28)
            setPadding(dp(16), dp(10), dp(16), dp(20))
        }
        sheet.addView(View(context).apply { background = Ui.rect(context, 0x33FFFFFF, 3) },
            LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(10) })
        sheet.addView(TextView(context).apply {
            text = "View & sort"; textSize = 20f; setTextColor(Ui.TEXT); setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(6), 0, 0, dp(10))
        })
        val tabBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.rect(context, Ui.SURFACE, 14); setPadding(dp(3), dp(3), dp(3), dp(3))
        }
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        sheet.addView(tabBar); sheet.addView(body)

        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        fun group(title: String?, content: View) {
            if (title != null) body.addView(TextView(context).apply {
                text = title.uppercase(); textSize = 11f; letterSpacing = 0.12f; setTextColor(Ui.MUTED)
                setTypeface(typeface, Typeface.BOLD); setPadding(dp(8), dp(14), 0, dp(6))
            })
            body.addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.rect(context, Ui.SURFACE, 18)
                setPadding(dp(8), dp(8), dp(8), dp(8))
                addView(content)
            }, LinearLayout.LayoutParams(-1, -2).apply { if (title == null) topMargin = dp(10) })
        }
        lateinit var render: () -> Unit
        fun changed() { rebuild(); render() }

        fun tiles(items: List<Triple<String, Int, Int>>, current: Int, pick: (Int) -> Unit) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            items.forEach { (name, icon, id) ->
                val on = current == id
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    background = if (on) Ui.accentFill(context, 14) else Ui.rect(context, Ui.SURFACE2, 14)
                    setPadding(0, dp(11), 0, dp(9))
                    addView(ImageView(context).apply {
                        setImageResource(icon); setColorFilter(if (on) Color.WHITE else 0xFF9AA0B8.toInt())
                    }, LinearLayout.LayoutParams(dp(28), dp(28)))
                    addView(TextView(context).apply {
                        text = name; textSize = 12.5f; gravity = Gravity.CENTER
                        setTextColor(if (on) Color.WHITE else 0xFFC4C8D8.toInt())
                        if (on) setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(5), 0, 0)
                    })
                    setOnClickListener { pick(id) }
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
            }
        }

        fun segmented(names: List<String>, current: Int, textSp: Float = 13.5f, pick: (Int) -> Unit) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.rect(context, Ui.SURFACE2, 14); setPadding(dp(3), dp(3), dp(3), dp(3))
            names.forEachIndexed { id, name ->
                val on = current == id
                addView(TextView(context).apply {
                    text = name; textSize = textSp; gravity = Gravity.CENTER; maxLines = 1
                    setTextColor(if (on) Color.WHITE else 0xFFB4B9CC.toInt())
                    if (on) setTypeface(typeface, Typeface.BOLD)
                    background = if (on) Ui.accentFill(context, 11) else null
                    setPadding(0, dp(10), 0, dp(10))
                    setOnClickListener { pick(id) }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }

        fun switchRow(label: String, sub: String?, on: Boolean, change: (Boolean) -> Unit) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(6), dp(4), dp(6))
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(context).apply { text = label; textSize = 14.5f; setTextColor(Ui.TEXT) })
            if (sub != null) col.addView(TextView(context).apply { text = sub; textSize = 12f; setTextColor(Ui.MUTED) })
            addView(col, LinearLayout.LayoutParams(0, -2, 1f))
            addView(android.widget.Switch(context).apply {
                isChecked = on
                thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, 0xFFB4B9CC.toInt()))
                trackTintList = ColorStateList(states, intArrayOf(Ui.ACCENT, 0xFF3A4156.toInt()))
                setOnCheckedChangeListener { _, v -> change(v) }
            })
        }

        fun layoutTab() {
            group("Folders", tiles(listOf(Triple("Cards", R.drawable.ic_layout_cards, 0), Triple("Compact", R.drawable.ic_layout_compact, 1),
                Triple("List", R.drawable.ic_layout_list, 2)), folderLayout) { folderLayout = it; prefs.edit().putInt("folderLayout2", it).apply(); changed() })
            group("Videos", tiles(listOf(Triple("List", R.drawable.ic_layout_list, 0), Triple("Grid", R.drawable.ic_layout_cards, 1)),
                videoLayout) { videoLayout = it; prefs.edit().putInt("videoLayout", it).apply(); changed() })
            val opts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            opts.addView(switchRow("File size and type", "Second line under each video name", showInfo) {
                showInfo = it; prefs.edit().putBoolean("showInfo", it).apply(); rebuild() })
            opts.addView(switchRow("Duration on thumbnail", null, showDuration) {
                showDuration = it; prefs.edit().putBoolean("showDuration", it).apply(); rebuild() })
            opts.addView(switchRow("Video frame on folder cards", "Cards and Compact layouts only", showCovers) {
                showCovers = it; prefs.edit().putBoolean("covers", it).apply(); rebuild() })
            group("Show", opts)
        }

        fun sortTab() {
            group("Sort by", segmented(listOf("Newest", "Name", "Size", "Length"), sortMode) {
                sortMode = it; prefs.edit().putInt("sort", it).apply(); changed() })
            val order = listOf(
                listOf("Newest first", "Oldest first"), listOf("A → Z", "Z → A"),
                listOf("Largest first", "Smallest first"), listOf("Longest first", "Shortest first"))[sortMode]
            group("Order", segmented(order, if (sortReverse) 1 else 0) {
                sortReverse = it == 1; prefs.edit().putBoolean("sortReverse", sortReverse).apply(); changed() })
            group("Show only", segmented(listOf("All", "New", "In progress"), filterMode) {
                filterMode = it; prefs.edit().putInt("filter", it).apply(); changed() })
            body.addView(TextView(context).apply {
                text = "Reset to defaults"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(Ui.TEAL)
                setPadding(0, dp(16), 0, dp(4))
                setOnClickListener {
                    sortMode = 0; sortReverse = false; filterMode = 0; videoLayout = 0; folderLayout = 2
                    showInfo = true; showDuration = true; showCovers = false
                    prefs.edit().putInt("sort", 0).putBoolean("sortReverse", false).putInt("filter", 0).putInt("videoLayout", 0)
                        .putInt("folderLayout2", 2).putBoolean("showInfo", true).putBoolean("showDuration", true)
                        .putBoolean("covers", false).apply()
                    changed()
                }
            })
        }

        fun lookTab() {
            val swatches = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until Ui.count) {
                val on = Ui.index == i
                val (hi, lo) = Ui.swatch(i)
                swatches.addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                    addView(FrameLayout(context).apply {
                        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(hi, lo))
                            .apply { shape = GradientDrawable.OVAL; if (on) setStroke(dp(3), Ui.TEXT) }
                        if (on) addView(ImageView(context).apply { setImageResource(R.drawable.ic_check); setColorFilter(Color.WHITE) },
                            LayoutParams(dp(20), dp(20), Gravity.CENTER))
                    }, LinearLayout.LayoutParams(dp(42), dp(42)))
                    addView(TextView(context).apply {
                        text = Ui.name(i); textSize = 11f; setTextColor(if (on) Ui.TEXT else Ui.MUTED); setPadding(0, dp(5), 0, 0)
                    })
                    setOnClickListener { if (!on) { dlg.dismiss(); Ui.select(context, i); onThemeChanged() } }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            group("Theme color", swatches)
            body.addView(TextView(context).apply {
                text = "The theme colours the whole app and its home-screen icon."
                textSize = 12f; setTextColor(Ui.MUTED); setPadding(dp(8), dp(10), dp(8), 0)
            })
        }

        render = {
            tabBar.removeAllViews()
            listOf("Layout", "Sort & filter", "Look").forEachIndexed { i, name ->
                val on = sheetTab == i
                tabBar.addView(TextView(context).apply {
                    text = name; textSize = 13.5f; gravity = Gravity.CENTER; maxLines = 1
                    setTextColor(if (on) Color.WHITE else 0xFFB4B9CC.toInt())
                    if (on) setTypeface(typeface, Typeface.BOLD)
                    background = if (on) Ui.accentFill(context, 11) else null
                    setPadding(0, dp(10), 0, dp(10))
                    setOnClickListener { sheetTab = i; render() }
                }, LinearLayout.LayoutParams(0, -2, 1f))
            }
            body.removeAllViews()
            when (sheetTab) { 0 -> layoutTab(); 1 -> sortTab(); else -> lookTab() }
        }
        render()

        dlg.setContentView(android.widget.ScrollView(context).apply { addView(sheet) })
        dlg.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(minOf(dp(560), context.resources.displayMetrics.widthPixels - dp(12)), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.55f)
        }
        dlg.show()
    }

    private inner class Row(ctx: Context) : LinearLayout(ctx) {
        val box = FrameLayout(ctx)
        val thumb = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val tileIcon = ImageView(ctx).apply { setColorFilter(Ui.ACCENT_HI) }
        val tileText = TextView(ctx).apply {
            gravity = Gravity.CENTER; textSize = 17f; setTextColor(Ui.TEAL); setTypeface(typeface, Typeface.BOLD); letterSpacing = 0.06f
        }
        val title = TextView(ctx).apply {
            setTextColor(Ui.TEXT); textSize = 15f; maxLines = 2
            setTypeface(typeface, Typeface.BOLD)
            ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        val meta = TextView(ctx).apply {
            setTextColor(muted); textSize = 12f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val badge = TextView(ctx).apply {
            setTextColor(Color.WHITE); textSize = 10f
            setPadding(dp(4), dp(1), dp(4), dp(1))
            background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(0xB0000000.toInt()) }
        }
        val newBadge = TextView(ctx).apply {
            text = "NEW"; setTextColor(Color.WHITE); textSize = 9f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(5), dp(1), dp(5), dp(1))
            background = GradientDrawable().apply { cornerRadius = dp(4).toFloat(); setColor(0xFFFF5A5F.toInt()) }
        }
        val progressTrack = View(ctx).apply { setBackgroundColor(0x66000000) }
        val progressFill = View(ctx).apply { setBackgroundColor(Ui.TEAL); pivotX = 0f }
        var current = -1L

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(10), dp(8))
            setCardStyle(false)
            box.clipToOutline = true
            box.addView(thumb, FrameLayout.LayoutParams(-1, -1))
            box.addView(tileIcon, FrameLayout.LayoutParams(-1, -1))
            box.addView(tileText, FrameLayout.LayoutParams(-1, -1))
            box.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, dp(4), dp(7))
            })
            box.addView(progressTrack, FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM))
            box.addView(progressFill, FrameLayout.LayoutParams(-1, dp(3), Gravity.BOTTOM))
            box.addView(newBadge, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                setMargins(dp(4), dp(4), 0, 0)
            })
            addView(box, LayoutParams(dp(136), dp(78)))
            val text = LinearLayout(ctx).apply { orientation = VERTICAL }
            text.addView(title); text.addView(meta)
            addView(text, LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) })
        }

        /** Videos sit directly on the page; stream tiles get a soft card. */
        private fun setCardStyle(card: Boolean) {
            background = InsetDrawable(
                RippleDrawable(
                    ColorStateList.valueOf(0x33FFFFFF),
                    if (card) Ui.rect(context, Ui.SURFACE, 18) else null, null
                ), dp(2), dp(2), dp(2), dp(2)
            )
            box.background = if (card) GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.soft(0x33), Ui.soft(0x22))).apply { cornerRadius = dp(14).toFloat() }
            else Ui.rect(context, Ui.SURFACE2, 14)
        }

        /** Small square tile for folders / streams, wide thumbnail for videos. */
        private fun tile(small: Boolean) {
            val w = dp(136)
            val h = dp(78)
            (box.layoutParams as LayoutParams).apply { width = w; height = h }
            box.requestLayout()
        }

        private fun asIcon(res: Int, fresh: Boolean, padH: Int = 34, padV: Int = 14) {
            current = -1
            tileText.visibility = View.GONE
            meta.visibility = View.VISIBLE
            setCardStyle(true)
            tile(true)
            tileIcon.setImageResource(res)
            tileIcon.setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
            tileIcon.visibility = View.VISIBLE
            thumb.visibility = View.GONE
            badge.visibility = View.GONE
            progressTrack.visibility = View.GONE
            progressFill.visibility = View.GONE
            newBadge.visibility = if (fresh) View.VISIBLE else View.GONE
            (newBadge.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = Gravity.TOP or Gravity.END; setMargins(0, dp(2), dp(2), 0)
            }
        }

        fun bind(item: Item) {
            when (item) {
                is Item.Folder, is Item.FolderGroup, is Item.ClipPair -> {}
                is Item.Net -> {
                    asIcon(R.drawable.ic_link, false, 40, 18)
                    tileIcon.visibility = View.GONE
                    tileText.visibility = View.VISIBLE
                    tileText.text = Ui.streamTag(item.stream.url)
                    title.text = item.stream.title
                    val u = android.net.Uri.parse(item.stream.url)
                    val addr = (u.host ?: "").ifBlank { item.stream.url }
                    val played = if (item.stream.lastPlayed > 0)
                        "Played " + java.text.SimpleDateFormat("d MMM yyyy, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(item.stream.lastPlayed))
                    else "Not played yet"
                    val dl = downloadInfo(item.stream.url)
                    val state = when {
                        dl == null -> played
                        dl.active -> if (dl.percent >= 0) "Downloading ${dl.percent}%" else "Downloading…"
                        dl.done -> "Downloaded · in the Gplay folder"
                        else -> "Download failed"
                    }
                    meta.maxLines = 2
                    meta.text = addr + "\n" + state
                    val showBar = dl != null && (dl.active || dl.done)
                    progressTrack.visibility = if (showBar) View.VISIBLE else View.GONE
                    progressFill.visibility = progressTrack.visibility
                    progressFill.scaleX = if (dl == null) 0f else if (dl.done) 1f else (dl.percent.coerceAtLeast(0) / 100f)
                }
                Item.Add -> {
                    asIcon(R.drawable.ic_add, false, 40, 18)
                    title.text = "Add network stream"
                    meta.text = "Paste a link: HTTP(S), HLS (.m3u8), DASH, RTMP, RTSP…"
                }
                is Item.Clip -> {
                    val v = item.video
                    current = v.id
                    meta.maxLines = 1
                    tileText.visibility = View.GONE
                    setCardStyle(false)
                    tile(false)
                    tileIcon.visibility = View.GONE
                    thumb.visibility = View.VISIBLE
                    (newBadge.layoutParams as FrameLayout.LayoutParams).apply {
                        gravity = Gravity.TOP or Gravity.START; setMargins(dp(4), dp(4), 0, 0)
                    }
                    newBadge.visibility = if (isNew(v)) View.VISIBLE else View.GONE
                    title.text = v.name.substringBeforeLast('.')
                    meta.text = "${v.name.substringAfterLast('.', "").uppercase()} · ${VideoRepo.fmtSize(v.size)}"
                    meta.visibility = if (showInfo) View.VISIBLE else View.GONE
                    badge.text = VideoRepo.fmtDuration(v.durationMs)
                    badge.visibility = if (showDuration) View.VISIBLE else View.GONE
                    val pr = progressOf(v)
                    progressTrack.visibility = if (pr > 0f) View.VISIBLE else View.GONE
                    progressFill.visibility = progressTrack.visibility
                    progressFill.scaleX = pr
                    val cached = thumbs.get(v.id)
                    if (cached != null) { thumb.setImageBitmap(cached); return }
                    thumb.setImageDrawable(null)
                    pool.execute {
                        if (current != v.id) return@execute // row was recycled while scrolling
                        val bmp = loadThumb(v) ?: return@execute
                        thumbs.put(v.id, bmp)
                        ui.post { if (current == v.id) thumb.setImageBitmap(bmp) }
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun loadThumb(v: Video): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(v.uri, Size(256, 144), null)
        else MediaStore.Video.Thumbnails.getThumbnail(
            context.contentResolver, v.id, MediaStore.Video.Thumbnails.MINI_KIND, null
        )
    } catch (_: Exception) { null }
}
