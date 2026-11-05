package kr.iamtalker.waterview

enum class MediaFormat(val label: String, val extensions: List<String>) {
    IMAGE("이미지", listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "tiff", "svg")),
    ARCHIVE("압축 만화", listOf("zip", "rar", "7z", "cbz", "cbr", "cb7", "cbt")),
    PDF("PDF", listOf("pdf")),
    TEXT("텍스트", listOf("txt", "md")),
    VIDEO("동영상", listOf("mp4", "mkv", "avi", "mov", "wmv")),
    AUDIO("음악", listOf("mp3"));

    companion object {
        fun fromExtension(extension: String): MediaFormat? = values().firstOrNull {
            extension.trimStart('.').lowercase() in it.extensions
        }
    }
}
