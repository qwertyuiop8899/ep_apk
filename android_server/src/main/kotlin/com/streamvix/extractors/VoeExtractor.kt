package com.streamvix.extractors

import com.streamvix.models.ExtractionResult
import com.streamvix.models.ExtractorError
import com.streamvix.utils.HttpClientProvider
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*
import java.net.URI
import java.util.Base64
import java.util.logging.Logger

/**
 * VOE URL extractor.
 * Estrae URL HLS da pagine VOE con deobfuscation.
 */
class VoeExtractor : Extractor {
    private val logger = Logger.getLogger(VoeExtractor::class.java.name)

    private val baseHeaders = mapOf(
        "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    )

    private val voeDomains = listOf("voe.sx", "voe.to", "voe.st", "voe.eu", "voe.la", "voe-network.net")

    override fun matches(url: String): Boolean {
        return voeDomains.any { it in url.lowercase() }
    }

    override suspend fun extract(url: String): ExtractionResult {
        return extractWithRedirectCount(url, 0)
    }

    private suspend fun extractWithRedirectCount(url: String, redirectCount: Int): ExtractionResult {
        if (!matches(url) && redirectCount == 0) {
            throw ExtractorError("Not a valid VOE URL")
        }

        if (redirectCount >= 5) {
            throw ExtractorError("VOE: too many redirects")
        }

        logger.info("Starting VOE extraction for: $url (redirect: $redirectCount)")

        try {
            val response = HttpClientProvider.client.get(url) {
                baseHeaders.forEach { (key, value) -> header(key, value) }
            }

            val html = response.bodyAsText()

            // Controlla redirect JavaScript
            val redirectPattern = Regex("""window\.location\.href\s*=\s*'([^']+)'""")
            val redirectMatch = redirectPattern.find(html)
            if (redirectMatch != null) {
                val redirectUrl = redirectMatch.groupValues[1]
                logger.info("VOE redirect to: $redirectUrl")
                return extractWithRedirectCount(redirectUrl, redirectCount + 1)
            }

            // Cerca payload offuscato e URL script esterno
            val codeAndScriptPattern = Regex("""json">\["([^"]+)"]</script>\s*<script\s*src="([^"]+)""")
            val match = codeAndScriptPattern.find(html)
                ?: throw ExtractorError("VOE: unable to locate obfuscated payload or external script URL")

            val encodedPayload = match.groupValues[1]
            val scriptPath = match.groupValues[2]

            // Costruisci URL assoluto per lo script
            val scriptUrl = URI(url).resolve(scriptPath).toString()

            // Scarica script esterno
            val scriptResponse = HttpClientProvider.client.get(scriptUrl) {
                baseHeaders.forEach { (key, value) -> header(key, value) }
            }
            val scriptText = scriptResponse.bodyAsText()

            // Trova LUTs nello script
            val lutsPattern = Regex("""\[(?:'[^\w]{2}'[,\]]){1,9}""")
            val lutsMatch = lutsPattern.find(scriptText)
                ?: throw ExtractorError("VOE: unable to locate LUTs in external script")

            // Decodifica payload
            val data = voeDecode(encodedPayload, lutsMatch.value)

            val finalUrl = data["source"]?.jsonPrimitive?.content
                ?: throw ExtractorError("VOE: failed to extract video URL")

            logger.info("✅ VOE URL extracted: $finalUrl")

            val streamHeaders = baseHeaders.toMutableMap()
            streamHeaders["referer"] = url

            return ExtractionResult(
                destinationUrl = finalUrl,
                requestHeaders = streamHeaders,
                mediaflowEndpoint = "hls_proxy"
            )

        } catch (e: ExtractorError) {
            throw e
        } catch (e: Exception) {
            logger.warning("VOE extraction failed: ${e.message}")
            throw ExtractorError("VOE extraction failed: ${e.message}")
        }
    }

    /**
     * Decodifica il payload VOE offuscato.
     */
    private fun voeDecode(ct: String, luts: String): JsonObject {
        // Estrai LUTs dalla stringa
        val lutList = luts.trim('[', ']')
            .split("','")
            .map { it.trim('\'') }
            .map { escapeRegex(it) }

        // Decodifica ROT13-like (shift di 13)
        var txt = ct.map { char ->
            val x = char.code
            when {
                x in 65..90 -> ((x - 52) % 26 + 65).toChar() // Maiuscole
                x in 97..122 -> ((x - 84) % 26 + 97).toChar() // Minuscole
                else -> char
            }
        }.joinToString("")

        // Rimuovi pattern LUT
        for (lut in lutList) {
            txt = txt.replace(Regex(lut), "")
        }

        // Base64 decode prima volta
        val decoded1 = String(Base64.getDecoder().decode(txt), Charsets.UTF_8)

        // Shift di 3 caratteri indietro
        val shifted = decoded1.map { (it.code - 3).toChar() }.joinToString("")

        // Inverti e Base64 decode seconda volta
        val reversed = shifted.reversed()
        val finalJson = String(Base64.getDecoder().decode(reversed), Charsets.UTF_8)

        return Json.parseToJsonElement(finalJson).jsonObject
    }

    /**
     * Escapa caratteri speciali regex.
     */
    private fun escapeRegex(str: String): String {
        val specialChars = ".*+?^\${}()|[]\\"
        return str.map { if (it in specialChars) "\\$it" else "$it" }.joinToString("")
    }
}
