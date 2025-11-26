package com.streamvix.services

import com.streamvix.utils.HttpClientProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.Base64
import java.util.logging.Logger

/**
 * Servizio per gestire richieste di licenze DRM (ClearKey e proxy generico).
 */
class LicenseProxy {
    private val logger = Logger.getLogger(LicenseProxy::class.java.name)

    /**
     * Gestisce richieste di licenza DRM.
     * Supporta:
     * - ClearKey statico con parametro clearkey=kid:key
     * - Proxy verso server di licenza remoto
     */
    suspend fun handleLicenseRequest(call: ApplicationCall) {
        try {
            // 1. Modalità ClearKey Statica
            val clearKeyParam = call.request.queryParameters["clearkey"]
            if (clearKeyParam != null) {
                handleStaticClearKey(call, clearKeyParam)
                return
            }

            // 2. Modalità Proxy Licenza
            val licenseUrl = call.request.queryParameters["url"]
            if (licenseUrl != null) {
                handleLicenseProxy(call, java.net.URLDecoder.decode(licenseUrl, "UTF-8"))
                return
            }

            call.respondText("Missing url or clearkey parameter", status = HttpStatusCode.BadRequest)

        } catch (e: Exception) {
            logger.warning("License proxy error: ${e.message}")
            call.respondText("License error: ${e.message}", status = HttpStatusCode.InternalServerError)
        }
    }

    /**
     * Genera una risposta ClearKey JWK statica.
     */
    private suspend fun handleStaticClearKey(call: ApplicationCall, clearKeyParam: String) {
        logger.info("🔑 Richiesta licenza ClearKey statica: $clearKeyParam")

        try {
            val (kidHex, keyHex) = clearKeyParam.split(":")

            // Converti hex in base64url (senza padding) come richiesto da JWK
            fun hexToB64Url(hexStr: String): String {
                val bytes = hexStr.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            }

            val jwkResponse = buildJsonObject {
                putJsonArray("keys") {
                    addJsonObject {
                        put("kty", "oct")
                        put("k", hexToB64Url(keyHex))
                        put("kid", hexToB64Url(kidHex))
                        put("type", "temporary")
                    }
                }
                put("type", "temporary")
            }

            logger.info("🔑 Serving static ClearKey license for KID: $kidHex")

            call.response.headers.apply {
                append("Access-Control-Allow-Origin", "*")
                append("Access-Control-Allow-Headers", "*")
                append("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            }

            call.respondText(
                jwkResponse.toString(),
                ContentType.Application.Json,
                HttpStatusCode.OK
            )

        } catch (e: Exception) {
            logger.warning("Errore generazione ClearKey: ${e.message}")
            call.respondText("Invalid ClearKey format", status = HttpStatusCode.BadRequest)
        }
    }

    /**
     * Esegue il proxy verso un server di licenza remoto.
     */
    private suspend fun handleLicenseProxy(call: ApplicationCall, licenseUrl: String) {
        logger.info("🔐 Proxying License Request to: $licenseUrl")

        // Ricostruisci headers dai parametri h_
        val headers = mutableMapOf<String, String>()
        for ((paramName, paramValue) in call.request.queryParameters.entries()) {
            if (paramName.startsWith("h_")) {
                val headerName = paramName.substring(2).replace("_", "-")
                headers[headerName] = paramValue
            }
        }

        // Aggiungi Content-Type dalla richiesta originale
        call.request.headers["Content-Type"]?.let {
            headers["Content-Type"] = it
        }

        // Leggi body della richiesta (challenge DRM)
        val body = call.receiveChannel().toByteArray(1024 * 1024) // Max 1MB

        try {
            val response = HttpClientProvider.client.request(licenseUrl) {
                method = HttpMethod.parse(call.request.httpMethod.value)
                headers.forEach { (key, value) -> header(key, value) }
                setBody(body)
            }

            val responseBody = response.readBytes()
            logger.info("✅ License response: ${response.status} (${responseBody.size} bytes)")

            call.response.headers.apply {
                append("Access-Control-Allow-Origin", "*")
                append("Access-Control-Allow-Headers", "*")
                append("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                response.headers["Content-Type"]?.let {
                    append("Content-Type", it)
                }
            }

            call.respondBytes(responseBody, status = response.status)

        } catch (e: Exception) {
            logger.warning("License proxy failed: ${e.message}")
            call.respondText("License proxy error: ${e.message}", status = HttpStatusCode.BadGateway)
        }
    }

    /**
     * Genera una chiave statica dal parametro static_key.
     */
    suspend fun handleStaticKey(call: ApplicationCall) {
        val staticKey = call.request.queryParameters["static_key"]

        if (staticKey == null) {
            call.respondText("Missing static_key parameter", status = HttpStatusCode.BadRequest)
            return
        }

        try {
            val keyBytes = staticKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

            call.response.headers.append("Access-Control-Allow-Origin", "*")
            call.respondBytes(keyBytes, ContentType.Application.OctetStream, HttpStatusCode.OK)

        } catch (e: Exception) {
            logger.warning("Invalid static key: ${e.message}")
            call.respondText("Invalid static key", status = HttpStatusCode.BadRequest)
        }
    }
}

/**
 * Extension function per leggere ByteReadChannel in ByteArray.
 */
private suspend fun io.ktor.utils.io.ByteReadChannel.toByteArray(maxSize: Int): ByteArray {
    val buffer = ByteArray(maxSize)
    var offset = 0
    while (!isClosedForRead && offset < maxSize) {
        val read = readAvailable(buffer, offset, maxSize - offset)
        if (read == -1) break
        offset += read
    }
    return buffer.copyOf(offset)
}
