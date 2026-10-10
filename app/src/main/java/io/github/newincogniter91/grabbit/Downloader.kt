package io.github.newincogniter91.grabbit

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

const val DEFAULT_FOLDER_LABEL = "Downloads/Grabbit"
const val PREFS_NAME = "grabbit"
const val KEY_FOLDER = "save_folder_uri"

enum class OutFormat(val label: String, val isVideo: Boolean) {
    MP4("MP4", true),
    M4A("M4A", false),
    MP3("MP3", false),
    WEBM("WEBM", true),
    OPUS("OPUS", false),
}

enum class VideoQuality(val label: String, val height: Int?) {
    BEST("Best", null),
    P1080("1080p", 1080),
    P720("720p", 720),
    P480("480p", 480),
    P360("360p", 360),
}

data class ExtraOptions(
    val embedCover: Boolean = false,
    val separateAv: Boolean = false,
    val trim: Boolean = false,
    val trimStart: String = "",
    val trimEnd: String = "",
)

object Downloader {

    /** Parses "90", "1:30" or "1:02:03" (optionally with decimals) into seconds, or null if invalid. */
    fun parseTime(s: String): Double? {
        if (!Regex("""\d+(:\d{1,2}){0,2}(\.\d+)?""").matches(s)) return null
        return s.split(':').fold(0.0) { acc, part -> acc * 60 + part.toDouble() }
    }

    fun buildRequest(
        url: String,
        format: OutFormat,
        quality: VideoQuality,
        dir: File,
        extra: ExtraOptions = ExtraOptions(),
    ): YoutubeDLRequest {
        val request = YoutubeDLRequest(url)
        request.addOption("--no-playlist")
        request.addOption("--no-mtime")
        val separate = extra.separateAv && format.isVideo
        val name = if (separate) "%(title).110s [%(format_id)s]" else "%(title).120s"
        request.addOption("-o", "${dir.absolutePath}/$name.%(ext)s")
        val h = quality.height?.let { "[height<=?$it]" }.orEmpty()
        when (format) {
            OutFormat.MP4 -> {
                if (separate) {
                    request.addOption("-f", "bv*$h[ext=mp4]/bv*$h,ba[ext=m4a]/ba")
                } else {
                    request.addOption("-f", "bv*$h[ext=mp4]+ba[ext=m4a]/b$h[ext=mp4]/bv*$h+ba/b$h/b")
                    request.addOption("--merge-output-format", "mp4")
                }
            }
            OutFormat.WEBM -> {
                if (separate) {
                    request.addOption("-f", "bv*$h[ext=webm]/bv*$h,ba[ext=webm]/ba")
                } else {
                    request.addOption("-f", "bv*$h[ext=webm]+ba[ext=webm]/bv*$h+ba/b$h/b")
                    request.addOption("--merge-output-format", "webm")
                }
            }
            OutFormat.M4A -> {
                request.addOption("-f", "ba[ext=m4a]/ba/b")
                request.addOption("-x")
                request.addOption("--audio-format", "m4a")
            }
            OutFormat.MP3 -> {
                request.addOption("-f", "ba/b")
                request.addOption("-x")
                request.addOption("--audio-format", "mp3")
                request.addOption("--audio-quality", "0")
            }
            OutFormat.OPUS -> {
                request.addOption("-f", "ba/b")
                request.addOption("-x")
                request.addOption("--audio-format", "opus")
            }
        }
        if (extra.embedCover && format != OutFormat.WEBM) {
            request.addOption("--embed-thumbnail")
            request.addOption("--convert-thumbnails", "jpg")
        }
        if (extra.trim) {
            val from = extra.trimStart.ifBlank { "0" }
            val to = extra.trimEnd.ifBlank { "inf" }
            request.addOption("--download-sections", "*$from-$to")
        }
        return request
    }

    fun folderLabel(uriString: String?): String {
        if (uriString == null) return DEFAULT_FOLDER_LABEL
        val id = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(uriString)) }.getOrNull()
            ?: return DEFAULT_FOLDER_LABEL
        return id.substringAfter(':', "").ifEmpty { "Internal storage" }
    }

    /** Returns true when the chosen folder failed and the default location was used instead. */
    fun saveFile(context: Context, file: File): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val folder = prefs.getString(KEY_FOLDER, null)?.let(Uri::parse)
        if (folder != null) {
            try {
                saveToFolder(context, folder, file)
                return false
            } catch (e: Throwable) {
                // fall back to the default location below
            }
        }
        saveToDownloads(context, file)
        return folder != null
    }

    private fun mimeOf(file: File): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"

    private fun saveToFolder(context: Context, treeUri: Uri, file: File) {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val doc = DocumentsContract.createDocument(resolver, parent, mimeOf(file), file.name)
            ?: error("Could not create the file in the chosen folder.")
        resolver.openOutputStream(doc)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: error("Could not write to the chosen folder.")
    }

    private fun saveToDownloads(context: Context, file: File) {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mimeOf(file))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Grabbit")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create the file in Downloads.")
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: error("Could not write to Downloads.")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
}
