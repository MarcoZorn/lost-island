package it.zorn.lostisland

import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread
import org.json.JSONObject

/**
 * Synced lyrics from lrclib.net: one fetch per track, cached in memory,
 * current line looked up locally against the playback position.
 */
object Lyrics {

    private val main = Handler(Looper.getMainLooper())
    private val cache = HashMap<Pair<String, String>, List<Pair<Double, String>>>()
    private val inFlight = HashSet<Pair<String, String>>()
    private val lrcLine = Regex("""\[(\d+):(\d+(?:\.\d+)?)](.*)""")

    /** Parsed (seconds, text) lines for the track, or null when not fetched yet. */
    fun get(artist: String, title: String): List<Pair<Double, String>>? =
        cache[artist to title]

    fun lineAt(lines: List<Pair<Double, String>>, posS: Double): String? =
        lines.lastOrNull { it.first <= posS }?.second

    /** Kick off a fetch unless cached or already running; onReady runs on main. */
    fun request(artist: String, title: String, album: String, durationS: Long, onReady: () -> Unit) {
        val key = artist to title
        if (title.isEmpty() || key in cache || key in inFlight) return
        inFlight.add(key)
        thread(isDaemon = true) {
            val parsed = fetch(artist, title, album, durationS)
            main.post {
                inFlight.remove(key)
                cache[key] = parsed
                onReady()
            }
        }
    }

    private fun fetch(artist: String, title: String, album: String, durationS: Long): List<Pair<Double, String>> {
        val lrc = try {
            fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
            val url = URL(
                "https://lrclib.net/api/get?artist_name=${enc(artist)}" +
                    "&track_name=${enc(title)}&album_name=${enc(album)}" +
                    "&duration=${durationS.coerceAtLeast(0)}"
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "lost-island")
            try {
                if (conn.responseCode == 200) {
                    JSONObject(conn.inputStream.bufferedReader().readText())
                        .optString("syncedLyrics")
                } else ""
            } finally {
                conn.disconnect()
            }
        } catch (_: Exception) {
            ""
        }
        return lrc.lineSequence()
            .mapNotNull { raw ->
                lrcLine.matchEntire(raw.trim())?.let { m ->
                    val t = m.groupValues[1].toInt() * 60 + m.groupValues[2].toDouble()
                    val text = m.groupValues[3].trim()
                    if (text.isEmpty()) null else t to text
                }
            }
            .sortedBy { it.first }
            .toList()
    }
}
