package com.streamvix.plugins

import com.streamvix.routes.*
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.ktor.server.http.content.*
import java.io.File

fun Application.configureRouting() {
    routing {
        // Static files (templates e assets)
        staticFiles("/static", File("static"))
        staticFiles("/templates", File("templates"))
        
        // Main routes
        rootRoutes()       // /, /builder, /info, /favicon.ico
        proxyRoutes()      // /proxy/*, /segment/*, /generate_urls
        infoRoutes()       // /api/info
        playlistRoutes()   // /playlist
        extractorRoutes()  // /extractor (compatibilità MediaFlow)
        keyRoutes()        // /key, /decrypt
        licenseRoutes()    // /license
    }
}
