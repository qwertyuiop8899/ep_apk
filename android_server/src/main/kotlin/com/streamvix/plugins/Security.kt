package com.streamvix.plugins

import io.ktor.server.application.*

fun Application.configureSecurity() {
    // TODO: Implement API Password check logic here if needed globally, 
    // or implement it as a function to call within routes.
}

fun checkPassword(password: String?): Boolean {
    // Logic from app.py: check_password(request)
    // In app.py it checks os.environ.get("API_PASSWORD")
    val envPassword = System.getenv("API_PASSWORD")
    if (envPassword.isNullOrEmpty()) return true
    return password == envPassword
}
