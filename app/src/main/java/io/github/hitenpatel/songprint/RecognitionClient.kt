package io.github.hitenpatel.songprint

import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.util.UUID

/**
 * Thin client for the public (unofficial, undocumented) Shazam discovery endpoint.
 *
 * Sends a signature produced by [SignatureGenerator] and returns the raw JSON
 * response, or null on no-match / HTTP error. The response contains a `track`
 * object with `title`, `subtitle` (artist), `images.coverart`, `share.href` etc. —
 * parse it with the JSON library of your choice; this class deliberately has no
 * dependencies.
 *
 * This endpoint is NOT an official API. It may change or disappear without notice.
 * Do not use it for anything where that would hurt.
 */
class RecognitionClient(
    private val endpoint: String = DEFAULT_ENDPOINT,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 10_000,
) {

    companion object {
        const val DEFAULT_ENDPOINT = "https://amp.shazam.com/discovery/v5/en/GB/android/-/tag"
    }

    /**
     * @param signature binary signature from [SignatureGenerator.generateSignature]
     * @param numSamples number of 16kHz mono samples the signature was generated from
     * @return raw JSON response body, or null on non-200 response
     */
    fun recognise(signature: ByteArray, numSamples: Int): String? {
        val uuid1 = UUID.randomUUID().toString().uppercase()
        val uuid2 = UUID.randomUUID().toString().uppercase()
        val url = URI("$endpoint/$uuid1/$uuid2?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3&sharehub=true&video=v3").toURL()

        val sigBase64 = Base64.getEncoder().encodeToString(signature)
        val sampleMs = (numSamples.toDouble() / SignatureGenerator.SAMPLE_RATE * 1000).toInt()

        val body = """
        {
            "signature": {
                "uri": "data:audio/vnd.shazam.sig;base64,$sigBase64",
                "samplems": $sampleMs
            },
            "timestamp": ${System.currentTimeMillis()},
            "context": {},
            "geolocation": {}
        }
        """.trimIndent()

        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Content-Language", "en_US")

        return try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            if (connection.responseCode == 200) {
                connection.inputStream.bufferedReader().readText()
            } else {
                null
            }
        } finally {
            connection.disconnect()
        }
    }
}
