package com.streamvix.models

import kotlinx.serialization.Serializable

@Serializable
data class ExtractionResult(
    val destinationUrl: String,
    val requestHeaders: Map<String, String> = emptyMap(),
    val mediaflowEndpoint: String = "hls_proxy" // "hls_proxy", "proxy_stream_endpoint", etc.
)

class ExtractorError(message: String) : Exception(message)
