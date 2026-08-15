package it.zorn.lostisland

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

/**
 * Per-app allowlist for notification peeks. An enabled app is added to the
 * "notif_apps" set; with the set empty every app is allowed (see Prefs.notifApps
 * and MediaListener.wanted).
 */
class AllowedAppsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs.get(this)
        val pm = packageManager
        val allowed = Prefs.notifApps(prefs).toMutableSet()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(32))
        }
        root.addView(TextView(this).apply {
            text = "Allowed apps"
            textSize = 28f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Only the apps you enable may peek into the island. Turn them all off and every app is allowed."
            textSize = 13f
            setTextColor(0x80FFFFFF.toInt())
            setPadding(0, dp(6), 0, dp(10))
        })

        // launchable apps only, sorted by label — keeps the list to things the user recognises
        val apps = pm.getInstalledApplications(0)
            .mapNotNull { info ->
                if (pm.getLaunchIntentForPackage(info.packageName) == null) return@mapNotNull null
                info.packageName to pm.getApplicationLabel(info).toString()
            }
            .sortedBy { it.second.lowercase() }

        for ((pkg, label) in apps) {
            root.addView(SwitchCompat(this).apply {
                text = label
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(0, dp(8), 0, dp(8))
                isChecked = pkg in allowed
                setOnCheckedChangeListener { _, checked ->
                    if (checked) allowed.add(pkg) else allowed.remove(pkg)
                    // store a fresh set; empty = all apps allowed
                    prefs.edit().putStringSet("notif_apps", HashSet(allowed)).apply()
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF0C0C0E.toInt())
            addView(root, MATCH_PARENT, WRAP_CONTENT)
        })
    }
}
