package com.streamvix.extractors

import com.streamvix.models.ExtractionResult
import com.streamvix.models.ExtractorError
import com.streamvix.utils.HttpClientProvider
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.logging.Logger

class VavooExtractor : Extractor {
    private val logger = Logger.getLogger(VavooExtractor::class.java.name)

    override fun matches(url: String): Boolean {
        return "vavoo.to" in url
    }

    override suspend fun extract(url: String): ExtractionResult {
        if (!matches(url)) {
            throw ExtractorError("Not a valid Vavoo URL")
        }

        val signature = getAuthSignature()
            ?: throw ExtractorError("Failed to get Vavoo authentication signature")

        val resolvedUrl = resolveVavooLink(url, signature)
            ?: throw ExtractorError("Failed to resolve Vavoo URL")

        val streamHeaders = mapOf(
            "user-agent" to "okhttp/4.11.0",
            "referer" to "https://vavoo.to/"
        )

        return ExtractionResult(
            destinationUrl = resolvedUrl,
            requestHeaders = streamHeaders,
            mediaflowEndpoint = "proxy_stream_endpoint"
        )
    }

    private suspend fun getAuthSignature(): String? {
        val headers = mapOf(
            "user-agent" to "okhttp/4.11.0",
            "accept" to "application/json",
            "content-type" to "application/json; charset=utf-8",
            "accept-encoding" to "gzip"
        )

        val currentTime = System.currentTimeMillis()
        val data = VavooPingRequest(
            token = "tosFwQCJMS8qrW_AjLoHPQ41646J5dRNha6ZWHnijoYQQQoADQoXYSo7ki7O5-CsgN4CH0uRk6EEoJ0728ar9scCRQW3ZkbfrPfeCXW2VgopSW2FWDqPOoVYIuVPAOnXCZ5g",
            reason = "app-blur",
            locale = "de",
            theme = "dark",
            metadata = VavooMetadata(
                device = VavooDevice(
                    type = "Handset",
                    brand = "google",
                    model = "Pixel",
                    name = "sdk_gphone64_arm64",
                    uniqueId = "d10e5d99ab665233"
                ),
                os = VavooOS(name = "android", version = "13"),
                app = VavooApp(platform = "android", version = "3.1.21"),
                version = VavooVersion(
                    `package` = "tv.vavoo.app",
                    binary = "3.1.21",
                    js = "3.1.21"
                )
            ),
            appFocusTime = 0,
            playerActive = false,
            playDuration = 0,
            devMode = false,
            hasAddon = true,
            castConnected = false,
            `package` = "tv.vavoo.app",
            version = "3.1.21",
            process = "app",
            firstAppStart = currentTime,
            lastAppStart = currentTime,
            ipLocation = "",
            adblockEnabled = true,
            proxy = VavooProxy(
                supported = listOf("ss", "openvpn"),
                engine = "ss",
                ssVersion = 1,
                enabled = true,
                autoServer = true,
                id = "de-fra"
            ),
            iap = VavooIAP(supported = false)
        )

        return try {
            val response = HttpClientProvider.client.post("https://www.vavoo.tv/api/app/ping") {
                headers.forEach { (key, value) -> header(key, value) }
                contentType(ContentType.Application.Json)
                setBody(data)
            }

            val jsonResponse = response.body<JsonObject>()
            val addonSig = jsonResponse["addonSig"]?.jsonPrimitive?.content

            if (addonSig != null) {
                logger.info("Successfully obtained Vavoo authentication signature")
                addonSig
            } else {
                logger.warning("No addonSig in Vavoo API response: $jsonResponse")
                null
            }
        } catch (e: Exception) {
            logger.warning("Failed to get Vavoo auth signature: ${e.message}")
            null
        }
    }

    private suspend fun resolveVavooLink(link: String, signature: String): String? {
        val headers = mapOf(
            "user-agent" to "okhttp/4.11.0",
            "accept" to "application/json",
            "content-type" to "application/json; charset=utf-8",
            "accept-encoding" to "gzip",
            "mediahubmx-signature" to signature
        )

        val data = VavooResolveRequest(
            language = "de",
            region = "AT",
            url = link,
            clientVersion = "3.1.21"
        )

        return try {
            logger.info("Attempting to resolve Vavoo URL: $link")
            val response = HttpClientProvider.client.post("https://vavoo.to/mediahubmx-resolve.json") {
                headers.forEach { (key, value) -> header(key, value) }
                contentType(ContentType.Application.Json)
                setBody(data)
            }

            val jsonElement = response.body<JsonElement>()

            // Handle both array and object responses
            val resolvedUrl = when {
                jsonElement is JsonArray && jsonElement.isNotEmpty() -> {
                    val firstItem = jsonElement[0] as? JsonObject
                    firstItem?.get("url")?.jsonPrimitive?.content
                }
                jsonElement is JsonObject -> {
                    jsonElement["url"]?.jsonPrimitive?.content
                }
                else -> null
            }

            if (resolvedUrl != null) {
                logger.info("Successfully resolved Vavoo URL to: $resolvedUrl")
                resolvedUrl
            } else {
                logger.warning("No URL found in Vavoo API response: $jsonElement")
                null
            }
        } catch (e: Exception) {
            logger.severe("Vavoo resolution failed for URL $link: ${e.message}")
            throw ExtractorError("Vavoo resolution failed: ${e.message}")
        }
    }

    @Serializable
    private data class VavooPingRequest(
        val token: String,
        val reason: String,
        val locale: String,
        val theme: String,
        val metadata: VavooMetadata,
        val appFocusTime: Long,
        val playerActive: Boolean,
        val playDuration: Long,
        val devMode: Boolean,
        val hasAddon: Boolean,
        val castConnected: Boolean,
        val `package`: String,
        val version: String,
        val process: String,
        val firstAppStart: Long,
        val lastAppStart: Long,
        val ipLocation: String,
        val adblockEnabled: Boolean,
        val proxy: VavooProxy,
        val iap: VavooIAP
    )

    @Serializable
    private data class VavooMetadata(
        val device: VavooDevice,
        val os: VavooOS,
        val app: VavooApp,
        val version: VavooVersion
    )

    @Serializable
    private data class VavooDevice(
        val type: String,
        val brand: String,
        val model: String,
        val name: String,
        val uniqueId: String
    )

    @Serializable
    private data class VavooOS(val name: String, val version: String)

    @Serializable
    private data class VavooApp(val platform: String, val version: String)

    @Serializable
    private data class VavooVersion(
        val `package`: String,
        val binary: String,
        val js: String
    )

    @Serializable
    private data class VavooProxy(
        val supported: List<String>,
        val engine: String,
        val ssVersion: Int,
        val enabled: Boolean,
        val autoServer: Boolean,
        val id: String
    )

    @Serializable
    private data class VavooIAP(val supported: Boolean)

    @Serializable
    private data class VavooResolveRequest(
        val language: String,
        val region: String,
        val url: String,
        val clientVersion: String
    )
}
