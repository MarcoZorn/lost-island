package it.zorn.lostisland

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    val FACES = listOf("auto", "status", "title", "lyrics", "clock", "battery", "notifs")

    val ACCENTS = listOf(
        0xFFFF9F0A.toInt(), 0xFFFF5E3A.toInt(), 0xFFFF2D55.toInt(), 0xFFAF52DE.toInt(),
        0xFF0A84FF.toInt(), 0xFF30D158.toInt(), 0xFFFFD60A.toInt(), 0xFFF2F2F4.toInt()
    )

    fun get(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("island", Context.MODE_PRIVATE)

    fun face(p: SharedPreferences): String = p.getString("face", "auto") ?: "auto"

    /** Enabled faces in canonical cycle order, never empty. */
    fun enabledFaces(p: SharedPreferences): List<String> {
        val on = p.getStringSet("faces", null) ?: return FACES
        return FACES.filter { it in on }.ifEmpty { listOf("auto") }
    }

    fun tapOpensCard(p: SharedPreferences) = p.getBoolean("tap_card", false)

    fun opacity(p: SharedPreferences) = p.getInt("opacity", 95).coerceIn(30, 100)

    fun topOffset(p: SharedPreferences) = p.getInt("offset", 0)

    fun pillWidth(p: SharedPreferences) = p.getInt("pill_width", 210).coerceIn(140, 340)

    /** 0 = legacy single centered slot, no camera gap. */
    fun cameraGap(p: SharedPreferences) = p.getInt("camera_gap", 28).coerceIn(0, 80)

    /** "left" | "right" | "split" */
    fun contentSide(p: SharedPreferences): String = p.getString("content_side", "split") ?: "split"

    fun accent(p: SharedPreferences) = p.getInt("accent", ACCENTS[0])

    fun clock24(p: SharedPreferences) = p.getBoolean("clock_24", false)

    fun haptic(p: SharedPreferences) = p.getBoolean("haptic", true)

    /** Seconds until the card closes itself; 0 = never. */
    fun autoCollapse(p: SharedPreferences) = p.getInt("auto_collapse", 8).coerceIn(0, 15)
}
