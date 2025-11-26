package com.streamvix.extractors

import com.streamvix.models.ExtractionResult
import com.streamvix.models.ExtractorError
import com.streamvix.utils.HttpClientProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import java.util.logging.Logger

/**
 * Mixdrop URL extractor.
 * Estrae URL di stream da pagine Mixdrop.
 */
class MixdropExtractor : Extractor {
    private val logger = Logger.getLogger(MixdropExtractor::class.java.name)

    private val baseHeaders = mapOf(
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    )

    override fun matches(url: String): Boolean {
        return "mixdrop" in url.lowercase()
    }

    override suspend fun extract(url: String): ExtractionResult {
        if (!matches(url)) {
            throw ExtractorError("Not a valid Mixdrop URL")
        }

        logger.info("Starting Mixdrop extraction for: $url")

        // Normalizza URL
        var normalizedUrl = url
        if ("club" in url) {
            normalizedUrl = url.replace("club", "ps").split("/2")[0]
        }

        try {
            val headers = mapOf(
                "accept-language" to "en-US,en;q=0.5",
                "referer" to normalizedUrl,
                "user-agent" to baseHeaders["user-agent"]!!
            )

            val response = HttpClientProvider.client.get(normalizedUrl) {
                headers.forEach { (key, value) -> header(key, value) }
            }

            val html = response.bodyAsText()

            // Estrai URL usando pattern MDCore.wurl
            val pattern = Regex("""MDCore\.wurl\s*=\s*"([^"]+)"""")
            val match = pattern.find(html)
                ?: throw ExtractorError("Could not find MDCore.wurl in response")

            var finalUrl = match.groupValues[1]

            // Aggiungi protocollo se mancante
            if (finalUrl.startsWith("//")) {
                finalUrl = "https:$finalUrl"
            }

            logger.info("✅ Mixdrop URL extracted: $finalUrl")

            val streamHeaders = baseHeaders.toMutableMap()
            streamHeaders["referer"] = normalizedUrl

            return ExtractionResult(
                destinationUrl = finalUrl,
                requestHeaders = streamHeaders,
                mediaflowEndpoint = "proxy_stream_endpoint"
            )

        } catch (e: ExtractorError) {
            throw e
        } catch (e: Exception) {
            logger.warning("Mixdrop extraction failed: ${e.message}")
            throw ExtractorError("Mixdrop extraction failed: ${e.message}")
        }
    }
}
