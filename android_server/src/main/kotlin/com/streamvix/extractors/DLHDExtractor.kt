package com.streamvix.extractors

import com.streamvix.models.ExtractionResult
import com.streamvix.models.ExtractorError
import com.streamvix.utils.HttpClientProvider
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import java.util.logging.Logger

class DLHDExtractor : Extractor {
    private val logger = Logger.getLogger(DLHDExtractor::class.java.name)

    private val channelIdPattern = Regex("id=(\\d+)")
    private val playerLinkPattern = Regex("""<button[^>]+onclick="openPlayer\('([^']+)'\)""")
    private val iframePattern = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
    private val lovecdnPattern = Regex("lovecdn\\.ru")
    private val channelKeyPattern = Regex("channelKey:\\s*\"([^\"]+)\"")
    private val authTokenPattern = Regex("AUTH_TOKEN:\\s*\"([^\"]+)\"")
    private val authCountryPattern = Regex("AUTH_COUNTRY:\\s*\"([^\"]+)\"")
    private val authTimestampPattern = Regex("AUTH_TS:\\s*(\\d+)")
    private val authExpiryPattern = Regex("AUTH_EXPIRY:\\s*(\\d+)")
    private val serverKeyLookup = mapOf(
        "au" to "VkxXb240", "de" to "V2xoU01qQXk=", "ro" to "VFdVNU5FWm9aQT09",
        "us" to "VjJ0aFNrMUdhSGMw", "uk" to "VkdoaFRsQnZjZz09", "fr" to "V1ZSS2VHVkhhSGM0",
        "ca" to "Vm10amVscGxXa2h3TUE9PQ=="
    )

    override fun matches(url: String): Boolean {
        return "embedme.top" in url || "webhdplayer.site" in url || "daddylivehd.com" in url || "daddyhd.com" in url
    }

    override suspend fun extract(url: String): ExtractionResult {
        if (!matches(url)) {
            throw ExtractorError("Not a valid DLHD URL")
        }

        logger.info("Starting DLHD extraction for URL: $url")

        // Extract channel ID from URL
        val channelId = channelIdPattern.find(url)?.groupValues?.getOrNull(1)
            ?: throw ExtractorError("Could not extract channel ID from URL")

        logger.info("Extracted channel ID: $channelId")

        // Fetch watch.php page
        val watchUrl = "https://webhdplayer.site/watch.php?id=$channelId"
        val watchPageContent = try {
            HttpClientProvider.client.get(watchUrl) {
                header("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                header("referer", "https://webhdplayer.site/")
            }.bodyAsText()
        } catch (e: Exception) {
            logger.warning("Failed to fetch watch.php: ${e.message}")
            throw ExtractorError("Failed to fetch watch page: ${e.message}")
        }

        // Extract all player links from buttons
        val playerLinks = playerLinkPattern.findAll(watchPageContent)
            .map { it.groupValues[1] }
            .toList()

        if (playerLinks.isEmpty()) {
            logger.warning("No player links found in watch.php")
            throw ExtractorError("No player links found")
        }

        logger.info("Found ${playerLinks.size} player links")

        // Try each player link until one works
        for ((index, playerLink) in playerLinks.withIndex()) {
            logger.info("Trying player link ${index + 1}/${playerLinks.size}: $playerLink")

            try {
                // Fetch iframe page
                val iframeContent = HttpClientProvider.client.get(playerLink) {
                    header("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    header("referer", watchUrl)
                }.bodyAsText()

                // Extract nested iframes
                val iframes = iframePattern.findAll(iframeContent)
                    .map { it.groupValues[1] }
                    .toList()

                if (iframes.isEmpty()) {
                    logger.info("No iframes found in player $playerLink, trying next...")
                    continue
                }

                // Try each iframe
                for (iframeUrl in iframes) {
                    val fullIframeUrl = if (iframeUrl.startsWith("http")) {
                        iframeUrl
                    } else {
                        "https://webhdplayer.site$iframeUrl"
                    }

                    logger.info("Testing iframe: $fullIframeUrl")

                    try {
                        val iframeContent2 = HttpClientProvider.client.get(fullIframeUrl) {
                            header("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                            header("referer", playerLink)
                        }.bodyAsText()

                        // Check if lovecdn.ru iframe
                        val isLovecdn = lovecdnPattern.containsMatchIn(iframeContent2)

                        if (isLovecdn) {
                            logger.info("Detected lovecdn.ru iframe, using alternative extraction method")
                            val streamUrl = extractLovecdnStream(iframeContent2, fullIframeUrl)
                            if (streamUrl != null) {
                                logger.info("Successfully extracted stream URL from lovecdn: $streamUrl")
                                return ExtractionResult(
                                    destinationUrl = streamUrl,
                                    requestHeaders = mapOf(
                                        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                        "referer" to fullIframeUrl,
                                        "origin" to "https://lovecdn.ru"
                                    ),
                                    mediaflowEndpoint = "proxy_stream_endpoint"
                                )
                            }
                        } else {
                            // Standard extraction
                            val streamUrl = extractStandardStream(iframeContent2, fullIframeUrl)
                            if (streamUrl != null) {
                                logger.info("Successfully extracted stream URL: $streamUrl")
                                return ExtractionResult(
                                    destinationUrl = streamUrl,
                                    requestHeaders = mapOf(
                                        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                        "referer" to fullIframeUrl
                                    ),
                                    mediaflowEndpoint = "proxy_stream_endpoint"
                                )
                            }
                        }
                    } catch (e: Exception) {
                        logger.warning("Failed to process iframe $fullIframeUrl: ${e.message}")
                        continue
                    }
                }
            } catch (e: Exception) {
                logger.warning("Failed to process player link $playerLink: ${e.message}")
                continue
            }
        }

        throw ExtractorError("All extraction methods failed for channel ID: $channelId")
    }

    private suspend fun extractStandardStream(htmlContent: String, referer: String): String? {
        // Extract authentication parameters
        val channelKey = channelKeyPattern.find(htmlContent)?.groupValues?.getOrNull(1)
        val authToken = authTokenPattern.find(htmlContent)?.groupValues?.getOrNull(1)
        val authCountry = authCountryPattern.find(htmlContent)?.groupValues?.getOrNull(1)
        val authTs = authTimestampPattern.find(htmlContent)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val authExpiry = authExpiryPattern.find(htmlContent)?.groupValues?.getOrNull(1)?.toIntOrNull()

        if (channelKey == null || authToken == null || authCountry == null || authTs == null || authExpiry == null) {
            logger.warning("Missing required auth parameters (channelKey: $channelKey, authToken: ${authToken != null}, country: $authCountry)")
            return null
        }

        logger.info("Extracted auth params: channelKey=$channelKey, country=$authCountry, ts=$authTs, expiry=$authExpiry")

        // Lookup server key
        val serverKey = serverKeyLookup[authCountry.lowercase()]
        if (serverKey == null) {
            logger.warning("No server key found for country: $authCountry")
            return null
        }

        // POST authentication request
        val authUrl = "https://security.newkso.ru/auth2.php"
        val authResponse = try {
            HttpClientProvider.client.submitForm(
                url = authUrl,
                formParameters = parameters {
                    append("AUTH_TOKEN", authToken)
                    append("AUTH_COUNTRY", authCountry)
                    append("AUTH_TS", authTs.toString())
                    append("AUTH_EXPIRY", authExpiry.toString())
                    append("channelKey", channelKey)
                }
            ) {
                header("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                header("referer", referer)
                header("origin", "https://webhdplayer.site")
            }.bodyAsText()
        } catch (e: Exception) {
            logger.warning("Auth request failed: ${e.message}")
            return null
        }

        logger.info("Auth response: $authResponse")

        // Construct stream URL
        val streamUrl = "https://$authCountry.newkso.ru/$serverKey/$authToken/index.css"
        return streamUrl
    }

    private fun extractLovecdnStream(htmlContent: String, referer: String): String? {
        // Alternative extraction for lovecdn.ru
        val channelKey = channelKeyPattern.find(htmlContent)?.groupValues?.getOrNull(1)
        if (channelKey == null) {
            logger.warning("No channelKey found in lovecdn iframe")
            return null
        }

        logger.info("Extracted channelKey from lovecdn: $channelKey")

        // Construct direct stream URL (lovecdn typically uses direct HLS)
        val streamUrl = "https://lovecdn.ru/live/$channelKey/index.m3u8"
        return streamUrl
    }
}
