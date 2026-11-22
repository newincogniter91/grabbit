package io.github.hitenpatel.songprint

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Generates Shazam-compatible audio signatures from raw PCM audio.
 *
 * Pure Kotlin — no JVM-only APIs (no java.io / java.nio / java.util.zip), so the
 * source compiles unchanged on any Kotlin Multiplatform target.
 *
 * Algorithm:
 * 1. Feed 16kHz mono 16-bit PCM samples
 * 2. Windowed FFT (2048-point, hop 128) → magnitude spectrogram
 * 3. Peak spreading (frequency + time smoothing)
 * 4. Peak recognition (local maxima with interpolation)
 * 5. Binary signature encoding (the format Shazam's clients send)
 *
 * The signature format was originally reverse-engineered by the SongRec project
 * (https://github.com/marin-m/SongRec); this is an independent Kotlin implementation
 * of that documented format.
 */
class SignatureGenerator {

    companion object {
        const val SAMPLE_RATE = 16000
        const val FFT_SIZE = 2048
        const val HOP_SIZE = 128
        const val FFT_OUTPUT_SIZE = FFT_SIZE / 2 + 1   // 1025
        const val MAX_TIME_SECONDS = 3.1
        const val MAX_PEAKS = 255
        private const val RING_SIZE = 256

        private val HANNING_WINDOW = DoubleArray(FFT_SIZE) { i ->
            0.5 * (1.0 - cos(2.0 * PI * i / (FFT_SIZE - 1)))
        }
    }

    data class Peak(
        val fftPassNumber: Int,
        val peakMagnitude: Int,
        val correctedFrequencyBin: Int,
    )

    private val sampleRing = DoubleArray(FFT_SIZE)
    private var sampleRingPos = 0

    private val fftOutputs = Array(RING_SIZE) { DoubleArray(FFT_OUTPUT_SIZE) }
    private var fftOutPos = 0
    private var fftOutCount = 0

    private val spreadOutputs = Array(RING_SIZE) { DoubleArray(FFT_OUTPUT_SIZE) }
    private var spreadOutPos = 0
    private var spreadOutCount = 0

    private val peaks = Array(4) { mutableListOf<Peak>() }
    private var numberSamples = 0

    /**
     * Generate a binary signature from 16kHz mono 16-bit PCM samples.
     * See [Pcm.resampleToMono16k] for converting arbitrary PCM to this format.
     */
    fun generateSignature(monoSamples16k: ShortArray): ByteArray {
        numberSamples = 0
        peaks.forEach { it.clear() }
        sampleRing.fill(0.0)
        sampleRingPos = 0
        fftOutputs.forEach { it.fill(0.0) }
        fftOutPos = 0
        fftOutCount = 0
        spreadOutputs.forEach { it.fill(0.0) }
        spreadOutPos = 0
        spreadOutCount = 0

        var offset = 0
        while (offset + HOP_SIZE <= monoSamples16k.size) {
            val totalPeaks = peaks.sumOf { it.size }
            if (numberSamples.toDouble() / SAMPLE_RATE >= MAX_TIME_SECONDS && totalPeaks >= MAX_PEAKS) break

            for (i in 0 until HOP_SIZE) {
                sampleRing[sampleRingPos] = monoSamples16k[offset + i].toDouble()
                sampleRingPos = (sampleRingPos + 1) % FFT_SIZE
            }
            numberSamples += HOP_SIZE

            doFft()
            doPeakSpreading()
            if (spreadOutCount >= 46) doPeakRecognition()

            offset += HOP_SIZE
        }

        return encodeSignature()
    }

    private fun doFft() {
        val real = DoubleArray(FFT_SIZE)
        val imag = DoubleArray(FFT_SIZE)
        for (i in 0 until FFT_SIZE) {
            real[i] = sampleRing[(sampleRingPos + i) % FFT_SIZE] * HANNING_WINDOW[i]
        }

        fft(real, imag)

        val magnitudes = DoubleArray(FFT_OUTPUT_SIZE)
        for (i in 0 until FFT_OUTPUT_SIZE) {
            magnitudes[i] = max(
                (real[i] * real[i] + imag[i] * imag[i]) / (1 shl 17).toDouble(),
                1e-10
            )
        }

        magnitudes.copyInto(fftOutputs[fftOutPos])
        fftOutPos = (fftOutPos + 1) % RING_SIZE
        fftOutCount++
    }

    private fun doPeakSpreading() {
        val origin = fftOutputs[(fftOutPos - 1 + RING_SIZE) % RING_SIZE]
        val spread = origin.copyOf()

        for (i in 0 until FFT_OUTPUT_SIZE - 2) {
            spread[i] = maxOf(spread[i], spread[i + 1], spread[i + 2])
        }

        for (pos in 0 until FFT_OUTPUT_SIZE) {
            var maxVal = spread[pos]
            for (off in intArrayOf(-1, -3, -6)) {
                val idx = (spreadOutPos + off + RING_SIZE) % RING_SIZE
                spreadOutputs[idx][pos] = max(spreadOutputs[idx][pos], maxVal)
                maxVal = max(spreadOutputs[idx][pos], maxVal)
            }
        }

        spread.copyInto(spreadOutputs[spreadOutPos])
        spreadOutPos = (spreadOutPos + 1) % RING_SIZE
        spreadOutCount++
    }

    private fun doPeakRecognition() {
        val fftMinus46 = fftOutputs[(fftOutPos - 46 + RING_SIZE) % RING_SIZE]
        val spreadMinus49 = spreadOutputs[(spreadOutPos - 49 + RING_SIZE) % RING_SIZE]
        val spreadMinus53 = spreadOutputs[(spreadOutPos - 53 + RING_SIZE) % RING_SIZE]
        val spreadMinus45 = spreadOutputs[(spreadOutPos - 45 + RING_SIZE) % RING_SIZE]

        for (binPos in 10 until min(1015, FFT_OUTPUT_SIZE - 1)) {
            if (fftMinus46[binPos] < 1.0 / 64.0) continue
            if (fftMinus46[binPos] < spreadMinus49[max(0, binPos - 1)]) continue

            var maxNeighbor = 0.0
            for (off in intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)) {
                val idx = binPos + off
                if (idx in 0 until FFT_OUTPUT_SIZE) maxNeighbor = max(maxNeighbor, spreadMinus49[idx])
            }
            if (fftMinus46[binPos] <= maxNeighbor) continue

            var maxTimeNeighbor = maxNeighbor
            for (arr in arrayOf(spreadMinus53, spreadMinus45)) {
                val idx = max(0, binPos - 1)
                if (idx < arr.size) maxTimeNeighbor = max(maxTimeNeighbor, arr[idx])
            }
            for (off in 165..200 step 7) {
                val binIdx = max(0, binPos - 1)
                maxTimeNeighbor = max(maxTimeNeighbor, spreadOutputs[(spreadOutPos + off) % RING_SIZE][binIdx])
            }
            for (off in 214..250 step 7) {
                val binIdx = max(0, binPos - 1)
                maxTimeNeighbor = max(maxTimeNeighbor, spreadOutputs[(spreadOutPos + off) % RING_SIZE][binIdx])
            }
            if (fftMinus46[binPos] <= maxTimeNeighbor) continue

            val fftNumber = spreadOutCount - 46
            val peakMag = ln(max(1.0 / 64.0, fftMinus46[binPos])) * 1477.3 + 6144.0
            val peakMagBefore = ln(max(1.0 / 64.0, fftMinus46[max(0, binPos - 1)])) * 1477.3 + 6144.0
            val peakMagAfter = ln(max(1.0 / 64.0, fftMinus46[min(FFT_OUTPUT_SIZE - 1, binPos + 1)])) * 1477.3 + 6144.0

            val variation1 = peakMag * 2.0 - peakMagBefore - peakMagAfter
            val variation2 = if (variation1 != 0.0) (peakMagAfter - peakMagBefore) * 32.0 / variation1 else 0.0
            val correctedBin = (binPos * 64.0 + variation2).toInt()

            val freqHz = correctedBin * (SAMPLE_RATE.toDouble() / 2.0 / 1024.0 / 64.0)
            val band = when {
                freqHz < 250 -> -1
                freqHz < 520 -> 0
                freqHz < 1450 -> 1
                freqHz < 3500 -> 2
                freqHz <= 5500 -> 3
                else -> -1
            }
            if (band < 0) continue

            peaks[band].add(Peak(fftNumber, peakMag.toInt(), correctedBin))
        }
    }

    private fun encodeSignature(): ByteArray {
        val contentBytes = buildByteList {
            for (band in 0..3) {
                val bandPeaks = peaks[band]
                if (bandPeaks.isEmpty()) continue

                val peakBytes = buildByteList {
                    var prevFftPass = 0
                    for (peak in bandPeaks.sortedBy { it.fftPassNumber }) {
                        val delta = peak.fftPassNumber - prevFftPass
                        if (delta >= 255) {
                            addByte(0xFF)
                            addIntLE(peak.fftPassNumber)
                            prevFftPass = peak.fftPassNumber
                        }
                        addByte((peak.fftPassNumber - prevFftPass) and 0xFF)
                        addShortLE(peak.peakMagnitude)
                        addShortLE(peak.correctedFrequencyBin)
                        prevFftPass = peak.fftPassNumber
                    }
                }

                addIntLE(0x60030040 + band)
                addIntLE(peakBytes.size)
                addAll(peakBytes)
                // 4-byte alignment padding
                repeat((4 - (peakBytes.size % 4)) % 4) { addByte(0) }
            }
        }

        // 48-byte header + 8-byte content header + content
        val totalSize = 48 + 8 + contentBytes.size
        val result = ByteArray(totalSize)

        fun putIntLE(offset: Int, value: Int) {
            result[offset] = (value and 0xFF).toByte()
            result[offset + 1] = ((value shr 8) and 0xFF).toByte()
            result[offset + 2] = ((value shr 16) and 0xFF).toByte()
            result[offset + 3] = ((value shr 24) and 0xFF).toByte()
        }

        // Header
        putIntLE(0, 0xcafe2580.toInt())
        putIntLE(4, 0)                                       // CRC32 placeholder
        putIntLE(8, contentBytes.size + 8)
        putIntLE(12, 0x94119c00.toInt())
        putIntLE(16, 0)
        putIntLE(20, 0)
        putIntLE(24, 0)
        putIntLE(28, 3 shl 27)
        putIntLE(32, 0)
        putIntLE(36, 0)
        putIntLE(40, numberSamples + (SAMPLE_RATE * 0.24).toInt())
        putIntLE(44, (15 shl 19) + 0x40000)
        // Content header
        putIntLE(48, 0x40000000)
        putIntLE(52, contentBytes.size + 8)
        // Content
        contentBytes.forEachIndexed { i, b -> result[56 + i] = b }

        // CRC32 over bytes [8, end)
        val crc = crc32(result, 8, result.size - 8)
        putIntLE(4, crc.toInt())

        return result
    }

    private fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        var j = 0
        for (i in 0 until n) {
            if (j > i) {
                val tmpR = real[i]; real[i] = real[j]; real[j] = tmpR
                val tmpI = imag[i]; imag[i] = imag[j]; imag[j] = tmpI
            }
            var m = n / 2
            while (m >= 1 && j >= m) { j -= m; m /= 2 }
            j += m
        }
        var step = 1
        while (step < n) {
            val halfStep = step
            step *= 2
            val angle = -PI / halfStep
            val wR = cos(angle); val wI = sin(angle)
            var curR = 1.0; var curI = 0.0
            for (m in 0 until halfStep) {
                var i = m
                while (i < n) {
                    val k = i + halfStep
                    val tR = curR * real[k] - curI * imag[k]
                    val tI = curR * imag[k] + curI * real[k]
                    real[k] = real[i] - tR; imag[k] = imag[i] - tI
                    real[i] += tR; imag[i] += tI
                    i += step
                }
                val tmp = curR
                curR = curR * wR - curI * wI
                curI = tmp * wI + curI * wR
            }
        }
    }

    private class ByteListBuilder {
        private val list = mutableListOf<Byte>()
        fun addByte(v: Int) { list.add((v and 0xFF).toByte()) }
        fun addShortLE(v: Int) { addByte(v); addByte(v shr 8) }
        fun addIntLE(v: Int) { addByte(v); addByte(v shr 8); addByte(v shr 16); addByte(v shr 24) }
        fun addAll(other: ByteArray) { other.forEach { list.add(it) } }
        val size: Int get() = list.size
        fun toByteArray(): ByteArray = list.toByteArray()
    }

    private fun buildByteList(block: ByteListBuilder.() -> Unit): ByteArray =
        ByteListBuilder().also(block).toByteArray()
}
