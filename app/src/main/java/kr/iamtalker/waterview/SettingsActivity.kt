package kr.iamtalker.waterview

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/** Persistent reading preferences, shared by comic, text and PDF viewers. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<MaterialToolbar>(R.id.settingsToolbar).setNavigationOnClickListener { finish() }

        val groundRow = findViewById<View>(R.id.groundRow)
        val groundValue = findViewById<TextView>(R.id.groundValue)
        val groundNames = resources.getStringArray(R.array.ground_choices)
        fun refreshGround() {
            groundValue.text = groundNames[Settings.groundIndex(this)]
        }
        groundRow.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.ground_title)
                .setSingleChoiceItems(R.array.ground_choices, Settings.groundIndex(this)) { dialog, which ->
                    Settings.setGroundIndex(this, which)
                    refreshGround()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        refreshGround()

        val themeRow = findViewById<View>(R.id.themeRow)
        val themeValue = findViewById<TextView>(R.id.themeValue)
        fun refreshTheme() {
            themeValue.setText(if (Settings.appNight(this)) R.string.theme_night else R.string.theme_day)
        }
        themeRow.setOnClickListener {
            val checked = if (Settings.appNight(this)) 1 else 0
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.theme_title)
                .setSingleChoiceItems(R.array.theme_choices, checked) { dialog, which ->
                    val night = which == 1
                    Settings.setAppNight(this, night)
                    androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                        if (night) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                        else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                    )
                    refreshTheme()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        refreshTheme()

        val tapEdgeRow = findViewById<View>(R.id.tapEdgeRow)
        val tapEdgeValue = findViewById<TextView>(R.id.tapEdgeValue)
        fun refreshTapEdge() {
            tapEdgeValue.text = getString(R.string.tap_edge_percent, Settings.tapEdgePercent(this))
        }
        tapEdgeRow.setOnClickListener {
            // Dragged to a value, with the percent shown as it moves; saved when the reader says OK
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val pad = (24 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, 0)
            }
            val label = TextView(this).apply {
                textSize = 16f
                gravity = android.view.Gravity.CENTER
            }
            val bar = SeekBar(this).apply {
                max = Settings.TAP_EDGE_MAX - Settings.TAP_EDGE_MIN
                progress = Settings.tapEdgePercent(this@SettingsActivity) - Settings.TAP_EDGE_MIN
            }
            fun shown(value: Int) { label.text = getString(R.string.tap_edge_percent, value) }
            shown(Settings.tapEdgePercent(this))
            bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) = shown(Settings.TAP_EDGE_MIN + p)
                override fun onStartTrackingTouch(s: SeekBar) = Unit
                override fun onStopTrackingTouch(s: SeekBar) = Unit
            })
            box.addView(label)
            box.addView(bar)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.tap_edge_title)
                .setView(box)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    Settings.setTapEdgePercent(this, Settings.TAP_EDGE_MIN + bar.progress)
                    refreshTapEdge()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        refreshTapEdge()

        val direction = findViewById<View>(R.id.mangaDirectionRow)
        val directionValue = findViewById<TextView>(R.id.mangaDirectionValue)
        fun refreshDirection() {
            directionValue.setText(
                if (Settings.mangaRightToLeft(this)) R.string.manga_direction_japanese
                else R.string.manga_direction_left_to_right
            )
        }
        direction.setOnClickListener {
            val checked = if (Settings.mangaRightToLeft(this)) 1 else 0
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.manga_direction_title)
                .setSingleChoiceItems(R.array.manga_direction_choices, checked) { dialog, which ->
                    Settings.setMangaRightToLeft(this, which == 1)
                    refreshDirection()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        refreshDirection()

        findViewById<MaterialSwitch>(R.id.splitWidePagesSwitch).apply {
            isChecked = Settings.splitWidePages(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked -> Settings.setSplitWidePages(this@SettingsActivity, checked) }
        }

        val textModeRow = findViewById<View>(R.id.textReadingModeRow)
        val textModeValue = findViewById<TextView>(R.id.textReadingModeValue)
        fun refreshTextMode() {
            textModeValue.setText(
                if (Settings.textHorizontalPages(this)) R.string.text_reading_mode_horizontal
                else R.string.text_reading_mode_scroll
            )
        }
        textModeRow.setOnClickListener {
            val checked = if (Settings.textHorizontalPages(this)) 1 else 0
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.text_reading_mode)
                .setSingleChoiceItems(R.array.text_reading_mode_choices, checked) { dialog, which ->
                    Settings.setTextHorizontalPages(this, which == 1)
                    refreshTextMode()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        refreshTextMode()

        // The size of plain text, which the text screen reads back each time it is shown
        val textSizeRow = findViewById<View>(R.id.textSizeRow)
        val textSizeValue = findViewById<TextView>(R.id.textSizeValue)
        val readerPrefs = getSharedPreferences("text_reader", MODE_PRIVATE)
        val sizes = intArrayOf(14, 16, 18, 20, 22, 24, 28)
        fun refreshTextSize() {
            textSizeValue.text = readerPrefs.getFloat("size_sp", 18f).toInt().toString()
        }
        textSizeRow.setOnClickListener {
            val current = readerPrefs.getFloat("size_sp", 18f).toInt()
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.text_size)
                .setSingleChoiceItems(sizes.map { "$it" }.toTypedArray(), sizes.indexOf(current).coerceAtLeast(0)) { dialog, which ->
                    readerPrefs.edit().putFloat("size_sp", sizes[which].toFloat()).apply()
                    refreshTextSize()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        refreshTextSize()

        // The diagnostic log of the text screen: shown here, or cleared, and nowhere else
        findViewById<View>(R.id.diagRow).setOnClickListener {
            val full = Diag.read(this)
            val log = full.ifBlank { getString(R.string.diagnostics_empty) }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.diagnostics_title)
                .setMessage(log.takeLast(6000))
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.diagnostics_clear) { _, _ -> Diag.clear(this) }
                .setNegativeButton(R.string.diagnostics_copy) { _, _ ->
                    // The whole log, not only the part shown, so it can be pasted somewhere to read
                    val clip = getSystemService(android.content.ClipboardManager::class.java)
                    clip.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.diagnostics_title), full))
                    android.widget.Toast.makeText(this, R.string.diagnostics_copied, android.widget.Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        val pdfModeRow = findViewById<View>(R.id.pdfReadingModeRow)
        val pdfModeValue = findViewById<TextView>(R.id.pdfReadingModeValue)
        fun refreshPdfMode() {
            pdfModeValue.setText(
                if (Settings.pdfHorizontalPages(this)) R.string.pdf_reading_mode_horizontal
                else R.string.pdf_reading_mode_scroll
            )
        }
        pdfModeRow.setOnClickListener {
            val checked = if (Settings.pdfHorizontalPages(this)) 1 else 0
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.pdf_reading_mode)
                .setSingleChoiceItems(R.array.pdf_reading_mode_choices, checked) { dialog, which ->
                    Settings.setPdfHorizontalPages(this, which == 1)
                    refreshPdfMode()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        refreshPdfMode()
        findViewById<MaterialSwitch>(R.id.pdfSplitWidePagesSwitch).apply {
            isChecked = Settings.pdfSplitWidePages(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked -> Settings.setPdfSplitWidePages(this@SettingsActivity, checked) }
        }
    }
}
