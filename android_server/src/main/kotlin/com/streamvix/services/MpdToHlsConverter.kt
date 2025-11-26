package com.streamvix.services

import java.net.URI
import java.net.URLEncoder
import java.util.logging.Logger
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader

/**
 * Convertitore MPD (DASH) -> HLS (M3U8) on-the-fly.
 * Genera Master Playlist e Media Playlist compatibili con HLS da manifest MPD.
 */
class MpdToHlsConverter {
    private val logger = Logger.getLogger(MpdToHlsConverter::class.java.name)

    /**
     * Genera la Master Playlist HLS dagli AdaptationSet del MPD.
     */
    fun convertMasterPlaylist(
        manifestContent: String,
        proxyBase: String,
        originalUrl: String,
        params: String
    ): String {
        try {
            val doc = parseXml(manifestContent)
            val root = doc.documentElement
            val lines = mutableListOf("#EXTM3U", "#EXT-X-VERSION:3")

            val videoSets = mutableListOf<Element>()
            val audioSets = mutableListOf<Element>()

            // Trova tutti gli AdaptationSet
            val adaptationSets = root.getElementsByTagName("AdaptationSet")
            for (i in 0 until adaptationSets.length) {
                val aset = adaptationSets.item(i) as Element
                val mimeType = aset.getAttribute("mimeType") ?: ""
                val contentType = aset.getAttribute("contentType") ?: ""

                when {
                    "video" in mimeType || "video" in contentType -> videoSets.add(aset)
                    "audio" in mimeType || "audio" in contentType -> audioSets.add(aset)
                }
            }

            // Gestione Audio (EXT-X-MEDIA)
            val audioGroupId = "audio"
            var hasAudio = false

            for (aset in audioSets) {
                val representations = aset.getElementsByTagName("Representation")
                val lang = aset.getAttribute("lang") ?: "und"

                for (j in 0 until representations.length) {
                    val rep = representations.item(j) as Element
                    val repId = rep.getAttribute("id")
                    val bandwidth = rep.getAttribute("bandwidth") ?: "128000"

                    val encodedUrl = URLEncoder.encode(originalUrl, "UTF-8")
                    val mediaUrl = "$proxyBase/proxy/hls/manifest.m3u8?d=$encodedUrl&format=hls&rep_id=$repId$params"
                    val name = "Audio $lang ($bandwidth)"
                    val defaultAttr = if (!hasAudio) "YES" else "NO"

                    lines.add("""#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="$audioGroupId",NAME="$name",LANGUAGE="$lang",DEFAULT=$defaultAttr,AUTOSELECT=YES,URI="$mediaUrl"""")
                    hasAudio = true
                }
            }

            // Gestione Video (EXT-X-STREAM-INF)
            for (aset in videoSets) {
                val representations = aset.getElementsByTagName("Representation")

                for (j in 0 until representations.length) {
                    val rep = representations.item(j) as Element
                    val repId = rep.getAttribute("id")
                    val bandwidth = rep.getAttribute("bandwidth")
                    val width = rep.getAttribute("width")
                    val height = rep.getAttribute("height")
                    val frameRate = rep.getAttribute("frameRate")
                    val codecs = rep.getAttribute("codecs")

                    val encodedUrl = URLEncoder.encode(originalUrl, "UTF-8")
                    val mediaUrl = "$proxyBase/proxy/hls/manifest.m3u8?d=$encodedUrl&format=hls&rep_id=$repId$params"

                    val infParts = mutableListOf("BANDWIDTH=$bandwidth")
                    if (width.isNotEmpty() && height.isNotEmpty()) {
                        infParts.add("RESOLUTION=${width}x$height")
                    }
                    if (frameRate.isNotEmpty()) {
                        infParts.add("FRAME-RATE=$frameRate")
                    }
                    if (codecs.isNotEmpty()) {
                        infParts.add("""CODECS="$codecs"""")
                    }
                    if (hasAudio) {
                        infParts.add("""AUDIO="$audioGroupId"""")
                    }

                    lines.add("#EXT-X-STREAM-INF:${infParts.joinToString(",")}")
                    lines.add(mediaUrl)
                }
            }

            return lines.joinToString("\n")

        } catch (e: Exception) {
            logger.warning("Errore conversione Master Playlist: ${e.message}")
            return "#EXTM3U\n#EXT-X-ERROR: ${e.message}"
        }
    }

    /**
     * Genera la Media Playlist HLS per una specifica Representation.
     */
    fun convertMediaPlaylist(
        manifestContent: String,
        repId: String,
        proxyBase: String,
        originalUrl: String,
        params: String,
        clearKeyParam: String? = null
    ): String {
        try {
            val doc = parseXml(manifestContent)
            val root = doc.documentElement
            val lines = mutableListOf(
                "#EXTM3U",
                "#EXT-X-VERSION:7",
                "#EXT-X-TARGETDURATION:10",
                "#EXT-X-PLAYLIST-TYPE:VOD"
            )

            // Trova la Representation specifica
            var representation: Element? = null
            var adaptationSet: Element? = null

            val adaptationSets = root.getElementsByTagName("AdaptationSet")
            outer@ for (i in 0 until adaptationSets.length) {
                val aset = adaptationSets.item(i) as Element
                val reps = aset.getElementsByTagName("Representation")
                for (j in 0 until reps.length) {
                    val rep = reps.item(j) as Element
                    if (rep.getAttribute("id") == repId) {
                        representation = rep
                        adaptationSet = aset
                        break@outer
                    }
                }
            }

            if (representation == null) {
                logger.warning("Representation $repId non trovata")
                return "#EXTM3U\n#EXT-X-ERROR: Representation not found"
            }

            // Gestione DRM (ClearKey)
            var serverSideDecryption = false
            var decryptionParams = ""
            if (clearKeyParam != null && ":" in clearKeyParam) {
                val (kidHex, keyHex) = clearKeyParam.split(":")
                serverSideDecryption = true
                decryptionParams = "&key=$keyHex&key_id=$kidHex"
            }

            // Trova SegmentTemplate
            var segmentTemplate = representation.getElementsByTagName("SegmentTemplate").let {
                if (it.length > 0) it.item(0) as Element else null
            }
            if (segmentTemplate == null) {
                segmentTemplate = adaptationSet!!.getElementsByTagName("SegmentTemplate").let {
                    if (it.length > 0) it.item(0) as Element else null
                }
            }

            if (segmentTemplate != null) {
                val timescale = segmentTemplate.getAttribute("timescale")?.toIntOrNull() ?: 1
                val initialization = segmentTemplate.getAttribute("initialization")
                val media = segmentTemplate.getAttribute("media")
                val startNumber = segmentTemplate.getAttribute("startNumber")?.toIntOrNull() ?: 1

                // Base URL
                val baseUrlElements = root.getElementsByTagName("BaseURL")
                var baseUrl = if (baseUrlElements.length > 0) baseUrlElements.item(0).textContent else ""
                if (baseUrl.isEmpty()) {
                    baseUrl = originalUrl.substringBeforeLast("/") + "/"
                }
                if (!baseUrl.endsWith("/")) baseUrl += "/"

                // Initialization segment
                var encodedInitUrl = ""
                if (initialization != null && initialization.isNotEmpty()) {
                    val initUrl = initialization.replace("\$RepresentationID\$", repId)
                    val fullInitUrl = resolveUrl(baseUrl, initUrl)
                    encodedInitUrl = URLEncoder.encode(fullInitUrl, "UTF-8")

                    if (!serverSideDecryption) {
                        val proxyInitUrl = "$proxyBase/segment/init.mp4?base_url=$encodedInitUrl$params"
                        lines.add("""#EXT-X-MAP:URI="$proxyInitUrl"""")
                    }
                }

                // SegmentTimeline
                val segmentTimeline = segmentTemplate.getElementsByTagName("SegmentTimeline").let {
                    if (it.length > 0) it.item(0) as Element else null
                }

                if (segmentTimeline != null) {
                    val sElements = segmentTimeline.getElementsByTagName("S")
                    var currentTime = 0L
                    var segmentNumber = startNumber

                    for (k in 0 until sElements.length) {
                        val s = sElements.item(k) as Element
                        val t = s.getAttribute("t")?.toLongOrNull()
                        if (t != null) currentTime = t

                        val d = s.getAttribute("d").toLong()
                        val r = s.getAttribute("r")?.toIntOrNull() ?: 0
                        val durationSec = d.toDouble() / timescale

                        repeat(r + 1) {
                            var segName = media
                                .replace("\$RepresentationID\$", repId)
                                .replace("\$Number\$", segmentNumber.toString())
                                .replace("\$Time\$", currentTime.toString())

                            val fullSegUrl = resolveUrl(baseUrl, segName)
                            val encodedSegUrl = URLEncoder.encode(fullSegUrl, "UTF-8")

                            lines.add("#EXTINF:${String.format("%.3f", durationSec)},")

                            if (serverSideDecryption) {
                                val decryptUrl = "$proxyBase/decrypt/segment.mp4?url=$encodedSegUrl&init_url=$encodedInitUrl$decryptionParams$params"
                                lines.add(decryptUrl)
                            } else {
                                val proxySegUrl = "$proxyBase/segment/$segName?base_url=$encodedSegUrl$params"
                                lines.add(proxySegUrl)
                            }

                            currentTime += d
                            segmentNumber++
                        }
                    }
                }
            }

            lines.add("#EXT-X-ENDLIST")
            return lines.joinToString("\n")

        } catch (e: Exception) {
            logger.warning("Errore conversione Media Playlist: ${e.message}")
            return "#EXTM3U\n#EXT-X-ERROR: ${e.message}"
        }
    }

    private fun parseXml(content: String): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
        return builder.parse(InputSource(StringReader(content)))
    }

    private fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        return if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://")) {
            relativeUrl
        } else {
            try {
                URI(baseUrl).resolve(relativeUrl).toString()
            } catch (e: Exception) {
                val base = if (baseUrl.endsWith("/")) baseUrl else baseUrl.substringBeforeLast("/") + "/"
                base + relativeUrl
            }
        }
    }
}
