package kr.iamtalker.waterview

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 첫 배포부터 사용할 업데이트 확인기. 릴리스 저장소가 정해지면 REPOSITORY만 고정하면 된다. */
object UpdateChecker {
    private const val REPOSITORY = "iamtalker/WaterView-Android"

    data class Release(val version: String, val pageUrl: String)

    suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL("https://api.github.com/repos/$REPOSITORY/releases/latest").openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.inputStream.bufferedReader().use { json ->
                val body = json.readText()
                val tag = Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)").find(body)?.groupValues?.get(1)
                val page = Regex("\"html_url\"\\s*:\\s*\"([^\"]+)").find(body)?.groupValues?.get(1)
                if (tag != null && page != null) Release(tag.removePrefix("v"), page) else null
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
}
