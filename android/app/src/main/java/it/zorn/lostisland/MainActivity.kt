package it.zorn.lostisland

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat

class MainActivity : AppCompatActivity() {

    private lateinit var overlayStatus: TextView
    private lateinit var listenerStatus: TextView
    private lateinit var restrictedHelp: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0C0C0E.toInt())
            setPadding(dp(24), dp(48), dp(24), dp(32))
        }

        root.add(text("Lost Island", 28f, Color.WHITE, bold = true))
        root.add(
            text(
                "A floating island pinned to the top of the screen: clock, current " +
                    "track and media controls. It needs two permissions to work.",
                15f, 0xB3FFFFFF.toInt()
            ),
            top = dp(10)
        )

        overlayStatus = text("", 13f, Color.WHITE, bold = true)
        root.add(text("1 · Display over other apps", 16f, Color.WHITE, bold = true), top = dp(32))
        root.add(
            text("The island is drawn as an overlay window above every other app.", 13f, 0x80FFFFFF.toInt()),
            top = dp(4)
        )
        root.add(overlayStatus, top = dp(8))
        root.add(
            button("Open overlay settings") {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            },
            top = dp(10)
        )

        listenerStatus = text("", 13f, Color.WHITE, bold = true)
        root.add(text("2 · Notification access", 16f, Color.WHITE, bold = true), top = dp(28))
        root.add(
            text(
                "Android only hands out active media sessions (track, artist, play " +
                    "state) to notification listeners. Nothing is read beyond that.",
                13f, 0x80FFFFFF.toInt()
            ),
            top = dp(4)
        )
        root.add(listenerStatus, top = dp(8))

        restrictedHelp = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        restrictedHelp.add(
            text(
                "Toggle greyed out? Android 13+ locks this setting for sideloaded " +
                    "apps (“restricted settings”). Unlock it once:",
                13f, 0xB3FFFFFF.toInt()
            ),
            top = dp(10)
        )
        restrictedHelp.add(
            text(
                "1 · Open App info below\n" +
                    "2 · Tap the ⋮ menu in the top-right corner\n" +
                    "3 · Choose “Allow restricted settings” and confirm\n" +
                    "4 · Come back and enable Lost Island in Notification access",
                13f, 0x80FFFFFF.toInt()
            ),
            top = dp(6)
        )
        restrictedHelp.add(
            button("Open app info") {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            },
            top = dp(10)
        )
        root.add(restrictedHelp)

        root.add(
            button("Open notification access") {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            top = dp(10)
        )

        root.add(
            button("Start island", accent = true) {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
                }
                startForegroundService(Intent(this, IslandService::class.java))
            },
            top = dp(40)
        )
        root.add(
            button("Island settings") {
                startActivity(Intent(this, SettingsActivity::class.java))
            },
            top = dp(12)
        )

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF0C0C0E.toInt())
            addView(root, MATCH_PARENT, WRAP_CONTENT)
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val overlay = Settings.canDrawOverlays(this)
        val listener = NotificationManagerCompat
            .getEnabledListenerPackages(this)
            .contains(packageName)
        overlayStatus.text = if (overlay) "Granted" else "Not granted"
        overlayStatus.setTextColor(if (overlay) GREEN else ORANGE)
        listenerStatus.text = if (listener) "Granted" else "Not granted"
        listenerStatus.setTextColor(if (listener) GREEN else ORANGE)
        restrictedHelp.visibility =
            if (!listener && Build.VERSION.SDK_INT >= 33) View.VISIBLE else View.GONE
    }

    private fun text(label: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = label
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun button(label: String, accent: Boolean = false, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(if (accent) Color.BLACK else Color.WHITE)
            background = GradientDrawable().apply {
                setColor(if (accent) ORANGE else 0xFF1C1C1F.toInt())
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(16), dp(13), dp(16), dp(13))
            setOnClickListener { onClick() }
        }

    private fun LinearLayout.add(v: View, top: Int = 0) {
        addView(v, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = top })
    }

    private companion object {
        val GREEN = 0xFF30D158.toInt()
        val ORANGE = 0xFFFF9F0A.toInt()
    }
}
