package com.streamvix.routes

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

fun Route.infoRoutes() {
    get("/info") {
        val file = File("templates/info.html")
        if (file.exists()) {
            call.respondFile(file)
        } else {
            call.respondText("Info file not found", status = io.ktor.http.HttpStatusCode.NotFound)
        }
    }

    get("/api/info") {
        val info = mapOf(
            "proxy" to "HLS Proxy Server (Kotlin/Ktor)",
            "version" to "2.5.0-android",
            "status" to "✅ Operational",
            "features" to listOf(
                "✅ Proxy HLS streams",
                "✅ AES-128 key proxying",
                "✅ Playlist building",
                "✅ Android Native Server"
            )
        )
        call.respond(info)
    }
}
