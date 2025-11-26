package com.streamvix.services

import com.streamvix.utils.HttpClientProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class PlaylistService {

    suspend fun generateCombinedPlaylist(
        playlistDefinitions: List<String>,
        baseUrl: String,
        apiPassword: String?
    ): List<String> = coroutineScope {
        val tasks = playlistDefinitions.map { definition ->
            async {
                var url = definition
                var shouldSort = false
                var shouldProxy = true

                if (url.startsWith("sort:")) {
                    shouldSort = true
                    url = url.removePrefix("sort:")
                }
                if (url.startsWith("no_proxy:")) {
                    shouldProxy = false
                    url = url.removePrefix("no_proxy:")
                }

                try {
                    val lines = downloadPlaylist(url)
                    PlaylistData(lines, shouldSort, shouldProxy, url)
                } catch (e: Exception) {
                    PlaylistData(listOf("# ERROR processing playlist $url: ${e.message}"), false, false, url, true)
                }
            }
        }

        val results = tasks.awaitAll()
        val finalLines = mutableListOf<String>()
        
        // Add Header once
        finalLines.add("#EXTM3U")

        // Process sorted playlists
        val sortedEntries = mutableListOf<Pair<List<String>, Boolean>>() // Lines, shouldProxy
        
        results.filter { it.shouldSort && !it.isError }.forEach { data ->
            val entries = parseChannelEntries(data.lines)
            entries.forEach { entry ->
                sortedEntries.add(entry to data.shouldProxy)
            }
        }

        // Sort by channel name (assumes #EXTINF is first line of entry)
        sortedEntries.sortBy { (lines, _) ->
            val extInf = lines.firstOrNull { it.startsWith("#EXTINF:") } ?: ""
            extInf.substringAfterLast(",").trim()
        }

        // Add sorted entries
        for ((lines, shouldProxy) in sortedEntries) {
            processEntry(lines, shouldProxy, baseUrl, apiPassword, finalLines)
        }

        // Process unsorted playlists
        results.filter { !it.shouldSort }.forEach { data ->
            if (data.isError) {
                finalLines.addAll(data.lines)
            } else {
                val entries = parseChannelEntries(data.lines)
                for (entry in entries) {
                    processEntry(entry, data.shouldProxy, baseUrl, apiPassword, finalLines)
                }
            }
        }

        finalLines
    }

    private suspend fun downloadPlaylist(url: String): List<String> {
        val response = HttpClientProvider.client.get(url) {
            header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        }
        val text = response.bodyAsText()
        return text.lines().filter { it.isNotBlank() }
    }

    private fun parseChannelEntries(lines: List<String>): List<List<String>> {
        val entries = mutableListOf<List<String>>()
        var currentEntry = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXTM3U")) continue 

            if (trimmed.startsWith("#EXTINF:")) {
                if (currentEntry.isNotEmpty()) {
                    // If we hit a new EXTINF but haven't finished the previous one (no URL?), 
                    // we might want to discard or keep. The python logic discards if no URL found.
                    // Here we just push what we have if it looks like an entry.
                    // But typically an entry ends with a URL.
                    // Let's follow the python logic: "if current_entry: current_entry = [line]"
                    // Wait, python logic appends to entries ONLY when it finds a non-tag line (URL).
                }
                currentEntry = mutableListOf(line)
            } else if (currentEntry.isNotEmpty()) {
                currentEntry.add(line)
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    // It's a URL, entry complete
                    entries.add(currentEntry)
                    currentEntry = mutableListOf()
                }
            }
        }
        return entries
    }

    private fun processEntry(
        lines: List<String>,
        shouldProxy: Boolean,
        baseUrl: String,
        apiPassword: String?,
        output: MutableList<String>
    ) {
        for (line in lines) {
            if (line.startsWith("#") || line.isBlank()) {
                output.add(line)
            } else {
                // It's a URL
                if (shouldProxy) {
                    output.add(rewriteUrl(line, baseUrl, apiPassword))
                } else {
                    output.add(line)
                }
            }
        }
    }

    private fun rewriteUrl(url: String, baseUrl: String, apiPassword: String?): String {
        if (url.contains("pluto.tv")) return url
        
        val encodedUrl = URLEncoder.encode(url, StandardCharsets.UTF_8.toString())
        var newUrl = "$baseUrl/proxy/hls/manifest.m3u8?d=$encodedUrl"
        
        if (url.contains(".mpd")) {
             newUrl = "$baseUrl/proxy/mpd/manifest.m3u8?d=$encodedUrl"
        } else if (url.contains("vixsrc.to")) {
             newUrl = "$baseUrl/proxy/stream?d=$encodedUrl&redirect_stream=true" // Simplified logic
        }

        if (apiPassword != null) {
            newUrl += "&api_password=$apiPassword"
        }
        return newUrl
    }

    data class PlaylistData(
        val lines: List<String>,
        val shouldSort: Boolean,
        val shouldProxy: Boolean,
        val originalUrl: String,
        val isError: Boolean = false
    )
}
