package com.streamvix.routes

import com.streamvix.plugins.checkPassword
import com.streamvix.services.PlaylistService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

// Istanza del servizio playlist
private val playlistService = PlaylistService()

/**
 * Rotte per il Playlist Builder.
 */
fun Route.playlistRoutes() {
    get("/playlist") {
        val urlsParam = call.request.queryParameters.getAll("url")
        val sortChannels = call.request.queryParameters["sort_channels"]?.toBoolean() ?: false
        
        if (urlsParam.isNullOrEmpty()) {
            call.respondText("Missing url parameter(s)", status = HttpStatusCode.BadRequest)
            return@get
        }

        try {
            val combinedPlaylist = playlistService.generateCombinedPlaylist(
                playlistUrls = urlsParam,
                sortChannels = sortChannels
            )
            
            call.response.headers.apply {
                append("Content-Disposition", """attachment; filename="playlist.m3u"""")
                append("Access-Control-Allow-Origin", "*")
            }
            
            call.respondText(combinedPlaylist, contentType = ContentType.parse("application/vnd.apple.mpegurl"))
        } catch (e: Exception) {
            call.respondText(
                "Failed to generate playlist: ${e.message}",
                status = HttpStatusCode.InternalServerError
            )
        }
    }
}

/**
 * Rotte per l'estrazione diretta (compatibilità MediaFlow).
 */
fun Route.extractorRoutes() {
    get("/extractor") {
        val apiPassword = call.request.queryParameters["api_password"]
        if (!checkPassword(apiPassword)) {
            call.respondText("Unauthorized", status = HttpStatusCode.Unauthorized)
            return@get
        }
        
        val url = call.request.queryParameters["url"] ?: call.request.queryParameters["d"]
        if (url == null) {
            call.respondText("Missing url or d parameter", status = HttpStatusCode.BadRequest)
            return@get
        }
        
        val redirectStream = call.request.queryParameters["redirect_stream"]?.lowercase() == "true"
        
        try {
            val decodedUrl = java.net.URLDecoder.decode(url, "UTF-8")
            val extractor = extractorFactory.getExtractor(decodedUrl)
            val result = extractor.extract(decodedUrl)
            
            if (redirectStream) {
                // Costruisci URL proxy e fai redirect
                val scheme = call.request.headers["X-Forwarded-Proto"] ?: "http"
                val host = call.request.headers["X-Forwarded-Host"] ?: call.request.host()
                val proxyBase = "$scheme://$host"
                
                val endpoint = when {
                    ".mpd" in result.destinationUrl -> "/proxy/mpd/manifest.m3u8"
                    result.mediaflowEndpoint == "proxy_stream_endpoint" -> "/proxy/stream"
                    else -> "/proxy/hls/manifest.m3u8"
                }
                
                val encodedUrl = java.net.URLEncoder.encode(result.destinationUrl, "UTF-8")
                val headerParams = result.requestHeaders.entries.joinToString("") { (key, value) ->
                    "&h_${java.net.URLEncoder.encode(key, "UTF-8")}=${java.net.URLEncoder.encode(value, "UTF-8")}"
                }
                
                var proxyUrl = "$proxyBase$endpoint?d=$encodedUrl$headerParams"
                if (apiPassword != null) {
                    proxyUrl += "&api_password=$apiPassword"
                }
                
                call.respondRedirect(proxyUrl)
            } else {
                // Restituisci JSON
                val responseData = kotlinx.serialization.json.buildJsonObject {
                    put("destination_url", result.destinationUrl)
                    put("request_headers", kotlinx.serialization.json.buildJsonObject {
                        result.requestHeaders.forEach { (key, value) -> put(key, value) }
                    })
                    put("mediaflow_endpoint", result.mediaflowEndpoint ?: "hls_proxy")
                }
                
                call.respondText(responseData.toString(), ContentType.Application.Json, HttpStatusCode.OK)
            }
            
        } catch (e: Exception) {
            call.respondText("Extraction error: ${e.message}", status = HttpStatusCode.InternalServerError)
        }
    }
}
