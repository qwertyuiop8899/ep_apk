package com.streamvix.extractors

import com.streamvix.models.ExtractionResult
import com.streamvix.models.ExtractorError
import com.streamvix.utils.HttpClientProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import java.util.logging.Logger

/**
 * Streamtape URL extractor.
 * Estrae URL di download diretto da pagine Streamtape.
 */
class StreamtapeExtractor : Extractor {
    private val logger = Logger.getLogger(StreamtapeExtractor::class.java.name)

    private val baseHeaders = mapOf(
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    )

    override fun matches(url: String): Boolean {
        val lowerUrl = url.lowercase()
        return "streamtape.com" in lowerUrl || "streamtape.to" in lowerUrl || "streamtape.net" in lowerUrl
    }

    override suspend fun extract(url: String): ExtractionResult {
        if (!matches(url)) {
            throw ExtractorError("Not a valid Streamtape URL")
        }

        logger.info("Starting Streamtape extraction for: $url")

        try {
            val response = HttpClientProvider.client.get(url) {
                baseHeaders.forEach { (key, value) -> header(key, value) }
            }

            val html = response.bodyAsText()

            // Estrai tutti i match con pattern id=...
            val pattern = Regex("""id=([^'&"]+)""")
            val matches = pattern.findAll(html).map { it.groupValues[1] }.toList()

            if (matches.isEmpty()) {
                throw ExtractorError("Failed to extract URL components")
            }

            // Cerca il pattern corretto con ip=
            var finalUrl: String? = null

            // Logica: cerca due match consecutivi uguali con ip=
            for (i in 1 until matches.size) {
                if (matches[i - 1] == matches[i] && "ip=" in matches[i]) {
                    finalUrl = "https://streamtape.com/get_video?id=${matches[i]}"
                    break
                }
            }

            // Fallback: prendi l'ultimo match con ip=
            if (finalUrl == null) {
                val matchWithIp = matches.lastOrNull { "ip=" in it }
                if (matchWithIp != null) {
                    finalUrl = "https://streamtape.com/get_video?id=$matchWithIp"
                }
            }

            if (finalUrl == null) {
                throw ExtractorError("Streamtape URL extraction failed - no valid pattern found")
            }

            logger.info("✅ Streamtape URL extracted: $finalUrl")

            val streamHeaders = baseHeaders.toMutableMap()
            streamHeaders["referer"] = url

            return ExtractionResult(
                destinationUrl = finalUrl,
                requestHeaders = streamHeaders,
                mediaflowEndpoint = "proxy_stream_endpoint"
            )

        } catch (e: ExtractorError) {
            throw e
        } catch (e: Exception) {
            logger.warning("Streamtape extraction failed: ${e.message}")
            throw ExtractorError("Streamtape extraction failed: ${e.message}")
        }
    }
}
