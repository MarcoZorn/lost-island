package it.zorn.lostisland

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
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

        root.add(text("Pill faces", 16f, Color.WHITE, bold = true), top = dp(28))
        root.add(text("Faces in the tap / swipe cycle.", 13f, DIM), top = dp(4))
        for (face in Prefs.FACES) root.add(faceSwitch(face), top = dp(6))

        root.add(text("Tap on the pill", 16f, Color.WHITE, bold = true), top = dp(28))
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
        root.add(group, top = dp(6))
        root.add(text("Long-press always opens the card.", 12f, DIM), top = dp(4))

        root.add(text("Opacity", 16f, Color.WHITE, bold = true), top = dp(28))
        val opacityLabel = text("", 13f, DIM)
        root.add(opacityLabel, top = dp(4))
        root.add(slider(30, 100, Prefs.opacity(prefs), opacityLabel, "opacity") { "$it%" }, top = dp(4))

        root.add(text("Top offset", 16f, Color.WHITE, bold = true), top = dp(28))
        root.add(text("Nudge the pill down if it clips the camera cutout.", 13f, DIM), top = dp(4))
        val offsetLabel = text("", 13f, DIM)
        root.add(offsetLabel, top = dp(8))
        root.add(slider(0, 60, Prefs.topOffset(prefs), offsetLabel, "offset") { "$it dp" }, top = dp(4))

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF0C0C0E.toInt())
            addView(root, MATCH_PARENT, WRAP_CONTENT)
        })
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

    private fun radio(label: String) = RadioButton(this).apply {
        id = View.generateViewId()
        text = label
        textSize = 15f
        setTextColor(Color.WHITE)
    }

    private fun slider(minV: Int, maxV: Int, value: Int, label: TextView, key: String, fmt: (Int) -> String) =
        SeekBar(this).apply {
            min = minV
            max = maxV
            progress = value
            label.text = fmt(value)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, v: Int, fromUser: Boolean) {
                    label.text = fmt(v)
                    if (fromUser) prefs.edit().putInt(key, v).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
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
