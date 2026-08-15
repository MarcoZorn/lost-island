package it.zorn.lostisland

import android.app.Notification
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MediaListener : NotificationListenerService() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var sessionManager: MediaSessionManager
    private lateinit var component: ComponentName
    private var active: MediaController? = null
    private val notifs = LinkedHashMap<String, NotifState.Entry>()

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
        NotifState.connected = true
        NotifState.canceler = { key ->
            try {
                cancelNotification(key)
            } catch (_: Exception) {
            }
        }
        notifs.clear()
        try {
            activeNotifications?.forEach { sbn -> if (wanted(sbn)) notifs[sbn.key] = toEntry(sbn) }
        } catch (_: Exception) {
        }
        pushNotifs()
    }

    override fun onListenerDisconnected() {
        if (::sessionManager.isInitialized) {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        }
        setActive(null)
        NotifState.connected = false
        NotifState.canceler = null
        notifs.clear()
        pushNotifs()
    }

    // -- notifications -------------------------------------------------------

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!wanted(sbn)) {
            if (notifs.remove(sbn.key) != null) pushNotifs()
            return
        }
        val entry = toEntry(sbn)
        notifs[sbn.key] = entry
        pushNotifs()
        main.post { NotifState.onPosted?.invoke(entry) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (notifs.remove(sbn.key) != null) pushNotifs()
    }

    private fun wanted(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        if (sbn.packageName == packageName) return false
        if (sbn.isOngoing) return false
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        // media notifications already surface through the session path
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)) return false
        if ((n.extras.getString("android.template") ?: "").contains("MediaStyle")) return false
        return true
    }

    private fun toEntry(sbn: StatusBarNotification): NotifState.Entry {
        val n = sbn.notification
        val icon = try {
            packageManager.getApplicationIcon(sbn.packageName)
        } catch (_: Exception) {
            try {
                n.smallIcon?.loadDrawable(this)
            } catch (_: Exception) {
                null
            }
        }
        val appName = try {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        } catch (_: Exception) {
            sbn.packageName
        }
        return NotifState.Entry(
            key = sbn.key,
            icon = icon,
            appName = appName,
            title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: appName,
            text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "",
            intent = n.contentIntent,
            whenTs = if (n.`when` > 0) n.`when` else sbn.postTime
        )
    }

    private fun pushNotifs() {
        NotifState.entries = notifs.values.sortedByDescending { it.whenTs }
        main.post { NotifState.onChanged?.invoke() }
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
        val state = controller?.playbackState
        MediaState.controller = controller
        MediaState.title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        MediaState.artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        MediaState.album = metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM)
        MediaState.durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        MediaState.playing = state?.state == PlaybackState.STATE_PLAYING
        MediaState.basePosMs = state?.position ?: 0L
        MediaState.baseTimeMs = state?.lastPositionUpdateTime?.takeIf { it > 0 }
            ?: SystemClock.elapsedRealtime()
        MediaState.speed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f
        main.post { MediaState.onChanged?.invoke() }
    }
}
