package com.cyberbot.mobile.core.model

/**
 * Conteudo do QR Code gerado pelo desktop (aba "Configurar P2P").
 *
 * Formato: cyberbot://pair?v=1&c=<codigo>&n=<pc>&t=<ticket iroh>&i=<endpoint id>&d=<url>&d=<url>
 *
 * Parser em Kotlin puro (sem android.net.Uri) para poder ser testado na JVM.
 */
data class PairingPayload(
    val code: String,
    val pcName: String? = null,
    val directUrls: List<String> = emptyList(),
    val irohEndpointId: String? = null,
    val irohTicket: String? = null,
) {
    val hasIroh: Boolean = !irohTicket.isNullOrBlank()

    companion object {
        const val SCHEME = "cyberbot"
        const val HOST = "pair"

        fun parse(raw: String?): PairingPayload? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null

            val schemeEnd = text.indexOf("://")
            if (schemeEnd <= 0) return null
            if (!SCHEME.equals(text.substring(0, schemeEnd), ignoreCase = true)) return null

            val rest = text.substring(schemeEnd + 3)
            val queryStart = rest.indexOf('?')
            val authority = (if (queryStart >= 0) rest.substring(0, queryStart) else rest)
                .substringBefore('/')
                .lowercase()
            if (authority != HOST) return null

            val query = if (queryStart >= 0) rest.substring(queryStart + 1) else ""
            val params = LinkedHashMap<String, MutableList<String>>()
            query.split('&').forEach { chunk ->
                if (chunk.isEmpty()) return@forEach
                val separator = chunk.indexOf('=')
                val key = if (separator >= 0) chunk.substring(0, separator) else chunk
                val value = if (separator >= 0) chunk.substring(separator + 1) else ""
                params.getOrPut(decode(key)) { mutableListOf() }.add(decode(value))
            }

            val code = params["c"]?.firstOrNull()?.trim().orEmpty()
            if (code.length != 6 || !code.all(Char::isDigit)) return null

            return PairingPayload(
                code = code,
                pcName = params["n"]?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
                directUrls = params["d"].orEmpty()
                    .map { it.trim() }
                    .filter { it.startsWith("http") },
                irohEndpointId = params["i"]?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
                irohTicket = params["t"]?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        private fun decode(value: String): String =
            runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }
}
