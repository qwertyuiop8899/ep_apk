package com.streamvix.extractors

import com.streamvix.models.ExtractorError

/**
 * Factory per selezionare l'estrattore appropriato in base all'URL.
 * L'ordine di registrazione è importante: gli estrattori più specifici vanno prima.
 */
class ExtractorFactory {
    private val extractors = mutableListOf<Extractor>()

    init {
        // Register all extractors (order matters - most specific first)
        extractors.add(VavooExtractor())      // vavoo.to
        extractors.add(DLHDExtractor())       // embedme.top, webhdplayer.site, daddylivehd
        extractors.add(VixSrcExtractor())     // vixsrc.to
        extractors.add(MixdropExtractor())    // mixdrop
        extractors.add(VoeExtractor())        // voe.sx, voe.to, etc.
        extractors.add(StreamtapeExtractor()) // streamtape.com
        extractors.add(DirectExtractor())     // Fallback per URL diretti
    }

    /**
     * Ottiene l'estrattore appropriato per l'URL dato.
     * Restituisce DirectExtractor come fallback se nessun altro estrattore corrisponde.
     */
    fun getExtractor(url: String): Extractor {
        return extractors.find { it.matches(url) }
            ?: DirectExtractor()
    }

    /**
     * Verifica se esiste un estrattore specifico (non DirectExtractor) per l'URL.
     */
    fun hasSpecificExtractor(url: String): Boolean {
        return extractors.dropLast(1).any { it.matches(url) }
    }

    /**
     * Lista dei nomi degli estrattori caricati.
     */
    fun getLoadedExtractorNames(): List<String> {
        return extractors.map { it::class.simpleName ?: "Unknown" }
    }
}
