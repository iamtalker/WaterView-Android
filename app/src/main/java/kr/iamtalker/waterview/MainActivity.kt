package kr.iamtalker.waterview

import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.content.Intent
import java.util.zip.ZipInputStream
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { WaterViewHome() }
    }
}

private sealed interface ViewerContent {
    data object Empty : ViewerContent
    data class Text(val name: String, val value: String) : ViewerContent
    data class Image(val name: String, val bitmap: android.graphics.Bitmap) : ViewerContent
    data class Pdf(val name: String, val bitmap: android.graphics.Bitmap, val pages: Int) : ViewerContent
    data class Archive(
        val name: String,
        val uri: Uri,
        val entryNames: List<String>,
        val index: Int,
        val bitmap: android.graphics.Bitmap,
    ) : ViewerContent
    data class External(val name: String, val uri: Uri, val mimeType: String, val format: MediaFormat?) : ViewerContent
    data class Error(val message: String) : ViewerContent
}

@Composable
private fun WaterViewHome() {
    val context = LocalContext.current
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var content by remember { mutableStateOf<ViewerContent>(ViewerContent.Empty) }
    var updateStatus by remember { mutableStateOf("업데이트 확인 안 함") }
    var updateUrl by remember { mutableStateOf<String?>(null) }
    var updateRelease by remember { mutableStateOf<UpdateChecker.Release?>(null) }
    val updateScope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selectedUri = uri
    }

    LaunchedEffect(selectedUri) {
        val uri = selectedUri ?: return@LaunchedEffect
        content = withContext(Dispatchers.IO) { loadContent(context, uri) }
    }

    LaunchedEffect(Unit) {
        val release = UpdateChecker.latest()
        if (release != null && UpdateChecker.isNewer(release.version, BuildConfig.VERSION_NAME)) {
            updateRelease = release
            updateUrl = release.pageUrl
            updateStatus = "새 버전 ${release.version}이 있습니다."
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("물뷰", style = MaterialTheme.typography.headlineLarge)
                Text("안드로이드 올인원 뷰어", style = MaterialTheme.typography.titleMedium)
                Button(onClick = {
                    picker.launch(arrayOf("image/*", "text/plain", "text/markdown", "application/pdf", "video/*", "audio/*", "application/zip", "*/*"))
                }) { Text("파일 열기") }
                Button(onClick = {
                    updateScope.launch {
                        updateStatus = "업데이트 확인 중…"
                        val release = UpdateChecker.latest()
                        updateRelease = if (release != null && UpdateChecker.isNewer(release.version, BuildConfig.VERSION_NAME)) release else null
                        updateUrl = updateRelease?.pageUrl
                        updateStatus = when {
                            release == null -> "업데이트를 확인하지 못했습니다."
                            UpdateChecker.isNewer(release.version, BuildConfig.VERSION_NAME) -> "새 버전 ${release.version}이 있습니다. 릴리스 페이지: ${release.pageUrl}"
                            else -> "최신 버전입니다. (${BuildConfig.VERSION_NAME})"
                        }
                    }
                }) { Text("업데이트 확인") }
                Text(updateStatus, style = MaterialTheme.typography.bodySmall)
                updateRelease?.let { release ->
                    Button(onClick = {
                        updateScope.launch {
                            updateStatus = "업데이트 다운로드 중…"
                            runCatching { UpdateChecker.downloadAndInstall(context, release) }
                                .onSuccess { updateStatus = "설치 확인 화면을 여는 중…" }
                                .onFailure { updateStatus = "업데이트 실패: ${it.message ?: "알 수 없는 오류"}" }
                        }
                    }) { Text("업데이트 설치") }
                }
                updateUrl?.let { url ->
                    Button(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }) { Text("새 버전 받기") }
                }
                Viewer(context, content) { nextIndex ->
                    val archive = content as? ViewerContent.Archive ?: return@Viewer
                    updateScope.launch(Dispatchers.IO) {
                        val next = loadZipPage(context, archive.uri, archive.entryNames, nextIndex)
                        withContext(Dispatchers.Main) { content = next }
                    }
                }
            }
        }
    }
}

@Composable
private fun Viewer(context: android.content.Context, content: ViewerContent, onArchivePage: (Int) -> Unit) {
    when (content) {
        ViewerContent.Empty -> Text("파일을 선택하면 여기에 표시됩니다.")
        is ViewerContent.Text -> Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(content.name, style = MaterialTheme.typography.titleMedium)
            Text(content.value, modifier = Modifier.padding(top = 8.dp))
        }
        is ViewerContent.Image -> Column {
            Text(content.name, style = MaterialTheme.typography.titleMedium)
            Image(
                bitmap = content.bitmap.asImageBitmap(),
                contentDescription = content.name,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        }
        is ViewerContent.Pdf -> Column {
            Text(content.name, style = MaterialTheme.typography.titleMedium)
            Text("PDF ${content.pages}쪽 · 현재 첫 페이지", style = MaterialTheme.typography.bodySmall)
            Image(
                bitmap = content.bitmap.asImageBitmap(),
                contentDescription = content.name,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
        }
        is ViewerContent.Archive -> Column {
            Text(content.name, style = MaterialTheme.typography.titleMedium)
            Text("${content.entryNames[content.index]} · ${content.index + 1}/${content.entryNames.size}", style = MaterialTheme.typography.bodySmall)
            Image(
                bitmap = content.bitmap.asImageBitmap(),
                contentDescription = content.entryNames[content.index],
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.FillWidth,
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onArchivePage(content.index - 1) }, enabled = content.index > 0) { Text("이전 페이지") }
                Button(onClick = { onArchivePage(content.index + 1) }, enabled = content.index + 1 < content.entryNames.size) { Text("다음 페이지") }
            }
        }
        is ViewerContent.External -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${content.name}\n${content.format?.label ?: "파일"}는 외부 재생 앱으로 엽니다.")
            Button(onClick = {
                val intent = Intent(Intent.ACTION_VIEW, content.uri).apply {
                    setDataAndType(content.uri, content.mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                runCatching { context.startActivity(intent) }
            }) { Text("외부 앱으로 열기") }
        }
        is ViewerContent.Error -> Text("파일을 열 수 없습니다.\n${content.message}", color = MaterialTheme.colorScheme.error)
    }
}

private fun loadContent(context: android.content.Context, uri: Uri): ViewerContent {
    val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        ?: uri.lastPathSegment
        ?: "선택한 파일"
    val format = MediaFormat.fromExtension(name.substringAfterLast('.', ""))
    val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
    return try {
        when (format) {
            MediaFormat.TEXT -> {
                val text = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes().toString(Charsets.UTF_8).removePrefix("\uFEFF")
                } ?: return ViewerContent.Error("파일을 읽지 못했습니다.")
                ViewerContent.Text(name, text)
            }
            MediaFormat.IMAGE -> {
                val bitmap = context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                    ?: return ViewerContent.Error("이미지를 읽지 못했습니다.")
                ViewerContent.Image(name, bitmap)
            }
            MediaFormat.PDF -> {
                val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                    ?: return ViewerContent.Error("PDF를 열 수 없습니다.")
                descriptor.use { file ->
                    PdfRenderer(file).use { renderer ->
                        if (renderer.pageCount == 0) return ViewerContent.Error("PDF에 페이지가 없습니다.")
                        renderer.openPage(0).use { page ->
                            val width = (page.width * 2).coerceAtLeast(1)
                            val height = (page.height * 2).coerceAtLeast(1)
                            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                            bitmap.eraseColor(Color.WHITE)
                            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            ViewerContent.Pdf(name, bitmap, renderer.pageCount)
                        }
                    }
                }
            }
            MediaFormat.ARCHIVE -> {
                if (name.substringAfterLast('.', "").lowercase() in listOf("zip", "cbz")) {
                    loadZipContent(context, uri, name)
                } else {
                    ViewerContent.External(name, uri, mimeType, format)
                }
            }
            MediaFormat.VIDEO, MediaFormat.AUDIO, null -> ViewerContent.External(name, uri, mimeType, format)
        }
    } catch (e: Exception) {
        ViewerContent.Error(e.message ?: "알 수 없는 오류")
    }
}

private fun loadZipContent(context: android.content.Context, uri: Uri, name: String): ViewerContent {
    val imageNames = mutableListOf<String>()
    var firstBitmap: android.graphics.Bitmap? = null
    context.contentResolver.openInputStream(uri)?.use { input ->
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val extension = entry.name.substringAfterLast('.', "").lowercase()
                if (extension !in listOf("jpg", "jpeg", "png", "gif", "webp", "bmp")) continue
                imageNames += entry.name
                if (firstBitmap == null) firstBitmap = BitmapFactory.decodeStream(zip)
            }
        }
    } ?: return ViewerContent.Error("압축 파일을 읽지 못했습니다.")
    val bitmap = firstBitmap
    return if (imageNames.isEmpty() || bitmap == null) {
        ViewerContent.Error("압축 파일 안에 지원하는 이미지가 없습니다.")
    } else {
        ViewerContent.Archive(name, uri, imageNames, 0, bitmap)
    }
}

private fun loadZipPage(
    context: android.content.Context,
    uri: Uri,
    imageNames: List<String>,
    index: Int,
): ViewerContent {
    val target = imageNames[index]
    context.contentResolver.openInputStream(uri)?.use { input ->
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name != target) continue
                val bitmap = BitmapFactory.decodeStream(zip)
                    ?: return ViewerContent.Error("압축 이미지가 손상되었습니다.")
                return ViewerContent.Archive(uri.lastPathSegment ?: "압축 파일", uri, imageNames, index, bitmap)
            }
        }
    }
    return ViewerContent.Error("압축 이미지 페이지를 찾지 못했습니다.")
}
