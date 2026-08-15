package it.zorn.lostisland

import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Drawable
import android.media.session.MediaController
import android.os.SystemClock

/**
 * Bridge between MediaListener (producer) and IslandService (consumer).
 * Single consumer in the same process, so a plain singleton with one
 * callback is all the plumbing this needs.
 */
object MediaState {
    @Volatile var controller: MediaController? = null
    @Volatile var title: String? = null
    @Volatile var artist: String? = null
    @Volatile var album: String? = null
    @Volatile var durationMs: Long = 0
    @Volatile var playing: Boolean = false

    // last reported position, extrapolated locally while playing
    @Volatile var basePosMs: Long = 0
    @Volatile var baseTimeMs: Long = 0   // elapsedRealtime base
    @Volatile var speed: Float = 1f

    /** Set by IslandService, invoked on the main thread. */
    var onChanged: (() -> Unit)? = null

    fun positionSec(): Double {
        if (!playing) return basePosMs / 1000.0
        val drift = (SystemClock.elapsedRealtime() - baseTimeMs) * speed
        return (basePosMs + drift) / 1000.0
    }
}

/**
 * Same bridge pattern for status-bar notifications: MediaListener produces,
 * IslandService consumes, all callbacks invoked on the main thread.
 */
object NotifState {
    class Entry(
        val key: String,
        val icon: Drawable?,
        val appName: String,
        val title: String,
        val text: String,
        val intent: PendingIntent?,
        val whenTs: Long
    )

    @Volatile var connected = false
    @Volatile var entries: List<Entry> = emptyList()

    /** Set by MediaListener while connected; dismisses from the status bar too. */
    var canceler: ((String) -> Unit)? = null

    /** Set by IslandService. */
    var onChanged: (() -> Unit)? = null
    var onPosted: ((Entry) -> Unit)? = null

    fun cancel(key: String) {
        canceler?.invoke(key)
    }
}

internal fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()
