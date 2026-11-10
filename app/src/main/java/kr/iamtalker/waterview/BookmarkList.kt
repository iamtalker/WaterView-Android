package kr.iamtalker.waterview

import android.app.Activity
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The bookmark list of a document, with a delete button beside each bookmark. Tapping a name goes
 * to that bookmark; its own 삭제 takes just that one away and the list stays open with the rest.
 */
internal object BookmarkList {

    /** [label] names a bookmark by its number in the list; [onJump] goes to it. */
    fun show(
        activity: Activity,
        key: String,
        title: Int,
        label: (Int, Marks.Bookmark) -> String,
        onJump: (Marks.Bookmark) -> Unit,
    ) {
        if (Marks.bookmarks(activity, key).isEmpty()) {
            Toast.makeText(activity, R.string.no_bookmarks, Toast.LENGTH_SHORT).show()
            return
        }
        val density = activity.resources.displayMetrics.density
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        lateinit var dialog: AlertDialog

        fun fill() {
            rows.removeAllViews()
            val marks = Marks.bookmarks(activity, key)
            if (marks.isEmpty()) {
                dialog.dismiss()
                Toast.makeText(activity, R.string.no_bookmarks, Toast.LENGTH_SHORT).show()
                return
            }
            marks.forEachIndexed { i, mark ->
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val name = TextView(activity).apply {
                    text = label(i, mark)
                    textSize = 16f
                    val pad = (16 * density).toInt()
                    setPadding(pad, (12 * density).toInt(), pad, (12 * density).toInt())
                    val ripple = TypedValue()
                    activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                    setBackgroundResource(ripple.resourceId)
                    isClickable = true
                    setOnClickListener {
                        dialog.dismiss()
                        onJump(mark)
                    }
                }
                row.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                val remove = MaterialButton(
                    activity, null, androidx.appcompat.R.attr.borderlessButtonStyle,
                ).apply {
                    text = activity.getString(R.string.bookmark_remove)
                    setOnClickListener {
                        Marks.remove(activity, key, mark.at)
                        fill()
                    }
                }
                row.addView(remove, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                rows.addView(row)
            }
        }

        val scroll = ScrollView(activity).apply { addView(rows) }
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        fill()
        dialog.show()
    }
}
