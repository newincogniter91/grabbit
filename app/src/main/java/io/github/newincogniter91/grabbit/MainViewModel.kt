package io.github.newincogniter91.grabbit

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val url: String = "",
    val format: OutFormat = OutFormat.MP4,
    val quality: VideoQuality = VideoQuality.BEST,
    val embedCover: Boolean = false,
    val separateAv: Boolean = false,
    val trim: Boolean = false,
    val trimStart: String = "",
    val trimEnd: String = "",
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
    val amoled: Boolean = false,
    val saveFolderLabel: String = DEFAULT_FOLDER_LABEL,
    val customFolder: Boolean = false,
)

class MainViewModel(private val app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences(PREFS_NAME, Application.MODE_PRIVATE)
    private val _state = MutableStateFlow(
        UiState(
            disclaimerAccepted = prefs.getBoolean(KEY_DISCLAIMER, false),
            darkTheme = prefs.getBoolean(KEY_DARK, true),
            amoled = prefs.getBoolean(KEY_AMOLED, false),
            saveFolderLabel = Downloader.folderLabel(prefs.getString(KEY_FOLDER, null)),
            customFolder = prefs.getString(KEY_FOLDER, null) != null,
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

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
        viewModelScope.launch {
            DownloadService.status.collect { s ->
                _state.update {
                    it.copy(busy = s.busy, progress = s.progress, status = s.status, isError = s.isError)
                }
            }
        }
    }

    fun setUrl(value: String) = _state.update { it.copy(url = value) }

    fun setFormat(value: OutFormat) = _state.update { it.copy(format = value) }

    fun setQuality(value: VideoQuality) = _state.update { it.copy(quality = value) }

    fun setEmbedCover(value: Boolean) = _state.update { it.copy(embedCover = value) }

    fun setSeparateAv(value: Boolean) = _state.update { it.copy(separateAv = value) }

    fun setTrim(value: Boolean) = _state.update { it.copy(trim = value) }

    fun setTrimStart(value: String) = _state.update { it.copy(trimStart = value) }

    fun setTrimEnd(value: String) = _state.update { it.copy(trimEnd = value) }

    fun acceptDisclaimer() {
        prefs.edit().putBoolean(KEY_DISCLAIMER, true).apply()
        _state.update { it.copy(disclaimerAccepted = true) }
    }

    fun setDarkTheme(dark: Boolean) {
        prefs.edit().putBoolean(KEY_DARK, dark).putBoolean(KEY_AMOLED, false).apply()
        _state.update { it.copy(darkTheme = dark, amoled = false) }
    }

    fun setAmoledTheme() {
        prefs.edit().putBoolean(KEY_DARK, true).putBoolean(KEY_AMOLED, true).apply()
        _state.update { it.copy(darkTheme = true, amoled = true) }
    }

    fun setSaveFolder(uri: Uri) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        prefs.edit().putString(KEY_FOLDER, uri.toString()).apply()
        _state.update { it.copy(saveFolderLabel = Downloader.folderLabel(uri.toString()), customFolder = true) }
    }

    fun resetSaveFolder() {
        prefs.edit().remove(KEY_FOLDER).apply()
        _state.update { it.copy(saveFolderLabel = DEFAULT_FOLDER_LABEL, customFolder = false) }
    }

    fun handleSharedText(text: String) {
        val link = Regex("https?://\\S+").find(text)?.value ?: text.trim()
        setUrl(link)
    }

    fun download() {
        val s = _state.value
        val url = s.url.trim()
        if (url.isEmpty() || s.busy || !s.ready) return
        val from = s.trimStart.trim()
        val to = s.trimEnd.trim()
        if (s.trim) {
            val start = if (from.isEmpty()) 0.0 else Downloader.parseTime(from)
            val end = if (to.isEmpty()) null else Downloader.parseTime(to)
            val invalid = (from.isEmpty() && to.isEmpty()) ||
                start == null ||
                (to.isNotEmpty() && end == null) ||
                (end != null && end <= start)
            if (invalid) {
                _state.update { it.copy(status = "Invalid time range. Use m:ss or h:mm:ss.", isError = true) }
                return
            }
        }
        val extra = ExtraOptions(
            embedCover = s.embedCover,
            separateAv = s.separateAv,
            trim = s.trim,
            trimStart = from,
            trimEnd = to,
        )
        DownloadService.start(app, url, s.format, s.quality, extra)
    }

    fun cancel() = DownloadService.cancelDownload()

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

    private companion object {
        const val KEY_DISCLAIMER = "disclaimer_accepted"
        const val KEY_DARK = "dark_theme"
        const val KEY_AMOLED = "amoled_theme"
    }
}
