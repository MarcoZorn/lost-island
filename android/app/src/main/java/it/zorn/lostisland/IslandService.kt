package it.zorn.lostisland

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.text.TextUtils
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.DisplayCutout
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.Chronometer
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextClock
import android.widget.TextView
import kotlin.math.abs

class IslandService : Service() {

    private enum class St { RESTING, COMPACT, PEEK, EXPANDED }

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var pill: LinearLayout
    private lateinit var card: LinearLayout
    private lateinit var outline: View

    // pill: leftSlot | spacer (camera gap) | rightSlot; capsule bg spans all three
    private lateinit var leftSlot: LinearLayout
    private lateinit var rightSlot: LinearLayout
    private lateinit var spacer: View

    // pill faces / compact content
    private lateinit var clock: TextClock
    private lateinit var titleText: TextView
    private lateinit var lyricText: TextView
    private lateinit var batteryText: TextView
    private lateinit var eq: EqBars
    private lateinit var dot: View
    private lateinit var artView: ImageView
    private lateinit var notifIcons: LinearLayout
    private lateinit var notifCount: TextView
    private lateinit var peekIcon: ImageView
    private lateinit var peekText: TextView

    // card
    private lateinit var cardTitle: TextView
    private lateinit var cardArtist: TextView
    private lateinit var cardBattery: TextView
    private lateinit var cardClock: TextClock
    private lateinit var progress: ProgressBar
    private lateinit var playPause: ImageButton
    private lateinit var notifHeader: TextView
    private lateinit var notifRows: LinearLayout

    private lateinit var pillBg: GradientDrawable
    private lateinit var cardBg: GradientDrawable
    private lateinit var outlineBg: GradientDrawable

    private val main = Handler(Looper.getMainLooper())
    private var state = St.RESTING
    private var face = "auto"
    private var animator: ValueAnimator? = null
    private var ringPulse: ObjectAnimator? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var lyricTicking = false
    private var peekEntry: NotifState.Entry? = null
    private var notch: Rect? = null
    private var effGapPx = 0
    private var battInit = false
    private var prevCharging = false
    private var prevPct = -1

    private val lyricTick = object : Runnable {
        override fun run() {
            refreshLyric()
            main.postDelayed(this, 1000)
        }
    }

    private val cardTick = object : Runnable {
        override fun run() {
            if (state != St.EXPANDED) return
            updateProgress()
            main.postDelayed(this, 1000)
        }
    }

    private val peekEnd = Runnable {
        peekEntry = null
        sync()
    }

    private val autoCollapseRun = Runnable { if (state == St.EXPANDED) collapse() }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        face = Prefs.face(prefs)
        applyStyle()
        if (state == St.EXPANDED) rebuildNotifRows() else sync()
        pushLayout()
    }

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        prefs = Prefs.get(this)
        face = Prefs.face(prefs)
        wm = getSystemService(WindowManager::class.java)
        buildViews()
        params = WindowManager.LayoutParams(
            WRAP_CONTENT,
            WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(Prefs.topOffset(prefs))
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(root, params)
        root.setOnApplyWindowInsetsListener { _, insets ->
            readInsets(insets)
            insets
        }
        root.requestApplyInsets()
        root.post { readInsets(root.rootWindowInsets) }
        applyStyle()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        MediaState.onChanged = ::updateUi
        NotifState.onChanged = ::onNotifsChanged
        NotifState.onPosted = ::onNotifPosted
        updateUi()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        MediaState.onChanged = null
        NotifState.onChanged = null
        NotifState.onPosted = null
        animator?.cancel()
        ringPulse?.cancel()
        main.removeCallbacksAndMessages(null)
        batteryReceiver?.let { unregisterReceiver(it) }
        batteryReceiver = null
        if (::prefs.isInitialized) prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
        if (::root.isInitialized && root.isAttachedToWindow) wm.removeView(root)
        super.onDestroy()
    }

    private fun startInForeground() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Island", NotificationManager.IMPORTANCE_MIN)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Lost Island")
            .setContentText("Island overlay is running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    // -- views ---------------------------------------------------------------

    private fun buildViews() {
        clock = TextClock(this).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 14f
            isSingleLine = true
        }
        titleText = pillLabel()
        lyricText = pillLabel()
        peekText = pillLabel()
        batteryText = TextView(this).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 13f
            isSingleLine = true
        }
        eq = EqBars(this)
        dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Prefs.accent(prefs))
            }
        }
        artView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        peekIcon = ImageView(this)
        notifIcons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setSize(dp(4), 0) }
        }
        notifCount = TextView(this).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 12f
            isSingleLine = true
        }

        fun slot() = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setSize(dp(8), 0) }
        }
        leftSlot = slot()
        rightSlot = slot()
        spacer = View(this)
        pillBg = islandBackground(dp(20).toFloat())
        pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            background = pillBg
            addView(leftSlot, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(spacer, LinearLayout.LayoutParams(0, 1))
            addView(rightSlot, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (state == St.RESTING && Prefs.idleMode(prefs) == "pill" && !Prefs.tapOpensCard(prefs)) {
                    cycle(1)
                } else {
                    expand()
                }
                return true
            }
            override fun onLongPress(e: MotionEvent) = doLongAction()
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (state == St.RESTING && Prefs.idleMode(prefs) == "pill" &&
                    abs(vx) > abs(vy) && abs(vx) > 400
                ) {
                    cycle(if (vx < 0) 1 else -1)
                    return true
                }
                return false
            }
        })
        pill.setOnTouchListener { _, event -> gestures.onTouchEvent(event) }

        outlineBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.TRANSPARENT)
            cornerRadius = dp(14).toFloat()
            setStroke(dp(2), 0x59FFFFFF)
        }
        outline = View(this).apply {
            background = outlineBg
            visibility = View.GONE
            setOnTouchListener { _, event -> gestures.onTouchEvent(event) }
        }

        val header = TextView(this).apply {
            text = "NOW PLAYING"
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(0x66FFFFFF)
        }
        cardClock = TextClock(this).apply {
            textSize = 11f
            setTextColor(0x66FFFFFF)
        }
        cardBattery = TextView(this).apply {
            textSize = 11f
            setTextColor(0x66FFFFFF)
        }
        val gear = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_preferences)
            setColorFilter(0x99FFFFFF.toInt())
            background = null
            setPadding(dp(8), dp(2), 0, dp(2))
            setOnClickListener {
                collapse()
                startActivity(
                    Intent(this@IslandService, SettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(header, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(cardClock, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                rightMargin = dp(8)
            })
            addView(cardBattery)
            addView(gear)
        }
        cardTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 17f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        cardArtist = TextView(this).apply {
            setTextColor(0xB3FFFFFF.toInt())
            textSize = 14f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
        }
        playPause = mediaButton(android.R.drawable.ic_media_play) {
            MediaState.controller?.transportControls?.let {
                if (MediaState.playing) it.pause() else it.play()
            }
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(mediaButton(android.R.drawable.ic_media_previous) {
                MediaState.controller?.transportControls?.skipToPrevious()
            })
            addView(playPause)
            addView(mediaButton(android.R.drawable.ic_media_next) {
                MediaState.controller?.transportControls?.skipToNext()
            })
        }
        notifHeader = TextView(this).apply {
            text = "NOTIFICATIONS"
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(0x66FFFFFF)
            visibility = View.GONE
        }
        notifRows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        cardBg = islandBackground(dp(34).toFloat())
        card = object : LinearLayout(this) {
            override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
                pokeAutoCollapse()
                return super.onInterceptTouchEvent(ev)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(10))
            background = cardBg
            visibility = View.GONE
            addView(headerRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            })
            addView(cardTitle)
            addView(cardArtist)
            addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, dp(4)).apply {
                topMargin = dp(10)
            })
            addView(controls, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
            addView(notifHeader, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(10)
            })
            addView(notifRows, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            setOnClickListener { collapse() }
        }

        root = FrameLayout(this).apply {
            addView(pill, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            addView(card, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            // outline drawn last so the ring pulse sits on top of everything
            addView(outline, FrameLayout.LayoutParams(dp(40), dp(20), Gravity.CENTER))
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE && state == St.EXPANDED) collapse()
                false
            }
        }
    }

    private fun pillLabel() = TextView(this).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun islandBackground(radius: Float) = GradientDrawable().apply {
        setColor(0xF70C0C0E.toInt())
        cornerRadius = radius
        setStroke(1, 0x17FFFFFF)
    }

    private fun mediaButton(icon: Int, onClick: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon)
        setColorFilter(Color.WHITE)
        background = null
        setPadding(dp(18), dp(10), dp(18), dp(10))
        setOnClickListener { onClick() }
    }

    // -- cutout detection ----------------------------------------------------

    private fun readInsets(insets: WindowInsets?) {
        // Read the cutout from the *display*, not the overlay's own window insets:
        // window insets are reported relative to the overlay, so moving the
        // overlay to centre on the notch shifts the reported cutout and the two
        // chase each other forever. The display cutout is fixed in screen space.
        val cutout = stableCutout() ?: insets?.displayCutout
        val newNotch = cutout?.let { pickTopRect(it) }
            ?.takeIf { it.width() > 0 && it.height() > 0 }
        if (newNotch == notch) return   // nothing changed; don't re-lay-out (kills the feedback loop)
        notch = newNotch
        if (state == St.EXPANDED) {
            applyLayout()
            pushLayout()
        } else {
            sync()
        }
    }

    private fun stableCutout(): DisplayCutout? {
        if (Build.VERSION.SDK_INT < 29) return null
        return try {
            getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)?.cutout
        } catch (_: Exception) {
            null
        }
    }

    private fun pickTopRect(c: DisplayCutout): Rect? {
        if (Build.VERSION.SDK_INT >= 29) {
            val r = c.boundingRectTop
            if (r.width() > 0 && r.height() > 0) return r
        }
        return c.boundingRects.minByOrNull { it.top }
    }

    private fun screenW() = resources.displayMetrics.widthPixels

    // -- style (colours, accent, corners, clock) -----------------------------

    private fun applyStyle() {
        val alpha = Prefs.opacity(prefs) * 255 / 100
        pillBg.setColor((alpha shl 24) or 0x0C0C0E)
        cardBg.setColor((alpha shl 24) or 0x0C0C0E)
        val r = dp(Prefs.corner(prefs)).toFloat()
        pillBg.cornerRadius = r
        cardBg.cornerRadius = r + dp(14)
        val accent = Prefs.accent(prefs)
        (dot.background as GradientDrawable).setColor(accent)
        progress.progressTintList = ColorStateList.valueOf(accent)
        val fmt = if (Prefs.clock24(prefs)) "HH:mm" else "h:mm"
        clock.format12Hour = fmt
        clock.format24Hour = fmt
        cardClock.format12Hour = fmt
        cardClock.format24Hour = fmt
    }

    private fun pushLayout() {
        if (::root.isInitialized && root.isAttachedToWindow) wm.updateViewLayout(root, params)
    }

    /** Window position + slot geometry for the current state. */
    private fun applyLayout() {
        val auto = Prefs.notchAutocenter(prefs) && notch != null
        val n = notch
        val pillWpx = dp(Prefs.pillWidth(prefs))
        effGapPx = if (auto && n != null) n.width() + dp(8) else dp(Prefs.cameraGap(prefs))
        val gapped = effGapPx > 0

        if (state == St.EXPANDED) {
            params.x = 0
            params.y = (n?.top ?: dp(Prefs.topOffset(prefs)))
        } else if (auto && n != null) {
            params.x = n.centerX() - screenW() / 2 + dp(Prefs.nudgeX(prefs))
            params.y = if (state == St.RESTING && Prefs.idleMode(prefs) == "outline") {
                n.centerY() - (n.height() + dp(4)) / 2 + dp(Prefs.nudgeY(prefs))
            } else {
                n.top + dp(Prefs.nudgeY(prefs))
            }
        } else {
            params.x = 0
            params.y = dp(Prefs.topOffset(prefs))
        }

        val slotW = if (gapped) ((pillWpx - effGapPx) / 2).coerceAtLeast(dp(20)) else WRAP_CONTENT
        (pill.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.width = if (gapped) pillWpx else WRAP_CONTENT
            pill.layoutParams = it
        }
        (leftSlot.layoutParams as LinearLayout.LayoutParams).let {
            it.width = slotW
            leftSlot.layoutParams = it
        }
        (rightSlot.layoutParams as LinearLayout.LayoutParams).let {
            it.width = if (gapped) slotW else 0
            rightSlot.layoutParams = it
        }
        (spacer.layoutParams as LinearLayout.LayoutParams).let {
            it.width = if (gapped) effGapPx else 0
            spacer.layoutParams = it
        }
        rightSlot.visibility = if (gapped) View.VISIBLE else View.GONE
        spacer.visibility = rightSlot.visibility
        leftSlot.gravity = if (gapped) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.CENTER
        if (gapped) {
            leftSlot.setPadding(dp(12), 0, dp(6), 0)
            rightSlot.setPadding(dp(6), 0, dp(12), 0)
        } else {
            leftSlot.setPadding(dp(16), 0, dp(16), 0)
        }

        // outline sized ~4dp larger than the hole
        if (n != null) {
            (outline.layoutParams as FrameLayout.LayoutParams).let {
                it.width = n.width() + dp(4)
                it.height = n.height() + dp(4)
                outline.layoutParams = it
            }
            outlineBg.cornerRadius = (n.height() + dp(4)) / 2f
        }
        outlineBg.setStroke(dp(2), 0x59FFFFFF)

        val maxW = if (gapped) (slotW - dp(18)).coerceAtLeast(dp(20)) else dp(240)
        for (t in listOf(titleText, lyricText, peekText, clock, batteryText, notifCount)) {
            t.maxWidth = maxW
        }
    }

    // -- slot placement ------------------------------------------------------

    private fun slotFor(splitRight: Boolean): LinearLayout {
        val gapped = effGapPx > 0
        val side = Prefs.contentSide(prefs)
        return when {
            !gapped -> leftSlot
            side == "left" -> leftSlot
            side == "right" -> rightSlot
            splitRight -> rightSlot
            else -> leftSlot
        }
    }

    private fun add(
        v: View,
        splitRight: Boolean,
        lp: LinearLayout.LayoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
    ) {
        slotFor(splitRight).addView(v, lp)
    }

    // -- state machine -------------------------------------------------------

    private fun computeState(): St = when {
        peekEntry != null -> St.PEEK
        MediaState.playing && MediaState.title != null -> St.COMPACT
        else -> St.RESTING
    }

    /** Recompute the resting/compact/peek state and re-lay-out. */
    private fun sync() {
        if (state == St.EXPANDED) return
        state = computeState()
        applyLayout()
        render()
        morphToContent()
        syncBattery()
        syncLyricTick()
    }

    private fun render() {
        if (state == St.EXPANDED) return
        leftSlot.removeAllViews()
        rightSlot.removeAllViews()
        card.visibility = View.GONE
        pill.visibility = View.GONE
        outline.visibility = View.GONE

        when (state) {
            St.PEEK -> {
                pill.visibility = View.VISIBLE
                val p = peekEntry
                if (p != null) {
                    if (p.icon != null) {
                        peekIcon.setImageDrawable(p.icon)
                        add(peekIcon, false, LinearLayout.LayoutParams(dp(16), dp(16)))
                    }
                    peekText.text = p.title
                    add(peekText, true)
                }
            }
            St.COMPACT -> {
                pill.visibility = View.VISIBLE
                val art = MediaState.art
                if (art != null) {
                    artView.setImageBitmap(art)
                    add(artView, false, LinearLayout.LayoutParams(dp(18), dp(18)))
                } else {
                    add(dot, false, LinearLayout.LayoutParams(dp(8), dp(8)))
                }
                add(eq, true)
            }
            St.RESTING -> when (Prefs.idleMode(prefs)) {
                "outline" -> outline.visibility = if (notch != null) View.VISIBLE else View.GONE
                "pill" -> {
                    pill.visibility = View.VISIBLE
                    renderFace()
                }
                else -> { /* hidden: nothing shown, window stays alive */ }
            }
            else -> {}
        }
        eq.setPlaying(MediaState.playing && eq.parent != null)
    }

    /** The legacy always-on pill content, only used by idle_mode = pill. */
    private fun renderFace() {
        val faces = Prefs.enabledFaces(prefs)
        if (face !in faces) {
            face = faces[0]
            prefs.edit().putString("face", face).apply()
        }
        val hasMedia = MediaState.title != null
        val notifsShown = NotifState.connected && NotifState.entries.isNotEmpty()
        when {
            face == "auto" && hasMedia -> {
                titleText.text = MediaState.title
                add(titleText, false)
                add(eq, true)
            }
            face == "status" -> {
                add(clock, false)
                add(batteryText, true)
            }
            face == "title" && hasMedia -> {
                titleText.text = MediaState.title
                add(titleText, false)
            }
            face == "lyrics" && hasMedia -> {
                add(lyricText, false)
                refreshLyric()
                Lyrics.request(
                    MediaState.artist ?: "", MediaState.title ?: "",
                    MediaState.album ?: "", MediaState.durationMs / 1000
                ) { if (face == "lyrics") refreshLyric() }
            }
            face == "clock" -> add(clock, false)
            face == "battery" -> add(batteryText, true)
            face == "notifs" && notifsShown -> {
                notifIcons.removeAllViews()
                for (e in NotifState.entries.take(4)) {
                    notifIcons.addView(
                        ImageView(this).apply { setImageDrawable(e.icon) },
                        LinearLayout.LayoutParams(dp(16), dp(16))
                    )
                }
                add(notifIcons, false)
                val extra = NotifState.entries.size - 4
                if (extra > 0) {
                    notifCount.text = "+$extra"
                    add(notifCount, true)
                }
            }
            else -> {
                add(dot, false, LinearLayout.LayoutParams(dp(6), dp(6)))
                add(clock, false)
            }
        }
    }

    private fun morphToContent() {
        val gapped = effGapPx > 0
        when {
            state == St.EXPANDED -> {}
            outline.visibility == View.VISIBLE -> {
                val n = notch
                morph((n?.width() ?: dp(40)) + dp(4), (n?.height() ?: dp(20)) + dp(4))
            }
            pill.visibility == View.VISIBLE -> {
                val wSpec =
                    if (gapped) View.MeasureSpec.makeMeasureSpec(dp(Prefs.pillWidth(prefs)), View.MeasureSpec.EXACTLY)
                    else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                pill.measure(wSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                morph(pill.measuredWidth, pill.measuredHeight)
            }
            else -> morph(0, 0)   // hidden resting: shrink into the notch
        }
    }

    private fun updateUi() {
        cardTitle.text = MediaState.title ?: "Nothing playing"
        cardArtist.text = MediaState.artist ?: ""
        playPause.setImageResource(
            if (MediaState.playing) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
        updateProgress()
        sync()
    }

    private fun updateProgress() {
        val dur = MediaState.durationMs
        if (MediaState.title != null && dur > 0) {
            progress.visibility = View.VISIBLE
            progress.max = (dur / 1000).toInt().coerceAtLeast(1)
            progress.progress = MediaState.positionSec().toInt().coerceIn(0, progress.max)
        } else {
            progress.visibility = View.GONE
        }
    }

    // -- notifications -------------------------------------------------------

    private fun onNotifsChanged() {
        rebuildNotifRows()
        if (state == St.EXPANDED) refreshInputFocus() else sync()
    }

    private fun onNotifPosted(entry: NotifState.Entry) {
        showPeek(entry)
    }

    private fun showPeek(entry: NotifState.Entry) {
        if (state == St.EXPANDED) return
        peekEntry = entry
        state = St.PEEK
        applyLayout()
        render()
        morphToContent()
        pulseRing()
        main.removeCallbacks(peekEnd)
        main.postDelayed(peekEnd, Prefs.peekSeconds(prefs) * 1000L)
    }

    private fun pulseRing() {
        ringPulse?.cancel()
        if (!Prefs.ledPulse(prefs) || notch == null) return
        outlineBg.setStroke(dp(2), Prefs.accent(prefs))
        outline.visibility = View.VISIBLE
        (outline.layoutParams as FrameLayout.LayoutParams).let {
            it.width = notch!!.width() + dp(4)
            it.height = notch!!.height() + dp(4)
            outline.layoutParams = it
        }
        ringPulse = ObjectAnimator.ofFloat(outline, "alpha", 0.2f, 1f).apply {
            duration = 300
            repeatCount = 5
            repeatMode = ValueAnimator.REVERSE
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    outline.alpha = 1f
                    if (state != St.EXPANDED) render()
                }
            })
            start()
        }
    }

    private fun rebuildNotifRows() {
        notifRows.removeAllViews()
        val entries = if (NotifState.connected) NotifState.entries else emptyList()
        val vis = if (entries.isEmpty()) View.GONE else View.VISIBLE
        notifHeader.visibility = vis
        notifRows.visibility = vis
        for (e in entries.take(6)) {
            notifRows.addView(notifRow(e), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun notifRow(e: NotifState.Entry): View {
        val title = TextView(this).apply {
            text = e.title
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 13f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        val body = TextView(this).apply {
            text = e.text
            setTextColor(0xA6FFFFFF.toInt())
            textSize = 11f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            // B4: live timer from EXTRA_SHOW_CHRONOMETER, else the plain body text
            if (e.showChrono) {
                addView(Chronometer(this@IslandService).apply {
                    // Chronometer's base is on the elapsedRealtime clock; map from wall-clock `when`
                    base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - e.whenTs)
                    if (Build.VERSION.SDK_INT >= 24) isCountDown = e.chronoCountDown
                    setTextColor(0xA6FFFFFF.toInt())
                    textSize = 11f
                    start()
                })
            } else if (e.text.isNotEmpty()) {
                addView(body)
            }
        }
        val close = TextView(this).apply {
            text = "✕"
            textSize = 13f
            setTextColor(0x66FFFFFF)
            setPadding(dp(10), dp(4), dp(2), dp(4))
            setOnClickListener { NotifState.cancel(e.key) }
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                ImageView(this@IslandService).apply { setImageDrawable(e.icon) },
                LinearLayout.LayoutParams(dp(20), dp(20))
            )
            addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply {
                leftMargin = dp(10)
            })
            addView(close)
            setOnClickListener {
                try {
                    e.intent?.send()
                } catch (_: Exception) {
                }
                collapse()
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            addView(topRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            // B1: inline reply when the notification exposes a RemoteInput action
            if (e.replyIntent != null && e.replyInputs != null) {
                addView(replyRow(e), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    topMargin = dp(6)
                })
            }
        }
    }

    private fun replyRow(e: NotifState.Entry): View {
        val input = EditText(this).apply {
            hint = "Reply…"
            textSize = 13f
            setTextColor(Color.WHITE)
            setHintTextColor(0x66FFFFFF)
            isSingleLine = true
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = GradientDrawable().apply {
                setColor(0x1FFFFFFF)
                cornerRadius = dp(16).toFloat()
            }
        }
        val send = TextView(this).apply {
            text = "Send"
            typeface = Typeface.DEFAULT_BOLD
            textSize = 13f
            setTextColor(Prefs.accent(prefs))
            setPadding(dp(12), dp(6), dp(4), dp(6))
            setOnClickListener { sendReply(e, input) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(input, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(send)
        }
    }

    private fun sendReply(e: NotifState.Entry, input: EditText) {
        val inputs = e.replyInputs ?: return
        val pi = e.replyIntent ?: return
        val key = inputs.firstOrNull { it.resultKey != null }?.resultKey ?: return
        val text = input.text?.toString().orEmpty()
        if (text.isEmpty()) return
        val results = Bundle().apply { putCharSequence(key, text) }
        val fill = Intent()
        RemoteInput.addResultsToIntent(inputs, fill, results)
        try {
            pi.send(this, 0, fill)
        } catch (_: Exception) {
        }
        input.setText("")
        collapse()
    }

    /** Overlay is normally non-focusable; a visible reply field needs a keyboard. */
    private fun setInputFocusable(on: Boolean) {
        val flags = if (on) {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        } else {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (flags != params.flags) {
            params.flags = flags
            pushLayout()
        }
    }

    private fun refreshInputFocus() {
        setInputFocusable(
            state == St.EXPANDED &&
                NotifState.connected &&
                NotifState.entries.any { it.replyIntent != null }
        )
    }

    // -- faces / gestures ----------------------------------------------------

    private fun cycle(step: Int) {
        peekEntry = null
        main.removeCallbacks(peekEnd)
        if (Prefs.haptic(prefs)) pill.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        val faces = Prefs.enabledFaces(prefs)
        val i = faces.indexOf(face).coerceAtLeast(0)
        face = faces[(i + step + faces.size) % faces.size]
        prefs.edit().putString("face", face).apply()
        applyLayout()
        render()
        morphToContent()
    }

    private fun doLongAction() {
        when (Prefs.longAction(prefs)) {
            "openapp" -> openApp()
            "dismiss" -> {
                peekEntry = null
                main.removeCallbacks(peekEnd)
                if (state == St.EXPANDED) collapse() else sync()
            }
            else -> expand()
        }
    }

    private fun openApp() {
        val pkg = MediaState.controller?.packageName
        val launch = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
        if (launch == null) {
            expand()
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
        if (state == St.EXPANDED) collapse()
    }

    // -- lyrics --------------------------------------------------------------

    private fun refreshLyric() {
        val title = MediaState.title ?: return
        val lines = Lyrics.get(MediaState.artist ?: "", title)
        lyricText.text =
            if (lines.isNullOrEmpty()) "♪ $title"
            else Lyrics.lineAt(lines, MediaState.positionSec()) ?: "…"
    }

    private fun syncLyricTick() {
        val want = state == St.RESTING && Prefs.idleMode(prefs) == "pill" &&
            face == "lyrics" && lyricText.isShown && MediaState.playing
        if (want && !lyricTicking) {
            lyricTicking = true
            main.postDelayed(lyricTick, 1000)
        } else if (!want && lyricTicking) {
            lyricTicking = false
            main.removeCallbacks(lyricTick)
        }
    }

    // -- battery -------------------------------------------------------------

    private fun syncBattery() {
        val want = state == St.EXPANDED || face == "status" || face == "battery" ||
            Prefs.battAlarm(prefs)
        if (want && batteryReceiver == null) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    intent?.let { onBattery(it) }
                }
            }
            batteryReceiver = receiver
            registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.let { onBattery(it) }
        } else if (!want && batteryReceiver != null) {
            unregisterReceiver(batteryReceiver)
            batteryReceiver = null
        }
    }

    private fun onBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
        if (level < 0) return
        val pct = level * 100 / scale.coerceAtLeast(1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val label = "$pct%"
        batteryText.text = label
        batteryText.setTextColor(if (charging) Prefs.accent(prefs) else Color.WHITE)
        cardBattery.text = label

        if (Prefs.battAlarm(prefs) && battInit) {
            val thr = Prefs.battThreshold(prefs)
            when {
                charging && !prevCharging -> alarmPeek("Charging • $pct%", false)
                charging && pct >= 100 && prevPct < 100 -> alarmPeek("Fully charged", false)
                !charging && prevPct > thr && pct <= thr ->
                    alarmPeek("Low battery • $pct%", Prefs.battVibrate(prefs))
            }
        }
        prevCharging = charging
        prevPct = pct
        battInit = true
    }

    private fun alarmPeek(title: String, vibrate: Boolean) {
        if (vibrate) vibrate()
        showPeek(NotifState.Entry("batt", null, "", title, "", null, System.currentTimeMillis()))
    }

    private fun vibrate() {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) {
                getSystemService(VibratorManager::class.java).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
            v?.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    // -- expand / collapse ---------------------------------------------------

    private fun pokeAutoCollapse() {
        main.removeCallbacks(autoCollapseRun)
        val s = Prefs.autoCollapse(prefs)
        if (state == St.EXPANDED && s > 0) main.postDelayed(autoCollapseRun, s * 1000L)
    }

    private fun expand() {
        if (state == St.EXPANDED) return
        state = St.EXPANDED
        peekEntry = null
        main.removeCallbacks(peekEnd)
        rebuildNotifRows()
        refreshInputFocus()
        updateProgress()
        pill.visibility = View.GONE
        outline.visibility = View.GONE
        card.visibility = View.VISIBLE
        applyLayout()
        pushLayout()
        syncBattery()
        syncLyricTick()
        pokeAutoCollapse()
        main.removeCallbacks(cardTick)
        main.postDelayed(cardTick, 1000)
        val width = dp(360)
        card.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        morph(width, card.measuredHeight)
    }

    private fun collapse() {
        main.removeCallbacks(autoCollapseRun)
        main.removeCallbacks(cardTick)
        state = computeState()
        refreshInputFocus()
        applyLayout()
        render()
        morphToContent()
        syncBattery()
        syncLyricTick()
    }

    private fun morph(toWidth: Int, toHeight: Int) {
        animator?.cancel()
        val fromWidth = root.width
        val fromHeight = root.height
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 250
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                params.width = fromWidth + ((toWidth - fromWidth) * f).toInt()
                params.height = fromHeight + ((toHeight - fromHeight) * f).toInt()
                if (root.isAttachedToWindow) wm.updateViewLayout(root, params)
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    // back to wrapping so the window tracks content changes between morphs
                    if (state != St.EXPANDED) params.width = WRAP_CONTENT
                    params.height = WRAP_CONTENT
                    if (root.isAttachedToWindow) wm.updateViewLayout(root, params)
                }
            })
            start()
        }
    }

    private companion object {
        const val CHANNEL = "island"
    }
}
