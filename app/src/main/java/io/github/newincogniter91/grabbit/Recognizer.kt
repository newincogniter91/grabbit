package io.github.newincogniter91.grabbit

import android.content.Context
import io.github.hitenpatel.songprint.Pcm
import io.github.hitenpatel.songprint.RecognitionClient
import io.github.hitenpatel.songprint.SignatureGenerator
import org.json.JSONObject
import java.io.File

const val KEY_RECOGNIZE = "recognize_songs"

data class SongTags(
    val title: String,
    val artist: String,
    val album: String?,
    val date: String?,
)

/** Result of [Recognizer.recognizeAndTag]: a short note to show next to the "Saved" message. */
data class RecognitionResult(val note: String)

/**
 * Identifies a downloaded song the way Shazam does, but on several excerpts: it cuts [SAMPLES] short
 * clips at different points of the file, fingerprints each one on the phone and asks Shazam's public
 * discovery endpoint (no API key). The result is only trusted when more than half of the searches
 * agree on the same artist + title; then the tags are rewritten with the recognised metadata.
 * Otherwise the file is left untouched.
 */
object Recognizer {
    private const val SAMPLES = 5
    private const val CLIP_SECONDS = 12
    private val POSITIONS = listOf(0.12, 0.30, 0.50, 0.68, 0.86)

    fun recognizeAndTag(
        context: Context,
        file: File,
        workDir: File,
        isCancelled: () -> Boolean,
    ): RecognitionResult {
        val ffmpeg = FfmpegRunner(context)
        val duration = ffmpeg.duration(file)
            ?: return RecognitionResult("Song not recognized")
        if (duration < CLIP_SECONDS + 1) return RecognitionResult("Song not recognized")

        // Search each excerpt: cut it, fingerprint it, ask Shazam.
        val client = RecognitionClient()
        var firstError: String? = null
        val answers = mutableListOf<SongTags?>()
        for ((i, fraction) in POSITIONS.take(SAMPLES).withIndex()) {
            if (isCancelled()) return RecognitionResult("")
            val start = (duration * fraction - CLIP_SECONDS / 2.0)
                .coerceIn(0.0, duration - CLIP_SECONDS)
            val raw = File(workDir, "clip_$i.pcm")
            val cut = ffmpeg.run(
                "-y", "-v", "error",
                "-ss", "%.2f".format(java.util.Locale.US, start),
                "-t", CLIP_SECONDS.toString(),
                "-i", file.absolutePath,
                "-vn", "-ac", "1", "-ar", "16000", "-f", "s16le", "-c:a", "pcm_s16le",
                raw.absolutePath,
            ).first == 0 && raw.length() > 0
            if (!cut) {
                answers.add(null)
                continue
            }
            answers.add(try {
                val samples = Pcm.bytesToShorts(raw.readBytes())
                val signature = SignatureGenerator().generateSignature(samples)
                client.recognise(signature, samples.size)?.let { parse(it) }
            } catch (e: Throwable) {
                if (firstError == null) firstError = e.message ?: e.javaClass.simpleName
                null
            } finally {
                raw.delete()
            })
        }
        if (isCancelled()) return RecognitionResult("")

        if (answers.all { it == null } && firstError != null) {
            return RecognitionResult("Recognition failed: $firstError")
        }

        // Majority vote: more than 50% of the searches must give the same song.
        val total = SAMPLES // searches that did not answer count as "no agreement"
        val groups = answers.filterNotNull().groupBy { norm(it.artist) + "|" + norm(it.title) }
        val best = groups.values.maxByOrNull { it.size }
        if (best == null || best.size * 2 <= total) return RecognitionResult("Song not recognized")

        val tags = SongTags(
            title = best.first().title,
            artist = best.first().artist,
            album = mostCommon(best.mapNotNull { it.album }),
            date = mostCommon(best.mapNotNull { it.date }),
        )

        // Write the metadata (the original file is kept if anything goes wrong).
        return if (writeTags(ffmpeg, file, tags, workDir)) {
            RecognitionResult("Tags: ${tags.artist} – ${tags.title}")
        } else {
            RecognitionResult("Song recognized but tags could not be written")
        }
    }

    /** Reads title, artist, album and release year from a Shazam discovery response; null if no match. */
    private fun parse(json: String): SongTags? {
        val track = JSONObject(json).optJSONObject("track") ?: return null
        val title = track.optString("title").trim()
        val artist = track.optString("subtitle").trim()
        if (title.isEmpty() || artist.isEmpty()) return null
        var album: String? = null
        var date: String? = null
        val sections = track.optJSONArray("sections")
        if (sections != null) {
            for (i in 0 until sections.length()) {
                val meta = sections.optJSONObject(i)?.optJSONArray("metadata") ?: continue
                for (j in 0 until meta.length()) {
                    val item = meta.optJSONObject(j) ?: continue
                    val text = item.optString("text").trim().ifEmpty { null }
                    when (item.optString("title").lowercase()) {
                        "album" -> album = album ?: text
                        "released" -> date = date ?: text
                    }
                }
            }
        }
        return SongTags(title, artist, album, date)
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "")

    private fun mostCommon(values: List<String>): String? =
        values.filter { it.isNotBlank() }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

    private fun writeTags(ffmpeg: FfmpegRunner, file: File, tags: SongTags, workDir: File): Boolean {
        val ext = file.extension.lowercase()
        val out = File(workDir, "tagged.$ext")
        val args = mutableListOf(
            "-y", "-v", "error",
            "-i", file.absolutePath,
            "-map", "0", "-c", "copy", "-map_metadata", "0",
            "-metadata", "title=${tags.title}",
            "-metadata", "artist=${tags.artist}",
        )
        tags.album?.let { args += listOf("-metadata", "album=$it") }
        tags.date?.let { args += listOf("-metadata", "date=$it") }
        if (ext == "mp3") args += listOf("-id3v2_version", "3")
        args += out.absolutePath
        val ok = ffmpeg.run(*args.toTypedArray()).first == 0 && out.length() > 0
        if (!ok) {
            out.delete()
            return false
        }
        val name = file.name
        val parent = file.parentFile
        return file.delete() && out.renameTo(File(parent, name))
    }
}

/** Runs the ffmpeg binary that youtubedl-android ships, with the same environment the library uses. */
class FfmpegRunner(context: Context) {
    private val bin = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
    private val libPath: String = run {
        val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
        File(packages, "python/usr/lib").absolutePath + ":" + File(packages, "ffmpeg/usr/lib").absolutePath
    }
    private val tmpDir = context.cacheDir.absolutePath

    /** Returns exit code and the combined stdout/stderr text. */
    fun run(vararg args: String): Pair<Int, String> {
        val pb = ProcessBuilder(listOf(bin.absolutePath) + args)
        pb.environment()["LD_LIBRARY_PATH"] = libPath
        pb.environment()["TMPDIR"] = tmpDir
        pb.redirectErrorStream(true)
        return try {
            val p = pb.start()
            val text = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor() to text
        } catch (e: Throwable) {
            -1 to (e.message ?: "")
        }
    }

    /** Duration in seconds, read from the "Duration:" line ffmpeg prints for an input file. */
    fun duration(file: File): Double? {
        val text = run("-hide_banner", "-i", file.absolutePath).second
        val m = Regex("""Duration:\s*(\d+):(\d+):(\d+(?:\.\d+)?)""").find(text) ?: return null
        val (h, min, s) = m.destructured
        return h.toDouble() * 3600 + min.toDouble() * 60 + s.toDouble()
    }
}
