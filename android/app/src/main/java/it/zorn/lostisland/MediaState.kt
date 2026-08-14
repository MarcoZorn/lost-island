package it.zorn.lostisland

import android.content.Context
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

internal fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()
