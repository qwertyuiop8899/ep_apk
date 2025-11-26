package com.streamvix.services

import com.streamvix.utils.HttpClientProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.utils.io.*
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.logging.Logger

/**
 * Servizio per gestire il proxy di stream video/audio.
 * Supporta:
 * - Proxy di manifest HLS con riscrittura URL
 * - Proxy di manifest MPD con conversione HLS
 * - Proxy di segmenti .ts/.m4s
 * - Proxy di chiavi AES-128
 * - Streaming diretto
 */
class StreamProxy {
    private val logger = Logger.getLogger(StreamProxy::class.java.name)
    private val manifestRewriter = ManifestRewriter()
    private val mpdConverter = MpdToHlsConverter()

    /**
     * Esegue il proxy di uno stream con gestione automatica di manifest e contenuti.
     */
    suspend fun proxyStream(
        call: ApplicationCall,
        streamUrl: String,
        streamHeaders: Map<String, String>,
        proxyBase: String,
        originalChannelUrl: String = "",
        apiPassword: String? = null
    ) {
        try {
            val headers = streamHeaders.toMutableMap()

            // Passa attraverso alcuni headers del client
            listOf("range", "if-none-match", "if-modified-since").forEach { header ->
                call.request.headers[header]?.let { headers[header] = it }
            }

            // Normalizza headers critici in Title-Case
            normalizeHeaders(headers)

            val response = HttpClientProvider.client.get(streamUrl) {
                headers.forEach { (key, value) -> header(key, value) }
            }

            val contentType = response.contentType()?.toString() ?: ""
            logger.info("Upstream Response: ${response.status} [$contentType]")

            when {
                // Manifest HLS
                "mpegurl" in contentType.lowercase() ||
                        streamUrl.endsWith(".m3u8") ||
                        (streamUrl.endsWith(".css") && "newkso.ru" in streamUrl) -> {

                    val manifestContent = response.bodyAsText()
                    val rewrittenManifest = manifestRewriter.rewriteHlsManifest(
                        manifestContent, streamUrl, proxyBase, streamHeaders, originalChannelUrl, apiPassword
                    )

                    call.respondText(
                        rewrittenManifest,
                        ContentType.parse("application/vnd.apple.mpegurl"),
                        HttpStatusCode.OK
                    )
                }

                // Manifest MPD (DASH)
                "dash+xml" in contentType.lowercase() || streamUrl.endsWith(".mpd") -> {
                    handleMpdManifest(call, response, streamUrl, proxyBase, streamHeaders, apiPassword)
                }

                // Streaming normale per altri contenuti
                else -> {
                    streamContent(call, response, streamUrl)
                }
            }

        } catch (e: Exception) {
            logger.warning("Errore proxy stream: ${e.message}")
            call.respondText("Errore stream: ${e.message}", status = HttpStatusCode.InternalServerError)
        }
    }

    /**
     * Gestisce manifest MPD con supporto conversione HLS.
     */
    private suspend fun handleMpdManifest(
        call: ApplicationCall,
        response: HttpResponse,
        streamUrl: String,
        proxyBase: String,
        streamHeaders: Map<String, String>,
        apiPassword: String?
    ) {
        val manifestContent = response.bodyAsText()

        // Recupera parametri dalla richiesta
        val clearKeyParam = call.request.queryParameters["clearkey"]
            ?: run {
                val keyId = call.request.queryParameters["key_id"]
                val key = call.request.queryParameters["key"]
                if (keyId != null && key != null) "$keyId:$key" else null
            }

        val reqFormat = call.request.queryParameters["format"]
        val repId = call.request.queryParameters["rep_id"]

        // Costruisci parametri per sotto-richieste
        val headerParams = buildHeaderParams(streamHeaders, apiPassword, clearKeyParam)

        // Conversione MPD -> HLS
        if (reqFormat == "hls" || (call.request.uri.endsWith(".m3u8") && reqFormat != "mpd")) {
            val hlsContent = if (repId != null) {
                mpdConverter.convertMediaPlaylist(manifestContent, repId, proxyBase, streamUrl, headerParams, clearKeyParam)
            } else {
                mpdConverter.convertMasterPlaylist(manifestContent, proxyBase, streamUrl, headerParams)
            }

            call.respondText(
                hlsContent,
                ContentType.parse("application/vnd.apple.mpegurl"),
                HttpStatusCode.OK
            )
        } else {
            // MPD nativo riscritto
            val rewrittenManifest = manifestRewriter.rewriteMpdManifest(
                manifestContent, streamUrl, proxyBase, streamHeaders, clearKeyParam, apiPassword
            )

            call.respondText(
                rewrittenManifest,
                ContentType.parse("application/dash+xml"),
                HttpStatusCode.OK
            )
        }
    }

    /**
     * Proxy per segmenti video/audio.
     */
    suspend fun proxySegment(
        call: ApplicationCall,
        segmentUrl: String,
        headers: Map<String, String>,
        segmentName: String
    ) {
        try {
            // Passa headers del client
            val requestHeaders = headers.toMutableMap()
            listOf("range", "if-none-match", "if-modified-since").forEach { header ->
                call.request.headers[header]?.let { requestHeaders[header] = it }
            }

            val response = HttpClientProvider.client.get(segmentUrl) {
                requestHeaders.forEach { (key, value) -> header(key, value) }
            }

            // Headers per la risposta
            val responseHeaders = mutableMapOf(
                "Content-Type" to "video/MP2T",
                "Content-Disposition" to """attachment; filename="$segmentName"""",
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
                "Access-Control-Allow-Headers" to "Range, Content-Type"
            )

            // Copia headers rilevanti dalla risposta upstream
            listOf("content-length", "content-range", "accept-ranges", "last-modified", "etag").forEach { header ->
                response.headers[header]?.let { responseHeaders[header] = it }
            }

            call.response.headers.apply {
                responseHeaders.forEach { (key, value) -> append(key, value) }
            }

            call.respondBytes(
                response.readBytes(),
                ContentType.parse("video/MP2T"),
                response.status
            )

        } catch (e: Exception) {
            logger.warning("Errore proxy segmento: ${e.message}")
            call.respondText("Errore segmento: ${e.message}", status = HttpStatusCode.InternalServerError)
        }
    }

    /**
     * Proxy per chiavi AES-128.
     */
    suspend fun proxyKey(
        call: ApplicationCall,
        keyUrl: String,
        headers: Map<String, String>
    ) {
        try {
            // Rimuovi header Range (le chiavi sono piccole e non supportano range)
            val requestHeaders = headers.filterKeys { it.lowercase() != "range" }

            logger.info("🔑 Fetching AES key from: $keyUrl")

            val response = HttpClientProvider.client.get(keyUrl) {
                requestHeaders.forEach { (key, value) -> header(key, value) }
            }

            if (response.status == HttpStatusCode.OK || response.status == HttpStatusCode.PartialContent) {
                val keyData = response.readBytes()
                logger.info("✅ AES key fetched: ${keyData.size} bytes")

                call.response.headers.apply {
                    append("Access-Control-Allow-Origin", "*")
                    append("Access-Control-Allow-Headers", "*")
                    append("Cache-Control", "no-cache, no-store, must-revalidate")
                }

                call.respondBytes(keyData, ContentType.Application.OctetStream, HttpStatusCode.OK)
            } else {
                logger.warning("Key fetch failed: ${response.status}")
                call.respondText("Key fetch failed: ${response.status}", status = response.status)
            }

        } catch (e: Exception) {
            logger.warning("Errore fetch chiave AES: ${e.message}")
            call.respondText("Key error: ${e.message}", status = HttpStatusCode.InternalServerError)
        }
    }

    /**
     * Stream diretto del contenuto.
     */
    private suspend fun streamContent(call: ApplicationCall, response: HttpResponse, streamUrl: String) {
        val responseHeaders = mutableMapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, HEAD, OPTIONS",
            "Access-Control-Allow-Headers" to "Range, Content-Type"
        )

        // Copia headers dalla risposta upstream
        listOf("content-type", "content-length", "content-range", "accept-ranges", "last-modified", "etag").forEach { header ->
            response.headers[header]?.let { responseHeaders[header] = it }
        }

        // Forza Content-Type per .ts
        if (streamUrl.endsWith(".ts") && "video/mp2t" !in (responseHeaders["content-type"] ?: "").lowercase()) {
            responseHeaders["Content-Type"] = "video/MP2T"
        }

        call.response.headers.apply {
            responseHeaders.forEach { (key, value) -> append(key, value) }
        }

        call.respondBytes(response.readBytes(), status = response.status)
    }

    private fun normalizeHeaders(headers: MutableMap<String, String>) {
        val keysToNormalize = listOf("user-agent" to "User-Agent", "referer" to "Referer", "origin" to "Origin", "authorization" to "Authorization")
        for ((lower, titleCase) in keysToNormalize) {
            headers[lower]?.let { value ->
                headers.remove(lower)
                headers[titleCase] = value
            }
        }
    }

    private fun buildHeaderParams(streamHeaders: Map<String, String>, apiPassword: String?, clearKeyParam: String?): String {
        val params = StringBuilder()

        for ((key, value) in streamHeaders) {
            val encodedKey = URLEncoder.encode(key, "UTF-8")
            val encodedValue = URLEncoder.encode(value, "UTF-8")
            params.append("&h_$encodedKey=$encodedValue")
        }

        if (apiPassword != null) {
            params.append("&api_password=$apiPassword")
        }

        if (clearKeyParam != null) {
            params.append("&clearkey=$clearKeyParam")
        }

        return params.toString()
    }
}
