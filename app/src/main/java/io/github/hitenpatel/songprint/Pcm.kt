package io.github.hitenpatel.songprint

/** Helpers for getting arbitrary 16-bit PCM into the 16kHz mono format the generator needs. */
object Pcm {

    /** Decode 16-bit little-endian PCM bytes into samples. */
    fun bytesToShorts(pcm: ByteArray): ShortArray =
        ShortArray(pcm.size / 2) { i ->
            ((pcm[i * 2].toInt() and 0xFF) or (pcm[i * 2 + 1].toInt() shl 8)).toShort()
        }

    /**
     * Convert interleaved PCM at any sample rate / channel count to 16kHz mono
     * using channel averaging and linear-interpolation resampling.
     */
    fun resampleToMono16k(samples: ShortArray, sampleRate: Int, channels: Int): ShortArray {
        val mono = if (channels >= 2) {
            ShortArray(samples.size / channels) { i ->
                var sum = 0
                for (ch in 0 until channels) sum += samples[i * channels + ch].toInt()
                (sum / channels).toShort()
            }
        } else samples

        if (sampleRate == SignatureGenerator.SAMPLE_RATE) return mono

        val ratio = SignatureGenerator.SAMPLE_RATE.toDouble() / sampleRate
        val outputSize = (mono.size * ratio).toInt()
        val output = ShortArray(outputSize)
        for (i in 0 until outputSize) {
            val srcPos = i / ratio
            val srcIdx = srcPos.toInt()
            val frac = srcPos - srcIdx
            val s0 = mono[minOf(srcIdx, mono.size - 1)].toInt()
            val s1 = mono[minOf(srcIdx + 1, mono.size - 1)].toInt()
            output[i] = (s0 + frac * (s1 - s0)).toInt().toShort()
        }
        return output
    }
}
