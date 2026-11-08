package kr.iamtalker.waterview

import android.os.Bundle
import android.view.View
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
            val choices = Settings.TAP_EDGE_CHOICES
            val checked = choices.indexOf(Settings.tapEdgePercent(this)).coerceAtLeast(0)
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.tap_edge_title)
                .setSingleChoiceItems(R.array.tap_edge_choices, checked) { dialog, which ->
                    Settings.setTapEdgePercent(this, choices[which])
                    refreshTapEdge()
                    dialog.dismiss()
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
