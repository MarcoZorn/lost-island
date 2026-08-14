package it.zorn.lostisland

import android.content.Context
import android.media.session.MediaController

/**
 * Bridge between MediaListener (producer) and IslandService (consumer).
 * Single consumer in the same process, so a plain singleton with one
 * callback is all the plumbing this needs.
 */
object MediaState {
    @Volatile var controller: MediaController? = null
    @Volatile var title: String? = null
    @Volatile var artist: String? = null
    @Volatile var playing: Boolean = false

    /** Set by IslandService, invoked on the main thread. */
    var onChanged: (() -> Unit)? = null
}

internal fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt()
