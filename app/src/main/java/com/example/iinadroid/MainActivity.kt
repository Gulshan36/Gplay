package com.example.iinadroid

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.app.RecoverableSecurityException
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.database.ContentObserver
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.util.LruCache
import android.util.Rational
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.jdtech.mpv.MPVLib
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * IINA-style player for Android on top of libmpv, plus MX Player extras:
 * pinch-zoom, resume, screen lock, rotation lock, repeat, sleep timer, volume boost,
 * subtitle/audio panels, background play, folders library, saved network streams,
 * timeline thumbnails, video delete/rename/share, NEW badges.
 */
class MainActivity : Activity(), SurfaceHolder.Callback, MPVLib.EventObserver {

    private lateinit var mpv: MPVLib

    private lateinit var root: FrameLayout
    private lateinit var library: LibraryView
    private lateinit var playerRoot: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var titleBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var repeatIcon: ImageView
    private lateinit var osc: LinearLayout
    private lateinit var playIcon: ImageView
    private lateinit var speedButton: TextView
    private lateinit var pipButton: ImageView
    private lateinit var sizeButton: ImageView
    private lateinit var bgButton: ImageView
    private lateinit var loadedView: TextView
    private lateinit var bufferBox: LinearLayout
    private lateinit var bufferRing: BufferRing
    private lateinit var bufferText: TextView
    private var pausedForCache = false
    private var cacheEnd = 0.0
    private var bufferingPct = 100
    private lateinit var prevButton: ImageView
    private lateinit var nextButton: ImageView
    private var sizeMode = 0
    private lateinit var panelBack: ImageView
    private var panelVolSlider: Slider? = null
    private var panelVolLabel: TextView? = null
    private var boosting = false

    // smoothness helpers
    @Volatile private var lastTimePost = 0L
    @Volatile private var latestPos = 0.0
    private val trailingPending = AtomicBoolean(false)
    private var lastBrightnessApply = 0L
    private var libraryLoaded = false
    private var libraryDirty = true
    private val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            libraryDirty = true
            if (!inPlayer && hasMediaPermission()) refreshLibrary()
        }
    }
    private lateinit var seekSlider: Slider
    private lateinit var elapsedView: TextView
    private lateinit var remainingView: TextView
    private lateinit var hud: TextView
    private lateinit var lockOverlay: ImageView
    private lateinit var audio: AudioManager

    // right-side sliding panel
    private lateinit var sidePanel: LinearLayout
    private lateinit var panelTitle: TextView
    private lateinit var panelBody: LinearLayout
    private lateinit var panelScroll: ScrollView
    private var panelKind = ""

    // seek-bar thumbnail preview
    private lateinit var previewBox: LinearLayout
    private lateinit var previewImage: ImageView
    private lateinit var previewTime: TextView
    private val previewExec = Executors.newSingleThreadExecutor()
    private val previewCache = object : LruCache<Int, Bitmap>(60) {}
    private var previewRetriever: MediaMetadataRetriever? = null
    private var previewFor: Uri? = null
    @Volatile private var previewBroken = false
    @Volatile private var previewWantedUs = -1L
    @Volatile private var previewRatio = 0f
    private val previewLock = Any()
    private var previewRunning = false

    private val ui = Handler(Looper.getMainLooper())
    private val hideControlsRunnable = Runnable { setControlsVisible(false) }
    private val hideHudRunnable = Runnable { hud.visibility = View.GONE }
    private val hideLockRunnable = Runnable {
        lockOverlay.animate().alpha(0f).setDuration(200).withEndAction { lockOverlay.visibility = View.GONE }.start()
    }

    private var inPlayer = false
    private var surfaceReady = false
    private var pendingPath: String? = null
    private var hasFile = false
    private var loadingPath = false
    private var paused = true
    private var duration = 0.0
    private var position = 0.0
    private var volume = 100.0
    private var muted = false
    private var speed = 1.0
    private var aspectLabel = "Default"
    private var zoom = 0.0

    private var playlist: List<Video> = emptyList()
    private var index = 0
    private var permanentlyDenied = false
    private var hwdecOn = true
    private var audioMode = 0   // 0 stereo, 1 mono, 2 surround
    private lateinit var fx: AudioFx
    private var fxSession = 0
    private var playerBrightness = -1f
    private var autoPip = false

    // MX-style extras
    private var locked = false
    private var repeatMode = 0 // 0 off, 1 one, 2 all
    private var orientMode = 0 // 0 auto (by video shape), 1 landscape, 2 portrait, 3 free rotate
    private var autoPortrait = false
    private var videoAspect = 16f / 9f
    private var eofReached = false
    private var suppressPip = false
    private var mpvAlive = true
    private var started = false
    private val mpvReady get() = started && mpvAlive
    private var splash: FrameLayout? = null
    private var bgPlay = false
    private var vidOff = false
    private var voOff = false
    private var serviceOn = false
    private var pendingSeek = 0.0
    private var lastSave = 0L
    private var subScale = 1.0
    private var subPos = 100
    private var subDelay = 0.0
    private var audioDelay = 0.0
    private var sleepIndex = 0
    private var sleepLabel = "Off"
    private var sleepRunnable: Runnable? = null
    private var sleepEndOfVideo = false
    private var resumeOnFocus = false

    // pending system-confirmed file operations
    private var pendingDelete: Video? = null
    private var pendingRename: Pair<Video, String>? = null

    // gesture state
    private var gestureMode = 0 // 0 none, 1 seek, 2 brightness, 3 volume
    private var ignoreGesture = false
    private var gestureStartPos = 0.0
    private var gestureSeekTarget = 0.0
    private var gestureStartValue = 0f

    private val accent: Int get() = Ui.ACCENT
    private val grey = 0xFF8E8E93.toInt()
    private val libraryBg = Ui.BG

    private val aspectNames = listOf("Default", "16:9", "4:3", "21:9", "1:1", "Fill", "Stretch")
    private val aspectValues = listOf("-1", "16:9", "4:3", "21:9", "1:1", "-1", "-1")
    private val sleepNames = listOf("Off", "15 min", "30 min", "45 min", "60 min", "90 min", "End of video")
    private val sleepMinutes = listOf(0, 15, 30, 45, 60, 90, -1)

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS -> if (!paused) mpv.setPropertyBoolean("pause", true)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (!paused) {
                resumeOnFocus = true; mpv.setPropertyBoolean("pause", true)
            }
            AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocus) {
                resumeOnFocus = false; mpv.setPropertyBoolean("pause", false)
            }
        }
    }

    /** Headphones unplugged -> pause. */
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { if (inPlayer && !paused) mpv.setPropertyBoolean("pause", true) }
    }

    /** Notification buttons for background playback. */
    private val notifReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                PlaybackService.ACTION_PLAY_PAUSE -> togglePause()
                PlaybackService.ACTION_STOP -> {
                    if (!paused) mpv.setPropertyBoolean("pause", true)
                    stopBackgroundService()
                }
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.load(this)
        bgPlay = getSharedPreferences("player", MODE_PRIVATE).getBoolean("bgplay", false)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        buildPlayer()
        buildLibrary()
        val openedWithContent = intent?.action == Intent.ACTION_VIEW || intent?.action == Intent.ACTION_SEND
        if (!openedWithContent && savedInstanceState == null) splash = buildSplash().also { root.addView(it, ViewGroup.LayoutParams(-1, -1)) }
        setContentView(root)
        showLibraryChrome()
        // Draw the first frame (library / intro page) right away; start the heavy engine just after it.
        root.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                root.viewTreeObserver.removeOnPreDrawListener(this)
                ui.post { startup() }
                return true
            }
        })
    }

    /** Everything that is not needed for the first frame: video engine, receivers, permission, intents. */
    private fun startup() {
        if (started || isFinishing) return
        initMpv()
        started = true
        contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        val f = IntentFilter().apply {
            addAction(PlaybackService.ACTION_PLAY_PAUSE); addAction(PlaybackService.ACTION_STOP)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(notifReceiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(notifReceiver, f)

        if (hasMediaPermission()) refreshLibrary()
        else {
            library.showPermissionNeeded(false)
            requestMediaPermission() // ask right away on first launch
        }
        handleIntent(intent)
        splash?.let { ui.postDelayed({ hideSplash() }, 1500) }
    }

    private fun hideSplash() {
        val sp = splash ?: return
        splash = null
        sp.animate().alpha(0f).setDuration(350).withEndAction { root.removeView(sp) }.start()
    }

    /** Intro page shown when the app starts: logo pops in, then the name, tagline and credit. */
    private fun buildSplash(): FrameLayout {
        val sp = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xFF0E0E12.toInt(), Ui.SPLASH_END))
            isClickable = true
            setOnClickListener { hideSplash() }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        val logoView = ImageView(this).apply { setImageDrawable(Ui.logo(this@MainActivity)); alpha = 0f; scaleX = 0.55f; scaleY = 0.55f }
        col.addView(logoView, LinearLayout.LayoutParams(dp(116), dp(116)))
        val nameView = TextView(this).apply {
            text = "Gplay"; textSize = 38f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.06f; alpha = 0f; translationY = dp(14).toFloat()
        }
        col.addView(nameView, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(18) })
        val tagline = TextView(this).apply {
            text = "Play everything. Smoothly."; textSize = 14f; setTextColor(0xFFB9B3E8.toInt()); alpha = 0f
        }
        col.addView(tagline, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        sp.addView(col, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        val credit = TextView(this).apply {
            text = "Developed by Gulshan Kumar"; textSize = 12f; setTextColor(0xFF8E8AB8.toInt()); alpha = 0f
            letterSpacing = 0.05f
        }
        sp.addView(credit, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(40) })
        logoView.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(560).setInterpolator(OvershootInterpolator(1.5f)).start()
        nameView.animate().alpha(1f).translationY(0f).setStartDelay(240).setDuration(420).start()
        tagline.animate().alpha(1f).setStartDelay(460).setDuration(420).start()
        credit.animate().alpha(1f).setStartDelay(680).setDuration(420).start()
        return sp
    }

    override fun onResume() {
        super.onResume()
        suppressPip = false
        if (inPlayer && playerBrightness >= 0f) setWindowBrightness(playerBrightness)
        stopBackgroundService()
        if (!inPlayer && hasMediaPermission()) refreshLibrary()
        if (Downloads.anyActive(this)) { ui.removeCallbacks(dlTicker); ui.post(dlTicker) }
    }

    override fun onPause() {
        super.onPause()
        // the brightness set by swiping belongs to the player only: give it back the moment we are not on screen
        setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        if (inPlayer && bgPlay && hasFile && !paused) {
            PlaybackService.start(this, titleView.text.toString(), false)
            serviceOn = true
        }
    }

    override fun onStop() {
        super.onStop()
        if (!inPlayer) return
        saveResume(force = true)
        val keepPlaying = bgPlay && hasFile && !paused
        if (!keepPlaying && hasFile && !paused && !isInPictureInPictureMode) mpv.setPropertyBoolean("pause", true)
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        stopBackgroundService()
        try { unregisterReceiver(noisyReceiver); unregisterReceiver(notifReceiver) } catch (_: Exception) {}
        try { contentResolver.unregisterContentObserver(mediaObserver) } catch (_: Exception) {}
        previewExec.execute { try { previewRetriever?.release() } catch (_: Exception) {} }
        previewExec.shutdown()
        mpvAlive = false
        if (::fx.isInitialized) fx.release()
        closeVideoFd(); closeSubFds()
        if (started) {
            mpv.removeObserver(this)
            mpv.detachSurface()
            mpv.destroy()
        }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            splash != null -> hideSplash()
            isPanelOpen() -> closePanel()
            locked -> showLockOverlay()
            inPlayer -> leavePlayer()
            library.handleBack() -> {}
            else -> super.onBackPressed()
        }
    }

    override fun onUserLeaveHint() {
        if (suppressPip) return
        if (inPlayer && hasFile && !paused && !bgPlay && autoPip) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        if (isInPip) {
            setControlsVisible(false)
            closePanel()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        osc.layoutParams = (osc.layoutParams as FrameLayout.LayoutParams).apply { width = oscWidth() }
        sidePanel.layoutParams = (sidePanel.layoutParams as FrameLayout.LayoutParams).apply { width = panelWidth() }
        positionPip()
    }

    // ---------------------------------------------------------------- permission

    private fun mediaPermission() =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasMediaPermission() =
        checkSelfPermission(mediaPermission()) == PackageManager.PERMISSION_GRANTED

    private fun requestMediaPermission() = requestPermissions(arrayOf(mediaPermission()), REQ_PERM)

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code != REQ_PERM) return
        if (hasMediaPermission()) refreshLibrary()
        else {
            permanentlyDenied = !shouldShowRequestPermissionRationale(mediaPermission())
            library.showPermissionNeeded(permanentlyDenied)
        }
    }

    /** Re-reads MediaStore only when the video list really changed; otherwise just refreshes badges / progress. */
    private fun refreshLibrary(force: Boolean = false) {
        if (!force && libraryLoaded && !libraryDirty) { library.refreshProgress(); return }
        libraryDirty = false
        Thread {
            val list = VideoRepo.load(this)
            SeenStore.ensureBaseline(this, list.map { it.id })
            if (list.isNotEmpty()) ResumeStore.prune(this, list)
            ui.post { libraryLoaded = true; library.setVideos(list) }
        }.start()
    }

    // ---------------------------------------------------------------- UI building

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildLibrary() {
        library = LibraryView(
            this,
            onPermissionButton = {
                if (permanentlyDenied) startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                ) else requestMediaPermission()
            },
            onOpenFile = { pickFile() },
            onOpenUrl = { askStream() },
            onPlay = { list, i -> startPlayback(list, i) },
            onVideoMenu = { showVideoMenu(it) },
            progressOf = { ResumeStore.progress(this, it) },
            isNew = { it.id > 0 && SeenStore.isNew(this, it.id) },
            streams = { NetworkStore.all(this) },
            onStreamPlay = { playStream(it.url, it.title) },
            onStreamMenu = { showStreamMenu(it) },
            onRefresh = { refreshLibrary(force = true); toast("Refreshing…") },
            onAbout = { showAbout() },
            onThemeChanged = { recreate() },
            downloadInfo = { Downloads.info(this, it) },
            onSortClicked = { cur, apply ->
                val names = arrayOf("Newest first", "Name (A–Z)", "Size (largest)", "Duration (longest)")
                dialog().setTitle("Sort by").setSingleChoiceItems(names, cur) { d, i -> apply(i); d.dismiss() }.show()
            },
        )
        root.addView(library, ViewGroup.LayoutParams(-1, -1))
    }

    /** Round press ripple for icon buttons. */
    private fun ripple(): Drawable? {
        val a = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        return try { a.getDrawable(0) } finally { a.recycle() }
    }

    private fun icon(res: Int, sizeDp: Int, tip: String, padDp: Int = 8, onClick: () -> Unit) =
        ImageView(this).apply {
            setImageResource(res)
            background = ripple()
            setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            tooltipText = tip
            contentDescription = tip
            setOnClickListener { onClick(); resetHideTimer() }
        }

    private fun smallText(text: String, widthDp: Int, gravity: Int) = TextView(this).apply {
        this.text = text
        setTextColor(0xFFB8B8B8.toInt())
        textSize = 12f
        this.gravity = gravity
        layoutParams = LinearLayout.LayoutParams(dp(widthDp), -2)
        typeface = Typeface.MONOSPACE
    }

    private fun oscWidth() = min(dp(560), resources.displayMetrics.widthPixels - dp(32))
    private fun panelWidth() = min(dp(360), resources.displayMetrics.widthPixels * 88 / 100)

    @SuppressLint("ClickableViewAccessibility")
    private fun buildPlayer() {
        playerRoot = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }

        surface = SurfaceView(this)
        surface.holder.addCallback(this)
        playerRoot.addView(surface, FrameLayout.LayoutParams(-1, -1))

        // ---- title bar (like the macOS window title bar)
        titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xDD000000.toInt(), 0x00000000))
            setPadding(dp(8), dp(8), dp(8), dp(26))
        }
        titleBar.addView(icon(R.drawable.ic_back, 40, "Back to library") { leavePlayer() })
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MIDDLE
            gravity = Gravity.CENTER_VERTICAL
        }
        loadedView = TextView(this).apply {
            setTextColor(Ui.TEAL); textSize = 11.5f; visibility = View.GONE
            typeface = Typeface.DEFAULT_BOLD
        }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(titleView)
        titleCol.addView(loadedView)
        titleBar.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(4) })
        repeatIcon = icon(R.drawable.ic_repeat, 40, "Repeat") { cycleRepeat() }
        repeatIcon.alpha = 0.55f
        titleBar.addView(repeatIcon)
        titleBar.addView(icon(R.drawable.ic_rotate, 40, "Screen rotation") { cycleOrientation() })
        titleBar.addView(icon(R.drawable.ic_lock, 40, "Lock screen") { lockScreen() })
        playerRoot.addView(titleBar, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // ---- floating control panel
        osc = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xE62E2E30.toInt())
                setStroke(dp(1), 0x26FFFFFF)
            }
            setPadding(dp(16), dp(10), dp(16), dp(8))
            isClickable = true // swallow taps so they don't toggle the controls
        }

        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // left: playback speed (tap to change, up to 4x)
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }
        speedButton = TextView(this).apply {
            text = "1x"; textSize = 14f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = pill(false)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            tooltipText = "Playback speed"
            setOnClickListener { showSpeedDialog(); resetHideTimer() }
        }
        left.addView(speedButton)
        bgButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_headset)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            tooltipText = "Background play"
            contentDescription = "Background play"
            background = rippleCircle()
            setOnClickListener { setBackgroundPlay(!bgPlay); resetHideTimer() }
        }
        styleBgButton()
        left.addView(bgButton, LinearLayout.LayoutParams(dp(36), dp(36)).apply { leftMargin = dp(4) })
        row1.addView(left, LinearLayout.LayoutParams(0, -2, 1f))

        // center: rewind / play-pause / forward
        val center = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        prevButton = icon(R.drawable.ic_skip_prev, 44, "Previous video", 8) { playAt(index - 1) }
        center.addView(prevButton)
        playIcon = icon(R.drawable.ic_play, 56, "Play / Pause", 10) { togglePause() }
        center.addView(playIcon)
        nextButton = icon(R.drawable.ic_skip_next, 44, "Next video", 8) { playAt(index + 1) }
        center.addView(nextButton)
        row1.addView(center, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(10); rightMargin = dp(10) })

        // right: playlist / settings (audio + subtitles live inside Settings)
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
        }
        right.addView(icon(R.drawable.ic_playlist, 40, "Playlist and chapters") { openPanel("playlist") })
        right.addView(icon(R.drawable.ic_settings, 40, "Settings") { openPanel("settings") })
        row1.addView(right, LinearLayout.LayoutParams(0, -2, 1f))
        osc.addView(row1, LinearLayout.LayoutParams(-1, -2))

        // row 2: elapsed | seek | remaining
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        elapsedView = smallText("00:00", 60, Gravity.START or Gravity.CENTER_VERTICAL)
        seekSlider = Slider(this, barKnob = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            onChange = { p, _ ->
                if (duration > 0) { updateTimes(p * duration); showPreview(p * duration, p) }
            }
            onTouchStart = { ui.removeCallbacks(hideControlsRunnable) }
            onTouchEnd = { p ->
                hidePreview()
                if (duration > 0) seekTo(p * duration)
                resetHideTimer()
            }
        }
        remainingView = smallText("-00:00", 76, Gravity.END or Gravity.CENTER_VERTICAL)
        row2.addView(elapsedView); row2.addView(seekSlider); row2.addView(remainingView)
        osc.addView(row2, LinearLayout.LayoutParams(-1, -2))

        playerRoot.addView(osc, FrameLayout.LayoutParams(oscWidth(), -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = dp(20)
        })

        // ---- Picture-in-Picture button, bottom-right corner
        pipButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_pip)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC202022.toInt()) }
            tooltipText = "Picture in Picture"
            contentDescription = "Picture in Picture"
            setOnClickListener { enterPip() }
        }
        playerRoot.addView(pipButton, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = dp(24); rightMargin = dp(24)
        })

        // ---- screen size button, just left of the PiP button: Fit -> Stretch -> Crop -> 100% (same order as MX Player)
        sizeButton = ImageView(this).apply {
            setImageResource(sizeModeIcons[0])
            setPadding(dp(11), dp(11), dp(11), dp(11))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC202022.toInt()) }
            tooltipText = "Screen size"
            contentDescription = "Screen size"
            setOnClickListener { setSizeMode((sizeMode + 1) % sizeModeNames.size); resetHideTimer() }
        }
        playerRoot.addView(sizeButton, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = dp(24); rightMargin = dp(24 + 44 + 10)
        })

        // ---- timeline preview bubble
        previewBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0xEE202022.toInt()) }
            setPadding(dp(4), dp(4), dp(4), dp(4))
            visibility = View.GONE
        }
        previewImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0xFF000000.toInt()) }
            clipToOutline = true
            visibility = View.GONE
        }
        previewBox.addView(previewImage, LinearLayout.LayoutParams(dp(160), dp(90)))
        previewTime = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(3), 0, dp(1))
        }
        previewBox.addView(previewTime, LinearLayout.LayoutParams(-2, -2))
        playerRoot.addView(previewBox, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))

        // ---- on-screen display for gestures
        hud = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER
            setPadding(dp(18), dp(10), dp(18), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(0xCC202022.toInt()) }
            visibility = View.GONE
        }
        playerRoot.addView(hud, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        // ---- "waiting for the network" overlay: ring with the percentage that fills until playback can continue
        bufferRing = BufferRing(this)
        bufferText = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 13f; gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0)
        }
        bufferBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            background = Ui.rect(this@MainActivity, 0xCC11141F.toInt(), 22)
            setPadding(dp(26), dp(18), dp(26), dp(16))
            visibility = View.GONE
            isClickable = false
            addView(bufferRing)
            addView(bufferText)
        }
        playerRoot.addView(bufferBox, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))

        // ---- screen-lock unlock button (only appears when the screen is touched while locked)
        lockOverlay = ImageView(this).apply {
            setImageResource(R.drawable.ic_lock)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xCC202022.toInt()) }
            visibility = View.GONE
            setOnClickListener { unlockScreen() }
        }
        playerRoot.addView(lockOverlay, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER_VERTICAL or Gravity.START).apply {
            leftMargin = dp(28)
        })

        // ---- right-side sliding panel (playlist / settings / subtitles / audio)
        sidePanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.leftRounded(this@MainActivity, Ui.PANEL, 26)
            visibility = View.GONE
            isClickable = true
        }
        val panelHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(8), dp(4))
        }
        panelBack = icon(R.drawable.ic_back, 40, "Back") { openPanel(if (panelKind == "eq") "audio" else "settings") }
        panelBack.visibility = View.GONE
        panelHeader.addView(panelBack)
        panelTitle = TextView(this).apply {
            setTextColor(Ui.TEXT); textSize = 21f; setTypeface(typeface, Typeface.BOLD)
        }
        panelHeader.addView(panelTitle, LinearLayout.LayoutParams(0, -2, 1f))
        panelHeader.addView(icon(R.drawable.ic_close, 40, "Close") { closePanel() })
        sidePanel.addView(panelHeader, LinearLayout.LayoutParams(-1, -2))
        panelBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(24)) }
        panelScroll = ScrollView(this).apply { addView(panelBody) }
        sidePanel.addView(panelScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        playerRoot.addView(sidePanel, FrameLayout.LayoutParams(panelWidth(), -1, Gravity.END))

        // ---- gestures on the video
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                if (!hasFile) return true
                zoom = (zoom + ln(d.scaleFactor.toDouble()) / ln(2.0)).coerceIn(-1.0, 3.0)
                mpv.setPropertyDouble("video-zoom", zoom)
                showHud("Zoom ${(2.0.pow(zoom) * 100).toInt()}%")
                return true
            }
        })
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isPanelOpen()) { closePanel(); return true }
                setControlsVisible(osc.visibility != View.VISIBLE)
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!hasFile || isPanelOpen()) return true
                val w = playerRoot.width.coerceAtLeast(1)
                when {
                    e.x < w / 3f -> { seekRelative(-10.0); showHud("-10s") }
                    e.x > w * 2f / 3f -> { seekRelative(10.0); showHud("+10s") }
                    else -> togglePause()
                }
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                if (!hasFile || isPanelOpen() || ignoreGesture) return
                val w = playerRoot.width.coerceAtLeast(1)
                if (e.x > w / 3f && e.x < w * 2f / 3f) return // only the left / right sides
                boosting = true
                gestureMode = 0
                mpv.setPropertyDouble("speed", BOOST_SPEED)
                hud.text = if (e.x < w / 2f) "\u23EA  2x" else "2x  \u23E9"
                hud.visibility = View.VISIBLE
                ui.removeCallbacks(hideHudRunnable)
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                e1 ?: return false
                if (boosting) return true
                if (!hasFile || isPanelOpen()) return false
                val totalX = e2.x - e1.x
                val totalY = e2.y - e1.y
                val w = playerRoot.width.coerceAtLeast(1)
                val h = playerRoot.height.coerceAtLeast(1)
                if (gestureMode == 0) {
                    if (abs(totalX) < dp(16) && abs(totalY) < dp(16)) return true
                    if (abs(totalX) > abs(totalY)) {
                        gestureMode = 1; gestureStartPos = position
                    } else if (e1.x < w / 2f) {
                        gestureMode = 2
                        gestureStartValue = if (playerBrightness >= 0f) playerBrightness else systemBrightness()
                    } else {
                        gestureMode = 3; gestureStartValue = combinedVolume()
                    }
                }
                when (gestureMode) {
                    1 -> {
                        gestureSeekTarget = (gestureStartPos + totalX / w * 120.0).coerceIn(0.0, duration.coerceAtLeast(0.0))
                        showHud(fmt(gestureSeekTarget) + "  (" + signed(gestureSeekTarget - gestureStartPos) + ")")
                    }
                    2 -> {
                        val b = (gestureStartValue - totalY / h * 1.2f).coerceIn(0.02f, 1f)
                        val t = SystemClock.uptimeMillis()
                        if (t - lastBrightnessApply > 40) {
                            lastBrightnessApply = t
                            playerBrightness = b
                            setWindowBrightness(b)
                        }
                        showHud("Brightness ${(b * 100).toInt()}%")
                    }
                    3 -> {
                        val v = (gestureStartValue - totalY / h * 150f).coerceIn(0f, MAX_VOLUME.toFloat())
                        applyCombinedVolume(v)
                        showHud(if (v > 100f) "Volume ${v.toInt()}%  (boost)" else "Volume ${v.toInt()}%")
                    }
                }
                return true
            }
        })
        playerRoot.setOnTouchListener { _, ev ->
            if (locked) {
                // locked: ignore everything, but reveal the unlock button on touch
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) showLockOverlay()
                return@setOnTouchListener true
            }
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) ignoreGesture = false
            scaleDetector.onTouchEvent(ev)
            if (scaleDetector.isInProgress || ev.pointerCount > 1) { ignoreGesture = true; gestureMode = 0 }
            if (!ignoreGesture) detector.onTouchEvent(ev)
            if (ev.action == MotionEvent.ACTION_UP || ev.action == MotionEvent.ACTION_CANCEL) {
                if (boosting) endBoost()
                if (gestureMode == 1 && !ignoreGesture) seekTo(gestureSeekTarget)
                gestureMode = 0
            }
            true
        }

        root.addView(playerRoot, ViewGroup.LayoutParams(-1, -1))
    }

    /**
     * One volume scale for the swipe gesture: 0-100% moves the phone's own media volume, and 100-200% keeps
     * the phone at maximum and boosts the player's software volume on top.
     */
    private fun combinedVolume(): Float {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val device = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max
        return if (device >= 99.5f && volume > 100.0) volume.toFloat() else device
    }

    private fun currentIsStream() = playlist.getOrNull(index)?.id == -1L

    /** Ring in the middle while a stream is loading or has paused because the network is too slow. */
    private fun updateBufferUi() {
        val show = inPlayer && currentIsStream() && (pausedForCache || loadingPath)
        if (!show) { bufferBox.visibility = View.GONE; return }
        bufferBox.visibility = View.VISIBLE
        if (pausedForCache && bufferingPct in 0..99) {
            bufferRing.percent = bufferingPct
            bufferText.text = "Slow network\nPlays when this reaches 100%"
        } else {
            bufferRing.percent = -1
            bufferText.text = if (pausedForCache) "Waiting for the network…" else "Loading…"
        }
    }

    /** "Loaded 42%" under the title for network streams, and the lighter downloaded bar on the seek bar. */
    private fun updateLoaded() {
        if (!currentIsStream() || duration <= 0.0) {
            loadedView.visibility = View.GONE
            seekSlider.buffered = 0f
            return
        }
        val pct = (cacheEnd / duration * 100).toInt().coerceIn(0, 100)
        seekSlider.buffered = (cacheEnd / duration).toFloat()
        loadedView.text = if (bufferingPct < 100) "Buffering $bufferingPct%  ·  Loaded $pct%" else "Loaded $pct%"
        loadedView.visibility = View.VISIBLE
    }

    private fun rippleCircle(): Drawable = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null,
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })

    private fun styleBgButton() {
        bgButton.setColorFilter(if (bgPlay) accent else Color.WHITE)
        bgButton.alpha = if (bgPlay) 1f else 0.55f
    }

    /** One place that turns background playback on or off (the control-bar button and the Settings switch). */
    private fun setBackgroundPlay(on: Boolean) {
        bgPlay = on
        getSharedPreferences("player", MODE_PRIVATE).edit().putBoolean("bgplay", on).apply()
        if (on && Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        styleBgButton()
        showHud(if (on) "Background playback enabled" else "Background playback disabled")
        if (panelKind == "settings") rebuildPanel()
    }

    private fun applyCombinedVolume(level: Float) {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val device = (level.coerceAtMost(100f) / 100f * max).roundToInt().coerceIn(0, max)
        try { audio.setStreamVolume(AudioManager.STREAM_MUSIC, device, 0) } catch (_: SecurityException) { }
        mpv.setPropertyDouble("volume", if (level > 100f) level.toDouble() else 100.0)
    }

    private fun setWindowBrightness(b: Float) {
        window.attributes = window.attributes.apply { screenBrightness = b }
    }

    private fun systemBrightness(): Float = try {
        Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
    } catch (_: Exception) { 0.5f }

    private fun showHud(text: String) {
        hud.text = text
        hud.visibility = View.VISIBLE
        ui.removeCallbacks(hideHudRunnable)
        ui.postDelayed(hideHudRunnable, 800)
    }

    private fun setControlsVisible(visible: Boolean) {
        ui.removeCallbacks(hideControlsRunnable)
        if (visible && (locked || isPanelOpen())) return
        if (visible) positionPip()
        for (v in listOf(titleBar, osc, pipButton, sizeButton)) {
            v.animate().cancel()
            if (visible) {
                v.visibility = View.VISIBLE
                v.animate().alpha(1f).setDuration(150).start()
            } else {
                v.animate().alpha(0f).setDuration(200).withEndAction { v.visibility = View.GONE }.start()
            }
        }
        if (!visible) hidePreview()
        if (visible) resetHideTimer()
    }

    private fun resetHideTimer() {
        ui.removeCallbacks(hideControlsRunnable)
        if (inPlayer && !paused && osc.visibility == View.VISIBLE) ui.postDelayed(hideControlsRunnable, 3000)
    }

    private fun applyOrientation() {
        requestedOrientation = when (orientMode) {
            0 -> if (autoPortrait) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
    }

    private fun showPlayerChrome() {
        applyOrientation()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    private fun showLibraryChrome() {
        // the library is always upright: leaving a landscape video can never leave it sideways
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        window.statusBarColor = libraryBg
        window.navigationBarColor = libraryBg
        playerBrightness = -1f
        setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
    }

    // ---------------------------------------------------------------- lock / rotate / repeat

    private fun lockScreen() {
        locked = true
        setControlsVisible(false)
        closePanel()
        // the unlock button stays hidden until the user touches the screen again
        ui.removeCallbacks(hideLockRunnable)
        lockOverlay.animate().cancel()
        lockOverlay.visibility = View.GONE
        Toast.makeText(this, "Screen locked. Touch the screen to show the unlock button", Toast.LENGTH_SHORT).show()
    }

    private fun unlockScreen() {
        locked = false
        ui.removeCallbacks(hideLockRunnable)
        lockOverlay.animate().cancel()
        lockOverlay.visibility = View.GONE
        setControlsVisible(true)
    }

    private fun showLockOverlay() {
        ui.removeCallbacks(hideLockRunnable)
        lockOverlay.animate().cancel()
        lockOverlay.alpha = 1f
        lockOverlay.visibility = View.VISIBLE
        ui.postDelayed(hideLockRunnable, 2000)
    }

    private fun cycleOrientation() {
        orientMode = (orientMode + 1) % 4
        getSharedPreferences("player", MODE_PRIVATE).edit().putInt("orient", orientMode).apply()
        applyOrientation()
        showHud(arrayOf("Rotation: auto (by video)", "Rotation: landscape", "Rotation: portrait", "Rotation: free")[orientMode])
    }

    private fun cycleRepeat() {
        repeatMode = (repeatMode + 1) % 3
        mpv.setPropertyString("loop-file", if (repeatMode == 1) "inf" else "no")
        repeatIcon.alpha = if (repeatMode == 0) 0.55f else 1f
        repeatIcon.setColorFilter(if (repeatMode == 0) Color.WHITE else accent)
        showHud(arrayOf("Repeat: off", "Repeat: this video", "Repeat: all")[repeatMode])
    }

    // ---------------------------------------------------------------- mpv

    private fun initMpv() {
        mpv = MPVLib.create(applicationContext) ?: error("libmpv failed to initialise")
        mpv.setOptionString("profile", "fast")
        mpv.setOptionString("framedrop", "decoder+vo")
        mpv.setOptionString("vo", "gpu")
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("opengl-es", "yes")
        val prefs = getSharedPreferences("player", MODE_PRIVATE)
        // Emulators ship a codec that hangs the whole player, so default to software there.
        hwdecOn = prefs.getBoolean("hwdec", !isEmulator())
        bgPlay = prefs.getBoolean("bgplay", false)
        if (::bgButton.isInitialized) styleBgButton()
        audioMode = if (prefs.contains("audiomode")) prefs.getInt("audiomode", 0) else if (prefs.getBoolean("downmix", true)) 0 else 2
        fxSession = audio.generateAudioSessionId()
        fx = AudioFx(prefs, fxSession)
        orientMode = prefs.getInt("orient", 0)
        mpv.setOptionString("hwdec", if (hwdecOn) "mediacodec-copy" else "no")
        // audio: several drivers to fall back on; 5.1/7.1 (EAC3, DTS, TrueHD) is mixed down to
        // stereo by default because many phones cannot open a multichannel output
        mpv.setOptionString("ao", "audiotrack,aaudio,opensles")
        mpv.setOptionString("audio-channels", audioChannels())
        // let the audio effects (equalizer etc.) attach to mpv's output: try the option names mpv versions use
        var sessionSet = false
        for (name in listOf("audiotrack-session-id", "ao-audiotrack-session-id", "session-id")) {
            if (!sessionSet && mpv.setOptionString(name, fxSession.toString()) >= 0) { sessionSet = true; android.util.Log.d("GplayFx", "session option $name=$fxSession") }
        }
        if (!sessionSet) android.util.Log.d("GplayFx", "no session option accepted")
        autoPip = prefs.getBoolean("autopip", false)
        mpv.setOptionString("audio-normalize-downmix", "yes")
        mpv.setOptionString("cache-pause-wait", "6")   // slow network: fill 6 seconds before playing again, not 1
        mpv.setOptionString("audio-fallback-to-null", "yes")
        mpv.setOptionString("keep-open", "yes")
        mpv.setOptionString("volume-max", MAX_VOLUME.toInt().toString())
        mpv.setOptionString("sub-fonts-dir", "/system/fonts")
        mpv.setOptionString("sub-auto", "fuzzy")
        mpv.setOptionString("slang", "en,eng,hi,hin")
        // network streams
        mpv.setOptionString("tls-verify", "yes")
        caBundle()?.let { mpv.setOptionString("tls-ca-file", it) }
        mpv.setOptionString("network-timeout", "30")
        mpv.setOptionString("cache", "yes")
        mpv.setOptionString("demuxer-max-bytes", "67108864")
        mpv.setOptionString("user-agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
        mpv.init()
        mpv.addObserver(this)
        mpv.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("demuxer-cache-time", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("cache-buffering-state", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        mpv.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("volume", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("mute", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("idle-active", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!mpvReady) return
        mpv.attachSurface(holder.surface)
        surfaceReady = true
        if (voOff) {
            // coming back from another app: the old video output died with the old surface
            mpv.setOptionString("force-window", "yes")
            mpv.setPropertyString("vo", "gpu")
            voOff = false
        }
        if (vidOff) { mpv.setPropertyString("vid", "auto"); vidOff = false }
        pendingPath?.let { loadPath(it); pendingPath = null }
        if (hasFile && paused) mpv.command(arrayOf("seek", "0", "relative")) // redraw the current frame
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
        if (!mpvReady) return
        mpv.setPropertyString("android-surface-size", "${w}x$h")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!mpvReady) return
        surfaceReady = false
        // keep the audio running without a video surface for background play
        if (inPlayer && bgPlay && hasFile && !paused) { mpv.setPropertyString("vid", "no"); vidOff = true }
        // stop the video output before the surface goes away, otherwise it never recovers
        mpv.setPropertyString("vo", "null")
        mpv.setOptionString("force-window", "no")
        voOff = true
        mpv.detachSurface()
    }

    override fun eventProperty(property: String) {}
    override fun eventProperty(property: String, value: Long) {
        if (!mpvReady || property != "cache-buffering-state") return
        ui.post { bufferingPct = value.toInt().coerceIn(0, 100); updateLoaded(); updateBufferUi() }
    }
    override fun eventProperty(property: String, value: String) {}

    private fun applyTime(v: Double) {
        position = v
        if (!seekSlider.dragging) updateTimes(v)
        saveResume(force = false)
    }

    override fun eventProperty(property: String, value: Double) {
        if (!mpvReady) return
        if (property == "time-pos") {
            // mpv reports the time on every video frame (30-60x a second); the UI only needs ~5x a second
            latestPos = value
            val now = SystemClock.uptimeMillis()
            if (now - lastTimePost >= 200) {
                lastTimePost = now
                ui.post { applyTime(latestPos) }
            } else if (trailingPending.compareAndSet(false, true)) {
                // make sure the final value (e.g. after a seek while paused) is still shown
                ui.postDelayed({
                    trailingPending.set(false)
                    lastTimePost = SystemClock.uptimeMillis()
                    applyTime(latestPos)
                }, 220)
            }
            return
        }
        ui.post {
            when (property) {
                "duration" -> { duration = value; updateTimes(position); updateLoaded() }
                "demuxer-cache-time" -> { cacheEnd = value; updateLoaded() }
                "volume" -> {
                    volume = value
                    panelVolSlider?.let { if (!it.dragging) it.progress = (value / MAX_VOLUME).toFloat() }
                    panelVolLabel?.text = "${value.toInt()}%"
                }
            }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        if (!mpvReady) return
        ui.post {
            when (property) {
                "pause" -> {
                    paused = value
                    if (inPlayer) { if (value) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                    playIcon.setImageResource(if (value) R.drawable.ic_play else R.drawable.ic_pause)
                    if (value) { setControlsVisible(true); saveResume(force = true) } else resetHideTimer()
                    if (serviceOn) PlaybackService.start(this, titleView.text.toString(), value)
                }
                "paused-for-cache" -> { pausedForCache = value; updateBufferUi() }
                "mute" -> { muted = value; if (panelKind == "audio") rebuildPanel() }
                "eof-reached" -> { eofReached = value; if (value) onEndOfVideo() }
                "idle-active" -> if (value && inPlayer && loadingPath) {
                    loadingPath = false
                    Toast.makeText(this, "Could not play this video or stream. Check the link or file format.", Toast.LENGTH_LONG).show()
                    setControlsVisible(true)
                }
            }
        }
    }

    /** Called when the video size is known: picks portrait / landscape for the screen and the PiP window. */
    private fun onVideoShape() {
        // size after the video's own aspect ratio (anamorphic video), then swap for a 90 / 270 degree rotation flag
        var w = mpv.getPropertyInt("video-params/dw") ?: mpv.getPropertyInt("video-out-params/dw") ?: 0
        var h = mpv.getPropertyInt("video-params/dh") ?: mpv.getPropertyInt("video-out-params/dh") ?: 0
        if (w <= 0 || h <= 0) { w = mpv.getPropertyInt("width") ?: 0; h = mpv.getPropertyInt("height") ?: 0 }
        if (w <= 0 || h <= 0) return
        val rot = ((mpv.getPropertyInt("video-params/rotate") ?: mpv.getPropertyInt("video-out-params/rotate") ?: 0) % 360 + 360) % 360
        if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
        videoAspect = w.toFloat() / h
        val portrait = h > w
        if (inPlayer && orientMode == 0 && portrait != autoPortrait) {
            autoPortrait = portrait
            applyOrientation()
        }
    }

    override fun event(eventId: Int) {
        if (!mpvReady) return
        if (eventId == MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG) ui.post { if (mpvAlive) onVideoShape() }
        if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) ui.post {
            hasFile = true
            loadingPath = false
            updateBufferUi()
            // warm up the thumbnail extractor so the first scrub shows a picture immediately (local files only)
            playlist.getOrNull(index)?.uri?.takeIf { it.scheme == "content" || it.scheme == "file" }?.let { u ->
                ui.postDelayed({ if (inPlayer && playlist.getOrNull(index)?.uri == u) previewExec.execute { frameAt(u, 0L) } }, 2500)
            }
            if (currentIsStream()) {
                // use the title stored inside the stream (if it has one), unless the user already renamed it
                val url = playlist[index].uri.toString()
                val tag = mpv.getPropertyString("metadata/by-key/title")?.trim()
                val saved = NetworkStore.all(this).firstOrNull { it.url == url }
                if (!tag.isNullOrEmpty() && saved != null && saved.title == NetworkStore.defaultTitle(url) && tag != saved.title) {
                    NetworkStore.rename(this, url, tag)
                    titleView.text = tag
                    library.refreshProgress()
                }
            }
            zoom = 0.0
            mpv.setPropertyDouble("video-zoom", 0.0)
            if (pendingSeek > 0) {
                mpv.command(arrayOf("seek", pendingSeek.toString(), "absolute"))
                showHud("Resumed at " + fmt(pendingSeek))
                pendingSeek = 0.0
            }
        }
    }

    private fun onEndOfVideo() {
        playlist.getOrNull(index)?.let { ResumeStore.clear(this, it) }
        when {
            sleepEndOfVideo -> { sleepEndOfVideo = false; sleepIndex = 0; sleepLabel = "Off"; showHud("Sleep timer: stopped") }
            index < playlist.size - 1 -> playAt(index + 1)
            repeatMode == 2 -> playAt(0)
        }
    }

    // ---------------------------------------------------------------- playback

    private fun saveResume(force: Boolean) {
        if (!inPlayer || !hasFile) return
        val v = playlist.getOrNull(index) ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 5000) return
        lastSave = now
        if (duration > 0 && duration - position < 10) ResumeStore.clear(this, v)
        else if (position > 5) ResumeStore.save(this, v, position, duration)
    }

    private fun startPlayback(list: List<Video>, i: Int) {
        if (!started) startup()
        playlist = list
        index = i
        inPlayer = true
        autoPortrait = false
        showPlayerChrome()
        library.visibility = View.GONE
        playerRoot.alpha = 0f
        playerRoot.visibility = View.VISIBLE
        playerRoot.animate().alpha(1f).setDuration(200).start()
        @Suppress("DEPRECATION")
        audio.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        playAt(i)
    }

    /** Previous / next are greyed out and not clickable at the first / last video. */
    private fun updateNavButtons() {
        if (!::prevButton.isInitialized) return
        for ((b, on) in listOf(prevButton to (index > 0), nextButton to (index < playlist.size - 1))) {
            b.isEnabled = on
            b.alpha = if (on) 1f else 0.3f
        }
    }

    private fun playAt(i: Int) {
        if (i !in playlist.indices) return
        saveResume(force = true)
        index = i
        updateNavButtons()
        val v = playlist[i]
        val path = uriToPath(v.uri) ?: run {
            Toast.makeText(this, "Cannot open file", Toast.LENGTH_SHORT).show(); return
        }
        if (v.id > 0) SeenStore.markSeen(this, v.id)
        titleView.text = v.name
        closeSubFds()
        hasFile = false
        eofReached = false
        position = 0.0; duration = 0.0
        cacheEnd = 0.0; bufferingPct = 100
        updateLoaded()
        pendingSeek = ResumeStore.resumeAt(this, v)
        updateTimes(0.0)
        resetPreview()
        if (panelKind == "playlist") rebuildPanel()
        if (surfaceReady) loadPath(path) else pendingPath = path
        setControlsVisible(true)
    }

    private fun loadPath(path: String) {
        loadingPath = true
        updateBufferUi()
        mpv.command(arrayOf("loadfile", path))
        mpv.setPropertyBoolean("pause", false)
    }

    private fun leavePlayer() {
        saveResume(force = true)
        sleepRunnable?.let { ui.removeCallbacks(it) }
        sleepRunnable = null; sleepEndOfVideo = false; sleepIndex = 0; sleepLabel = "Off"
        repeatMode = 0; mpv.setPropertyString("loop-file", "no"); repeatIcon.alpha = 0.55f; repeatIcon.setColorFilter(Color.WHITE)
        mpv.command(arrayOf("stop"))
        closeVideoFd(); closeSubFds()
        hasFile = false
        loadingPath = false
        pausedForCache = false
        inPlayer = false
        updateBufferUi()
        locked = false
        lockOverlay.visibility = View.GONE
        stopBackgroundService()
        @Suppress("DEPRECATION")
        audio.abandonAudioFocus(focusListener)
        ui.removeCallbacks(hideControlsRunnable)
        closePanel(animate = false)
        hidePreview()
        playerRoot.animate().cancel()
        playerRoot.visibility = View.GONE
        library.alpha = 0f
        library.visibility = View.VISIBLE
        library.animate().alpha(1f).setDuration(200).start()
        showLibraryChrome()
        if (hasMediaPermission()) refreshLibrary()
    }

    private fun stopBackgroundService() {
        if (serviceOn) { PlaybackService.stop(this); serviceOn = false }
    }

    private fun togglePause() {
        if (!hasFile) return
        if (eofReached) {
            // video already finished: play again from the start
            eofReached = false
            mpv.command(arrayOf("seek", "0", "absolute"))
            mpv.setPropertyBoolean("pause", false)
            return
        }
        mpv.setPropertyBoolean("pause", !paused)
    }

    private fun seekTo(seconds: Double) {
        if (hasFile) mpv.command(arrayOf("seek", seconds.toString(), "absolute"))
    }

    private fun seekRelative(delta: Double) {
        if (hasFile) mpv.command(arrayOf("seek", delta.toString(), "relative"))
    }

    private fun updateTimes(pos: Double) {
        elapsedView.text = fmt(pos)
        remainingView.text = "-" + fmt((duration - pos).coerceAtLeast(0.0))
        if (!seekSlider.dragging && duration > 0) seekSlider.progress = (pos / duration).toFloat()
    }

    private fun fmt(sec: Double): String {
        val s = sec.toInt().coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val r = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%02d:%02d".format(m, r)
    }

    private fun signed(d: Double) = (if (d >= 0) "+" else "-") + fmt(abs(d))

    private fun enterPip() {
        if (!hasFile) return
        try {
            val a = videoAspect.coerceIn(0.42f, 2.39f)
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational((a * 1000).toInt(), 1000)).build())
        } catch (_: Exception) {
            Toast.makeText(this, "Picture in Picture is not available", Toast.LENGTH_SHORT).show()
        }
    }

    /** Mozilla CA list bundled in assets, so HTTPS streams are verified (Android's own store is not readable by mpv). */
    private fun caBundle(): String? = try {
        val f = java.io.File(filesDir, "cacert.pem")
        if (!f.exists() || f.length() == 0L) {
            assets.open("cacert.pem").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        }
        f.absolutePath
    } catch (_: Exception) { null }

    private fun isEmulator() = Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu") ||
            Build.HARDWARE.contains("goldfish") || Build.MODEL.contains("Emulator")

    // ---------------------------------------------------------------- timeline preview

    private fun resetPreview() {
        previewCache.evictAll()
        previewBroken = false
        previewWantedUs = -1
        previewRatio = 0f
        val gone = previewRetriever
        previewRetriever = null
        previewFor = null
        if (gone != null) previewExec.execute { try { gone.release() } catch (_: Exception) {} }
    }

    private fun setPreviewBitmap(b: Bitmap) {
        previewImage.setImageBitmap(b)
        previewImage.visibility = View.VISIBLE
    }

    /** Thumbnail bubble above the seek bar. Shows every finished frame while sliding, so it follows the finger live. */
    private fun showPreview(sec: Double, progress: Float) {
        previewTime.text = fmt(sec)
        // place the bubble above the seek bar, following the knob
        val a = IntArray(2); val b = IntArray(2)
        seekSlider.getLocationInWindow(a); playerRoot.getLocationInWindow(b)
        val pad = dp(8)
        val cx = a[0] - b[0] + pad + progress * (seekSlider.width - 2 * pad)
        val bw = previewBox.width.takeIf { it > 0 } ?: dp(168)
        previewBox.translationY = -(dp(20) + osc.height + dp(10)).toFloat()
        previewBox.translationX = (cx - bw / 2f).coerceIn(dp(8).toFloat(), (playerRoot.width - bw - dp(8)).coerceAtLeast(dp(8)).toFloat())
        previewBox.visibility = View.VISIBLE

        if (previewBroken) { previewImage.visibility = View.GONE; return }
        previewCache.get(sec.toInt())?.let { setPreviewBitmap(it) }
        val uri = playlist.getOrNull(index)?.uri ?: return
        previewWantedUs = (sec * 1_000_000).toLong()
        synchronized(previewLock) { if (previewRunning) return; previewRunning = true }
        // one worker keeps decoding the *latest* wanted position; every frame it finishes is shown straight away
        previewExec.execute {
            while (true) {
                val us = previewWantedUs
                if (us >= 0) {
                    val bmp = frameAt(uri, us)
                    if (bmp != null) {
                        previewCache.put((us / 1_000_000).toInt(), bmp)
                        ui.post { if (previewBox.visibility == View.VISIBLE) setPreviewBitmap(bmp) }
                    }
                }
                synchronized(previewLock) {
                    if (previewWantedUs == us || previewBroken) { previewRunning = false; return@execute }
                }
            }
        }
    }

    private fun hidePreview() {
        previewWantedUs = -1
        previewBox.visibility = View.GONE
    }

    private fun frameAt(uri: Uri, us: Long): Bitmap? {
        return try {
            if (previewFor != uri) {
                previewRetriever?.release()
                previewRetriever = MediaMetadataRetriever().also {
                    if (uri.scheme == "http" || uri.scheme == "https") it.setDataSource(uri.toString(), HashMap())
                    else if (uri.scheme == "file") it.setDataSource(uri.path)
                    else it.setDataSource(this, uri)
                    val vw = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull()
                    val vh = it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull()
                    previewRatio = if (vw != null && vh != null && vh > 0) vw / vh else 0f
                }
                previewFor = uri
            }
            val r = previewRetriever ?: return null
            val w = 320
            if (Build.VERSION.SDK_INT >= 27 && previewRatio > 0f) {
                // decoding straight to thumbnail size is much faster than a full-size frame
                r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, (w / previewRatio).toInt().coerceAtLeast(1))
            } else {
                val raw = r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
                val h = (raw.height * (w / raw.width.toFloat())).toInt().coerceAtLeast(1)
                if (raw.width > w) Bitmap.createScaledBitmap(raw, w, h, true).also { if (it !== raw) raw.recycle() } else raw
            }
        } catch (_: Exception) {
            previewBroken = true
            null
        }
    }

    // ---------------------------------------------------------------- side panel

    private fun isPanelOpen() = panelKind.isNotEmpty()

    private fun openPanel(kind: String) {
        if (!hasFile && kind != "playlist") return
        if (panelKind == kind) { closePanel(); return }
        val wasOpen = isPanelOpen()
        panelKind = kind
        rebuildPanel()
        setControlsVisible(false)
        if (!wasOpen) {
            sidePanel.visibility = View.VISIBLE
            sidePanel.translationX = panelWidth().toFloat()
            sidePanel.animate().translationX(0f).setDuration(220).start()
        }
    }

    private fun closePanel(animate: Boolean = true) {
        if (!isPanelOpen()) return
        panelKind = ""
        lastBuiltPanel = ""
        sidePanel.animate().cancel()
        if (animate) {
            sidePanel.animate().translationX(panelWidth().toFloat()).setDuration(200)
                .withEndAction { if (!isPanelOpen()) sidePanel.visibility = View.GONE }.start()
        } else sidePanel.visibility = View.GONE
        if (inPlayer) setControlsVisible(true)
    }

    private var lastBuiltPanel = ""

    private fun rebuildPanel() {
        val keep = if (lastBuiltPanel == panelKind) panelScroll.scrollY else 0
        lastBuiltPanel = panelKind
        panelBody.removeAllViews()
        cardTarget = null
        panelVolSlider = null; panelVolLabel = null
        panelBack.visibility = if (panelKind == "subs" || panelKind == "audio" || panelKind == "eq") View.VISIBLE else View.GONE
        when (panelKind) {
            "playlist" -> buildPlaylistPanel()
            "settings" -> buildSettingsPanel()
            "subs" -> buildSubtitlePanel()
            "audio" -> buildAudioPanel()
            "eq" -> buildEqPanel()
        }
        panelScroll.post { if (panelKind != "playlist") panelScroll.scrollTo(0, keep) }
    }

    // ---- Nebula panel helpers: grouped cards, tiles, chip grids, tinted switches

    private var cardTarget: LinearLayout? = null

    private fun pAdd(v: View, lp: LinearLayout.LayoutParams? = null) {
        val t = cardTarget
        if (t == null) { if (lp == null) panelBody.addView(v) else panelBody.addView(v, lp); return }
        if (t.childCount > 0) t.addView(View(this).apply { setBackgroundColor(Ui.LINE) },
            LinearLayout.LayoutParams(-1, 1).apply { leftMargin = dp(16); rightMargin = dp(16) })
        if (lp == null) t.addView(v) else t.addView(v, lp)
    }

    private fun pCard(block: () -> Unit) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rect(this@MainActivity, Ui.SURFACE, 18)
            setPadding(0, dp(4), 0, dp(4))
        }
        panelBody.addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), dp(2), dp(14), dp(2)) })
        cardTarget = card
        block()
        cardTarget = null
    }

    private fun rowBg(selected: Boolean): Drawable = RippleDrawable(
        ColorStateList.valueOf(0x22FFFFFF),
        if (selected) Ui.rect(this, Ui.soft(0x2E), 14) else null, null
    )

    private fun pill(on: Boolean): GradientDrawable =
        if (on) Ui.accentFill(this, 18) else Ui.rect(this, Ui.SURFACE2, 18)

    private fun iconChip(res: Int, sizeDp: Int = 38): View = FrameLayout(this).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Ui.soft(0x33), Ui.soft(0x22)))
            .apply { cornerRadius = dp(12).toFloat() }
        addView(ImageView(this@MainActivity).apply { setImageResource(res); setColorFilter(Ui.ACCENT_HI) },
            FrameLayout.LayoutParams(dp(sizeDp - 18), dp(sizeDp - 18), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun pSection(t: String) {
        panelBody.addView(TextView(this).apply {
            text = t.uppercase(); setTextColor(Ui.MUTED); textSize = 11f; letterSpacing = 0.12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(24), dp(20), dp(20), dp(8))
        })
    }

    private fun pHint(t: String) {
        pAdd(TextView(this).apply {
            text = t; setTextColor(Ui.MUTED); textSize = 13f
            setPadding(dp(20), dp(14), dp(20), dp(14))
        })
    }

    /** Wrapping grid of choice chips; the selected one gets the violet gradient. */
    private fun pChips(options: List<String>, selected: Int, cols: Int = 4, pick: (Int) -> Unit) {
        val grid = GridLayout(this).apply { columnCount = cols; setPadding(dp(11), dp(2), dp(11), dp(2)) }
        options.forEachIndexed { i, label ->
            grid.addView(TextView(this).apply {
                text = label; textSize = 12.5f; gravity = Gravity.CENTER; maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(if (i == selected) Color.WHITE else 0xFFC4C8D8.toInt())
                if (i == selected) setTypeface(typeface, Typeface.BOLD)
                background = if (i == selected) Ui.accentFill(this@MainActivity, 12) else Ui.rect(this@MainActivity, Ui.SURFACE2, 12)
                setPadding(dp(6), dp(11), dp(6), dp(11))
                setOnClickListener { pick(i) }
            }, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f)).apply {
                width = 0; setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        panelBody.addView(grid, LinearLayout.LayoutParams(-1, -2))
    }

    /** Selectable list row (audio / subtitle tracks, playlist). */
    private fun pRow(label: String, sub: String? = null, selected: Boolean = false, onClick: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(12), dp(12))
            background = rowBg(selected)
            setOnClickListener { onClick() }
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = label; textSize = 15f; maxLines = 2; ellipsize = TextUtils.TruncateAt.MIDDLE
            setTextColor(if (selected) Ui.ACCENT_HI else Ui.TEXT)
            if (selected) setTypeface(typeface, Typeface.BOLD)
        })
        if (!sub.isNullOrBlank()) col.addView(TextView(this).apply { text = sub; textSize = 12f; setTextColor(Ui.MUTED) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        if (selected) row.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_check); setColorFilter(Ui.ACCENT_HI)
        }, LinearLayout.LayoutParams(dp(22), dp(22)))
        pAdd(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(6), dp(1), dp(6), dp(1)) })
        return row
    }

    private fun pToggle(label: String, sub: String?, on: Boolean, change: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(16), dp(10))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = label; textSize = 15f; setTextColor(Ui.TEXT) })
        if (sub != null) col.addView(TextView(this).apply { text = sub; textSize = 12f; setTextColor(Ui.MUTED) })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        row.addView(Switch(this).apply {
            isChecked = on
            thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, 0xFFB4B9CC.toInt()))
            trackTintList = ColorStateList(states, intArrayOf(Ui.ACCENT, 0xFF3A4156.toInt()))
            setOnCheckedChangeListener { _, v -> change(v) }
        })
        pAdd(row)
    }

    private fun pStepper(label: String, value: String, minus: () -> Unit, plus: () -> Unit, reset: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(8), dp(14), dp(8))
        }
        fun btn(t: String, a: () -> Unit) = TextView(this).apply {
            text = t; textSize = 20f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = Ui.rect(this@MainActivity, Ui.SURFACE2, 12)
            setOnClickListener { a() }
        }
        row.addView(TextView(this).apply { text = label; textSize = 15f; setTextColor(Ui.TEXT) }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(btn("−", minus), LinearLayout.LayoutParams(dp(40), dp(38)))
        row.addView(TextView(this).apply {
            text = value; textSize = 13f; setTextColor(Ui.TEAL); gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE; setOnClickListener { reset() }
        }, LinearLayout.LayoutParams(dp(60), dp(38)))
        row.addView(btn("+", plus), LinearLayout.LayoutParams(dp(40), dp(38)))
        pAdd(row)
    }

    /** Settings row with an icon chip, the current value on a second line and a chevron. */
    private fun pNavRow(label: String, value: String?, icon: Int? = null, onClick: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = rowBg(false)
            setOnClickListener { onClick() }
        }
        if (icon != null) row.addView(iconChip(icon), LinearLayout.LayoutParams(dp(38), dp(38)).apply { rightMargin = dp(14) })
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply { text = label; textSize = 15f; setTextColor(Ui.TEXT) })
        if (!value.isNullOrBlank()) col.addView(TextView(this).apply {
            text = value; textSize = 12.5f; setTextColor(Ui.TEAL); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply { text = "›"; textSize = 24f; setTextColor(Ui.MUTED) })
        pAdd(row)
    }

    /** Two big tiles side by side (Audio / Subtitles). */
    private fun pTiles(a: Triple<Int, String, String>, onA: () -> Unit, b: Triple<Int, String, String>, onB: () -> Unit) {
        fun tile(t: Triple<Int, String, String>, click: () -> Unit) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), Ui.rect(this@MainActivity, Ui.SURFACE, 18), null)
            setPadding(dp(14), dp(14), dp(12), dp(14))
            setOnClickListener { click() }
            addView(iconChip(t.first, 40))
            addView(TextView(this@MainActivity).apply {
                text = t.second; textSize = 16f; setTextColor(Ui.TEXT); setTypeface(typeface, Typeface.BOLD)
                setPadding(0, dp(12), 0, 0)
            })
            addView(TextView(this@MainActivity).apply {
                text = t.third; textSize = 12.5f; setTextColor(Ui.TEAL); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(tile(a, onA), LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(5) })
        row.addView(tile(b, onB), LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(5) })
        panelBody.addView(row, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(14), dp(6), dp(14), dp(2)) })
    }

    private fun buildPlaylistPanel() {
        panelTitle.text = "Playlist"
        var selectedRow: View? = null
        pCard {
            playlist.forEachIndexed { i, v ->
                val r = pRow(v.name.substringBeforeLast('.'), null, i == index) { playAt(i); closePanel() }
                if (i == index) selectedRow = r
            }
        }
        selectedRow?.let { r -> panelScroll.post { panelScroll.scrollTo(0, ((r.parent as View).top + r.top - dp(120)).coerceAtLeast(0)) } }
    }

    private fun buildSettingsPanel() {
        panelTitle.text = "Settings"
        val audioNow = tracks("audio").firstOrNull { it.selected }
        val subNow = tracks("sub").firstOrNull { it.selected }
        pTiles(
            Triple(R.drawable.ic_audio, "Audio", audioNow?.label ?: "None"), { openPanel("audio") },
            Triple(R.drawable.ic_subtitles, "Subtitles", subNow?.label ?: "Off"), { openPanel("subs") }
        )
        pCard { pNavRow("Playback speed", speedLabel(), R.drawable.ic_speed) { showSpeedDialog() } }

        pSection("Picture")
        pChips(aspectNames, aspectNames.indexOf(aspectLabel).coerceAtLeast(0), 4) { i ->
            aspectLabel = aspectNames[i]
            sizeMode = 0; sizeButton.setImageResource(sizeModeIcons[0]); mpv.setPropertyString("video-unscaled", "no")
            mpv.setPropertyString("keepaspect", if (i == 6) "no" else "yes")
            mpv.setPropertyString("video-aspect-override", aspectValues[i])
            mpv.setPropertyDouble("panscan", if (i == 5) 1.0 else 0.0)
            zoom = 0.0; mpv.setPropertyDouble("video-zoom", 0.0)
            rebuildPanel()
        }

        pSection("Sleep timer")
        pChips(sleepNames, sleepIndex, 3) { i -> setSleepTimer(i); rebuildPanel() }

        pSection("Player")
        pCard {
            pToggle("Hardware decoding", null, hwdecOn) { on ->
                hwdecOn = on
                getSharedPreferences("player", MODE_PRIVATE).edit().putBoolean("hwdec", on).apply()
                mpv.setPropertyString("hwdec", if (on) "mediacodec-copy" else "no")
                Toast.makeText(this, "Applies to the next video", Toast.LENGTH_SHORT).show()
            }
            pToggle("Auto picture-in-picture", null, autoPip) { on ->
                autoPip = on
                getSharedPreferences("player", MODE_PRIVATE).edit().putBoolean("autopip", on).apply()
            }
            pToggle("Background play", null, bgPlay) { on -> setBackgroundPlay(on) }
        }
        panelBody.addView(TextView(this).apply {
            text = "Gplay 1.3  ·  Developed by Gulshan Kumar"
            setTextColor(0xFF5A607A.toInt()); textSize = 11f; gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, dp(6))
        })
    }

    private fun formatSpeed(v: Double): String =
        if (abs(v - Math.round(v)) < 0.001) Math.round(v).toString()
        else java.lang.String.format(java.util.Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    private fun speedLabel() = formatSpeed(speed) + "x"

    private fun setSpeed(v: Double) {
        speed = Math.round(v.coerceIn(MIN_SPEED, MAX_SPEED) * 100) / 100.0
        if (!boosting) mpv.setPropertyDouble("speed", speed)
        speedButton.text = speedLabel()
        if (panelKind == "settings") rebuildPanel()
    }

    private fun endBoost() {
        boosting = false
        mpv.setPropertyDouble("speed", speed)
        hud.visibility = View.GONE
    }

    private fun positionPip() {
        val lp = pipButton.layoutParams as FrameLayout.LayoutParams
        val narrow = resources.displayMetrics.widthPixels < dp(700)
        lp.bottomMargin = if (narrow) dp(20) + osc.height + dp(12) else dp(24)
        pipButton.layoutParams = lp
        val lp2 = sizeButton.layoutParams as FrameLayout.LayoutParams
        lp2.bottomMargin = lp.bottomMargin
        sizeButton.layoutParams = lp2
    }

    private val sizeModeNames = listOf("Fit to screen", "Stretch", "Crop", "100%")
    private val sizeModeIcons = listOf(R.drawable.ic_size_fit, R.drawable.ic_size_stretch, R.drawable.ic_size_crop, R.drawable.ic_size_100)

    private fun setSizeMode(i: Int) {
        sizeMode = i
        sizeButton.setImageResource(sizeModeIcons[i])
        // the size button and the aspect-ratio chips both drive the same mpv properties, so reset the chips
        aspectLabel = aspectNames[0]
        mpv.setPropertyString("video-aspect-override", "-1")
        mpv.setPropertyString("keepaspect", if (i == 1) "no" else "yes")
        mpv.setPropertyString("video-unscaled", if (i == 3) "yes" else "no")
        mpv.setPropertyDouble("panscan", if (i == 2) 1.0 else 0.0)
        zoom = 0.0; mpv.setPropertyDouble("video-zoom", 0.0)
        showHud(sizeModeNames[i])
    }

    private fun showSpeedDialog() {
        val dlg = android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
        val wide = resources.displayMetrics.widthPixels > resources.displayMetrics.heightPixels
        val ruler = SpeedRuler(this, MIN_SPEED, MAX_SPEED).apply { value = speed }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(formatSpeed(speed)); gravity = Gravity.CENTER; textSize = 52f
            setTypeface(typeface, Typeface.BOLD); setTextColor(Ui.TEXT)
            background = null; setPadding(0, 0, 0, 0); isSingleLine = true; setSelectAllOnFocus(true)
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
        }
        val chips = ArrayList<Pair<Double, TextView>>()
        fun styleChips() = chips.forEach { (v, tv) ->
            val on = abs(speed - v) < 0.001
            tv.background = if (on) Ui.accentFill(this, 14) else Ui.rect(this, Ui.SURFACE2, 14)
            tv.setTextColor(if (on) Color.WHITE else 0xFFC4C8D8.toInt())
        }
        var syncing = false
        fun apply(v: Double, fromTyping: Boolean = false) {
            setSpeed(v)
            syncing = true
            if (!fromTyping) { input.setText(formatSpeed(speed)); input.setSelection(input.text.length) }
            ruler.value = speed
            styleChips()
            syncing = false
        }
        ruler.onChange = { apply(it) }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) {
                if (syncing) return
                val v = e?.toString()?.toDoubleOrNull() ?: return
                if (v >= MIN_SPEED && v <= MAX_SPEED) apply(v, true)
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        fun roundBtn(t: String, d: Double) = TextView(this).apply {
            text = t; textSize = 26f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.SURFACE2) }
            setOnClickListener { apply(speed + d) }
        }
        val numberRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        numberRow.addView(roundBtn("−", -0.05), LinearLayout.LayoutParams(dp(52), dp(52)))
        val num = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER or Gravity.BOTTOM }
        num.addView(input, LinearLayout.LayoutParams(-2, -2))
        num.addView(TextView(this).apply { text = "×"; textSize = 26f; setTextColor(Ui.TEAL); setPadding(dp(2), 0, 0, dp(10)) })
        numberRow.addView(num, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(8); rightMargin = dp(8) })
        numberRow.addView(roundBtn("+", 0.05), LinearLayout.LayoutParams(dp(52), dp(52)))

        val grid = GridLayout(this).apply { columnCount = 4 }
        listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0, 4.0).forEach { v ->
            val tv = TextView(this).apply {
                text = formatSpeed(v) + "x"; textSize = 14f; gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD); setPadding(0, dp(12), 0, dp(12))
                setOnClickListener { apply(v) }
            }
            chips += v to tv
            grid.addView(tv, GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED, 1f), GridLayout.spec(GridLayout.UNDEFINED, 1f))
                .apply { width = 0; setMargins(dp(3), dp(3), dp(3), dp(3)) })
        }
        styleChips()

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(TextView(this).apply {
            text = "Reset 1x"; textSize = 14f; setTextColor(Ui.MUTED); setPadding(dp(8), dp(12), dp(16), dp(12))
            setOnClickListener { apply(1.0) }
        })
        actions.addView(TextView(this).apply {
            text = "Done"; textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = Ui.accentFill(this@MainActivity, 24); setPadding(0, dp(12), 0, dp(12))
            setOnClickListener {
                input.text.toString().toDoubleOrNull()?.let { setSpeed(it) }
                dlg.dismiss()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        input.setOnEditorActionListener { _, _, _ ->
            input.text.toString().toDoubleOrNull()?.let { apply(it) }; false
        }

        fun title() = TextView(this).apply {
            text = "PLAYBACK SPEED"; textSize = 11f; letterSpacing = 0.14f; setTextColor(Ui.MUTED)
            setTypeface(typeface, Typeface.BOLD); setPadding(dp(4), 0, 0, dp(10))
        }
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.topRounded(this@MainActivity, 0xFF11141F.toInt(), 28)
            setPadding(dp(22), dp(10), dp(22), dp(18))
        }
        sheet.addView(View(this).apply { background = Ui.rect(this@MainActivity, 0x33FFFFFF, 3) },
            LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(14) })
        if (wide) {
            val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            left.addView(title()); left.addView(numberRow)
            left.addView(ruler, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
            right.addView(grid); right.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            cols.addView(left, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(14) })
            cols.addView(right, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(14) })
            sheet.addView(cols)
        } else {
            sheet.addView(title()); sheet.addView(numberRow)
            sheet.addView(ruler, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            sheet.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            sheet.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        dlg.setContentView(ScrollView(this).apply { addView(sheet); isFillViewport = false })
        dlg.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(minOf(dp(760), resources.displayMetrics.widthPixels - dp(12)), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.55f)
        }
        dlg.show()
    }

    private fun setSleepTimer(i: Int) {
        sleepRunnable?.let { ui.removeCallbacks(it) }
        sleepRunnable = null
        sleepEndOfVideo = false
        sleepIndex = i
        sleepLabel = sleepNames[i]
        when {
            sleepMinutes[i] > 0 -> {
                sleepRunnable = Runnable {
                    mpv.setPropertyBoolean("pause", true)
                    sleepIndex = 0; sleepLabel = "Off"
                    Toast.makeText(this, "Sleep timer: playback paused", Toast.LENGTH_LONG).show()
                }.also { ui.postDelayed(it, sleepMinutes[i] * 60_000L) }
            }
            sleepMinutes[i] == -1 -> sleepEndOfVideo = true
        }
        showHud("Sleep timer: $sleepLabel")
    }

    private class Track(val id: Int, val label: String, val sub: String?, val selected: Boolean, val base: String = label)

    private fun tracks(type: String): List<Track> {
        val out = ArrayList<Track>()
        val n = mpv.getPropertyInt("track-list/count") ?: 0
        for (i in 0 until n) {
            if (mpv.getPropertyString("track-list/$i/type") != type) continue
            val id = mpv.getPropertyInt("track-list/$i/id") ?: continue
            val lang = mpv.getPropertyString("track-list/$i/lang")?.uppercase()
            val title = mpv.getPropertyString("track-list/$i/title")
            val codec = mpv.getPropertyString("track-list/$i/codec")?.uppercase()
            val ch = mpv.getPropertyString("track-list/$i/demux-channels")
            val rate = mpv.getPropertyString("track-list/$i/demux-samplerate")?.toIntOrNull()
            val langName = lang?.let { l ->
                java.util.Locale.forLanguageTag(l.lowercase()).getDisplayLanguage(java.util.Locale.ENGLISH)
                    .takeIf { it.isNotBlank() && !it.equals(l, true) }
            } ?: lang
            val name = if (langName.isNullOrBlank()) title?.takeIf { it.isNotBlank() } ?: "Track $id" else langName
            val chNice = ch?.substringBefore('(')?.let { c ->
                Regex("unknown(\\d+)").matchEntire(c)?.let { m -> "${m.groupValues[1]}ch" } ?: c
            }
            val sub: String? = null
            val same = out.count { it.base == name }
            out += Track(id, if (same == 0) name else "$name ${same + 1}", sub, mpv.getPropertyString("track-list/$i/selected") == "yes", name)
        }
        return out
    }

    private fun buildSubtitlePanel() {
        panelTitle.text = "Subtitles"
        val subs = tracks("sub")
        pCard {
            pRow("Off", null, subs.none { it.selected }) { mpv.setPropertyString("sid", "no"); rebuildPanel() }
            subs.forEach { t -> pRow(t.label, t.sub, t.selected) { mpv.setPropertyString("sid", t.id.toString()); rebuildPanel() } }
            pRow("Load subtitle file…", null) { pickSubtitleFile() }
        }

        val sizes = doubleArrayOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)
        pSection("Size")
        pChips(sizes.map { "${(it * 100).toInt()}%" }, sizes.indexOfFirst { it == subScale }.coerceAtLeast(0), 3) {
            subScale = sizes[it]; mpv.setPropertyDouble("sub-scale", subScale); rebuildPanel()
        }
        val pos = intArrayOf(100, 95, 90, 80, 70, 50)
        pSection("Position")
        pChips(listOf("Bottom", "Up 1", "Up 2", "Up 3", "Up 4", "Middle"), pos.indexOf(subPos).coerceAtLeast(0), 3) {
            subPos = pos[it]; mpv.setPropertyInt("sub-pos", subPos); rebuildPanel()
        }
        pSection("Timing")
        pCard {
            fun setDelay(v: Double) { subDelay = Math.round(v * 10) / 10.0; mpv.setPropertyDouble("sub-delay", subDelay); rebuildPanel() }
            pStepper("Subtitle delay", "%+.1fs".format(subDelay), { setDelay(subDelay - 0.1) }, { setDelay(subDelay + 0.1) }, { setDelay(0.0) })
        }
    }

    private fun audioChannels() = when (audioMode) { 1 -> "mono"; 2 -> "auto-safe"; else -> "stereo" }

    private fun applyAudioFx() { fx.apply() }

    private fun fxChanged(final: Boolean) {
        fx.apply()
        if (final) fx.save()
    }

    private fun buildEqPanel() {
        panelTitle.text = "Equalizer"
        pCard { pToggle("Equalizer", null, fx.enabled) { on -> fx.enabled = on; fx.save(); applyAudioFx(); rebuildPanel() } }
        if (!fx.eqSupported) { pHint("The equalizer is not available on this phone"); return }
        val start = panelBody.childCount

        pSection("Preset")
        pChips(fx.presetNames, fx.preset, 3) { i -> fx.applyPreset(i); fxChanged(true); rebuildPanel() }

        pSection("Bands")
        val bandRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(6), dp(10), dp(6), dp(8)) }
        (0 until fx.bandCount).forEach { i ->
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val db = TextView(this).apply { textSize = 11.5f; setTextColor(Ui.TEAL); typeface = Typeface.MONOSPACE }
            fun label(v: Int) { db.text = if (v == 0) "0" else "%+.1f".format(java.util.Locale.US, v / 10.0) }
            label(fx.bands[i])
            col.addView(db, LinearLayout.LayoutParams(-2, -2))
            col.addView(EqBand(this, fx.limit).apply {
                value = fx.bands[i]
                onChange = { v, final -> fx.bands[i] = v; fx.preset = -1; label(v); fxChanged(final) }
            }, LinearLayout.LayoutParams(-1, -2))
            col.addView(TextView(this).apply { text = fx.freqNames[i]; textSize = 11.5f; setTextColor(Ui.MUTED); setPadding(0, dp(2), 0, 0) })
            bandRow.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        }
        pCard { pAdd(bandRow) }

        pSection("Sound effects")
        fun effect(name: String, value: Int, change: (Int) -> Unit) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(10), dp(14), dp(6)) }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val pct = TextView(this).apply { text = "$value%"; textSize = 13f; setTextColor(Ui.TEAL); typeface = Typeface.MONOSPACE }
            head.addView(TextView(this).apply { text = name; textSize = 15f; setTextColor(Ui.TEXT) }, LinearLayout.LayoutParams(0, -2, 1f))
            head.addView(pct)
            box.addView(head)
            box.addView(Slider(this, barKnob = false).apply {
                tint(Ui.ACCENT, Ui.ACCENT_HI); progress = value / 100f
                onChange = { p, _ -> val v = (p * 100).roundToInt(); pct.text = "$v%"; change(v); fxChanged(false) }
                onTouchEnd = { fxChanged(true) }
            }, LinearLayout.LayoutParams(-1, -2))
            pAdd(box)
        }
        pCard {
            if (fx.bassSupported) effect("Bass boost", fx.bass) { fx.bass = it } else pHint("Bass boost is not supported on this phone")
            if (fx.virtSupported) effect("Virtualizer", fx.virtualizer) { fx.virtualizer = it } else pHint("Virtualizer is not supported on this phone")
        }
        panelBody.addView(TextView(this).apply {
            text = "Reset equalizer"; textSize = 14f; gravity = Gravity.CENTER; setTextColor(Ui.TEAL)
            setPadding(0, dp(16), 0, dp(4))
            setOnClickListener { fx.reset(); fxChanged(true); rebuildPanel() }
        })

        // while the equalizer is off the controls stay visible but dimmed and untouchable
        val moved = (start until panelBody.childCount).map { panelBody.getChildAt(it) }
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        moved.forEach { panelBody.removeView(it); holder.addView(it) }
        val wrap = FrameLayout(this)
        wrap.addView(holder, FrameLayout.LayoutParams(-1, -2))
        if (!fx.enabled) {
            holder.alpha = 0.38f
            wrap.addView(View(this).apply { isClickable = true }, FrameLayout.LayoutParams(-1, -1))
        }
        panelBody.addView(wrap, LinearLayout.LayoutParams(-1, -2))
    }

    private fun buildAudioPanel() {
        panelTitle.text = "Audio"
        val list = tracks("audio")
        pSection("Track")
        pCard {
            pRow("Disabled", null, list.none { it.selected }) { mpv.setPropertyString("aid", "no"); rebuildPanel() }
            list.forEach { t -> pRow(t.label, null, t.selected) { mpv.setPropertyString("aid", t.id.toString()); rebuildPanel() } }
        }

        pSection("Stereo mode")
        pChips(listOf("Stereo", "Mono", "Surround"), audioMode, 3) { i ->
            audioMode = i
            getSharedPreferences("player", MODE_PRIVATE).edit().putInt("audiomode", i).apply()
            mpv.setPropertyString("audio-channels", audioChannels())
            rebuildPanel()
        }

        pSection("Sync")
        pCard {
            fun setDelay(v: Double) { audioDelay = Math.round(v * 20) / 20.0; mpv.setPropertyDouble("audio-delay", audioDelay); rebuildPanel() }
            pStepper("Audio delay", "%+d ms".format((audioDelay * 1000).roundToInt()), { setDelay(audioDelay - 0.05) }, { setDelay(audioDelay + 0.05) }, { setDelay(0.0) })
        }

        pSection("Sound")
        pCard { pNavRow("Equalizer", if (fx.enabled) "On" else "Off", R.drawable.ic_equalizer) { openPanel("eq") } }
    }

    // ---------------------------------------------------------------- dialogs

    private fun dialog() = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun showVideoMenu(v: Video) {
        val progress = ResumeStore.progress(this, v)
        val items = ArrayList<String>().apply {
            add("Play"); add("Share"); add("Rename"); add("Delete"); add("Details")
            if (progress > 0f) add("Mark as unwatched")
        }
        dialog().setTitle(v.name).setItems(items.toTypedArray()) { _, which ->
            when (items[which]) {
                "Play" -> startPlayback(listOf(v), 0)
                "Share" -> startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("video/*").putExtra(Intent.EXTRA_STREAM, v.uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share video"))
                "Rename" -> askRename(v)
                "Delete" -> dialog().setTitle("Delete video?")
                    .setMessage("\"${v.name}\" will be deleted from your device.")
                    .setPositiveButton("Delete") { _, _ -> deleteVideo(v) }
                    .setNegativeButton("Cancel", null).show()
                "Details" -> dialog().setTitle("Details").setMessage(
                    "Name: ${v.name}\nFolder: ${v.folder}\nSize: ${VideoRepo.fmtSize(v.size)}\n" +
                            "Duration: ${VideoRepo.fmtDuration(v.durationMs)}\nWatched: ${(progress * 100).toInt()}%"
                ).setPositiveButton("OK", null).show()
                "Mark as unwatched" -> { ResumeStore.clear(this, v); library.refreshProgress() }
            }
        }.show()
    }

    private fun showAbout() {
        dialog().setTitle("Gplay 1.3")
            .setMessage(
                "Developed by Gulshan Kumar\n\n" +
                "Playback engine: mpv + FFmpeg (open-source, GPL / LGPL).\n\n" +
                "Your videos stay on your device. Network streams play straight from the link you give."
            )
            .setPositiveButton("OK", null).show()
    }

    /** Bottom sheet in the app's look; [build] fills it. */
    private fun sheet(build: (LinearLayout, android.app.Dialog) -> Unit) {
        val dlg = android.app.Dialog(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.topRounded(this@MainActivity, 0xFF11141F.toInt(), 28)
            setPadding(dp(20), dp(10), dp(20), dp(20))
        }
        box.addView(View(this).apply { background = Ui.rect(this@MainActivity, 0x33FFFFFF, 3) },
            LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
        build(box, dlg)
        dlg.setContentView(ScrollView(this).apply { addView(box) })
        dlg.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            setLayout(minOf(dp(560), resources.displayMetrics.widthPixels - dp(12)), ViewGroup.LayoutParams.WRAP_CONTENT)
            setDimAmount(0.55f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        dlg.show()
    }

    private fun sheetTitle(t: String, sub: String? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(2), 0, 0, dp(12))
        addView(TextView(this@MainActivity).apply {
            text = t; textSize = 19f; setTextColor(Ui.TEXT); setTypeface(typeface, Typeface.BOLD)
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        })
        if (sub != null) addView(TextView(this@MainActivity).apply {
            text = sub; textSize = 12.5f; setTextColor(Ui.MUTED); maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
        })
    }

    private fun sheetField(value: String, hint: String) = EditText(this).apply {
        setText(value); this.hint = hint
        setTextColor(Ui.TEXT); setHintTextColor(Ui.MUTED); textSize = 15f
        isSingleLine = true; setSelectAllOnFocus(true)
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        background = Ui.rect(this@MainActivity, Ui.SURFACE, 14)
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun sheetButton(label: String, primary: Boolean, click: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (primary) Color.WHITE else Ui.TEXT)
        background = if (primary) Ui.accentFill(this@MainActivity, 24) else Ui.rect(this@MainActivity, Ui.SURFACE2, 24)
        setPadding(0, dp(13), 0, dp(13))
        setOnClickListener { click() }
    }

    private fun showStreamMenu(s: NetworkStore.Stream) {
        sheet { box, dlg ->
            box.addView(sheetTitle(s.title, s.url))
            fun action(label: String, danger: Boolean = false, run: () -> Unit) {
                box.addView(TextView(this).apply {
                    text = label; textSize = 16f
                    setTextColor(if (danger) 0xFFFF6B6B.toInt() else Ui.TEXT)
                    setPadding(dp(8), dp(14), dp(8), dp(14))
                    background = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, null)
                    setOnClickListener { dlg.dismiss(); run() }
                })
            }
            action("Play") { playStream(s.url, s.title) }
            val dl = Downloads.info(this, s.url)
            when {
                dl?.active == true -> action("Cancel download") { Downloads.remove(this, s.url); library.refreshProgress() }
                dl?.done == true -> action("Delete downloaded video", danger = true) {
                    Downloads.remove(this, s.url); library.refreshProgress(); refreshLibrary(force = true)
                    Toast.makeText(this, "Downloaded video deleted", Toast.LENGTH_SHORT).show()
                }
                else -> action("Download") { startStreamDownload(s) }
            }
            action("Rename") { renameStreamSheet(s) }
            action("Copy link") {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("stream", s.url))
                Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
            }
            action("Share link") {
                startActivity(Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, s.url), "Share link"))
            }
            action("Remove", danger = true) { NetworkStore.remove(this, s.url); library.refreshProgress() }
        }
    }

    private val dlTicker = object : Runnable {
        override fun run() {
            library.refreshProgress()
            if (Downloads.anyActive(this@MainActivity)) ui.postDelayed(this, 1000)
            else refreshLibrary(force = true)   // finished: the new file now shows in the Gplay folder
        }
    }

    private fun startStreamDownload(s: NetworkStore.Stream) {
        Toast.makeText(this, "Starting download…", Toast.LENGTH_SHORT).show()
        Downloads.start(this, s.url, s.title) { error ->
            if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            else {
                Toast.makeText(this, "Downloading to the Gplay folder", Toast.LENGTH_SHORT).show()
                ui.removeCallbacks(dlTicker); ui.post(dlTicker)
            }
        }
    }

    private fun renameStreamSheet(s: NetworkStore.Stream) {
        sheet { box, dlg ->
            box.addView(sheetTitle("Rename"))
            val input = sheetField(s.title, "Name")
            box.addView(input)
            val go = {
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) { NetworkStore.rename(this, s.url, t); library.refreshProgress() }
                dlg.dismiss()
            }
            input.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
            input.setOnEditorActionListener { _, _, _ -> go(); true }
            box.addView(sheetButton("Save", true) { go() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        }
    }

    private fun textInput(value: String, hint: String) = EditText(this).apply {
        setText(value); this.hint = hint
        setTextColor(Color.WHITE); setHintTextColor(grey)
        setSelectAllOnFocus(true)
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
        setPadding(dp(24), dp(16), dp(24), dp(16))
    }

    /** Add (and play) a network stream. */
    private fun askStream(prefill: String? = null) {
        val clip = prefill ?: try {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()?.trim()
        } catch (_: Exception) { null }
        sheet { box, dlg ->
            box.addView(sheetTitle("Add network stream"))
            val url = sheetField(if (clip != null && clip.contains("://")) clip else "", "Paste a link")
                .apply { inputType = InputType.TYPE_TEXT_VARIATION_URI }
            val name = sheetField("", "Name (optional)")
            box.addView(url)
            box.addView(name, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val save = sheetButton("Save", false) {
                if (url.text.isNotBlank()) { addStream(url.text.toString(), name.text.toString(), play = false); dlg.dismiss() }
            }
            val play = sheetButton("Play", true) {
                if (url.text.isNotBlank()) { addStream(url.text.toString(), name.text.toString(), play = true); dlg.dismiss() }
            }
            buttons.addView(save, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(6) })
            buttons.addView(play, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(6) })
            box.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
            fun sync() { val on = url.text.isNotBlank(); save.alpha = if (on) 1f else 0.4f; play.alpha = if (on) 1f else 0.4f }
            sync()
            url.addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) = sync()
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
            if (url.text.isBlank()) url.requestFocus()
        }
    }

    private fun addStream(rawUrl: String, rawName: String, play: Boolean) {
        var u = rawUrl.trim()
        if (u.isEmpty()) return
        if (!u.contains("://")) u = "https://$u"
        NetworkStore.add(this, u, rawName.trim().ifEmpty { null })
        library.refreshProgress()
        if (play) playStream(u, NetworkStore.all(this).firstOrNull { it.url == u }?.title ?: u)
        else Toast.makeText(this, "Saved to Network", Toast.LENGTH_SHORT).show()
    }

    private fun playStream(url: String, title: String) {
        NetworkStore.add(this, url) // bump to the top
        NetworkStore.markPlayed(this, url)
        library.refreshProgress()
        startPlayback(listOf(Video(-1, Uri.parse(url), title, 0, 0)), 0)
    }

    // ---- rename / delete (Android asks the user to confirm changes to other apps' files)

    private fun askRename(v: Video) {
        val ext = v.name.substringAfterLast('.', "")
        val base = v.name.substringBeforeLast('.')
        val input = textInput(base, "New name")
        dialog().setTitle("Rename video").setView(input)
            .setPositiveButton("Rename") { _, _ ->
                val n = input.text.toString().trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                if (n.isNotEmpty() && n != base) renameVideo(v, if (ext.isEmpty()) n else "$n.$ext")
            }.setNegativeButton("Cancel", null).show()
    }

    private fun renameVideo(v: Video, newName: String) {
        pendingRename = v to newName
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val pi = MediaStore.createWriteRequest(contentResolver, listOf(v.uri))
                startIntentSenderForResult(pi.intentSender, REQ_WRITE, null, 0, 0, 0)
            } else doRename(v, newName)
        } catch (e: Exception) { toast("Cannot rename this video") }
    }

    private fun doRename(v: Video, newName: String) {
        try {
            val cv = ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }
            val n = contentResolver.update(v.uri, cv, null, null)
            toast(if (n > 0) "Renamed" else "Could not rename")
            refreshLibrary(force = true)
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= 29 && askUserFor(e, REQ_RENAME_Q)) return
            toast("Cannot rename this video")
        } catch (e: Exception) { toast("Cannot rename this video") }
    }

    private fun deleteVideo(v: Video) {
        pendingDelete = v
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val pi = MediaStore.createDeleteRequest(contentResolver, listOf(v.uri))
                startIntentSenderForResult(pi.intentSender, REQ_DELETE, null, 0, 0, 0)
            } else doDelete(v)
        } catch (e: Exception) { toast("Cannot delete this video") }
    }

    private fun doDelete(v: Video) {
        try {
            val n = contentResolver.delete(v.uri, null, null)
            if (n > 0) onDeleted(v) else toast("Could not delete")
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= 29 && askUserFor(e, REQ_DELETE_Q)) return
            toast("Cannot delete this video")
        } catch (e: Exception) { toast("Cannot delete this video") }
    }

    /** Android 10: the system asks the user for permission to modify a file owned by another app. */
    private fun askUserFor(e: SecurityException, code: Int): Boolean {
        val r = e as? RecoverableSecurityException ?: return false
        startIntentSenderForResult(r.userAction.actionIntent.intentSender, code, null, 0, 0, 0)
        return true
    }

    private fun onDeleted(v: Video) {
        ResumeStore.clear(this, v)
        toast("Deleted")
        refreshLibrary(force = true)
    }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    // ---------------------------------------------------------------- opening files

    private fun pickFile() {
        suppressPip = true
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "audio/*"))
        }, REQ_VIDEO)
    }

    private fun pickSubtitleFile() {
        suppressPip = true
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, REQ_SUB)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        suppressPip = false
        val ok = resultCode == RESULT_OK
        when (requestCode) {
            REQ_DELETE -> { if (ok) pendingDelete?.let { onDeleted(it) }; pendingDelete = null; return }
            REQ_DELETE_Q -> { if (ok) pendingDelete?.let { doDelete(it) }; return }
            REQ_WRITE -> { if (ok) pendingRename?.let { doRename(it.first, it.second) }; pendingRename = null; return }
            REQ_RENAME_Q -> { if (ok) pendingRename?.let { doRename(it.first, it.second) }; return }
        }
        val uri = data?.data ?: return
        if (!ok) return
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        when (requestCode) {
            REQ_VIDEO -> openExternal(uri)
            REQ_SUB -> uriToPath(uri, "sub")?.let { mpv.command(arrayOf("sub-add", it, "select")); if (panelKind == "subs") rebuildPanel() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (started) handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { openExternal(it) }
            Intent.ACTION_SEND -> {
                // a link shared from a browser / chat app
                val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
                val link = Regex("(https?|rtmps?|rtsp)://\\S+").find(text)?.value
                if (link == null) toast("No link found in the shared text") else askStream(link)
            }
        }
    }

    private fun openExternal(uri: Uri) {
        startPlayback(listOf(Video(-1, uri, displayName(uri) ?: "Video", 0, 0)), 0)
    }

    // mpv reads content:// files through a file descriptor. It does not close it, so we keep the handle and close it ourselves.
    private var videoPfd: android.os.ParcelFileDescriptor? = null
    private val subPfds = ArrayList<android.os.ParcelFileDescriptor>()

    private fun closeVideoFd() {
        try { videoPfd?.close() } catch (_: Exception) {}
        videoPfd = null
    }

    private fun closeSubFds() {
        subPfds.forEach { try { it.close() } catch (_: Exception) {} }
        subPfds.clear()
    }

    /** Opens [uri] for mpv. [kind] = "video" or "sub" decides which handle is kept (and closed later). */
    private fun uriToPath(uri: Uri, kind: String = "video"): String? = when (uri.scheme) {
        "content" -> try {
            contentResolver.openFileDescriptor(uri, "r")?.let { pfd ->
                if (kind == "video") { closeVideoFd(); videoPfd = pfd } else subPfds += pfd
                "fd://" + pfd.fd
            }
        } catch (e: Exception) { null }
        "file" -> uri.path
        else -> uri.toString()
    }

    private fun displayName(uri: Uri): String? {
        if (uri.scheme != "content") return uri.lastPathSegment
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) { null }
    }

    companion object {
        private const val REQ_VIDEO = 1
        private const val REQ_SUB = 2
        private const val REQ_PERM = 3
        private const val REQ_NOTIF = 4
        private const val REQ_DELETE = 5
        private const val REQ_DELETE_Q = 6
        private const val REQ_WRITE = 7
        private const val REQ_RENAME_Q = 8
        private const val MAX_VOLUME = 200.0
        private const val MAX_SPEED = 4.0
        private const val MIN_SPEED = 0.1
        private const val BOOST_SPEED = 2.0
    }
}
