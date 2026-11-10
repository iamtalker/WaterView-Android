package kr.iamtalker.waterview

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks for a newer WaterView on GitHub and installs it. Ported from the APK update module: the
 * release tag is the version, the release file named [ASSET] is the download, and the phone's own
 * install confirmation finishes the job.
 */
object UpdateChecker {
    private const val REPOSITORY = "iamtalker/WaterView-Android"
    const val RELEASES_URL = "https://github.com/$REPOSITORY/releases/latest"

    /** The APK's file name on the release. It must match exactly, as the update module expects. */
    const val ASSET = "app-release.apk"

    /** [downloadUrl] is null when the release has no .apk; [notes] is the release text. */
    data class Release(val version: String, val downloadUrl: String?, val notes: String = "")

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL("https://api.github.com/repos/$REPOSITORY/releases/latest").openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.inputStream.bufferedReader().use { reader -> parseRelease(reader.readText()) }
        }.getOrNull()
    }

    /** The latest-release JSON from GitHub, or null when it has no tag. */
    fun parseRelease(json: String): Release? = runCatching {
        val o = JSONObject(json)
        val tag = o.getString("tag_name")
        var apk: String? = null
        val assets = o.optJSONArray("assets")
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name") == ASSET) {
                apk = a.getString("browser_download_url")
                break
            }
        }
        Release(tag.removePrefix("v").removePrefix("V"), apk, o.optString("body"))
    }.getOrNull()

    /** "v1.4.6" and "1.4.6" compare as numbers, so 1.10.0 is newer than 1.9.9. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parts(latest)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun parts(version: String): List<Int> = version.trim().removePrefix("v").removePrefix("V")
        .split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

    /**
     * Downloads the release and hands it to the phone's installer. [report] gets a percentage
     * (0..100, or -1 when the step has no known length) and a line of text for the dialog. Returns
     * once the install confirmation has been opened; the app itself is restarted by the system after
     * the reader confirms, so no progress can be shown for that last step.
     */
    suspend fun downloadAndInstall(context: Context, release: Release, report: (Int, String) -> Unit) = withContext(Dispatchers.IO) {
        val url = release.downloadUrl ?: error("릴리즈 APK를 찾지 못했습니다.")
        val part = File(context.cacheDir, "waterview-update.apk.part")
        val apk = File(context.cacheDir, "waterview-update.apk")

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        val total = connection.contentLengthLong
        val downloading = "새 버전 ${release.version} 받는 중"
        report(if (total > 0) 0 else -1, "$downloading 0%")
        var got = 0L
        var lastPercent = -1
        connection.inputStream.use { input -> part.outputStream().use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                output.write(buffer, 0, n)
                got += n
                if (total > 0) {
                    val percent = (got * 100 / total).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        report(percent, "$downloading $percent%")
                    }
                }
            }
        } }
        if (total > 0 && got != total) {
            part.delete()
            error("받은 크기가 다릅니다.")
        }
        apk.delete()
        if (!part.renameTo(apk)) error("업데이트 파일을 저장하지 못했습니다.")

        report(-1, "설치를 준비하는 중…")
        val installer = context.packageManager.packageInstaller
        val sessionId = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input -> session.openWrite("update.apk", 0, apk.length()).use { output ->
                input.copyTo(output)
                session.fsync(output)
            } }
            val intent = Intent(context, UpdateReceiver::class.java).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
        }
        report(-1, "설치 확인 화면을 기다리는 중…")
    }
}

/** The installer's answer: it opens the confirmation screen, or says why the install did not happen. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirm != null) context.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> toast(context, "설치를 취소했습니다.")
            else -> toast(context, "설치하지 못했습니다: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "오류 $status"))
        }
    }

    private fun toast(context: Context, text: String) {
        Handler(Looper.getMainLooper()).post { Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show() }
    }
}
