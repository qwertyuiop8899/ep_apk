package com.streamvix.routes

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

fun Route.rootRoutes() {
    get("/") {
        // Serve index.html
        // In a real Android app, this might be an asset or a resource string
        // For now, we assume it reads from the templates folder like app.py
        val file = File("templates/index.html")
        if (file.exists()) {
            call.respondFile(file)
        } else {
            call.respondText("Index file not found", status = io.ktor.http.HttpStatusCode.NotFound)
        }
    }

    get("/builder") {
        val file = File("templates/builder.html")
        if (file.exists()) {
            call.respondFile(file)
        } else {
            call.respondText("Builder file not found", status = io.ktor.http.HttpStatusCode.NotFound)
        }
    }
    
    get("/favicon.ico") {
        val file = File("static/favicon.ico")
        if (file.exists()) {
            call.respondFile(file)
        } else {
            call.respondText("Favicon not found", status = io.ktor.http.HttpStatusCode.NotFound)
        }
    }
}
