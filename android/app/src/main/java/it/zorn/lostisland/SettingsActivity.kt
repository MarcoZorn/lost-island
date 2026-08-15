package it.zorn.lostisland

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs.get(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(48), dp(24), dp(32))
        }

        root.add(text("Settings", 28f, Color.WHITE, bold = true))

        // -- Layout ----------------------------------------------------------
        root.section("Layout")
        root.sliderRow("Pill width", 140, 340, Prefs.pillWidth(prefs), "pill_width") { "$it dp" }
        root.sliderRow("Camera gap", 0, 80, Prefs.cameraGap(prefs), "camera_gap") {
            if (it == 0) "off — single centered slot" else "$it dp"
        }
        root.add(text("Content side", 13f, DIM), top = dp(12))
        root.add(sideRadios(), top = dp(2))
        root.sliderRow("Top offset", 0, 60, Prefs.topOffset(prefs), "offset") { "$it dp" }
        root.sliderRow("Opacity", 30, 100, Prefs.opacity(prefs), "opacity") { "$it%" }

        // -- Look ------------------------------------------------------------
        root.section("Look")
        root.add(text("Accent", 13f, DIM), top = dp(12))
        root.add(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(accentRow())
        }, top = dp(8))
        root.add(boolSwitch("24-hour clock", "clock_24", false), top = dp(14))

        // -- Faces -----------------------------------------------------------
        root.section("Faces")
        root.add(text("Faces in the tap / swipe cycle.", 13f, DIM), top = dp(4))
        for (face in Prefs.FACES) root.add(faceSwitch(face), top = dp(6))

        // -- Behavior --------------------------------------------------------
        root.section("Behavior")
        root.add(text("Tap on the pill", 13f, DIM), top = dp(12))
        val cycle = radio("Cycle faces")
        val open = radio("Open the card")
        val group = RadioGroup(this).apply {
            addView(cycle)
            addView(open)
        }
        (if (Prefs.tapOpensCard(prefs)) open else cycle).isChecked = true
        group.setOnCheckedChangeListener { _, checkedId ->
            prefs.edit().putBoolean("tap_card", checkedId == open.id).apply()
        }
        root.add(group, top = dp(2))
        root.add(text("Long-press always opens the card.", 12f, DIM), top = dp(2))
        root.add(boolSwitch("Haptic tick on face change", "haptic", true), top = dp(12))
        root.sliderRow("Auto-collapse card", 0, 15, Prefs.autoCollapse(prefs), "auto_collapse") {
            if (it == 0) "never" else "$it s"
        }

        // -- Camera notch ----------------------------------------------------
        root.section("Camera notch")
        root.add(text("Resting appearance", 13f, DIM), top = dp(12))
        root.add(stringRadios(
            Prefs.idleMode(prefs),
            listOf("hidden" to "Hidden", "outline" to "Outline", "pill" to "Pill"),
            horizontal = true,
            key = "idle_mode"
        ), top = dp(2))

        // nudges only bite when auto-centering on the cutout, so gate them on that switch
        val nudgeBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nudgeBox.sliderRow("Nudge X", -40, 40, Prefs.nudgeX(prefs), "notch_nudge_x") { "$it dp" }
        nudgeBox.sliderRow("Nudge Y", -40, 40, Prefs.nudgeY(prefs), "notch_nudge_y") { "$it dp" }
        nudgeBox.visibility = if (Prefs.notchAutocenter(prefs)) View.VISIBLE else View.GONE
        root.add(SwitchCompat(this).apply {
            text = "Auto-center on the camera cutout"
            textSize = 15f
            setTextColor(Color.WHITE)
            isChecked = Prefs.notchAutocenter(prefs)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("notch_autocenter", checked).apply()
                nudgeBox.visibility = if (checked) View.VISIBLE else View.GONE
            }
        }, top = dp(14))
        root.add(nudgeBox)
        root.sliderRow("Corner radius", 8, 40, Prefs.corner(prefs), "corner") { "$it dp" }
        root.sliderRow("Peek duration", 1, 8, Prefs.peekSeconds(prefs), "peek_seconds") { "$it s" }

        // -- Notifications ---------------------------------------------------
        root.section("Notifications")
        root.add(boolSwitch("Pulse the ring on a new notification", "led_pulse", true), top = dp(12))
        root.add(boolSwitch("Battery alarm", "batt_alarm", false), top = dp(14))
        root.sliderRow("Low-battery threshold", 5, 50, Prefs.battThreshold(prefs), "batt_threshold") { "$it%" }
        root.add(boolSwitch("Vibrate on low battery", "batt_vibrate", true), top = dp(12))
        root.add(text("Long-press the island", 13f, DIM), top = dp(16))
        root.add(stringRadios(
            Prefs.longAction(prefs),
            listOf("expand" to "Open card", "openapp" to "Open app", "dismiss" to "Dismiss"),
            horizontal = false,
            key = "long_action"
        ), top = dp(2))

        // -- Allowed apps ----------------------------------------------------
        root.section("Allowed apps")
        root.add(text("Which apps may peek into the island. Empty = all apps allowed.", 13f, DIM), top = dp(4))
        root.add(text("Choose apps →", 15f, Color.WHITE).apply {
            setPadding(0, dp(10), 0, dp(10))
            setOnClickListener {
                startActivity(Intent(this@SettingsActivity, AllowedAppsActivity::class.java))
            }
        }, top = dp(6))

        // -- About -----------------------------------------------------------
        root.section("About")
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        root.add(text("Lost Island $version", 15f, Color.WHITE), top = dp(12))
        root.add(text("github.com/MarcoZorn/lost-island", 13f, DIM), top = dp(4))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF0C0C0E.toInt())
            addView(root, MATCH_PARENT, WRAP_CONTENT)
        })
    }

    private fun sideRadios(): RadioGroup {
        val left = radio("Left")
        val right = radio("Right")
        val split = radio("Split")
        val group = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            addView(left)
            addView(right)
            addView(split)
        }
        when (Prefs.contentSide(prefs)) {
            "left" -> left
            "right" -> right
            else -> split
        }.isChecked = true
        group.setOnCheckedChangeListener { _, checkedId ->
            val value = when (checkedId) {
                left.id -> "left"
                right.id -> "right"
                else -> "split"
            }
            prefs.edit().putString("content_side", value).apply()
        }
        return group
    }

    private fun stringRadios(
        current: String,
        options: List<Pair<String, String>>,
        horizontal: Boolean,
        key: String
    ): RadioGroup {
        val group = RadioGroup(this).apply {
            if (horizontal) orientation = RadioGroup.HORIZONTAL
        }
        val buttons = options.map { (value, label) ->
            val rb = radio(label)
            group.addView(rb)
            value to rb
        }
        (buttons.firstOrNull { it.first == current }?.second ?: buttons.first().second).isChecked = true
        group.setOnCheckedChangeListener { _, checkedId ->
            val picked = buttons.firstOrNull { it.second.id == checkedId }?.first
                ?: return@setOnCheckedChangeListener
            prefs.edit().putString(key, picked).apply()
        }
        return group
    }

    private fun accentRow(): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val circles = ArrayList<View>()
        fun refresh() {
            val selected = Prefs.accent(prefs)
            circles.forEachIndexed { i, v ->
                (v.background as GradientDrawable).setStroke(
                    dp(3),
                    if (Prefs.ACCENTS[i] == selected) Color.WHITE else Color.TRANSPARENT
                )
            }
        }
        for (color in Prefs.ACCENTS) {
            val circle = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
                setOnClickListener {
                    prefs.edit().putInt("accent", color).apply()
                    refresh()
                }
            }
            circles.add(circle)
            row.addView(circle, LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                rightMargin = dp(10)
            })
        }
        refresh()
        return row
    }

    private fun faceSwitch(face: String) = SwitchCompat(this).apply {
        text = face.replaceFirstChar { it.uppercase() }
        textSize = 15f
        setTextColor(Color.WHITE)
        isChecked = face in Prefs.enabledFaces(prefs)
        setOnCheckedChangeListener { _, checked ->
            val on = Prefs.enabledFaces(prefs).toMutableSet()
            if (checked) on.add(face) else on.remove(face)
            if (on.isEmpty()) {
                isChecked = true
                return@setOnCheckedChangeListener
            }
            prefs.edit().putStringSet("faces", on).apply()
        }
    }

    private fun boolSwitch(label: String, key: String, default: Boolean) = SwitchCompat(this).apply {
        text = label
        textSize = 15f
        setTextColor(Color.WHITE)
        isChecked = prefs.getBoolean(key, default)
        setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(key, checked).apply()
        }
    }

    private fun radio(label: String) = RadioButton(this).apply {
        id = View.generateViewId()
        text = label
        textSize = 15f
        setTextColor(Color.WHITE)
    }

    private fun LinearLayout.section(title: String) {
        add(text(title, 17f, Color.WHITE, bold = true), top = dp(30))
    }

    private fun LinearLayout.sliderRow(label: String, minV: Int, maxV: Int, value: Int, key: String, fmt: (Int) -> String) {
        val lab = text("$label · ${fmt(value)}", 13f, DIM)
        add(lab, top = dp(12))
        add(SeekBar(this@SettingsActivity).apply {
            min = minV
            max = maxV
            progress = value
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                    lab.text = "$label · ${fmt(v)}"
                    if (fromUser) prefs.edit().putInt(key, v).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }, top = dp(4))
    }

    private fun text(label: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = label
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun LinearLayout.add(v: View, top: Int = 0) {
        addView(v, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = top })
    }

    private companion object {
        val DIM = 0x80FFFFFF.toInt()
    }
}
