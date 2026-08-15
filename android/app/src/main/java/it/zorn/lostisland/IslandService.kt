package it.zorn.lostisland

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextClock
import android.widget.TextView
import kotlin.math.abs

class IslandService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var pill: LinearLayout
    private lateinit var card: LinearLayout

    // pill: leftSlot | spacer (camera gap) | rightSlot; capsule bg spans all three
    private lateinit var leftSlot: LinearLayout
    private lateinit var rightSlot: LinearLayout
    private lateinit var spacer: View

    // pill faces
    private lateinit var clock: TextClock
    private lateinit var titleText: TextView
    private lateinit var lyricText: TextView
    private lateinit var batteryText: TextView
    private lateinit var eq: EqBars
    private lateinit var dot: View
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

    private val main = Handler(Looper.getMainLooper())
    private var face = "auto"
    private var expanded = false
    private var animator: ValueAnimator? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var lyricTicking = false
    private var peekEntry: NotifState.Entry? = null

    private val lyricTick = object : Runnable {
        override fun run() {
            refreshLyric()
            main.postDelayed(this, 1000)
        }
    }

    private val cardTick = object : Runnable {
        override fun run() {
            if (!expanded) return
            updateProgress()
            main.postDelayed(this, 1000)
        }
    }

    private val peekEnd = Runnable {
        peekEntry = null
        if (!expanded) render()
    }

    private val autoCollapseRun = Runnable { if (expanded) collapse() }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        face = Prefs.face(prefs)
        applyPrefs()
        render()
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
            // sit over the punch-hole like the real thing
            if (Build.VERSION.SDK_INT >= 30) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(root, params)
        applyPrefs()
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
                if (Prefs.tapOpensCard(prefs)) expand() else cycle(1)
                return true
            }
            override fun onLongPress(e: MotionEvent) = expand()
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) > abs(vy) && abs(vx) > 400) {
                    cycle(if (vx < 0) 1 else -1)
                    return true
                }
                return false
            }
        })
        pill.setOnTouchListener { _, event -> gestures.onTouchEvent(event) }

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
            setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE && expanded) collapse()
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

    // -- prefs ---------------------------------------------------------------

    private fun applyPrefs() {
        val alpha = Prefs.opacity(prefs) * 255 / 100
        pillBg.setColor((alpha shl 24) or 0x0C0C0E)
        cardBg.setColor((alpha shl 24) or 0x0C0C0E)
        val accent = Prefs.accent(prefs)
        (dot.background as GradientDrawable).setColor(accent)
        progress.progressTintList = ColorStateList.valueOf(accent)
        val fmt = if (Prefs.clock24(prefs)) "HH:mm" else "h:mm"
        clock.format12Hour = fmt
        clock.format24Hour = fmt
        cardClock.format12Hour = fmt
        cardClock.format24Hour = fmt
        applyLayout()
        params.y = dp(Prefs.topOffset(prefs))
        if (root.isAttachedToWindow) wm.updateViewLayout(root, params)
        pokeAutoCollapse()
    }

    /** Slot geometry: fixed pill width with a screen-centered camera gap, or legacy wrap. */
    private fun applyLayout() {
        val gap = Prefs.cameraGap(prefs)
        val gapped = gap > 0
        val pillW = dp(Prefs.pillWidth(prefs))
        val slotW = if (gapped) (pillW - dp(gap)) / 2 else WRAP_CONTENT

        val plp = pill.layoutParams
        if (plp != null) {
            plp.width = if (gapped) pillW else WRAP_CONTENT
            pill.layoutParams = plp
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
            it.width = if (gapped) dp(gap) else 0
            spacer.layoutParams = it
        }
        rightSlot.visibility = if (gapped) View.VISIBLE else View.GONE
        spacer.visibility = rightSlot.visibility
        leftSlot.gravity =
            if (gapped) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.CENTER
        if (gapped) {
            leftSlot.setPadding(dp(12), 0, dp(6), 0)
            rightSlot.setPadding(dp(6), 0, dp(12), 0)
        } else {
            leftSlot.setPadding(dp(16), 0, dp(16), 0)
        }
        // content must never cross the gap: ellipsize everything to its slot
        val maxW = if (gapped) (slotW - dp(18)).coerceAtLeast(dp(20)) else dp(240)
        for (t in listOf(titleText, lyricText, peekText, clock, batteryText, notifCount)) {
            t.maxWidth = maxW
        }
    }

    // -- faces ---------------------------------------------------------------

    private fun cycle(step: Int) {
        peekEntry = null
        main.removeCallbacks(peekEnd)
        if (Prefs.haptic(prefs)) pill.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        val faces = Prefs.enabledFaces(prefs)
        val i = faces.indexOf(face).coerceAtLeast(0)
        face = faces[(i + step + faces.size) % faces.size]
        prefs.edit().putString("face", face).apply()
        render()
    }

    private fun render() {
        val faces = Prefs.enabledFaces(prefs)
        if (face !in faces) {
            face = faces[0]
            prefs.edit().putString("face", face).apply()
        }
        leftSlot.removeAllViews()
        rightSlot.removeAllViews()
        val side = Prefs.contentSide(prefs)
        val gapped = Prefs.cameraGap(prefs) > 0

        fun add(v: View, splitRight: Boolean, lp: LinearLayout.LayoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)) {
            val slot = when {
                !gapped -> leftSlot
                side == "left" -> leftSlot
                side == "right" -> rightSlot
                splitRight -> rightSlot
                else -> leftSlot
            }
            slot.addView(v, lp)
        }

        val hasMedia = MediaState.title != null
        val peek = peekEntry
        val notifsShown = NotifState.connected && NotifState.entries.isNotEmpty()
        when {
            peek != null && !expanded -> {
                peekIcon.setImageDrawable(peek.icon)
                peekText.text = peek.title
                add(peekIcon, false, LinearLayout.LayoutParams(dp(16), dp(16)))
                add(peekText, true)
            }
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
                // nothing to show for this face: idle dot + clock
                add(dot, false, LinearLayout.LayoutParams(dp(6), dp(6)))
                add(clock, false)
            }
        }
        eq.setPlaying(MediaState.playing && eq.parent != null)
        syncBattery()
        syncLyricTick()
    }

    private fun updateUi() {
        cardTitle.text = MediaState.title ?: "Nothing playing"
        cardArtist.text = MediaState.artist ?: ""
        playPause.setImageResource(
            if (MediaState.playing) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
        updateProgress()
        render()
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
        if (!expanded) render()
    }

    private fun onNotifPosted(entry: NotifState.Entry) {
        if (expanded) return
        peekEntry = entry
        main.removeCallbacks(peekEnd)
        main.postDelayed(peekEnd, 2500)
        render()
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
            if (e.text.isNotEmpty()) addView(body)
        }
        val close = TextView(this).apply {
            text = "✕"
            textSize = 13f
            setTextColor(0x66FFFFFF)
            setPadding(dp(10), dp(4), dp(2), dp(4))
            setOnClickListener { NotifState.cancel(e.key) }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
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
        val want = face == "lyrics" && lyricText.isShown && MediaState.playing
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
        val want = expanded || face == "status" || face == "battery"
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
    }

    // -- expand / collapse ---------------------------------------------------

    private fun pokeAutoCollapse() {
        main.removeCallbacks(autoCollapseRun)
        val s = Prefs.autoCollapse(prefs)
        if (expanded && s > 0) main.postDelayed(autoCollapseRun, s * 1000L)
    }

    private fun expand() {
        if (expanded) return
        expanded = true
        peekEntry = null
        main.removeCallbacks(peekEnd)
        rebuildNotifRows()
        updateProgress()
        pill.visibility = View.GONE
        card.visibility = View.VISIBLE
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
        expanded = false
        main.removeCallbacks(autoCollapseRun)
        main.removeCallbacks(cardTick)
        card.visibility = View.GONE
        pill.visibility = View.VISIBLE
        syncBattery()
        syncLyricTick()
        val gapped = Prefs.cameraGap(prefs) > 0
        val wSpec =
            if (gapped) View.MeasureSpec.makeMeasureSpec(dp(Prefs.pillWidth(prefs)), View.MeasureSpec.EXACTLY)
            else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        pill.measure(wSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        morph(pill.measuredWidth, pill.measuredHeight)
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
                    if (!expanded) params.width = WRAP_CONTENT
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
