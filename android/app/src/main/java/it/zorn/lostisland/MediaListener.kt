package it.zorn.lostisland

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService

class MediaListener : NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var sessionManager: MediaSessionManager
    private lateinit var component: ComponentName
    private var active: MediaController? = null

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { onSessions(it) }

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() = refresh()
    }

    override fun onListenerConnected() {
        sessionManager = getSystemService(MediaSessionManager::class.java)
        component = ComponentName(this, MediaListener::class.java)
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, component, main)
            onSessions(sessionManager.getActiveSessions(component))
        } catch (_: SecurityException) {
            // listener access not granted yet; reconnect after the user enables it
        }
    }

    override fun onListenerDisconnected() {
        if (::sessionManager.isInitialized) {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        }
        setActive(null)
    }

    private fun refresh() {
        try {
            onSessions(sessionManager.getActiveSessions(component))
        } catch (_: SecurityException) {
        }
    }

    private fun onSessions(sessions: List<MediaController>?) {
        // first session is the most relevant one per MediaSessionManager ordering
        setActive(sessions?.firstOrNull())
    }

    private fun setActive(controller: MediaController?) {
        if (active?.sessionToken == controller?.sessionToken) {
            publish()
            return
        }
        active?.unregisterCallback(callback)
        active = controller
        controller?.registerCallback(callback, main)
        publish()
    }

    private fun publish() {
        val controller = active
        val metadata = controller?.metadata
        MediaState.controller = controller
        MediaState.title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        MediaState.artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        MediaState.playing = controller?.playbackState?.state == PlaybackState.STATE_PLAYING
        main.post { MediaState.onChanged?.invoke() }
    }
}
