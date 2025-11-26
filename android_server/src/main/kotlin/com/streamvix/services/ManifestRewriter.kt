package com.streamvix.services

import com.streamvix.utils.HttpClientProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import java.net.URI
import java.net.URLEncoder
import java.util.logging.Logger

/**
 * Servizio per riscrivere manifest HLS (M3U8) e MPD (DASH) per passare attraverso il proxy.
 */
class ManifestRewriter {
    private val logger = Logger.getLogger(ManifestRewriter::class.java.name)

    /**
     * Riscrive un manifest HLS per proxare tutti gli URL (segmenti, chiavi, sub-manifest).
     */
    suspend fun rewriteHlsManifest(
        manifestContent: String,
        baseUrl: String,
        proxyBase: String,
        streamHeaders: Map<String, String>,
        originalChannelUrl: String = "",
        apiPassword: String? = null
    ): String {
        val lines = manifestContent.split("\n")
        val rewrittenLines = mutableListOf<String>()

        // Costruisci parametri header per passarli alle sotto-richieste
        val headerParams = buildHeaderParams(streamHeaders, apiPassword)

        for (line in lines) {
            val trimmedLine = line.trim()

            when {
                // Gestione chiavi AES-128
                trimmedLine.startsWith("#EXT-X-KEY:") && "URI=" in trimmedLine -> {
                    val rewrittenLine = rewriteKeyTag(trimmedLine, baseUrl, proxyBase, headerParams, originalChannelUrl, apiPassword)
                    rewrittenLines.add(rewrittenLine)
                }

                // Gestione sottotitoli e altri media nel tag #EXT-X-MEDIA
                trimmedLine.startsWith("#EXT-X-MEDIA:") && "URI=" in trimmedLine -> {
                    val rewrittenLine = rewriteMediaTag(trimmedLine, baseUrl, proxyBase, headerParams)
                    rewrittenLines.add(rewrittenLine)
                }

                // Gestione URL segmenti e sub-manifest
                trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#") -> {
                    val absoluteUrl = resolveUrl(baseUrl, trimmedLine)
                    val encodedUrl = URLEncoder.encode(absoluteUrl, "UTF-8")
                    val proxyUrl = "$proxyBase/proxy/manifest.m3u8?url=$encodedUrl$headerParams"
                    rewrittenLines.add(proxyUrl)
                }

                else -> {
                    rewrittenLines.add(trimmedLine)
                }
            }
        }

        return rewrittenLines.joinToString("\n")
    }

    /**
     * Riscrive un manifest MPD per proxare tutti gli URL e iniettare ClearKey se necessario.
     */
    fun rewriteMpdManifest(
        manifestContent: String,
        baseUrl: String,
        proxyBase: String,
        streamHeaders: Map<String, String>,
        clearKeyParam: String? = null,
        apiPassword: String? = null
    ): String {
        try {
            val headerParams = buildHeaderParams(streamHeaders, apiPassword)

            // Funzione per creare URL proxy
            fun createProxyUrl(relativeUrl: String): String {
                val absoluteUrl = resolveUrl(baseUrl, relativeUrl)
                val encodedUrl = URLEncoder.encode(absoluteUrl, "UTF-8")
                return "$proxyBase/proxy/mpd/manifest.m3u8?d=$encodedUrl$headerParams"
            }

            var result = manifestContent

            // Riscrive BaseURL
            val baseUrlRegex = Regex("""<BaseURL>([^<]+)</BaseURL>""")
            result = baseUrlRegex.replace(result) { match ->
                val originalUrl = match.groupValues[1]
                "<BaseURL>${createProxyUrl(originalUrl)}</BaseURL>"
            }

            // Riscrive SegmentTemplate media e initialization
            val mediaAttrRegex = Regex("""media="([^"]+)"""")
            result = mediaAttrRegex.replace(result) { match ->
                val originalUrl = match.groupValues[1]
                """media="${createProxyUrl(originalUrl)}""""
            }

            val initAttrRegex = Regex("""initialization="([^"]+)"""")
            result = initAttrRegex.replace(result) { match ->
                val originalUrl = match.groupValues[1]
                """initialization="${createProxyUrl(originalUrl)}""""
            }

            // Riscrive SegmentURL media
            val segmentMediaRegex = Regex("""<SegmentURL media="([^"]+)"""")
            result = segmentMediaRegex.replace(result) { match ->
                val originalUrl = match.groupValues[1]
                """<SegmentURL media="${createProxyUrl(originalUrl)}""""
            }

            // Iniezione ClearKey se presente
            if (clearKeyParam != null && ":" in clearKeyParam) {
                result = injectClearKey(result, proxyBase, clearKeyParam, apiPassword)
            }

            return result

        } catch (e: Exception) {
            logger.warning("Errore riscrittura MPD: ${e.message}")
            return manifestContent
        }
    }

    /**
     * Inietta ContentProtection ClearKey nel manifest MPD.
     */
    private fun injectClearKey(
        manifestContent: String,
        proxyBase: String,
        clearKeyParam: String,
        apiPassword: String?
    ): String {
        try {
            val (kidHex, _) = clearKeyParam.split(":")

            // Costruisci License URL
            var licenseUrl = "$proxyBase/license?clearkey=$clearKeyParam"
            if (apiPassword != null) {
                licenseUrl += "&api_password=$apiPassword"
            }

            // Formatta KID come GUID (8-4-4-4-12)
            val kidGuid = if (kidHex.length == 32) {
                "${kidHex.substring(0, 8)}-${kidHex.substring(8, 12)}-${kidHex.substring(12, 16)}-${kidHex.substring(16, 20)}-${kidHex.substring(20)}"
            } else kidHex

            // ContentProtection ClearKey XML
            val contentProtection = """
                <ContentProtection schemeIdUri="urn:uuid:e2719d58-a985-b3c9-781a-007147f192ec" value="ClearKey" cenc:default_KID="$kidGuid">
                    <Laurl>$licenseUrl</Laurl>
                </ContentProtection>
            """.trimIndent()

            // Trova primo AdaptationSet e inietta ContentProtection dopo il tag di apertura
            val adaptationSetRegex = Regex("""(<AdaptationSet[^>]*>)""")
            return adaptationSetRegex.replaceFirst(manifestContent) { match ->
                "${match.value}\n$contentProtection"
            }

        } catch (e: Exception) {
            logger.warning("Errore iniezione ClearKey: ${e.message}")
            return manifestContent
        }
    }

    /**
     * Riscrive il tag EXT-X-KEY per proxare la chiave AES.
     */
    private fun rewriteKeyTag(
        line: String,
        baseUrl: String,
        proxyBase: String,
        headerParams: String,
        originalChannelUrl: String,
        apiPassword: String?
    ): String {
        val uriStart = line.indexOf("URI=\"") + 5
        val uriEnd = line.indexOf("\"", uriStart)

        if (uriStart > 4 && uriEnd > uriStart) {
            val originalKeyUrl = line.substring(uriStart, uriEnd)
            val absoluteKeyUrl = resolveUrl(baseUrl, originalKeyUrl)
            val encodedKeyUrl = URLEncoder.encode(absoluteKeyUrl, "UTF-8")
            val encodedOriginalUrl = URLEncoder.encode(originalChannelUrl, "UTF-8")

            var proxyKeyUrl = "$proxyBase/key?key_url=$encodedKeyUrl&original_channel_url=$encodedOriginalUrl$headerParams"
            if (apiPassword != null) {
                proxyKeyUrl += "&api_password=$apiPassword"
            }

            logger.info("🔄 Redirected AES key: $absoluteKeyUrl -> $proxyKeyUrl")
            return line.substring(0, uriStart) + proxyKeyUrl + line.substring(uriEnd)
        }

        return line
    }

    /**
     * Riscrive il tag EXT-X-MEDIA per proxare sottotitoli e altri media.
     */
    private fun rewriteMediaTag(
        line: String,
        baseUrl: String,
        proxyBase: String,
        headerParams: String
    ): String {
        val uriStart = line.indexOf("URI=\"") + 5
        val uriEnd = line.indexOf("\"", uriStart)

        if (uriStart > 4 && uriEnd > uriStart) {
            val originalMediaUrl = line.substring(uriStart, uriEnd)
            val absoluteMediaUrl = resolveUrl(baseUrl, originalMediaUrl)
            val encodedMediaUrl = URLEncoder.encode(absoluteMediaUrl, "UTF-8")
            val proxyMediaUrl = "$proxyBase/proxy/hls/manifest.m3u8?d=$encodedMediaUrl$headerParams"

            logger.info("🔄 Redirected Media URL: $absoluteMediaUrl -> $proxyMediaUrl")
            return line.substring(0, uriStart) + proxyMediaUrl + line.substring(uriEnd)
        }

        return line
    }

    /**
     * Costruisce i parametri header da passare nelle sotto-richieste.
     */
    private fun buildHeaderParams(streamHeaders: Map<String, String>, apiPassword: String?): String {
        val params = StringBuilder()

        for ((key, value) in streamHeaders) {
            val encodedKey = URLEncoder.encode(key, "UTF-8")
            val encodedValue = URLEncoder.encode(value, "UTF-8")
            params.append("&h_$encodedKey=$encodedValue")
        }

        if (apiPassword != null) {
            params.append("&api_password=$apiPassword")
        }

        return params.toString()
    }

    /**
     * Risolve un URL relativo rispetto a un base URL.
     */
    private fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        return if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://")) {
            relativeUrl
        } else {
            try {
                URI(baseUrl).resolve(relativeUrl).toString()
            } catch (e: Exception) {
                // Fallback: concatenazione semplice
                val base = if (baseUrl.endsWith("/")) baseUrl else baseUrl.substringBeforeLast("/") + "/"
                base + relativeUrl
            }
        }
    }
}
