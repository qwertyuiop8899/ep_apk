package com.streamvix.extractors

import com.streamvix.models.ExtractionResult

interface Extractor {
    suspend fun extract(url: String): ExtractionResult
    fun matches(url: String): Boolean
}
