package com.streamvix.extractors

import com.streamvix.models.ExtractionResult
import com.streamvix.models.ExtractorError
import com.streamvix.utils.HttpClientProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*
import java.util.logging.Logger

/**
 * VixSrc URL extractor per risolvere link VixSrc.
 * Supporta /movie/, /tv/, e /iframe/ URLs.
 */
class VixSrcExtractor : Extractor {
    private val logger = Logger.getLogger(VixSrcExtractor::class.java.name)

    private val baseHeaders = mapOf(
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "accept-language" to "en-US,en;q=0.5",
        "accept-encoding" to "gzip, deflate",
        "connection" to "keep-alive"
    )

    override fun matches(url: String): Boolean {
        return "vixsrc.to/" in url.lowercase() &&
                (url.contains("/movie/") || url.contains("/tv/") || url.contains("/iframe/"))
    }

    override suspend fun extract(url: String): ExtractionResult {
        if (!matches(url)) {
            throw ExtractorError("Not a valid VixSrc URL")
        }

        logger.info("Starting VixSrc extraction for: $url")

        try {
            // Se l'URL è già un manifest, restituiscilo direttamente
            if ("vixsrc.to/playlist" in url) {
                logger.info("URL is already a VixSrc manifest")
                return ExtractionResult(
                    destinationUrl = url,
                    requestHeaders = baseHeaders,
                    mediaflowEndpoint = "hls_manifest_proxy"
                )
            }

            val response: String
            val version: String?

            if ("/iframe/" in url) {
                // Gestione URL iframe
                val siteUrl = url.substringBefore("/iframe")
                version = getVersion(siteUrl)

                // Prima richiesta con headers Inertia
                val iframePageContent = makeRequest(url, mapOf(
                    "x-inertia" to "true",
                    "x-inertia-version" to version
                ))

                // Cerca iframe src
                val iframeSrc = extractIframeSrc(iframePageContent)
                    ?: throw ExtractorError("No iframe found in response")

                // Seconda richiesta all'iframe
                response = makeRequest(iframeSrc, mapOf(
                    "x-inertia" to "true",
                    "x-inertia-version" to version
                ))

            } else if ("/movie/" in url || "/tv/" in url) {
                // Gestione URL diretti movie/tv
                response = makeRequest(url)
            } else {
                throw ExtractorError("Unsupported VixSrc URL type")
            }

            // Estrai parametri dallo script JavaScript
            val finalUrl = extractStreamUrl(response, url)
                ?: throw ExtractorError("Failed to extract stream URL from script")

            // Prepara headers finali
            val streamHeaders = baseHeaders.toMutableMap()
            streamHeaders["referer"] = url

            logger.info("✅ VixSrc URL extracted: $finalUrl")

            return ExtractionResult(
                destinationUrl = finalUrl,
                requestHeaders = streamHeaders,
                mediaflowEndpoint = "hls_manifest_proxy"
            )

        } catch (e: ExtractorError) {
            throw e
        } catch (e: Exception) {
            logger.warning("VixSrc extraction failed: ${e.message}")
            throw ExtractorError("VixSrc extraction failed: ${e.message}")
        }
    }

    /**
     * Ottiene la versione Inertia dal sito VixSrc.
     */
    private suspend fun getVersion(siteUrl: String): String {
        val baseUrl = "$siteUrl/request-a-title"

        val response = makeRequest(baseUrl, mapOf(
            "Referer" to "$siteUrl/",
            "Origin" to siteUrl
        ))

        // Cerca data-page nel div#app
        val dataPagePattern = Regex("""<div[^>]*id="app"[^>]*data-page="([^"]*)"[^>]*>""", RegexOption.IGNORE_CASE)
        val match = dataPagePattern.find(response)
            ?: throw ExtractorError("Cannot find version data")

        val dataPage = match.groupValues[1].replace("&quot;", "\"")

        return try {
            val json = Json.parseToJsonElement(dataPage).jsonObject
            json["version"]?.jsonPrimitive?.content
                ?: throw ExtractorError("Version not found in JSON")
        } catch (e: Exception) {
            throw ExtractorError("Failed to parse version: ${e.message}")
        }
    }

    /**
     * Estrae l'URL dell'iframe dalla pagina.
     */
    private fun extractIframeSrc(html: String): String? {
        val pattern = Regex("""<iframe[^>]*src="([^"]*)"[^>]*>""", RegexOption.IGNORE_CASE)
        return pattern.find(html)?.groupValues?.getOrNull(1)
    }

    /**
     * Estrae l'URL dello stream dallo script JavaScript.
     */
    private fun extractStreamUrl(html: String, originalUrl: String): String? {
        // Cerca il primo script nel body
        val scriptPattern = Regex("""<body[^>]*>.*?<script[^>]*>(.*?)</script>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        val scriptMatch = scriptPattern.find(html)
        val scriptContent = scriptMatch?.groupValues?.getOrNull(1)
            ?: return null

        // Estrai parametri
        val tokenMatch = Regex("""'token':\s*'(\w+)'""").find(scriptContent)
        val expiresMatch = Regex("""'expires':\s*'(\d+)'""").find(scriptContent)
        val serverUrlMatch = Regex("""url:\s*'([^']+)'""").find(scriptContent)

        if (tokenMatch == null || expiresMatch == null || serverUrlMatch == null) {
            return null
        }

        val token = tokenMatch.groupValues[1]
        val expires = expiresMatch.groupValues[1]
        val serverUrl = serverUrlMatch.groupValues[1]

        // Costruisci URL finale
        var finalUrl = if ("?b=1" in serverUrl) {
            "$serverUrl&token=$token&expires=$expires"
        } else {
            "$serverUrl?token=$token&expires=$expires"
        }

        // Verifica supporto FHD
        if ("window.canPlayFHD = true" in scriptContent) {
            finalUrl += "&h=1"
        }

        return finalUrl
    }

    /**
     * Esegue una richiesta HTTP con headers opzionali.
     */
    private suspend fun makeRequest(url: String, extraHeaders: Map<String, String> = emptyMap()): String {
        val response = HttpClientProvider.client.get(url) {
            baseHeaders.forEach { (key, value) -> header(key, value) }
            extraHeaders.forEach { (key, value) -> header(key, value) }
        }

        if (response.status.value >= 400) {
            throw ExtractorError("Request failed with status: ${response.status}")
        }

        return response.bodyAsText()
    }
}
