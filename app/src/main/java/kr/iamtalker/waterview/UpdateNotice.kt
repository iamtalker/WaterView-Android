package kr.iamtalker.waterview

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Tells the reader that a newer WaterView is out, with a notification that opens the update when it
 * is tapped. The release is looked up when the app opens, and at most once a day, so the notice is
 * only as late as the reader's last opening. No background job runs when the app is closed.
 */
internal object UpdateNotice {

    private const val CHANNEL_ID = "update"
    private const val NOTIFICATION_ID = 1
    private const val PREFS = "update_notice"
    private const val KEY_CHECKED_AT = "checked_at"
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /** Extra on the intent a tap on the notification opens the app with: the update is then asked about. */
    const val EXTRA_CHECK_UPDATE = "check_update"

    /** Whether the notification may be shown: on Android 13 and later, the reader must allow it. */
    fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    /** Looks for a newer release, at most once a day, and notifies if there is one. Runs off the main thread. */
    suspend fun checkDaily(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong(KEY_CHECKED_AT, 0L) < DAY_MILLIS) return
        val release = UpdateChecker.latest() ?: return
        prefs.edit().putLong(KEY_CHECKED_AT, now).apply()
        if (!UpdateChecker.isNewer(release.version, BuildConfig.VERSION_NAME)) return
        notify(context, release.version)
    }

    private fun notify(context: Context, version: String) {
        if (!allowed(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val open = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_CHECK_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val tap = PendingIntent.getActivity(
            context, NOTIFICATION_ID, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val note = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.update_available_title, version))
            .setContentText(context.getString(R.string.update_available_text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, note)
    }
}
