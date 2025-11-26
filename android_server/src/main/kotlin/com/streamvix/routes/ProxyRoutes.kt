package com.streamvix.routes

import com.streamvix.extractors.ExtractorFactory
import com.streamvix.models.ExtractorError
import com.streamvix.plugins.checkPassword
import com.streamvix.services.LicenseProxy
import com.streamvix.services.StreamProxy
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.*
import java.net.URLDecoder
import java.net.URLEncoder

// Istanze globali dei servizi
val extractorFactory = ExtractorFactory()
val streamProxy = StreamProxy()
val licenseProxy = LicenseProxy()

fun Route.proxyRoutes() {
    route("/proxy") {
        get("/hls/manifest.m3u8") {
            handleProxyRequest(call)
        }
        get("/mpd/manifest.m3u8") {
            handleProxyRequest(call)
        }
        get("/stream") {
            handleProxyRequest(call)
        }
        get("/manifest.m3u8") {
            handleProxyRequest(call)
        }
    }

    // Segment proxy
    get("/segment/{segment}") {
        handleSegmentRequest(call)
    }

    // Generate URLs endpoint (compatibilità MediaFlow)
    post("/generate_urls") {
        handleGenerateUrls(call)
    }
}

fun Route.keyRoutes() {
    get("/key") {
        handleKeyRequest(call)
    }

    get("/decrypt") {
        // Placeholder per decryption lato server
        call.respondText("Decrypt endpoint - not yet implemented", status = HttpStatusCode.NotImplemented)
    }
}

fun Route.licenseRoutes() {
    route("/license") {
        get {
            licenseProxy.handleLicenseRequest(call)
        }
        post {
            licenseProxy.handleLicenseRequest(call)
        }
        options {
            call.response.headers.apply {
                append("Access-Control-Allow-Origin", "*")
                append("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                append("Access-Control-Allow-Headers", "*")
                append("Access-Control-Max-Age", "86400")
            }
            call.respond(HttpStatusCode.OK)
        }
    }
}

/**
 * Gestisce le richieste proxy principali per HLS/MPD/Stream.
 */
suspend fun handleProxyRequest(call: ApplicationCall) {
    val apiPassword = call.request.queryParameters["api_password"]
    if (!checkPassword(apiPassword)) {
        call.respondText("Unauthorized: Invalid API Password", status = HttpStatusCode.Unauthorized)
        return
    }

    val targetUrl = call.request.queryParameters["url"] ?: call.request.queryParameters["d"]

    if (targetUrl == null) {
        call.respondText("Missing url or d parameter", status = HttpStatusCode.BadRequest)
        return
    }

    val decodedUrl = try {
        URLDecoder.decode(targetUrl, "UTF-8")
    } catch (e: Exception) {
        targetUrl
    }

    val redirectStream = call.request.queryParameters["redirect_stream"]?.lowercase() != "false"

    try {
        // 1. Trova l'estrattore giusto (Vavoo, DLHD, VixSrc, etc.)
        val extractor = extractorFactory.getExtractor(decodedUrl)
        println("🔍 [DEBUG] Processing URL: $decodedUrl")
        println("   Extractor: ${extractor::class.simpleName}")

        // 2. Esegui l'estrazione
        val result = extractor.extract(decodedUrl)
        println("   Resolved Stream URL: ${result.destinationUrl}")
        println("   Stream Headers: ${result.requestHeaders}")

        // 3. Se redirect_stream è false, restituisci JSON (stile MediaFlow)
        if (!redirectStream) {
            val scheme = call.request.headers["X-Forwarded-Proto"] ?: "http"
            val host = call.request.headers["X-Forwarded-Host"] ?: call.request.host()
            val proxyBase = "$scheme://$host"

            val endpoint = when {
                ".mpd" in result.destinationUrl -> "/proxy/mpd/manifest.m3u8"
                result.mediaflowEndpoint == "proxy_stream_endpoint" -> "/proxy/stream"
                else -> "/proxy/hls/manifest.m3u8"
            }

            val encodedUrl = URLEncoder.encode(result.destinationUrl, "UTF-8")
            val headerParams = result.requestHeaders.entries.joinToString("") { (key, value) ->
                "&h_${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
            }

            var proxyUrl = "$proxyBase$endpoint?d=$encodedUrl$headerParams"
            if (apiPassword != null) {
                proxyUrl += "&api_password=$apiPassword"
            }

            val responseData = buildJsonObject {
                put("destination_url", result.destinationUrl)
                put("request_headers", buildJsonObject {
                    result.requestHeaders.forEach { (key, value) -> put(key, value) }
                })
                put("mediaflow_endpoint", result.mediaflowEndpoint ?: "hls_proxy")
                put("mediaflow_proxy_url", proxyUrl)
                putJsonObject("query_params") {}
            }

            call.respondText(responseData.toString(), ContentType.Application.Json, HttpStatusCode.OK)
            return
        }

        // 4. Costruisci headers per lo stream (includi h_ params dalla query)
        val streamHeaders = result.requestHeaders.toMutableMap()
        for ((paramName, paramValue) in call.request.queryParameters.entries()) {
            if (paramName.startsWith("h_")) {
                val headerName = paramName.substring(2)
                // Rimuovi duplicati case-insensitive
                streamHeaders.keys.filter { it.equals(headerName, ignoreCase = true) }.forEach {
                    streamHeaders.remove(it)
                }
                streamHeaders[headerName] = paramValue
            }
        }

        // 5. Proxy dello stream
        val scheme = call.request.headers["X-Forwarded-Proto"] ?: "http"
        val host = call.request.headers["X-Forwarded-Host"] ?: call.request.host()
        val proxyBase = "$scheme://$host"

        streamProxy.proxyStream(
            call = call,
            streamUrl = result.destinationUrl,
            streamHeaders = streamHeaders,
            proxyBase = proxyBase,
            originalChannelUrl = decodedUrl,
            apiPassword = apiPassword
        )

    } catch (e: ExtractorError) {
        println("❌ Extraction error: ${e.message}")
        call.respondText("Errore estrazione: ${e.message}", status = HttpStatusCode.InternalServerError)
    } catch (e: Exception) {
        e.printStackTrace()
        call.respondText("Errore generico: ${e.message}", status = HttpStatusCode.InternalServerError)
    }
}

/**
 * Gestisce le richieste per segmenti .ts/.m4s.
 */
suspend fun handleSegmentRequest(call: ApplicationCall) {
    val segment = call.parameters["segment"]
    val baseUrl = call.request.queryParameters["base_url"]

    if (baseUrl == null) {
        call.respondText("Base URL missing", status = HttpStatusCode.BadRequest)
        return
    }

    val decodedBaseUrl = try {
        URLDecoder.decode(baseUrl, "UTF-8")
    } catch (e: Exception) {
        baseUrl
    }

    // Costruisci URL del segmento
    val segmentUrl = when {
        listOf(".mp4", ".m4s", ".ts", ".m4i", ".m4a", ".m4v").any { decodedBaseUrl.contains(it) } -> decodedBaseUrl
        decodedBaseUrl.endsWith("/") -> "$decodedBaseUrl$segment"
        else -> "${decodedBaseUrl.substringBeforeLast("/")}/$segment"
    }

    // Estrai headers dai parametri h_
    val headers = mutableMapOf(
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
        "referer" to decodedBaseUrl
    )
    for ((paramName, paramValue) in call.request.queryParameters.entries()) {
        if (paramName.startsWith("h_")) {
            headers[paramName.substring(2).replace("_", "-")] = paramValue
        }
    }

    streamProxy.proxySegment(call, segmentUrl, headers, segment ?: "segment.ts")
}

/**
 * Gestisce le richieste per chiavi AES-128.
 */
suspend fun handleKeyRequest(call: ApplicationCall) {
    val apiPassword = call.request.queryParameters["api_password"]
    if (!checkPassword(apiPassword)) {
        call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
        return
    }

    // Chiave statica
    val staticKey = call.request.queryParameters["static_key"]
    if (staticKey != null) {
        licenseProxy.handleStaticKey(call)
        return
    }

    // Proxy chiave remota
    val keyUrl = call.request.queryParameters["key_url"]
    if (keyUrl == null) {
        call.respondText("Missing key_url or static_key parameter", status = HttpStatusCode.BadRequest)
        return
    }

    val decodedKeyUrl = try {
        URLDecoder.decode(keyUrl, "UTF-8")
    } catch (e: Exception) {
        keyUrl
    }

    // Estrai headers dai parametri h_
    val headers = mutableMapOf<String, String>()
    for ((paramName, paramValue) in call.request.queryParameters.entries()) {
        if (paramName.startsWith("h_")) {
            val headerName = paramName.substring(2).replace("_", "-")
            // Ignora header Range per le chiavi
            if (headerName.lowercase() != "range") {
                headers[headerName] = paramValue
            }
        }
    }

    streamProxy.proxyKey(call, decodedKeyUrl, headers)
}

/**
 * Gestisce richieste generate_urls (compatibilità MediaFlow).
 */
suspend fun handleGenerateUrls(call: ApplicationCall) {
    try {
        val data = call.receive<JsonObject>()

        // Verifica password
        val reqPassword = data["api_password"]?.jsonPrimitive?.contentOrNull
        val apiPassword = call.request.queryParameters["api_password"]
        if (!checkPassword(reqPassword) && !checkPassword(apiPassword)) {
            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
            return
        }

        val urlsToProcess = data["urls"]?.jsonArray ?: JsonArray(emptyList())

        val scheme = call.request.headers["X-Forwarded-Proto"] ?: "http"
        val host = call.request.headers["X-Forwarded-Host"] ?: call.request.host()
        val proxyBase = "$scheme://$host"

        val generatedUrls = buildJsonArray {
            for (item in urlsToProcess) {
                val itemObj = item.jsonObject
                val destUrl = itemObj["destination_url"]?.jsonPrimitive?.contentOrNull ?: continue
                var endpoint = itemObj["endpoint"]?.jsonPrimitive?.contentOrNull ?: "/proxy/stream"
                val reqHeaders = itemObj["request_headers"]?.jsonObject ?: buildJsonObject {}

                if (!endpoint.startsWith("/")) {
                    endpoint = "/$endpoint"
                }

                val encodedUrl = URLEncoder.encode(destUrl, "UTF-8")
                val params = mutableListOf("d=$encodedUrl")

                for ((key, value) in reqHeaders) {
                    val encodedKey = URLEncoder.encode(key, "UTF-8")
                    val encodedValue = URLEncoder.encode(value.jsonPrimitive.content, "UTF-8")
                    params.add("h_$encodedKey=$encodedValue")
                }

                val finalUrl = "$proxyBase$endpoint?${params.joinToString("&")}"
                addJsonObject {
                    put("original_url", destUrl)
                    put("proxy_url", finalUrl)
                }
            }
        }

        call.respondText(generatedUrls.toString(), ContentType.Application.Json, HttpStatusCode.OK)

    } catch (e: Exception) {
        call.respondText("Error: ${e.message}", status = HttpStatusCode.InternalServerError)
    }
}
