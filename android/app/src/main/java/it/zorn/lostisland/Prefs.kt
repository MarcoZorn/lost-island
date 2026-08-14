package it.zorn.lostisland

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    val FACES = listOf("auto", "status", "title", "lyrics", "clock", "battery")

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
}
