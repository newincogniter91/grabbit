package io.github.newincogniter91.grabbit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class DownloadStatus(
    val busy: Boolean = false,
    val progress: Float = 0f,
    val status: String = "",
    val isError: Boolean = false,
)

/** Runs downloads as a foreground service so they keep going when the app is in the background. */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelDownload()
            if (job?.isActive != true) stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        startForeground(
            NOTIF_ID,
            buildNotification("Starting…", 0, true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )

        val url = intent?.getStringExtra(EXTRA_URL)
        if (url == null) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY

        val format = runCatching { OutFormat.valueOf(intent.getStringExtra(EXTRA_FORMAT).orEmpty()) }
            .getOrDefault(OutFormat.MP4)
        val quality = runCatching { VideoQuality.valueOf(intent.getStringExtra(EXTRA_QUALITY).orEmpty()) }
            .getOrDefault(VideoQuality.BEST)

        val extra = ExtraOptions(
            embedCover = intent.getBooleanExtra(EXTRA_EMBED, false),
            separateAv = intent.getBooleanExtra(EXTRA_SEPARATE, false),
            trim = intent.getBooleanExtra(EXTRA_TRIM, false),
            trimStart = intent.getStringExtra(EXTRA_TRIM_START).orEmpty(),
            trimEnd = intent.getStringExtra(EXTRA_TRIM_END).orEmpty(),
        )

        job = scope.launch {
            val wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "grabbit:download")
            wakeLock.acquire(60 * 60 * 1000L)
            try {
                runDownload(url, format, quality, extra)
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun runDownload(url: String, format: OutFormat, quality: VideoQuality, extra: ExtraOptions) {
        cancelled = false
        val tmp = File(cacheDir, "dl").apply {
            deleteRecursively()
            mkdirs()
        }
        var lastPercent = -1
        try {
            val request = Downloader.buildRequest(url, format, quality, tmp, extra)
            YoutubeDL.getInstance().execute(request, PROCESS_ID) { progress, _, _ ->
                if (progress >= 0f) {
                    val percent = progress.toInt()
                    _status.update {
                        it.copy(
                            progress = (progress / 100f).coerceIn(0f, 1f),
                            status = "Downloading… $percent%",
                        )
                    }
                    if (percent != lastPercent) {
                        lastPercent = percent
                        notifyProgress("Downloading… $percent%", percent)
                    }
                }
            }

            _status.update { it.copy(progress = 1f, status = "Saving…") }
            notifyProgress("Saving…", 100)
            val files = tmp.listFiles().orEmpty().filter { f ->
                f.isFile && SKIP_SUFFIXES.none { f.name.endsWith(it) }
            }
            if (files.isEmpty()) error("No file was produced.")
            val usedFallback = files.map { Downloader.saveFile(this, it) }.any { it }
            val where = if (usedFallback) {
                DEFAULT_FOLDER_LABEL
            } else {
                Downloader.folderLabel(
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_FOLDER, null)
                )
            }
            val note = if (usedFallback) "Chosen folder unavailable. " else ""
            val message = "${note}Saved to $where: ${files.first().name}"
            _status.value = DownloadStatus(busy = false, progress = 1f, status = message)
            notifyDone(message, error = false)
        } catch (e: Throwable) {
            val message = if (cancelled) {
                "Cancelled."
            } else {
                e.message.orEmpty().lines().lastOrNull { it.isNotBlank() }?.take(300) ?: "Download failed."
            }
            _status.value = DownloadStatus(busy = false, status = message, isError = !cancelled)
            if (!cancelled) notifyDone(message, error = true)
        } finally {
            tmp.deleteRecursively()
        }
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildNotification(text: String, percent: Int, indeterminate: Boolean): Notification {
        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DownloadService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Cancel",
            cancelIntent,
        ).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Grabbit")
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, indeterminate)
            .addAction(cancelAction)
            .build()
    }

    private fun notifyProgress(text: String, percent: Int) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text, percent, false))
    }

    private fun notifyDone(text: String, error: Boolean) {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (error) "Download failed" else "Download complete")
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(DONE_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIF_ID = 1
        private const val DONE_ID = 2
        private const val PROCESS_ID = "grabbit-download"
        private const val ACTION_CANCEL = "io.github.newincogniter91.grabbit.CANCEL"
        private const val EXTRA_URL = "url"
        private const val EXTRA_FORMAT = "format"
        private const val EXTRA_QUALITY = "quality"
        private const val EXTRA_EMBED = "embed_cover"
        private const val EXTRA_SEPARATE = "separate_av"
        private const val EXTRA_TRIM = "trim"
        private const val EXTRA_TRIM_START = "trim_start"
        private const val EXTRA_TRIM_END = "trim_end"
        private val SKIP_SUFFIXES = listOf(".part", ".ytdl", ".temp", ".tmp", ".jpg", ".png", ".webp")

        @Volatile
        private var cancelled = false

        private val _status = MutableStateFlow(DownloadStatus())
        val status: StateFlow<DownloadStatus> = _status.asStateFlow()

        fun start(
            context: Context,
            url: String,
            format: OutFormat,
            quality: VideoQuality,
            extra: ExtraOptions = ExtraOptions(),
        ) {
            _status.value = DownloadStatus(busy = true, status = "Starting…")
            val intent = Intent(context, DownloadService::class.java)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_FORMAT, format.name)
                .putExtra(EXTRA_QUALITY, quality.name)
                .putExtra(EXTRA_EMBED, extra.embedCover)
                .putExtra(EXTRA_SEPARATE, extra.separateAv)
                .putExtra(EXTRA_TRIM, extra.trim)
                .putExtra(EXTRA_TRIM_START, extra.trimStart)
                .putExtra(EXTRA_TRIM_END, extra.trimEnd)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Throwable) {
                _status.value = DownloadStatus(status = "Could not start the download: ${e.message}", isError = true)
            }
        }

        fun cancelDownload() {
            cancelled = true
            runCatching { YoutubeDL.getInstance().destroyProcessById(PROCESS_ID) }
        }
    }
}
