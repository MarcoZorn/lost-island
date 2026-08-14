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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
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

    // pill faces
    private lateinit var clock: TextClock
    private lateinit var titleText: TextView
    private lateinit var lyricText: TextView
    private lateinit var batteryText: TextView
    private lateinit var eq: EqBars
    private lateinit var dot: View

    // card
    private lateinit var cardTitle: TextView
    private lateinit var cardArtist: TextView
    private lateinit var cardBattery: TextView
    private lateinit var playPause: ImageButton

    private lateinit var pillBg: GradientDrawable
    private lateinit var cardBg: GradientDrawable

    private val main = Handler(Looper.getMainLooper())
    private var face = "auto"
    private var expanded = false
    private var animator: ValueAnimator? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var lyricTicking = false

    private val lyricTick = object : Runnable {
        override fun run() {
            refreshLyric()
            main.postDelayed(this, 1000)
        }
    }

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
        updateUi()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        MediaState.onChanged = null
        animator?.cancel()
        main.removeCallbacks(lyricTick)
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
        }
        titleText = pillLabel(maxWidthDp = 220)
        lyricText = pillLabel(maxWidthDp = 240)
        batteryText = TextView(this).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 13f
            visibility = View.GONE
        }
        eq = EqBars(this).apply { visibility = View.GONE }
        dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ACCENT)
            }
            visibility = View.GONE
        }
        pillBg = islandBackground(dp(20).toFloat())
        pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = pillBg
            // 8dp between whichever children a face leaves visible
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            dividerDrawable = GradientDrawable().apply { setSize(dp(8), 0) }
            addView(titleText)
            addView(eq)
            addView(lyricText)
            addView(dot, LinearLayout.LayoutParams(dp(6), dp(6)))
            addView(clock)
            addView(batteryText)
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
        cardBg = islandBackground(dp(34).toFloat())
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(10))
            background = cardBg
            visibility = View.GONE
            addView(headerRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                bottomMargin = dp(6)
            })
            addView(cardTitle)
            addView(cardArtist)
            addView(controls, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
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

    private fun pillLabel(maxWidthDp: Int) = TextView(this).apply {
        setTextColor(Color.WHITE)
        textSize = 13f
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        maxWidth = dp(maxWidthDp)
        visibility = View.GONE
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
        params.y = dp(Prefs.topOffset(prefs))
        if (root.isAttachedToWindow) wm.updateViewLayout(root, params)
    }

    // -- faces ---------------------------------------------------------------

    private fun cycle(step: Int) {
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
        for (v in listOf(titleText, eq, lyricText, dot, clock, batteryText)) {
            v.visibility = View.GONE
        }
        val hasMedia = MediaState.title != null
        when {
            face == "auto" && hasMedia -> {
                titleText.text = MediaState.title
                titleText.visibility = View.VISIBLE
                eq.visibility = View.VISIBLE
            }
            face == "status" -> {
                clock.visibility = View.VISIBLE
                batteryText.visibility = View.VISIBLE
            }
            face == "title" && hasMedia -> {
                titleText.text = MediaState.title
                titleText.visibility = View.VISIBLE
            }
            face == "lyrics" && hasMedia -> {
                lyricText.visibility = View.VISIBLE
                refreshLyric()
                Lyrics.request(
                    MediaState.artist ?: "", MediaState.title ?: "",
                    MediaState.album ?: "", MediaState.durationMs / 1000
                ) { if (face == "lyrics") refreshLyric() }
            }
            face == "clock" -> clock.visibility = View.VISIBLE
            face == "battery" -> batteryText.visibility = View.VISIBLE
            else -> {
                // media face with nothing playing: fall back to the idle clock
                dot.visibility = View.VISIBLE
                clock.visibility = View.VISIBLE
            }
        }
        eq.setPlaying(MediaState.playing && eq.visibility == View.VISIBLE)
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
        render()
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
        batteryText.setTextColor(if (charging) ACCENT else Color.WHITE)
        cardBattery.text = label
    }

    // -- expand / collapse ---------------------------------------------------

    private fun expand() {
        if (expanded) return
        expanded = true
        pill.visibility = View.GONE
        card.visibility = View.VISIBLE
        syncBattery()
        syncLyricTick()
        val width = dp(360)
        card.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        morph(width, card.measuredHeight)
    }

    private fun collapse() {
        expanded = false
        card.visibility = View.GONE
        pill.visibility = View.VISIBLE
        syncBattery()
        syncLyricTick()
        pill.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
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
        val ACCENT = 0xFFFF9F0A.toInt()
    }
}
