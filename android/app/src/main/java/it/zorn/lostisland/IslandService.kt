package it.zorn.lostisland

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.TextUtils
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

class IslandService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var root: FrameLayout
    private lateinit var pill: LinearLayout
    private lateinit var card: LinearLayout
    private lateinit var mediaText: TextView
    private lateinit var dot: View
    private lateinit var cardTitle: TextView
    private lateinit var cardArtist: TextView
    private lateinit var playPause: ImageButton

    private var expanded = false
    private var animator: ValueAnimator? = null

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        wm = getSystemService(WindowManager::class.java)
        buildViews()
        params = WindowManager.LayoutParams(
            WRAP_CONTENT,
            WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(8)
        }
        wm.addView(root, params)
        MediaState.onChanged = ::updateUi
        updateUi()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        MediaState.onChanged = null
        animator?.cancel()
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

    private fun buildViews() {
        val clock = TextClock(this).apply {
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            textSize = 14f
        }
        mediaText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(280)
            visibility = View.GONE
        }
        dot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ACCENT)
            }
            visibility = View.GONE
        }
        pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = islandBackground(dp(20).toFloat())
            addView(clock)
            addView(mediaText, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = dp(10)
            })
            addView(dot, LinearLayout.LayoutParams(dp(6), dp(6)).apply {
                marginStart = dp(8)
            })
            setOnClickListener { if (!expanded) expand() }
        }

        val header = TextView(this).apply {
            text = "NOW PLAYING"
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(0x66FFFFFF)
            setPadding(0, 0, 0, dp(6))
            setOnClickListener { collapse() }
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
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(20), dp(10))
            background = islandBackground(dp(34).toFloat())
            visibility = View.GONE
            addView(header)
            addView(cardTitle)
            addView(cardArtist)
            addView(controls, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                topMargin = dp(8)
            })
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

    private fun expand() {
        expanded = true
        pill.visibility = View.GONE
        card.visibility = View.VISIBLE
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

    private fun updateUi() {
        val title = MediaState.title
        val artist = MediaState.artist
        val active = title != null
        mediaText.text = if (artist.isNullOrEmpty()) title else "$artist — $title"
        mediaText.visibility = if (active) View.VISIBLE else View.GONE
        dot.visibility = if (active) View.VISIBLE else View.GONE
        cardTitle.text = title ?: "Nothing playing"
        cardArtist.text = artist ?: ""
        playPause.setImageResource(
            if (MediaState.playing) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        )
    }

    private companion object {
        const val CHANNEL = "island"
        val ACCENT = 0xFFFF9F0A.toInt()
    }
}
