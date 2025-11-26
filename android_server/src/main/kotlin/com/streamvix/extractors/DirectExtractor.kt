package com.streamvix.extractors

import com.streamvix.models.ExtractionResult

/**
 * Fallback extractor for direct stream URLs that don't require special handling.
 * This matches all URLs and should be registered last in ExtractorFactory.
 */
class DirectExtractor : Extractor {
    override fun matches(url: String): Boolean = true // Matches everything as fallback

    override suspend fun extract(url: String): ExtractionResult {
        return ExtractionResult(
            destinationUrl = url,
            requestHeaders = mapOf(
                "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "referer" to url
            ),
            mediaflowEndpoint = "proxy_stream_endpoint"
        )
    }
}
