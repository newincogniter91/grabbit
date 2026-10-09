package io.github.newincogniter91.grabbit

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class OutFormat(val label: String) {
    MP4("MP4"),
    M4A("M4A"),
    MP3("MP3"),
    WEBM("WEBM"),
    OPUS("OPUS"),
}

const val DEFAULT_FOLDER_LABEL = "Downloads/Grabbit"

data class UiState(
    val url: String = "",
    val format: OutFormat = OutFormat.MP4,
    val ready: Boolean = false,
    val initError: String? = null,
    val busy: Boolean = false,
    val progress: Float = 0f,
    val status: String = "",
    val isError: Boolean = false,
    val engineVersion: String = "",
    val updating: Boolean = false,
    val updateMessage: String = "",
    val disclaimerAccepted: Boolean = false,
    val darkTheme: Boolean = true,
    val saveFolderLabel: String = DEFAULT_FOLDER_LABEL,
    val customFolder: Boolean = false,
)

class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("grabbit", Application.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        UiState(
            disclaimerAccepted = prefs.getBoolean(KEY_DISCLAIMER, false),
            darkTheme = prefs.getBoolean(KEY_DARK, true),
            saveFolderLabel = folderLabel(prefs.getString(KEY_FOLDER, null)),
            customFolder = prefs.getString(KEY_FOLDER, null) != null,
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    @Volatile
    private var cancelled = false

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                YoutubeDL.getInstance().init(app)
                FFmpeg.getInstance().init(app)
                val version = runCatching { YoutubeDL.getInstance().version(app) }.getOrNull().orEmpty()
                _state.update { it.copy(ready = true, engineVersion = version) }
            } catch (e: Throwable) {
                _state.update { it.copy(initError = "Engine failed to start: ${e.message}") }
            }
        }
    }

    fun setUrl(value: String) = _state.update { it.copy(url = value) }

    fun setFormat(value: OutFormat) = _state.update { it.copy(format = value) }

    fun acceptDisclaimer() {
        prefs.edit().putBoolean(KEY_DISCLAIMER, true).apply()
        _state.update { it.copy(disclaimerAccepted = true) }
    }

    fun setDarkTheme(dark: Boolean) {
        prefs.edit().putBoolean(KEY_DARK, dark).apply()
        _state.update { it.copy(darkTheme = dark) }
    }

    fun setSaveFolder(uri: Uri) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        prefs.edit().putString(KEY_FOLDER, uri.toString()).apply()
        _state.update { it.copy(saveFolderLabel = folderLabel(uri.toString()), customFolder = true) }
    }

    fun resetSaveFolder() {
        prefs.edit().remove(KEY_FOLDER).apply()
        _state.update { it.copy(saveFolderLabel = DEFAULT_FOLDER_LABEL, customFolder = false) }
    }

    private fun folderLabel(uriString: String?): String {
        if (uriString == null) return DEFAULT_FOLDER_LABEL
        val id = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(uriString)) }.getOrNull()
            ?: return DEFAULT_FOLDER_LABEL
        return id.substringAfter(':', "").ifEmpty { "Internal storage" }
    }

    fun handleSharedText(text: String) {
        val link = Regex("https?://\\S+").find(text)?.value ?: text.trim()
        setUrl(link)
    }

    fun download() {
        val s = _state.value
        val url = s.url.trim()
        if (url.isEmpty() || s.busy || !s.ready) return
        cancelled = false
        _state.update { it.copy(busy = true, progress = 0f, status = "Starting…", isError = false) }

        viewModelScope.launch(Dispatchers.IO) {
            val tmp = File(app.cacheDir, "dl").apply {
                deleteRecursively()
                mkdirs()
            }
            try {
                val request = buildRequest(url, s.format, tmp)
                YoutubeDL.getInstance().execute(request, PROCESS_ID) { progress, _, _ ->
                    if (progress >= 0f) {
                        _state.update {
                            it.copy(
                                progress = (progress / 100f).coerceIn(0f, 1f),
                                status = "Downloading… ${progress.toInt()}%",
                            )
                        }
                    }
                }

                _state.update { it.copy(progress = 1f, status = "Saving…") }
                val files = tmp.listFiles().orEmpty().filter { f ->
                    f.isFile && SKIP_SUFFIXES.none { f.name.endsWith(it) }
                }
                if (files.isEmpty()) error("No file was produced.")
                val usedFallback = files.map { saveFile(it) }.any { it }
                val where = if (usedFallback) DEFAULT_FOLDER_LABEL else _state.value.saveFolderLabel
                val note = if (usedFallback) "Chosen folder unavailable. " else ""
                _state.update {
                    it.copy(
                        busy = false,
                        status = "${note}Saved to $where: ${files.first().name}",
                        isError = false,
                    )
                }
            } catch (e: Throwable) {
                val message = if (cancelled) {
                    "Cancelled."
                } else {
                    e.message.orEmpty().lines().lastOrNull { it.isNotBlank() }?.take(300)
                        ?: "Download failed."
                }
                _state.update { it.copy(busy = false, status = message, isError = !cancelled) }
            } finally {
                tmp.deleteRecursively()
            }
        }
    }

    fun cancel() {
        cancelled = true
        YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
    }

    fun updateEngine() {
        if (_state.value.updating) return
        _state.update { it.copy(updating = true, updateMessage = "Checking for updates…") }
        viewModelScope.launch(Dispatchers.IO) {
            val message = try {
                val result = YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE)
                if (result.toString().contains("ALREADY")) "Already up to date." else "Updated successfully."
            } catch (e: Throwable) {
                "Update failed: ${e.message}"
            }
            val version = runCatching { YoutubeDL.getInstance().version(app) }.getOrNull().orEmpty()
            _state.update {
                it.copy(updating = false, updateMessage = message, engineVersion = version.ifBlank { it.engineVersion })
            }
        }
    }

    private fun buildRequest(url: String, format: OutFormat, dir: File): YoutubeDLRequest {
        val request = YoutubeDLRequest(url)
        request.addOption("--no-playlist")
        request.addOption("--no-mtime")
        request.addOption("-o", "${dir.absolutePath}/%(title).120s.%(ext)s")
        when (format) {
            OutFormat.MP4 -> {
                request.addOption("-f", "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b")
                request.addOption("--merge-output-format", "mp4")
            }
            OutFormat.WEBM -> {
                request.addOption("-f", "bv*[ext=webm]+ba[ext=webm]/bv*+ba/b")
                request.addOption("--merge-output-format", "webm")
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
        return request
    }

    /** Returns true when the chosen folder failed and the default location was used instead. */
    private fun saveFile(file: File): Boolean {
        val folder = prefs.getString(KEY_FOLDER, null)?.let(Uri::parse)
        if (folder != null) {
            try {
                saveToFolder(folder, file)
                return false
            } catch (e: Throwable) {
                // fall back to the default location below
            }
        }
        saveToDownloads(file)
        return folder != null
    }

    private fun saveToFolder(treeUri: Uri, file: File) {
        val resolver = app.contentResolver
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val doc = DocumentsContract.createDocument(resolver, parent, mime, file.name)
            ?: error("Could not create the file in the chosen folder.")
        resolver.openOutputStream(doc)?.use { out ->
            file.inputStream().use { it.copyTo(out) }
        } ?: error("Could not write to the chosen folder.")
    }

    private fun saveToDownloads(file: File) {
        val resolver = app.contentResolver
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())
            ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
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

    private companion object {
        const val KEY_DISCLAIMER = "disclaimer_accepted"
        const val KEY_DARK = "dark_theme"
        const val KEY_FOLDER = "save_folder_uri"
        const val PROCESS_ID = "grabbit-download"
        val SKIP_SUFFIXES = listOf(".part", ".ytdl", ".temp", ".tmp")
    }
}
