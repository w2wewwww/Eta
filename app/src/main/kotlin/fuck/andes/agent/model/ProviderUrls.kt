package fuck.andes.agent.model

internal object ProviderUrls {
    private const val RETIRED_ANYROUTER_HOST = "anyrouter.top"
    private const val CURRENT_ANYROUTER_HOST = "anyrouter.win"

    /**
     * anyrouter.top now fails during the TLS ClientHello on Android and its current API
     * documentation uses anyrouter.win. Keep existing local Provider records usable without
     * requiring users to re-enter the key or recreate the model list.
     */
    fun normalizeBaseUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd < 0) return trimmed

        val authorityStart = schemeEnd + 3
        val pathStart = trimmed.indexOf('/', authorityStart)
        val authority = trimmed.substring(
            authorityStart,
            if (pathStart >= 0) pathStart else trimmed.length,
        )
        val host = authority.substringBefore(':').lowercase()
        if (host != RETIRED_ANYROUTER_HOST) return trimmed

        val replacement = authority.replaceFirst(
            RETIRED_ANYROUTER_HOST,
            CURRENT_ANYROUTER_HOST,
            ignoreCase = true,
        )
        return trimmed.replaceRange(authorityStart, authorityStart + authority.length, replacement)
    }

    fun openAiChatCompletionsUrl(baseUrl: String): String =
        appendPath(baseUrl, "chat/completions")

    fun openAiResponsesUrl(baseUrl: String): String =
        appendPath(baseUrl, "responses")

    fun openAiModelsUrl(baseUrl: String): String =
        appendPath(baseUrl, "models")

    fun anthropicMessagesUrl(baseUrl: String): String =
        appendPath(baseUrl, "v1/messages")

    fun anthropicModelsUrl(baseUrl: String): String =
        appendPath(baseUrl, "v1/models")

    private fun appendPath(baseUrl: String, path: String): String =
        "${normalizeBaseUrl(baseUrl)}/${path.trimStart('/')}"
}
