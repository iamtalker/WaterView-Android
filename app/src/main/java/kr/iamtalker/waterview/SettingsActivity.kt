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

        val layoutValue = findViewById<TextView>(R.id.mangaLayoutValue)
        fun refreshLayout() {
            layoutValue.setText(
                if (Settings.mangaDoublePage(this)) R.string.manga_layout_double
                else R.string.manga_layout_single
            )
        }
        findViewById<View>(R.id.mangaLayoutRow).setOnClickListener {
            val checked = if (Settings.mangaDoublePage(this)) 1 else 0
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.manga_layout_title)
                .setSingleChoiceItems(R.array.manga_layout_choices, checked) { dialog, which ->
                    Settings.setMangaDoublePage(this, which == 1)
                    refreshLayout()
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        refreshLayout()

        findViewById<MaterialSwitch>(R.id.splitWidePagesSwitch).apply {
            isChecked = Settings.splitWidePages(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked -> Settings.setSplitWidePages(this@SettingsActivity, checked) }
        }
        findViewById<MaterialSwitch>(R.id.pdfHorizontalSwitch).apply {
            isChecked = Settings.pdfHorizontalPages(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked -> Settings.setPdfHorizontalPages(this@SettingsActivity, checked) }
        }
    }
}
