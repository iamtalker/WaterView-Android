package kr.iamtalker.waterview

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {
    private const val REPOSITORY = "iamtalker/WaterView-Android"
    data class Release(val version: String, val downloadUrl: String?)

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL("https://api.github.com/repos/$REPOSITORY/releases/latest").openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.inputStream.bufferedReader().use { reader ->
                val body = reader.readText()
                val tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)").find(body)?.groupValues?.get(1)
                val apk = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+\\.apk)\"").find(body)?.groupValues?.get(1)
                if (tag == null) null else Release(tag.removePrefix("v"), apk)
            }
        }.getOrNull()
    }

    fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        return (0 until maxOf(a.size, b.size)).firstNotNullOfOrNull { i ->
            (a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }).takeIf { it != 0 }
        }?.let { it > 0 } ?: false
    }

    suspend fun downloadAndInstall(context: Context, release: Release) = withContext(Dispatchers.IO) {
        val url = release.downloadUrl ?: error("릴리즈 APK를 찾지 못했습니다.")
        val part = File(context.cacheDir, "waterview-update.apk.part")
        val apk = File(context.cacheDir, "waterview-update.apk")
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.inputStream.use { input -> part.outputStream().use { output -> input.copyTo(output) } }
        if (!part.renameTo(apk)) error("업데이트 파일을 저장하지 못했습니다.")

        val installer = context.packageManager.packageInstaller
        val sessionId = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input -> session.openWrite("update.apk", 0, apk.length()).use { output -> input.copyTo(output); session.fsync(output) } }
            val intent = Intent(context, UpdateReceiver::class.java).setPackage(context.packageName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
        }
    }
}

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (confirm != null) context.startActivity(confirm)
        }
    }
}
